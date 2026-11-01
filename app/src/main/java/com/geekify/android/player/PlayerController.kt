package com.geekify.android.player

import android.content.Context
import android.os.SystemClock
import com.geekify.android.data.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
    val queueManager: QueueManager
) {
    val state: StateFlow<QueueState> = queueManager.state
    val position: StateFlow<PlaybackPosition> = queueManager.position

    private val sleepTimerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sleepTimerJob: Job? = null
    private val _sleepTimerRemainingMs = MutableStateFlow<Long?>(null)
    val sleepTimerRemainingMs: StateFlow<Long?> = _sleepTimerRemainingMs.asStateFlow()

    fun play(track: Track, queue: List<Track> = emptyList()) = queueManager.play(track, queue)
    fun playShuffled(tracks: List<Track>) = queueManager.playShuffled(tracks)
    fun togglePlay() = queueManager.togglePlay()
    fun seekTo(positionMs: Long) = queueManager.seekTo(positionMs)
    fun next() = queueManager.next()
    fun previous(progressSeconds: Long) = queueManager.previous(progressSeconds)
    fun toggleShuffle() = queueManager.toggleShuffle()
    fun cycleRepeat() = queueManager.cycleRepeat()
    fun setVolume(v: Float) = queueManager.setVolume(v)
    fun addToQueue(track: Track) = queueManager.addToQueue(track)
    fun removeFromQueue(index: Int) = queueManager.removeFromQueue(index)
    fun reorder(from: Int, to: Int) = queueManager.reorder(from, to)
    fun clearQueue() = queueManager.clearQueue()

    private var sleepTimerInitialVolume: Float? = null

    /** Starts a playback sleep timer. When it expires, playback is smoothly faded out and paused without altering the queue. */
    fun setSleepTimer(minutes: Int) {
        val durationMs = minutes.coerceAtLeast(1).toLong() * 60_000L
        cancelSleepTimer()
        val initialVol = queueManager.state.value.volume
        sleepTimerInitialVolume = initialVol
        sleepTimerJob = sleepTimerScope.launch {
            val endAt = SystemClock.elapsedRealtime() + durationMs
            val fadeDurationMs = 8_000L
            while (isActive) {
                val remaining = (endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                _sleepTimerRemainingMs.value = remaining
                if (remaining in 1..fadeDurationMs) {
                    val factor = (remaining.toFloat() / fadeDurationMs).coerceIn(0f, 1f)
                    queueManager.setVolume(initialVol * factor)
                }
                if (remaining == 0L) break
                delay(minOf(500L, remaining))
            }
            if (isActive) {
                _sleepTimerRemainingMs.value = null
                queueManager.setPlaying(false)
                queueManager.setVolume(initialVol)
                sleepTimerInitialVolume = null
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerInitialVolume?.let { initialVol ->
            queueManager.setVolume(initialVol)
            sleepTimerInitialVolume = null
        }
        _sleepTimerRemainingMs.value = null
    }
}
