package com.geekify.android.customization

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.geekify.android.settings.GlassConfig

enum class ThemeMode(val label: String, val subtitle: String) {
    AUTO("Automatic", "Follows system light or dark theme"),
    LIGHT("Light", "Clean bright surfaces with high contrast"),
    DARK("Dark", "Signature dark surfaces using selected dark variant"),
    PURE_BLACK("Pure Black (AMOLED)", "True zero-nit pitch black for OLED displays")
}

enum class ThemePaletteStyle(val label: String, val subtitle: String) {
    TONAL_SPOT("Tonal Spot", "Balanced accent harmony with subtle tonal surfaces"),
    VIBRANT("Vibrant", "High-chroma vivid accents and energetic highlights"),
    EXPRESSIVE("Expressive", "Bold editorial contrast with rich secondary tones"),
    RAINBOW("Rainbow", "Playful multi-hue accent shifts across controls"),
    FRUIT_SALAD("Fruit Salad", "Warm contrasting primary and secondary pairing"),
    MONOCHROME("Monochrome", "Refined grayscale minimalism without color distraction"),
    NEUTRAL("Neutral", "Soft desaturated accents for calm focus"),
    CONTENT("Content-Based", "Adapts accent harmony to currently playing artwork")
}

enum class DarkThemeVariant(
    val label: String,
    val subtitle: String,
    val backgroundArgb: Long,
    val panelArgb: Long,
    val elevatedArgb: Long
) {
    STANDARD_DARK("Standard Dark", "Near-black with subtle violet depth", 0xFF0B0A10, 0xFF15131C, 0xFF26242E),
    AMOLED_BLACK("AMOLED Black", "True zero-nit pitch black for OLED displays", 0xFF000000, 0xFF0A0A0D, 0xFF16161C),
    DEEP_BLACK("Deep Black", "Ultra-dark obsidian surfaces", 0xFF050508, 0xFF0E0D14, 0xFF1D1B26),
    GRAPHITE("Graphite", "Neutral slate dark surfaces", 0xFF141519, 0xFF1E2026, 0xFF2C2F38),
    CUSTOM_DARK("Custom Dark", "User-defined background & surface colors", 0xFF0B0A10, 0xFF15131C, 0xFF26242E)
}

enum class AccentPalettePreset(
    val label: String,
    val primaryArgb: Long,
    val secondaryArgb: Long,
    val onAccentArgb: Long
) {
    ELECTRIC_LIME("Electric Lime", 0xFFC8E65A, 0xFFCF9CF1, 0xFF14110A),
    CYBER_VIOLET("Cyber Violet", 0xFFB87CFF, 0xFF6AE2FF, 0xFF120824),
    NEON_CYAN("Neon Cyan", 0xFF38E8FF, 0xFF7B88FF, 0xFF04181F),
    SUNSET_CORAL("Sunset Coral", 0xFFFF7A6B, 0xFFFFC168, 0xFF200A07),
    EMERALD_MINT("Emerald Mint", 0xFF3EF0A8, 0xFF72D6FF, 0xFF061E13),
    ROYAL_GOLD("Royal Gold", 0xFFFFD15C, 0xFFFF8F6B, 0xFF1F1604),
    ROSE_QUARTZ("Rose Quartz", 0xFFFF7EB6, 0xFFC996FF, 0xFF210814),
    CUSTOM("Custom Accent", 0xFFC8E65A, 0xFFCF9CF1, 0xFF14110A)
}

