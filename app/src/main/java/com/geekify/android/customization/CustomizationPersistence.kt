package com.geekify.android.customization

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Strongly-typed DataStore persistence, validation, and backward-compatible migration for
 * [CustomizationState]. Shares the application's single [androidx.datastore.core.DataStore]
 * instance via [com.geekify.android.settings.AppSettingsRepository].
 */
object CustomizationPersistence {
    // Theme & Colors
    val KEY_THEME_MODE = stringPreferencesKey("cust_theme_mode")
    val KEY_THEME_VARIANT = stringPreferencesKey("cust_theme_variant")
    val KEY_PALETTE_PRESET = stringPreferencesKey("cust_palette_preset")
    val KEY_PALETTE_STYLE = stringPreferencesKey("cust_palette_style")
    val KEY_CUSTOM_PRIMARY_ARGB = longPreferencesKey("cust_custom_primary_argb")
    val KEY_CUSTOM_SECONDARY_ARGB = longPreferencesKey("cust_custom_secondary_argb")
    val KEY_CUSTOM_BG_ARGB = longPreferencesKey("cust_custom_bg_argb")
    val KEY_CUSTOM_SURFACE_ARGB = longPreferencesKey("cust_custom_surface_argb")
    val KEY_MATERIAL_YOU = booleanPreferencesKey("cust_material_you")
    val KEY_DYNAMIC_PLAYER_ACCENT = booleanPreferencesKey("cust_dynamic_player_accent")
    val KEY_UI_ENHANCEMENTS = booleanPreferencesKey("cust_ui_enhancements")

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
    val KEY_NAV_TAB_ORDER = stringPreferencesKey("cust_nav_tab_order")
    val KEY_NAV_HIDDEN_TABS = stringPreferencesKey("cust_nav_hidden_tabs")
    val KEY_NAV_VISIBLE_TABS = stringPreferencesKey("cust_nav_visible_tabs")
    val KEY_NAV_STYLE = stringPreferencesKey("cust_nav_style")
    val KEY_NAV_SHOW_LABELS = booleanPreferencesKey("cust_nav_show_labels")
    val KEY_NAV_FLOATING = booleanPreferencesKey("cust_nav_floating")
    val KEY_NAV_FLOATING_TOP_BAR = booleanPreferencesKey("cust_nav_floating_top_bar")

    // Expanded Player
    val KEY_PLAYER_LAYOUT_STYLE = stringPreferencesKey("cust_player_layout_style")
    val KEY_PLAYER_ARTWORK_SHAPE = stringPreferencesKey("cust_player_artwork_shape")
    val KEY_PLAYER_ARTWORK_SCALE = floatPreferencesKey("cust_player_artwork_scale")
    val KEY_PLAYER_ARTWORK_CROP_MODE = stringPreferencesKey("cust_player_artwork_crop_mode")
    val KEY_PLAYER_ARTWORK_PRESENTATION = stringPreferencesKey("cust_player_artwork_presentation")
    val KEY_PLAYER_ARTWORK_TRANSITIONS = booleanPreferencesKey("cust_player_artwork_transitions")
    val KEY_PLAYER_ANIMATED_ARTWORK = booleanPreferencesKey("cust_player_animated_artwork")
    val KEY_PLAYER_DISABLE_ANIM_ARTWORK_CELLULAR = booleanPreferencesKey("cust_player_disable_anim_artwork_cellular")
    val KEY_PLAYER_BG_STYLE = stringPreferencesKey("cust_player_bg_style")
    val KEY_PLAYER_PROGRESS_STYLE = stringPreferencesKey("cust_player_progress_style")
    val KEY_PLAYER_PROGRESS_HEIGHT = floatPreferencesKey("cust_player_progress_height")
    val KEY_PLAYER_ROUNDED_PLAY = booleanPreferencesKey("cust_player_rounded_play")
    val KEY_PLAYER_IMMERSIVE = booleanPreferencesKey("cust_player_immersive")
    val KEY_PLAYER_SHOW_ALBUM_META = booleanPreferencesKey("cust_player_show_album_meta")
    val KEY_PLAYER_SHOW_REMAINING = booleanPreferencesKey("cust_player_show_remaining")
    val KEY_PLAYER_SHOW_DOWNLOAD = booleanPreferencesKey("cust_player_show_download")
    val KEY_PLAYER_SHOW_SLEEP = booleanPreferencesKey("cust_player_show_sleep")
    val KEY_PLAYER_SHOW_LIKE = booleanPreferencesKey("cust_player_show_like")
    val KEY_PLAYER_SHOW_LYRICS = booleanPreferencesKey("cust_player_show_lyrics")
    val KEY_PLAYER_SHOW_UP_NEXT = booleanPreferencesKey("cust_player_show_up_next")
    val KEY_PLAYER_SHOW_SHUFFLE = booleanPreferencesKey("cust_player_show_shuffle")
    val KEY_PLAYER_SHOW_REPEAT = booleanPreferencesKey("cust_player_show_repeat")
    val KEY_PLAYER_SHOW_QUEUE = booleanPreferencesKey("cust_player_show_queue")
    val KEY_PLAYER_COMPACT_CONTROLS = booleanPreferencesKey("cust_player_compact_controls")

