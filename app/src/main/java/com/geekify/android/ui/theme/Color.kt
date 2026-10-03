package com.geekify.android.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ---- Surfaces (near-black, Spotify style) ----
val InkBackground = Color(0xFF0D0D0D)
val InkPanel = Color(0xFF1F1F1F)
val InkElevated = Color(0xFF2A2A2A)
val InkGlass = Color(0x1FFFFFFF)
val InkGlassBorder = Color(0x24FFFFFF)

// ---- Accent ----
val SpotifyGreen = Color(0xFF1ED760)
val OnAccent = Color(0xFF000000)

// Legacy brand aliases: every older screen that still references these now picks up the green accent.
val BrandViolet = SpotifyGreen
val BrandMint = SpotifyGreen
val BrandPink = SpotifyGreen

// ---- Text ----
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFB3B3B3)
val TextMuted = Color(0xFF8A8A8A)

val ErrorRed = Color(0xFFF87171)
val SuccessGreen = SpotifyGreen

val BrandGradient = Brush.linearGradient(listOf(SpotifyGreen, SpotifyGreen))

/** "Liked Songs" tile: purple to mint, like Spotify. */
val LikedGradient = Brush.linearGradient(listOf(Color(0xFF4B2FE8), Color(0xFFA8E6CF)))

/** Browse / Search category tile colours. */
val CategoryColors = listOf(
    Color(0xFFE13300), Color(0xFF1E3264), Color(0xFF8400E7), Color(0xFFE8115B),
    Color(0xFF148A08), Color(0xFFBC5900), Color(0xFF477D95), Color(0xFFDC148C),
    Color(0xFF503750), Color(0xFF006450), Color(0xFF8D67AB), Color(0xFF509BF5)
)

val CardGradients = listOf(
    Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))),
    Brush.linearGradient(listOf(Color(0xFFEC4899), Color(0xFF8B5CF6))),
    Brush.linearGradient(listOf(Color(0xFF06B6D4), Color(0xFF3B82F6))),
    Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF06B6D4))),
    Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFEF4444)))
)
