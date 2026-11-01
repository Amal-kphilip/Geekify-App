package com.geekify.android.notifications

import com.geekify.android.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Looks for a newer GitHub release (the project's existing distribution channel - the same repo
 * [com.geekify.android.update.AppUpdateManager] uses for the in-app update dialog).
 * Network is touched at most once per [CHECK_INTERVAL_MS], uses `If-None-Match` ETags so `304 Not Modified`
 * responses do not consume GitHub API quota, and respects `X-RateLimit-Reset` / `Retry-After` headers.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val http: OkHttpClient,
    private val store: NotificationStore,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    companion object {
        const val CHECK_INTERVAL_MS = 12 * NotificationPolicy.HOUR_MS
        const val DEFAULT_RATE_LIMIT_BACKOFF_MS = NotificationPolicy.HOUR_MS
        private const val LATEST_URL = "https://api.github.com/repos/Amal-kphilip/Geekify-App/releases/latest"

        fun parseRateLimitResetAtMs(response: Response, nowMs: Long): Long? {
            val remaining = response.header("X-RateLimit-Remaining")?.toIntOrNull()
            if (response.code != 403 && response.code != 429 && (remaining == null || remaining > 0)) {
                return null
            }
            val retryAfterSec = response.header("Retry-After")?.toLongOrNull()
            if (retryAfterSec != null && retryAfterSec > 0L) {
                return nowMs + retryAfterSec * 1_000L
            }
            val resetEpochSec = response.header("X-RateLimit-Reset")?.toLongOrNull()
            if (resetEpochSec != null && resetEpochSec * 1_000L > nowMs) {
                return resetEpochSec * 1_000L
            }
            return if (response.code == 403 || response.code == 429) {
                nowMs + DEFAULT_RATE_LIMIT_BACKOFF_MS
            } else {
                null
            }
        }
    }

    private sealed interface FetchOutcome {
        data class Updated(val release: ReleaseInfo, val etag: String?) : FetchOutcome
        data class NotModified(val etag: String?) : FetchOutcome
        data class RateLimited(val resetAtMs: Long) : FetchOutcome
        data object Failed : FetchOutcome
    }

    /** Refreshes the cached latest release when it is older than the interval. Failure keeps the old cache. */
    suspend fun refreshIfDue(now: Long): NotificationState {
        val cached = store.stateNow()
        if (now < cached.rateLimitResetAt) return cached
        if (now - cached.lastCheckedAt < CHECK_INTERVAL_MS) return cached
        return when (val outcome = fetchLatest(cached.latestReleaseEtag, now)) {
            is FetchOutcome.Updated -> {
                store.updateState {
                    it.copy(
                        lastCheckedAt = now,
                        latestVersionCode = outcome.release.versionCode,
                        latestVersionName = outcome.release.versionName,
                        latestReleaseUrl = outcome.release.pageUrl,
                        latestReleaseEtag = outcome.etag ?: it.latestReleaseEtag,
                        rateLimitResetAt = 0L
                    )
                }
                store.stateNow()
            }
            is FetchOutcome.NotModified -> {
                store.updateState {
                    it.copy(
                        lastCheckedAt = now,
                        latestReleaseEtag = outcome.etag ?: it.latestReleaseEtag,
                        rateLimitResetAt = 0L
                    )
                }
                store.stateNow()
            }
            is FetchOutcome.RateLimited -> {
                store.updateState {
                    it.copy(rateLimitResetAt = outcome.resetAtMs)
                }
                store.stateNow()
            }
            FetchOutcome.Failed -> cached
        }
    }

    private suspend fun fetchLatest(etag: String?, nowMs: Long): FetchOutcome = withContext(io) {
        try {
            val requestBuilder = Request.Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Geekify-Android-Updater")
            if (!etag.isNullOrBlank()) {
                requestBuilder.header("If-None-Match", etag)
            }
            http.newCall(requestBuilder.build()).execute().use { r ->
                val resetAt = parseRateLimitResetAtMs(r, nowMs)
                when {
                    r.code == 304 -> FetchOutcome.NotModified(r.header("ETag") ?: etag)
                    resetAt != null && !r.isSuccessful -> FetchOutcome.RateLimited(resetAt)
                    !r.isSuccessful -> FetchOutcome.Failed
                    else -> {
                        val parsed = ReleaseParser.parse(r.body.string()) ?: return@use FetchOutcome.Failed
                        FetchOutcome.Updated(parsed, r.header("ETag"))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            FetchOutcome.Failed
        }
    }
}
