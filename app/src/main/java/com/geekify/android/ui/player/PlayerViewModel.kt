package com.geekify.android.ui.player

import androidx.lifecycle.ViewModel
import com.geekify.android.audio.AudioEffectsController
import androidx.lifecycle.viewModelScope
import com.geekify.android.data.local.LibraryRepository
import com.geekify.android.data.sync.SyncRepository
import com.geekify.android.data.model.Track
import com.geekify.android.player.PlaybackPosition
import com.geekify.android.player.PlayerController
import com.geekify.android.player.QueueState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
class PlayerViewModel constructor(
    private val controller: PlayerController,
    private val library: LibraryRepository,
    private val sync: SyncRepository,
    private val audioEffects: AudioEffectsController
) : ViewModel() {

    val state: StateFlow<QueueState> = controller.state
    /** High-frequency position, collected only by the progress UI so ticks don't recompose the whole player. */
    val position: StateFlow<PlaybackPosition> = controller.position
    val beatIntensity: StateFlow<Float> = audioEffects.beatIntensity
    val sleepTimerRemainingMs: StateFlow<Long?> = controller.sleepTimerRemainingMs
    val audioSettings: StateFlow<com.geekify.android.audio.AudioSettings> = audioEffects.settings

    private val _isCurrentLiked = MutableStateFlow(false)
    val isCurrentLiked: StateFlow<Boolean> = _isCurrentLiked.asStateFlow()

    private val _dislikedVideoIds = MutableStateFlow<Set<String>>(emptySet())
    val isCurrentDisliked: StateFlow<Boolean> = combine(
        state.map { it.current?.videoId }.distinctUntilChanged(),
        _dislikedVideoIds
    ) { id, disliked -> id != null && id in disliked }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Direction attached atomically to the queue's current track transition. */
    val lastSkipDirection: Int get() = state.value.skipDirection

    init {
        combine(
            state.map { it.current?.videoId }.distinctUntilChanged(),
            library.liked
        ) { currentVideoId, likedTracks ->
            currentVideoId != null && likedTracks.any { it.videoId == currentVideoId }
        }
            .distinctUntilChanged()
            .onEach { _isCurrentLiked.value = it }
            .launchIn(viewModelScope)
    }

    fun play(track: Track, queue: List<Track> = emptyList()) = controller.play(track, queue)
    fun playShuffled(tracks: List<Track>) = controller.playShuffled(tracks)
    fun togglePlay() = controller.togglePlay()
    fun seek(positionMs: Long) = controller.seekTo(positionMs)

    /**
     * Seeks relatively by [deltaMs] from the latest authoritative position while preserving
     * paused/playing state, clamping to `0..durationMs`, and guarding against buffering or
     * non-seekable media (`durationMs <= 0`).
     *
     * Returns the clamped target position in ms if a seek was dispatched, or `null` otherwise.
     */
    fun seekBy(deltaMs: Long, basePositionMs: Long? = null): Long? {
        val currentState = state.value
        if (currentState.current == null || currentState.isBuffering) return null
        val pos = position.value
        val durationMs = pos.durationMs
        if (durationMs <= 0L) return null
        val startPosMs = (basePositionMs ?: pos.positionMs).coerceIn(0L, durationMs)
        val targetMs = computeSeekByTargetMs(
            currentPositionMs = startPosMs,
            durationMs = durationMs,
            deltaMs = deltaMs
        ) ?: return null
        controller.seekTo(targetMs)
        return targetMs
    }
    fun next() = controller.next()
    fun previous() = controller.previous(position.value.positionMs / 1000)

    fun toggleShuffle() = controller.toggleShuffle()
    fun cycleRepeat() = controller.cycleRepeat()
    fun setVolume(volume: Float) = controller.setVolume(volume)
    fun setPlaybackSpeed(speed: Float) = audioEffects.update { it.copy(speed = speed) }
    fun setPlaybackPitch(pitch: Float) = audioEffects.update { it.copy(pitch = pitch) }
    fun resetTempoPitch() = audioEffects.update { it.copy(speed = 1f, pitch = 1f) }
    fun startRadio(track: Track) = controller.play(track, emptyList())
    fun setReactiveArtworkMeterEnabled(enabled: Boolean) = audioEffects.setReactiveMeterEnabled(enabled)
    fun setTonearmSyncEnabled(enabled: Boolean) = controller.queueManager.setTonearmSyncEnabled(enabled)
    fun completeTonearmTransition(trackVideoId: String) = controller.queueManager.completeTonearmTransition(trackVideoId)
    fun addToQueue(track: Track) = controller.addToQueue(track)
    fun playNext(track: Track) = controller.queueManager.playNext(track)
    fun removeFromQueue(index: Int) = controller.removeFromQueue(index)
    fun reorder(from: Int, to: Int) = controller.reorder(from, to)
    fun clearQueue() = controller.clearQueue()
    fun setSleepTimer(minutes: Int) = controller.setSleepTimer(minutes)
    fun cancelSleepTimer() = controller.cancelSleepTimer()

    fun toggleLikeCurrent() {
        val current = state.value.current ?: return
        viewModelScope.launch {
            library.toggleLike(current)
            val nowLiked = library.isLiked(current.videoId)
            _isCurrentLiked.value = nowLiked
            if (nowLiked) {
                _dislikedVideoIds.value = _dislikedVideoIds.value - current.videoId
            }
            sync.pushNow()
        }
    }

    fun toggleDislikeCurrent(): Boolean {
        val current = state.value.current ?: return false
        val currentlyDisliked = current.videoId in _dislikedVideoIds.value
        val nextDisliked = !currentlyDisliked
        _dislikedVideoIds.value = if (nextDisliked) {
            _dislikedVideoIds.value + current.videoId
        } else {
            _dislikedVideoIds.value - current.videoId
        }
        viewModelScope.launch {
            if (nextDisliked && _isCurrentLiked.value) {
                library.toggleLike(current)
                _isCurrentLiked.value = false
                sync.pushNow()
            }
        }
        return nextDisliked
    }
}
