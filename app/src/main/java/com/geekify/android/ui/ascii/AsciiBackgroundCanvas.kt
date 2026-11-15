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

    // Throttled frame loop: ~33 FPS for ambient background (saving 75% CPU and preventing scroll jank), ~45 FPS for preview
    val frameIntervalNanos = remember(isPreview) {
        if (isPreview) 22_000_000L else 30_000_000L
    }

    LaunchedEffect(config.enabled, config.animalId, isPreview) {
        if (!config.enabled && !isPreview) return@LaunchedEffect
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nowNanos ->
                val elapsed = nowNanos - lastNanos
                if (lastNanos != 0L && elapsed < frameIntervalNanos) {
                    return@withFrameNanos
                }
                val dt = if (lastNanos == 0L) {
                    0.016f
                } else {
                    (elapsed * 1e-9f).coerceIn(0.005f, 0.05f)
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
            engine.renderer.render(native)
            native.restoreToCount(saveCount)
        }
    }
}

private val ANIMAL_EMOJIS = mapOf(
    "butterflies" to "🦋",
    "koi" to "🎏",
    "jellyfish" to "🪼",
    "frogs" to "🐸",
    "cranes" to "🪶",
    "crabs" to "🦀",
    "whale" to "🐋",
    "rain" to "🌧️",
    "snowfall" to "❄️",
    "waves" to "🌊",
    "aurora" to "🌌"
)