@Immutable
data class ThemeCustomizationConfig(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val themeVariant: DarkThemeVariant = DarkThemeVariant.STANDARD_DARK,
    val palettePreset: AccentPalettePreset = AccentPalettePreset.ELECTRIC_LIME,
    val paletteStyle: ThemePaletteStyle = ThemePaletteStyle.TONAL_SPOT,
    val customPrimaryArgb: Long = 0xFFC8E65A,
    val customSecondaryArgb: Long = 0xFFCF9CF1,
    val customBackgroundArgb: Long = 0xFF0B0A10,
    val customSurfaceArgb: Long = 0xFF15131C,
    val materialYouEnabled: Boolean = false,
    val dynamicPlayerAccent: Boolean = true,
    val uiEnhancements: Boolean = true
) {
    val basePrimary: Color
        get() = Color(if (palettePreset == AccentPalettePreset.CUSTOM) customPrimaryArgb else palettePreset.primaryArgb)
    val baseSecondary: Color
        get() = Color(if (palettePreset == AccentPalettePreset.CUSTOM) customSecondaryArgb else palettePreset.secondaryArgb)

    val resolvedPrimary: Color
        get() = applyPaletteStyle(basePrimary, baseSecondary, isPrimary = true)
    val resolvedSecondary: Color
        get() = applyPaletteStyle(baseSecondary, basePrimary, isPrimary = false)
    val resolvedOnAccent: Color
        get() = when (paletteStyle) {
            ThemePaletteStyle.MONOCHROME -> Color(0xFF111114)
            else -> Color(palettePreset.onAccentArgb)
        }
    val resolvedBackground: Color
        get() = when (themeMode) {
            ThemeMode.PURE_BLACK -> Color(0xFF000000)
            ThemeMode.LIGHT -> Color(0xFFF6F6FA)
            else -> Color(if (themeVariant == DarkThemeVariant.CUSTOM_DARK) customBackgroundArgb else themeVariant.backgroundArgb)
        }
    val resolvedPanel: Color
        get() = when (themeMode) {
            ThemeMode.PURE_BLACK -> Color(0xFF0A0A0D)
            ThemeMode.LIGHT -> Color(0xFFFFFFFF)
            else -> Color(if (themeVariant == DarkThemeVariant.CUSTOM_DARK) customSurfaceArgb else themeVariant.panelArgb)
        }
    val resolvedElevated: Color
        get() = when (themeMode) {
            ThemeMode.PURE_BLACK -> Color(0xFF16161C)
            ThemeMode.LIGHT -> Color(0xFFEAEAF2)
            else -> Color(themeVariant.elevatedArgb)
        }

    fun isDarkFor(systemDark: Boolean): Boolean = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.PURE_BLACK -> true
        ThemeMode.AUTO -> systemDark
    }

    private fun applyPaletteStyle(color: Color, partner: Color, isPrimary: Boolean): Color {
        return when (paletteStyle) {
            ThemePaletteStyle.TONAL_SPOT, ThemePaletteStyle.CONTENT -> color
            ThemePaletteStyle.VIBRANT -> lerp(color, Color(
                red = (color.red * 1.12f).coerceIn(0f, 1f),
                green = (color.green * 1.12f).coerceIn(0f, 1f),
                blue = (color.blue * 1.12f).coerceIn(0f, 1f),
                alpha = 1f
            ), 0.7f)
            ThemePaletteStyle.EXPRESSIVE -> if (isPrimary) lerp(color, partner, 0.18f) else lerp(partner, color, 0.35f)
            ThemePaletteStyle.RAINBOW -> if (isPrimary) lerp(color, Color(0xFF38E8FF), 0.25f) else lerp(partner, Color(0xFFFF7EB6), 0.35f)
            ThemePaletteStyle.FRUIT_SALAD -> if (isPrimary) lerp(color, Color(0xFFFFB74D), 0.28f) else lerp(partner, Color(0xFF69F0AE), 0.32f)
            ThemePaletteStyle.MONOCHROME -> {
                val lum = (0.299f * color.red + 0.587f * color.green + 0.114f * color.blue).coerceIn(0.68f, 0.92f)
                Color(lum, lum, lum, 1f)
            }
            ThemePaletteStyle.NEUTRAL -> lerp(color, Color(0xFFB4B6C4), 0.45f)
        }
    }
}

enum class GlassTintMode(val label: String) {
    THEME("Theme Surface"),
    ACCENT("Accent Color"),
    CUSTOM("Custom Color")
}

enum class GlassQualityLevel(val label: String) {
    HIGH("High Quality"),
    BALANCED("Balanced"),
    BATTERY_SAVER("Battery Saver")
}

