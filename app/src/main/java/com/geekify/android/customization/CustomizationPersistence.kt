package com.geekify.android.customization

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Strongly-typed DataStore persistence and validation for [CustomizationState].
 * Shares the application's single [androidx.datastore.core.DataStore] instance via
 * [com.geekify.android.settings.AppSettingsRepository] without creating competing preference files.
 */
object CustomizationPersistence {
    // Theme & Colors
    val KEY_THEME_VARIANT = stringPreferencesKey("cust_theme_variant")
    val KEY_PALETTE_PRESET = stringPreferencesKey("cust_palette_preset")
    val KEY_CUSTOM_PRIMARY_ARGB = longPreferencesKey("cust_custom_primary_argb")
    val KEY_CUSTOM_SECONDARY_ARGB = longPreferencesKey("cust_custom_secondary_argb")
    val KEY_CUSTOM_BG_ARGB = longPreferencesKey("cust_custom_bg_argb")
    val KEY_CUSTOM_SURFACE_ARGB = longPreferencesKey("cust_custom_surface_argb")
    val KEY_DYNAMIC_PLAYER_ACCENT = booleanPreferencesKey("cust_dynamic_player_accent")

    // Glass & ASCII Performance
    val KEY_GLASS_PRESET = stringPreferencesKey("cust_glass_preset")
    val KEY_ASCII_PERF_MODE = stringPreferencesKey("cust_ascii_perf_mode")

    // Home Screen
    val KEY_HOME_SECTION_ORDER = stringPreferencesKey("cust_home_section_order")
    val KEY_HOME_HIDDEN_SECTIONS = stringPreferencesKey("cust_home_hidden_sections")
    val KEY_HOME_LAYOUT_MODE = stringPreferencesKey("cust_home_layout_mode")
    val KEY_HOME_GRID_COLUMNS = intPreferencesKey("cust_home_grid_columns")
    val KEY_HOME_ARTWORK_SIZE_DP = intPreferencesKey("cust_home_artwork_size_dp")
    val KEY_HOME_CORNER_RADIUS_DP = intPreferencesKey("cust_home_corner_radius_dp")
    val KEY_HOME_SHOW_TITLES = booleanPreferencesKey("cust_home_show_titles")
    val KEY_HOME_SHOW_METADATA = booleanPreferencesKey("cust_home_show_metadata")
    val KEY_HOME_SHOW_GREETING = booleanPreferencesKey("cust_home_show_greeting")

    // Navigation
    val KEY_NAV_VISIBLE_TABS = stringPreferencesKey("cust_nav_visible_tabs")
    val KEY_NAV_STYLE = stringPreferencesKey("cust_nav_style")
    val KEY_NAV_SHOW_LABELS = booleanPreferencesKey("cust_nav_show_labels")

    // Expanded Player
    val KEY_PLAYER_LAYOUT_STYLE = stringPreferencesKey("cust_player_layout_style")
    val KEY_PLAYER_ARTWORK_SHAPE = stringPreferencesKey("cust_player_artwork_shape")
    val KEY_PLAYER_ARTWORK_SCALE = floatPreferencesKey("cust_player_artwork_scale")
    val KEY_PLAYER_BG_STYLE = stringPreferencesKey("cust_player_bg_style")
    val KEY_PLAYER_PROGRESS_STYLE = stringPreferencesKey("cust_player_progress_style")
    val KEY_PLAYER_PROGRESS_HEIGHT = floatPreferencesKey("cust_player_progress_height")
    val KEY_PLAYER_ROUNDED_PLAY = booleanPreferencesKey("cust_player_rounded_play")
    val KEY_PLAYER_SHOW_DOWNLOAD = booleanPreferencesKey("cust_player_show_download")
    val KEY_PLAYER_SHOW_SLEEP = booleanPreferencesKey("cust_player_show_sleep")
    val KEY_PLAYER_SHOW_LIKE = booleanPreferencesKey("cust_player_show_like")
    val KEY_PLAYER_SHOW_LYRICS = booleanPreferencesKey("cust_player_show_lyrics")
    val KEY_PLAYER_SHOW_UP_NEXT = booleanPreferencesKey("cust_player_show_up_next")
    val KEY_PLAYER_SHOW_SHUFFLE = booleanPreferencesKey("cust_player_show_shuffle")
    val KEY_PLAYER_SHOW_REPEAT = booleanPreferencesKey("cust_player_show_repeat")
    val KEY_PLAYER_SHOW_QUEUE = booleanPreferencesKey("cust_player_show_queue")
    val KEY_PLAYER_COMPACT_CONTROLS = booleanPreferencesKey("cust_player_compact_controls")

