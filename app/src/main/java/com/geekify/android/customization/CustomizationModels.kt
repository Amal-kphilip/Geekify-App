package com.geekify.android.customization

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.geekify.android.settings.GlassConfig

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
    val themeVariant: DarkThemeVariant = DarkThemeVariant.STANDARD_DARK,
    val palettePreset: AccentPalettePreset = AccentPalettePreset.ELECTRIC_LIME,
    val customPrimaryArgb: Long = 0xFFC8E65A,
    val customSecondaryArgb: Long = 0xFFCF9CF1,
    val customBackgroundArgb: Long = 0xFF0B0A10,
    val customSurfaceArgb: Long = 0xFF15131C,
    val dynamicPlayerAccent: Boolean = true
) {
    val resolvedPrimary: Color
        get() = Color(if (palettePreset == AccentPalettePreset.CUSTOM) customPrimaryArgb else palettePreset.primaryArgb)
    val resolvedSecondary: Color
        get() = Color(if (palettePreset == AccentPalettePreset.CUSTOM) customSecondaryArgb else palettePreset.secondaryArgb)
    val resolvedOnAccent: Color
        get() = Color(palettePreset.onAccentArgb)
    val resolvedBackground: Color
        get() = Color(if (themeVariant == DarkThemeVariant.CUSTOM_DARK) customBackgroundArgb else themeVariant.backgroundArgb)
    val resolvedPanel: Color
        get() = Color(if (themeVariant == DarkThemeVariant.CUSTOM_DARK) customSurfaceArgb else themeVariant.panelArgb)
    val resolvedElevated: Color
        get() = Color(themeVariant.elevatedArgb)
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
    val visibleTabs: List<NavTabId> = NavTabId.DEFAULT_TABS,
    val style: NavBarStyle = NavBarStyle.FLOATING_CAPSULE,
    val showLabels: Boolean = false
) {
    /** Guarantees at least one primary destination (Home or Library) is always reachable. */
    val safeVisibleTabs: List<NavTabId>
        get() = visibleTabs.ifEmpty { listOf(NavTabId.HOME, NavTabId.SEARCH) }
            .let { list ->
                if (list.none { it == NavTabId.HOME || it == NavTabId.LIBRARY }) {
                    listOf(NavTabId.HOME) + list
                } else list
            }
}

enum class PlayerLayoutStyle(val label: String, val subtitle: String) {
    VINYL_TONEARM("Vinyl Player", "Interactive spinning vinyl record with animated stylus tonearm"),
    STANDARD("Standard Player", "Classic balanced album artwork with full transport controls"),
    MINIMAL("Minimal Player", "Clean distraction-free typography and essential controls"),
    ARTWORK_FOCUSED("Artwork-Focused", "Oversized hero album cover with compact controls"),
    FULLSCREEN_ARTWORK("Fullscreen Artwork", "Immersive edge-to-edge album artwork backdrop"),
    EXPRESSIVE("Expressive Player", "Editorial left-aligned typography with bold cover framing")
}

enum class PlayerArtworkShape(val label: String) {
    VINYL_DISC("Vinyl Record"),
    ROUNDED("Rounded Square"),
    SQUARE("Sharp Square"),
    CIRCLE("Circle")
}

enum class PlayerBackgroundStyle(val label: String) {
    BLURRED_ARTWORK("Blurred Artwork Wash"),
    DYNAMIC_GRADIENT("Dynamic Artwork Gradient"),
    SOLID_DARK("Solid Theme Surface"),
    AMOLED_PITCH("AMOLED Pitch Black")
}

enum class PlayerProgressStyle(val label: String) {
    WAVE_GLOW("Animated Wave Bar"),
    THICK_BAR("Thick Capsule Bar"),
    MINIMAL_LINE("Minimal Hairline")
}

