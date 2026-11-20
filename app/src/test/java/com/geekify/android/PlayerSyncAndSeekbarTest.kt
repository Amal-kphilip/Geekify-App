package com.geekify.android

import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.geekify.android.customization.CustomizationCategory
import com.geekify.android.customization.CustomizationPersistence
import com.geekify.android.customization.PlayerProgressStyle
import com.geekify.android.customization.SeekbarCustomizationConfig
import com.geekify.android.customization.SeekbarGradientPreset
import com.geekify.android.customization.SeekbarThumbStyle
import com.geekify.android.lyrics.LyricsLine
import com.geekify.android.lyrics.LyricsWord
import com.geekify.android.ui.player.LyricsSyncCalculator
import com.geekify.android.ui.player.PendingSeekPosition
import com.geekify.android.ui.player.calculateProgressFraction
import com.geekify.android.ui.player.formatPlaybackTime
import com.geekify.android.ui.player.interpolatePlaybackPosition
import com.geekify.android.ui.player.resolveSeekbarPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSyncAndSeekbarTest {

    // ========================================================================================
    // 1. LYRICS SYNCHRONIZATION & OFFSET TESTS
    // ========================================================================================

    @Test
    fun `effectiveLyricsTimeMs applies track offset, global offset, and anticipation strictly once`() {
        val effective = LyricsSyncCalculator.effectiveLyricsTimeMs(
            playbackPositionMs = 12_000L,
            trackOffsetMs = 500L,
            globalOffsetMs = -250L,
            anticipationMs = 100L
        )
        assertEquals(12_350L, effective)
    }

    @Test
    fun `targetSeekPositionMs inverts offsets accurately and never returns negative`() {
        val target = LyricsSyncCalculator.targetSeekPositionMs(
            lyricTimestampMs = 15_000L,
            trackOffsetMs = 500L,
            globalOffsetMs = -250L,
            durationMs = 180_000L
        )
        // Round-trip: effectiveLyricsTimeMs(target, ...) == lyricTimestampMs
        assertEquals(14_750L, target)
        assertEquals(
            15_000L,
            LyricsSyncCalculator.effectiveLyricsTimeMs(target, 500L, -250L, 0L)
        )

        // Clamps to 0 when offsets exceed timestamp
        assertEquals(
            0L,
            LyricsSyncCalculator.targetSeekPositionMs(
                lyricTimestampMs = 200L,
                trackOffsetMs = 1_000L,
                globalOffsetMs = 500L,
                durationMs = 180_000L
            )
        )
    }

    @Test
    fun `findActiveLineIndex handles before-first-line, exact boundaries, duplicates, and unsynced lines`() {
        val lines = listOf(
            LyricsLine(timeMs = 5_000L, text = "Line 1"),
            LyricsLine(timeMs = 10_000L, text = "Line 2"),
            LyricsLine(timeMs = 10_000L, text = "Line 2 duplicate timestamp"),
            LyricsLine(timeMs = 18_500L, text = "Line 3")
        )

        assertEquals(-1, LyricsSyncCalculator.findActiveLineIndex(lines, 0L))
        assertEquals(-1, LyricsSyncCalculator.findActiveLineIndex(lines, 4_999L))
        assertEquals(0, LyricsSyncCalculator.findActiveLineIndex(lines, 5_000L))
        assertEquals(0, LyricsSyncCalculator.findActiveLineIndex(lines, 9_999L))
        // Duplicate timestamp resolves cleanly to the latest matching line
        assertEquals(2, LyricsSyncCalculator.findActiveLineIndex(lines, 10_000L))
        assertEquals(3, LyricsSyncCalculator.findActiveLineIndex(lines, 99_000L))

        val unsynced = listOf(LyricsLine(timeMs = -1L, text = "Unsynced"))
        assertEquals(-1, LyricsSyncCalculator.findActiveLineIndex(unsynced, 5_000L))
    }

    @Test
    fun `findActiveWordIndex tracks word-level timestamps accurately across playback`() {
        val line = LyricsLine(
            timeMs = 10_000L,
            text = "Hello world today",
            words = listOf(
                LyricsWord(startMs = 10_000L, endMs = 10_600L, text = "Hello"),
                LyricsWord(startMs = 10_700L, endMs = 11_400L, text = "world"),
                LyricsWord(startMs = 11_500L, endMs = 12_200L, text = "today")
            )
        )

        assertEquals(-1, LyricsSyncCalculator.findActiveWordIndex(line.words, 9_900L))
        assertEquals(0, LyricsSyncCalculator.findActiveWordIndex(line.words, 10_000L))
        assertEquals(0, LyricsSyncCalculator.findActiveWordIndex(line.words, 10_650L)) // gap between word 0 and 1
        assertEquals(1, LyricsSyncCalculator.findActiveWordIndex(line.words, 10_700L))
        assertEquals(2, LyricsSyncCalculator.findActiveWordIndex(line.words, 11_500L))
        assertEquals(2, LyricsSyncCalculator.findActiveWordIndex(line.words, 15_000L))
    }

    // ========================================================================================
    // 2. PLAYBACK CLOCK INTERPOLATION & SEEKBAR ENGINE TESTS
    // ========================================================================================

    @Test
    fun `interpolatePlaybackPosition advances smoothly with playbackSpeed and freezes on pause or buffering`() {
        // Playing at 1.0x for 500ms
        assertEquals(
            10_500L,
            interpolatePlaybackPosition(
                sampledPositionMs = 10_000L,
                elapsedRealtimeMs = 500L,
                playbackSpeed = 1.0f,
                durationMs = 180_000L,
                isPlaying = true,
                isBuffering = false
            )
        )

        // Playing at 1.5x for 1000ms -> +1500ms
        assertEquals(
            11_500L,
            interpolatePlaybackPosition(
                sampledPositionMs = 10_000L,
                elapsedRealtimeMs = 1_000L,
                playbackSpeed = 1.5f,
                durationMs = 180_000L,
                isPlaying = true,
                isBuffering = false
            )
        )

        // Paused -> frozen at sampledPositionMs
        assertEquals(
            10_000L,
            interpolatePlaybackPosition(
                sampledPositionMs = 10_000L,
                elapsedRealtimeMs = 2_000L,
                playbackSpeed = 1.0f,
                durationMs = 180_000L,
                isPlaying = false,
                isBuffering = false
            )
        )

        // Buffering -> frozen at sampledPositionMs
        assertEquals(
            10_000L,
            interpolatePlaybackPosition(
                sampledPositionMs = 10_000L,
                elapsedRealtimeMs = 2_000L,
                playbackSpeed = 1.0f,
                durationMs = 180_000L,
                isPlaying = true,
                isBuffering = true
            )
        )

        // Clamped to track duration
        assertEquals(
            180_000L,
            interpolatePlaybackPosition(
                sampledPositionMs = 179_500L,
                elapsedRealtimeMs = 2_000L,
                playbackSpeed = 1.0f,
                durationMs = 180_000L,
                isPlaying = true,
                isBuffering = false
            )
        )
    }

    @Test
    fun `PendingSeekPosition prevents snap-back until ExoPlayer acknowledges or timeout expires`() {
        val createdNanos = 1_000_000_000L
        val pending = PendingSeekPosition(
            targetMs = 90_000L,
            previousPositionMs = 15_000L,
            createdAtNanos = createdNanos
        )

        // Stale player emission still at old position -> NOT acknowledged
        assertFalse(pending.isAcknowledged(15_200L))
        assertFalse(pending.isExpired(createdNanos + 500_000_000L))

        // Player arrives near target -> acknowledged!
        assertTrue(pending.isAcknowledged(90_200L))
        assertTrue(pending.isAcknowledged(89_800L))

        // Timeout fallback after DEFAULT_TIMEOUT_MS (2.5s) -> expired so UI never locks permanently
        assertTrue(pending.isExpired(createdNanos + 2_600_000_000L))
    }

    @Test
    fun `calculateProgressFraction and formatPlaybackTime handle edge cases safely`() {
        assertEquals(0f, calculateProgressFraction(5_000L, 0L), 0.0001f)
        assertEquals(0f, calculateProgressFraction(5_000L, -100L), 0.0001f)
        assertEquals(0.5f, calculateProgressFraction(60_000L, 120_000L), 0.0001f)
        assertEquals(1f, calculateProgressFraction(150_000L, 120_000L), 0.0001f)

        assertEquals("0:00", formatPlaybackTime(-5_000L))
        assertEquals("0:00", formatPlaybackTime(0L))
        assertEquals("3:05", formatPlaybackTime(185_000L))
        assertEquals("1:02:09", formatPlaybackTime(3_729_000L))
    }

    // ========================================================================================
    // 3. SEEKBAR CUSTOMIZATION PERSISTENCE, MIGRATION & PALETTE TESTS
    // ========================================================================================

    @Test
    fun `seekbar persistence supports all 5 styles, 6 thumb styles, clamping, migration, and reset`() {
        assertEquals(5, PlayerProgressStyle.entries.size)
        assertEquals(6, SeekbarThumbStyle.entries.size)
        assertEquals(5, SeekbarGradientPreset.entries.size)

        // Legacy & removed styles migrate safely
        val legacyDefault = CustomizationPersistence.fromPreferences(
            mutablePreferencesOf(
                CustomizationPersistence.KEY_PLAYER_PROGRESS_STYLE to "DEFAULT",
                CustomizationPersistence.KEY_PLAYER_PROGRESS_HEIGHT to 6f
            )
        )
        assertEquals(PlayerProgressStyle.CLASSIC_ROUNDED, legacyDefault.player.progressStyle)
        assertEquals(PlayerProgressStyle.CLASSIC_ROUNDED, legacyDefault.seekbar.style)
        assertEquals(6f, legacyDefault.seekbar.thicknessDp, 0.001f)

        assertEquals(
            PlayerProgressStyle.WAVY,
            CustomizationPersistence.fromPreferences(
                mutablePreferencesOf(CustomizationPersistence.KEY_SEEKBAR_STYLE to "SQUIGGLY")
            ).seekbar.style
        )
        assertEquals(
            PlayerProgressStyle.SLIM_LINEAR,
            CustomizationPersistence.fromPreferences(
                mutablePreferencesOf(CustomizationPersistence.KEY_SEEKBAR_STYLE to "MINIMAL_LINE")
            ).seekbar.style
        )
        assertEquals(
            PlayerProgressStyle.CLASSIC_ROUNDED,
            CustomizationPersistence.fromPreferences(
                mutablePreferencesOf(CustomizationPersistence.KEY_SEEKBAR_STYLE to "GRADIENT")
            ).seekbar.style
        )

        // Out-of-range seekbar numeric values clamp cleanly
        val clampedPrefs = mutablePreferencesOf(
            CustomizationPersistence.KEY_SEEKBAR_STYLE to PlayerProgressStyle.WAVE_GLOW.name,
            CustomizationPersistence.KEY_SEEKBAR_THUMB_STYLE to SeekbarThumbStyle.VERTICAL_PILL.name,
            CustomizationPersistence.KEY_SEEKBAR_THUMB_SIZE_DP to 99f,
            CustomizationPersistence.KEY_SEEKBAR_THICKNESS_DP to 1f,
            CustomizationPersistence.KEY_SEEKBAR_CORNER_RADIUS_DP to 50f,
            CustomizationPersistence.KEY_SEEKBAR_INACTIVE_ALPHA to 0.01f,
            CustomizationPersistence.KEY_SEEKBAR_WAVE_AMP to 30f,
            CustomizationPersistence.KEY_SEEKBAR_WAVE_FREQ to 0.1f,
            CustomizationPersistence.KEY_SEEKBAR_GLOW_INTENSITY to 5f,
            CustomizationPersistence.KEY_SEEKBAR_WAVE_SPEED to 10f,
            CustomizationPersistence.KEY_SEEKBAR_TIME_SCALE to 3f,
            CustomizationPersistence.KEY_SEEKBAR_TOUCH_HEIGHT to 10f
        )
        val clamped = CustomizationPersistence.fromPreferences(clampedPrefs)
        assertEquals(PlayerProgressStyle.WAVE_GLOW, clamped.seekbar.style)
        assertEquals(SeekbarThumbStyle.VERTICAL_PILL, clamped.seekbar.thumbStyle)
        assertTrue(clamped.seekbar.thumbSizeDp <= 24f)
        assertTrue(clamped.seekbar.thicknessDp >= 2f)
        assertTrue(clamped.seekbar.cornerRadiusDp <= 12f)
        assertTrue(clamped.seekbar.inactiveTrackAlpha >= 0.08f)
        assertTrue(clamped.seekbar.waveAmplitudeDp <= 10f)
        assertTrue(clamped.seekbar.waveFrequency >= 0.5f)
        assertTrue(clamped.seekbar.glowIntensity <= 1f)
        assertTrue(clamped.seekbar.waveSpeed <= 2.5f)
        assertTrue(clamped.seekbar.timeLabelScale <= 1.35f)
        assertTrue(clamped.seekbar.touchTargetHeightDp >= 28f)

        // Resetting PLAYER_APPEARANCE restores default seekbar settings
        CustomizationPersistence.resetCategory(clampedPrefs, CustomizationCategory.PLAYER_APPEARANCE)
        val afterReset = CustomizationPersistence.fromPreferences(clampedPrefs)
        assertEquals(SeekbarCustomizationConfig(), afterReset.seekbar)
    }

    @Test
    fun `resolveSeekbarPalette honors custom colors and gradient toggle`() {
        val defaultAccent = Color(0xFFC8E65A)
        val customActive = 0xFF38E8FFL
        val customThumb = 0xFFFF7A6BL

        val config = SeekbarCustomizationConfig(
            style = PlayerProgressStyle.CLASSIC_ROUNDED,
            useCustomActiveColor = true,
            customActiveColorArgb = customActive,
            useCustomThumbColor = true,
            customThumbColorArgb = customThumb,
            gradientEnabled = true,
            gradientPreset = SeekbarGradientPreset.AURORA_NEON
        )
        val palette = resolveSeekbarPalette(config, defaultAccent)
        assertEquals(Color(customActive), palette.activeColor)
        assertEquals(Color(customThumb), palette.thumbColor)
        assertTrue(palette.useGradient)
        assertEquals(Color(SeekbarGradientPreset.AURORA_NEON.startArgb), palette.gradientStart)
        assertEquals(Color(SeekbarGradientPreset.AURORA_NEON.endArgb), palette.gradientEnd)
    }
}