    // Mini Player
    val KEY_MINI_STYLE = stringPreferencesKey("cust_mini_style")
    val KEY_MINI_ARTWORK_SHAPE = stringPreferencesKey("cust_mini_artwork_shape")
    val KEY_MINI_SHOW_ARTWORK = booleanPreferencesKey("cust_mini_show_artwork")
    val KEY_MINI_SHOW_PREV = booleanPreferencesKey("cust_mini_show_prev")
    val KEY_MINI_SHOW_NEXT = booleanPreferencesKey("cust_mini_show_next")
    val KEY_MINI_SHOW_LIKE = booleanPreferencesKey("cust_mini_show_like")
    val KEY_MINI_SHOW_SHUFFLE = booleanPreferencesKey("cust_mini_show_shuffle")
    val KEY_MINI_SHOW_PROGRESS = booleanPreferencesKey("cust_mini_show_progress")
    val KEY_MINI_CORNER_RADIUS_DP = intPreferencesKey("cust_mini_corner_radius_dp")
    val KEY_MINI_HEIGHT_DP = intPreferencesKey("cust_mini_height_dp")

    // Gestures
    val KEY_GESTURE_SWIPE_ARTWORK = booleanPreferencesKey("cust_gesture_swipe_artwork")
    val KEY_GESTURE_SWIPE_COLLAPSE = booleanPreferencesKey("cust_gesture_swipe_collapse")
    val KEY_GESTURE_DISMISS_MINI = booleanPreferencesKey("cust_gesture_dismiss_mini")
    val KEY_GESTURE_REMOVE_QUEUE = booleanPreferencesKey("cust_gesture_remove_queue")
    val KEY_GESTURE_HAPTICS = booleanPreferencesKey("cust_gesture_haptics")
    val KEY_GESTURE_SENSITIVITY = floatPreferencesKey("cust_gesture_sensitivity")

    // Typography & Density
    val KEY_TYPO_FONT_CHOICE = stringPreferencesKey("cust_typo_font_choice")
    val KEY_TYPO_FONT_SCALE = floatPreferencesKey("cust_typo_font_scale")
    val KEY_TYPO_TITLE_SCALE = floatPreferencesKey("cust_typo_title_scale")
    val KEY_TYPO_SUBTITLE_SCALE = floatPreferencesKey("cust_typo_subtitle_scale")
    val KEY_TYPO_DENSITY_MODE = stringPreferencesKey("cust_typo_density_mode")

    // Lyrics
    val KEY_LYRICS_FONT_SIZE_SP = floatPreferencesKey("cust_lyrics_font_size_sp")
    val KEY_LYRICS_LINE_SPACING = floatPreferencesKey("cust_lyrics_line_spacing")
    val KEY_LYRICS_ALIGNMENT = stringPreferencesKey("cust_lyrics_alignment")
    val KEY_LYRICS_SHOW_ROMANIZATION = booleanPreferencesKey("cust_lyrics_show_romanization")
    val KEY_LYRICS_SHOW_TRANSLATION = booleanPreferencesKey("cust_lyrics_show_translation")
    val KEY_LYRICS_WORD_HIGHLIGHT = booleanPreferencesKey("cust_lyrics_word_highlight")
    val KEY_LYRICS_ACCENT_COLOR = booleanPreferencesKey("cust_lyrics_accent_color")

    // Widgets
    val KEY_WIDGET_BG_STYLE = stringPreferencesKey("cust_widget_bg_style")
    val KEY_WIDGET_SHOW_ARTWORK = booleanPreferencesKey("cust_widget_show_artwork")
    val KEY_WIDGET_SHOW_PREV_NEXT = booleanPreferencesKey("cust_widget_show_prev_next")
    val KEY_WIDGET_SHOW_ARTIST = booleanPreferencesKey("cust_widget_show_artist")

