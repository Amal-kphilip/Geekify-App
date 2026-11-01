package com.geekify.android

import androidx.work.NetworkType
import com.geekify.android.data.local.LibraryRepository
import com.geekify.android.data.source.YouTubeMusicSource
import com.geekify.android.notifications.NotificationPolicy
import com.geekify.android.notifications.NotificationWorker
import com.geekify.android.player.potoken.PoTokenGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FunctionalBugFixesTest {

    @Test
    fun `parseTrackDetails uses contentOrNull without quote or JsonNull artifacts`() {
        val raw = Json.parseToJsonElement(
            """
            {
              "videoDetails": {
                "videoId": null,
                "title": "Song \"Live\" Edition",
                "author": "The Artist",
                "lengthSeconds": "215",
                "channelId": null
              }
            }
            """.trimIndent()
        ).jsonObject

        val track = YouTubeMusicSource.parseTrackDetails(raw, fallbackVideoId = "dQw4w9WgXcQ")
        assertEquals("dQw4w9WgXcQ", track.videoId)
        assertEquals("Song \"Live\" Edition", track.title)
        assertEquals("The Artist", track.artist)
        assertEquals(215, track.durationSeconds)
        assertEquals("3:35", track.duration)
        assertNull(track.artists.first().id)
    }

    @Test
    fun `nextPlaylistPosition computes non-colliding index after middle track removal`() {
        assertEquals(0, LibraryRepository.nextPlaylistPosition(emptyList()))
        // If positions [0, 1, 2] had position 1 removed without compaction -> [0, 2],
        // next position must be 3 (not size == 2 which would collide with existing position 2).
        assertEquals(3, LibraryRepository.nextPlaylistPosition(listOf(0, 2)))
        // After compaction [0, 1], next position is 2.
        assertEquals(2, LibraryRepository.nextPlaylistPosition(listOf(0, 1)))
    }

    @Test
    fun `NotificationWorker schedule interval and network constraints match documentation`() {
        assertEquals(12L, NotificationWorker.SCHEDULE_INTERVAL_HOURS)
        assertEquals(NetworkType.CONNECTED, NotificationWorker.networkConstraints.requiredNetworkType)
        assertEquals(6 * NotificationPolicy.HOUR_MS, NotificationPolicy.ENGAGEMENT_QUIET_AFTER_OPEN_MS)
    }

    @Test
    fun `PoTokenGenerator mapTokens maps playerPot to playerRequestPoToken and streamingPot to streamingDataPoToken`() {
        val mapped = PoTokenGenerator.mapTokens(
            playerPot = "video_bound_player_pot",
            streamingPot = "session_bound_streaming_pot"
        )
        assertEquals("video_bound_player_pot", mapped.playerRequestPoToken)
        assertEquals("session_bound_streaming_pot", mapped.streamingDataPoToken)
    }
}
