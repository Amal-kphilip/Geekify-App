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
    fun setThemeVariant(variant: DarkThemeVariant) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_THEME_VARIANT] = variant.name
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
            val current = appSettings.value.customization.navigation.visibleTabs.toMutableList()
            if (visible) {
                if (tab !in current) current.add(tab)
            } else {
                current.remove(tab)
            }
            val safe = NavigationCustomizationConfig(visibleTabs = current).safeVisibleTabs
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_NAV_VISIBLE_TABS] = safe.joinToString(",") { t -> t.name }
            }
        }
    }

    fun moveNavTab(tab: NavTabId, direction: Int) {
        viewModelScope.launch {
            val current = appSettings.value.customization.navigation.safeVisibleTabs.toMutableList()
            val idx = current.indexOf(tab)
            if (idx < 0) return@launch
            val targetIdx = (idx + direction).coerceIn(0, current.lastIndex)
            if (idx == targetIdx) return@launch
            current.removeAt(idx)
            current.add(targetIdx, tab)
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_NAV_VISIBLE_TABS] = current.joinToString(",") { t -> t.name }
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
        compactControls: Boolean? = null
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

    fun setMiniPlayerArtworkShape(shape: MiniPlayerArtworkShape) {
        viewModelScope.launch {
            settingsRepository.editCustomization {
                it[CustomizationPersistence.KEY_MINI_ARTWORK_SHAPE] = shape.name
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

    fun setMiniPlayerToggles(
        showArtwork: Boolean? = null,
        showPrevious: Boolean? = null,
        showNext: Boolean? = null,
        showLike: Boolean? = null,
        showShuffle: Boolean? = null,
        showProgress: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsRepository.editCustomization { prefs ->
                showArtwork?.let { prefs[CustomizationPersistence.KEY_MINI_SHOW_ARTWORK] = it }
                showPrevious?.let { prefs[CustomizationPersistence.KEY_MINI_SHOW_PREV] = it }
                showNext?.let { prefs[CustomizationPersistence.KEY_MINI_SHOW_NEXT] = it }
                showLike?.let { prefs[CustomizationPersistence.KEY_MINI_SHOW_LIKE] = it }
                showShuffle?.let { prefs[CustomizationPersistence.KEY_MINI_SHOW_SHUFFLE] = it }
                showProgress?.let { prefs[CustomizationPersistence.KEY_MINI_SHOW_PROGRESS] = it }
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
        sensitivity: Float? = null
    ) {
        viewModelScope.launch {
            settingsRepository.editCustomization { prefs ->
                swipeArtworkToSkip?.let { prefs[CustomizationPersistence.KEY_GESTURE_SWIPE_ARTWORK] = it }
                swipeDownToCollapse?.let { prefs[CustomizationPersistence.KEY_GESTURE_SWIPE_COLLAPSE] = it }
                swipeToDismissMini?.let { prefs[CustomizationPersistence.KEY_GESTURE_DISMISS_MINI] = it }
                swipeToRemoveQueue?.let { prefs[CustomizationPersistence.KEY_GESTURE_REMOVE_QUEUE] = it }
                haptics?.let { prefs[CustomizationPersistence.KEY_GESTURE_HAPTICS] = it }
                sensitivity?.let { prefs[CustomizationPersistence.KEY_GESTURE_SENSITIVITY] = it.coerceIn(0.6f, 1.5f) }
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
                it[CustomizationPersistence.KEY_LYRICS_LINE_SPACING] = multiplier.coerceIn(1.1f, 1.8f)
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

    fun setLyricsToggles(
        showRomanization: Boolean? = null,
        showTranslation: Boolean? = null,
        wordHighlight: Boolean? = null,
        accentColor: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsRepository.editCustomization { prefs ->
                showRomanization?.let { prefs[CustomizationPersistence.KEY_LYRICS_SHOW_ROMANIZATION] = it }
                showTranslation?.let { prefs[CustomizationPersistence.KEY_LYRICS_SHOW_TRANSLATION] = it }
                wordHighlight?.let { prefs[CustomizationPersistence.KEY_LYRICS_WORD_HIGHLIGHT] = it }
                accentColor?.let { prefs[CustomizationPersistence.KEY_LYRICS_ACCENT_COLOR] = it }
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