    fun fromPreferences(prefs: Preferences): CustomizationState {
        val theme = ThemeCustomizationConfig(
            themeVariant = prefs[KEY_THEME_VARIANT]?.let { runCatching { DarkThemeVariant.valueOf(it) }.getOrNull() }
                ?: DarkThemeVariant.STANDARD_DARK,
            palettePreset = prefs[KEY_PALETTE_PRESET]?.let { runCatching { AccentPalettePreset.valueOf(it) }.getOrNull() }
                ?: AccentPalettePreset.ELECTRIC_LIME,
            customPrimaryArgb = prefs[KEY_CUSTOM_PRIMARY_ARGB] ?: 0xFFC8E65A,
            customSecondaryArgb = prefs[KEY_CUSTOM_SECONDARY_ARGB] ?: 0xFFCF9CF1,
            customBackgroundArgb = prefs[KEY_CUSTOM_BG_ARGB] ?: 0xFF0B0A10,
            customSurfaceArgb = prefs[KEY_CUSTOM_SURFACE_ARGB] ?: 0xFF15131C,
            dynamicPlayerAccent = prefs[KEY_DYNAMIC_PLAYER_ACCENT] ?: true
        )

        val sectionOrder = prefs[KEY_HOME_SECTION_ORDER]
            ?.split(',')
            ?.mapNotNull { name -> runCatching { HomeSectionId.valueOf(name.trim()) }.getOrNull() }
            ?.distinct()
            ?.let { saved ->
                val missing = HomeSectionId.DEFAULT_ORDER.filter { it !in saved }
                (saved + missing).ifEmpty { HomeSectionId.DEFAULT_ORDER }
            } ?: HomeSectionId.DEFAULT_ORDER

        val hiddenSections = prefs[KEY_HOME_HIDDEN_SECTIONS]
            ?.split(',')
            ?.mapNotNull { name -> runCatching { HomeSectionId.valueOf(name.trim()) }.getOrNull() }
            ?.toSet() ?: emptySet()

        val home = HomeCustomizationConfig(
            sectionOrder = sectionOrder,
            hiddenSections = hiddenSections,
            layoutMode = prefs[KEY_HOME_LAYOUT_MODE]?.let { runCatching { HomeLayoutMode.valueOf(it) }.getOrNull() }
                ?: HomeLayoutMode.HORIZONTAL_SHELVES,
            gridColumns = (prefs[KEY_HOME_GRID_COLUMNS] ?: 2).coerceIn(2, 3),
            artworkSizeDp = (prefs[KEY_HOME_ARTWORK_SIZE_DP] ?: 72).coerceIn(52, 104),
            cardCornerRadiusDp = (prefs[KEY_HOME_CORNER_RADIUS_DP] ?: 24).coerceIn(8, 36),
            showSectionTitles = prefs[KEY_HOME_SHOW_TITLES] ?: true,
            showMetadata = prefs[KEY_HOME_SHOW_METADATA] ?: true,
            showGreetingHeader = prefs[KEY_HOME_SHOW_GREETING] ?: true
        )

        val visibleTabs = prefs[KEY_NAV_VISIBLE_TABS]
            ?.split(',')
            ?.mapNotNull { name -> runCatching { NavTabId.valueOf(name.trim()) }.getOrNull() }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: NavTabId.DEFAULT_TABS

        val navigation = NavigationCustomizationConfig(
            visibleTabs = visibleTabs,
            style = prefs[KEY_NAV_STYLE]?.let { runCatching { NavBarStyle.valueOf(it) }.getOrNull() }
                ?: NavBarStyle.FLOATING_CAPSULE,
            showLabels = prefs[KEY_NAV_SHOW_LABELS] ?: false
        )

        val player = PlayerCustomizationConfig(
            layoutStyle = prefs[KEY_PLAYER_LAYOUT_STYLE]?.let { runCatching { PlayerLayoutStyle.valueOf(it) }.getOrNull() }
                ?: PlayerLayoutStyle.VINYL_TONEARM,
            artworkShape = prefs[KEY_PLAYER_ARTWORK_SHAPE]?.let { runCatching { PlayerArtworkShape.valueOf(it) }.getOrNull() }
                ?: PlayerArtworkShape.VINYL_DISC,
            artworkScale = (prefs[KEY_PLAYER_ARTWORK_SCALE] ?: 1.0f).coerceIn(0.75f, 1.20f),
            backgroundStyle = prefs[KEY_PLAYER_BG_STYLE]?.let { runCatching { PlayerBackgroundStyle.valueOf(it) }.getOrNull() }
                ?: PlayerBackgroundStyle.BLURRED_ARTWORK,
            progressStyle = prefs[KEY_PLAYER_PROGRESS_STYLE]?.let { runCatching { PlayerProgressStyle.valueOf(it) }.getOrNull() }
                ?: PlayerProgressStyle.WAVE_GLOW,
            progressHeightDp = (prefs[KEY_PLAYER_PROGRESS_HEIGHT] ?: 4f).coerceIn(2f, 10f),
            roundedPlayButton = prefs[KEY_PLAYER_ROUNDED_PLAY] ?: true,
            showDownloadButton = prefs[KEY_PLAYER_SHOW_DOWNLOAD] ?: true,
            showSleepTimerButton = prefs[KEY_PLAYER_SHOW_SLEEP] ?: true,
            showLikeButton = prefs[KEY_PLAYER_SHOW_LIKE] ?: true,
            showLyricsButton = prefs[KEY_PLAYER_SHOW_LYRICS] ?: true,
            showUpNextPreview = prefs[KEY_PLAYER_SHOW_UP_NEXT] ?: true,
            showShuffleButton = prefs[KEY_PLAYER_SHOW_SHUFFLE] ?: true,
            showRepeatButton = prefs[KEY_PLAYER_SHOW_REPEAT] ?: true,
            showQueueButton = prefs[KEY_PLAYER_SHOW_QUEUE] ?: true,
            compactControls = prefs[KEY_PLAYER_COMPACT_CONTROLS] ?: false
        )

        val miniPlayer = MiniPlayerCustomizationConfig(
            style = prefs[KEY_MINI_STYLE]?.let { runCatching { MiniPlayerStyle.valueOf(it) }.getOrNull() }
                ?: MiniPlayerStyle.FLOATING_PILL,
            artworkShape = prefs[KEY_MINI_ARTWORK_SHAPE]?.let { runCatching { MiniPlayerArtworkShape.valueOf(it) }.getOrNull() }
                ?: MiniPlayerArtworkShape.CIRCLE,
            showArtwork = prefs[KEY_MINI_SHOW_ARTWORK] ?: true,
            showPreviousButton = prefs[KEY_MINI_SHOW_PREV] ?: false,
            showNextButton = prefs[KEY_MINI_SHOW_NEXT] ?: true,
            showLikeButton = prefs[KEY_MINI_SHOW_LIKE] ?: true,
            showShuffleButton = prefs[KEY_MINI_SHOW_SHUFFLE] ?: true,
            showProgressIndicator = prefs[KEY_MINI_SHOW_PROGRESS] ?: true,
            cornerRadiusDp = (prefs[KEY_MINI_CORNER_RADIUS_DP] ?: 32).coerceIn(12, 36),
            heightDp = (prefs[KEY_MINI_HEIGHT_DP] ?: 64).coerceIn(52, 76)
        )

        val gestures = GestureCustomizationConfig(
            swipeArtworkToSkip = prefs[KEY_GESTURE_SWIPE_ARTWORK] ?: true,
            swipeDownToCollapsePlayer = prefs[KEY_GESTURE_SWIPE_COLLAPSE] ?: true,
            swipeToDismissMiniPlayer = prefs[KEY_GESTURE_DISMISS_MINI] ?: true,
            swipeToRemoveQueueItem = prefs[KEY_GESTURE_REMOVE_QUEUE] ?: true,
            hapticFeedback = prefs[KEY_GESTURE_HAPTICS] ?: true,
            gestureSensitivity = (prefs[KEY_GESTURE_SENSITIVITY] ?: 1.0f).coerceIn(0.6f, 1.5f)
        )

        val typography = TypographyDensityConfig(
            fontChoice = prefs[KEY_TYPO_FONT_CHOICE]?.let { runCatching { AppFontChoice.valueOf(it) }.getOrNull() }
                ?: AppFontChoice.SYSTEM_SANS,
            fontScale = (prefs[KEY_TYPO_FONT_SCALE] ?: 1.0f).coerceIn(0.85f, 1.25f),
            titleScale = (prefs[KEY_TYPO_TITLE_SCALE] ?: 1.0f).coerceIn(0.85f, 1.25f),
            subtitleScale = (prefs[KEY_TYPO_SUBTITLE_SCALE] ?: 1.0f).coerceIn(0.85f, 1.25f),
            densityMode = prefs[KEY_TYPO_DENSITY_MODE]?.let { runCatching { UiDensityMode.valueOf(it) }.getOrNull() }
                ?: UiDensityMode.COMFORTABLE
        )

        val lyrics = LyricsCustomizationConfig(
            fontSizeSp = (prefs[KEY_LYRICS_FONT_SIZE_SP] ?: 22f).coerceIn(14f, 34f),
            lineSpacingMultiplier = (prefs[KEY_LYRICS_LINE_SPACING] ?: 1.35f).coerceIn(1.1f, 1.8f),
            alignment = prefs[KEY_LYRICS_ALIGNMENT]?.let { runCatching { LyricsTextAlignment.valueOf(it) }.getOrNull() }
                ?: LyricsTextAlignment.CENTER,
            showRomanization = prefs[KEY_LYRICS_SHOW_ROMANIZATION] ?: true,
            showTranslation = prefs[KEY_LYRICS_SHOW_TRANSLATION] ?: true,
            wordLevelHighlight = prefs[KEY_LYRICS_WORD_HIGHLIGHT] ?: true,
            activeColorFollowsAccent = prefs[KEY_LYRICS_ACCENT_COLOR] ?: true
        )

        val widgets = WidgetCustomizationConfig(
            backgroundStyle = prefs[KEY_WIDGET_BG_STYLE]?.let { runCatching { WidgetBackgroundStyle.valueOf(it) }.getOrNull() }
                ?: WidgetBackgroundStyle.DARK_GLASS,
            showArtwork = prefs[KEY_WIDGET_SHOW_ARTWORK] ?: true,
            showPrevNext = prefs[KEY_WIDGET_SHOW_PREV_NEXT] ?: true,
            showArtist = prefs[KEY_WIDGET_SHOW_ARTIST] ?: true
        )

        return CustomizationState(
            theme = theme,
            glassPreset = prefs[KEY_GLASS_PRESET]?.let { runCatching { GlassPreset.valueOf(it) }.getOrNull() }
                ?: GlassPreset.BALANCED,
            asciiPerformanceMode = prefs[KEY_ASCII_PERF_MODE]?.let { runCatching { AsciiPerformanceMode.valueOf(it) }.getOrNull() }
                ?: AsciiPerformanceMode.AUTO,
            home = home,
            navigation = navigation,
            player = player,
            miniPlayer = miniPlayer,
            gestures = gestures,
            typography = typography,
            lyrics = lyrics,
            widgets = widgets
        )
    }