@Immutable
data class PlayerCustomizationConfig(
    val layoutStyle: PlayerLayoutStyle = PlayerLayoutStyle.VINYL_TONEARM,
    val artworkShape: PlayerArtworkShape = PlayerArtworkShape.VINYL_DISC,
    val artworkScale: Float = 1.0f,
    val backgroundStyle: PlayerBackgroundStyle = PlayerBackgroundStyle.BLURRED_ARTWORK,
    val progressStyle: PlayerProgressStyle = PlayerProgressStyle.WAVE_GLOW,
    val progressHeightDp: Float = 4f,
    val roundedPlayButton: Boolean = true,
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

enum class MiniPlayerStyle(val label: String) {
    FLOATING_PILL("Floating Glass Capsule"),
    COMPACT_SLIM("Slim Compact Bar"),
    ROUNDED_CARD("Soft Rounded Card")
}

enum class MiniPlayerArtworkShape(val label: String) {
    CIRCLE("Circle"),
    ROUNDED("Rounded Square"),
    SQUARE("Square")
}

@Immutable
data class MiniPlayerCustomizationConfig(
    val style: MiniPlayerStyle = MiniPlayerStyle.FLOATING_PILL,
    val artworkShape: MiniPlayerArtworkShape = MiniPlayerArtworkShape.CIRCLE,
    val showArtwork: Boolean = true,
    val showPreviousButton: Boolean = false,
    val showNextButton: Boolean = true,
    val showLikeButton: Boolean = true,
    val showShuffleButton: Boolean = true,
    val showProgressIndicator: Boolean = true,
    val cornerRadiusDp: Int = 32,
    val heightDp: Int = 64
)

@Immutable
data class GestureCustomizationConfig(
    val swipeArtworkToSkip: Boolean = true,
    val swipeDownToCollapsePlayer: Boolean = true,
    val swipeToDismissMiniPlayer: Boolean = true,
    val swipeToRemoveQueueItem: Boolean = true,
    val hapticFeedback: Boolean = true,
    val gestureSensitivity: Float = 1.0f
)

enum class AppFontChoice(val label: String) {
    SYSTEM_SANS("System Sans-Serif"),
    MONTSERRAT("Montserrat Geometric"),
    SERIF_EDITORIAL("Editorial Serif"),
    MONOSPACE_TECH("Geek Monospace")
}

enum class UiDensityMode(val label: String, val spacingMultiplier: Float) {
    COMPACT("Compact", 0.88f),
    COMFORTABLE("Comfortable", 1.0f),
    SPACIOUS("Spacious", 1.14f)
}

@Immutable
data class TypographyDensityConfig(
    val fontChoice: AppFontChoice = AppFontChoice.SYSTEM_SANS,
    val fontScale: Float = 1.0f,
    val titleScale: Float = 1.0f,
    val subtitleScale: Float = 1.0f,
    val densityMode: UiDensityMode = UiDensityMode.COMFORTABLE
)

enum class LyricsTextAlignment(val label: String) {
    START("Left Aligned"),
    CENTER("Centered"),
    END("Right Aligned")
}

@Immutable
data class LyricsCustomizationConfig(
    val fontSizeSp: Float = 22f,
    val lineSpacingMultiplier: Float = 1.35f,
    val alignment: LyricsTextAlignment = LyricsTextAlignment.CENTER,
    val showRomanization: Boolean = true,
    val showTranslation: Boolean = true,
    val wordLevelHighlight: Boolean = true,
    val activeColorFollowsAccent: Boolean = true
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
    THEME_COLORS("Theme & Colors", "Dark variants, accent palettes & custom colors"),
    LIQUID_GLASS("Liquid Glass", "Optical refraction, blur presets & surface toggles"),
    ASCII_BACKGROUND("ASCII Background", "Creatures, scenes, color cycling & performance mode"),
    HOME_SCREEN("Home Screen", "Reorder or hide sections, shelves vs grid & card sizing"),
    NAVIGATION("Navigation", "Visible tabs, slim or capsule bar & startup screen"),
    PLAYER_APPEARANCE("Player Appearance", "6 player layouts, vinyl tonearm, artwork & controls"),
    MINI_PLAYER("Mini Player", "Capsule style, buttons, corner radius & progress line"),
    GESTURES("Gestures & Haptics", "Swipe actions, sensitivity & haptic feedback"),
    TYPOGRAPHY_DENSITY("Typography & Density", "Font family, text scaling & UI spacing density"),
    LYRICS_APPEARANCE("Lyrics Appearance", "Font size, alignment, romanization & sync offset"),
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
    val miniPlayer: MiniPlayerCustomizationConfig = MiniPlayerCustomizationConfig(),
    val gestures: GestureCustomizationConfig = GestureCustomizationConfig(),
    val typography: TypographyDensityConfig = TypographyDensityConfig(),
    val lyrics: LyricsCustomizationConfig = LyricsCustomizationConfig(),
    val widgets: WidgetCustomizationConfig = WidgetCustomizationConfig()
)

val LocalCustomizationState = staticCompositionLocalOf { CustomizationState() }
