package com.geekify.android.listentogether

import com.geekify.android.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherTest {

    @Test
    fun stateMessageSurvivesARoundTrip() {
        val original = RoomMessage.State(
            room = "ABC123", user = "u1",
            track = Track(videoId = "vid", title = "Song", artist = "Artist"),
            positionMs = 42_000L, playing = true, sentAt = 1_700_000_000_000L
        )
        val decoded = MessageCodec.decode(MessageCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun encodedMessagesCarryATypeField() {
        assertTrue(MessageCodec.encode(RoomMessage.Ping(5L)).contains("\"type\":\"ping\""))
    }

    @Test
    fun malformedOrUnknownPayloadsAreDroppedNotThrown() {
        assertNull(MessageCodec.decode(""))
        assertNull(MessageCodec.decode("not json at all"))
        assertNull(MessageCodec.decode("{\"type\":\"warp-drive\"}"))
        assertNull(MessageCodec.decode("{\"type\":\"state\"}"))               // required fields missing
        assertNull(MessageCodec.decode("x".repeat(70_000)))                   // oversized frame
    }

    @Test
    fun unknownExtraFieldsAreIgnored() {
        assertNotNull(MessageCodec.decode("{\"type\":\"pong\",\"t\":9,\"future\":true}"))
    }

    @Test
    fun backoffDoublesAndIsCapped() {
        assertEquals(1_000L, ListenTogetherManager.backoffMs(1))
        assertEquals(2_000L, ListenTogetherManager.backoffMs(2))
        assertEquals(4_000L, ListenTogetherManager.backoffMs(3))
        assertEquals(30_000L, ListenTogetherManager.backoffMs(20))
        assertEquals(30_250L, ListenTogetherManager.backoffMs(20, jitterMs = 250L))
    }

    @Test
    fun serverAddressesAreNormalised() {
        assertEquals("wss://example.com/ws", ListenTogetherManager.normalizeServerUrl("  example.com/ws "))
        assertEquals("wss://example.com", ListenTogetherManager.normalizeServerUrl("https://example.com"))
        assertEquals("ws://192.168.1.5:8080", ListenTogetherManager.normalizeServerUrl("ws://192.168.1.5:8080"))
        assertNull(ListenTogetherManager.normalizeServerUrl(""))
        assertNull(ListenTogetherManager.normalizeServerUrl("   "))
    }
}
