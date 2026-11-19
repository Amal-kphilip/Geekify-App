package com.geekify.android.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.geekify.android.customization.ThemeCustomizationConfig

/**
 * Reactive theme palette updated in real time by [GeekifyTheme] from [ThemeCustomizationConfig].
 * Backing top-level color getters with Compose state ensures every screen in the app updates
 * immediately without restart when the user changes dark variant or accent palette.
 */
object ActiveThemePalette {
    var background by mutableStateOf(Color(0xFF0B0A10))
    var panel by mutableStateOf(Color(0xFF15131C))
    var elevated by mutableStateOf(Color(0xFF26242E))
    var accent by mutableStateOf(Color(0xFFC8E65A))
    var secondaryAccent by mutableStateOf(Color(0xFFCF9CF1))
    var onAccent by mutableStateOf(Color(0xFF14110A))

    fun apply(config: ThemeCustomizationConfig) {
        if (background != config.resolvedBackground) background = config.resolvedBackground
        if (panel != config.resolvedPanel) panel = config.resolvedPanel
        if (elevated != config.resolvedElevated) elevated = config.resolvedElevated
        if (accent != config.resolvedPrimary) accent = config.resolvedPrimary
        if (secondaryAccent != config.resolvedSecondary) secondaryAccent = config.resolvedSecondary
        if (onAccent != config.resolvedOnAccent) onAccent = config.resolvedOnAccent
    }
}

// ---- Surfaces: reactive dark theme variants ----
val InkBackground: Color get() = ActiveThemePalette.background
val InkPanel: Color get() = ActiveThemePalette.panel
val InkElevated: Color get() = ActiveThemePalette.elevated
val InkGlass = Color(0x24FFFFFF)
val InkGlassBorder = Color(0x1FFFFFFF)

/** Floating bottom navigation / mini-player pill (translucent warm grey, like frosted glass). */
val NavPill = Color(0xEB2D2C31)
val NavPillBorder = Color(0x26FFFFFF)

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
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFA7A4B2)
val TextMuted = Color(0xFF706D7B)

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
