package com.geekify.android.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.geekify.android.customization.ThemeCustomizationConfig

/**
 * Reactive theme palette updated in real time by [GeekifyTheme] from [ThemeCustomizationConfig],
 * Material You dynamic colors, and ThemeMode (Auto / Light / Dark / Pure Black).
 * Backing top-level color getters with Compose state ensures every screen in the app updates
 * immediately without restart when the user changes theme mode, palette style, or accent colors.
 */
object ActiveThemePalette {
    var isDark by mutableStateOf(true)
    var background by mutableStateOf(Color(0xFF0B0A10))
    var panel by mutableStateOf(Color(0xFF15131C))
    var elevated by mutableStateOf(Color(0xFF26242E))
    var accent by mutableStateOf(Color(0xFFC8E65A))
    var secondaryAccent by mutableStateOf(Color(0xFFCF9CF1))
    var onAccent by mutableStateOf(Color(0xFF14110A))
    var textPrimary by mutableStateOf(Color(0xFFFFFFFF))
    var textSecondary by mutableStateOf(Color(0xFFA7A4B2))
    var textMuted by mutableStateOf(Color(0xFF706D7B))
    var navPill by mutableStateOf(Color(0xEB2D2C31))
    var navPillBorder by mutableStateOf(Color(0x26FFFFFF))
    var glass by mutableStateOf(Color(0x24FFFFFF))
    var glassBorder by mutableStateOf(Color(0x1FFFFFFF))
    var uiEnhancements by mutableStateOf(true)

    fun apply(
        config: ThemeCustomizationConfig,
        dark: Boolean = true,
        overridePrimary: Color? = null,
        overrideSecondary: Color? = null,
        overrideBackground: Color? = null,
        overridePanel: Color? = null,
        overrideElevated: Color? = null
    ) {
        val nextBg = overrideBackground ?: if (dark) config.resolvedBackground else Color(0xFFF6F6FA)
        val nextPanel = overridePanel ?: if (dark) config.resolvedPanel else Color(0xFFFFFFFF)
        val nextElevated = overrideElevated ?: if (dark) config.resolvedElevated else Color(0xFFE9E9F2)
        val nextAccent = overridePrimary ?: config.resolvedPrimary
        val nextSecondary = overrideSecondary ?: config.resolvedSecondary
        val nextOnAccent = if (nextAccent.luminance() > 0.55f) Color(0xFF14110A) else Color(0xFFFFFFFF)

        if (isDark != dark) isDark = dark
        if (background != nextBg) background = nextBg
        if (panel != nextPanel) panel = nextPanel
        if (elevated != nextElevated) elevated = nextElevated
        if (accent != nextAccent) accent = nextAccent
        if (secondaryAccent != nextSecondary) secondaryAccent = nextSecondary
        if (onAccent != nextOnAccent) onAccent = nextOnAccent
        if (uiEnhancements != config.uiEnhancements) uiEnhancements = config.uiEnhancements

        val nextTextPrimary = if (dark) Color(0xFFFFFFFF) else Color(0xFF121118)
        val nextTextSecondary = if (dark) Color(0xFFA7A4B2) else Color(0xFF565466)
        val nextTextMuted = if (dark) Color(0xFF706D7B) else Color(0xFF838092)
        val nextNavPill = if (dark) Color(0xEB2D2C31) else Color(0xEBF2F2F8)
        val nextNavBorder = if (dark) Color(0x26FFFFFF) else Color(0x1F000000)
        val nextGlass = if (dark) Color(0x24FFFFFF) else Color(0x14000000)
        val nextGlassBorder = if (dark) Color(0x1FFFFFFF) else Color(0x18000000)

        if (textPrimary != nextTextPrimary) textPrimary = nextTextPrimary
        if (textSecondary != nextTextSecondary) textSecondary = nextTextSecondary
        if (textMuted != nextTextMuted) textMuted = nextTextMuted
        if (navPill != nextNavPill) navPill = nextNavPill
        if (navPillBorder != nextNavBorder) navPillBorder = nextNavBorder
        if (glass != nextGlass) glass = nextGlass
        if (glassBorder != nextGlassBorder) glassBorder = nextGlassBorder
    }
}

// ---- Surfaces: reactive theme variants ----
val InkBackground: Color get() = ActiveThemePalette.background
val InkPanel: Color get() = ActiveThemePalette.panel
val InkElevated: Color get() = ActiveThemePalette.elevated
val InkGlass: Color get() = ActiveThemePalette.glass
val InkGlassBorder: Color get() = ActiveThemePalette.glassBorder

/** Floating bottom navigation / mini-player pill (translucent frosted glass). */
val NavPill: Color get() = ActiveThemePalette.navPill
val NavPillBorder: Color get() = ActiveThemePalette.navPillBorder

// ---- Accent: reactive primary accent ----
val Lime: Color get() = ActiveThemePalette.accent
val OnAccent: Color get() = ActiveThemePalette.onAccent

/** Legacy name kept so every older screen picks up the active accent. */
val SpotifyGreen: Color get() = ActiveThemePalette.accent

// ---- Card pastels (Discover cards, category tiles) ----
val Lilac: Color get() = ActiveThemePalette.secondaryAccent
val LilacDeep = Color(0xFF3A1250)
val Peach = Color(0xFFF2B590)
val Sky = Color(0xFF9CCBF2)
val Rose = Color(0xFFF29CC4)
val OnPastel = Color(0xFF1A0E24)

// Legacy brand aliases
val BrandViolet: Color get() = ActiveThemePalette.secondaryAccent
val BrandMint: Color get() = ActiveThemePalette.accent
val BrandPink = Rose

// ---- Text ----
val TextPrimary: Color get() = ActiveThemePalette.textPrimary
val TextSecondary: Color get() = ActiveThemePalette.textSecondary
val TextMuted: Color get() = ActiveThemePalette.textMuted

val ErrorRed = Color(0xFFF87171)
val SuccessGreen: Color get() = ActiveThemePalette.accent

val BrandGradient: Brush get() = Brush.linearGradient(listOf(Lilac, Lime))

/** "Liked Songs" tile. */
val LikedGradient = Brush.linearGradient(listOf(Color(0xFFB57CF0), Color(0xFFF08AB8)))

/** Browse / Search category tile colours (pastels; text on them is [OnPastel]). */
val CategoryColors: List<Color> get() = listOf(Lilac, Lime, Peach, Sky, Rose, Color(0xFF9CF2D2))

/** Rotating colours for the "Curated & trending" cards. */
val DiscoverColors: List<Color> get() = listOf(Lilac, Lime, Peach, Sky, Rose)

val CardGradients: List<Brush> get() = listOf(
    Brush.linearGradient(listOf(Lilac, Rose)),
    Brush.linearGradient(listOf(Lime, Color(0xFF9CF2D2))),
    Brush.linearGradient(listOf(Peach, Rose)),
    Brush.linearGradient(listOf(Sky, Lilac)),
    Brush.linearGradient(listOf(Rose, Peach))
)
