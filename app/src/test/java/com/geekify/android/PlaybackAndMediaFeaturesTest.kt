package com.geekify.android

import com.geekify.android.audio.CrossfadeAudioTransitionController
import com.geekify.android.data.source.innertube.Parsers
import com.geekify.android.lyrics.LrcParser
import com.geekify.android.player.extraction.ClientHealthTracker
import com.geekify.android.player.extraction.PlaybackFailureCategory
import com.geekify.android.player.extraction.PlaybackFailureClassifier
import com.geekify.android.player.extraction.WebPoTokenHealth
import com.geekify.android.recognition.MusicRecognitionService
import com.geekify.android.recognition.RecognitionResult
import com.geekify.android.recognition.RecognitionResultCache
import com.geekify.android.recognition.ShazamRecognitionClient
import com.geekify.android.recognition.ShazamSignatureGenerator
import com.metrolist.innertubex.extraction.strategy.ClientFailureKind
import com.metrolist.innertubex.extraction.strategy.ClientHealthScope
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import kotlin.io.encoding.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAndMediaFeaturesTest {

    @Test
    fun `ShazamSignatureGenerator produces valid base64 shazam header from 16kHz PCM`() {
        val sampleRate = 16_000
        val seconds = 3
        val totalSamples = sampleRate * seconds
        val pcm = ByteArray(totalSamples * 2)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val sample = (12000.0 * sin(2.0 * PI * 440.0 * t) + 8000.0 * sin(2.0 * PI * 1200.0 * t)).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[i * 2] = (sample and 0xff).toByte()
            pcm[i * 2 + 1] = ((sample ushr 8) and 0xff).toByte()
        }

        val sig = ShazamSignatureGenerator.fromI16(pcm)
        assertTrue(sig.startsWith("data:audio/vnd.shazam.sig;base64,"))
        val raw = Base64.decode(sig.substringAfter("base64,"))
        assertTrue("Signature payload should include 48-byte Shazam header", raw.size >= 56)
        // Little-endian magic 0xcafe2580
        assertEquals(0x80.toByte(), raw[0])
        assertEquals(0x25.toByte(), raw[1])
        assertEquals(0xfe.toByte(), raw[2])
        assertEquals(0xca.toByte(), raw[3])
    }

    @Test
    fun `MusicRecognitionService resamples 44_1kHz to 16kHz with expected length`() {
        val inputSamples = 44_100
        val input = ByteArray(inputSamples * 2)
        val resampled = MusicRecognitionService.resample16BitMono(input, 44_100, 16_000)
        assertEquals(16_000 * 2, resampled.size)
    }

    @Test
    fun `ShazamRecognitionClient parses full track response and handles no-match`() {
        val client = ShazamRecognitionClient(OkHttpClient())
        val payload = """
            {
              "tagid": "tag-1",
              "track": {
                "key": "123456",
                "title": "Blinding Lights",
                "subtitle": "The Weeknd",
                "url": "https://www.shazam.com/track/123456",
                "isrc": "USUG11904206",
                "genres": { "primary": "Pop" },
                "images": {
                  "coverart": "https://img/lo.jpg",
                  "coverarthq": "https://img/hi.jpg"
                },
                "sections": [
                  {
                    "type": "SONG",
                    "metadata": [
                      { "title": "Album", "text": "After Hours" },
                      { "title": "Released", "text": "2020" },
                      { "title": "Label", "text": "Republic" }
                    ]
                  }
                ],
                "hub": {
                  "options": [
                    {
                      "type": "open_video",
                      "actions": [{ "uri": "https://www.youtube.com/watch?v=4NRXx6U8ABQ" }]
                    }
                  ],
                  "providers": [
                    {
                      "caption": "Spotify",
                      "actions": [{ "uri": "spotify:track:0VjIjW4GlUZAMYd2vXMi3b" }]
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val parsed = client.parseResult(payload)
        assertNotNull(parsed)
        assertEquals("123456", parsed!!.trackId)
        assertEquals("Blinding Lights", parsed.title)
        assertEquals("The Weeknd", parsed.artist)
        assertEquals("After Hours", parsed.album)
        assertEquals("Pop", parsed.genre)
        assertEquals("4NRXx6U8ABQ", parsed.youtubeVideoId)
        assertEquals("spotify:track:0VjIjW4GlUZAMYd2vXMi3b", parsed.spotifyUrl)

        assertNull(client.parseResult("""{"matches":[]}"""))
    }

    @Test
    fun `RecognitionResultCache respects TTL`() {
        val cache = RecognitionResultCache(maxEntries = 4, ttlMs = 1_000L)
        val item = RecognitionResult("1", "Song", "Artist")
        cache.put("sig-1", item, nowMs = 10_000L)
        assertEquals(item, cache.get("sig-1", nowMs = 10_500L))
        assertNull(cache.get("sig-1", nowMs = 12_000L))
    }

    @Test
    fun `PlaybackFailureClassifier categorizes HTTP, SABR, and retry policies accurately`() {
        assertEquals(PlaybackFailureCategory.CDN_FORBIDDEN, PlaybackFailureClassifier.fromHttpStatus(403))
        assertEquals(PlaybackFailureCategory.TOKEN, PlaybackFailureClassifier.fromHttpStatus(401))
        assertEquals(PlaybackFailureCategory.URL_EXPIRED, PlaybackFailureClassifier.fromHttpStatus(410))
        assertEquals(PlaybackFailureCategory.RATE_LIMIT, PlaybackFailureClassifier.fromHttpStatus(429))
        assertEquals(PlaybackFailureCategory.NETWORK, PlaybackFailureClassifier.fromHttpStatus(503))

        val sabrAttest = SabrProtocolException("pending", SabrFailureKind.ATTESTATION_REQUIRED)
        assertEquals(PlaybackFailureCategory.SABR_ATTESTATION, PlaybackFailureClassifier.fromThrowable(sabrAttest))
        assertTrue(PlaybackFailureClassifier.requiresFreshExtraction(PlaybackFailureCategory.SABR_ATTESTATION))
        assertFalse(PlaybackFailureClassifier.isRetryable(PlaybackFailureCategory.UNAVAILABLE))
    }

    @Test
    fun `ClientHealthTracker adjusts scores and applies cooldown on repeated failures`() {
        var now = 1_000L
        val tracker = ClientHealthTracker(nowMs = { now })
        tracker.recordSuccess("ANDROID_VR", null)
        assertTrue(tracker.scoreAdjustment("ANDROID_VR", null) > 0)

        tracker.recordFailure("WEB_REMIX", ClientFailureKind.MEDIA_FORBIDDEN, null)
        assertTrue("403 forbidden should apply cooldown penalty", tracker.scoreAdjustment("WEB_REMIX", null) < -500)

        WebPoTokenHealth.recordRejection(nowMs = 1_000L)
        assertTrue(WebPoTokenHealth.tokensRejected(nowMs = 5_000L))
        WebPoTokenHealth.recordAccepted()
        assertFalse(WebPoTokenHealth.tokensRejected(nowMs = 5_000L))
    }

    @Test
    fun `CrossfadeAudioTransitionController equal-power gains preserve constant acoustic energy`() {
        for (step in 0..20) {
            val progress = step / 20f
            val (outGain, inGain) = CrossfadeAudioTransitionController.computeEqualPowerGains(progress)
            val energy = outGain * outGain + inGain * inGain
            assertTrue("Expected out^2 + in^2 ~= 1.0 at progress=$progress, got $energy", abs(energy - 1f) < 0.001f)
        }
    }

    @Test
    fun `LrcParser parses synced line and word timestamps and handles plain text without fake timestamps`() {
        val lrc = """
            [00:10.50]First line
            [00:15.00]<00:15.00>Second <00:15.50>line
            [00:20.00]Third line
        """.trimIndent()
        val parsed = LrcParser.parse(lrc, provider = "LrcLib", offsetMs = 0L)
        assertTrue(parsed.isSynced)
        assertEquals(3, parsed.lines.size)
        assertEquals(10_500L, parsed.lines[0].timeMs)
        assertEquals(2, parsed.lines[1].words.size)
        assertEquals(0, parsed.activeLineIndex(12_000L))
        assertEquals(1, parsed.activeLineIndex(16_000L))

        val plain = LrcParser.parse("Hello world\nSecond line", provider = "LrcLib")
        assertFalse(plain.isSynced)
        assertEquals(-1, plain.activeLineIndex(5_000L))
    }

    @Test
    fun `Parsers extracts nextContinuationData continuation token from musicShelfRenderer`() {
        val raw = Json.parseToJsonElement(
            """
            {
              "contents": {
                "musicShelfRenderer": {
                  "title": { "runs": [{ "text": "Songs" }] },
                  "continuations": [
                    { "nextContinuationData": { "continuation": "TOKEN_PAGE_2" } }
                  ]
                }
              }
            }
            """.trimIndent()
        )
        val parsed = Parsers.parseSearchResponseSinglePass(raw, "test", "song")
        assertEquals("TOKEN_PAGE_2", parsed.continuation)
    }
}
