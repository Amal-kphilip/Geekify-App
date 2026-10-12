package com.geekify.android.notifications

import com.geekify.android.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngagementMessageFactoryTest {
    @Test fun repeatMessageUsesTrackAndArtistFromHistory() {
        val message = EngagementMessageFactory.create(
            Track(videoId = "track-1", title = "Midnight City", artist = "M83"),
            playCount = 4
        )
        assertEquals("track-1", message.trackId)
        assertEquals("Your repeat pick is waiting", message.title)
        assertTrue(message.body.contains("Midnight City"))
        assertTrue(message.body.contains("M83"))
    }

    @Test fun firstTimeTrackUsesRecentSongsCopy() {
        val message = EngagementMessageFactory.create(
            Track(videoId = "track-2", title = "Intro", artist = "Unknown"),
            playCount = 1
        )
        assertEquals("Pick up where you left off", message.title)
        assertTrue(message.body.contains("Intro"))
        assertTrue(message.body.contains("recent songs"))
        assertTrue(!message.body.contains("Unknown"))
    }

    @Test fun notificationTextIsSingleLineAndBounded() {
        val message = EngagementMessageFactory.create(
            Track(videoId = "track-3", title = "A\n" + "Long ".repeat(30), artist = "Artist\nName"),
            playCount = 1
        )
        assertTrue(!message.body.contains("\n"))
        assertTrue(message.body.length < 220)
    }
}
