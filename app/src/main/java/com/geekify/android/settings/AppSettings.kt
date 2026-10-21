package com.geekify.android.settings

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.geekify.android.ui.navigation.FloatingTabBarInlineBehavior
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

enum class AudioQualityPreference(val label: String, val subtitle: String) {
    AUTO("Automatic", "Adapts to Wi-Fi and mobile data"),
    HIGH("High (Best)", "Always stream highest available bitrate"),
    LOW("Data Saver", "Lower bitrate to save mobile data")
}

@Immutable
data class GlassConfig(
    val enabled: Boolean = true,
    val blurDp: Float = 3f,
    val saturation: Float = 1.8f,
    val lensAmountDp: Float = 12f,
    val lensHeightDp: Float = 14f,
    val chromaticAberration: Boolean = false,
    val depthEffect: Boolean = false,
    val tintAlpha: Float = 0.12f,
    val miniPlayerGlass: Boolean = true,
    val navBarGlass: Boolean = true
)

@Immutable
data class AppSettingsState(
    // Playback
    val audioQuality: AudioQualityPreference = AudioQualityPreference.AUTO,
    val persistentQueue: Boolean = true,
    val autoplayRelated: Boolean = true,
    val rememberShuffleRepeat: Boolean = true,
    val autoSkipOnError: Boolean = true,
    val keepScreenOn: Boolean = false,

    // Liquid Glass & Appearance
    val glass: GlassConfig = GlassConfig(),
    val navInlineBehavior: FloatingTabBarInlineBehavior = FloatingTabBarInlineBehavior.OnScrollDown,
    val defaultTab: String = "home",

    // Content & Region
    val contentCountry: String = "IN",
    val contentLanguage: String = "en",
    val hideExplicit: Boolean = false,

    // Privacy
    val pauseListenHistory: Boolean = false,
    val disableScreenshots: Boolean = false
)

val LocalGlassConfig = staticCompositionLocalOf { GlassConfig() }
val LocalAppSettings = staticCompositionLocalOf { AppSettingsState() }

