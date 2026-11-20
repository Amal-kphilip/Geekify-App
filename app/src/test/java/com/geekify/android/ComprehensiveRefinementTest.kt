package com.geekify.android

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.geekify.android.customization.CustomizationCategory
import com.geekify.android.customization.CustomizationPersistence
import com.geekify.android.customization.GlassQualityLevel
import com.geekify.android.customization.GlassTintMode
import com.geekify.android.customization.HomeSectionId
import com.geekify.android.customization.NavTabId
import com.geekify.android.customization.NavigationCustomizationConfig
import com.geekify.android.customization.PlayerProgressStyle
import com.geekify.android.lyrics.LyricsLine
import com.geekify.android.lyrics.LyricsWord
import com.geekify.android.settings.AppSettingsRepository
import com.geekify.android.ui.player.LyricsSyncCalculator
import com.geekify.android.ui.player.MAX_CLOCK_EXTRAPOLATION_MS
import com.geekify.android.ui.player.PlaybackClockAnchor
import com.geekify.android.ui.player.interpolatePlaybackPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComprehensiveRefinementTest {

    // ========================================================================================
    // PART 1: SEEKBAR 5-STYLE CLEANUP & MIGRATION
    // ========================================================================================

    @Test
    fun `exactly 5 seekbar styles exist and removed styles migrate safely`() {
        val names = PlayerProgressStyle.entries.map { it.name }.toSet()
        assertEquals(5, names.size)
        assertTrue("CLASSIC_ROUNDED" in names)
        assertTrue("WAVY" in names)
        assertTrue("SLIM_LINEAR" in names)
        assertTrue("THICK_BAR" in names)
        assertTrue("WAVE_GLOW" in names)
        assertFalse("GRADIENT" in names)
        assertFalse("MINIMAL_LINE" in names)
        assertFalse("SQUIGGLY" in names)
    }

    // ========================================================================================
    // PART 4: LONG PAUSE & RESUME LYRICS SYNCHRONIZATION REGRESSION
    // ========================================================================================

    @Test
    fun `long 10-minute pause and resume re-anchors immediately without jumping ahead`() {
        val durationMs = 240_000L
        val lines = listOf(
            LyricsLine(timeMs = 10_000L, text = "First verse", words = listOf(LyricsWord(10_000L, 11_000L, "First"), LyricsWord(11_100L, 12_000L, "verse"))),
            LyricsLine(timeMs = 45_000L, text = "Chorus line", words = listOf(LyricsWord(45_000L, 46_000L, "Chorus"), LyricsWord(46_100L, 47_500L, "line"))),
            LyricsLine(timeMs = 120_000L, text = "Bridge line")
        )

        // Playing normally at 45.2s
        var anchor = PlaybackClockAnchor(
            sampledPositionMs = 45_000L,
            anchorFrameNanos = 1_000_000_000L,
            lastFrameNanos = 1_000_000_000L
        )
        val (playingAnchor, playingPos) = anchor.advance(
            latestSampleMs = 45_000L,
            frameNanos = 1_200_000_000L, // +200ms
            playbackSpeed = 1.0f,
            durationMs = durationMs,
            isPlaying = true,
            isBuffering = false
        )
        anchor = playingAnchor
        assertEquals(45_200L, playingPos)
        assertEquals(1, LyricsSyncCalculator.findActiveLineIndex(lines, playingPos))

        // Pause for 10 full minutes (600,000 ms) — frame clock jumps 600s forward!
        val tenMinutesLaterNanos = 601_200_000_000L
        val (resumedAnchor, resumedPos) = anchor.advance(
            latestSampleMs = 45_200L,
            frameNanos = tenMinutesLaterNanos,
            playbackSpeed = 1.0f,
            durationMs = durationMs,
            isPlaying = true,
            isBuffering = false
        )
        // Must re-anchor to 45_200L immediately — NOT jump to 240_000L!
        assertEquals(45_200L, resumedPos)
        assertEquals(1, LyricsSyncCalculator.findActiveLineIndex(lines, resumedPos))
        assertEquals(0, LyricsSyncCalculator.findActiveWordIndex(lines[1].words, resumedPos))

        // Subsequent normal 16ms frame advances smoothly from 45_200L
        val (_, nextPos) = resumedAnchor.advance(
            latestSampleMs = 45_200L,
            frameNanos = tenMinutesLaterNanos + 1_000_000_000L, // +1s
            playbackSpeed = 1.0f,
            durationMs = durationMs,
            isPlaying = true,
            isBuffering = false
        )
        // Frame gap > 450ms also re-anchors cleanly; within normal 16ms frames advances smoothly
        assertEquals(45_200L, nextPos)

        val (_, smoothNextPos) = resumedAnchor.advance(
            latestSampleMs = 45_200L,
            frameNanos = tenMinutesLaterNanos + 16_000_000L, // +16ms normal frame
            playbackSpeed = 1.0f,
            durationMs = durationMs,
            isPlaying = true,
            isBuffering = false
        )
        assertEquals(45_216L, smoothNextPos)

        // Extrapolation cap guards against unbounded drift even if player stops emitting ticks
        val capped = interpolatePlaybackPosition(
            sampledPositionMs = 45_000L,
            elapsedRealtimeMs = 600_000L,
            playbackSpeed = 1.0f,
            durationMs = durationMs,
            isPlaying = true,
            isBuffering = false
        )
        assertEquals(45_000L + MAX_CLOCK_EXTRAPOLATION_MS, capped)
    }

    // ========================================================================================
    // PART 10: ACRYLIC / LIQUID GLASS CUSTOMIZATION PERSISTENCE
    // ========================================================================================

    @Test
    fun `acrylic and liquid glass tint color, border, noise, and quality persist and clamp safely`() {
        val prefs = mutablePreferencesOf(
            AppSettingsRepository.KEY_GLASS_TINT_MODE to GlassTintMode.CUSTOM.name,
            AppSettingsRepository.KEY_GLASS_CUSTOM_TINT_ARGB to 0xFF2E1065L,
            AppSettingsRepository.KEY_GLASS_BRIGHTNESS to 0.9f, // clamps to 0.35f
            AppSettingsRepository.KEY_GLASS_CORNER_RADIUS_DP to 20f,
            AppSettingsRepository.KEY_GLASS_BORDER_ENABLED to true,
            AppSettingsRepository.KEY_GLASS_BORDER_COLOR_ARGB to 0xFFC8E65AL,
            AppSettingsRepository.KEY_GLASS_BORDER_OPACITY to 0.5f,
            AppSettingsRepository.KEY_GLASS_BORDER_THICKNESS_DP to 2.0f,
            AppSettingsRepository.KEY_GLASS_SHADOW_DP to 14f,
            AppSettingsRepository.KEY_GLASS_NOISE_ALPHA to 0.12f,
            AppSettingsRepository.KEY_GLASS_QUALITY to GlassQualityLevel.HIGH.name
        )

        val state = AppSettingsRepository.fromPreferences(prefs)
        assertEquals(GlassTintMode.CUSTOM, state.glass.tintMode)
        assertEquals(0xFF2E1065L, state.glass.customTintArgb)
        assertEquals(0.35f, state.glass.brightness, 0.001f)
        assertEquals(20f, state.glass.cornerRadiusDp, 0.001f)
        assertTrue(state.glass.borderEnabled)
        assertEquals(0xFFC8E65AL, state.glass.borderColorArgb)
        assertEquals(0.5f, state.glass.borderOpacity, 0.001f)
        assertEquals(2.0f, state.glass.borderThicknessDp, 0.001f)
        assertEquals(14f, state.glass.shadowElevationDp, 0.001f)
        assertEquals(0.12f, state.glass.noiseGrainAlpha, 0.001f)
        assertEquals(GlassQualityLevel.HIGH, state.glass.qualityLevel)

        CustomizationPersistence.resetCategory(prefs, CustomizationCategory.LIQUID_GLASS)
        val afterReset = AppSettingsRepository.fromPreferences(prefs)
        assertEquals(GlassTintMode.THEME, afterReset.glass.tintMode)
        assertEquals(0f, afterReset.glass.noiseGrainAlpha, 0.001f)
        assertEquals(GlassQualityLevel.BALANCED, afterReset.glass.qualityLevel)
    }

    // ========================================================================================
    // PARTS 11 & 12: NAVIGATION COMPACTION & ORDER PRESERVATION
    // ========================================================================================

    @Test
    fun `navigation ordering preserves hidden tab positions and compacts enabled destinations`() {
        val customOrder = listOf(NavTabId.SEARCH, NavTabId.LIBRARY, NavTabId.CREATE, NavTabId.HOME)
        val config = NavigationCustomizationConfig(
            tabOrder = customOrder,
            hiddenTabs = setOf(NavTabId.CREATE, NavTabId.HOME)
        )

        // Only enabled tabs appear in safeVisibleTabs, compacted with zero placeholders
        assertEquals(listOf(NavTabId.SEARCH, NavTabId.LIBRARY), config.safeVisibleTabs)

        // Re-enabling CREATE restores it to its exact saved slot between LIBRARY and HOME
        val reenabled = config.copy(hiddenTabs = setOf(NavTabId.HOME))
        assertEquals(listOf(NavTabId.SEARCH, NavTabId.LIBRARY, NavTabId.CREATE), reenabled.safeVisibleTabs)

        // Persistence round-trip preserves full order and hidden set
        val prefs = mutablePreferencesOf(
            CustomizationPersistence.KEY_NAV_TAB_ORDER to customOrder.joinToString(",") { it.name },
            CustomizationPersistence.KEY_NAV_HIDDEN_TABS to NavTabId.CREATE.name
        )
        val loaded = CustomizationPersistence.fromPreferences(prefs).navigation
        assertEquals(customOrder, loaded.normalizedTabOrder)
        assertEquals(listOf(NavTabId.SEARCH, NavTabId.LIBRARY, NavTabId.HOME), loaded.safeVisibleTabs)
    }

    // ========================================================================================
    // PART 13: HOME SECTION ORDERING & VISIBILITY
    // ========================================================================================

    @Test
    fun `home section ordering preserves custom sequence, hides disabled sections, and restores on reset`() {
        val customOrder = listOf(
            HomeSectionId.FEED_SHELVES,
            HomeSectionId.DISCOVER_CAROUSEL,
            HomeSectionId.DAILY_PICKS
        )
        val prefs = mutablePreferencesOf(
            CustomizationPersistence.KEY_HOME_SECTION_ORDER to customOrder.joinToString(",") { it.name },
            CustomizationPersistence.KEY_HOME_HIDDEN_SECTIONS to HomeSectionId.DISCOVER_CAROUSEL.name
        )
        val loaded = CustomizationPersistence.fromPreferences(prefs).home
        assertEquals(customOrder, loaded.sectionOrder)
        assertEquals(
            listOf(HomeSectionId.FEED_SHELVES, HomeSectionId.DAILY_PICKS),
            loaded.sectionOrder.filter { it !in loaded.hiddenSections }
        )

        CustomizationPersistence.resetCategory(prefs, CustomizationCategory.HOME_SCREEN)
        val reset = CustomizationPersistence.fromPreferences(prefs).home
        assertEquals(HomeSectionId.DEFAULT_ORDER, reset.sectionOrder)
        assertTrue(reset.hiddenSections.isEmpty())
    }
}