    // Seekbar Appearance
    val KEY_SEEKBAR_STYLE = stringPreferencesKey("cust_seekbar_style")
    val KEY_SEEKBAR_THUMB_STYLE = stringPreferencesKey("cust_seekbar_thumb_style")
    val KEY_SEEKBAR_THUMB_SIZE_DP = floatPreferencesKey("cust_seekbar_thumb_size_dp")
    val KEY_SEEKBAR_THICKNESS_DP = floatPreferencesKey("cust_seekbar_thickness_dp")
    val KEY_SEEKBAR_CORNER_RADIUS_DP = floatPreferencesKey("cust_seekbar_corner_radius_dp")
    val KEY_SEEKBAR_INACTIVE_ALPHA = floatPreferencesKey("cust_seekbar_inactive_alpha")
    val KEY_SEEKBAR_USE_CUSTOM_ACTIVE = booleanPreferencesKey("cust_seekbar_use_custom_active")
    val KEY_SEEKBAR_CUSTOM_ACTIVE_ARGB = longPreferencesKey("cust_seekbar_custom_active_argb")
    val KEY_SEEKBAR_USE_CUSTOM_THUMB = booleanPreferencesKey("cust_seekbar_use_custom_thumb")
    val KEY_SEEKBAR_CUSTOM_THUMB_ARGB = longPreferencesKey("cust_seekbar_custom_thumb_argb")
    val KEY_SEEKBAR_GRADIENT_ENABLED = booleanPreferencesKey("cust_seekbar_gradient_enabled")
    val KEY_SEEKBAR_GRADIENT_PRESET = stringPreferencesKey("cust_seekbar_gradient_preset")
    val KEY_SEEKBAR_ANIM_ENABLED = booleanPreferencesKey("cust_seekbar_anim_enabled")
    val KEY_SEEKBAR_WAVE_ANIM = booleanPreferencesKey("cust_seekbar_wave_anim")
    val KEY_SEEKBAR_WAVE_AMP = floatPreferencesKey("cust_seekbar_wave_amp")
    val KEY_SEEKBAR_WAVE_FREQ = floatPreferencesKey("cust_seekbar_wave_freq")
    val KEY_SEEKBAR_ANIM_INTENSITY = floatPreferencesKey("cust_seekbar_anim_intensity")
    val KEY_SEEKBAR_GLOW_INTENSITY = floatPreferencesKey("cust_seekbar_glow_intensity")
    val KEY_SEEKBAR_WAVE_SPEED = floatPreferencesKey("cust_seekbar_wave_speed")
    val KEY_SEEKBAR_HAPTICS = booleanPreferencesKey("cust_seekbar_haptics")
    val KEY_SEEKBAR_SHOW_ELAPSED = booleanPreferencesKey("cust_seekbar_show_elapsed")
    val KEY_SEEKBAR_SHOW_REMAINING = booleanPreferencesKey("cust_seekbar_show_remaining")
    val KEY_SEEKBAR_TIME_SCALE = floatPreferencesKey("cust_seekbar_time_scale")
    val KEY_SEEKBAR_TOUCH_HEIGHT = floatPreferencesKey("cust_seekbar_touch_height")

    // Mini Player
    val KEY_MINI_STYLE = stringPreferencesKey("cust_mini_style")
    val KEY_MINI_BG_STYLE = stringPreferencesKey("cust_mini_bg_style")
    val KEY_MINI_ARTWORK_SHAPE = stringPreferencesKey("cust_mini_artwork_shape")
    val KEY_MINI_ARTWORK_SIZE_DP = intPreferencesKey("cust_mini_artwork_size_dp")
    val KEY_MINI_SHOW_ARTWORK = booleanPreferencesKey("cust_mini_show_artwork")
    val KEY_MINI_SHOW_PREV = booleanPreferencesKey("cust_mini_show_prev")
    val KEY_MINI_SHOW_PLAY_PAUSE = booleanPreferencesKey("cust_mini_show_play_pause")
    val KEY_MINI_SHOW_NEXT = booleanPreferencesKey("cust_mini_show_next")
    val KEY_MINI_SHOW_LIKE = booleanPreferencesKey("cust_mini_show_like")
    val KEY_MINI_SHOW_SHUFFLE = booleanPreferencesKey("cust_mini_show_shuffle")
    val KEY_MINI_SHOW_QUEUE = booleanPreferencesKey("cust_mini_show_queue")
    val KEY_MINI_SHOW_PROGRESS = booleanPreferencesKey("cust_mini_show_progress")
    val KEY_MINI_CORNER_RADIUS_DP = intPreferencesKey("cust_mini_corner_radius_dp")
    val KEY_MINI_HEIGHT_DP = intPreferencesKey("cust_mini_height_dp")
    val KEY_MINI_H_PADDING_DP = intPreferencesKey("cust_mini_h_padding_dp")
    val KEY_MINI_SURFACE_ALPHA = floatPreferencesKey("cust_mini_surface_alpha")
    val KEY_MINI_GLASS_EFFECT = booleanPreferencesKey("cust_mini_glass_effect")
    val KEY_MINI_ELEVATION_DP = intPreferencesKey("cust_mini_elevation_dp")
    val KEY_MINI_EXPRESSIVE_COOKIE = booleanPreferencesKey("cust_mini_expressive_cookie")
    val KEY_MINI_EXPRESSIVE_MOTION = booleanPreferencesKey("cust_mini_expressive_motion")
    val KEY_MINI_EXPRESSIVE_RING = booleanPreferencesKey("cust_mini_expressive_ring")
    val KEY_MINI_EXPRESSIVE_ART_PLAY = booleanPreferencesKey("cust_mini_expressive_art_play")

    // Gestures
    val KEY_GESTURE_SWIPE_ARTWORK = booleanPreferencesKey("cust_gesture_swipe_artwork")
    val KEY_GESTURE_SWIPE_COLLAPSE = booleanPreferencesKey("cust_gesture_swipe_collapse")
    val KEY_GESTURE_DISMISS_MINI = booleanPreferencesKey("cust_gesture_dismiss_mini")
    val KEY_GESTURE_REMOVE_QUEUE = booleanPreferencesKey("cust_gesture_remove_queue")
    val KEY_GESTURE_HAPTICS = booleanPreferencesKey("cust_gesture_haptics")
    val KEY_GESTURE_SENSITIVITY = floatPreferencesKey("cust_gesture_sensitivity")
    val KEY_GESTURE_ARTWORK_DOUBLE_TAP_SKIP = booleanPreferencesKey("cust_gesture_artwork_double_tap_skip")
    val KEY_GESTURE_ARTWORK_SKIP_DURATION_SEC = intPreferencesKey("cust_gesture_artwork_skip_duration_sec")
    val KEY_GESTURE_ARTWORK_PROGRESSIVE_SKIP = booleanPreferencesKey("cust_gesture_artwork_progressive_skip")

