package com.geekify.android.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ---- Surfaces: near-black with a faint violet cast ----
val InkBackground = Color(0xFF0B0A10)
val InkPanel = Color(0xFF15131C)
val InkElevated = Color(0xFF26242E)      // chips, circular icon buttons, art placeholders
val InkGlass = Color(0x24FFFFFF)
val InkGlassBorder = Color(0x1FFFFFFF)

/** Floating bottom navigation / mini-player pill (translucent warm grey, like frosted glass). */
val NavPill = Color(0xEB2D2C31)
val NavPillBorder = Color(0x26FFFFFF)

// ---- Accent: lime ----
val Lime = Color(0xFFC8E65A)
val OnAccent = Color(0xFF14110A)

/** Legacy name kept so every older screen picks up the lime accent. */
val SpotifyGreen = Lime

// ---- Card pastels (Discover cards, category tiles) ----
val Lilac = Color(0xFFCF9CF1)
val LilacDeep = Color(0xFF3A1250)
val Peach = Color(0xFFF2B590)
val Sky = Color(0xFF9CCBF2)
val Rose = Color(0xFFF29CC4)
val OnPastel = Color(0xFF1A0E24)

// Legacy brand aliases
val BrandViolet = Lilac
val BrandMint = Lime
val BrandPink = Rose

// ---- Text ----
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFA7A4B2)
val TextMuted = Color(0xFF706D7B)

val ErrorRed = Color(0xFFF87171)
val SuccessGreen = Lime

val BrandGradient = Brush.linearGradient(listOf(Lilac, Lime))

/** "Liked Songs" tile. */
val LikedGradient = Brush.linearGradient(listOf(Color(0xFFB57CF0), Color(0xFFF08AB8)))

/** Browse / Search category tile colours (pastels; text on them is [OnPastel]). */
val CategoryColors = listOf(Lilac, Lime, Peach, Sky, Rose, Color(0xFF9CF2D2))

/** Rotating colours for the "Curated & trending" cards. */
val DiscoverColors = listOf(Lilac, Lime, Peach, Sky, Rose)

val CardGradients = listOf(
    Brush.linearGradient(listOf(Lilac, Rose)),
    Brush.linearGradient(listOf(Lime, Color(0xFF9CF2D2))),
    Brush.linearGradient(listOf(Peach, Rose)),
    Brush.linearGradient(listOf(Sky, Lilac)),
    Brush.linearGradient(listOf(Rose, Peach))
)
