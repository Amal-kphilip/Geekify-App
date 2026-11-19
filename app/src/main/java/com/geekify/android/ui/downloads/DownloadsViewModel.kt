package com.geekify.android.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.data.local.DownloadEntity
import com.geekify.android.data.model.Track
import com.geekify.android.downloads.AudioCacheManager
import com.geekify.android.downloads.SongDownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(
    private val downloadManager: SongDownloadManager,
    private val audioCacheManager: AudioCacheManager
) : ViewModel() {

    val downloads: StateFlow<List<DownloadEntity>> = downloadManager.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val transferSpeedBytesPerSec: StateFlow<Long> = downloadManager.transferSpeedBytesPerSec

    private val _stats = MutableStateFlow(AudioCacheManager.StorageStats(0L, 0, 0L, 0))
    val stats: StateFlow<AudioCacheManager.StorageStats> = _stats.asStateFlow()

    init {
        refreshStats()
        viewModelScope.launch {
            downloadManager.downloads.collect {
                _stats.value = audioCacheManager.stats()
            }
        }
    }

    fun refreshStats() {
        viewModelScope.launch {
            _stats.value = audioCacheManager.stats()
        }
    }

    fun enqueue(track: Track) = downloadManager.enqueue(track)
    fun enqueueBatch(tracks: List<Track>, playlistId: String? = null) = downloadManager.enqueueBatch(tracks, playlistId = playlistId)
    fun pause(videoId: String) = downloadManager.pause(videoId)
    fun resume(videoId: String) = downloadManager.resume(videoId)
    fun retry(videoId: String) = downloadManager.retry(videoId)
    fun cancel(videoId: String) {
        viewModelScope.launch {
            downloadManager.deleteDownloadAndAwait(videoId)
            _stats.value = audioCacheManager.stats()
        }
    }
    fun delete(videoId: String) = cancel(videoId)
    fun clearStreamingCache() {
        viewModelScope.launch {
            audioCacheManager.clearStreamingCache()
            refreshStats()
        }
    }
}
