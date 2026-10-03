package com.geekify.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.geekify.android.ui.theme.InkBackground

/**
 * Screen background. Kept under its old name so every screen keeps compiling.
 * Plain near-black by default; pass [tint] for the Spotify-style colour that fades into black
 * from the top (it animates smoothly when the colour changes, e.g. on track change).
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    tint: Color = InkBackground,
    content: @Composable BoxScope.() -> Unit
) {
    val animatedTint by animateColorAsState(tint, tween(600), label = "bgTint")
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(InkBackground)
            .background(
                Brush.verticalGradient(
                    0f to animatedTint,
                    0.55f to InkBackground,
                    1f to InkBackground
                )
            ),
        content = content
    )
}
