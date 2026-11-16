package com.geekify.android.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.geekify.android.ui.components.LocalIsPlaying
import com.geekify.android.ui.components.LocalNowPlayingId
import com.geekify.android.ui.components.LocalTogglePlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class PlaylistRowPlayStateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun currentTrackPlaying_showsPause_andClickTogglesPlay() {
        var toggleClicked = false
        var itemClicked = false

        val item = ListRowItem(
            id = "song_1",
            title = "Midnight City",
            subtitle = "By M83",
            art = null,
            onClick = { itemClicked = true },
            trackId = "song_1"
        )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalNowPlayingId provides "song_1",
                LocalIsPlaying provides true,
                LocalTogglePlay provides { toggleClicked = true }
            ) {
                PlaylistRow(item = item)
            }
        }

        // Must show Pause button
        composeTestRule.onNodeWithContentDescription("Pause").assertExists()

        // Clicking the circular button toggles play/pause instead of restarting
        composeTestRule.onNodeWithContentDescription("Pause").performClick()
        assertTrue(toggleClicked)
        assertEquals(false, itemClicked)
    }

    @Test
    fun currentTrackPaused_showsPlay_andClickTogglesPlay() {
        var toggleClicked = false
        var itemClicked = false

        val item = ListRowItem(
            id = "song_1",
            title = "Midnight City",
            subtitle = "By M83",
            art = null,
            onClick = { itemClicked = true },
            trackId = "song_1"
        )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalNowPlayingId provides "song_1",
                LocalIsPlaying provides false,
                LocalTogglePlay provides { toggleClicked = true }
            ) {
                PlaylistRow(item = item)
            }
        }

        // Must show Play button
        composeTestRule.onNodeWithContentDescription("Play").assertExists()

        // Clicking the circular button toggles play/pause
        composeTestRule.onNodeWithContentDescription("Play").performClick()
        assertTrue(toggleClicked)
        assertEquals(false, itemClicked)
    }

    @Test
    fun nonCurrentTrack_showsPlay_andClickCallsItemOnClick() {
        var toggleClicked = false
        var itemClicked = false

        val item = ListRowItem(
            id = "song_2",
            title = "Starboy",
            subtitle = "By The Weeknd",
            art = null,
            onClick = { itemClicked = true },
            trackId = "song_2"
        )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalNowPlayingId provides "song_1",
                LocalIsPlaying provides true,
                LocalTogglePlay provides { toggleClicked = true }
            ) {
                PlaylistRow(item = item)
            }
        }

        // Non-current track must show Play even when player is playing
        composeTestRule.onNodeWithContentDescription("Play").assertExists()

        // Clicking the circular button triggers item.onClick (to play the new song)
        composeTestRule.onNodeWithContentDescription("Play").performClick()
        assertTrue(itemClicked)
        assertEquals(false, toggleClicked)
    }

    @Test
    fun albumOrPlaylistCard_neverShowsPause_evenWhenMatchingIdIsPlaying() {
        var toggleClicked = false
        var itemClicked = false

        val albumCardItem = ListRowItem(
            id = "album_123",
            title = "Random Access Memories",
            subtitle = "Album",
            art = null,
            onClick = { itemClicked = true },
            trackId = null // Albums / playlists have null trackId
        )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalNowPlayingId provides "album_123",
                LocalIsPlaying provides true,
                LocalTogglePlay provides { toggleClicked = true }
            ) {
                PlaylistRow(item = albumCardItem)
            }
        }

        // Album/playlist card must always show Play, never Pause
        composeTestRule.onNodeWithContentDescription("Play").assertExists()

        // Clicking circular button opens/plays album via item.onClick
        composeTestRule.onNodeWithContentDescription("Play").performClick()
        assertTrue(itemClicked)
        assertEquals(false, toggleClicked)
    }
}