enum class GlassPreset(
    val label: String,
    val subtitle: String,
    val config: GlassConfig
) {
    SUBTLE(
        "Subtle",
        "Lightweight frosted rim with minimal blur",
        GlassConfig(enabled = true, blurDp = 1.5f, saturation = 1.3f, lensAmountDp = 6f, lensHeightDp = 8f, tintAlpha = 0.08f)
    ),
    BALANCED(
        "Balanced",
        "Default Liquid Glass refraction and blur",
        GlassConfig(enabled = true, blurDp = 3f, saturation = 1.8f, lensAmountDp = 12f, lensHeightDp = 14f, tintAlpha = 0.12f)
    ),
    GLASSY(
        "Glassy",
        "Pronounced lens curvature and vivid saturation",
        GlassConfig(enabled = true, blurDp = 7f, saturation = 2.1f, lensAmountDp = 18f, lensHeightDp = 20f, tintAlpha = 0.18f)
    ),
    DEEP_GLASS(
        "Deep Glass",
        "High-depth blur, chromatic aberration & 3D depth",
        GlassConfig(
            enabled = true,
            blurDp = 14f,
            saturation = 2.3f,
            lensAmountDp = 24f,
            lensHeightDp = 24f,
            chromaticAberration = true,
            depthEffect = true,
            tintAlpha = 0.24f
        )
    ),
    CUSTOM("Custom", "Fine-tuned optical parameters", GlassConfig())
}

enum class AsciiPerformanceMode(val label: String, val subtitle: String) {
    AUTO("Automatic", "Adapts frame rate and density to device capability"),
    HIGH_QUALITY("High Quality", "Maximum glyph sharpness and full refresh rate"),
    BATTERY_SAVER("Battery Saver", "Lower density and conservative frame pacing")
}

enum class HomeSectionId(val key: String, val label: String) {
    DISCOVER_CAROUSEL("discover", "Curated & Trending Carousel"),
    DAILY_PICKS("daily", "Daily Recommendations"),
    FEED_SHELVES("shelves", "Personalized Mixes & Shelves");

    companion object {
        val DEFAULT_ORDER = listOf(DISCOVER_CAROUSEL, DAILY_PICKS, FEED_SHELVES)
    }
}

enum class HomeLayoutMode(val label: String) {
    HORIZONTAL_SHELVES("Horizontal Shelves"),
    COMPACT_GRID("2-Column Grid")
}

@Immutable
data class HomeCustomizationConfig(
    val sectionOrder: List<HomeSectionId> = HomeSectionId.DEFAULT_ORDER,
    val hiddenSections: Set<HomeSectionId> = emptySet(),
    val layoutMode: HomeLayoutMode = HomeLayoutMode.HORIZONTAL_SHELVES,
    val gridColumns: Int = 2,
    val artworkSizeDp: Int = 72,
    val cardCornerRadiusDp: Int = 24,
    val showSectionTitles: Boolean = true,
    val showMetadata: Boolean = true,
    val showGreetingHeader: Boolean = true
)

enum class NavTabId(val route: String, val label: String) {
    HOME("home", "Home"),
    LIBRARY("library", "Library"),
    CREATE("create", "Create"),
    SEARCH("search", "Search");

    companion object {
        val DEFAULT_TABS = listOf(HOME, LIBRARY, CREATE, SEARCH)
    }
}

enum class NavBarStyle(val label: String) {
    FLOATING_CAPSULE("Floating Glass Capsule"),
    SLIM_PILL("Slim Compact Pill")
}

@Immutable
data class NavigationCustomizationConfig(
    val tabOrder: List<NavTabId> = NavTabId.DEFAULT_TABS,
    val hiddenTabs: Set<NavTabId> = emptySet(),
    val visibleTabs: List<NavTabId> = NavTabId.DEFAULT_TABS,
    val style: NavBarStyle = NavBarStyle.FLOATING_CAPSULE,
    val showLabels: Boolean = false,
    val floatingNavBar: Boolean = true,
    val floatingTopBar: Boolean = false
) {
    /** Complete deduplicated destination order including hidden tabs so positions are never lost. */
    val normalizedTabOrder: List<NavTabId>
        get() {
            val base = tabOrder.distinct()
            val missing = NavTabId.DEFAULT_TABS.filter { it !in base }
            return (base + missing).ifEmpty { NavTabId.DEFAULT_TABS }
        }

    /** Authoritative ordered list of enabled destinations. Guarantees at least one primary destination (Home or Library). */
    val safeVisibleTabs: List<NavTabId>
        get() {
            val base = if (hiddenTabs.isEmpty() && tabOrder == NavTabId.DEFAULT_TABS && visibleTabs != NavTabId.DEFAULT_TABS) {
                visibleTabs.distinct()
            } else {
                normalizedTabOrder.filter { it !in hiddenTabs }
            }
            val nonEmpty = base.ifEmpty { listOf(NavTabId.HOME, NavTabId.SEARCH) }
            return if (nonEmpty.none { it == NavTabId.HOME || it == NavTabId.LIBRARY }) {
                listOf(NavTabId.HOME) + nonEmpty
            } else {
                nonEmpty
            }
        }
}

