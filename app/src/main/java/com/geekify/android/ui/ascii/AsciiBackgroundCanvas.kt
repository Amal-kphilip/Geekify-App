package com.geekify.android.ui.ascii

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.settings.SettingsViewModel
import com.geekify.android.ui.components.*
import com.geekify.android.ui.theme.GeekifyIcons
import com.geekify.android.ui.theme.Lime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class AsciiEngineInstance(initialAnimalId: String) {
    val tuning = AsciiTuning()
    val renderer = AsciiRenderer(tuning)
    var currentAnimalId: String = initialAnimalId
    var simulation: AsciiSimulation = AsciiSimulationFactory.create(initialAnimalId, tuning)
    var simWidth: Float = 0f
    var simHeight: Float = 0f
    var timeSec: Float = 0f

    fun ensureAnimal(animalId: String) {
        if (animalId != currentAnimalId) {
            currentAnimalId = animalId
            simulation = AsciiSimulationFactory.create(animalId, tuning)
            if (simWidth > 1f && simHeight > 1f) {
                simulation.resize(simWidth, simHeight)
            }
            simulation.onSelect()
        }
    }

    fun ensureSize(w: Float, h: Float) {
        if (abs(w - simWidth) > 1f || abs(h - simHeight) > 1f) {
            simWidth = max(1f, w)
            simHeight = max(1f, h)
            renderer.resize(simWidth, simHeight)
            simulation.resize(simWidth, simHeight)
        }
    }
}

class AsciiPointerHub {
    internal var onDown: ((Float, Float) -> Unit)? = null
    internal var onMove: ((Float, Float) -> Unit)? = null

    fun dispatchDown(rawX: Float, rawY: Float) {
        onDown?.invoke(rawX, rawY)
    }

    fun dispatchMove(rawX: Float, rawY: Float) {
        onMove?.invoke(rawX, rawY)
    }
}

@Composable
fun rememberAsciiPointerHub(): AsciiPointerHub = remember { AsciiPointerHub() }

fun Modifier.asciiPointerObserver(hub: AsciiPointerHub, enabled: Boolean = true): Modifier {
    if (!enabled) return this
    return this.pointerInput(hub) {
        awaitEachGesture {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val changes = event.changes
                if (changes.isEmpty()) break
                val first = changes.first()
                if (first.changedToDownIgnoreConsumed()) {
                    hub.dispatchDown(first.position.x, first.position.y)
                } else if (first.pressed) {
                    hub.dispatchMove(first.position.x, first.position.y)
                }
                if (changes.none { it.pressed }) break
            }
        }
    }
}

