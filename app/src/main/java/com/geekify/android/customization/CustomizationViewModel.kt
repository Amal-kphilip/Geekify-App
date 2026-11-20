package com.geekify.android.customization

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.audio.AudioEffectsController
import com.geekify.android.audio.AudioSettings
import com.geekify.android.audio.EqPresets
import com.geekify.android.settings.AppSettingsRepository
import com.geekify.android.settings.AppSettingsState
import com.geekify.android.settings.AudioQualityPreference
import com.geekify.android.ui.navigation.FloatingTabBarInlineBehavior
import com.geekify.android.widget.GeekifyMusicWidgetProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Centralized ViewModel coordinating Geekify's 13 personalization categories across
 * [AppSettingsRepository], [AudioEffectsController], and home screen widgets.
 */
class CustomizationViewModel(
    private val context: Context,
    private val settingsRepository: AppSettingsRepository,
    private val audioController: AudioEffectsController
) : ViewModel() {

    val appSettings: StateFlow<AppSettingsState> = settingsRepository.state
    val audioSettings: StateFlow<AudioSettings> = audioController.settings

    private val _selectedCategory = MutableStateFlow(CustomizationCategory.THEME_COLORS)
    val selectedCategory: StateFlow<CustomizationCategory> = _selectedCategory.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    fun selectCategory(category: CustomizationCategory) {
        _selectedCategory.value = category
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    // ---- 1. Theme & Colors ----
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_THEME_MODE] = mode.name
            }
        }
    }

    fun setThemeVariant(variant: DarkThemeVariant) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_THEME_VARIANT] = variant.name
                if (variant == DarkThemeVariant.AMOLED_BLACK) {
                    it[CustomizationPersistence.KEY_THEME_MODE] = ThemeMode.PURE_BLACK.name
                } else {
                    it[CustomizationPersistence.KEY_THEME_MODE] = ThemeMode.DARK.name
                }
            }
        }
    }

    fun setPalettePreset(preset: AccentPalettePreset) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_PALETTE_PRESET] = preset.name
            }
        }
    }

    fun setPaletteStyle(style: ThemePaletteStyle) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_PALETTE_STYLE] = style.name
            }
        }
    }

    fun setMaterialYouEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_MATERIAL_YOU] = enabled
            }
        }
    }

    fun setUiEnhancements(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_UI_ENHANCEMENTS] = enabled
            }
        }
    }

    fun setCustomPrimaryColor(argb: Long) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_PALETTE_PRESET] = AccentPalettePreset.CUSTOM.name
                it[CustomizationPersistence.KEY_CUSTOM_PRIMARY_ARGB] = argb
            }
        }
    }

    fun setCustomSecondaryColor(argb: Long) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_PALETTE_PRESET] = AccentPalettePreset.CUSTOM.name
                it[CustomizationPersistence.KEY_CUSTOM_SECONDARY_ARGB] = argb
            }
        }
    }

    fun setCustomBackgroundColor(argb: Long) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_THEME_VARIANT] = DarkThemeVariant.CUSTOM_DARK.name
                it[CustomizationPersistence.KEY_CUSTOM_BG_ARGB] = argb
            }
        }
    }

    fun setCustomSurfaceColor(argb: Long) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_THEME_VARIANT] = DarkThemeVariant.CUSTOM_DARK.name
                it[CustomizationPersistence.KEY_CUSTOM_SURFACE_ARGB] = argb
            }
        }
    }

    fun setDynamicPlayerAccent(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_DYNAMIC_PLAYER_ACCENT] = enabled
            }
        }
    }

    // ---- 2. Liquid Glass & Visual Effects ----
    fun applyGlassPreset(preset: GlassPreset) {
        viewModelScope.launch {
            settingsRepository.applyGlassPreset(preset)
        }
    }

    fun setGlassEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setGlassEnabled(enabled) }
    }

    fun setGlassBlurDp(value: Float) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_GLASS_PRESET] = GlassPreset.CUSTOM.name
            }
            settingsRepository.setGlassBlurDp(value)
        }
    }

    fun setGlassSaturation(value: Float) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_GLASS_PRESET] = GlassPreset.CUSTOM.name
            }
            settingsRepository.setGlassSaturation(value)
        }
    }

    fun setGlassLensAmountDp(value: Float) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_GLASS_PRESET] = GlassPreset.CUSTOM.name
            }
            settingsRepository.setGlassLensAmountDp(value)
        }
    }

    fun setGlassTintAlpha(value: Float) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_GLASS_PRESET] = GlassPreset.CUSTOM.name
            }
            settingsRepository.setGlassTintAlpha(value)
        }
    }

    fun setGlassChromaticAberration(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_GLASS_PRESET] = GlassPreset.CUSTOM.name
            }
            settingsRepository.setGlassChromaticAberration(enabled)
        }
    }

    fun setGlassDepthEffect(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_GLASS_PRESET] = GlassPreset.CUSTOM.name
            }
            settingsRepository.setGlassDepthEffect(enabled)
        }
    }

    fun setGlassMiniPlayer(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setGlassMiniPlayer(enabled) }
    }

    fun setGlassNavBar(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setGlassNavBar(enabled) }
    }

    fun setGlassAppearance(
        tintMode: GlassTintMode? = null,
        customTintArgb: Long? = null,
        brightness: Float? = null,
        cornerRadiusDp: Float? = null,
        borderEnabled: Boolean? = null,
        borderColorArgb: Long? = null,
        borderOpacity: Float? = null,
        borderThicknessDp: Float? = null,
        shadowElevationDp: Float? = null,
        noiseGrainAlpha: Float? = null,
        qualityLevel: GlassQualityLevel? = null
    ) {
        viewModelScope.launch {
            settingsRepository.editCustomization { prefs ->
                prefs[CustomizationPersistence.KEY_GLASS_PRESET] = GlassPreset.CUSTOM.name
                tintMode?.let { prefs[AppSettingsRepository.KEY_GLASS_TINT_MODE] = it.name }
                customTintArgb?.let { prefs[AppSettingsRepository.KEY_GLASS_CUSTOM_TINT_ARGB] = it }
                brightness?.let { prefs[AppSettingsRepository.KEY_GLASS_BRIGHTNESS] = it.coerceIn(-0.35f, 0.35f) }
                cornerRadiusDp?.let { prefs[AppSettingsRepository.KEY_GLASS_CORNER_RADIUS_DP] = it.coerceIn(8f, 36f) }
                borderEnabled?.let { prefs[AppSettingsRepository.KEY_GLASS_BORDER_ENABLED] = it }
                borderColorArgb?.let { prefs[AppSettingsRepository.KEY_GLASS_BORDER_COLOR_ARGB] = it }
                borderOpacity?.let { prefs[AppSettingsRepository.KEY_GLASS_BORDER_OPACITY] = it.coerceIn(0f, 0.85f) }
                borderThicknessDp?.let { prefs[AppSettingsRepository.KEY_GLASS_BORDER_THICKNESS_DP] = it.coerceIn(0.5f, 3.0f) }
                shadowElevationDp?.let { prefs[AppSettingsRepository.KEY_GLASS_SHADOW_DP] = it.coerceIn(0f, 24f) }
                noiseGrainAlpha?.let { prefs[AppSettingsRepository.KEY_GLASS_NOISE_ALPHA] = it.coerceIn(0f, 0.25f) }
                qualityLevel?.let { prefs[AppSettingsRepository.KEY_GLASS_QUALITY] = it.name }
            }
        }
    }

    // ---- 3. ASCII Background ----
    fun setAsciiEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAsciiEnabled(enabled) }
    }

    fun setAsciiAnimal(animalId: String) {
        viewModelScope.launch { settingsRepository.setAsciiAnimal(animalId) }
    }

    fun setAsciiDetail(value: Float) {
        viewModelScope.launch { settingsRepository.setAsciiDetail(value) }
    }

    fun setAsciiGrid(value: Float) {
        viewModelScope.launch { settingsRepository.setAsciiGrid(value) }
    }

    fun setAsciiHueShift(value: Float) {
        viewModelScope.launch { settingsRepository.setAsciiHueShift(value) }
    }

    fun setAsciiColorCycle(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAsciiColorCycle(enabled) }
    }

    fun setAsciiPerformanceMode(mode: AsciiPerformanceMode) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_ASCII_PERF_MODE] = mode.name
            }
            when (mode) {
                AsciiPerformanceMode.HIGH_QUALITY -> {
                    settingsRepository.setAsciiDetail(2.0f)
                    settingsRepository.setAsciiGrid(0.0f)
                }
                AsciiPerformanceMode.BATTERY_SAVER -> {
                    settingsRepository.setAsciiDetail(1.1f)
                    settingsRepository.setAsciiGrid(1.0f)
                }
                AsciiPerformanceMode.AUTO -> {
                    settingsRepository.setAsciiDetail(1.6f)
                    settingsRepository.setAsciiGrid(0.0f)
                }
            }
        }
    }

    // ---- 4. Home Screen ----
    fun moveHomeSection(section: HomeSectionId, direction: Int) {
        viewModelScope.launch {
            val current = appSettings.value.customization.home.sectionOrder.toMutableList()
            val idx = current.indexOf(section)
            if (idx < 0) return@launch
            val targetIdx = (idx + direction).coerceIn(0, current.lastIndex)
            if (idx == targetIdx) return@launch
            current.removeAt(idx)
            current.add(targetIdx, section)
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_HOME_SECTION_ORDER] = current.joinToString(",") { s -> s.name }
            }
        }
    }

    fun toggleHomeSectionVisible(section: HomeSectionId, visible: Boolean) {
        viewModelScope.launch {
            val hidden = appSettings.value.customization.home.hiddenSections.toMutableSet()
            if (visible) hidden.remove(section) else hidden.add(section)
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_HOME_HIDDEN_SECTIONS] = hidden.joinToString(",") { s -> s.name }
            }
        }
    }

    fun resetHomeSectionOrder() {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_HOME_SECTION_ORDER] =
                    HomeSectionId.DEFAULT_ORDER.joinToString(",") { s -> s.name }
            }
        }
    }

    fun setHomeLayoutMode(mode: HomeLayoutMode) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_HOME_LAYOUT_MODE] = mode.name
            }
        }
    }

    fun setHomeGridColumns(columns: Int) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_HOME_GRID_COLUMNS] = columns.coerceIn(2, 3)
            }
        }
    }

    fun setHomeArtworkSizeDp(dp: Int) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_HOME_ARTWORK_SIZE_DP] = dp.coerceIn(52, 104)
            }
        }
    }

    fun setHomeCornerRadiusDp(dp: Int) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_HOME_CORNER_RADIUS_DP] = dp.coerceIn(8, 36)
            }
        }
    }

    fun setHomeShowTitles(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_HOME_SHOW_TITLES] = show
            }
        }
    }

    fun setHomeShowMetadata(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_HOME_SHOW_METADATA] = show
            }
        }
    }

    fun setHomeShowGreeting(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_HOME_SHOW_GREETING] = show
            }
        }
    }

    // ---- 5. Navigation ----
    fun toggleNavTabVisible(tab: NavTabId, visible: Boolean) {
        viewModelScope.launch {
            val nav = appSettings.value.customization.navigation
            val order = nav.normalizedTabOrder
            val hidden = nav.hiddenTabs.toMutableSet()
            if (visible) {
                hidden.remove(tab)
            } else {
                hidden.add(tab)
                // Always keep at least one primary destination (Home or Library) enabled
                val remaining = order.filter { it !in hidden }
                if (remaining.none { it == NavTabId.HOME || it == NavTabId.LIBRARY }) {
                    hidden.remove(tab)
                    return@launch
                }
            }
            val safe = NavigationCustomizationConfig(tabOrder = order, hiddenTabs = hidden).safeVisibleTabs
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_NAV_TAB_ORDER] = order.joinToString(",") { t -> t.name }
                it[CustomizationPersistence.KEY_NAV_HIDDEN_TABS] = hidden.joinToString(",") { t -> t.name }
                it[CustomizationPersistence.KEY_NAV_VISIBLE_TABS] = safe.joinToString(",") { t -> t.name }
            }
        }
    }

    fun moveNavTab(tab: NavTabId, direction: Int) {
        viewModelScope.launch {
            val nav = appSettings.value.customization.navigation
            val order = nav.normalizedTabOrder.toMutableList()
            val idx = order.indexOf(tab)
            if (idx < 0) return@launch
            val targetIdx = (idx + direction).coerceIn(0, order.lastIndex)
            if (idx == targetIdx) return@launch
            order.removeAt(idx)
            order.add(targetIdx, tab)
            val safe = NavigationCustomizationConfig(tabOrder = order, hiddenTabs = nav.hiddenTabs).safeVisibleTabs
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_NAV_TAB_ORDER] = order.joinToString(",") { t -> t.name }
                it[CustomizationPersistence.KEY_NAV_VISIBLE_TABS] = safe.joinToString(",") { t -> t.name }
            }
        }
    }

    fun resetNavTabOrder() {
        viewModelScope.launch {
            val nav = appSettings.value.customization.navigation
            val order = NavTabId.DEFAULT_TABS
            val safe = NavigationCustomizationConfig(tabOrder = order, hiddenTabs = nav.hiddenTabs).safeVisibleTabs
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_NAV_TAB_ORDER] = order.joinToString(",") { t -> t.name }
                it[CustomizationPersistence.KEY_NAV_VISIBLE_TABS] = safe.joinToString(",") { t -> t.name }
            }
        }
    }

    fun setNavStyle(style: NavBarStyle) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_NAV_STYLE] = style.name
            }
        }
    }

    fun setNavShowLabels(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_NAV_SHOW_LABELS] = show
            }
        }
    }

    fun setNavFloating(floating: Boolean) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_NAV_FLOATING] = floating
            }
        }
    }

    fun setNavFloatingTopBar(floating: Boolean) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_NAV_FLOATING_TOP_BAR] = floating
            }
        }
    }

    fun setDefaultStartupTab(tab: String) {
        viewModelScope.launch { settingsRepository.setDefaultTab(tab) }
    }

    fun setNavInlineBehavior(behavior: FloatingTabBarInlineBehavior) {
        viewModelScope.launch { settingsRepository.setNavInlineBehavior(behavior) }
    }

    // ---- 6. Player Appearance ----
    fun setPlayerLayoutStyle(style: PlayerLayoutStyle) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_PLAYER_LAYOUT_STYLE] = style.name
            }
        }
    }

    fun setPlayerArtworkShape(shape: PlayerArtworkShape) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_PLAYER_ARTWORK_SHAPE] = shape.name
            }
        }
    }

    fun setPlayerArtworkScale(scale: Float) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_PLAYER_ARTWORK_SCALE] = scale.coerceIn(0.75f, 1.20f)
            }
        }
    }

    fun setPlayerArtworkCropMode(mode: PlayerArtworkCropMode) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_PLAYER_ARTWORK_CROP_MODE] = mode.name
            }
        }
    }

    fun setPlayerArtworkPresentation(presentation: PlayerArtworkPresentation) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_PLAYER_ARTWORK_PRESENTATION] = presentation.name
            }
        }
    }

    fun setPlayerArtworkOptions(
        transitions: Boolean? = null,
        animatedArtwork: Boolean? = null,
        disableOnMobileData: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsRepository.editCustomization { prefs ->
                transitions?.let { prefs[CustomizationPersistence.KEY_PLAYER_ARTWORK_TRANSITIONS] = it }
                animatedArtwork?.let { prefs[CustomizationPersistence.KEY_PLAYER_ANIMATED_ARTWORK] = it }
                disableOnMobileData?.let { prefs[CustomizationPersistence.KEY_PLAYER_DISABLE_ANIM_ARTWORK_CELLULAR] = it }
            }
        }
    }

    fun setPlayerBackgroundStyle(style: PlayerBackgroundStyle) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_PLAYER_BG_STYLE] = style.name
            }
        }
    }

    fun setPlayerProgressStyle(style: PlayerProgressStyle) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_PLAYER_PROGRESS_STYLE] = style.name
                it[CustomizationPersistence.KEY_SEEKBAR_STYLE] = style.name
            }
        }
    }

    fun setPlayerProgressHeightDp(dp: Float) {
        viewModelScope.launch {
            val clamped = dp.coerceIn(2f, 12f)
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_PLAYER_PROGRESS_HEIGHT] = clamped
                it[CustomizationPersistence.KEY_SEEKBAR_THICKNESS_DP] = clamped
            }
        }
    }

    fun setSeekbarThumbStyle(style: SeekbarThumbStyle) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_SEEKBAR_THUMB_STYLE] = style.name
            }
        }
    }

    fun setSeekbarGradientPreset(preset: SeekbarGradientPreset) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_SEEKBAR_GRADIENT_PRESET] = preset.name
            }
        }
    }

    fun setSeekbarNumeric(
        thumbSizeDp: Float? = null,
        thicknessDp: Float? = null,
        cornerRadiusDp: Float? = null,
        inactiveAlpha: Float? = null,
        waveAmplitudeDp: Float? = null,
        waveFrequency: Float? = null,
        animationIntensity: Float? = null,
        glowIntensity: Float? = null,
        waveSpeed: Float? = null,
        timeLabelScale: Float? = null,
        touchTargetHeightDp: Float? = null
    ) {
        viewModelScope.launch {
            settingsRepository.editCustomization { prefs ->
                thumbSizeDp?.let { prefs[CustomizationPersistence.KEY_SEEKBAR_THUMB_SIZE_DP] = it.coerceIn(6f, 24f) }
                thicknessDp?.let {
                    val v = it.coerceIn(2f, 12f)
                    prefs[CustomizationPersistence.KEY_SEEKBAR_THICKNESS_DP] = v
                    prefs[CustomizationPersistence.KEY_PLAYER_PROGRESS_HEIGHT] = v
                }
                cornerRadiusDp?.let { prefs[CustomizationPersistence.KEY_SEEKBAR_CORNER_RADIUS_DP] = it.coerceIn(0f, 12f) }
                inactiveAlpha?.let { prefs[CustomizationPersistence.KEY_SEEKBAR_INACTIVE_ALPHA] = it.coerceIn(0.08f, 0.60f) }
                waveAmplitudeDp?.let { prefs[CustomizationPersistence.KEY_SEEKBAR_WAVE_AMP] = it.coerceIn(1f, 10f) }
                waveFrequency?.let { prefs[CustomizationPersistence.KEY_SEEKBAR_WAVE_FREQ] = it.coerceIn(0.5f, 2.0f) }
                animationIntensity?.let { prefs[CustomizationPersistence.KEY_SEEKBAR_ANIM_INTENSITY] = it.coerceIn(0.3f, 1.8f) }
                glowIntensity?.let { prefs[CustomizationPersistence.KEY_SEEKBAR_GLOW_INTENSITY] = it.coerceIn(0.1f, 1.0f) }
                waveSpeed?.let { prefs[CustomizationPersistence.KEY_SEEKBAR_WAVE_SPEED] = it.coerceIn(0.4f, 2.2f) }
                timeLabelScale?.let { prefs[CustomizationPersistence.KEY_SEEKBAR_TIME_SCALE] = it.coerceIn(0.85f, 1.25f) }
                touchTargetHeightDp?.let { prefs[CustomizationPersistence.KEY_SEEKBAR_TOUCH_HEIGHT] = it.coerceIn(28f, 56f) }
            }
        }
    }

    fun setSeekbarColors(
        useCustomActive: Boolean? = null,
        customActiveArgb: Long? = null,
        useCustomThumb: Boolean? = null,
        customThumbArgb: Long? = null
    ) {
        viewModelScope.launch {
            settingsRepository.editCustomization { prefs ->
                useCustomActive?.let { prefs[CustomizationPersistence.KEY_SEEKBAR_USE_CUSTOM_ACTIVE] = it }
                customActiveArgb?.let {
                    prefs[CustomizationPersistence.KEY_SEEKBAR_USE_CUSTOM_ACTIVE] = true
                    prefs[CustomizationPersistence.KEY_SEEKBAR_CUSTOM_ACTIVE_ARGB] = it
                }
                useCustomThumb?.let { prefs[CustomizationPersistence.KEY_SEEKBAR_USE_CUSTOM_THUMB] = it }
                customThumbArgb?.let {
                    prefs[CustomizationPersistence.KEY_SEEKBAR_USE_CUSTOM_THUMB] = true
                    prefs[CustomizationPersistence.KEY_SEEKBAR_CUSTOM_THUMB_ARGB] = it
                }
            }
        }
    }

    fun setSeekbarToggles(
        gradientEnabled: Boolean? = null,
        animationsEnabled: Boolean? = null,
        waveAnimationEnabled: Boolean? = null,
        hapticFeedback: Boolean? = null,
        showElapsedTime: Boolean? = null,
        showRemainingTime: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsRepository.editCustomization { prefs ->
                gradientEnabled?.let { prefs[CustomizationPersistence.KEY_SEEKBAR_GRADIENT_ENABLED] = it }
                animationsEnabled?.let { prefs[CustomizationPersistence.KEY_SEEKBAR_ANIM_ENABLED] = it }
                waveAnimationEnabled?.let { prefs[CustomizationPersistence.KEY_SEEKBAR_WAVE_ANIM] = it }
                hapticFeedback?.let { prefs[CustomizationPersistence.KEY_SEEKBAR_HAPTICS] = it }
                showElapsedTime?.let { prefs[CustomizationPersistence.KEY_SEEKBAR_SHOW_ELAPSED] = it }
                showRemainingTime?.let {
                    prefs[CustomizationPersistence.KEY_SEEKBAR_SHOW_REMAINING] = it
                    prefs[CustomizationPersistence.KEY_PLAYER_SHOW_REMAINING] = it
                }
            }
        }
    }

    fun setPlayerRoundedPlayButton(rounded: Boolean) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_PLAYER_ROUNDED_PLAY] = rounded
            }
        }
    }

    fun setPlayerControlVisibility(
        showDownload: Boolean? = null,
        showSleepTimer: Boolean? = null,
        showLike: Boolean? = null,
        showLyrics: Boolean? = null,
        showUpNext: Boolean? = null,
        showShuffle: Boolean? = null,
        showRepeat: Boolean? = null,
        showQueue: Boolean? = null,
        compactControls: Boolean? = null,
        immersivePlayer: Boolean? = null,
        showAlbumMetadata: Boolean? = null,
        showRemainingTime: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsRepository.editCustomization { prefs ->
                showDownload?.let { prefs[CustomizationPersistence.KEY_PLAYER_SHOW_DOWNLOAD] = it }
                showSleepTimer?.let { prefs[CustomizationPersistence.KEY_PLAYER_SHOW_SLEEP] = it }
                showLike?.let { prefs[CustomizationPersistence.KEY_PLAYER_SHOW_LIKE] = it }
                showLyrics?.let { prefs[CustomizationPersistence.KEY_PLAYER_SHOW_LYRICS] = it }
                showUpNext?.let { prefs[CustomizationPersistence.KEY_PLAYER_SHOW_UP_NEXT] = it }
                showShuffle?.let { prefs[CustomizationPersistence.KEY_PLAYER_SHOW_SHUFFLE] = it }
                showRepeat?.let { prefs[CustomizationPersistence.KEY_PLAYER_SHOW_REPEAT] = it }
                showQueue?.let { prefs[CustomizationPersistence.KEY_PLAYER_SHOW_QUEUE] = it }
                compactControls?.let { prefs[CustomizationPersistence.KEY_PLAYER_COMPACT_CONTROLS] = it }
                immersivePlayer?.let { prefs[CustomizationPersistence.KEY_PLAYER_IMMERSIVE] = it }
                showAlbumMetadata?.let { prefs[CustomizationPersistence.KEY_PLAYER_SHOW_ALBUM_META] = it }
                showRemainingTime?.let { prefs[CustomizationPersistence.KEY_PLAYER_SHOW_REMAINING] = it }
            }
        }
    }

    // ---- 7. Mini Player ----
    fun setMiniPlayerStyle(style: MiniPlayerStyle) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_MINI_STYLE] = style.name
            }
        }
    }

    fun setMiniPlayerBackgroundStyle(style: MiniPlayerBackgroundStyle) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_MINI_BG_STYLE] = style.name
            }
        }
    }

    fun setMiniPlayerArtworkShape(shape: MiniPlayerArtworkShape) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_MINI_ARTWORK_SHAPE] = shape.name
            }
        }
    }

    fun setMiniPlayerArtworkSizeDp(dp: Int) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_MINI_ARTWORK_SIZE_DP] = dp.coerceIn(32, 56)
            }
        }
    }

    fun setMiniPlayerCornerRadiusDp(dp: Int) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_MINI_CORNER_RADIUS_DP] = dp.coerceIn(12, 36)
            }
        }
    }

    fun setMiniPlayerHeightDp(dp: Int) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_MINI_HEIGHT_DP] = dp.coerceIn(52, 76)
            }
        }
    }

    fun setMiniPlayerHorizontalPaddingDp(dp: Int) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_MINI_H_PADDING_DP] = dp.coerceIn(4, 20)
            }
        }
    }

    fun setMiniPlayerSurfaceAlpha(alpha: Float) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_MINI_SURFACE_ALPHA] = alpha.coerceIn(0.30f, 1.0f)
            }
        }
    }

    fun setMiniPlayerElevationDp(dp: Int) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_MINI_ELEVATION_DP] = dp.coerceIn(0, 16)
            }
        }
    }

    fun setMiniPlayerToggles(
        showArtwork: Boolean? = null,
        showPrevious: Boolean? = null,
        showPlayPause: Boolean? = null,
        showNext: Boolean? = null,
        showLike: Boolean? = null,
        showShuffle: Boolean? = null,
        showQueue: Boolean? = null,
        showProgress: Boolean? = null,
        glassEffect: Boolean? = null,
        expressiveCookieArtwork: Boolean? = null,
        expressiveShapeMotion: Boolean? = null,
        expressiveProgressRing: Boolean? = null,
        expressiveArtworkPlaybackTap: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsRepository.editCustomization { prefs ->
                showArtwork?.let { prefs[CustomizationPersistence.KEY_MINI_SHOW_ARTWORK] = it }
                showPrevious?.let { prefs[CustomizationPersistence.KEY_MINI_SHOW_PREV] = it }
                showPlayPause?.let { prefs[CustomizationPersistence.KEY_MINI_SHOW_PLAY_PAUSE] = it }
                showNext?.let { prefs[CustomizationPersistence.KEY_MINI_SHOW_NEXT] = it }
                showLike?.let { prefs[CustomizationPersistence.KEY_MINI_SHOW_LIKE] = it }
                showShuffle?.let { prefs[CustomizationPersistence.KEY_MINI_SHOW_SHUFFLE] = it }
                showQueue?.let { prefs[CustomizationPersistence.KEY_MINI_SHOW_QUEUE] = it }
                showProgress?.let { prefs[CustomizationPersistence.KEY_MINI_SHOW_PROGRESS] = it }
                glassEffect?.let { prefs[CustomizationPersistence.KEY_MINI_GLASS_EFFECT] = it }
                expressiveCookieArtwork?.let { prefs[CustomizationPersistence.KEY_MINI_EXPRESSIVE_COOKIE] = it }
                expressiveShapeMotion?.let { prefs[CustomizationPersistence.KEY_MINI_EXPRESSIVE_MOTION] = it }
                expressiveProgressRing?.let { prefs[CustomizationPersistence.KEY_MINI_EXPRESSIVE_RING] = it }
                expressiveArtworkPlaybackTap?.let { prefs[CustomizationPersistence.KEY_MINI_EXPRESSIVE_ART_PLAY] = it }
            }
        }
    }

    // ---- 8. Gestures ----
    fun setGestureToggles(
        swipeArtworkToSkip: Boolean? = null,
        swipeDownToCollapse: Boolean? = null,
        swipeToDismissMini: Boolean? = null,
        swipeToRemoveQueue: Boolean? = null,
        haptics: Boolean? = null,
        sensitivity: Float? = null,
        artworkDoubleTapSkipEnabled: Boolean? = null,
        artworkSkipDurationSeconds: Int? = null,
        artworkProgressiveSkipEnabled: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsRepository.editCustomization { prefs ->
                swipeArtworkToSkip?.let { prefs[CustomizationPersistence.KEY_GESTURE_SWIPE_ARTWORK] = it }
                swipeDownToCollapse?.let { prefs[CustomizationPersistence.KEY_GESTURE_SWIPE_COLLAPSE] = it }
                swipeToDismissMini?.let { prefs[CustomizationPersistence.KEY_GESTURE_DISMISS_MINI] = it }
                swipeToRemoveQueue?.let { prefs[CustomizationPersistence.KEY_GESTURE_REMOVE_QUEUE] = it }
                haptics?.let { prefs[CustomizationPersistence.KEY_GESTURE_HAPTICS] = it }
                sensitivity?.let { prefs[CustomizationPersistence.KEY_GESTURE_SENSITIVITY] = it.coerceIn(0.6f, 1.5f) }
                artworkDoubleTapSkipEnabled?.let { prefs[CustomizationPersistence.KEY_GESTURE_ARTWORK_DOUBLE_TAP_SKIP] = it }
                artworkSkipDurationSeconds?.let {
                    prefs[CustomizationPersistence.KEY_GESTURE_ARTWORK_SKIP_DURATION_SEC] =
                        GestureCustomizationConfig.normalizeSkipDurationSeconds(it)
                }
                artworkProgressiveSkipEnabled?.let { prefs[CustomizationPersistence.KEY_GESTURE_ARTWORK_PROGRESSIVE_SKIP] = it }
            }
        }
    }

    // ---- 9. Typography & Density ----
    fun setFontChoice(choice: AppFontChoice) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_TYPO_FONT_CHOICE] = choice.name
            }
        }
    }

    fun setFontScale(scale: Float) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_TYPO_FONT_SCALE] = scale.coerceIn(0.85f, 1.25f)
            }
        }
    }

    fun setTitleScale(scale: Float) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_TYPO_TITLE_SCALE] = scale.coerceIn(0.85f, 1.25f)
            }
        }
    }

    fun setSubtitleScale(scale: Float) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_TYPO_SUBTITLE_SCALE] = scale.coerceIn(0.85f, 1.25f)
            }
        }
    }

    fun setDensityMode(mode: UiDensityMode) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_TYPO_DENSITY_MODE] = mode.name
                it[CustomizationPersistence.KEY_TYPO_DISPLAY_DENSITY] = DisplayDensityScale.fromLegacy(mode).name
            }
        }
    }

    fun setDisplayDensity(scale: DisplayDensityScale) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_TYPO_DISPLAY_DENSITY] = scale.name
                val legacy = when {
                    scale.scaleFactor < 0.95f -> UiDensityMode.COMPACT
                    scale.scaleFactor > 1.06f -> UiDensityMode.SPACIOUS
                    else -> UiDensityMode.COMFORTABLE
                }
                it[CustomizationPersistence.KEY_TYPO_DENSITY_MODE] = legacy.name
            }
        }
    }

    fun setLandscapeScaling(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_TYPO_LANDSCAPE_SCALING] = enabled
            }
        }
    }

    // ---- 10. Lyrics Appearance ----
    fun setLyricsFontSizeSp(sizeSp: Float) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_LYRICS_FONT_SIZE_SP] = sizeSp.coerceIn(14f, 34f)
            }
        }
    }

    fun setLyricsLineSpacing(multiplier: Float) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_LYRICS_LINE_SPACING] = multiplier.coerceIn(1.1f, 2.2f)
            }
        }
    }

    fun setLyricsSubtitleFontSizeSp(sizeSp: Float) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_LYRICS_SUBTITLE_FONT_SIZE_SP] = sizeSp.coerceIn(10f, 28f)
            }
        }
    }

    fun setLyricsAlignment(alignment: LyricsTextAlignment) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_LYRICS_ALIGNMENT] = alignment.name
            }
        }
    }

    fun setLyricsActiveAlignment(alignment: LyricsTextAlignment) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_LYRICS_ACTIVE_ALIGNMENT] = alignment.name
            }
        }
    }

    fun setLyricsAnimationStyle(style: LyricsAnimationStyle) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_LYRICS_ANIMATION_STYLE] = style.name
            }
        }
    }

    fun setLyricsGlobalOffsetMs(offsetMs: Long) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_LYRICS_GLOBAL_OFFSET_MS] = offsetMs.coerceIn(-5_000L, 5_000L)
            }
        }
    }

    fun setLyricsToggles(
        showOriginal: Boolean? = null,
        showRomanization: Boolean? = null,
        showTranslation: Boolean? = null,
        wordHighlight: Boolean? = null,
        accentColor: Boolean? = null,
        neutralColor: Boolean? = null,
        respectProviderPositioning: Boolean? = null,
        autoScroll: Boolean? = null,
        tapToSeek: Boolean? = null,
        preferWordSynced: Boolean? = null,
        activeByRealTiming: Boolean? = null,
        showLyricsOnPlayer: Boolean? = null,
        hideStatusBarInFullscreen: Boolean? = null,
        progressiveBlur: Boolean? = null,
        plusLighterBlend: Boolean? = null,
        fullscreenDefault: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsRepository.editCustomization { prefs ->
                showOriginal?.let { prefs[CustomizationPersistence.KEY_LYRICS_SHOW_ORIGINAL] = it }
                showRomanization?.let { prefs[CustomizationPersistence.KEY_LYRICS_SHOW_ROMANIZATION] = it }
                showTranslation?.let { prefs[CustomizationPersistence.KEY_LYRICS_SHOW_TRANSLATION] = it }
                wordHighlight?.let { prefs[CustomizationPersistence.KEY_LYRICS_WORD_HIGHLIGHT] = it }
                accentColor?.let { prefs[CustomizationPersistence.KEY_LYRICS_ACCENT_COLOR] = it }
                neutralColor?.let { prefs[CustomizationPersistence.KEY_LYRICS_NEUTRAL_COLOR] = it }
                respectProviderPositioning?.let { prefs[CustomizationPersistence.KEY_LYRICS_RESPECT_PROVIDER_POS] = it }
                autoScroll?.let { prefs[CustomizationPersistence.KEY_LYRICS_AUTO_SCROLL] = it }
                tapToSeek?.let { prefs[CustomizationPersistence.KEY_LYRICS_TAP_TO_SEEK] = it }
                preferWordSynced?.let { prefs[CustomizationPersistence.KEY_LYRICS_PREFER_WORD_SYNC] = it }
                activeByRealTiming?.let { prefs[CustomizationPersistence.KEY_LYRICS_REAL_TIMING] = it }
                showLyricsOnPlayer?.let { prefs[CustomizationPersistence.KEY_LYRICS_SHOW_ON_PLAYER] = it }
                hideStatusBarInFullscreen?.let { prefs[CustomizationPersistence.KEY_LYRICS_HIDE_STATUS_BAR] = it }
                progressiveBlur?.let { prefs[CustomizationPersistence.KEY_LYRICS_PROGRESSIVE_BLUR] = it }
                plusLighterBlend?.let { prefs[CustomizationPersistence.KEY_LYRICS_PLUS_LIGHTER] = it }
                fullscreenDefault?.let { prefs[CustomizationPersistence.KEY_LYRICS_FULLSCREEN_DEFAULT] = it }
            }
        }
    }

    // ---- 11. Playback & Audio Preferences ----
    fun setAudioQuality(quality: AudioQualityPreference) {
        viewModelScope.launch { settingsRepository.setAudioQuality(quality) }
    }

    fun setCrossfadeSeconds(seconds: Int) {
        audioController.update { it.copy(crossfadeSeconds = seconds.coerceIn(0, 12)) }
    }

    fun setSkipSilence(enabled: Boolean) {
        audioController.update { it.copy(skipSilence = enabled) }
    }

    fun setAudioNormalization(enabled: Boolean) {
        audioController.update { it.copy(normalize = enabled) }
    }

    fun setBassBoostEnabled(enabled: Boolean) {
        audioController.update { current ->
            if (enabled) {
                val bassGains = EqPresets.presets["Bass boost"] ?: listOf(6f, 4f, 0f, 0f, 0f)
                current.copy(eqEnabled = true, preset = "Bass boost", bandGainsDb = bassGains)
            } else {
                current.copy(eqEnabled = false, preset = EqPresets.FLAT, bandGainsDb = EqPresets.zeros())
            }
        }
    }

    fun setPersistentQueue(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPersistentQueue(enabled) }
    }

    fun setAutoplayRelated(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoplayRelated(enabled) }
    }

    fun setRememberShuffleRepeat(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setRememberShuffleRepeat(enabled) }
    }

    fun setAutoSkipOnError(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoSkipOnError(enabled) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setKeepScreenOn(enabled) }
    }

    fun setStopOnTaskRemoved(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setStopPlaybackOnTaskRemoved(enabled) }
    }

    // ---- 12. Widgets ----
    fun setWidgetBackgroundStyle(style: WidgetBackgroundStyle) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_WIDGET_BG_STYLE] = style.name
            }
            GeekifyMusicWidgetProvider.refreshAll(context)
        }
    }

    fun setWidgetToggles(
        showArtwork: Boolean? = null,
        showPrevNext: Boolean? = null,
        showArtist: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsRepository.editCustomization { prefs ->
                showArtwork?.let { prefs[CustomizationPersistence.KEY_WIDGET_SHOW_ARTWORK] = it }
                showPrevNext?.let { prefs[CustomizationPersistence.KEY_WIDGET_SHOW_PREV_NEXT] = it }
                showArtist?.let { prefs[CustomizationPersistence.KEY_WIDGET_SHOW_ARTIST] = it }
            }
            GeekifyMusicWidgetProvider.refreshAll(context)
        }
    }

    // ---- Reset & Defaults ----
    fun resetCategory(category: CustomizationCategory) {
        viewModelScope.launch {
            settingsRepository.resetCustomizationCategory(category)
            if (category == CustomizationCategory.PLAYBACK_PREFERENCES) {
                audioController.update { AudioSettings() }
            } else if (category == CustomizationCategory.WIDGETS) {
                GeekifyMusicWidgetProvider.refreshAll(context)
            }
            _toastMessage.value = "Reset ${category.title} to default settings"
        }
    }

    fun resetAllCustomization() {
        viewModelScope.launch {
            settingsRepository.resetAllCustomization()
            audioController.update { AudioSettings() }
            GeekifyMusicWidgetProvider.refreshAll(context)
            _toastMessage.value = "Restored all personalization settings to Geekify defaults"
        }
    }
}
