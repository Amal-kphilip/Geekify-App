package com.geekify.android.audio

import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.geekify.android.core.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@HiltViewModel
class AudioSettingsViewModel @Inject constructor(
    private val controller: AudioEffectsController
) : ViewModel() {

    val state: StateFlow<UiState<AudioSettings>> = controller.settings
        .map<AudioSettings, UiState<AudioSettings>> { UiState.Success(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Success(controller.settings.value))

    fun setEqEnabled(enabled: Boolean) = controller.update { it.copy(eqEnabled = enabled) }

    fun selectPreset(name: String) {
        if (name == EqPresets.CUSTOM) {
            controller.update { it.copy(preset = EqPresets.CUSTOM) }
            return
        }
        val gains = EqPresets.presets[name] ?: return
        controller.update { it.copy(preset = name, bandGainsDb = gains) }
    }

    /** Moving a slider switches the preset to "Custom" while keeping the user's current Equalizer on/off state. */
    fun setBand(index: Int, gainDb: Float) = controller.update { s ->
        // Snap near-zero to 0.0 dB and round to 0.5 dB steps for clean control.
        val snapped = if (abs(gainDb) < 0.25f) 0f else (gainDb * 2f).roundToInt() / 2f
        s.copy(
            preset = EqPresets.CUSTOM,
            bandGainsDb = s.bandGainsDb.toMutableList().also { if (index in it.indices) it[index] = snapped }
        )
    }

    fun setSpeed(speed: Float) = controller.update {
        val snapped = if (abs(speed - 1f) < 0.02f) 1f else (speed * 20f).roundToInt() / 20f
        it.copy(speed = snapped)
    }

    fun setPitch(pitch: Float) = controller.update {
        val snapped = if (abs(pitch - 1f) < 0.02f) 1f else (pitch * 20f).roundToInt() / 20f
        it.copy(pitch = snapped)
    }

    fun setSkipSilence(enabled: Boolean) = controller.update { it.copy(skipSilence = enabled) }
    fun setNormalize(enabled: Boolean) = controller.update { it.copy(normalize = enabled) }
    fun resetAll() = controller.update { AudioSettings() }
}
