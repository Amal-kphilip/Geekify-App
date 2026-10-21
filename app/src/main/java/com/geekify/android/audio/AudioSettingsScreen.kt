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
import kotlin.math.abs

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
    val presetNames = buildList {
        addAll(EqPresets.presets.keys)
        if (s.preset == EqPresets.CUSTOM || !contains(EqPresets.CUSTOM)) {
            add(EqPresets.CUSTOM)
        }
    }

    // ---- Equalizer ----
    Card {
        SwitchRow(
            title = "Equalizer",
            subtitle = if (s.eqEnabled) "Active · ${s.preset} preset" else "Shape the sound with five bands",
            checked = s.eqEnabled,
            onChecked = vm::setEqEnabled
        )
        Spacer(Modifier.height(14.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(presetNames) { name ->
                PillChip(
                    text = name,
                    selected = s.preset == name,
                    onClick = { vm.selectPreset(name) }
                )
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
                active = s.eqEnabled,
                onValueClick = if (abs(gain) > 0.05f) ({ vm.setBand(index, 0f) }) else null,
                onChange = { vm.setBand(index, it) }
            )
        }
        if (s.bandGainsDb.any { abs(it) > 0.05f }) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Tip: Tap any dB badge to reset that band to 0.0 dB",
                color = TextMuted,
                fontSize = 11.sp
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    // ---- Tempo & pitch ----
    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Tempo & pitch", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            if (abs(s.speed - 1f) > 0.01f || abs(s.pitch - 1f) > 0.01f) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Lime.copy(alpha = 0.14f))
                        .border(1.dp, Lime.copy(alpha = 0.35f), CircleShape)
                        .clickable {
                            vm.setSpeed(1f)
                            vm.setPitch(1f)
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("Reset 1.0×", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { rate ->
                val selected = abs(s.speed - rate) < 0.03f
                PillChip(
                    text = if (rate == 1.0f) "1×" else "${rate}×",
                    selected = selected,
                    onClick = { vm.setSpeed(rate) }
                )
            }
        }
        LabeledSlider(
            label = "Speed",
            value = s.speed,
            valueText = "%.2f×".format(s.speed),
            range = AudioSettings.MIN_RATE..AudioSettings.MAX_RATE,
            active = true,
            onValueClick = if (abs(s.speed - 1f) > 0.01f) ({ vm.setSpeed(1f) }) else null,
            onChange = vm::setSpeed
        )
        LabeledSlider(
            label = "Pitch",
            value = s.pitch,
            valueText = "%.2f×".format(s.pitch),
            range = AudioSettings.MIN_RATE..AudioSettings.MAX_RATE,
            active = true,
            onValueClick = if (abs(s.pitch - 1f) > 0.01f) ({ vm.setPitch(1f) }) else null,
            onChange = vm::setPitch
        )
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
    active: Boolean,
    onValueClick: (() -> Unit)? = null,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = if (active) TextSecondary else TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                color = if (active) Lime else TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = if (onValueClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onValueClick)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                } else {
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                }
            )
        }
        // Keep sliders always draggable so moving a band automatically enables EQ & switches to Custom.
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            enabled = true,
            colors = SliderDefaults.colors(
                thumbColor = if (active) Lime else TextSecondary,
                activeTrackColor = if (active) Lime else Lime.copy(alpha = 0.45f),
                inactiveTrackColor = Color.White.copy(alpha = if (active) 0.18f else 0.10f)
            )
        )
    }
}