enum class PlayerLayoutStyle(val label: String, val subtitle: String) {
    VINYL_TONEARM("Vinyl Player", "Interactive spinning vinyl record with animated stylus tonearm"),
    STANDARD("Standard Player", "Balanced Metrolist-inspired album cover with clean controls"),
    MINIMAL("Minimal Player", "Distraction-free typography and essential playback controls"),
    ARTWORK_FOCUSED("Artwork-Focused", "Oversized hero album artwork with compact bottom controls"),
    FULLSCREEN_ARTWORK("Fullscreen Artwork", "Immersive edge-to-edge album artwork with contrast scrim"),
    EXPRESSIVE("Expressive Player", "Editorial left-aligned typography with bold cover framing")
}

enum class PlayerArtworkShape(val label: String) {
    VINYL_DISC("Vinyl Record"),
    ROUNDED("Rounded Square"),
    SQUARE("Sharp Square"),
    CIRCLE("Circle")
}

enum class PlayerArtworkCropMode(val label: String, val subtitle: String) {
    CROP("Fill & Crop", "Fills the artwork frame edge-to-edge"),
    FIT("Fit Inside", "Preserves the complete uncropped cover aspect ratio")
}

enum class PlayerArtworkPresentation(val label: String, val subtitle: String) {
    STANDARD("Standard", "Balanced cover framing"),
    COMPACT("Compact Rounded", "Sleeker cover leaving extra room for metadata"),
    IMMERSIVE("Immersive", "Larger full-bleed cover presence")
}

enum class PlayerBackgroundStyle(val label: String) {
    BLURRED_ARTWORK("Blurred Artwork"),
    DYNAMIC_GRADIENT("Dynamic Artwork Gradient"),
    ANIMATED_GRADIENT("Animated Gradient"),
    LIQUID_GLASS("Liquid Glass"),
    SOLID_DARK("Solid Theme Background"),
    AMOLED_PITCH("AMOLED Black")
}

enum class PlayerProgressStyle(val label: String, val subtitle: String = "") {
    CLASSIC_ROUNDED("Classic Rounded", "Smooth rounded track with crisp ends and balanced thumb"),
    WAVY("Wavy", "Continuous animated waveform adapting to playback"),
    SLIM_LINEAR("Thin Linear", "Minimal thin line with subtle inactive track"),
    THICK_BAR("Thick Capsule", "Chunky rounded capsule track that expands while dragging"),
    WAVE_GLOW("Wave Glow", "Animated wave progress with luminous accent halo")
}

enum class SeekbarThumbStyle(val label: String) {
    DEFAULT_CIRCLE("Default Circle"),
    SMALL_CIRCLE("Small Circle"),
    LARGE_CIRCLE("Large Circle"),
    VERTICAL_PILL("Vertical Pill"),
    MINIMAL_DOT("Minimal Dot"),
    HIDDEN("Hidden (Full Touch Area)")
}

enum class SeekbarGradientPreset(
    val label: String,
    val startArgb: Long,
    val endArgb: Long
) {
    ACCENT_HARMONY("Accent Palette", 0xFFC8E65A, 0xFFCF9CF1),
    AURORA_NEON("Aurora Neon", 0xFF38E8FF, 0xFFB87CFF),
    SUNSET_PULSE("Sunset Pulse", 0xFFFF7A6B, 0xFFFFD15C),
    EMERALD_GLOW("Emerald Glow", 0xFF3EF0A8, 0xFF38E8FF),
    MONOCHROME_SILK("Monochrome Silk", 0xFFFFFFFF, 0xFF9EA2B0)
}

@Immutable
data class SeekbarCustomizationConfig(
    val style: PlayerProgressStyle = PlayerProgressStyle.WAVE_GLOW,
    val thumbStyle: SeekbarThumbStyle = SeekbarThumbStyle.DEFAULT_CIRCLE,
    val thumbSizeDp: Float = 14f,
    val thicknessDp: Float = 4f,
    val cornerRadiusDp: Float = 6f,
    val inactiveTrackAlpha: Float = 0.24f,
    val useCustomActiveColor: Boolean = false,
    val customActiveColorArgb: Long = 0xFFC8E65A,
    val useCustomThumbColor: Boolean = false,
    val customThumbColorArgb: Long = 0xFFC8E65A,
    val gradientEnabled: Boolean = false,
    val gradientPreset: SeekbarGradientPreset = SeekbarGradientPreset.ACCENT_HARMONY,
    val animationsEnabled: Boolean = true,
    val waveAnimationEnabled: Boolean = true,
    val waveAmplitudeDp: Float = 4.5f,
    val waveFrequency: Float = 1.0f,
    val animationIntensity: Float = 1.0f,
    val glowIntensity: Float = 0.65f,
    val waveSpeed: Float = 1.0f,
    val hapticFeedback: Boolean = true,
    val showElapsedTime: Boolean = true,
    val showRemainingTime: Boolean = true,
    val timeLabelScale: Float = 1.0f,
    val touchTargetHeightDp: Float = 40f
)

