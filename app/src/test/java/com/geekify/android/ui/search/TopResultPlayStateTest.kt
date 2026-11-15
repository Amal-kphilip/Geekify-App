package com.geekify.android.ui.search

import com.geekify.android.data.model.Track
import com.geekify.android.ui.theme.GeekifyIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopResultPlayStateTest {

    data class PlayButtonState(
        val icon: androidx.compose.ui.graphics.vector.ImageVector,
        val contentDescription: String,
        val isTogglingCurrent: Boolean
    )

    private fun resolvePlayButtonState(
        trackId: String,
        currentTrackId: String?,
        isPlaying: Boolean
    ): PlayButtonState {
        val isCurrent = trackId == currentTrackId
        val isCurrentPlaying = isCurrent && isPlaying
        return PlayButtonState(
            icon = if (isCurrentPlaying) GeekifyIcons.Pause else GeekifyIcons.Play,
            contentDescription = if (isCurrentPlaying) "Pause" else "Play",
            isTogglingCurrent = isCurrent
        )
    }

    @Test
    fun `icon toggles Play to Pause to Play as isPlaying changes for current track`() {
        val currentTrack = "song_123"

        // 1. Current track loaded but paused -> icon is Play, description "Play", clicking toggles play
        val pausedState = resolvePlayButtonState(
            trackId = currentTrack,
            currentTrackId = currentTrack,
            isPlaying = false
        )
        assertEquals(GeekifyIcons.Play, pausedState.icon)
        assertEquals("Play", pausedState.contentDescription)
        assertTrue(pausedState.isTogglingCurrent)

        // 2. Playback starts (isPlaying = true) -> icon transitions to Pause, description "Pause", clicking toggles play
        val playingState = resolvePlayButtonState(
            trackId = currentTrack,
            currentTrackId = currentTrack,
            isPlaying = true
        )
        assertEquals(GeekifyIcons.Pause, playingState.icon)
        assertEquals("Pause", playingState.contentDescription)
        assertTrue(playingState.isTogglingCurrent)

        // 3. Playback paused (isPlaying = false) -> icon transitions back to Play, description "Play"
        val pausedAgainState = resolvePlayButtonState(
            trackId = currentTrack,
            currentTrackId = currentTrack,
            isPlaying = false
        )
        assertEquals(GeekifyIcons.Play, pausedAgainState.icon)
        assertEquals("Play", pausedAgainState.contentDescription)
        assertTrue(pausedAgainState.isTogglingCurrent)
    }

    @Test
    fun `icon stays Play for tracks that are not the current track regardless of isPlaying`() {
        val currentTrack = "song_123"
        val otherTrack = "song_456"

        // When player is playing current track, other track still shows Play and starts playback (not toggling)
        val otherWhilePlayingState = resolvePlayButtonState(
            trackId = otherTrack,
            currentTrackId = currentTrack,
            isPlaying = true
        )
        assertEquals(GeekifyIcons.Play, otherWhilePlayingState.icon)
        assertEquals("Play", otherWhilePlayingState.contentDescription)
        assertFalse(otherWhilePlayingState.isTogglingCurrent)

        // When player is paused, other track still shows Play and starts playback
        val otherWhilePausedState = resolvePlayButtonState(
            trackId = otherTrack,
            currentTrackId = currentTrack,
            isPlaying = false
        )
        assertEquals(GeekifyIcons.Play, otherWhilePausedState.icon)
        assertEquals("Play", otherWhilePausedState.contentDescription)
        assertFalse(otherWhilePausedState.isTogglingCurrent)
    }
}
