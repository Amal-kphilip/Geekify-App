package com.geekify.android.player

import com.geekify.android.data.model.Track
import kotlinx.coroutines.Job

data class PendingOfflineRecovery(
    val track: Track,
    val resumeAtMs: Long,
    val wasPlaying: Boolean,
    val markFailed: Boolean
)

/**
 * Coordinates stream retry attempts, buffering-watchdog checks, auto-skip timers, and offline
 * recovery so that:
 * - Simultaneous retry operations cannot run concurrently.
 * - Buffering watchdog and error-recovery never conflict.
 * - Network outages pause retries and never auto-skip through the user's queue.
 * - Returning connectivity automatically resumes recovery for the active track.
 */
class PlaybackRetryCoordinator(
    private val maxStreamRetries: Int = MAX_STREAM_RETRIES,
    private val retryResetAfterMs: Long = RETRY_RESET_AFTER_MS
) {
    companion object {
        const val MAX_STREAM_RETRIES = 3
        const val RETRY_RESET_AFTER_MS = 15_000L
    }

    private val lock = Any()

    @Volatile
    var isOnline: Boolean = true
        private set

    var resolveRetries: Int = 0
        private set

    var playedSinceRetryMs: Long = 0L
        private set

    var retryJob: Job? = null
        private set

    var skipJob: Job? = null
        private set

    var pendingOfflineRecovery: PendingOfflineRecovery? = null
        private set

    fun isRetryActive(): Boolean = synchronized(lock) {
        retryJob?.isActive == true
    }

    fun canWatchdogTriggerRetry(loadJobActive: Boolean): Boolean = synchronized(lock) {
        isOnline && !loadJobActive && retryJob?.isActive != true
    }

    fun onNewTrackLoading(videoId: String) = synchronized(lock) {
        retryJob?.cancel()
        retryJob = null
        skipJob?.cancel()
        skipJob = null
        resolveRetries = 0
        playedSinceRetryMs = 0L
        if (pendingOfflineRecovery?.track?.videoId != videoId) {
            pendingOfflineRecovery = null
        }
    }

    fun resetRetryBudget() = synchronized(lock) {
        resolveRetries = 0
        playedSinceRetryMs = 0L
    }

    fun onPlaybackProgressTick(deltaMs: Long) = synchronized(lock) {
        if (resolveRetries > 0) {
            playedSinceRetryMs += deltaMs
            if (playedSinceRetryMs >= retryResetAfterMs) {
                resolveRetries = 0
                playedSinceRetryMs = 0L
            }
        }
    }

    /**
     * Records a pending recovery if currently offline. Returns true if the caller should pause
     * retries and wait for connectivity instead of retrying or skipping.
     */
    fun deferIfOffline(
        track: Track,
        resumeAtMs: Long,
        wasPlaying: Boolean,
        markFailed: Boolean
    ): Boolean = synchronized(lock) {
        if (isOnline) return false
        skipJob?.cancel()
        skipJob = null
        retryJob?.cancel()
        retryJob = null
        pendingOfflineRecovery = PendingOfflineRecovery(
            track = track,
            resumeAtMs = resumeAtMs.coerceAtLeast(0L),
            wasPlaying = wasPlaying,
            markFailed = markFailed
        )
        true
    }

    /**
     * Reserves the next retry attempt (1..maxStreamRetries), or returns null if the retry budget
     * is exhausted or another retry is already active.
     */
    fun tryBeginRetry(forceResetBudget: Boolean = false): Int? = synchronized(lock) {
        if (forceResetBudget) {
            resolveRetries = 0
            playedSinceRetryMs = 0L
        }
        if (retryJob?.isActive == true) {
            return null
        }
        if (resolveRetries >= maxStreamRetries) {
            return null
        }
        skipJob?.cancel()
        skipJob = null
        resolveRetries++
        playedSinceRetryMs = 0L
        resolveRetries
    }

    fun attachRetryJob(job: Job) = synchronized(lock) {
        retryJob?.cancel()
        retryJob = job
    }

    fun clearRetryJob(job: Job?) = synchronized(lock) {
        if (job == null || retryJob === job) {
            retryJob = null
        }
    }

    fun attachSkipJob(job: Job) = synchronized(lock) {
        skipJob?.cancel()
        skipJob = job
    }

    fun canSkipAfterError(): Boolean = synchronized(lock) {
        isOnline && retryJob?.isActive != true
    }

    fun onConnectivityChanged(
        online: Boolean,
        currentTrackId: String?,
        onResume: (PendingOfflineRecovery) -> Unit
    ) {
        val toResume: PendingOfflineRecovery? = synchronized(lock) {
            isOnline = online
            if (!online) {
                skipJob?.cancel()
                skipJob = null
                null
            } else {
                val pending = pendingOfflineRecovery
                if (pending != null && pending.track.videoId == currentTrackId && retryJob?.isActive != true) {
                    pendingOfflineRecovery = null
                    pending
                } else {
                    if (pending != null && pending.track.videoId != currentTrackId) {
                        pendingOfflineRecovery = null
                    }
                    null
                }
            }
        }
        if (toResume != null) {
            onResume(toResume)
        }
    }

    fun cancelAll() = synchronized(lock) {
        retryJob?.cancel()
        retryJob = null
        skipJob?.cancel()
        skipJob = null
        pendingOfflineRecovery = null
    }
}