    fun resetCategory(prefs: MutablePreferences, category: CustomizationCategory) {
        when (category) {
            CustomizationCategory.THEME_COLORS -> {
                prefs.remove(KEY_THEME_VARIANT)
                prefs.remove(KEY_PALETTE_PRESET)
                prefs.remove(KEY_CUSTOM_PRIMARY_ARGB)
                prefs.remove(KEY_CUSTOM_SECONDARY_ARGB)
                prefs.remove(KEY_CUSTOM_BG_ARGB)
                prefs.remove(KEY_CUSTOM_SURFACE_ARGB)
                prefs.remove(KEY_DYNAMIC_PLAYER_ACCENT)
            }
            CustomizationCategory.LIQUID_GLASS -> {
                prefs.remove(KEY_GLASS_PRESET)
            }
            CustomizationCategory.ASCII_BACKGROUND -> {
                prefs.remove(KEY_ASCII_PERF_MODE)
            }
            CustomizationCategory.HOME_SCREEN -> {
                prefs.remove(KEY_HOME_SECTION_ORDER)
                prefs.remove(KEY_HOME_HIDDEN_SECTIONS)
                prefs.remove(KEY_HOME_LAYOUT_MODE)
                prefs.remove(KEY_HOME_GRID_COLUMNS)
                prefs.remove(KEY_HOME_ARTWORK_SIZE_DP)
                prefs.remove(KEY_HOME_CORNER_RADIUS_DP)
                prefs.remove(KEY_HOME_SHOW_TITLES)
                prefs.remove(KEY_HOME_SHOW_METADATA)
                prefs.remove(KEY_HOME_SHOW_GREETING)
            }
            CustomizationCategory.NAVIGATION -> {
                prefs.remove(KEY_NAV_VISIBLE_TABS)
                prefs.remove(KEY_NAV_STYLE)
                prefs.remove(KEY_NAV_SHOW_LABELS)
            }
            CustomizationCategory.PLAYER_APPEARANCE -> {
                prefs.remove(KEY_PLAYER_LAYOUT_STYLE)
                prefs.remove(KEY_PLAYER_ARTWORK_SHAPE)
                prefs.remove(KEY_PLAYER_ARTWORK_SCALE)
                prefs.remove(KEY_PLAYER_BG_STYLE)
                prefs.remove(KEY_PLAYER_PROGRESS_STYLE)
                prefs.remove(KEY_PLAYER_PROGRESS_HEIGHT)
                prefs.remove(KEY_PLAYER_ROUNDED_PLAY)
                prefs.remove(KEY_PLAYER_SHOW_DOWNLOAD)
                prefs.remove(KEY_PLAYER_SHOW_SLEEP)
                prefs.remove(KEY_PLAYER_SHOW_LIKE)
                prefs.remove(KEY_PLAYER_SHOW_LYRICS)
                prefs.remove(KEY_PLAYER_SHOW_UP_NEXT)
                prefs.remove(KEY_PLAYER_SHOW_SHUFFLE)
                prefs.remove(KEY_PLAYER_SHOW_REPEAT)
                prefs.remove(KEY_PLAYER_SHOW_QUEUE)
                prefs.remove(KEY_PLAYER_COMPACT_CONTROLS)
            }
            CustomizationCategory.MINI_PLAYER -> {
                prefs.remove(KEY_MINI_STYLE)
                prefs.remove(KEY_MINI_ARTWORK_SHAPE)
                prefs.remove(KEY_MINI_SHOW_ARTWORK)
                prefs.remove(KEY_MINI_SHOW_PREV)
                prefs.remove(KEY_MINI_SHOW_NEXT)
                prefs.remove(KEY_MINI_SHOW_LIKE)
                prefs.remove(KEY_MINI_SHOW_SHUFFLE)
                prefs.remove(KEY_MINI_SHOW_PROGRESS)
                prefs.remove(KEY_MINI_CORNER_RADIUS_DP)
                prefs.remove(KEY_MINI_HEIGHT_DP)
            }
            CustomizationCategory.GESTURES -> {
                prefs.remove(KEY_GESTURE_SWIPE_ARTWORK)
                prefs.remove(KEY_GESTURE_SWIPE_COLLAPSE)
                prefs.remove(KEY_GESTURE_DISMISS_MINI)
                prefs.remove(KEY_GESTURE_REMOVE_QUEUE)
                prefs.remove(KEY_GESTURE_HAPTICS)
                prefs.remove(KEY_GESTURE_SENSITIVITY)
            }
            CustomizationCategory.TYPOGRAPHY_DENSITY -> {
                prefs.remove(KEY_TYPO_FONT_CHOICE)
                prefs.remove(KEY_TYPO_FONT_SCALE)
                prefs.remove(KEY_TYPO_TITLE_SCALE)
                prefs.remove(KEY_TYPO_SUBTITLE_SCALE)
                prefs.remove(KEY_TYPO_DENSITY_MODE)
            }
            CustomizationCategory.LYRICS_APPEARANCE -> {
                prefs.remove(KEY_LYRICS_FONT_SIZE_SP)
                prefs.remove(KEY_LYRICS_LINE_SPACING)
                prefs.remove(KEY_LYRICS_ALIGNMENT)
                prefs.remove(KEY_LYRICS_SHOW_ROMANIZATION)
                prefs.remove(KEY_LYRICS_SHOW_TRANSLATION)
                prefs.remove(KEY_LYRICS_WORD_HIGHLIGHT)
                prefs.remove(KEY_LYRICS_ACCENT_COLOR)
            }
            CustomizationCategory.WIDGETS -> {
                prefs.remove(KEY_WIDGET_BG_STYLE)
                prefs.remove(KEY_WIDGET_SHOW_ARTWORK)
                prefs.remove(KEY_WIDGET_SHOW_PREV_NEXT)
                prefs.remove(KEY_WIDGET_SHOW_ARTIST)
            }
            CustomizationCategory.PLAYBACK_PREFERENCES,
            CustomizationCategory.APP_ICONS -> Unit
        }
    }

    fun resetAll(prefs: MutablePreferences) {
        CustomizationCategory.entries.forEach { resetCategory(prefs, it) }
    }
}
