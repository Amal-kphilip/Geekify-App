package com.geekify.android

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.geekify.android.customization.CustomizationCategory
import com.geekify.android.customization.CustomizationPersistence
import com.geekify.android.customization.GestureCustomizationConfig
import com.geekify.android.lyrics.LyricsLine
import com.geekify.android.lyrics.LyricsWord
import com.geekify.android.ui.player.ArtworkDoubleTapSkipEngine
import com.geekify.android.ui.player.LyricsSyncCalculator
import com.geekify.android.ui.player.PendingSeekPosition
import com.geekify.android.ui.player.calculateProgressFraction
import com.geekify.android.ui.player.computeSeekByTargetMs
import com.geekify.android.ui.player.interpolatePlaybackPosition
import com.geekify.android.ui.player.nextSeekMultiplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkDoubleTapSkipTest {

    @Test
    fun `1 and 2 - left double-tap rewinds and right double-tap fast-forwards relative to artwork center`() {
        val engine = ArtworkDoubleTapSkipEngine()
        val width = 600f

        // Left of center (x = 120 < 300) -> rewind 10s
        val rewind = engine.onDoubleTap(
            trackId = "track_1",
            tapX = 120f,
            widthPx = width,
            enabled = true,
            stepSeconds = 10,
            progressive = false,
            nowMs = 1_000L
        )
        assertEquals(-10_000L, rewind)

        // Right of center (x = 450 >= 300) -> forward 10s
        val forward = engine.onDoubleTap(
            trackId = "track_1",
            tapX = 450f,
            widthPx = width,
            enabled = true,
            stepSeconds = 10,
            progressive = false,
            nowMs = 3_000L
        )
        assertEquals(10_000L, forward)
    }

    @Test
    fun `3 and 4 - default 10s duration and all configurable 5 to 30s step values work for both directions`() {
        val defaults = GestureCustomizationConfig()
        assertTrue(defaults.artworkDoubleTapSkipEnabled)
        assertEquals(10, defaults.artworkSkipDurationSeconds)
        assertFalse(defaults.artworkProgressiveSkipEnabled)

        val engine = ArtworkDoubleTapSkipEngine()
        val supported = listOf(5, 10, 15, 20, 25, 30)
        supported.forEachIndexed { idx, sec ->
            val back = engine.onDoubleTap("t", 100f, 500f, true, sec, false, idx * 2_000L)
            val fwd = engine.onDoubleTap("t", 400f, 500f, true, sec, false, idx * 2_000L + 500L)
            assertEquals(-sec * 1_000L, back)
            assertEquals(sec * 1_000L, fwd)
        }

        // Clamping & snapping of invalid stored values
        assertEquals(5, GestureCustomizationConfig.normalizeSkipDurationSeconds(-10))
        assertEquals(5, GestureCustomizationConfig.normalizeSkipDurationSeconds(2))
        assertEquals(10, GestureCustomizationConfig.normalizeSkipDurationSeconds(12))
        assertEquals(15, GestureCustomizationConfig.normalizeSkipDurationSeconds(13))
        assertEquals(30, GestureCustomizationConfig.normalizeSkipDurationSeconds(99))
    }

    @Test
    fun `5 and 6 - backward seek clamps to 0 and forward seek clamps to track duration`() {
        assertEquals(0L, computeSeekByTargetMs(currentPositionMs = 4_000L, durationMs = 200_000L, deltaMs = -10_000L))
        assertEquals(0L, computeSeekByTargetMs(currentPositionMs = 0L, durationMs = 200_000L, deltaMs = -10_000L))
        assertEquals(200_000L, computeSeekByTargetMs(currentPositionMs = 195_000L, durationMs = 200_000L, deltaMs = 10_000L))
        assertEquals(200_000L, computeSeekByTargetMs(currentPositionMs = 200_000L, durationMs = 200_000L, deltaMs = 30_000L))
        assertEquals(70_000L, computeSeekByTargetMs(currentPositionMs = 60_000L, durationMs = 200_000L, deltaMs = 10_000L))
    }

    @Test
    fun `7 and 8 - progressive skip multiplies within 1 second and resets after 1 second`() {
        // Direct nextSeekMultiplier contract
        assertEquals(2, nextSeekMultiplier(previousMultiplier = 1, elapsedMs = 400L))
        assertEquals(3, nextSeekMultiplier(previousMultiplier = 2, elapsedMs = 999L))
        assertEquals(1, nextSeekMultiplier(previousMultiplier = 3, elapsedMs = 1_000L))
        assertEquals(1, nextSeekMultiplier(previousMultiplier = 4, elapsedMs = 2_500L))

        val engine = ArtworkDoubleTapSkipEngine()
        // Rapid consecutive right double-taps within 1 second -> 10s, 20s, 30s, 40s
        assertEquals(10_000L, engine.onDoubleTap("songA", 400f, 500f, true, 10, true, 1_000L))
        assertEquals(20_000L, engine.onDoubleTap("songA", 400f, 500f, true, 10, true, 1_600L))
        assertEquals(30_000L, engine.onDoubleTap("songA", 400f, 500f, true, 10, true, 2_200L))
        assertEquals(40_000L, engine.onDoubleTap("songA", 400f, 500f, true, 10, true, 2_900L))

        // Wait >= 1 second -> resets back to 1x (10s) for rewind as well
        assertEquals(-10_000L, engine.onDoubleTap("songA", 100f, 500f, true, 10, true, 4_000L))
        assertEquals(-20_000L, engine.onDoubleTap("songA", 100f, 500f, true, 10, true, 4_500L))
    }

    @Test
    fun `9 - progressive skip disabled always uses exact configured duration`() {
        val engine = ArtworkDoubleTapSkipEngine()
        assertEquals(15_000L, engine.onDoubleTap("songA", 400f, 500f, true, 15, false, 1_000L))
        assertEquals(15_000L, engine.onDoubleTap("songA", 400f, 500f, true, 15, false, 1_300L))
        assertEquals(15_000L, engine.onDoubleTap("songA", 400f, 500f, true, 15, false, 1_600L))
    }

    @Test
    fun `10 and 11 - track change resets progressive state and disabling prevents skipping`() {
        val engine = ArtworkDoubleTapSkipEngine()
        assertEquals(10_000L, engine.onDoubleTap("songA", 400f, 500f, true, 10, true, 1_000L))
        assertEquals(20_000L, engine.onDoubleTap("songA", 400f, 500f, true, 10, true, 1_500L))

        // Changing track resets multiplier back to 1 even within 1 second
        assertEquals(10_000L, engine.onDoubleTap("songB", 400f, 500f, true, 10, true, 1_800L))

        // Disabling prevents skipping and resets progressive multiplier
        assertNull(engine.onDoubleTap("songB", 400f, 500f, false, 10, true, 2_000L))
        assertEquals(10_000L, engine.onDoubleTap("songB", 400f, 500f, true, 10, true, 2_200L))
    }

    @Test
    fun `12 and 13 - paused playback remains paused after seek and non-seekable media is guarded`() {
        // Non-seekable / unknown duration returns null
        assertNull(computeSeekByTargetMs(currentPositionMs = 5_000L, durationMs = 0L, deltaMs = 10_000L))
        assertNull(computeSeekByTargetMs(currentPositionMs = 5_000L, durationMs = -1L, deltaMs = -10_000L))

        // When paused, seeking moves to target and interpolation stays frozen at target (never resumes)
        val target = computeSeekByTargetMs(currentPositionMs = 30_000L, durationMs = 180_000L, deltaMs = 10_000L)!!
        assertEquals(40_000L, target)
        val pausedAfterWait = interpolatePlaybackPosition(
            sampledPositionMs = target,
            elapsedRealtimeMs = 3_000L,
            playbackSpeed = 1.0f,
            durationMs = 180_000L,
            isPlaying = false,
            isBuffering = false
        )
        assertEquals(40_000L, pausedAfterWait)

        // When buffering, interpolation also stays frozen
        val bufferingAfterWait = interpolatePlaybackPosition(
            sampledPositionMs = target,
            elapsedRealtimeMs = 3_000L,
            playbackSpeed = 1.0f,
            durationMs = 180_000L,
            isPlaying = true,
            isBuffering = true
        )
        assertEquals(40_000L, bufferingAfterWait)
    }

    @Test
    fun `14, 15 and 16 - seekbar pending state, lyrics sync, and swipe gesture independence after skip`() {
        val durationMs = 200_000L
        val previousPos = 20_000L
        val target = computeSeekByTargetMs(previousPos, durationMs, 10_000L)!!
        assertEquals(30_000L, target)

        // Seekbar PendingSeekPosition holds target and prevents snap-back to stale 20_000L sample
        val pending = PendingSeekPosition(targetMs = target, previousPositionMs = previousPos, createdAtNanos = 1_000L)
        assertFalse(pending.isAcknowledged(20_100L))
        assertEquals(0.15f, calculateProgressFraction(pending.targetMs, durationMs), 0.0001f)
        assertTrue(pending.isAcknowledged(30_150L))

        // Lyrics immediately resolve to the skipped line and word
        val lines = listOf(
            LyricsLine(timeMs = 10_000L, text = "Early verse"),
            LyricsLine(
                timeMs = 29_000L,
                text = "Chorus starts now",
                words = listOf(
                    LyricsWord(29_000L, 29_500L, "Chorus"),
                    LyricsWord(29_600L, 30_200L, "starts"),
                    LyricsWord(30_300L, 31_000L, "now")
                )
            )
        )
        val effTime = LyricsSyncCalculator.effectiveLyricsTimeMs(target, trackOffsetMs = 0L, globalOffsetMs = 0L)
        assertEquals(1, LyricsSyncCalculator.findActiveLineIndex(lines, effTime))
        assertEquals(1, LyricsSyncCalculator.findActiveWordIndex(lines[1].words, effTime))

        // Swipe artwork setting stays independent of double-tap setting
        val prefs = mutablePreferencesOf(
            CustomizationPersistence.KEY_GESTURE_SWIPE_ARTWORK to true,
            CustomizationPersistence.KEY_GESTURE_ARTWORK_DOUBLE_TAP_SKIP to false
        )
        val loaded = CustomizationPersistence.fromPreferences(prefs)
        assertTrue(loaded.gestures.swipeArtworkToSkip)
        assertFalse(loaded.gestures.artworkDoubleTapSkipEnabled)
    }

    @Test
    fun `17 - artwork double-tap settings persist, clamp invalid values, and reset properly`() {
        val prefs = mutablePreferencesOf(
            CustomizationPersistence.KEY_GESTURE_ARTWORK_DOUBLE_TAP_SKIP to false,
            CustomizationPersistence.KEY_GESTURE_ARTWORK_SKIP_DURATION_SEC to 25,
            CustomizationPersistence.KEY_GESTURE_ARTWORK_PROGRESSIVE_SKIP to true
        )
        val restored = CustomizationPersistence.fromPreferences(prefs)
        assertFalse(restored.gestures.artworkDoubleTapSkipEnabled)
        assertEquals(25, restored.gestures.artworkSkipDurationSeconds)
        assertTrue(restored.gestures.artworkProgressiveSkipEnabled)

        // Invalid out-of-range stored duration clamps cleanly
        prefs[CustomizationPersistence.KEY_GESTURE_ARTWORK_SKIP_DURATION_SEC] = 120
        assertEquals(30, CustomizationPersistence.fromPreferences(prefs).gestures.artworkSkipDurationSeconds)

        // Category reset restores defaults (enabled=true, duration=10s, progressive=false)
        CustomizationPersistence.resetCategory(prefs, CustomizationCategory.GESTURES)
        val afterReset = CustomizationPersistence.fromPreferences(prefs)
        assertTrue(afterReset.gestures.artworkDoubleTapSkipEnabled)
        assertEquals(10, afterReset.gestures.artworkSkipDurationSeconds)
        assertFalse(afterReset.gestures.artworkProgressiveSkipEnabled)
    }
}