@Singleton
class AppSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        val KEY_AUDIO_QUALITY = stringPreferencesKey("settings_audio_quality")
        val KEY_PERSISTENT_QUEUE = booleanPreferencesKey("settings_persistent_queue")
        val KEY_AUTOPLAY_RELATED = booleanPreferencesKey("settings_autoplay_related")
        val KEY_REMEMBER_SHUFFLE_REPEAT = booleanPreferencesKey("settings_remember_shuffle_repeat")
        val KEY_AUTO_SKIP_ON_ERROR = booleanPreferencesKey("settings_auto_skip_on_error")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("settings_keep_screen_on")

        val KEY_GLASS_ENABLED = booleanPreferencesKey("settings_glass_enabled")
        val KEY_GLASS_BLUR_DP = floatPreferencesKey("settings_glass_blur_dp")
        val KEY_GLASS_SATURATION = floatPreferencesKey("settings_glass_saturation")
        val KEY_GLASS_LENS_AMOUNT_DP = floatPreferencesKey("settings_glass_lens_amount_dp")
        val KEY_GLASS_LENS_HEIGHT_DP = floatPreferencesKey("settings_glass_lens_height_dp")
        val KEY_GLASS_CHROMATIC = booleanPreferencesKey("settings_glass_chromatic")
        val KEY_GLASS_DEPTH = booleanPreferencesKey("settings_glass_depth")
        val KEY_GLASS_TINT_ALPHA = floatPreferencesKey("settings_glass_tint_alpha")
        val KEY_GLASS_MINI_PLAYER = booleanPreferencesKey("settings_glass_mini_player")
        val KEY_GLASS_NAV_BAR = booleanPreferencesKey("settings_glass_nav_bar")

        val KEY_NAV_INLINE_BEHAVIOR = stringPreferencesKey("settings_nav_inline_behavior")
        val KEY_DEFAULT_TAB = stringPreferencesKey("settings_default_tab")

        val KEY_CONTENT_COUNTRY = stringPreferencesKey("settings_content_country")
        val KEY_CONTENT_LANGUAGE = stringPreferencesKey("settings_content_language")
        val KEY_HIDE_EXPLICIT = booleanPreferencesKey("settings_hide_explicit")

        val KEY_PAUSE_LISTEN_HISTORY = booleanPreferencesKey("settings_pause_listen_history")
        val KEY_DISABLE_SCREENSHOTS = booleanPreferencesKey("settings_disable_screenshots")

        private val PLAYER_KEY_QUEUE = stringPreferencesKey("player_queue")
        private val PLAYER_KEY_INDEX = intPreferencesKey("player_index")
        private val PLAYER_KEY_SHUFFLE = stringPreferencesKey("player_shuffle")
        private val PLAYER_KEY_REPEAT = stringPreferencesKey("player_repeat")
        private val PLAYER_KEY_PROGRESS = longPreferencesKey("player_progress_ms")
        private val PLAYER_KEY_REASONS = stringPreferencesKey("player_recommendation_reasons")

        fun fromPreferences(prefs: Preferences): AppSettingsState {
            val quality = prefs[KEY_AUDIO_QUALITY]?.let {
                runCatching { AudioQualityPreference.valueOf(it) }.getOrNull()
            } ?: AudioQualityPreference.AUTO

            val inlineBehavior = prefs[KEY_NAV_INLINE_BEHAVIOR]?.let {
                runCatching { FloatingTabBarInlineBehavior.valueOf(it) }.getOrNull()
            } ?: FloatingTabBarInlineBehavior.OnScrollDown

            return AppSettingsState(
                audioQuality = quality,
                persistentQueue = prefs[KEY_PERSISTENT_QUEUE] ?: true,
                autoplayRelated = prefs[KEY_AUTOPLAY_RELATED] ?: true,
                rememberShuffleRepeat = prefs[KEY_REMEMBER_SHUFFLE_REPEAT] ?: true,
                autoSkipOnError = prefs[KEY_AUTO_SKIP_ON_ERROR] ?: true,
                keepScreenOn = prefs[KEY_KEEP_SCREEN_ON] ?: false,
                glass = GlassConfig(
                    enabled = prefs[KEY_GLASS_ENABLED] ?: true,
                    blurDp = (prefs[KEY_GLASS_BLUR_DP] ?: 3f).coerceIn(0f, 25f),
                    saturation = (prefs[KEY_GLASS_SATURATION] ?: 1.8f).coerceIn(0.5f, 2.5f),
                    lensAmountDp = (prefs[KEY_GLASS_LENS_AMOUNT_DP] ?: 12f).coerceIn(0f, 32f),
                    lensHeightDp = (prefs[KEY_GLASS_LENS_HEIGHT_DP] ?: 14f).coerceIn(0f, 32f),
                    chromaticAberration = prefs[KEY_GLASS_CHROMATIC] ?: false,
                    depthEffect = prefs[KEY_GLASS_DEPTH] ?: false,
                    tintAlpha = (prefs[KEY_GLASS_TINT_ALPHA] ?: 0.12f).coerceIn(0f, 0.60f),
                    miniPlayerGlass = prefs[KEY_GLASS_MINI_PLAYER] ?: true,
                    navBarGlass = prefs[KEY_GLASS_NAV_BAR] ?: true
                ),
                navInlineBehavior = inlineBehavior,
                defaultTab = prefs[KEY_DEFAULT_TAB] ?: "home",
                contentCountry = prefs[KEY_CONTENT_COUNTRY] ?: "IN",
                contentLanguage = prefs[KEY_CONTENT_LANGUAGE] ?: "en",
                hideExplicit = prefs[KEY_HIDE_EXPLICIT] ?: false,
                pauseListenHistory = prefs[KEY_PAUSE_LISTEN_HISTORY] ?: false,
                disableScreenshots = prefs[KEY_DISABLE_SCREENSHOTS] ?: false
            )
        }
    }

    val state: StateFlow<AppSettingsState> = dataStore.data
        .map { fromPreferences(it) }
        .stateIn(scope, SharingStarted.Eagerly, AppSettingsState())

    suspend fun current(): AppSettingsState = fromPreferences(dataStore.data.first())

    suspend fun setAudioQuality(value: AudioQualityPreference) {
        dataStore.edit { it[KEY_AUDIO_QUALITY] = value.name }
    }

    suspend fun setPersistentQueue(enabled: Boolean) {
        dataStore.edit {
            it[KEY_PERSISTENT_QUEUE] = enabled
            if (!enabled) {
                it.remove(PLAYER_KEY_QUEUE)
                it.remove(PLAYER_KEY_INDEX)
                it.remove(PLAYER_KEY_PROGRESS)
                it.remove(PLAYER_KEY_REASONS)
            }
        }
    }

    suspend fun setAutoplayRelated(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTOPLAY_RELATED] = enabled }
    }

    suspend fun setRememberShuffleRepeat(enabled: Boolean) {
        dataStore.edit {
            it[KEY_REMEMBER_SHUFFLE_REPEAT] = enabled
            if (!enabled) {
                it.remove(PLAYER_KEY_SHUFFLE)
                it.remove(PLAYER_KEY_REPEAT)
            }
        }
    }

    suspend fun setAutoSkipOnError(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_SKIP_ON_ERROR] = enabled }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        dataStore.edit { it[KEY_KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setGlassEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_GLASS_ENABLED] = enabled }
    }

    suspend fun setGlassBlurDp(value: Float) {
        dataStore.edit { it[KEY_GLASS_BLUR_DP] = value.coerceIn(0f, 25f) }
    }

    suspend fun setGlassSaturation(value: Float) {
        dataStore.edit { it[KEY_GLASS_SATURATION] = value.coerceIn(0.5f, 2.5f) }
    }

    suspend fun setGlassLensAmountDp(value: Float) {
        dataStore.edit { it[KEY_GLASS_LENS_AMOUNT_DP] = value.coerceIn(0f, 32f) }
    }

    suspend fun setGlassLensHeightDp(value: Float) {
        dataStore.edit { it[KEY_GLASS_LENS_HEIGHT_DP] = value.coerceIn(0f, 32f) }
    }

    suspend fun setGlassChromaticAberration(enabled: Boolean) {
        dataStore.edit { it[KEY_GLASS_CHROMATIC] = enabled }
    }

    suspend fun setGlassDepthEffect(enabled: Boolean) {
        dataStore.edit { it[KEY_GLASS_DEPTH] = enabled }
    }

    suspend fun setGlassTintAlpha(value: Float) {
        dataStore.edit { it[KEY_GLASS_TINT_ALPHA] = value.coerceIn(0f, 0.60f) }
    }

    suspend fun setGlassMiniPlayer(enabled: Boolean) {
        dataStore.edit { it[KEY_GLASS_MINI_PLAYER] = enabled }
    }

    suspend fun setGlassNavBar(enabled: Boolean) {
        dataStore.edit { it[KEY_GLASS_NAV_BAR] = enabled }
    }

    suspend fun resetGlassDefaults() {
        dataStore.edit {
            it[KEY_GLASS_ENABLED] = true
            it[KEY_GLASS_BLUR_DP] = 3f
            it[KEY_GLASS_SATURATION] = 1.8f
            it[KEY_GLASS_LENS_AMOUNT_DP] = 12f
            it[KEY_GLASS_LENS_HEIGHT_DP] = 14f
            it[KEY_GLASS_CHROMATIC] = false
            it[KEY_GLASS_DEPTH] = false
            it[KEY_GLASS_TINT_ALPHA] = 0.12f
            it[KEY_GLASS_MINI_PLAYER] = true
            it[KEY_GLASS_NAV_BAR] = true
        }
    }

    suspend fun setNavInlineBehavior(behavior: FloatingTabBarInlineBehavior) {
        dataStore.edit { it[KEY_NAV_INLINE_BEHAVIOR] = behavior.name }
    }

    suspend fun setDefaultTab(tab: String) {
        dataStore.edit { it[KEY_DEFAULT_TAB] = tab }
    }

    suspend fun setContentCountry(countryCode: String) {
        dataStore.edit { it[KEY_CONTENT_COUNTRY] = countryCode }
    }

    suspend fun setContentLanguage(languageCode: String) {
        dataStore.edit { it[KEY_CONTENT_LANGUAGE] = languageCode }
    }

    suspend fun setHideExplicit(enabled: Boolean) {
        dataStore.edit { it[KEY_HIDE_EXPLICIT] = enabled }
    }

    suspend fun setPauseListenHistory(enabled: Boolean) {
        dataStore.edit { it[KEY_PAUSE_LISTEN_HISTORY] = enabled }
    }

    suspend fun setDisableScreenshots(enabled: Boolean) {
        dataStore.edit { it[KEY_DISABLE_SCREENSHOTS] = enabled }
    }
}
