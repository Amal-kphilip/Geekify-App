package com.geekify.android.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.audio.AudioEffectsController
import com.geekify.android.data.local.LibraryRepository
import com.geekify.android.data.model.Track
import com.geekify.android.player.PlayerController
import com.geekify.android.player.QueueState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val controller: PlayerController,
    private val library: LibraryRepository,
    private val audioEffects: AudioEffectsController
) : ViewModel() {

    val state: StateFlow<QueueState> = controller.state
    val artworkBeatIntensity: StateFlow<Float> = audioEffects.beatIntensity

    private val _isCurrentLiked = MutableStateFlow(false)
    val isCurrentLiked: StateFlow<Boolean> = _isCurrentLiked.asStateFlow()

    init {
        state.map { it.current }
            .distinctUntilChanged()
            .onEach { track ->
                if (track != null) {
                    _isCurrentLiked.value = library.isLiked(track.videoId)
                } else {
                    _isCurrentLiked.value = false
                }
            }
            .launchIn(viewModelScope)
    }

    fun play(track: Track, queue: List<Track> = emptyList()) = controller.play(track, queue)
    fun playShuffled(tracks: List<Track>) = controller.playShuffled(tracks)
    fun togglePlay() = controller.togglePlay()
    fun seek(positionMs: Long) = controller.seekTo(positionMs)
    fun next() = controller.next()
    fun previous() = controller.previous(state.value.progressMs / 1000)
    fun toggleShuffle() = controller.toggleShuffle()
    fun cycleRepeat() = controller.cycleRepeat()
    fun setVolume(volume: Float) = controller.setVolume(volume)
    fun addToQueue(track: Track) = controller.addToQueue(track)
    fun removeFromQueue(index: Int) = controller.removeFromQueue(index)
    fun reorder(from: Int, to: Int) = controller.reorder(from, to)
    fun clearQueue() = controller.clearQueue()
    fun setArtworkReactiveEnabled(enabled: Boolean) = audioEffects.setArtworkReactiveEnabled(enabled)

    fun toggleLikeCurrent() {
        val current = state.value.current ?: return
        viewModelScope.launch {
            library.toggleLike(current)
            _isCurrentLiked.value = library.isLiked(current.videoId)
        }
    }
}
