package com.geekify.android

import android.util.Log
import com.geekify.android.data.model.Track
import com.geekify.android.player.PendingOfflineRecovery
import com.geekify.android.player.PlaybackRetryCoordinator
import timber.log.Timber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliabilityTest {

    @Test
    fun `production logging tree suppresses verbose and debug logs while allowing warn and error`() {
        var loggedCount = 0
        var lastLoggedPriority = -1
        var lastLoggedMessage = ""

        val releaseTree = object : Timber.Tree() {
            public fun checkLoggable(priority: Int): Boolean = isLoggable(null, priority)

            override fun isLoggable(tag: String?, priority: Int): Boolean {
                return priority >= Log.INFO
            }

            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (!isLoggable(tag, priority)) return
                if (priority >= Log.WARN) {
                    loggedCount++
                    lastLoggedPriority = priority
                    lastLoggedMessage = message
                }
            }
        }

        assertFalse(releaseTree.checkLoggable(Log.VERBOSE))
        assertFalse(releaseTree.checkLoggable(Log.DEBUG))
        assertTrue(releaseTree.checkLoggable(Log.INFO))
        assertTrue(releaseTree.checkLoggable(Log.WARN))
        assertTrue(releaseTree.checkLoggable(Log.ERROR))

        Timber.plant(releaseTree)
        try {
            Timber.v("verbose trace message")
            Timber.d("debug calculation message")
            assertEquals(0, loggedCount)

            Timber.w("warning network retry delayed")
            assertEquals(1, loggedCount)
            assertEquals(Log.WARN, lastLoggedPriority)
            assertEquals("warning network retry delayed", lastLoggedMessage)

            Timber.e(IllegalStateException("boom"), "critical error")
            assertEquals(2, loggedCount)
            assertEquals(Log.ERROR, lastLoggedPriority)
            assertTrue(lastLoggedMessage.contains("critical error"))
        } finally {
            Timber.uproot(releaseTree)
        }
    }

    @Test
    fun `playback retry coordinator suppresses auto retries when offline and allows resumption when online`() {
        val coordinator = PlaybackRetryCoordinator(maxStreamRetries = 3)
        val track = Track(videoId = "song123", title = "Song", artist = "Artist")

        // Offline state: offline flag pauses network operations
        coordinator.onConnectivityChanged(online = false, currentTrackId = "song123") {}
        assertFalse(coordinator.isOnline)

        // Watchdog should not attempt network retry when device has no connectivity
        assertFalse(coordinator.canWatchdogTriggerRetry(loadJobActive = false))
        assertFalse(coordinator.canSkipAfterError())

        // Register pending offline recovery for the track
        assertTrue(coordinator.deferIfOffline(track = track, resumeAtMs = 45_000L, wasPlaying = true, markFailed = false))
        assertEquals(track, coordinator.pendingOfflineRecovery?.track)

        // When connectivity is restored, pending offline recovery is resumed
        var resumedRecovery: PendingOfflineRecovery? = null
        coordinator.onConnectivityChanged(online = true, currentTrackId = "song123") {
            resumedRecovery = it
        }

        assertTrue(coordinator.isOnline)
        assertNotNull(resumedRecovery)
        assertEquals("song123", resumedRecovery?.track?.videoId)
        assertEquals(45_000L, resumedRecovery?.resumeAtMs)
        assertTrue(coordinator.canWatchdogTriggerRetry(loadJobActive = false))
    }
}