@Composable
fun AsciiBackgroundSettingsSubPage(
    config: AsciiBackgroundConfig,
    viewModel: SettingsViewModel
) {
    val currentSpec = remember(config.animalId) { AsciiCatalog.byId(config.animalId) }
    var expandedPreview by remember { mutableStateOf(false) }
    val previewHeight by animateDpAsState(
        targetValue = if (expandedPreview) 340.dp else 210.dp,
        animationSpec = tween(260),
        label = "asciiPreviewHeight"
    )

    // Category filter: 0 = Creatures, 1 = Scenes
    var categoryTab by remember(currentSpec.category) {
        mutableIntStateOf(if (currentSpec.category == "scene") 1 else 0)
    }

    // Settings control subtab: 0 = Motion, 1 = Color & Style, 2 = Look & Grid
    var controlTab by remember { mutableIntStateOf(0) }

    // 1. LIVE HERO INTERACTIVE PREVIEW
    IosSettingsSection(
        title = "Live interactive preview",
        footer = "Tap inside the preview to interact: feed koi, startle butterflies, scare crabs & frogs, splash waves, or trigger aurora surges."
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

            // Top status bar overlay
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
                        .background(Color(0xD9090B12))
                        .border(0.6.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(currentSpec.swatchColor)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "${ANIMAL_EMOJIS[currentSpec.id] ?: "✨"} ${currentSpec.name}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Reset defaults pill
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xD9090B12))
                            .border(0.6.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                            .clickable { viewModel.resetAsciiDefaults(currentSpec.id) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Reset",
                            color = Color(0xFFFFA726),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Compact/Expand pill
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xD9090B12))
                            .border(0.6.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                            .clickable { expandedPreview = !expandedPreview }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
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
            }

            // Bottom telemetry hint
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC090B12))
                    .border(0.5.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "💡 Tap preview to interact",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp
                )
            }
        }
    }

    Spacer(Modifier.height(18.dp))

    // 2. MASTER TOGGLE
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

    Spacer(Modifier.height(18.dp))

    // 3. CLEAN CATEGORY & ANIMATION PICKER
    IosSettingsSection(
        title = "Choose animation",
        footer = "Pick from 7 biological creatures or 4 natural scenes. Detail starts at max (2.00) and Grid starts at min (0.00)."
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Segmented Category Switcher
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF141722))
                    .padding(3.dp)
            ) {
                val catTabs = listOf("Creatures (7)", "Scenes (4)")
                catTabs.forEachIndexed { idx, label ->
                    val isSelected = categoryTab == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0xFF222838) else Color.Transparent)
                            .clickable { categoryTab = idx }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Carousel of selectable items for active category
            val currentItems = if (categoryTab == 0) AsciiCatalog.CREATURES else AsciiCatalog.SCENES
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                currentItems.forEach { item ->
                    val isSelected = item.id == config.animalId
                    val borderColor by animateColorAsState(
                        targetValue = if (isSelected) Lime else Color.White.copy(alpha = 0.08f),
                        label = "itemBorder"
                    )
                    val bgColor by animateColorAsState(
                        targetValue = if (isSelected) Color(0xFF1C2230) else Color(0xFF10131B),
                        label = "itemBg"
                    )

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(bgColor)
                            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                            .clickable { viewModel.setAsciiAnimal(item.id) }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = ANIMAL_EMOJIS[item.id] ?: "✨",
                            fontSize = 15.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = item.name,
                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.8f),
                                fontSize = 12.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                        Spacer(Modifier.width(7.dp))
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(item.swatchColor)
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(20.dp))

    // 4. USER-FRIENDLY TABBED CONTROLS
    IosSettingsSection(title = "${currentSpec.name} tweaks") {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            // Segmented Control Tabs (Motion, Color, Look)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF141722))
                    .padding(3.dp)
            ) {
                val tabs = listOf("🎛️ Motion", "🎨 Color & Shimmer", "⚙️ Look & Grid")
                tabs.forEachIndexed { idx, label ->
                    val isSelected = controlTab == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0xFF222838) else Color.Transparent)
                            .clickable { controlTab = idx }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        when (controlTab) {
            // TAB 0: ANIMAL / SCENE MOTION
            0 -> {
                Column {
                    if (currentSpec.id == "butterflies") {
                        IosSettingsSwitchRow(
                            icon = GeekifyIcons.Flare,
                            tileColor = IosPalette.systemCyan,
                            title = "Multiple Coloured Butterflies",
                            subtitle = "Show 8 distinct vibrant wing palettes (Morpho Blue, Monarch Gold, Emerald, Rose, Birdwing, Purple, Cyan & Sunset)",
                            checked = config.multiColorButterflies,
                            onCheckedChange = viewModel::setAsciiMultiColorButterflies
                        )
                        IosSettingsDivider(insetStart = 16.dp)
                    }

                    currentSpec.sections.forEach { section ->
                        section.controls.forEachIndexed { idx, ctrl ->
                            if (ctrl.key == "multiColorButterflies") return@forEachIndexed
                            if (ctrl.isToggle) {
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

                    // Shared movement sliders relevant to creatures
                    if (currentSpec.category == "creature") {
                        IosSettingsDivider(insetStart = 16.dp)
                        val speedVal = config.valueFor("speed", currentSpec).coerceIn(0.1f, 3f)
                        IosSettingsSliderRow(
                            title = "Cruise Speed",
                            valueLabel = "${"%.2f".format(Locale.US, speedVal)}×",
                            value = speedVal,
                            valueRange = 0.1f..3f,
                            onValueChange = { viewModel.setAsciiParam(currentSpec.id, "speed", snapStep(it, 0.05f, 0.1f, 3f)) }
                        )

                        IosSettingsDivider(insetStart = 16.dp)
                        val densityVal = config.valueFor("density", currentSpec).coerceIn(0.2f, 3.5f)
                        IosSettingsSliderRow(
                            title = "Population Count",
                            valueLabel = "${"%.2f".format(Locale.US, densityVal)}×",
                            value = densityVal,
                            valueRange = 0.2f..3.5f,
                            onValueChange = { viewModel.setAsciiParam(currentSpec.id, "density", snapStep(it, 0.05f, 0.2f, 3.5f)) }
                        )
                    }
                }
            }

            // TAB 1: COLOR & SHIMMER
            1 -> {
                Column {
                    IosSettingsSwitchRow(
                        icon = GeekifyIcons.Palette,
                        tileColor = IosPalette.systemPink,
                        title = "Colour Changing (Auto Cycle)",
                        subtitle = "Smoothly cycle ASCII hues continuously across the color spectrum",
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
                    val shimmerVal = config.valueFor("shimmer", currentSpec).coerceIn(0f, 1f)
                    IosSettingsSliderRow(
                        title = "Shimmer Iridescence",
                        valueLabel = "${(shimmerVal * 100f).roundToInt()}%",
                        value = shimmerVal,
                        valueRange = 0f..1f,
                        onValueChange = { viewModel.setAsciiParam(currentSpec.id, "shimmer", snapStep(it, 0.02f, 0f, 1f)) }
                    )

                    IosSettingsDivider(insetStart = 16.dp)
                    val sparkleVal = config.valueFor("sparkle", currentSpec).coerceIn(0f, 0.5f)
                    IosSettingsSliderRow(
                        title = "Sparkle Glints",
                        valueLabel = "${(sparkleVal * 100f).roundToInt()}%",
                        value = sparkleVal,
                        valueRange = 0f..0.5f,
                        onValueChange = { viewModel.setAsciiParam(currentSpec.id, "sparkle", snapStep(it, 0.01f, 0f, 0.5f)) }
                    )

                    IosSettingsDivider(insetStart = 16.dp)
                    val fogVal = config.valueFor("fog", currentSpec).coerceIn(0f, 1f)
                    IosSettingsSliderRow(
                        title = "Atmospheric Depth (Fog)",
                        valueLabel = "${(fogVal * 100f).roundToInt()}%",
                        value = fogVal,
                        valueRange = 0f..1f,
                        onValueChange = { viewModel.setAsciiParam(currentSpec.id, "fog", snapStep(it, 0.02f, 0f, 1f)) }
                    )
                }
            }

            // TAB 2: ASCII LOOK & GRID
            2 -> {
                Column {
                    // ASCII Detail: Always starts at Max Detail = 2.00
                    IosSettingsSliderRow(
                        title = "ASCII Detail Resolution",
                        valueLabel = if (config.detail >= 1.95f) "Max (2.00)" else "${"%.2f".format(Locale.US, config.detail)}",
                        value = config.detail,
                        valueRange = 0.5f..MAX_ASCII_DETAIL,
                        onValueChange = { viewModel.setAsciiDetail(snapStep(it, 0.05f, 0.5f, MAX_ASCII_DETAIL)) }
                    )

                    IosSettingsDivider(insetStart = 16.dp)

                    // Grid: Always starts at Min Grid = 0.00
                    IosSettingsSliderRow(
                        title = "Background Grid Lines",
                        valueLabel = if (config.grid <= 0.02f) "Min (0.00)" else "${"%.2f".format(Locale.US, config.grid)}",
                        value = config.grid,
                        valueRange = MIN_ASCII_GRID..4.0f,
                        onValueChange = { viewModel.setAsciiGrid(snapStep(it, 0.05f, MIN_ASCII_GRID, 4.0f)) }
                    )

                    IosSettingsDivider(insetStart = 16.dp)
                    val trailVal = config.valueFor("trail", currentSpec).coerceIn(0f, 4f)
                    IosSettingsSliderRow(
                        title = "Trail Energy",
                        valueLabel = "${"%.2f".format(Locale.US, trailVal)}×",
                        value = trailVal,
                        valueRange = 0f..4f,
                        onValueChange = { viewModel.setAsciiParam(currentSpec.id, "trail", snapStep(it, 0.1f, 0f, 4f)) }
                    )

                    IosSettingsDivider(insetStart = 16.dp)
                    val fadeVal = config.valueFor("trailFade", currentSpec).coerceIn(0.1f, 6f)
                    IosSettingsSliderRow(
                        title = "Trail Fade Rate",
                        valueLabel = "${"%.2f".format(Locale.US, fadeVal)}/s",
                        value = fadeVal,
                        valueRange = 0.1f..6f,
                        onValueChange = { viewModel.setAsciiParam(currentSpec.id, "trailFade", snapStep(it, 0.1f, 0.1f, 6f)) }
                    )
                }
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
    val presets = listOf(
        Triple("Rainbow", 0f, true),
        Triple("Natural", 0f, false),
        Triple("Ocean Blue", 90f, false),
        Triple("Violet Rose", 140f, false),
        Triple("Emerald", -70f, false),
        Triple("Sunset Gold", -135f, false)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        presets.forEach { (label, hue, cycle) ->
            val isSelected = (cycle && colorCycle) || (!colorCycle && !cycle && abs(currentHue - hue) < 5f)
            val borderColor = if (isSelected) Lime else Color.White.copy(alpha = 0.1f)
            val bgColor = if (isSelected) Color(0xFF1E2838) else Color(0xFF10131B)

            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(bgColor)
                    .border(1.dp, borderColor, CircleShape)
                    .clickable { onSelectHue(hue, cycle) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.75f),
                    fontSize = 11.5.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

private fun formatAsciiValue(v: Float, step: Float): String {
    return if (step >= 1f) {
        "${v.roundToInt()}"
    } else if (step >= 0.1f) {
        "%.1f".format(Locale.US, v)
    } else {
        "%.2f".format(Locale.US, v)
    }
}

private fun snapStep(v: Float, step: Float, minV: Float, maxV: Float): Float {
    if (step <= 0f) return v.coerceIn(minV, maxV)
    val snapped = (Math.round((v - minV) / step) * step + minV)
    return snapped.coerceIn(minV, maxV)
}
