package com.geekify.android.ui.theme

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import com.geekify.android.R
import com.geekify.android.customization.AppFontChoice
import com.geekify.android.customization.LocalCustomizationState
import com.geekify.android.customization.ThemeMode
import com.geekify.android.customization.TypographyDensityConfig
import com.geekify.android.settings.AppSettingsRepository
import com.geekify.android.settings.AppSettingsState
import com.geekify.android.settings.LocalAppSettings
import com.geekify.android.settings.LocalGlassConfig
import org.koin.core.context.GlobalContext

/**
 * Exclusive global Sohne Breit font family with all 8 upright weights and all 8 true italic
 * variants mapped directly to their OTF files (no synthesized bold/italic).
 */
val SohneBreitFontFamily = FontFamily(
    Font(R.font.sohne_breit_extralight, FontWeight.ExtraLight, FontStyle.Normal),
    Font(R.font.sohne_breit_extralight_italic, FontWeight.ExtraLight, FontStyle.Italic),
    Font(R.font.sohne_breit_light, FontWeight.Light, FontStyle.Normal),
    Font(R.font.sohne_breit_light_italic, FontWeight.Light, FontStyle.Italic),
    Font(R.font.sohne_breit_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.sohne_breit_regular_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.sohne_breit_medium, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.sohne_breit_medium_italic, FontWeight.Medium, FontStyle.Italic),
    Font(R.font.sohne_breit_semibold, FontWeight.SemiBold, FontStyle.Normal),
    Font(R.font.sohne_breit_semibold_italic, FontWeight.SemiBold, FontStyle.Italic),
    Font(R.font.sohne_breit_bold, FontWeight.Bold, FontStyle.Normal),
    Font(R.font.sohne_breit_bold_italic, FontWeight.Bold, FontStyle.Italic),
    Font(R.font.sohne_breit_extrabold, FontWeight.ExtraBold, FontStyle.Normal),
    Font(R.font.sohne_breit_extrabold_italic, FontWeight.ExtraBold, FontStyle.Italic),
    Font(R.font.sohne_breit_black, FontWeight.Black, FontStyle.Normal),
    Font(R.font.sohne_breit_black_italic, FontWeight.Black, FontStyle.Italic)
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val GoogleSansFontFamily: FontFamily by lazy {
    FontFamily(
        Font(R.font.google_sans_variable, FontWeight.ExtraLight, FontStyle.Normal, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(200))),
        Font(R.font.google_sans_variable, FontWeight.Light, FontStyle.Normal, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(300))),
        Font(R.font.google_sans_variable, FontWeight.Normal, FontStyle.Normal, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(400))),
        Font(R.font.google_sans_variable, FontWeight.Medium, FontStyle.Normal, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(500))),
        Font(R.font.google_sans_variable, FontWeight.SemiBold, FontStyle.Normal, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(600))),
        Font(R.font.google_sans_variable, FontWeight.Bold, FontStyle.Normal, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(700))),
        Font(R.font.google_sans_variable, FontWeight.ExtraBold, FontStyle.Normal, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(800))),
        Font(R.font.google_sans_variable, FontWeight.Black, FontStyle.Normal, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(900)))
    )
}

object ActiveTypographyState {
    var fontFamily: FontFamily by mutableStateOf(SohneBreitFontFamily)
        private set
    var fontScale: Float by mutableStateOf(1.0f)
        private set
    var titleScale: Float by mutableStateOf(1.0f)
        private set
    var subtitleScale: Float by mutableStateOf(1.0f)
        private set

    fun apply(config: TypographyDensityConfig, context: android.content.Context? = null) {
        val resolved = GeekifyFontManager.resolveFontFamily(config, context)
        if (fontFamily != resolved) fontFamily = resolved
        val clampedFont = config.fontScale.coerceIn(0.85f, 1.25f)
        if (fontScale != clampedFont) fontScale = clampedFont
        val clampedTitle = config.titleScale.coerceIn(0.85f, 1.25f)
        if (titleScale != clampedTitle) titleScale = clampedTitle
        val clampedSub = config.subtitleScale.coerceIn(0.85f, 1.25f)
        if (subtitleScale != clampedSub) subtitleScale = clampedSub
    }
}

/** Reactive global font family — dynamically resolved from TypographyDensityConfig. */
val AppFont: FontFamily
    get() = ActiveTypographyState.fontFamily

