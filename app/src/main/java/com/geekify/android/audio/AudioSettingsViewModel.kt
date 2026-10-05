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

@OptIn(UnstableApi::class)
@HiltViewModel
class AudioSettingsViewModel @Inject constructor(
    private val controller: AudioEffectsController
) : ViewModel() {

    val state: StateFlow<UiState<AudioSettings>> = controller.settings
        .map<AudioSettings, UiState<AudioSettings>> { UiState.Success(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun setEqEnabled(enabled: Boolean) = controller.update { it.copy(eqEnabled = enabled) }

    fun selectPreset(name: String) {
        val gains = EqPresets.presets[name] ?: return
        controller.update { it.copy(preset = name, bandGainsDb = gains, eqEnabled = true) }
    }

    /** Moving a slider turns the preset into "Custom" and switches the equalizer on. */
    fun setBand(index: Int, gainDb: Float) = controller.update { s ->
        s.copy(
            preset = EqPresets.CUSTOM,
            eqEnabled = true,
            bandGainsDb = s.bandGainsDb.toMutableList().also { if (index in it.indices) it[index] = gainDb }
        )
    }

    fun setSpeed(speed: Float) = controller.update { it.copy(speed = speed) }
    fun setPitch(pitch: Float) = controller.update { it.copy(pitch = pitch) }
    fun setSkipSilence(enabled: Boolean) = controller.update { it.copy(skipSilence = enabled) }
    fun setNormalize(enabled: Boolean) = controller.update { it.copy(normalize = enabled) }
    fun resetAll() = controller.update { AudioSettings() }
}
