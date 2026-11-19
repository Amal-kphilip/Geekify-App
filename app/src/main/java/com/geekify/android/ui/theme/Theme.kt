package com.geekify.android.ui.theme

import android.app.Activity
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import com.geekify.android.R
import com.geekify.android.customization.AppFontChoice
import com.geekify.android.customization.LocalCustomizationState
import com.geekify.android.customization.TypographyDensityConfig
import com.geekify.android.settings.AppSettingsRepository
import com.geekify.android.settings.AppSettingsState
import com.geekify.android.settings.LocalAppSettings
import com.geekify.android.settings.LocalGlassConfig
import org.koin.core.context.GlobalContext

val MontserratFontFamily = FontFamily(
    Font(R.font.montserrat_regular, FontWeight.Normal),
    Font(R.font.montserrat_medium, FontWeight.Medium),
    Font(R.font.montserrat_semibold, FontWeight.SemiBold),
    Font(R.font.montserrat_bold, FontWeight.Bold)
)

object ActiveTypographyState {
    var fontFamily: FontFamily by mutableStateOf(FontFamily.SansSerif)
        private set
    var fontScale: Float by mutableStateOf(1.0f)
        private set
    var titleScale: Float by mutableStateOf(1.0f)
        private set
    var subtitleScale: Float by mutableStateOf(1.0f)
        private set

    fun apply(config: TypographyDensityConfig) {
        val resolved = config.fontChoice.resolveFontFamily()
        if (fontFamily != resolved) fontFamily = resolved
        val clampedFont = config.fontScale.coerceIn(0.85f, 1.25f)
        if (fontScale != clampedFont) fontScale = clampedFont
        val clampedTitle = config.titleScale.coerceIn(0.85f, 1.25f)
        if (titleScale != clampedTitle) titleScale = clampedTitle
        val clampedSub = config.subtitleScale.coerceIn(0.85f, 1.25f)
        if (subtitleScale != clampedSub) subtitleScale = clampedSub
    }
}

/** Reactive global font family reflecting the user's current selection in Personalization Studio. */
val AppFont: FontFamily
    get() = ActiveTypographyState.fontFamily

fun AppFontChoice.resolveFontFamily(): FontFamily = when (this) {
    AppFontChoice.SYSTEM_SANS -> FontFamily.SansSerif
    AppFontChoice.MONTSERRAT -> MontserratFontFamily
    AppFontChoice.SERIF_EDITORIAL -> FontFamily.Serif
    AppFontChoice.MONOSPACE_TECH -> FontFamily.Monospace
}

private val DefaultMaterialTypography = Typography()

fun buildGeekifyTypography(
    fontFamily: FontFamily,
    titleScale: Float = 1.0f,
    subtitleScale: Float = 1.0f
): Typography {
    fun TextStyle.withFont(scale: Float = 1.0f): TextStyle = copy(
        fontFamily = fontFamily,
        fontSize = if (scale == 1.0f) fontSize else fontSize * scale,
        lineHeight = if (scale == 1.0f) lineHeight else lineHeight * scale
    )
    return Typography(
        displayLarge = DefaultMaterialTypography.displayLarge.withFont(titleScale),
        displayMedium = DefaultMaterialTypography.displayMedium.withFont(titleScale),
        displaySmall = DefaultMaterialTypography.displaySmall.withFont(titleScale),
        headlineLarge = DefaultMaterialTypography.headlineLarge.withFont(titleScale),
        headlineMedium = DefaultMaterialTypography.headlineMedium.withFont(titleScale),
        headlineSmall = DefaultMaterialTypography.headlineSmall.withFont(titleScale),
        titleLarge = DefaultMaterialTypography.titleLarge.withFont(titleScale),
        titleMedium = DefaultMaterialTypography.titleMedium.withFont(titleScale),
        titleSmall = DefaultMaterialTypography.titleSmall.withFont(titleScale),
        bodyLarge = DefaultMaterialTypography.bodyLarge.withFont(1.0f),
        bodyMedium = DefaultMaterialTypography.bodyMedium.withFont(1.0f),
        bodySmall = DefaultMaterialTypography.bodySmall.withFont(subtitleScale),
        labelLarge = DefaultMaterialTypography.labelLarge.withFont(1.0f),
        labelMedium = DefaultMaterialTypography.labelMedium.withFont(subtitleScale),
        labelSmall = DefaultMaterialTypography.labelSmall.withFont(subtitleScale)
    )
}

@Composable
fun GeekifyTheme(
    content: @Composable () -> Unit
) {
    val repo = remember {
        runCatching { GlobalContext.getOrNull()?.get<AppSettingsRepository>() }.getOrNull()
    }
    val appSettings by (repo?.state?.collectAsState() ?: remember { mutableStateOf(AppSettingsState()) })
    val customization = appSettings.customization
    val typoConfig = customization.typography

    // Apply reactive theme palette & typography synchronously so top-level getters update immediately
    ActiveThemePalette.apply(customization.theme)
    ActiveTypographyState.apply(typoConfig)

    val activeFontFamily = remember(typoConfig.fontChoice) {
        typoConfig.fontChoice.resolveFontFamily()
    }
    val fontScale = typoConfig.fontScale.coerceIn(0.85f, 1.25f)
    val titleScale = typoConfig.titleScale.coerceIn(0.85f, 1.25f)
    val subtitleScale = typoConfig.subtitleScale.coerceIn(0.85f, 1.25f)

    val colorScheme = remember(customization.theme) {
        darkColorScheme(
            primary = customization.theme.resolvedPrimary,
            secondary = customization.theme.resolvedSecondary,
            tertiary = customization.theme.resolvedPrimary,
            background = customization.theme.resolvedBackground,
            surface = customization.theme.resolvedPanel,
            surfaceVariant = customization.theme.resolvedElevated,
            onPrimary = customization.theme.resolvedOnAccent,
            onSecondary = customization.theme.resolvedOnAccent,
            onTertiary = customization.theme.resolvedOnAccent,
            onBackground = TextPrimary,
            onSurface = TextPrimary,
            onSurfaceVariant = TextSecondary,
            error = ErrorRed,
            onError = customization.theme.resolvedBackground
        )
    }

    val scaledTypography = remember(activeFontFamily, titleScale, subtitleScale) {
        buildGeekifyTypography(
            fontFamily = activeFontFamily,
            titleScale = titleScale,
            subtitleScale = subtitleScale
        )
    }

    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity.density, baseDensity.fontScale, fontScale) {
        Density(
            density = baseDensity.density,
            fontScale = baseDensity.fontScale * fontScale
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        LocalAppSettings provides appSettings,
        LocalGlassConfig provides appSettings.glass,
        LocalCustomizationState provides customization
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = scaledTypography
        ) {
            ProvideTextStyle(
                value = scaledTypography.bodyLarge.copy(fontFamily = activeFontFamily)
            ) {
                content()
            }
        }
    }
}