    // Typography & Density
    val KEY_TYPO_FONT_CHOICE = stringPreferencesKey("cust_typo_font_choice")
    val KEY_TYPO_FONT_SCALE = floatPreferencesKey("cust_typo_font_scale")
    val KEY_TYPO_TITLE_SCALE = floatPreferencesKey("cust_typo_title_scale")
    val KEY_TYPO_SUBTITLE_SCALE = floatPreferencesKey("cust_typo_subtitle_scale")
    val KEY_TYPO_DENSITY_MODE = stringPreferencesKey("cust_typo_density_mode")
    val KEY_TYPO_DISPLAY_DENSITY = stringPreferencesKey("cust_typo_display_density")
    val KEY_TYPO_LANDSCAPE_SCALING = booleanPreferencesKey("cust_typo_landscape_scaling")

    // Lyrics
    val KEY_LYRICS_FONT_SIZE_SP = floatPreferencesKey("cust_lyrics_font_size_sp")
    val KEY_LYRICS_LINE_SPACING = floatPreferencesKey("cust_lyrics_line_spacing")
    val KEY_LYRICS_SUBTITLE_FONT_SIZE_SP = floatPreferencesKey("cust_lyrics_subtitle_font_size_sp")
    val KEY_LYRICS_ALIGNMENT = stringPreferencesKey("cust_lyrics_alignment")
    val KEY_LYRICS_ACTIVE_ALIGNMENT = stringPreferencesKey("cust_lyrics_active_alignment")
    val KEY_LYRICS_RESPECT_PROVIDER_POS = booleanPreferencesKey("cust_lyrics_respect_provider_pos")
    val KEY_LYRICS_ANIMATION_STYLE = stringPreferencesKey("cust_lyrics_animation_style")
    val KEY_LYRICS_SHOW_ORIGINAL = booleanPreferencesKey("cust_lyrics_show_original")
    val KEY_LYRICS_SHOW_ROMANIZATION = booleanPreferencesKey("cust_lyrics_show_romanization")
    val KEY_LYRICS_SHOW_TRANSLATION = booleanPreferencesKey("cust_lyrics_show_translation")
    val KEY_LYRICS_WORD_HIGHLIGHT = booleanPreferencesKey("cust_lyrics_word_highlight")
    val KEY_LYRICS_ACCENT_COLOR = booleanPreferencesKey("cust_lyrics_accent_color")
    val KEY_LYRICS_NEUTRAL_COLOR = booleanPreferencesKey("cust_lyrics_neutral_color")
    val KEY_LYRICS_AUTO_SCROLL = booleanPreferencesKey("cust_lyrics_auto_scroll")
    val KEY_LYRICS_TAP_TO_SEEK = booleanPreferencesKey("cust_lyrics_tap_to_seek")
    val KEY_LYRICS_PREFER_WORD_SYNC = booleanPreferencesKey("cust_lyrics_prefer_word_sync")
    val KEY_LYRICS_REAL_TIMING = booleanPreferencesKey("cust_lyrics_real_timing")
    val KEY_LYRICS_GLOBAL_OFFSET_MS = longPreferencesKey("cust_lyrics_global_offset_ms")
    val KEY_LYRICS_SHOW_ON_PLAYER = booleanPreferencesKey("cust_lyrics_show_on_player")
    val KEY_LYRICS_HIDE_STATUS_BAR = booleanPreferencesKey("cust_lyrics_hide_status_bar")
    val KEY_LYRICS_PROGRESSIVE_BLUR = booleanPreferencesKey("cust_lyrics_progressive_blur")
    val KEY_LYRICS_PLUS_LIGHTER = booleanPreferencesKey("cust_lyrics_plus_lighter")
    val KEY_LYRICS_FULLSCREEN_DEFAULT = booleanPreferencesKey("cust_lyrics_fullscreen_default")

    // Widgets
    val KEY_WIDGET_BG_STYLE = stringPreferencesKey("cust_widget_bg_style")
    val KEY_WIDGET_SHOW_ARTWORK = booleanPreferencesKey("cust_widget_show_artwork")
    val KEY_WIDGET_SHOW_PREV_NEXT = booleanPreferencesKey("cust_widget_show_prev_next")
    val KEY_WIDGET_SHOW_ARTIST = booleanPreferencesKey("cust_widget_show_artist")

