package com.geekify.android.downloads

import android.content.Context
import com.geekify.android.data.local.DownloadDao
import com.geekify.android.data.local.DownloadEntity
import com.geekify.android.data.model.Track
import com.geekify.android.player.InnerTubeXPlayer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Persistent offline download manager. Manages single tracks, albums, playlists, and queue downloads
 * with pause/resume/cancel/retry, progress reporting, and foreground [DownloadService] orchestration.
 */
class SongDownloadManager(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val audioCacheManager: AudioCacheManager,
    private val streamResolver: InnerTubeXPlayer,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val _activeVideoId = MutableStateFlow<String?>(null)
    val activeVideoId: StateFlow<String?> = _activeVideoId.asStateFlow()

    private val _transferSpeedBytesPerSec = MutableStateFlow(0L)
    val transferSpeedBytesPerSec: StateFlow<Long> = _transferSpeedBytesPerSec.asStateFlow()

    val downloads: Flow<List<DownloadEntity>> = downloadDao.observeAll()
    val completedDownloads: Flow<List<DownloadEntity>> = downloadDao.observeCompleted()

    fun enqueue(track: Track, albumId: String? = track.albumId, playlistId: String? = null) {
        scope.launch {
            val existing = downloadDao.find(track.videoId)
            if (existing?.status == DownloadEntity.STATUS_COMPLETED) return@launch
            downloadDao.upsert(
                DownloadEntity(
                    videoId = track.videoId,
                    title = track.title,
                    artist = track.artist,
                    thumbnailUrl = track.thumbnails.lastOrNull()?.url,
                    albumId = albumId,
                    playlistId = playlistId,
                    status = DownloadEntity.STATUS_QUEUED
                )
            )
            DownloadService.start(context)
        }
    }

    fun enqueueBatch(tracks: List<Track>, albumId: String? = null, playlistId: String? = null) {
        if (tracks.isEmpty()) return
        scope.launch {
            for (track in tracks) {
                val existing = downloadDao.find(track.videoId)
                if (existing?.status == DownloadEntity.STATUS_COMPLETED) continue
                downloadDao.upsert(
                    DownloadEntity(
                        videoId = track.videoId,
                        title = track.title,
                        artist = track.artist,
                        thumbnailUrl = track.thumbnails.lastOrNull()?.url,
                        albumId = albumId ?: track.albumId,
                        playlistId = playlistId,
                        status = DownloadEntity.STATUS_QUEUED
                    )
                )
            }
            DownloadService.start(context)
        }
    }

    fun pause(videoId: String) {
        activeJobs.remove(videoId)?.cancel()
        scope.launch {
            downloadDao.updateStatus(videoId, DownloadEntity.STATUS_PAUSED)
        }
    }

    fun resume(videoId: String) {
        scope.launch {
            downloadDao.updateStatus(videoId, DownloadEntity.STATUS_QUEUED, error = null)
            DownloadService.start(context)
        }
    }

    fun retry(videoId: String) = resume(videoId)

    fun cancel(videoId: String) {
        activeJobs.remove(videoId)?.cancel()
        scope.launch {
            deleteDownloadAndAwait(videoId)
        }
    }

    suspend fun deleteDownloadAndAwait(videoId: String) {
        activeJobs.remove(videoId)?.cancel()
        downloadDao.delete(videoId)
        audioCacheManager.deleteDownload(videoId)
        streamResolver.invalidate(videoId)
    }

    fun deleteDownload(videoId: String) = cancel(videoId)

    suspend fun requeueInterrupted() {
        downloadDao.requeueInterrupted()
    }

    /**
     * Drains the next queued download item. Called sequentially by [DownloadService].
     * Returns `true` if a queued item was processed, `false` when queue is empty.
     */
    suspend fun processNext(onNotificationProgress: (title: String, progressPercent: Int) -> Unit = { _, _ -> }): Boolean {
        val item = downloadDao.nextQueued() ?: return false
        val videoId = item.videoId
        _activeVideoId.value = videoId
        downloadDao.updateProgress(videoId, DownloadEntity.STATUS_RUNNING, 0f, 0L, 0L)
        onNotificationProgress(item.title, 0)

        val job = scope.launch {
            val startTimeMs = System.currentTimeMillis()
            try {
                // Reuse cached progressive stream if valid; otherwise resolve a direct progressive stream (no real-time SABR/HLS delay).
                val cachedOrResolved = runCatching {
                    streamResolver.resolve(videoId, forceRefresh = false)
                }.getOrNull()
                val stream = if (cachedOrResolved != null &&
                    !cachedOrResolved.isHls &&
                    cachedOrResolved.sabrBootstrap == null &&
                    cachedOrResolved.expiresAt > System.currentTimeMillis() + 30_000L
                ) {
                    cachedOrResolved
                } else {
                    audioCacheManager.resolveStreamForDownload(videoId)
                }

                val track = Track(
                    videoId = videoId,
                    title = item.title,
                    artist = item.artist,
                    albumId = item.albumId,
                    thumbnails = item.thumbnailUrl?.let { listOf(com.geekify.android.data.model.Thumbnail(it)) }.orEmpty()
                )
                var lastDbPersistMs = 0L
                val transferStartedMs = System.currentTimeMillis()
                val stored = try {
                    audioCacheManager.downloadToPermanentStorage(track, stream) { downloaded, total ->
                        val now = System.currentTimeMillis()
                        val elapsedSec = ((now - transferStartedMs).coerceAtLeast(100L)) / 1000.0
                        _transferSpeedBytesPerSec.value = (downloaded / elapsedSec).toLong()
                        if (now - lastDbPersistMs >= 350L || (total > 0L && downloaded >= total)) {
                            lastDbPersistMs = now
                            val pct = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                            downloadDao.updateProgress(videoId, DownloadEntity.STATUS_RUNNING, pct, downloaded, total)
                            onNotificationProgress(item.title, (pct * 100).toInt())
                        }
                    }
                } catch (firstErr: Exception) {
                    if (firstErr is CancellationException) throw firstErr
                    // Retry once with a freshly extracted progressive stream in case a cached URL expired
                    streamResolver.invalidate(videoId)
                    val freshStream = audioCacheManager.resolveStreamForDownload(videoId)
                    audioCacheManager.downloadToPermanentStorage(track, freshStream) { downloaded, total ->
                        val now = System.currentTimeMillis()
                        val elapsedSec = ((now - transferStartedMs).coerceAtLeast(100L)) / 1000.0
                        _transferSpeedBytesPerSec.value = (downloaded / elapsedSec).toLong()
                        if (now - lastDbPersistMs >= 350L || (total > 0L && downloaded >= total)) {
                            lastDbPersistMs = now
                            val pct = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                            downloadDao.updateProgress(videoId, DownloadEntity.STATUS_RUNNING, pct, downloaded, total)
                            onNotificationProgress(item.title, (pct * 100).toInt())
                        }
                    }
                }
                val totalElapsedMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)
                val kbps = (stored.contentLength * 1000L / totalElapsedMs) / 1024L
                Timber.i("Download complete for $videoId (${stored.contentLength} bytes in ${totalElapsedMs}ms, ${kbps} KB/s)")
                downloadDao.upsert(
                    item.copy(
                        status = DownloadEntity.STATUS_COMPLETED,
                        progress = 1f,
                        bytesDownloaded = stored.contentLength,
                        totalBytes = stored.contentLength,
                        filePath = stored.filePath,
                        mimeType = stored.mimeType,
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                streamResolver.invalidate(videoId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.w(t, "Download failed for $videoId")
                downloadDao.updateStatus(
                    videoId = videoId,
                    status = DownloadEntity.STATUS_FAILED,
                    error = t.message ?: "Download failed"
                )
            } finally {
                _transferSpeedBytesPerSec.value = 0L
            }
        }
        activeJobs[videoId] = job
        try {
            job.join()
        } finally {
            activeJobs.remove(videoId, job)
            if (_activeVideoId.value == videoId) {
                _activeVideoId.value = null
            }
        }
        return true
    }
}