@Immutable
data class PlayerCustomizationConfig(
    val layoutStyle: PlayerLayoutStyle = PlayerLayoutStyle.VINYL_TONEARM,
    val artworkShape: PlayerArtworkShape = PlayerArtworkShape.VINYL_DISC,
    val artworkScale: Float = 1.0f,
    val artworkCropMode: PlayerArtworkCropMode = PlayerArtworkCropMode.CROP,
    val artworkPresentation: PlayerArtworkPresentation = PlayerArtworkPresentation.STANDARD,
    val artworkTransitions: Boolean = true,
    val animatedArtworkEnabled: Boolean = true,
    val disableAnimatedArtworkOnMobileData: Boolean = false,
    val backgroundStyle: PlayerBackgroundStyle = PlayerBackgroundStyle.BLURRED_ARTWORK,
    val progressStyle: PlayerProgressStyle = PlayerProgressStyle.WAVE_GLOW,
    val progressHeightDp: Float = 4f,
    val roundedPlayButton: Boolean = true,
    val immersivePlayer: Boolean = false,
    val showAlbumMetadata: Boolean = true,
    val showRemainingTime: Boolean = true,
    val showDownloadButton: Boolean = true,
    val showSleepTimerButton: Boolean = true,
    val showLikeButton: Boolean = true,
    val showLyricsButton: Boolean = true,
    val showUpNextPreview: Boolean = true,
    val showShuffleButton: Boolean = true,
    val showRepeatButton: Boolean = true,
    val showQueueButton: Boolean = true,
    val compactControls: Boolean = false
)

enum class MiniPlayerStyle(val label: String, val subtitle: String = "") {
    FLOATING_PILL("Floating Glass Capsule", "Geekify signature frosted floating pill"),
    COMPACT_SLIM("Slim Compact Bar", "Low-profile slim playback strip"),
    ROUNDED_CARD("Soft Rounded Card", "Elevated soft-cornered card bar"),
    MODERN("Modern Pill", "Metrolist-inspired circular progress ring & clean controls"),
    VINTAGE("Vintage Dock", "Full-width classic dock with linear bottom progress"),
    EXPRESSIVE("Expressive", "Tonal container with animated scalloped artwork & wavy ring")
}

enum class MiniPlayerBackgroundStyle(val label: String) {
    THEMED("Themed Background"),
    BLURRED_ARTWORK("Blurred Artwork"),
    DYNAMIC_GRADIENT("Dynamic Gradient"),
    SOLID_DARK("Solid Dark"),
    AMOLED_BLACK("AMOLED Black")
}

enum class MiniPlayerArtworkShape(val label: String) {
    CIRCLE("Circle"),
    ROUNDED("Rounded Square"),
    SQUARE("Square")
}

@Immutable
data class MiniPlayerCustomizationConfig(
    val style: MiniPlayerStyle = MiniPlayerStyle.FLOATING_PILL,
    val backgroundStyle: MiniPlayerBackgroundStyle = MiniPlayerBackgroundStyle.THEMED,
    val artworkShape: MiniPlayerArtworkShape = MiniPlayerArtworkShape.CIRCLE,
    val artworkSizeDp: Int = 44,
    val showArtwork: Boolean = true,
    val showPreviousButton: Boolean = false,
    val showPlayPauseButton: Boolean = true,
    val showNextButton: Boolean = true,
    val showLikeButton: Boolean = true,
    val showShuffleButton: Boolean = true,
    val showQueueButton: Boolean = false,
    val showProgressIndicator: Boolean = true,
    val cornerRadiusDp: Int = 32,
    val heightDp: Int = 64,
    val horizontalPaddingDp: Int = 10,
    val surfaceAlpha: Float = 0.88f,
    val glassEffect: Boolean = true,
    val elevationDp: Int = 6,
    val expressiveCookieArtwork: Boolean = true,
    val expressiveShapeMotion: Boolean = true,
    val expressiveProgressRing: Boolean = true,
    val expressiveArtworkPlaybackTap: Boolean = false
)