@Composable
fun AsciiBackgroundCanvas(
    config: AsciiBackgroundConfig,
    modifier: Modifier = Modifier,
    isPreview: Boolean = false,
    consumeTouches: Boolean = false,
    pointerHub: AsciiPointerHub? = null
) {
    val density = LocalDensity.current.density
    val scaleFactor = remember(density, isPreview) {
        if (isPreview) {
            (density * 0.46f).coerceIn(0.85f, 1.8f)
        } else {
            (density * 0.58f).coerceIn(1.0f, 2.2f)
        }
    }

    val engine = remember { AsciiEngineInstance(config.animalId) }
    val animalSpec = remember(config.animalId) { AsciiCatalog.byId(config.animalId) }

    // Keep tuning synchronized with latest config
    SideEffect {
        engine.tuning.syncFrom(config, animalSpec)
        engine.ensureAnimal(config.animalId)
        if (pointerHub != null) {
            pointerHub.onDown = { rx, ry ->
                engine.simulation.onPointerDown(rx / scaleFactor, ry / scaleFactor)
            }
            pointerHub.onMove = { rx, ry ->
                engine.simulation.onPointerMove(rx / scaleFactor, ry / scaleFactor)
            }
        }
    }

    DisposableEffect(pointerHub) {
        onDispose {
            if (pointerHub != null) {
                pointerHub.onDown = null
                pointerHub.onMove = null
            }
        }
    }

    var frameTick by remember { mutableIntStateOf(0) }
    var lastDeltaSec by remember { mutableFloatStateOf(0.016f) }

    LaunchedEffect(config.enabled, config.animalId) {
        if (!config.enabled && !isPreview) return@LaunchedEffect
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nowNanos ->
                val dt = if (lastNanos == 0L) {
                    0.016f
                } else {
                    ((nowNanos - lastNanos) * 1e-9f).coerceIn(0.001f, 0.05f)
                }
                lastNanos = nowNanos
                lastDeltaSec = dt
                engine.timeSec += dt
                frameTick++
            }
        }
    }

    val pointerModifier = Modifier.pointerInput(engine, scaleFactor, consumeTouches) {
        awaitEachGesture {
            val pass = if (consumeTouches) PointerEventPass.Main else PointerEventPass.Initial
            while (true) {
                val event = awaitPointerEvent(pass)
                val changes = event.changes
                if (changes.isEmpty()) break
                val first = changes.first()
                val sx = first.position.x / scaleFactor
                val sy = first.position.y / scaleFactor
                if (first.changedToDownIgnoreConsumed()) {
                    engine.simulation.onPointerDown(sx, sy)
                    if (consumeTouches) first.consume()
                } else if (first.pressed) {
                    engine.simulation.onPointerMove(sx, sy)
                    if (consumeTouches) first.consume()
                }
                if (changes.none { it.pressed }) break
            }
        }
    }

    Canvas(modifier = modifier.then(pointerModifier)) {
        // Read frameTick so Compose invalidates this Canvas every frame
        @Suppress("UNUSED_EXPRESSION")
        frameTick

        val w = size.width / scaleFactor
        val h = size.height / scaleFactor
        if (w <= 1f || h <= 1f) return@Canvas

        engine.ensureSize(w, h)
        engine.renderer.begin(lastDeltaSec, engine.timeSec)
        engine.simulation.step(lastDeltaSec, engine.timeSec, engine.renderer)

        drawIntoCanvas { composeCanvas ->
            val native = composeCanvas.nativeCanvas
            val saveCount = native.save()
            native.scale(scaleFactor, scaleFactor)
            engine.renderer.end(native)
            native.restoreToCount(saveCount)
        }
    }
}

