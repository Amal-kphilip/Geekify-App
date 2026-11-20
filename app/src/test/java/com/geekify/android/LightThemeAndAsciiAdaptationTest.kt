package com.geekify.android

import androidx.compose.ui.graphics.Color
import com.geekify.android.customization.ThemeCustomizationConfig
import com.geekify.android.customization.ThemeMode
import com.geekify.android.ui.ascii.AsciiCatalog
import com.geekify.android.ui.ascii.AsciiRenderer
import com.geekify.android.ui.ascii.AsciiThemePalette
import com.geekify.android.ui.ascii.AsciiTuning
import com.geekify.android.ui.ascii.adaptRgbForLightBackground
import com.geekify.android.ui.glass.GlassRole
import com.geekify.android.ui.glass.GlassTintTokens
import com.geekify.android.ui.glass.resolveThemeGlassBorderColor
import com.geekify.android.ui.glass.resolveThemeGlassTintAlpha
import com.geekify.android.ui.theme.ActiveThemePalette
import com.geekify.android.ui.theme.resolveOnAccentColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightThemeAndAsciiAdaptationTest {

    // ---- 1. Central Theme & Accent Contrast ----

    @Test
    fun `activeThemePalette resolves light dark and pure black cleanly`() {
        val cfg = ThemeCustomizationConfig(themeMode = ThemeMode.LIGHT)
        ActiveThemePalette.apply(cfg, dark = false)
        assertFalse(ActiveThemePalette.isDark)
        assertEquals(Color(0xFFF6F6FA), ActiveThemePalette.background)
        assertEquals(Color(0xFF121118), ActiveThemePalette.textPrimary)

        val darkCfg = ThemeCustomizationConfig(themeMode = ThemeMode.DARK)
        ActiveThemePalette.apply(darkCfg, dark = true)
        assertTrue(ActiveThemePalette.isDark)
        assertEquals(Color(0xFFFFFFFF), ActiveThemePalette.textPrimary)

        ActiveThemePalette.apply(darkCfg, dark = true, overrideBackground = Color.Black)
        assertEquals(Color.Black, ActiveThemePalette.background)
    }

    @Test
    fun `resolveOnAccentColor picks high contrast foreground across accent presets`() {
        // High-luminance accents need dark ink
        assertEquals(Color(0xFF14110A), resolveOnAccentColor(Color(0xFFC8E65A))) // Electric Lime
        assertEquals(Color(0xFF14110A), resolveOnAccentColor(Color(0xFF38BDF8))) // Neon Cyan
        assertEquals(Color(0xFF14110A), resolveOnAccentColor(Color(0xFFFACC15))) // Royal Gold
        // Deep accents need white
        assertEquals(Color.White, resolveOnAccentColor(Color(0xFF4C1D95))) // Deep Violet
        assertEquals(Color.White, resolveOnAccentColor(Color(0xFF1E3A8A))) // Deep Blue
    }

    // ---- 2. ASCII Background & Reversible White/Near-White Color Adaptation ----

    @Test
    fun `asciiRenderer uses theme background instead of forcing dark 6 7 12 in light mode`() {
        val renderer = AsciiRenderer(AsciiTuning().apply { dayNight = 0f })

        renderer.applyTheme(AsciiThemePalette.DefaultLight)
        renderer.begin(dt = 0.016f, flockTime = 1f)
        assertEquals(244f, renderer.activeBg[0], 0.1f)
        assertEquals(245f, renderer.activeBg[1], 0.1f)
        assertEquals(250f, renderer.activeBg[2], 0.1f)

        renderer.applyTheme(AsciiThemePalette.PureBlack)
        renderer.begin(dt = 0.016f, flockTime = 2f)
        assertEquals(0f, renderer.activeBg[0], 0.1f)
        assertEquals(0f, renderer.activeBg[1], 0.1f)
        assertEquals(0f, renderer.activeBg[2], 0.1f)

        renderer.applyTheme(AsciiThemePalette.DefaultDark)
        renderer.begin(dt = 0.016f, flockTime = 3f)
        assertEquals(6f, renderer.activeBg[0], 0.1f)
        assertEquals(7f, renderer.activeBg[1], 0.1f)
        assertEquals(12f, renderer.activeBg[2], 0.1f)
    }

    @Test
    fun `adaptRgbForLightBackground makes white visible while preserving distinctive colors`() {
        val out = FloatArray(3)
        // Pure white on light #F4F5FA must be darkened to readable slate (not invisible white)
        adaptRgbForLightBackground(255f, 255f, 255f, 244f, 245f, 250f, out)
        assertTrue("White should darken significantly on light bg, got ${out.toList()}", out[0] < 140f && out[1] < 140f && out[2] < 145f)

        // Distinctive high-contrast purple/red/blue creature colors must remain UNCHANGED
        adaptRgbForLightBackground(118f, 42f, 188f, 244f, 245f, 250f, out)
        assertEquals(118f, out[0], 0.01f)
        assertEquals(42f, out[1], 0.01f)
        assertEquals(188f, out[2], 0.01f)

        adaptRgbForLightBackground(180f, 36f, 48f, 244f, 245f, 250f, out)
        assertEquals(180f, out[0], 0.01f)
        assertEquals(36f, out[1], 0.01f)
        assertEquals(48f, out[2], 0.01f)
    }

    @Test
    fun `repeated theme switches are completely reversible with zero palette drift`() {
        val renderer = AsciiRenderer(AsciiTuning().apply { dayNight = 0f })
        val origSpec = AsciiCatalog.byId("koi")
        val origTrailR = origSpec.trailDarkR

        renderer.applyTheme(AsciiThemePalette.DefaultDark)
        renderer.begin(0.016f, 0f)
        val darkWhite1 = renderer.fade(255f, 255f, 255f, 0f)

        // Switch repeatedly: Dark -> Light -> AMOLED -> Light -> Dark
        renderer.applyTheme(AsciiThemePalette.DefaultLight)
        renderer.begin(0.016f, 1f)
        val lightWhite = renderer.fade(255f, 255f, 255f, 0f)
        assertNotEquals("Light theme must adapt pure white", darkWhite1, lightWhite)

        renderer.applyTheme(AsciiThemePalette.PureBlack)
        renderer.begin(0.016f, 2f)
        val amoledWhite = renderer.fade(255f, 255f, 255f, 0f)
        assertEquals("AMOLED must preserve pure white", darkWhite1, amoledWhite)

        renderer.applyTheme(AsciiThemePalette.DefaultDark)
        renderer.begin(0.016f, 3f)
        val darkWhite2 = renderer.fade(255f, 255f, 255f, 0f)
        assertEquals("Returning to Dark must restore exact original color", darkWhite1, darkWhite2)
        assertEquals("Catalog source palette must never be mutated", origTrailR, origSpec.trailDarkR)
    }

    // ---- 3. Liquid Glass Light Theme Tokens ----

    @Test
    fun `liquid glass resolves frosted light opacity and dark border while preserving custom overrides`() {
        // Dark mode keeps low default alpha
        assertEquals(0.12f, resolveThemeGlassTintAlpha(isDarkTheme = true, GlassRole.Navigation, 0.12f), 0.001f)
        // Light mode lifts low default alpha to frosted Light nav token
        assertEquals(
            GlassTintTokens.NavPillFillOpacityLight,
            resolveThemeGlassTintAlpha(isDarkTheme = false, GlassRole.Navigation, 0.12f),
            0.001f
        )
        // Explicit higher custom opacity is preserved
        assertEquals(0.78f, resolveThemeGlassTintAlpha(isDarkTheme = false, GlassRole.Navigation, 0.78f), 0.001f)

        // Default white border adapts to subtle dark border on Light Theme
        assertEquals(GlassTintTokens.BorderLight, resolveThemeGlassBorderColor(isDarkTheme = false, 0xFFFFFFFFL))
        // Custom colored border is preserved
        assertEquals(Color(0xFFC8E65AL), resolveThemeGlassBorderColor(isDarkTheme = false, 0xFFC8E65AL))
    }
}
