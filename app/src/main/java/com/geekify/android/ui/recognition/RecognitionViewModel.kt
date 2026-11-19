package com.geekify.android.ui.recognition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.data.local.RecognitionHistoryEntity
import com.geekify.android.data.model.Track
import com.geekify.android.recognition.MusicRecognitionService
import com.geekify.android.recognition.RecognitionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class RecognitionViewModel(
    private val service: MusicRecognitionService
) : ViewModel() {

    val status: StateFlow<RecognitionStatus> = service.status
    val audioLevel: StateFlow<Float> = service.audioLevel

    private val _historyQuery = MutableStateFlow("")
    val historyQuery: StateFlow<String> = _historyQuery.asStateFlow()

    private val _isResolvingTrack = MutableStateFlow(false)
    val isResolvingTrack: StateFlow<Boolean> = _isResolvingTrack.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    val history: StateFlow<List<RecognitionHistoryEntity>> = _historyQuery
        .flatMapLatest { q -> service.searchHistory(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun hasMicrophonePermission(): Boolean = service.hasMicrophonePermission()

    fun startListening() {
        _playbackError.value = null
        service.startRecognition()
    }

    fun stopListening(abort: Boolean = true) = service.stopRecognition(abort)

    fun reset() = service.reset()

    fun onHistoryQueryChange(query: String) {
        _historyQuery.value = query
    }

    fun deleteHistoryItem(id: Long) = service.deleteHistoryItem(id)

    fun clearHistory() = service.clearHistory()

    fun playRecognizedItem(
        title: String,
        artist: String,
        album: String?,
        coverArtUrl: String?,
        existingVideoId: String?,
        historyId: Long? = null,
        onResolved: (Track) -> Unit
    ) {
        if (_isResolvingTrack.value) return
        _playbackError.value = null
        viewModelScope.launch {
            _isResolvingTrack.value = true
            try {
                val track = service.resolveTrackForPlayback(
                    title = title,
                    artist = artist,
                    album = album,
                    coverArtUrl = coverArtUrl,
                    existingVideoId = existingVideoId,
                    historyId = historyId
                )
                if (track != null) {
                    onResolved(track)
                } else {
                    _playbackError.value = "Could not find a playable stream for \"$title\"."
                }
            } finally {
                _isResolvingTrack.value = false
            }
        }
    }

    override fun onCleared() {
        service.stopRecognition(abort = true)
    }
}