@Composable
fun AsciiBackgroundSettingsSubPage(
    config: AsciiBackgroundConfig,
    viewModel: SettingsViewModel
) {
    val currentSpec = remember(config.animalId) { AsciiCatalog.byId(config.animalId) }
    var expandedPreview by remember { mutableStateOf(false) }
    val previewHeight by animateDpAsState(
        targetValue = if (expandedPreview) 360.dp else 224.dp,
        animationSpec = tween(260),
        label = "asciiPreviewHeight"
    )

    // 1. LIVE INTERACTIVE PREVIEW
    IosSettingsSection(
        title = "Live interactive preview",
        footer = "Tap inside the preview to interact with ${currentSpec.name} (drop koi food, scatter butterflies, startle frogs & crabs, splash ocean waves, or trigger solar aurora surges)."
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(previewHeight)
                .clip(IosCardShape)
                .border(0.8.dp, SettingsVisuals.cardBorder, IosCardShape)
        ) {
            AsciiBackgroundCanvas(
                config = config,
                isPreview = true,
                consumeTouches = true,
                modifier = Modifier.fillMaxSize()
            )

            // Top bar overlay inside preview
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xB8090B12))
                        .border(0.6.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(currentSpec.swatchColor)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "${currentSpec.name} · ${if (currentSpec.category == "creature") "Creature" else "Scene"}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xB8090B12))
                        .border(0.6.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                        .clickable { expandedPreview = !expandedPreview }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (expandedPreview) "Compact" else "Expand",
                        color = Lime,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Bottom telemetry badge inside preview
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .clip(CircleShape)
                    .background(Color(0xAD090B12))
                    .border(0.5.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Detail ${"%.2f".format(Locale.US, config.detail)} (Max) · Grid ${"%.2f".format(Locale.US, config.grid)}" +
                        if (config.colorCycle) " · Auto Colour" else if (abs(config.hueShift) > 0.5f) " · Hue ${config.hueShift.roundToInt()}°" else "",
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 11.sp
                )
            }
        }
    }

    Spacer(Modifier.height(22.dp))

    // 2. MASTER SWITCH
    IosSettingsSection(title = "App background") {
        IosSettingsSwitchRow(
            icon = GeekifyIcons.AutoAwesome,
            tileColor = Lime,
            title = "Background Animation",
            subtitle = "Render live ASCII creatures & scenes behind Geekify screens and Liquid Glass",
            checked = config.enabled,
            onCheckedChange = viewModel::setAsciiEnabled
        )
    }

    Spacer(Modifier.height(22.dp))

    // 3. CREATURE & SCENE PICKER (14 Creatures + 7 Scenes)
    IosSettingsSection(
        title = "Creatures (${AsciiCatalog.CREATURES.size})",
        footer = "Select any creature or natural scene. Switching preserves max ASCII detail (2.00) and min Grid (0.00)."
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            AnimalSelectorRow(
                items = AsciiCatalog.CREATURES,
                selectedId = config.animalId,
                onSelect = viewModel::setAsciiAnimal
            )
        }
    }

    Spacer(Modifier.height(18.dp))

    IosSettingsSection(title = "Scenes (${AsciiCatalog.SCENES.size})") {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            AnimalSelectorRow(
                items = AsciiCatalog.SCENES,
                selectedId = config.animalId,
                onSelect = viewModel::setAsciiAnimal
            )
        }
    }

    Spacer(Modifier.height(22.dp))

    // 4. COLOUR & MULTI-COLOUR BUTTERFLY TWEAKS
    IosSettingsSection(
        title = "Colour & palette tweaks",
        footer = "Enable Colour Changing for smooth continuous spectrum cycling, shift hues manually, or enable multiple coloured butterflies across the flock."
    ) {
        IosSettingsSwitchRow(
            icon = GeekifyIcons.Palette,
            tileColor = IosPalette.systemPink,
            title = "Colour Changing (Auto Cycle)",
            subtitle = "Continuously cycle ASCII colours across the spectrum",
            checked = config.colorCycle,
            onCheckedChange = viewModel::setAsciiColorCycle
        )
        if (config.colorCycle) {
            IosSettingsDivider(insetStart = 16.dp)
            IosSettingsSliderRow(
                title = "Colour Cycle Speed",
                valueLabel = "${"%.2f".format(Locale.US, config.colorCycleSpeed)}×",
                value = config.colorCycleSpeed,
                valueRange = 0.1f..3.0f,
                onValueChange = viewModel::setAsciiColorCycleSpeed
            )
        }
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSliderRow(
            title = "Hue Shift (°)",
            valueLabel = "${config.hueShift.roundToInt()}°",
            value = config.hueShift,
            valueRange = -180f..180f,
            onValueChange = { viewModel.setAsciiHueShift(it.roundToInt().toFloat()) }
        )
        IosSettingsDivider(insetStart = 16.dp)
        HuePresetRow(
            currentHue = config.hueShift,
            colorCycle = config.colorCycle,
            onSelectHue = { hue, cycle ->
                viewModel.setAsciiColorCycle(cycle)
                viewModel.setAsciiHueShift(hue)
            }
        )
        IosSettingsDivider(insetStart = 16.dp)
        IosSettingsSwitchRow(
            icon = GeekifyIcons.Flare,
            tileColor = IosPalette.systemCyan,
            title = "Multiple Coloured Butterflies",
            subtitle = "Morpho Blue, Monarch Gold, Emerald, Rose, Birdwing, Purple & Cyan wings",
            checked = config.multiColorButterflies,
            onCheckedChange = viewModel::setAsciiMultiColorButterflies
        )
    }

    // 5. ANIMAL / SCENE-SPECIFIC TWEAK SECTIONS
    currentSpec.sections.forEach { section ->
        Spacer(Modifier.height(22.dp))
        IosSettingsSection(title = "${currentSpec.name} · ${section.title}") {
            section.controls.forEachIndexed { idx, ctrl ->
                if (ctrl.key == "multiColorButterflies") {
                    IosSettingsSwitchRow(
                        icon = GeekifyIcons.AutoAwesome,
                        tileColor = IosPalette.systemCyan,
                        title = ctrl.label,
                        subtitle = "Assign distinct vibrant wing palettes to each butterfly",
                        checked = config.multiColorButterflies,
                        onCheckedChange = viewModel::setAsciiMultiColorButterflies
                    )
                } else if (ctrl.isToggle) {
                    val checked = config.boolFor(ctrl.key, currentSpec)
                    IosSettingsSwitchRow(
                        icon = GeekifyIcons.Tune,
                        tileColor = IosPalette.systemTeal,
                        title = ctrl.label,
                        checked = checked,
                        onCheckedChange = { viewModel.setAsciiParam(currentSpec.id, ctrl.key, if (it) 1f else 0f) }
                    )
                } else {
                    val v = config.valueFor(ctrl.key, currentSpec).coerceIn(ctrl.min, ctrl.max)
                    IosSettingsSliderRow(
                        title = ctrl.label,
                        valueLabel = formatAsciiValue(v, ctrl.step),
                        value = v,
                        valueRange = ctrl.min..ctrl.max,
                        onValueChange = { raw ->
                            viewModel.setAsciiParam(currentSpec.id, ctrl.key, snapStep(raw, ctrl.step, ctrl.min, ctrl.max))
                        }
                    )
                }
                if (idx < section.controls.lastIndex) {
                    IosSettingsDivider(insetStart = 16.dp)
                }
            }
        }
    }

    // 6. LOOK TWEAKS (Always starting at Max ASCII Detail = 2.00 and Min Grid = 0.00)
    Spacer(Modifier.height(22.dp))
    IosSettingsSection(
        title = "Look tweaks",
        footer = "ASCII detail starts at maximum (2.00) and Grid starts at minimum (0.00) by default."
    ) {
        AsciiCatalog.LOOK_CONTROLS.forEachIndexed { idx, ctrl ->
            val v = when (ctrl.key) {
                "detail" -> config.detail.coerceIn(ctrl.min, ctrl.max)
                "grid" -> config.grid.coerceIn(ctrl.min, ctrl.max)
                else -> config.valueFor(ctrl.key, currentSpec).coerceIn(ctrl.min, ctrl.max)
            }
            IosSettingsSliderRow(
                title = ctrl.label,
                valueLabel = formatAsciiValue(v, ctrl.step),
                value = v,
                valueRange = ctrl.min..ctrl.max,
                onValueChange = { raw ->
                    val snapped = snapStep(raw, ctrl.step, ctrl.min, ctrl.max)
                    when (ctrl.key) {
                        "detail" -> viewModel.setAsciiDetail(snapped)
                        "grid" -> viewModel.setAsciiGrid(snapped)
                        else -> viewModel.setAsciiParam(currentSpec.id, ctrl.key, snapped)
                    }
                }
            )
            if (idx < AsciiCatalog.LOOK_CONTROLS.lastIndex) {
                IosSettingsDivider(insetStart = 16.dp)
            }
        }
    }

    // 7. MOVEMENT TWEAKS (Filtered by currentSpec.hideShared)
    val visibleMovement = remember(currentSpec) {
        AsciiCatalog.MOVEMENT_CONTROLS.filter { it.key !in currentSpec.hideShared }
    }
    if (visibleMovement.isNotEmpty()) {
        Spacer(Modifier.height(22.dp))
        IosSettingsSection(title = "Movement tweaks") {
            visibleMovement.forEachIndexed { idx, ctrl ->
                if (ctrl.isToggle) {
                    val checked = config.boolFor(ctrl.key, currentSpec)
                    IosSettingsSwitchRow(
                        icon = GeekifyIcons.Navigation,
                        tileColor = IosPalette.systemBlue,
                        title = ctrl.label,
                        checked = checked,
                        onCheckedChange = { viewModel.setAsciiParam(currentSpec.id, ctrl.key, if (it) 1f else 0f) }
                    )
                } else {
                    val v = config.valueFor(ctrl.key, currentSpec).coerceIn(ctrl.min, ctrl.max)
                    IosSettingsSliderRow(
                        title = ctrl.label,
                        valueLabel = formatAsciiValue(v, ctrl.step),
                        value = v,
                        valueRange = ctrl.min..ctrl.max,
                        onValueChange = { raw ->
                            viewModel.setAsciiParam(currentSpec.id, ctrl.key, snapStep(raw, ctrl.step, ctrl.min, ctrl.max))
                        }
                    )
                }
                if (idx < visibleMovement.lastIndex) {
                    IosSettingsDivider(insetStart = 16.dp)
                }
            }
        }
    }

    // 8. RESET TWEAKS
    Spacer(Modifier.height(22.dp))
    IosSettingsSection(title = "Reset") {
        IosSettingsRow(
            icon = GeekifyIcons.RestartAlt,
            tileColor = IosPalette.systemGray,
            title = "Reset ${currentSpec.name} Tweaks",
            subtitle = "Restore default parameters with Max ASCII detail (2.00) & Min Grid (0.00)",
            onClick = { viewModel.resetAsciiDefaults(currentSpec.id) }
        )
    }
}

