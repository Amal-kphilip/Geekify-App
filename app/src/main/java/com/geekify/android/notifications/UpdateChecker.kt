package com.geekify.android.notifications

import com.geekify.android.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Looks for a newer GitHub release (the project's existing distribution channel - the same repo
 * [com.geekify.android.update.AppUpdateManager] uses for the in-app update dialog).
 * Network is touched at most once per [CHECK_INTERVAL_MS]; in between the cached result in [NotificationStore] is used.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val http: OkHttpClient,
    private val store: NotificationStore,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    companion object {
        const val CHECK_INTERVAL_MS = 12 * NotificationPolicy.HOUR_MS
        private const val LATEST_URL = "https://api.github.com/repos/Amal-kphilip/Geekify-App/releases/latest"
    }

    /** Refreshes the cached latest release when it is older than the interval. Failure keeps the old cache. */
    suspend fun refreshIfDue(now: Long): NotificationState {
        val cached = store.stateNow()
        if (now - cached.lastCheckedAt < CHECK_INTERVAL_MS) return cached
        val release = fetchLatest() ?: return cached
        store.updateState {
            it.copy(
                lastCheckedAt = now,
                latestVersionCode = release.versionCode,
                latestVersionName = release.versionName,
                latestReleaseUrl = release.pageUrl
            )
        }
        return store.stateNow()
    }

    private suspend fun fetchLatest(): ReleaseInfo? = withContext(io) {
        try {
            val request = Request.Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Geekify-Android-Updater")
                .build()
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) null else ReleaseParser.parse(r.body.string())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
