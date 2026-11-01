package com.geekify.android.player

import androidx.datastore.preferences.core.emptyPreferences
import com.geekify.android.data.model.Track
import com.geekify.android.settings.AppSettingsRepository
import com.geekify.android.settings.AppSettingsState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRetryCoordinatorTest {

    private fun track(id: String) = Track(
        videoId = id,
        title = "Title $id",
        artist = "Artist $id",
        durationSeconds = 200
    )

    @Test
    fun `prevents simultaneous retry operations and watchdog conflicts`() {
        val coordinator = PlaybackRetryCoordinator(maxStreamRetries = 3, retryResetAfterMs = 15_000L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()

        val firstAttempt = coordinator.tryBeginRetry()
        assertEquals(1, firstAttempt)

        val job = scope.launch { gate.await() }
        coordinator.attachRetryJob(job)
        assertTrue(coordinator.isRetryActive())

        // Simultaneous retry must be rejected while retryJob is active
        assertNull(coordinator.tryBeginRetry())
        // Watchdog must not trigger a competing retry while retryJob or loadJob is active
        assertFalse(coordinator.canWatchdogTriggerRetry(loadJobActive = false))
        assertFalse(coordinator.canWatchdogTriggerRetry(loadJobActive = true))

        gate.complete(Unit)
        coordinator.clearRetryJob(job)
        assertFalse(coordinator.isRetryActive())
        assertTrue(coordinator.canWatchdogTriggerRetry(loadJobActive = false))
    }

    @Test
    fun `offline outage pauses retries prevents skipping and resumes when connectivity returns`() {
        val coordinator = PlaybackRetryCoordinator(maxStreamRetries = 3, retryResetAfterMs = 15_000L)
        val current = track("song1")

        // Simulate going offline
        coordinator.onConnectivityChanged(online = false, currentTrackId = current.videoId) {}
        assertFalse(coordinator.isOnline)
        assertFalse(coordinator.canSkipAfterError())
        assertFalse(coordinator.canWatchdogTriggerRetry(loadJobActive = false))

        // Error while offline must defer recovery without consuming retry budget
        val deferred = coordinator.deferIfOffline(
            track = current,
            resumeAtMs = 42_000L,
            wasPlaying = true,
            markFailed = false
        )
        assertTrue(deferred)
        assertEquals(0, coordinator.resolveRetries)
        assertNotNull(coordinator.pendingOfflineRecovery)

        // When connectivity returns for the same track, recovery resumes automatically
        var resumed: PendingOfflineRecovery? = null
        coordinator.onConnectivityChanged(online = true, currentTrackId = current.videoId) {
            resumed = it
        }
        assertNotNull(resumed)
        assertEquals("song1", resumed?.track?.videoId)
        assertEquals(42_000L, resumed?.resumeAtMs)
        assertEquals(true, resumed?.wasPlaying)
        assertNull(coordinator.pendingOfflineRecovery)
    }

    @Test
    fun `retry budget exhausts at max retries and refills after continuous playback`() {
        val coordinator = PlaybackRetryCoordinator(maxStreamRetries = 3, retryResetAfterMs = 15_000L)

        assertEquals(1, coordinator.tryBeginRetry())
        assertEquals(2, coordinator.tryBeginRetry())
        assertEquals(3, coordinator.tryBeginRetry())
        assertNull(coordinator.tryBeginRetry())

        // 15 seconds of continuous playback resets the retry budget
        repeat(30) {
            coordinator.onPlaybackProgressTick(500L)
        }
        assertEquals(0, coordinator.resolveRetries)
        assertEquals(1, coordinator.tryBeginRetry())
    }

    @Test
    fun `stopPlaybackOnTaskRemoved defaults to true and is configurable`() {
        val defaults = AppSettingsState()
        assertTrue(defaults.stopPlaybackOnTaskRemoved)

        val prefsFalse = emptyPreferences().toMutablePreferences().apply {
            this[AppSettingsRepository.KEY_STOP_ON_TASK_REMOVED] = false
        }
        assertFalse(AppSettingsRepository.fromPreferences(prefsFalse).stopPlaybackOnTaskRemoved)
    }
}