fun AppFontChoice.resolveFontFamily(): FontFamily = when (this) {
    AppFontChoice.SOHNE_BREIT -> SohneBreitFontFamily
    AppFontChoice.SYSTEM_DEFAULT -> FontFamily.Default
    AppFontChoice.DOWNLOADED, AppFontChoice.IMPORTED -> ActiveTypographyState.fontFamily
}

private val DefaultMaterialTypography = Typography()

fun buildGeekifyTypography(
    fontFamily: FontFamily = SohneBreitFontFamily,
    titleScale: Float = 1.0f,
    subtitleScale: Float = 1.0f
): Typography {
    fun TextStyle.withSohne(weight: FontWeight, scale: Float = 1.0f): TextStyle = copy(
        fontFamily = fontFamily,
        fontWeight = weight,
        fontSize = if (scale == 1.0f) fontSize else fontSize * scale,
        lineHeight = if (scale == 1.0f) lineHeight else lineHeight * scale
    )
    return Typography(
        displayLarge = DefaultMaterialTypography.displayLarge.withSohne(FontWeight.ExtraBold, titleScale),
        displayMedium = DefaultMaterialTypography.displayMedium.withSohne(FontWeight.ExtraBold, titleScale),
        displaySmall = DefaultMaterialTypography.displaySmall.withSohne(FontWeight.Bold, titleScale),
        headlineLarge = DefaultMaterialTypography.headlineLarge.withSohne(FontWeight.Bold, titleScale),
        headlineMedium = DefaultMaterialTypography.headlineMedium.withSohne(FontWeight.Bold, titleScale),
        headlineSmall = DefaultMaterialTypography.headlineSmall.withSohne(FontWeight.SemiBold, titleScale),
        titleLarge = DefaultMaterialTypography.titleLarge.withSohne(FontWeight.SemiBold, titleScale),
        titleMedium = DefaultMaterialTypography.titleMedium.withSohne(FontWeight.SemiBold, titleScale),
        titleSmall = DefaultMaterialTypography.titleSmall.withSohne(FontWeight.Medium, titleScale),
        bodyLarge = DefaultMaterialTypography.bodyLarge.withSohne(FontWeight.Normal, 1.0f),
        bodyMedium = DefaultMaterialTypography.bodyMedium.withSohne(FontWeight.Normal, 1.0f),
        bodySmall = DefaultMaterialTypography.bodySmall.withSohne(FontWeight.Light, subtitleScale),
        labelLarge = DefaultMaterialTypography.labelLarge.withSohne(FontWeight.Medium, 1.0f),
        labelMedium = DefaultMaterialTypography.labelMedium.withSohne(FontWeight.Light, subtitleScale),
        labelSmall = DefaultMaterialTypography.labelSmall.withSohne(FontWeight.ExtraLight, subtitleScale)
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
    val themeConfig = customization.theme
    val typoConfig = customization.typography

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val systemDark = isSystemInDarkTheme()
    val isDark = themeConfig.isDarkFor(systemDark)
    val isPureBlack = themeConfig.themeMode == ThemeMode.PURE_BLACK

    val useDynamicColors = themeConfig.materialYouEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dynamicScheme = remember(useDynamicColors, isDark, context) {
        if (useDynamicColors) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else null
    }

    val rawPrimary = dynamicScheme?.primary ?: themeConfig.resolvedPrimary
    // In Light Theme, keep high-luminance neon/pastel accents legible on white surfaces
    val resolvedPrimary = if (!isDark && rawPrimary.luminance() > 0.62f) {
        androidx.compose.ui.graphics.lerp(rawPrimary, Color(0xFF1A2208), 0.34f)
    } else {
        rawPrimary
    }
    val rawSecondary = dynamicScheme?.secondary ?: themeConfig.resolvedSecondary
    val resolvedSecondary = if (!isDark && rawSecondary.luminance() > 0.65f) {
        androidx.compose.ui.graphics.lerp(rawSecondary, Color(0xFF221036), 0.28f)
    } else {
        rawSecondary
    }
    val resolvedBg = when {
        isPureBlack -> Color.Black
        dynamicScheme != null -> dynamicScheme.background
        !isDark -> Color(0xFFF4F5FA)
        else -> themeConfig.resolvedBackground
    }
    val resolvedPanel = when {
        isPureBlack -> Color(0xFF0A0A0D)
        dynamicScheme != null -> dynamicScheme.surface
        !isDark -> Color(0xFFFFFFFF)
        else -> themeConfig.resolvedPanel
    }
    val resolvedElevated = when {
        isPureBlack -> Color(0xFF16161C)
        dynamicScheme != null -> dynamicScheme.surfaceVariant
        !isDark -> Color(0xFFE9ECF4)
        else -> themeConfig.resolvedElevated
    }

    // Apply reactive theme palette & typography synchronously so top-level getters update immediately
    ActiveThemePalette.apply(
        config = themeConfig,
        dark = isDark,
        overridePrimary = resolvedPrimary,
        overrideSecondary = resolvedSecondary,
        overrideBackground = resolvedBg,
        overridePanel = resolvedPanel,
        overrideElevated = resolvedElevated
    )
    ActiveTypographyState.apply(typoConfig, context)

    val activeFontFamily = remember(
        typoConfig.fontChoice,
        typoConfig.activeFontId,
        typoConfig.activeFontPath
    ) {
        GeekifyFontManager.resolveFontFamily(typoConfig, context)
    }
    val fontScale = typoConfig.fontScale.coerceIn(0.85f, 1.25f)
    val titleScale = typoConfig.titleScale.coerceIn(0.85f, 1.25f)
    val subtitleScale = typoConfig.subtitleScale.coerceIn(0.85f, 1.25f)

    val colorScheme = remember(isDark, resolvedPrimary, resolvedSecondary, resolvedBg, resolvedPanel, resolvedElevated) {
        if (isDark) {
            darkColorScheme(
                primary = resolvedPrimary,
                secondary = resolvedSecondary,
                tertiary = resolvedPrimary,
                background = resolvedBg,
                surface = resolvedPanel,
                surfaceVariant = resolvedElevated,
                surfaceContainer = resolvedPanel,
                surfaceContainerHigh = resolvedElevated,
                surfaceContainerLow = resolvedBg,
                outline = Color(0x33FFFFFF),
                outlineVariant = Color(0x1FFFFFFF),
                onPrimary = ActiveThemePalette.onAccent,
                onSecondary = resolveOnAccentColor(resolvedSecondary),
                onTertiary = ActiveThemePalette.onAccent,
                onBackground = ActiveThemePalette.textPrimary,
                onSurface = ActiveThemePalette.textPrimary,
                onSurfaceVariant = ActiveThemePalette.textSecondary,
                error = ErrorRed,
                onError = resolvedBg
            )
        } else {
            lightColorScheme(
                primary = resolvedPrimary,
                secondary = resolvedSecondary,
                tertiary = resolvedPrimary,
                background = resolvedBg,
                surface = resolvedPanel,
                surfaceVariant = resolvedElevated,
                surfaceContainer = resolvedPanel,
                surfaceContainerHigh = resolvedElevated,
                surfaceContainerLow = resolvedBg,
                outline = Color(0x3D121118),
                outlineVariant = Color(0x1F121118),
                onPrimary = ActiveThemePalette.onAccent,
                onSecondary = resolveOnAccentColor(resolvedSecondary),
                onTertiary = ActiveThemePalette.onAccent,
                onBackground = ActiveThemePalette.textPrimary,
                onSurface = ActiveThemePalette.textPrimary,
                onSurfaceVariant = ActiveThemePalette.textSecondary,
                error = Color(0xFFDC2626),
                onError = Color.White
            )
        }
    }

    val scaledTypography = remember(activeFontFamily, titleScale, subtitleScale) {
        buildGeekifyTypography(
            fontFamily = activeFontFamily,
            titleScale = titleScale,
            subtitleScale = subtitleScale
        )
    }

    val baseDensity = LocalDensity.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val effectiveDensityFactor = if (!isLandscape || typoConfig.enableLandscapeScaling) {
        typoConfig.displayDensity.scaleFactor.coerceIn(0.75f, 1.35f)
    } else {
        1.0f
    }
    val scaledDensity = remember(baseDensity.density, baseDensity.fontScale, fontScale, effectiveDensityFactor) {
        Density(
            density = baseDensity.density * effectiveDensityFactor,
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
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
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
