package com.geekify.android.ui.theme

import android.app.Activity
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.WindowCompat

/**
 * One place to change the app typeface. To use the geometric face from the design
 * (e.g. Plus Jakarta Sans), add the .ttf files to res/font and point this at
 * FontFamily(Font(R.font.plus_jakarta_sans_regular), ...).
 */
val AppFont: FontFamily = FontFamily.SansSerif

private val DarkColorScheme = darkColorScheme(
    primary = Lime,
    secondary = Lime,
    tertiary = Lime,
    background = InkBackground,
    surface = InkPanel,
    surfaceVariant = InkElevated,
    onPrimary = OnAccent,
    onSecondary = OnAccent,
    onTertiary = OnAccent,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
    onError = InkBackground
)

@Composable
fun GeekifyTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(colorScheme = DarkColorScheme) {
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = AppFont)
        ) {
            content()
        }
    }
}
