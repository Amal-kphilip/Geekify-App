package com.geekify.android.audio

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.geekify.android.core.UiState
import com.geekify.android.ui.components.CircleIconButton
import com.geekify.android.ui.components.LocalBottomInset
import com.geekify.android.ui.components.PillChip
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.theme.*

/** Equalizer, tempo / pitch, silence skipping and volume normalisation. Every control applies instantly. */
@OptIn(UnstableApi::class)
@Composable
fun AudioSettingsScreen(
    onBack: () -> Unit,
    viewModel: AudioSettingsViewModel = hiltViewModel()
) {
    val ui by viewModel.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(InkBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp + LocalBottomInset.current)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack, size = 50.dp)
                Text(
                    "Audio & equalizer",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(50.dp))
            }
            Spacer(Modifier.height(24.dp))

            when (val state = ui) {
                UiState.Loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Lime)
                }
                is UiState.Error -> Text(state.message, color = ErrorRed, fontSize = 14.sp)
                is UiState.Success -> Content(state.data, viewModel)
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Content(s: AudioSettings, vm: AudioSettingsViewModel) {
    // ---- Equalizer ----
    Card {
        SwitchRow("Equalizer", "Shape the sound with five bands", s.eqEnabled, vm::setEqEnabled)
        Spacer(Modifier.height(14.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(EqPresets.presets.keys.toList()) { name ->
                PillChip(text = name, selected = s.preset == name, onClick = { vm.selectPreset(name) })
            }
        }
        Spacer(Modifier.height(10.dp))
        EqPresets.BAND_HZ.forEachIndexed { index, hz ->
            val gain = s.bandGainsDb.getOrElse(index) { 0f }
            LabeledSlider(
                label = if (hz >= 1000) "${hz / 1000f} kHz".replace(".0 ", " ") else "$hz Hz",
                value = gain,
                valueText = "%+.1f dB".format(gain),
                range = -AudioSettings.MAX_GAIN_DB..AudioSettings.MAX_GAIN_DB,
                enabled = s.eqEnabled,
                onChange = { vm.setBand(index, it) }
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    // ---- Tempo & pitch ----
    Card {
        Text("Tempo & pitch", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        LabeledSlider("Speed", s.speed, "%.2f×".format(s.speed), AudioSettings.MIN_RATE..AudioSettings.MAX_RATE, true, vm::setSpeed)
        LabeledSlider("Pitch", s.pitch, "%.2f×".format(s.pitch), AudioSettings.MIN_RATE..AudioSettings.MAX_RATE, true, vm::setPitch)
    }

    Spacer(Modifier.height(16.dp))

    // ---- Smart playback ----
    Card {
        SwitchRow("Skip silence", "Jump over quiet gaps in tracks", s.skipSilence, vm::setSkipSilence)
        Spacer(Modifier.height(14.dp))
        SwitchRow("Normalize volume", "Even out loud and quiet songs, without clipping", s.normalize, vm::setNormalize)
    }

    Spacer(Modifier.height(24.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .background(InkElevated)
            .bouncyClickable(pressedScale = 0.97f, onClick = vm::resetAll),
        contentAlignment = Alignment.Center
    ) {
        Text("Reset all", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(InkPanel)
            .padding(20.dp),
        content = content
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = OnAccent,
                checkedTrackColor = Lime,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = InkElevated,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Row {
            Text(label, color = if (enabled) TextSecondary else TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = if (enabled) Lime else TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Lime,
                activeTrackColor = Lime,
                inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                disabledThumbColor = TextMuted,
                disabledActiveTrackColor = TextMuted,
                disabledInactiveTrackColor = Color.White.copy(alpha = 0.08f)
            )
        )
    }
}