@Composable
private fun AnimalSelectorRow(
    items: List<AsciiAnimalSpec>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { item ->
            val selected = item.id == selectedId
            val bg by animateColorAsState(
                targetValue = if (selected) item.swatchColor.copy(alpha = 0.22f) else SettingsVisuals.cardRaised,
                animationSpec = tween(180),
                label = "animalChipBg"
            )
            val borderCol by animateColorAsState(
                targetValue = if (selected) item.swatchColor else Color.White.copy(alpha = 0.10f),
                animationSpec = tween(180),
                label = "animalChipBorder"
            )
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(bg)
                    .border(if (selected) 1.2.dp else 0.7.dp, borderCol, CircleShape)
                    .clickable { onSelect(item.id) }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(item.swatchColor)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = item.name,
                    color = if (selected) Color.White else IosPalette.secondaryLabel,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun HuePresetRow(
    currentHue: Float,
    colorCycle: Boolean,
    onSelectHue: (Float, Boolean) -> Unit
) {
    val presets = remember {
        listOf(
            Triple("Natural", 0f, Color(0xFF3287FF)),
            Triple("Emerald", 90f, Color(0xFF30D158)),
            Triple("Violet", 135f, Color(0xFFBF5AF2)),
            Triple("Sunset", -45f, Color(0xFFFF9F0A)),
            Triple("Crimson", -115f, Color(0xFFFF375F)),
            Triple("Cyan", 45f, Color(0xFF64D2FF))
        )
    }
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Rainbow Auto-Cycle chip
        val cycleSelected = colorCycle
        Surface(
            color = if (cycleSelected) IosPalette.systemPink.copy(alpha = 0.24f) else SettingsVisuals.cardRaised,
            shape = CircleShape,
            modifier = Modifier
                .border(
                    width = if (cycleSelected) 1.2.dp else 0.6.dp,
                    color = if (cycleSelected) IosPalette.systemPink else Color.White.copy(alpha = 0.10f),
                    shape = CircleShape
                )
                .clip(CircleShape)
                .clickable { onSelectHue(currentHue, !colorCycle) }
        ) {
            Text(
                text = "Rainbow Cycle",
                color = if (cycleSelected) Color.White else IosPalette.secondaryLabel,
                fontSize = 12.sp,
                fontWeight = if (cycleSelected) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
            )
        }

        presets.forEach { (label, hue, swatch) ->
            val selected = !colorCycle && abs(currentHue - hue) < 2f
            Surface(
                color = if (selected) swatch.copy(alpha = 0.22f) else SettingsVisuals.cardRaised,
                shape = CircleShape,
                modifier = Modifier
                    .border(
                        width = if (selected) 1.2.dp else 0.6.dp,
                        color = if (selected) swatch else Color.White.copy(alpha = 0.10f),
                        shape = CircleShape
                    )
                    .clip(CircleShape)
                    .clickable { onSelectHue(hue, false) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(swatch)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = label,
                        color = if (selected) Color.White else IosPalette.secondaryLabel,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

private fun snapStep(value: Float, step: Float, minV: Float, maxV: Float): Float {
    val clamped = value.coerceIn(minV, maxV)
    if (step <= 0f) return clamped
    val steps = ((clamped - minV) / step).roundToInt()
    return (minV + steps * step).coerceIn(minV, maxV)
}

private fun formatAsciiValue(value: Float, step: Float): String {
    return when {
        step >= 1f -> value.roundToInt().toString()
        step < 0.05f -> "%.2f".format(Locale.US, value)
        else -> "%.2f".format(Locale.US, value).trimEnd('0').trimEnd('.')
    }
}
