package com.geekify.android.audio

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.geekify.android.data.local.safeDbCall
import com.geekify.android.data.source.MusicResult
import com.geekify.android.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the audio settings screen can change. This is the contract between the UI and the audio
 * pipeline: [AudioEffectsController] turns a new value into processor parameters without rebuilding the player.
 */
data class AudioSettings(
    val eqEnabled: Boolean = false,
    val preset: String = EqPresets.FLAT,
    /** Gain in dB for each of [EqPresets.BAND_HZ]. */
    val bandGainsDb: List<Float> = EqPresets.zeros(),
    /** Tempo (1.0 = normal). Pitch is independent of it. */
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val skipSilence: Boolean = false,
    val normalize: Boolean = false
) {
    /** Clamps every value into a safe range, so a corrupt stored value can never produce harsh or invalid audio. */
    fun sanitized(): AudioSettings = copy(
        bandGainsDb = List(EqPresets.BAND_HZ.size) { i -> (bandGainsDb.getOrNull(i) ?: 0f).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB) },
        speed = speed.coerceIn(MIN_RATE, MAX_RATE),
        pitch = pitch.coerceIn(MIN_RATE, MAX_RATE)
    )

    companion object {
        const val MAX_GAIN_DB = 12f
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2f
    }
}

object EqPresets {
    const val FLAT = "Flat"
    const val CUSTOM = "Custom"
    val BAND_HZ = listOf(60, 230, 910, 3600, 14000)

    fun zeros() = List(BAND_HZ.size) { 0f }

    /** Gains in dB per band (60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz). */
    val presets: LinkedHashMap<String, List<Float>> = linkedMapOf(
        FLAT to listOf(0f, 0f, 0f, 0f, 0f),
        "Bass boost" to listOf(6f, 4f, 0f, 0f, 0f),
        "Vocal" to listOf(-2f, 0f, 3f, 3f, 0f),
        "Treble boost" to listOf(0f, 0f, 0f, 3f, 6f),
        "Rock" to listOf(4f, 2f, -2f, 2f, 4f),
        "Electronic" to listOf(5f, 2f, -1f, 3f, 4f),
        "Acoustic" to listOf(3f, 2f, 1f, 2f, 3f)
    )
}

/** Saves [AudioSettings] in DataStore. Reads never throw (a failed read yields the defaults). */
@Singleton
class AudioSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val eqEnabled = booleanPreferencesKey("audio_eq_enabled")
    private val preset = stringPreferencesKey("audio_eq_preset")
    private val gains = stringPreferencesKey("audio_eq_gains")
    private val speed = floatPreferencesKey("audio_speed")
    private val pitch = floatPreferencesKey("audio_pitch")
    private val skipSilence = booleanPreferencesKey("audio_skip_silence")
    private val normalize = booleanPreferencesKey("audio_normalize")

    val settings: Flow<AudioSettings> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { decode(it) }
        .distinctUntilChanged()

    suspend fun update(transform: (AudioSettings) -> AudioSettings): MusicResult<Unit> = safeDbCall(io) {
        dataStore.edit { prefs -> encode(prefs, transform(decode(prefs)).sanitized()) }
        Unit
    }

    private fun decode(p: Preferences): AudioSettings = AudioSettings(
        eqEnabled = p[eqEnabled] ?: false,
        preset = p[preset] ?: EqPresets.FLAT,
        bandGainsDb = p[gains]?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: EqPresets.zeros(),
        speed = p[speed] ?: 1f,
        pitch = p[pitch] ?: 1f,
        skipSilence = p[skipSilence] ?: false,
        normalize = p[normalize] ?: false
    ).sanitized()

    private fun encode(p: MutablePreferences, s: AudioSettings) {
        p[eqEnabled] = s.eqEnabled
        p[preset] = s.preset
        p[gains] = s.bandGainsDb.joinToString(",")
        p[speed] = s.speed
        p[pitch] = s.pitch
        p[skipSilence] = s.skipSilence
        p[normalize] = s.normalize
    }
}