    fun fromPreferences(prefs: Preferences): CustomizationState {
        val themeVariant = prefs[KEY_THEME_VARIANT]?.let { runCatching { DarkThemeVariant.valueOf(it) }.getOrNull() }
            ?: DarkThemeVariant.STANDARD_DARK
        val themeMode = prefs[KEY_THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: if (themeVariant == DarkThemeVariant.AMOLED_BLACK) ThemeMode.PURE_BLACK else ThemeMode.DARK

        val theme = ThemeCustomizationConfig(
            themeMode = themeMode,
            themeVariant = themeVariant,
            palettePreset = prefs[KEY_PALETTE_PRESET]?.let { runCatching { AccentPalettePreset.valueOf(it) }.getOrNull() }
                ?: AccentPalettePreset.ELECTRIC_LIME,
            paletteStyle = prefs[KEY_PALETTE_STYLE]?.let { runCatching { ThemePaletteStyle.valueOf(it) }.getOrNull() }
                ?: ThemePaletteStyle.TONAL_SPOT,
            customPrimaryArgb = prefs[KEY_CUSTOM_PRIMARY_ARGB] ?: 0xFFC8E65A,
            customSecondaryArgb = prefs[KEY_CUSTOM_SECONDARY_ARGB] ?: 0xFFCF9CF1,
            customBackgroundArgb = prefs[KEY_CUSTOM_BG_ARGB] ?: 0xFF0B0A10,
            customSurfaceArgb = prefs[KEY_CUSTOM_SURFACE_ARGB] ?: 0xFF15131C,
            materialYouEnabled = prefs[KEY_MATERIAL_YOU] ?: false,
            dynamicPlayerAccent = prefs[KEY_DYNAMIC_PLAYER_ACCENT] ?: true,
            uiEnhancements = prefs[KEY_UI_ENHANCEMENTS] ?: true
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

        val legacyVisibleTabs = prefs[KEY_NAV_VISIBLE_TABS]
            ?.split(',')
            ?.mapNotNull { name -> runCatching { NavTabId.valueOf(name.trim()) }.getOrNull() }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }

        val tabOrder = prefs[KEY_NAV_TAB_ORDER]
            ?.split(',')
            ?.mapNotNull { name -> runCatching { NavTabId.valueOf(name.trim()) }.getOrNull() }
            ?.distinct()
            ?.let { saved ->
                val missing = NavTabId.DEFAULT_TABS.filter { it !in saved }
                (saved + missing).ifEmpty { NavTabId.DEFAULT_TABS }
            }
            ?: legacyVisibleTabs?.let { vis ->
                val missing = NavTabId.DEFAULT_TABS.filter { it !in vis }
                (vis + missing).ifEmpty { NavTabId.DEFAULT_TABS }
            }
            ?: NavTabId.DEFAULT_TABS

        val hiddenTabs = when {
            prefs.contains(KEY_NAV_HIDDEN_TABS) -> prefs[KEY_NAV_HIDDEN_TABS]
                ?.split(',')
                ?.mapNotNull { name -> runCatching { NavTabId.valueOf(name.trim()) }.getOrNull() }
                ?.toSet() ?: emptySet()
            legacyVisibleTabs != null -> NavTabId.DEFAULT_TABS.filter { it !in legacyVisibleTabs }.toSet()
            else -> emptySet()
        }

        val visibleTabs = tabOrder.filter { it !in hiddenTabs }.ifEmpty { listOf(NavTabId.HOME) }

        val navigation = NavigationCustomizationConfig(
            tabOrder = tabOrder,
            hiddenTabs = hiddenTabs,
            visibleTabs = visibleTabs,
            style = prefs[KEY_NAV_STYLE]?.let { runCatching { NavBarStyle.valueOf(it) }.getOrNull() }
                ?: NavBarStyle.FLOATING_CAPSULE,
            showLabels = prefs[KEY_NAV_SHOW_LABELS] ?: false,
            floatingNavBar = prefs[KEY_NAV_FLOATING] ?: true,
            floatingTopBar = prefs[KEY_NAV_FLOATING_TOP_BAR] ?: false
        )

        fun parseProgressStyle(raw: String?): PlayerProgressStyle? = when (raw) {
            null -> null
            "DEFAULT", "GRADIENT" -> PlayerProgressStyle.CLASSIC_ROUNDED
            "SQUIGGLY" -> PlayerProgressStyle.WAVY
            "MINIMAL_LINE" -> PlayerProgressStyle.SLIM_LINEAR
            else -> runCatching { PlayerProgressStyle.valueOf(raw) }.getOrNull()
        }

        val resolvedProgressStyle = parseProgressStyle(prefs[KEY_SEEKBAR_STYLE])
            ?: parseProgressStyle(prefs[KEY_PLAYER_PROGRESS_STYLE])
            ?: PlayerProgressStyle.WAVE_GLOW
        val resolvedProgressThickness = (prefs[KEY_SEEKBAR_THICKNESS_DP] ?: prefs[KEY_PLAYER_PROGRESS_HEIGHT] ?: 4f)
            .coerceIn(2f, 12f)

        val player = PlayerCustomizationConfig(
            layoutStyle = prefs[KEY_PLAYER_LAYOUT_STYLE]?.let { runCatching { PlayerLayoutStyle.valueOf(it) }.getOrNull() }
                ?: PlayerLayoutStyle.VINYL_TONEARM,
            artworkShape = prefs[KEY_PLAYER_ARTWORK_SHAPE]?.let { runCatching { PlayerArtworkShape.valueOf(it) }.getOrNull() }
                ?: PlayerArtworkShape.VINYL_DISC,
            artworkScale = (prefs[KEY_PLAYER_ARTWORK_SCALE] ?: 1.0f).coerceIn(0.75f, 1.20f),
            artworkCropMode = prefs[KEY_PLAYER_ARTWORK_CROP_MODE]?.let { runCatching { PlayerArtworkCropMode.valueOf(it) }.getOrNull() }
                ?: PlayerArtworkCropMode.CROP,
            artworkPresentation = prefs[KEY_PLAYER_ARTWORK_PRESENTATION]?.let { runCatching { PlayerArtworkPresentation.valueOf(it) }.getOrNull() }
                ?: PlayerArtworkPresentation.STANDARD,
            artworkTransitions = prefs[KEY_PLAYER_ARTWORK_TRANSITIONS] ?: true,
            animatedArtworkEnabled = prefs[KEY_PLAYER_ANIMATED_ARTWORK] ?: true,
            disableAnimatedArtworkOnMobileData = prefs[KEY_PLAYER_DISABLE_ANIM_ARTWORK_CELLULAR] ?: false,
            backgroundStyle = prefs[KEY_PLAYER_BG_STYLE]?.let { runCatching { PlayerBackgroundStyle.valueOf(it) }.getOrNull() }
                ?: PlayerBackgroundStyle.BLURRED_ARTWORK,
            progressStyle = resolvedProgressStyle,
            progressHeightDp = resolvedProgressThickness,
            roundedPlayButton = prefs[KEY_PLAYER_ROUNDED_PLAY] ?: true,
            immersivePlayer = prefs[KEY_PLAYER_IMMERSIVE] ?: false,
            showAlbumMetadata = prefs[KEY_PLAYER_SHOW_ALBUM_META] ?: true,
            showRemainingTime = prefs[KEY_PLAYER_SHOW_REMAINING] ?: true,
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

        val seekbar = SeekbarCustomizationConfig(
            style = resolvedProgressStyle,
            thumbStyle = prefs[KEY_SEEKBAR_THUMB_STYLE]?.let { runCatching { SeekbarThumbStyle.valueOf(it) }.getOrNull() }
                ?: SeekbarThumbStyle.DEFAULT_CIRCLE,
            thumbSizeDp = (prefs[KEY_SEEKBAR_THUMB_SIZE_DP] ?: 14f).coerceIn(6f, 24f),
            thicknessDp = resolvedProgressThickness,
            cornerRadiusDp = (prefs[KEY_SEEKBAR_CORNER_RADIUS_DP] ?: 6f).coerceIn(0f, 12f),
            inactiveTrackAlpha = (prefs[KEY_SEEKBAR_INACTIVE_ALPHA] ?: 0.24f).coerceIn(0.08f, 0.60f),
            useCustomActiveColor = prefs[KEY_SEEKBAR_USE_CUSTOM_ACTIVE] ?: false,
            customActiveColorArgb = prefs[KEY_SEEKBAR_CUSTOM_ACTIVE_ARGB] ?: 0xFFC8E65A,
            useCustomThumbColor = prefs[KEY_SEEKBAR_USE_CUSTOM_THUMB] ?: false,
            customThumbColorArgb = prefs[KEY_SEEKBAR_CUSTOM_THUMB_ARGB] ?: 0xFFC8E65A,
            gradientEnabled = prefs[KEY_SEEKBAR_GRADIENT_ENABLED] ?: false,
            gradientPreset = prefs[KEY_SEEKBAR_GRADIENT_PRESET]?.let { runCatching { SeekbarGradientPreset.valueOf(it) }.getOrNull() }
                ?: SeekbarGradientPreset.ACCENT_HARMONY,
            animationsEnabled = prefs[KEY_SEEKBAR_ANIM_ENABLED] ?: true,
            waveAnimationEnabled = prefs[KEY_SEEKBAR_WAVE_ANIM] ?: true,
            waveAmplitudeDp = (prefs[KEY_SEEKBAR_WAVE_AMP] ?: 4.5f).coerceIn(1f, 10f),
            waveFrequency = (prefs[KEY_SEEKBAR_WAVE_FREQ] ?: 1.0f).coerceIn(0.5f, 2.0f),
            animationIntensity = (prefs[KEY_SEEKBAR_ANIM_INTENSITY] ?: 1.0f).coerceIn(0.3f, 1.8f),
            glowIntensity = (prefs[KEY_SEEKBAR_GLOW_INTENSITY] ?: 0.65f).coerceIn(0.1f, 1.0f),
            waveSpeed = (prefs[KEY_SEEKBAR_WAVE_SPEED] ?: 1.0f).coerceIn(0.4f, 2.2f),
            hapticFeedback = prefs[KEY_SEEKBAR_HAPTICS] ?: true,
            showElapsedTime = prefs[KEY_SEEKBAR_SHOW_ELAPSED] ?: true,
            showRemainingTime = prefs[KEY_SEEKBAR_SHOW_REMAINING] ?: player.showRemainingTime,
            timeLabelScale = (prefs[KEY_SEEKBAR_TIME_SCALE] ?: 1.0f).coerceIn(0.85f, 1.25f),
            touchTargetHeightDp = (prefs[KEY_SEEKBAR_TOUCH_HEIGHT] ?: 40f).coerceIn(28f, 56f)
        )

        val miniPlayer = MiniPlayerCustomizationConfig(
            style = prefs[KEY_MINI_STYLE]?.let { runCatching { MiniPlayerStyle.valueOf(it) }.getOrNull() }
                ?: MiniPlayerStyle.FLOATING_PILL,
            backgroundStyle = prefs[KEY_MINI_BG_STYLE]?.let { runCatching { MiniPlayerBackgroundStyle.valueOf(it) }.getOrNull() }
                ?: MiniPlayerBackgroundStyle.THEMED,
            artworkShape = prefs[KEY_MINI_ARTWORK_SHAPE]?.let { runCatching { MiniPlayerArtworkShape.valueOf(it) }.getOrNull() }
                ?: MiniPlayerArtworkShape.CIRCLE,
            artworkSizeDp = (prefs[KEY_MINI_ARTWORK_SIZE_DP] ?: 44).coerceIn(32, 56),
            showArtwork = prefs[KEY_MINI_SHOW_ARTWORK] ?: true,
            showPreviousButton = prefs[KEY_MINI_SHOW_PREV] ?: false,
            showPlayPauseButton = prefs[KEY_MINI_SHOW_PLAY_PAUSE] ?: true,
            showNextButton = prefs[KEY_MINI_SHOW_NEXT] ?: true,
            showLikeButton = prefs[KEY_MINI_SHOW_LIKE] ?: true,
            showShuffleButton = prefs[KEY_MINI_SHOW_SHUFFLE] ?: true,
            showQueueButton = prefs[KEY_MINI_SHOW_QUEUE] ?: false,
            showProgressIndicator = prefs[KEY_MINI_SHOW_PROGRESS] ?: true,
            cornerRadiusDp = (prefs[KEY_MINI_CORNER_RADIUS_DP] ?: 32).coerceIn(12, 36),
            heightDp = (prefs[KEY_MINI_HEIGHT_DP] ?: 64).coerceIn(52, 76),
            horizontalPaddingDp = (prefs[KEY_MINI_H_PADDING_DP] ?: 10).coerceIn(4, 20),
            surfaceAlpha = (prefs[KEY_MINI_SURFACE_ALPHA] ?: 0.88f).coerceIn(0.30f, 1.0f),
            glassEffect = prefs[KEY_MINI_GLASS_EFFECT] ?: true,
            elevationDp = (prefs[KEY_MINI_ELEVATION_DP] ?: 6).coerceIn(0, 16),
            expressiveCookieArtwork = prefs[KEY_MINI_EXPRESSIVE_COOKIE] ?: true,
            expressiveShapeMotion = prefs[KEY_MINI_EXPRESSIVE_MOTION] ?: true,
            expressiveProgressRing = prefs[KEY_MINI_EXPRESSIVE_RING] ?: true,
            expressiveArtworkPlaybackTap = prefs[KEY_MINI_EXPRESSIVE_ART_PLAY] ?: false
        )

        val gestures = GestureCustomizationConfig(
            swipeArtworkToSkip = prefs[KEY_GESTURE_SWIPE_ARTWORK] ?: true,
            swipeDownToCollapsePlayer = prefs[KEY_GESTURE_SWIPE_COLLAPSE] ?: true,
            swipeToDismissMiniPlayer = prefs[KEY_GESTURE_DISMISS_MINI] ?: true,
            swipeToRemoveQueueItem = prefs[KEY_GESTURE_REMOVE_QUEUE] ?: true,
            hapticFeedback = prefs[KEY_GESTURE_HAPTICS] ?: true,
            gestureSensitivity = (prefs[KEY_GESTURE_SENSITIVITY] ?: 1.0f).coerceIn(0.6f, 1.5f),
            artworkDoubleTapSkipEnabled = prefs[KEY_GESTURE_ARTWORK_DOUBLE_TAP_SKIP] ?: true,
            artworkSkipDurationSeconds = GestureCustomizationConfig.normalizeSkipDurationSeconds(
                prefs[KEY_GESTURE_ARTWORK_SKIP_DURATION_SEC] ?: GestureCustomizationConfig.DEFAULT_SKIP_DURATION_SECONDS
            ),
            artworkProgressiveSkipEnabled = prefs[KEY_GESTURE_ARTWORK_PROGRESSIVE_SKIP] ?: false
        )

        val legacyDensityMode = prefs[KEY_TYPO_DENSITY_MODE]?.let { runCatching { UiDensityMode.valueOf(it) }.getOrNull() }
            ?: UiDensityMode.COMFORTABLE
        val displayDensity = prefs[KEY_TYPO_DISPLAY_DENSITY]?.let { runCatching { DisplayDensityScale.valueOf(it) }.getOrNull() }
            ?: DisplayDensityScale.fromLegacy(legacyDensityMode)

        val typography = TypographyDensityConfig(
            fontChoice = prefs[KEY_TYPO_FONT_CHOICE]?.let { runCatching { AppFontChoice.valueOf(it) }.getOrNull() }
                ?: AppFontChoice.SOHNE_BREIT,
            fontScale = (prefs[KEY_TYPO_FONT_SCALE] ?: 1.0f).coerceIn(0.85f, 1.25f),
            titleScale = (prefs[KEY_TYPO_TITLE_SCALE] ?: 1.0f).coerceIn(0.85f, 1.25f),
            subtitleScale = (prefs[KEY_TYPO_SUBTITLE_SCALE] ?: 1.0f).coerceIn(0.85f, 1.25f),
            densityMode = legacyDensityMode,
            displayDensity = displayDensity,
            enableLandscapeScaling = prefs[KEY_TYPO_LANDSCAPE_SCALING] ?: true
        )

        val baseAlignment = prefs[KEY_LYRICS_ALIGNMENT]?.let { runCatching { LyricsTextAlignment.valueOf(it) }.getOrNull() }
            ?: LyricsTextAlignment.CENTER
        val activeAlignment = prefs[KEY_LYRICS_ACTIVE_ALIGNMENT]?.let { runCatching { LyricsTextAlignment.valueOf(it) }.getOrNull() }
            ?: baseAlignment

        val lyrics = LyricsCustomizationConfig(
            fontSizeSp = (prefs[KEY_LYRICS_FONT_SIZE_SP] ?: 22f).coerceIn(14f, 34f),
            lineSpacingMultiplier = (prefs[KEY_LYRICS_LINE_SPACING] ?: 1.35f).coerceIn(1.1f, 2.2f),
            subtitleFontSizeSp = (prefs[KEY_LYRICS_SUBTITLE_FONT_SIZE_SP] ?: 14f).coerceIn(10f, 28f),
            alignment = baseAlignment,
            activeAlignment = activeAlignment,
            respectProviderPositioning = prefs[KEY_LYRICS_RESPECT_PROVIDER_POS] ?: true,
            animationStyle = prefs[KEY_LYRICS_ANIMATION_STYLE]?.let { runCatching { LyricsAnimationStyle.valueOf(it) }.getOrNull() }
                ?: LyricsAnimationStyle.APPLE,
            showOriginalLyrics = prefs[KEY_LYRICS_SHOW_ORIGINAL] ?: true,
            showRomanization = prefs[KEY_LYRICS_SHOW_ROMANIZATION] ?: true,
            showTranslation = prefs[KEY_LYRICS_SHOW_TRANSLATION] ?: true,
            wordLevelHighlight = prefs[KEY_LYRICS_WORD_HIGHLIGHT] ?: true,
            activeColorFollowsAccent = prefs[KEY_LYRICS_ACCENT_COLOR] ?: true,
            useNeutralColor = prefs[KEY_LYRICS_NEUTRAL_COLOR] ?: false,
            autoScroll = prefs[KEY_LYRICS_AUTO_SCROLL] ?: true,
            tapToSeek = prefs[KEY_LYRICS_TAP_TO_SEEK] ?: true,
            preferWordSynced = prefs[KEY_LYRICS_PREFER_WORD_SYNC] ?: true,
            activeByRealTiming = prefs[KEY_LYRICS_REAL_TIMING] ?: true,
            globalOffsetMs = (prefs[KEY_LYRICS_GLOBAL_OFFSET_MS] ?: 0L).coerceIn(-5_000L, 5_000L),
            showLyricsOnPlayer = prefs[KEY_LYRICS_SHOW_ON_PLAYER] ?: false,
            hideStatusBarInFullscreen = prefs[KEY_LYRICS_HIDE_STATUS_BAR] ?: true,
            progressiveBlur = prefs[KEY_LYRICS_PROGRESSIVE_BLUR] ?: true,
            plusLighterBlend = prefs[KEY_LYRICS_PLUS_LIGHTER] ?: false,
            fullscreenDefault = prefs[KEY_LYRICS_FULLSCREEN_DEFAULT] ?: false
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
            seekbar = seekbar,
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
                prefs.remove(KEY_THEME_MODE)
                prefs.remove(KEY_THEME_VARIANT)
                prefs.remove(KEY_PALETTE_PRESET)
                prefs.remove(KEY_PALETTE_STYLE)
                prefs.remove(KEY_CUSTOM_PRIMARY_ARGB)
                prefs.remove(KEY_CUSTOM_SECONDARY_ARGB)
                prefs.remove(KEY_CUSTOM_BG_ARGB)
                prefs.remove(KEY_CUSTOM_SURFACE_ARGB)
                prefs.remove(KEY_MATERIAL_YOU)
                prefs.remove(KEY_DYNAMIC_PLAYER_ACCENT)
                prefs.remove(KEY_UI_ENHANCEMENTS)
            }
            CustomizationCategory.LIQUID_GLASS -> {
                prefs.remove(KEY_GLASS_PRESET)
                prefs.remove(com.geekify.android.settings.AppSettingsRepository.KEY_GLASS_TINT_MODE)
                prefs.remove(com.geekify.android.settings.AppSettingsRepository.KEY_GLASS_CUSTOM_TINT_ARGB)
                prefs.remove(com.geekify.android.settings.AppSettingsRepository.KEY_GLASS_BRIGHTNESS)
                prefs.remove(com.geekify.android.settings.AppSettingsRepository.KEY_GLASS_CORNER_RADIUS_DP)
                prefs.remove(com.geekify.android.settings.AppSettingsRepository.KEY_GLASS_BORDER_ENABLED)
                prefs.remove(com.geekify.android.settings.AppSettingsRepository.KEY_GLASS_BORDER_COLOR_ARGB)
                prefs.remove(com.geekify.android.settings.AppSettingsRepository.KEY_GLASS_BORDER_OPACITY)
                prefs.remove(com.geekify.android.settings.AppSettingsRepository.KEY_GLASS_BORDER_THICKNESS_DP)
                prefs.remove(com.geekify.android.settings.AppSettingsRepository.KEY_GLASS_SHADOW_DP)
                prefs.remove(com.geekify.android.settings.AppSettingsRepository.KEY_GLASS_NOISE_ALPHA)
                prefs.remove(com.geekify.android.settings.AppSettingsRepository.KEY_GLASS_QUALITY)
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
                prefs.remove(KEY_NAV_TAB_ORDER)
                prefs.remove(KEY_NAV_HIDDEN_TABS)
                prefs.remove(KEY_NAV_VISIBLE_TABS)
                prefs.remove(KEY_NAV_STYLE)
                prefs.remove(KEY_NAV_SHOW_LABELS)
                prefs.remove(KEY_NAV_FLOATING)
                prefs.remove(KEY_NAV_FLOATING_TOP_BAR)
            }
            CustomizationCategory.PLAYER_APPEARANCE -> {
                prefs.remove(KEY_PLAYER_LAYOUT_STYLE)
                prefs.remove(KEY_PLAYER_ARTWORK_SHAPE)
                prefs.remove(KEY_PLAYER_ARTWORK_SCALE)
                prefs.remove(KEY_PLAYER_ARTWORK_CROP_MODE)
                prefs.remove(KEY_PLAYER_ARTWORK_PRESENTATION)
                prefs.remove(KEY_PLAYER_ARTWORK_TRANSITIONS)
                prefs.remove(KEY_PLAYER_ANIMATED_ARTWORK)
                prefs.remove(KEY_PLAYER_DISABLE_ANIM_ARTWORK_CELLULAR)
                prefs.remove(KEY_PLAYER_BG_STYLE)
                prefs.remove(KEY_PLAYER_PROGRESS_STYLE)
                prefs.remove(KEY_PLAYER_PROGRESS_HEIGHT)
                prefs.remove(KEY_PLAYER_ROUNDED_PLAY)
                prefs.remove(KEY_PLAYER_IMMERSIVE)
                prefs.remove(KEY_PLAYER_SHOW_ALBUM_META)
                prefs.remove(KEY_PLAYER_SHOW_REMAINING)
                prefs.remove(KEY_PLAYER_SHOW_DOWNLOAD)
                prefs.remove(KEY_PLAYER_SHOW_SLEEP)
                prefs.remove(KEY_PLAYER_SHOW_LIKE)
                prefs.remove(KEY_PLAYER_SHOW_LYRICS)
                prefs.remove(KEY_PLAYER_SHOW_UP_NEXT)
                prefs.remove(KEY_PLAYER_SHOW_SHUFFLE)
                prefs.remove(KEY_PLAYER_SHOW_REPEAT)
                prefs.remove(KEY_PLAYER_SHOW_QUEUE)
                prefs.remove(KEY_PLAYER_COMPACT_CONTROLS)
                prefs.remove(KEY_SEEKBAR_STYLE)
                prefs.remove(KEY_SEEKBAR_THUMB_STYLE)
                prefs.remove(KEY_SEEKBAR_THUMB_SIZE_DP)
                prefs.remove(KEY_SEEKBAR_THICKNESS_DP)
                prefs.remove(KEY_SEEKBAR_CORNER_RADIUS_DP)
                prefs.remove(KEY_SEEKBAR_INACTIVE_ALPHA)
                prefs.remove(KEY_SEEKBAR_USE_CUSTOM_ACTIVE)
                prefs.remove(KEY_SEEKBAR_CUSTOM_ACTIVE_ARGB)
                prefs.remove(KEY_SEEKBAR_USE_CUSTOM_THUMB)
                prefs.remove(KEY_SEEKBAR_CUSTOM_THUMB_ARGB)
                prefs.remove(KEY_SEEKBAR_GRADIENT_ENABLED)
                prefs.remove(KEY_SEEKBAR_GRADIENT_PRESET)
                prefs.remove(KEY_SEEKBAR_ANIM_ENABLED)
                prefs.remove(KEY_SEEKBAR_WAVE_ANIM)
                prefs.remove(KEY_SEEKBAR_WAVE_AMP)
                prefs.remove(KEY_SEEKBAR_WAVE_FREQ)
                prefs.remove(KEY_SEEKBAR_ANIM_INTENSITY)
                prefs.remove(KEY_SEEKBAR_GLOW_INTENSITY)
                prefs.remove(KEY_SEEKBAR_WAVE_SPEED)
                prefs.remove(KEY_SEEKBAR_HAPTICS)
                prefs.remove(KEY_SEEKBAR_SHOW_ELAPSED)
                prefs.remove(KEY_SEEKBAR_SHOW_REMAINING)
                prefs.remove(KEY_SEEKBAR_TIME_SCALE)
                prefs.remove(KEY_SEEKBAR_TOUCH_HEIGHT)
            }
            CustomizationCategory.MINI_PLAYER -> {
                prefs.remove(KEY_MINI_STYLE)
                prefs.remove(KEY_MINI_BG_STYLE)
                prefs.remove(KEY_MINI_ARTWORK_SHAPE)
                prefs.remove(KEY_MINI_ARTWORK_SIZE_DP)
                prefs.remove(KEY_MINI_SHOW_ARTWORK)
                prefs.remove(KEY_MINI_SHOW_PREV)
                prefs.remove(KEY_MINI_SHOW_PLAY_PAUSE)
                prefs.remove(KEY_MINI_SHOW_NEXT)
                prefs.remove(KEY_MINI_SHOW_LIKE)
                prefs.remove(KEY_MINI_SHOW_SHUFFLE)
                prefs.remove(KEY_MINI_SHOW_QUEUE)
                prefs.remove(KEY_MINI_SHOW_PROGRESS)
                prefs.remove(KEY_MINI_CORNER_RADIUS_DP)
                prefs.remove(KEY_MINI_HEIGHT_DP)
                prefs.remove(KEY_MINI_H_PADDING_DP)
                prefs.remove(KEY_MINI_SURFACE_ALPHA)
                prefs.remove(KEY_MINI_GLASS_EFFECT)
                prefs.remove(KEY_MINI_ELEVATION_DP)
                prefs.remove(KEY_MINI_EXPRESSIVE_COOKIE)
                prefs.remove(KEY_MINI_EXPRESSIVE_MOTION)
                prefs.remove(KEY_MINI_EXPRESSIVE_RING)
                prefs.remove(KEY_MINI_EXPRESSIVE_ART_PLAY)
            }
            CustomizationCategory.GESTURES -> {
                prefs.remove(KEY_GESTURE_SWIPE_ARTWORK)
                prefs.remove(KEY_GESTURE_SWIPE_COLLAPSE)
                prefs.remove(KEY_GESTURE_DISMISS_MINI)
                prefs.remove(KEY_GESTURE_REMOVE_QUEUE)
                prefs.remove(KEY_GESTURE_HAPTICS)
                prefs.remove(KEY_GESTURE_SENSITIVITY)
                prefs.remove(KEY_GESTURE_ARTWORK_DOUBLE_TAP_SKIP)
                prefs.remove(KEY_GESTURE_ARTWORK_SKIP_DURATION_SEC)
                prefs.remove(KEY_GESTURE_ARTWORK_PROGRESSIVE_SKIP)
            }
            CustomizationCategory.TYPOGRAPHY_DENSITY -> {
                prefs.remove(KEY_TYPO_FONT_CHOICE)
                prefs.remove(KEY_TYPO_FONT_SCALE)
                prefs.remove(KEY_TYPO_TITLE_SCALE)
                prefs.remove(KEY_TYPO_SUBTITLE_SCALE)
                prefs.remove(KEY_TYPO_DENSITY_MODE)
                prefs.remove(KEY_TYPO_DISPLAY_DENSITY)
                prefs.remove(KEY_TYPO_LANDSCAPE_SCALING)
            }
            CustomizationCategory.LYRICS_APPEARANCE -> {
                prefs.remove(KEY_LYRICS_FONT_SIZE_SP)
                prefs.remove(KEY_LYRICS_LINE_SPACING)
                prefs.remove(KEY_LYRICS_SUBTITLE_FONT_SIZE_SP)
                prefs.remove(KEY_LYRICS_ALIGNMENT)
                prefs.remove(KEY_LYRICS_ACTIVE_ALIGNMENT)
                prefs.remove(KEY_LYRICS_RESPECT_PROVIDER_POS)
                prefs.remove(KEY_LYRICS_ANIMATION_STYLE)
                prefs.remove(KEY_LYRICS_SHOW_ORIGINAL)
                prefs.remove(KEY_LYRICS_SHOW_ROMANIZATION)
                prefs.remove(KEY_LYRICS_SHOW_TRANSLATION)
                prefs.remove(KEY_LYRICS_WORD_HIGHLIGHT)
                prefs.remove(KEY_LYRICS_ACCENT_COLOR)
                prefs.remove(KEY_LYRICS_NEUTRAL_COLOR)
                prefs.remove(KEY_LYRICS_AUTO_SCROLL)
                prefs.remove(KEY_LYRICS_TAP_TO_SEEK)
                prefs.remove(KEY_LYRICS_PREFER_WORD_SYNC)
                prefs.remove(KEY_LYRICS_REAL_TIMING)
                prefs.remove(KEY_LYRICS_GLOBAL_OFFSET_MS)
                prefs.remove(KEY_LYRICS_SHOW_ON_PLAYER)
                prefs.remove(KEY_LYRICS_HIDE_STATUS_BAR)
                prefs.remove(KEY_LYRICS_PROGRESSIVE_BLUR)
                prefs.remove(KEY_LYRICS_PLUS_LIGHTER)
                prefs.remove(KEY_LYRICS_FULLSCREEN_DEFAULT)
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
