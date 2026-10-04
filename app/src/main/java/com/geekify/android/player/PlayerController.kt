package com.geekify.android.player

import android.content.Context
import android.content.Intent
import com.geekify.android.data.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
    val queueManager: QueueManager
) {
    val state: StateFlow<QueueState> = queueManager.state

    /**
     * Android stops an idle, paused playback service after a while. If the UI then asks to play, the state
     * flips to "playing" but nothing is alive to act on it, so make sure the service is running first.
     */
    private fun ensureService() {
        try {
            context.startService(Intent(context, PlaybackService::class.java))
        } catch (_: Exception) {
            // Not allowed from the background; the app is in the foreground whenever the UI calls this.
        }
    }

    fun play(track: Track, queue: List<Track> = emptyList()) { ensureService(); queueManager.play(track, queue) }
    fun playShuffled(tracks: List<Track>) { ensureService(); queueManager.playShuffled(tracks) }
    fun togglePlay() { ensureService(); queueManager.togglePlay() }
    fun seekTo(positionMs: Long) = queueManager.seekTo(positionMs)
    fun next() { ensureService(); queueManager.next() }
    fun previous(progressSeconds: Long) { ensureService(); queueManager.previous(progressSeconds) }
    fun toggleShuffle() = queueManager.toggleShuffle()
    fun cycleRepeat() = queueManager.cycleRepeat()
    fun setVolume(v: Float) = queueManager.setVolume(v)
    fun addToQueue(track: Track) = queueManager.addToQueue(track)
    fun removeFromQueue(index: Int) = queueManager.removeFromQueue(index)
    fun reorder(from: Int, to: Int) = queueManager.reorder(from, to)
    fun clearQueue() = queueManager.clearQueue()
}