@Immutable
data class GestureCustomizationConfig(
    val swipeArtworkToSkip: Boolean = true,
    val swipeDownToCollapsePlayer: Boolean = true,
    val swipeToDismissMiniPlayer: Boolean = true,
    val swipeToRemoveQueueItem: Boolean = true,
    val hapticFeedback: Boolean = true,
    val gestureSensitivity: Float = 1.0f,
    val artworkDoubleTapSkipEnabled: Boolean = true,
    val artworkSkipDurationSeconds: Int = 10,
    val artworkProgressiveSkipEnabled: Boolean = false
) {
    companion object {
        const val MIN_SKIP_DURATION_SECONDS = 5
        const val MAX_SKIP_DURATION_SECONDS = 30
        const val SKIP_DURATION_STEP_SECONDS = 5
        const val DEFAULT_SKIP_DURATION_SECONDS = 10

        /** Clamps any stored or slider duration to 5..30s and snaps to 5-second increments. */
        fun normalizeSkipDurationSeconds(rawSeconds: Int): Int {
            val clamped = rawSeconds.coerceIn(MIN_SKIP_DURATION_SECONDS, MAX_SKIP_DURATION_SECONDS)
            return ((clamped + (SKIP_DURATION_STEP_SECONDS / 2)) / SKIP_DURATION_STEP_SECONDS) * SKIP_DURATION_STEP_SECONDS
        }
    }
}

enum class AppFontChoice(val label: String) {
    SOHNE_BREIT("Sohne Breit")
}

enum class UiDensityMode(val label: String, val spacingMultiplier: Float) {
    COMPACT("Compact", 0.88f),
    COMFORTABLE("Comfortable", 1.0f),
    SPACIOUS("Spacious", 1.14f)
}

enum class DisplayDensityScale(val label: String, val scaleFactor: Float) {
    MASSIVE("Massive (130%)", 1.30f),
    HUGE("Huge (122%)", 1.22f),
    EXTRA_LARGE("Extra Large (115%)", 1.15f),
    LARGE("Large (110%)", 1.10f),
    SLIGHTLY_LARGE("Slightly Large (105%)", 1.05f),
    NATIVE("Native (100%)", 1.00f),
    SLIGHTLY_COMPACT("Slightly Compact (94%)", 0.94f),
    COMPACT("Compact (88%)", 0.88f),
    VERY_COMPACT("Very Compact (82%)", 0.82f),
    ULTRA_COMPACT("Ultra Compact (76%)", 0.76f);

    companion object {
        fun fromLegacy(mode: UiDensityMode): DisplayDensityScale = when (mode) {
            UiDensityMode.COMPACT -> COMPACT
            UiDensityMode.COMFORTABLE -> NATIVE
            UiDensityMode.SPACIOUS -> LARGE
        }
    }
}

@Immutable
data class TypographyDensityConfig(
    val fontChoice: AppFontChoice = AppFontChoice.SOHNE_BREIT,
    val fontScale: Float = 1.0f,
    val titleScale: Float = 1.0f,
    val subtitleScale: Float = 1.0f,
    val densityMode: UiDensityMode = UiDensityMode.COMFORTABLE,
    val displayDensity: DisplayDensityScale = DisplayDensityScale.NATIVE,
    val enableLandscapeScaling: Boolean = true
)

enum class LyricsTextAlignment(val label: String) {
    START("Left Aligned"),
    CENTER("Centered"),
    END("Right Aligned")
}

enum class LyricsAnimationStyle(val label: String, val subtitle: String) {
    APPLE("Apple Music Style", "Fluid spring lift, soft glow & horizontal word wipe"),
    BRACCATO("Braccato Style", "Energetic letter-wave crest with expressive bounce"),
    EXPRESSIVE("Expressive Style", "Bold scale pulse, accent sweep & theatrical focus"),
    SIMPLE("Simple Style", "Clean progressive word highlight without motion lift"),
    NONE("None", "Instant line highlight with zero motion effects")
}

