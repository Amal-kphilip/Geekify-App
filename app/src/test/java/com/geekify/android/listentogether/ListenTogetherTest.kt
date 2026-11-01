package com.geekify.android.listentogether

import com.geekify.android.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherTest {

    @Test
    fun stateMessageSurvivesARoundTrip() {
        val original = RoomMessage.State(
            room = "ABC123", user = "u1234",
            track = Track(videoId = "dQw4w9WgXcQ", title = "Song", artist = "Artist"),
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
    fun serverAddressesAreNormalisedAndCleartextRestrictedInRelease() {
        assertEquals("wss://example.com/ws", ListenTogetherManager.normalizeServerUrl("  example.com/ws "))
        assertEquals("wss://example.com", ListenTogetherManager.normalizeServerUrl("https://example.com"))
        assertEquals("ws://192.168.1.5:8080", ListenTogetherManager.normalizeServerUrl("ws://192.168.1.5:8080"))
        // Release policy rejects external cleartext ws:// while permitting wss:// and localhost loopback
        assertNull(ListenTogetherManager.normalizeServerUrl("ws://192.168.1.5:8080", allowInsecureCleartext = false))
        assertEquals("wss://relay.geekify.app/ws", ListenTogetherManager.normalizeServerUrl("wss://relay.geekify.app/ws", allowInsecureCleartext = false))
        assertEquals("ws://localhost:8080/ws", ListenTogetherManager.normalizeServerUrl("ws://localhost:8080/ws", allowInsecureCleartext = false))
        assertNull(ListenTogetherManager.normalizeServerUrl(""))
        assertNull(ListenTogetherManager.normalizeServerUrl("   "))
    }

    @Test
    fun roomCodeAndRemoteTrackValidationEnforcesSecurityRules() {
        val code = ListenTogetherManager.generateRoomCode()
        assertEquals(ListenTogetherManager.DEFAULT_ROOM_CODE_LENGTH, code.length)
        assertTrue(ListenTogetherManager.isValidRoomCode(code))
        assertFalse(ListenTogetherManager.isValidRoomCode("AB1")) // too short

        // Video ID validation
        assertTrue(ListenTogetherManager.isValidVideoId("dQw4w9WgXcQ"))
        assertFalse(ListenTogetherManager.isValidVideoId("../etc/passwd"))
        assertFalse(ListenTogetherManager.isValidVideoId("short"))

        // Trusted thumbnail host validation
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            ListenTogetherManager.sanitizeThumbnailUrl("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg")
        )
        assertNull(ListenTogetherManager.sanitizeThumbnailUrl("http://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"))
        assertNull(ListenTogetherManager.sanitizeThumbnailUrl("https://evil.example.com/tracker.png"))

        // Remote state validation
        val now = 1_700_000_000_000L
        val validState = RoomMessage.State(
            room = "ROOM2345",
            user = "peer1234",
            track = Track(videoId = "dQw4w9WgXcQ", title = "Valid", artist = "Artist"),
            positionMs = 15_000L,
            playing = true,
            sentAt = now
        )
        assertTrue(ListenTogetherManager.isValidRemoteState(validState, nowMs = now))
        assertFalse(ListenTogetherManager.isValidRemoteState(validState.copy(positionMs = -50L), nowMs = now))
        assertFalse(ListenTogetherManager.isValidRemoteState(validState.copy(sentAt = Long.MAX_VALUE), nowMs = now))
    }
}
