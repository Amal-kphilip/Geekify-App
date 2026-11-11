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
import kotlinx.coroutines.launch
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a video to a stream using the InnerTubeX extractor while preserving
 * Geekify's existing Media3 service, queue, and stable on-disk cache-key scheme.
 */
@Singleton
class StreamResolver internal constructor(
    private val extractor: InnerTubePlaybackExtractor?,
    private val clock: () -> Long,
    private val customFetcher: (suspend (String) -> ResolvedStream)?
) : InnerTubeXPlayer {

    @Inject
    constructor(extractor: InnerTubePlaybackExtractor) : this(
        extractor = extractor,
        clock = System::currentTimeMillis,
        customFetcher = null
    )
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
        const val MAX_CONCURRENT_PREFETCHES = 2
    }

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, ResolvedStream>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedStream>): Boolean =
            size > MAX_CACHE_ENTRIES
    }
    private val inFlight = HashMap<String, Deferred<ResolvedStream>>()
    private val prefetchJobs = HashMap<String, Job>()
    private val prefetchOnlyInFlight = HashSet<String>()
    private var lastPrefetchSignature: List<String> = emptyList()
    private val generations = HashMap<String, Long>()
    private val byUrl = object : LinkedHashMap<String, ResolvedStream>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedStream>): Boolean =
            size > MAX_URL_METADATA_ENTRIES
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun resolve(videoId: String, forceRefresh: Boolean): ResolvedStream =
        resolveInternal(videoId = videoId, forceRefresh = forceRefresh, initiatedByPrefetch = false)

    private suspend fun resolveInternal(
        videoId: String,
        forceRefresh: Boolean,
        initiatedByPrefetch: Boolean
    ): ResolvedStream {
        val now = clock()
        val job: Deferred<ResolvedStream> = synchronized(lock) {
            if (!initiatedByPrefetch) {
                // Promote any in-flight prefetch to a foreground playback resolution so queue changes never cancel it.
                prefetchOnlyInFlight.remove(videoId)
            }
            if (!forceRefresh) {
                val cached = cache[videoId]
                if (cached != null && cached.expiresAt > now) {
                    byUrl[cached.url] = cached
                    return cached
                }
                if (cached != null) {
                    cache.remove(videoId)
                    byUrl.remove(cached.url)
                }
                inFlight[videoId]?.takeIf { it.isActive }?.let { return@synchronized it }
            } else {
                val previous = cache.remove(videoId)
                previous?.let { byUrl.remove(it.url) }
                prefetchJobs.remove(videoId)?.cancel()
                prefetchOnlyInFlight.remove(videoId)
                inFlight.remove(videoId)?.cancel()
            }

            if (initiatedByPrefetch) {
                prefetchOnlyInFlight.add(videoId)
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
                        if (inFlight[videoId] === completedJob) {
                            inFlight.remove(videoId)
                            prefetchOnlyInFlight.remove(videoId)
                        }
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
        val now = clock()
        val normalizedTargets = videoIds.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_CONCURRENT_PREFETCHES)
            .toList()
        val targetSet = normalizedTargets.toSet()

        val toPrefetch = synchronized(lock) {
            // Cancel obsolete prefetches for tracks no longer in the upcoming window
            val obsoleteIds = prefetchJobs.keys.filter { it !in targetSet }
            for (obsoleteId in obsoleteIds) {
                prefetchJobs.remove(obsoleteId)?.cancel()
                if (prefetchOnlyInFlight.remove(obsoleteId)) {
                    inFlight.remove(obsoleteId)?.cancel()
                    generations[obsoleteId] = (generations[obsoleteId] ?: 0L) + 1L
                }
            }

            if (normalizedTargets == lastPrefetchSignature && normalizedTargets.all { id ->
                    val cached = cache[id]
                    (cached != null && cached.expiresAt > now) || inFlight[id]?.isActive == true
                }
            ) {
                return
            }
            lastPrefetchSignature = normalizedTargets

            normalizedTargets.filter { id ->
                val cached = cache[id]
                val validCached = cached != null && cached.expiresAt > now
                val activeInFlight = inFlight[id]?.isActive == true
                !validCached && !activeInFlight
            }
        }
        toPrefetch.forEach { id ->
            val job = scope.launch {
                try {
                    runCatching {
                        resolveInternal(videoId = id, forceRefresh = false, initiatedByPrefetch = true)
                    }
                } finally {
                    val currentJob = currentCoroutineContext()[Job]
                    synchronized(lock) {
                        if (prefetchJobs[id] === currentJob) {
                            prefetchJobs.remove(id)
                        }
                    }
                }
            }
            synchronized(lock) {
                prefetchJobs[id] = job
            }
        }
    }

    override fun invalidate(videoId: String) {
        synchronized(lock) {
            cache.remove(videoId)?.let { byUrl.remove(it.url) }
            prefetchJobs.remove(videoId)?.cancel()
            prefetchOnlyInFlight.remove(videoId)
            inFlight.remove(videoId)?.cancel()
            generations[videoId] = (generations[videoId] ?: 0L) + 1L
        }
    }

    override fun clearCache() {
        synchronized(lock) {
            cache.clear()
            byUrl.clear()
            prefetchJobs.values.forEach { it.cancel() }
            prefetchJobs.clear()
            prefetchOnlyInFlight.clear()
            lastPrefetchSignature = emptyList()
            inFlight.values.forEach { it.cancel() }
            inFlight.clear()
            generations.keys.toList().forEach { id ->
                generations[id] = (generations[id] ?: 0L) + 1L
            }
        }
    }

    override fun markFailed(videoId: String) {
        val failed = synchronized(lock) {
            val stream = cache.remove(videoId)
            stream?.let { byUrl.remove(it.url) }
            prefetchJobs.remove(videoId)?.cancel()
            prefetchOnlyInFlight.remove(videoId)
            inFlight.remove(videoId)?.cancel()
            generations[videoId] = (generations[videoId] ?: 0L) + 1L
            stream
        }
        failed?.clientName?.takeIf(String::isNotBlank)?.let { clientName ->
            extractor?.markStreamClientFailed(videoId, clientName)
            // A 403 frequently means the remote player config/cipher changed. Refresh it off the
            // playback thread; the next bounded retry uses another client and/or fresh config.
            scope.launch { runCatching { extractor?.refreshAfterStreamRejection() } }
        }
    }

    override suspend fun warmUp() {
        try {
            extractor?.prewarm()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Warm-up is opportunistic. A failed prewarm should not prevent playback from trying.
        }
    }

    @OptIn(UnstableApi::class)
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val url = dataSpec.uri.toString()
        val now = clock()
        val stream = synchronized(lock) {
            val found = byUrl[url] ?: return dataSpec
            if (found.expiresAt <= now) {
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
            customFetcher?.let { return it(videoId) }
            val ext = requireNotNull(extractor) { "InnerTubePlaybackExtractor is required when customFetcher is null" }
            val stream = ext.resolve(videoId)
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
        } catch (error: StreamException) {
            throw error
        } catch (error: Exception) {
            throw StreamException(error.message ?: "Could not resolve a playable stream.", error)
        }
    }

    class StreamException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