@Immutable
data class LyricsCustomizationConfig(
    val fontSizeSp: Float = 22f,
    val lineSpacingMultiplier: Float = 1.35f,
    val subtitleFontSizeSp: Float = 14f,
    val alignment: LyricsTextAlignment = LyricsTextAlignment.CENTER,
    val activeAlignment: LyricsTextAlignment = LyricsTextAlignment.CENTER,
    val respectProviderPositioning: Boolean = true,
    val animationStyle: LyricsAnimationStyle = LyricsAnimationStyle.APPLE,
    val showOriginalLyrics: Boolean = true,
    val showRomanization: Boolean = true,
    val showTranslation: Boolean = true,
    val wordLevelHighlight: Boolean = true,
    val activeColorFollowsAccent: Boolean = true,
    val useNeutralColor: Boolean = false,
    val autoScroll: Boolean = true,
    val tapToSeek: Boolean = true,
    val preferWordSynced: Boolean = true,
    val activeByRealTiming: Boolean = true,
    val globalOffsetMs: Long = 0L,
    val showLyricsOnPlayer: Boolean = false,
    val hideStatusBarInFullscreen: Boolean = true,
    val progressiveBlur: Boolean = true,
    val plusLighterBlend: Boolean = false,
    val fullscreenDefault: Boolean = false
)

enum class WidgetBackgroundStyle(val label: String, val argbHex: Int) {
    DARK_GLASS("Dark Frosted", 0xE6141418.toInt()),
    AMOLED_BLACK("AMOLED Black", 0xFF000000.toInt()),
    ACCENT_TINT("Deep Charcoal", 0xF01C1B24.toInt())
}

@Immutable
data class WidgetCustomizationConfig(
    val backgroundStyle: WidgetBackgroundStyle = WidgetBackgroundStyle.DARK_GLASS,
    val showArtwork: Boolean = true,
    val showPrevNext: Boolean = true,
    val showArtist: Boolean = true
)

enum class CustomizationCategory(val title: String, val subtitle: String) {
    THEME_COLORS("Theme & Colors", "Theme mode, palette styles, Material You & accent colors"),
    LIQUID_GLASS("Liquid Glass", "Optical refraction, blur presets & surface toggles"),
    ASCII_BACKGROUND("ASCII Background", "Creatures, scenes, color cycling & performance mode"),
    HOME_SCREEN("Home Screen", "Reorder or hide sections, shelves vs grid & card sizing"),
    NAVIGATION("Navigation", "Visible tabs, floating or docked bar & startup screen"),
    PLAYER_APPEARANCE("Player Appearance", "6 player layouts, backgrounds, sliders & artwork"),
    MINI_PLAYER("Mini Player", "6 styles, expressive artwork, backgrounds & controls"),
    GESTURES("Gestures & Haptics", "Swipe actions, sensitivity & haptic feedback"),
    TYPOGRAPHY_DENSITY("Typography & Density", "Font family, scaling & 10-level display density"),
    LYRICS_APPEARANCE("Lyrics Appearance", "Animations, alignment, typography, blur & sync offset"),
    PLAYBACK_PREFERENCES("Playback & Audio", "Streaming quality, crossfade, EQ, speed & autoplay"),
    WIDGETS("Home Screen Widgets", "Widget background, artwork & transport button visibility"),
    APP_ICONS("App Icons", "Customize Geekify's home screen launcher icon")
}

@Immutable
data class CustomizationState(
    val theme: ThemeCustomizationConfig = ThemeCustomizationConfig(),
    val glassPreset: GlassPreset = GlassPreset.BALANCED,
    val asciiPerformanceMode: AsciiPerformanceMode = AsciiPerformanceMode.AUTO,
    val home: HomeCustomizationConfig = HomeCustomizationConfig(),
    val navigation: NavigationCustomizationConfig = NavigationCustomizationConfig(),
    val player: PlayerCustomizationConfig = PlayerCustomizationConfig(),
    val seekbar: SeekbarCustomizationConfig = SeekbarCustomizationConfig(),
    val miniPlayer: MiniPlayerCustomizationConfig = MiniPlayerCustomizationConfig(),
    val gestures: GestureCustomizationConfig = GestureCustomizationConfig(),
    val typography: TypographyDensityConfig = TypographyDensityConfig(),
    val lyrics: LyricsCustomizationConfig = LyricsCustomizationConfig(),
    val widgets: WidgetCustomizationConfig = WidgetCustomizationConfig()
)

val LocalCustomizationState = staticCompositionLocalOf { CustomizationState() }
