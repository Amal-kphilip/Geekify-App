package com.geekify.android.audio

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.geekify.android.core.UiState
import com.geekify.android.ui.components.*
import com.geekify.android.ui.theme.*
import java.util.Locale
import kotlin.math.abs

/** Equalizer, tempo / pitch, silence skipping and volume normalisation. Every control applies instantly. */
@OptIn(UnstableApi::class)
@Composable
fun AudioSettingsScreen(
    onBack: () -> Unit,
    viewModel: AudioSettingsViewModel = hiltViewModel()
) {
    val ui by viewModel.state.collectAsState()
    val scrollState = rememberScrollState()
    val bottomInset = LocalBottomInset.current

    IosSettingsPageScaffold(
        title = "Equalizer & effects",
        scrollState = scrollState,
        onBack = onBack,
        bottomPadding = bottomInset + 36.dp
    ) {
        when (val state = ui) {
            UiState.Loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = IosPalette.systemBlue)
            }

            is UiState.Error -> Text(state.message, color = IosPalette.systemRed, fontSize = 14.sp)
            is UiState.Success -> Content(state.data, viewModel)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Content(s: AudioSettings, vm: AudioSettingsViewModel) {
    val presetNames = buildList {
        addAll(EqPresets.presets.keys)
        if (s.preset == EqPresets.CUSTOM || !contains(EqPresets.CUSTOM)) {
            add(EqPresets.CUSTOM)
        }
    }

    // ---- Equalizer ----
    IosSettingsSection(
        title = "Five-band equalizer",
        footer = if (s.bandGainsDb.any { abs(it) > 0.05f }) {
            "Tip: Tap any dB value to reset that band to 0.0 dB."
        } else null
    ) {
        IosSettingsSwitchRow(
            icon = GeekifyIcons.GraphicEq,
            tileColor = IosPalette.systemGreen,
            title = "Equalizer",
            subtitle = if (s.eqEnabled) "Active · ${s.preset} preset" else "Shape the sound across five frequency bands",
            checked = s.eqEnabled,
            onCheckedChange = vm::setEqEnabled
        )
        IosSettingsDivider(insetStart = 16.dp)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(presetNames) { name ->
                val selected = s.preset == name
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (selected) IosPalette.systemBlue
                            else IosPalette.tertiarySystemGroupedBackground
                        )
                        .clickable { vm.selectPreset(name) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name,
                        color = if (selected) Color.White else IosPalette.label,
                        fontSize = 13.5.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }

        EqPresets.BAND_HZ.forEachIndexed { index, hz ->
            IosSettingsDivider(insetStart = 16.dp)
            val gain = s.bandGainsDb.getOrElse(index) { 0f }
            val label = if (hz >= 1000) "${hz / 1000f} kHz".replace(".0 ", " ") else "$hz Hz"
            IosSettingsSliderRow(
                title = label,
                valueLabel = String.format(Locale.US, "%+.1f dB", gain),
                value = gain.coerceIn(-AudioSettings.MAX_GAIN_DB, AudioSettings.MAX_GAIN_DB),
                valueRange = -AudioSettings.MAX_GAIN_DB..AudioSettings.MAX_GAIN_DB,
                activeColor = if (s.eqEnabled) IosPalette.systemGreen else IosPalette.systemGray,
                onValueLabelClick = if (abs(gain) > 0.05f) ({ vm.setBand(index, 0f) }) else null,
                onValueChange = { vm.setBand(index, it) }
            )
        }
    }

    Spacer(Modifier.height(24.dp))

    // ---- Tempo & pitch ----
    IosSettingsSection(title = "Tempo & pitch") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { rate ->
                val selected = abs(s.speed - rate) < 0.03f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(
                            if (selected) IosPalette.systemBlue
                            else IosPalette.tertiarySystemGroupedBackground
                        )
                        .clickable { vm.setSpeed(rate) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (rate == 1.0f) "1×" else "${rate}×",
                        color = if (selected) Color.White else IosPalette.label,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            icon = GeekifyIcons.Speed,
            tileColor = IosPalette.systemOrange,
            title = "Playback Speed",
            valueLabel = String.format(Locale.US, "%.2f×", s.speed),
            value = s.speed.coerceIn(AudioSettings.MIN_RATE, AudioSettings.MAX_RATE),
            valueRange = AudioSettings.MIN_RATE..AudioSettings.MAX_RATE,
            activeColor = IosPalette.systemOrange,
            onValueLabelClick = if (abs(s.speed - 1f) > 0.01f) ({ vm.setSpeed(1f) }) else null,
            onValueChange = vm::setSpeed
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            icon = GeekifyIcons.Tune,
            tileColor = IosPalette.systemPurple,
            title = "Audio Pitch",
            valueLabel = String.format(Locale.US, "%.2f×", s.pitch),
            value = s.pitch.coerceIn(AudioSettings.MIN_RATE, AudioSettings.MAX_RATE),
            valueRange = AudioSettings.MIN_RATE..AudioSettings.MAX_RATE,
            activeColor = IosPalette.systemPurple,
            onValueLabelClick = if (abs(s.pitch - 1f) > 0.01f) ({ vm.setPitch(1f) }) else null,
            onValueChange = vm::setPitch
        )
    }

    Spacer(Modifier.height(24.dp))

    // ---- Smart playback ----
    IosSettingsSection(title = "Smart playback") {
        IosSettingsSwitchRow(
            icon = GeekifyIcons.FastForward,
            tileColor = IosPalette.systemCyan,
            title = "Skip Silence",
            subtitle = "Automatically jump over quiet gaps in tracks",
            checked = s.skipSilence,
            onCheckedChange = vm::setSkipSilence
        )
        IosSettingsDivider()
        IosSettingsSwitchRow(
            icon = GeekifyIcons.VolumeUp,
            tileColor = IosPalette.systemBlue,
            title = "Normalize Volume",
            subtitle = "Even out loud and quiet songs without clipping",
            checked = s.normalize,
            onCheckedChange = vm::setNormalize
        )
    }

    Spacer(Modifier.height(24.dp))

    IosSettingsSection {
        IosSettingsRow(
            icon = GeekifyIcons.RestartAlt,
            tileColor = IosPalette.systemRed,
            title = "Reset All Audio Effects",
            destructive = true,
            showChevron = false,
            onClick = vm::resetAll
        )
    }
}
