package com.geekify.android.downloads

import android.content.Context
import android.net.Uri
import com.geekify.android.data.local.CachedAudioDao
import com.geekify.android.data.local.CachedAudioEntity
import com.geekify.android.data.model.Track
import com.geekify.android.innertube.YouTube
import com.geekify.android.player.StreamResolver
import com.geekify.android.player.extraction.InnerTubePlaybackExtractor
import com.geekify.android.player.sabr.sabrAudioPlaybackChunks
import com.geekify.android.player.sabr.sabrStreamingTokenSupplier
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Manages two strictly separated audio storage pools:
 * 1. [CachedAudioEntity.KIND_CACHE] (`files/audio_cache`) — LRU-evicted streaming cache.
 * 2. [CachedAudioEntity.KIND_DOWNLOAD] (`files/downloads`) — permanent offline library entries.
 */
class AudioCacheManager(
    private val context: Context,
    private val dao: CachedAudioDao,
    private val http: OkHttpClient,
    private val extractor: InnerTubePlaybackExtractor
) {
    data class StorageStats(
        val cacheBytes: Long,
        val cacheCount: Int,
        val downloadBytes: Long,
        val downloadCount: Int
    )

    private val cacheDir = File(context.filesDir, "audio_cache").apply { mkdirs() }
    private val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
    private val stagingDir = File(context.cacheDir, "download_staging").apply { mkdirs() }
    private val activePlaybackIds = ConcurrentHashMap.newKeySet<String>()
    private val writeLocks = ConcurrentHashMap<String, Mutex>()

    private fun safeFileKey(videoId: String): String {
        val sanitized = videoId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
        require(sanitized.isNotBlank()) { "Invalid videoId for file storage" }
        return sanitized
    }

    private fun isInsideManagedDirectories(file: File): Boolean = runCatching {
        val canonical = file.canonicalPath
        canonical.startsWith(downloadsDir.canonicalPath) ||
            canonical.startsWith(cacheDir.canonicalPath) ||
            canonical.startsWith(stagingDir.canonicalPath)
    }.getOrDefault(false)

    @Volatile
    var maxCacheBytes: Long = 512L * 1024 * 1024 // 512 MB default LRU cache cap

    fun markPlaybackActive(videoId: String) {
        activePlaybackIds.add(videoId)
    }

    fun markPlaybackInactive(videoId: String) {
        activePlaybackIds.remove(videoId)
    }

    fun observeDownloads(): Flow<List<CachedAudioEntity>> =
        dao.observeByKind(CachedAudioEntity.KIND_DOWNLOAD)

    /**
     * Checks permanent downloads and valid cached audio for [videoId].
     * Returns a local `file://` [StreamResolver.ResolvedStream] so offline playback works without network.
     */
    suspend fun resolveLocalStream(videoId: String): StreamResolver.ResolvedStream? = withContext(Dispatchers.IO) {
        val entry = dao.find(videoId) ?: return@withContext null
        val file = File(entry.filePath)
        if (!isInsideManagedDirectories(file) || !file.isFile || file.length() <= 0L) {
            // Corrupt or missing file on disk: self-heal DB index
            dao.delete(videoId)
            return@withContext null
        }
        dao.touch(videoId)
        StreamResolver.ResolvedStream(
            url = Uri.fromFile(file).toString(),
            mimeType = entry.mimeType,
            expiresAt = Long.MAX_VALUE,
            clientName = "LOCAL_${entry.kind}",
            isLocalFile = true,
            contentLength = file.length()
        )
    }

    suspend fun downloadToPermanentStorage(
        track: Track,
        stream: StreamResolver.ResolvedStream,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): CachedAudioEntity = withContext(Dispatchers.IO) {
        val lock = writeLocks.computeIfAbsent(track.videoId) { Mutex() }
        lock.withLock {
            // If already permanently downloaded and intact, return immediately
            val existing = dao.find(track.videoId)
            if (existing != null && existing.kind == CachedAudioEntity.KIND_DOWNLOAD) {
                val f = File(existing.filePath)
                if (f.isFile && f.length() > 0L) {
                    onProgress(f.length(), f.length())
                    return@withLock existing
                }
            }

            val safeKey = safeFileKey(track.videoId)
            // Cache-to-download promotion: reuse already-cached bytes without re-downloading
            if (existing != null && existing.kind == CachedAudioEntity.KIND_CACHE) {
                val cachedFile = File(existing.filePath)
                if (isInsideManagedDirectories(cachedFile) && cachedFile.isFile && cachedFile.length() > 0L) {
                    val destFile = File(downloadsDir, "$safeKey.${extensionForMime(existing.mimeType)}")
                    cachedFile.copyTo(destFile, overwrite = true)
                    val promoted = existing.copy(
                        kind = CachedAudioEntity.KIND_DOWNLOAD,
                        filePath = destFile.absolutePath,
                        contentLength = destFile.length(),
                        title = track.title,
                        artist = track.artist,
                        thumbnailUrl = track.thumbnails.lastOrNull()?.url,
                        lastAccessedAt = System.currentTimeMillis()
                    )
                    dao.upsert(promoted)
                    cachedFile.delete()
                    onProgress(destFile.length(), destFile.length())
                    return@withLock promoted
                }
            }

            val ext = extensionForMime(stream.mimeType)
            val tempFile = File(stagingDir, "${safeKey}_${System.currentTimeMillis()}.part")
            val destFile = File(downloadsDir, "$safeKey.$ext")

            try {
                if (stream.isHls && stream.sabrBootstrap == null) {
                    throw IOException("HLS playlist manifests cannot be downloaded directly as raw audio")
                }
                val writtenBytes = if (stream.sabrBootstrap != null) {
                    downloadSabrToFile(track.videoId, stream, tempFile, onProgress)
                } else {
                    downloadHttpToFile(stream, tempFile, onProgress)
                }

                if (writtenBytes <= 0L || !tempFile.isFile || tempFile.length() <= 0L) {
                    throw IOException("Downloaded file is empty or incomplete")
                }

                if (destFile.exists()) destFile.delete()
                if (!tempFile.renameTo(destFile)) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }

                val entity = CachedAudioEntity(
                    videoId = track.videoId,
                    kind = CachedAudioEntity.KIND_DOWNLOAD,
                    filePath = destFile.absolutePath,
                    mimeType = stream.mimeType,
                    contentLength = destFile.length(),
                    durationMs = (track.durationSeconds ?: 0) * 1000L,
                    title = track.title,
                    artist = track.artist,
                    thumbnailUrl = track.thumbnails.lastOrNull()?.url
                )
                dao.upsert(entity)
                entity
            } catch (ce: CancellationException) {
                runCatching { tempFile.delete() }
                throw ce
            } catch (t: Throwable) {
                runCatching { tempFile.delete() }
                throw t
            }
        }
    }

    private suspend fun downloadHttpToFile(
        stream: StreamResolver.ResolvedStream,
        tempFile: File,
        onProgress: suspend (Long, Long) -> Unit
    ): Long {
        val chunkSize = (stream.rangeChunkSizeBytes ?: DEFAULT_RANGE_CHUNK_BYTES).toLong()
            .coerceIn(256L * 1024L, 2L * 1024L * 1024L)
        var downloaded = 0L
        var total = stream.contentLength.takeIf { it > 0L } ?: -1L
        var lastReportedAt = 0L

        java.io.BufferedOutputStream(FileOutputStream(tempFile), 64 * 1024).use { output ->
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val rangeStart = downloaded
                val rangeEnd = if (total > 0L) {
                    minOf(rangeStart + chunkSize - 1L, total - 1L)
                } else {
                    rangeStart + chunkSize - 1L
                }
                if (total > 0L && rangeStart >= total) break

                val reqBuilder = Request.Builder().url(stream.url)
                stream.requestHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
                reqBuilder.header("Range", "bytes=$rangeStart-$rangeEnd")

                val call = http.newCall(reqBuilder.build())
                val cancelHandle = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { cause ->
                    if (cause != null) call.cancel()
                }
                try {
                    call.execute().use { res ->
                        if (!res.isSuccessful) {
                            throw IOException("Download HTTP failed with status ${res.code}")
                        }
                        val body = res.body ?: throw IOException("Empty download response body")
                        if (total <= 0L) {
                            val contentRange = res.header("Content-Range")
                            val totalFromRange = contentRange
                                ?.substringAfterLast('/', "")
                                ?.trim()
                                ?.toLongOrNull()
                                ?.takeIf { it > 0L }
                            total = totalFromRange ?: body.contentLength().takeIf { it > 0L } ?: -1L
                        }
                        val isPartial = res.code == 206
                        var chunkBytesRead = 0L
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                val read = input.read(buf)
                                if (read < 0) break
                                output.write(buf, 0, read)
                                downloaded += read
                                chunkBytesRead += read
                                val now = System.currentTimeMillis()
                                if (now - lastReportedAt >= 250L || (total > 0L && downloaded >= total)) {
                                    lastReportedAt = now
                                    onProgress(downloaded, total)
                                }
                            }
                        }
                        if (!isPartial || chunkBytesRead == 0L || (total > 0L && downloaded >= total)) {
                            output.flush()
                            onProgress(downloaded, if (total > 0L) total else downloaded)
                            return downloaded
                        }
                    }
                } finally {
                    cancelHandle?.dispose()
                }
            }
            output.flush()
        }
        onProgress(downloaded, if (total > 0L) total else downloaded)
        return downloaded
    }

    private suspend fun downloadSabrToFile(
        videoId: String,
        stream: StreamResolver.ResolvedStream,
        tempFile: File,
        onProgress: suspend (Long, Long) -> Unit
    ): Long {
        val bootstrap = requireNotNull(stream.sabrBootstrap)
        val transport = YouTube.extractionTransport()
        val tokenSupplier = sabrStreamingTokenSupplier(
            bootstrap = bootstrap,
            profileId = stream.clientName,
            innerTube = transport.innerTube,
            provider = extractor.tokenProvider
        )
        var downloaded = 0L
        val total = stream.contentLength
        val syntheticPositionMs = java.util.concurrent.atomic.AtomicLong(0L)
        var lastReportedAt = 0L
        java.io.BufferedOutputStream(FileOutputStream(tempFile), 64 * 1024).use { output ->
            sabrAudioPlaybackChunks(
                httpClient = transport.httpClient,
                bootstrap = bootstrap,
                playbackPositionMs = { syntheticPositionMs.get() },
                initialPlayerTimeMs = 0L,
                requestRetryDelay = { kotlinx.coroutines.delay(minOf(it, 50L)) },
                tokenSupplier = tokenSupplier
            ).collect { chunk ->
                if (chunk.data.isNotEmpty()) {
                    output.write(chunk.data)
                    downloaded += chunk.data.size
                    val advanceMs = chunk.durationMs?.takeIf { it > 0L } ?: 2_000L
                    val chunkStartMs = chunk.startMs ?: 0L
                    syntheticPositionMs.updateAndGet { prev -> maxOf(prev + advanceMs, chunkStartMs + advanceMs) }
                    val now = System.currentTimeMillis()
                    if (now - lastReportedAt >= 250L) {
                        lastReportedAt = now
                        onProgress(downloaded, total)
                    }
                }
            }
            output.flush()
        }
        onProgress(downloaded, if (total > 0L) total else downloaded)
        return downloaded
    }

    suspend fun deleteDownload(videoId: String) = withContext(Dispatchers.IO) {
        val entry = dao.find(videoId)
        if (entry != null && entry.kind == CachedAudioEntity.KIND_DOWNLOAD) {
            if (videoId !in activePlaybackIds) {
                runCatching { File(entry.filePath).delete() }
            }
            dao.delete(videoId)
        }
    }

    /** Evicts oldest [CachedAudioEntity.KIND_CACHE] entries until under [maxCacheBytes]. Never deletes downloads. */
    suspend fun evictCacheIfNeeded() = withContext(Dispatchers.IO) {
        var total = dao.totalBytesByKind(CachedAudioEntity.KIND_CACHE)
        if (total <= maxCacheBytes) return@withContext
        val oldest = dao.byKindOldestFirst(CachedAudioEntity.KIND_CACHE)
        for (entry in oldest) {
            if (total <= maxCacheBytes) break
            if (entry.videoId in activePlaybackIds) continue
            runCatching { File(entry.filePath).delete() }
            dao.delete(entry.videoId)
            total -= entry.contentLength
        }
    }

    suspend fun clearStreamingCache() = withContext(Dispatchers.IO) {
        val entries = dao.byKindOldestFirst(CachedAudioEntity.KIND_CACHE)
        for (entry in entries) {
            if (entry.videoId in activePlaybackIds) continue
            runCatching { File(entry.filePath).delete() }
            dao.delete(entry.videoId)
        }
        Timber.i("Cleared streaming audio cache (preserved active playback & permanent downloads)")
    }

    suspend fun stats(): StorageStats = withContext(Dispatchers.IO) {
        StorageStats(
            cacheBytes = dao.totalBytesByKind(CachedAudioEntity.KIND_CACHE),
            cacheCount = dao.countByKind(CachedAudioEntity.KIND_CACHE),
            downloadBytes = dao.totalBytesByKind(CachedAudioEntity.KIND_DOWNLOAD),
            downloadCount = dao.countByKind(CachedAudioEntity.KIND_DOWNLOAD)
        )
    }

    private fun extensionForMime(mimeType: String): String = when {
        mimeType.contains("webm", ignoreCase = true) || mimeType.contains("opus", ignoreCase = true) -> "webm"
        mimeType.contains("ogg", ignoreCase = true) -> "ogg"
        mimeType.contains("mpeg", ignoreCase = true) || mimeType.contains("mp3", ignoreCase = true) -> "mp3"
        else -> "m4a"
    }

    suspend fun resolveStreamForDownload(videoId: String): StreamResolver.ResolvedStream {
        val stream = extractor.resolve(videoId, preferProgressiveForDownload = true)
        return StreamResolver.ResolvedStream(
            url = stream.url,
            mimeType = stream.mimeType,
            expiresAt = stream.expiresAtMs,
            loudnessDb = stream.loudnessDb,
            itag = stream.itag,
            clientName = stream.clientName,
            requestHeaders = stream.requestHeaders,
            requireBoundedRange = stream.requireBoundedRange,
            rangeChunkSizeBytes = stream.rangeChunkSizeBytes,
            useRangeChunks = stream.useRangeChunks,
            sabrBootstrap = stream.sabrBootstrap,
            isHls = stream.isHls,
            isLocalFile = false,
            contentLength = stream.contentLength
        )
    }

    private companion object {
        const val DEFAULT_RANGE_CHUNK_BYTES = 512 * 1024
    }
}
