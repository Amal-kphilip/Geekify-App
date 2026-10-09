package com.geekify.android.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.geekify.android.player.extraction.InnerTubePlaybackExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a video to a stream using the InnerTubeX extractor while preserving
 * Geekify's existing Media3 service, queue, and stable on-disk cache-key scheme.
 */
@Singleton
class StreamResolver @Inject constructor(
    private val extractor: InnerTubePlaybackExtractor
) : InnerTubeXPlayer {
    data class ResolvedStream(
        val url: String,
        val mimeType: String,
        val expiresAt: Long,
        val loudnessDb: Float? = null,
        val itag: Int? = null,
        val requestHeaders: Map<String, String> = emptyMap(),
        val clientName: String = "",
        val requireBoundedRange: Boolean = false,
        val rangeChunkSizeBytes: Long = 0L,
        val useRangeChunks: Boolean = false
    )

    private companion object {
        const val MAX_CACHE_ENTRIES = 500
        const val MAX_URL_METADATA_ENTRIES = 500
    }

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, ResolvedStream>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedStream>): Boolean =
            size > MAX_CACHE_ENTRIES
    }
    private val inFlight = HashMap<String, Deferred<ResolvedStream>>()
    private val generations = HashMap<String, Long>()
    private val byUrl = object : LinkedHashMap<String, ResolvedStream>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedStream>): Boolean =
            size > MAX_URL_METADATA_ENTRIES
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun resolve(videoId: String, forceRefresh: Boolean): ResolvedStream {
        val job: Deferred<ResolvedStream> = synchronized(lock) {
            if (!forceRefresh) {
                val cached = cache[videoId]
                if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
                    byUrl[cached.url] = cached
                    return cached
                }
                if (cached != null) cache.remove(videoId)
                inFlight[videoId]?.takeIf { it.isActive }?.let { return@synchronized it }
            } else {
                val previous = cache.remove(videoId)
                previous?.let { byUrl.remove(it.url) }
            }

            val generation = (generations[videoId] ?: 0L) + 1L
            generations[videoId] = generation
            val created = scope.async(start = CoroutineStart.LAZY) {
                try {
                    doResolve(videoId).also { resolved ->
                        synchronized(lock) {
                            // An older request may finish after a force-refresh. Never let it replace
                            // the newer stream or its request headers.
                            if (generations[videoId] == generation) {
                                cache[videoId] = resolved
                                byUrl[resolved.url] = resolved
                            }
                        }
                    }
                } finally {
                    val completedJob = currentCoroutineContext()[Job]
                    synchronized(lock) {
                        if (inFlight[videoId] === completedJob) inFlight.remove(videoId)
                    }
                }
            }
            inFlight[videoId] = created
            created.start()
            created
        }
        return job.await()
    }

    override fun prefetch(videoIds: List<String>) {
        videoIds.distinct().forEach { id -> scope.launch { runCatching { resolve(id) } } }
    }

    override fun invalidate(videoId: String) {
        synchronized(lock) {
            cache.remove(videoId)?.let { byUrl.remove(it.url) }
            generations[videoId] = (generations[videoId] ?: 0L) + 1L
        }
    }

    override fun markFailed(videoId: String) {
        val failed = synchronized(lock) {
            val stream = cache.remove(videoId)
            stream?.let { byUrl.remove(it.url) }
            generations[videoId] = (generations[videoId] ?: 0L) + 1L
            stream
        }
        failed?.clientName?.takeIf(String::isNotBlank)?.let { clientName ->
            extractor.markStreamClientFailed(videoId, clientName)
            // A 403 frequently means the remote player config/cipher changed. Refresh it off the
            // playback thread; the next bounded retry uses another client and/or fresh config.
            scope.launch { runCatching { extractor.refreshAfterStreamRejection() } }
        }
    }

    override suspend fun warmUp() {
        try {
            extractor.prewarm()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Warm-up is opportunistic. A failed prewarm should not prevent playback from trying.
        }
    }

    @OptIn(UnstableApi::class)
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val url = dataSpec.uri.toString()
        val stream = synchronized(lock) {
            val found = byUrl[url] ?: return dataSpec
            if (found.expiresAt <= System.currentTimeMillis()) {
                byUrl.remove(url)
                return dataSpec
            }
            found
        }
        var resolved = dataSpec.withRequestHeaders(dataSpec.httpRequestHeaders + stream.requestHeaders)
        if ((!stream.requireBoundedRange && !stream.useRangeChunks) || stream.rangeChunkSizeBytes <= 0L) {
            return resolved
        }
        val boundedLength = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            stream.rangeChunkSizeBytes
        } else {
            minOf(dataSpec.length, stream.rangeChunkSizeBytes)
        }
        if (boundedLength <= 0L) return resolved
        resolved = resolved.subrange(0L, boundedLength)
        return resolved
    }

    private suspend fun doResolve(videoId: String): ResolvedStream {
        try {
            val stream = extractor.resolve(videoId)
            return ResolvedStream(
                url = stream.url,
                mimeType = stream.mimeType,
                expiresAt = stream.expiresAtMs,
                loudnessDb = stream.loudnessDb,
                itag = stream.itag,
                requestHeaders = stream.requestHeaders,
                clientName = stream.clientName,
                requireBoundedRange = stream.requireBoundedRange,
                rangeChunkSizeBytes = stream.rangeChunkSizeBytes,
                useRangeChunks = stream.useRangeChunks
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw StreamException(error.message ?: "Could not resolve a playable stream.", error)
        }
    }

    class StreamException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
