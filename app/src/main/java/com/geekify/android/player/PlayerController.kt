package com.geekify.android.player

import android.content.Context
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

    fun play(track: Track, queue: List<Track> = emptyList()) = queueManager.play(track, queue)
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
}
