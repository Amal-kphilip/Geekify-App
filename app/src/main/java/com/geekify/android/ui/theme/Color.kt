package com.geekify.android.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ---- Geekify "midnight aurora" surfaces ----
val InkBackground = Color(0xFF090B18)
val InkPanel = Color(0xFF12172B)
val InkElevated = Color(0xFF1B2340)
val InkGlass = Color(0x1FFFFFFF)
val InkGlassBorder = Color(0x24FFFFFF)

// ---- Accent ----
// Kept as a legacy symbol while the interface is migrated away from the old green palette.
val SpotifyGreen = Color(0xFF7C8CFF)
val OnAccent = Color(0xFF000000)

// Legacy brand aliases: every older screen that still references these now picks up the green accent.
val BrandViolet = Color(0xFF8B7CFF)
val BrandMint = Color(0xFF46E0C2)
val BrandPink = Color(0xFFFF7EB6)

// ---- Text ----
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFB3B3B3)
val TextMuted = Color(0xFF8A8A8A)

val ErrorRed = Color(0xFFF87171)
val SuccessGreen = BrandMint

val BrandGradient = Brush.linearGradient(listOf(BrandViolet, BrandMint))

/** "Liked Songs" tile: purple to mint, like Spotify. */
val LikedGradient = Brush.linearGradient(listOf(Color(0xFF6558D9), Color(0xFF4FC7D9)))

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
