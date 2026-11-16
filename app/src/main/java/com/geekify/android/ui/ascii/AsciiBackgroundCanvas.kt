package com.geekify.android.ui.ascii

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.settings.LocalAppSettings
import com.geekify.android.settings.SettingsViewModel
import com.geekify.android.ui.components.*
import com.geekify.android.ui.theme.GeekifyIcons
import com.geekify.android.ui.theme.Lime
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
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
    var lastDetail: Float = -1f
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

    var widthDp: Float = 0f
    var lastSizeChangeTimeNanos: Long = 0L
    var pendingW: Float = 0f
    var pendingH: Float = 0f
    var pendingWdp: Float = 0f
    var lastExtraCw: Float = -1f

    fun ensureSize(
        w: Float,
        h: Float,
        widthDp: Float = 0f,
        extraCw: Float = 0f,
        nowNanos: Long = System.nanoTime()
    ) {
        val detailChanged = abs(tuning.detail - lastDetail) > 0.01f
        val widthDpChanged = abs(this.widthDp - widthDp) > 1f
        val extraCwChanged = abs(this.lastExtraCw - extraCw) > 0.01f
        val dimsChanged = abs(w - simWidth) > 1f || abs(h - simHeight) > 1f

        if (dimsChanged || widthDpChanged) {
            if (abs(w - pendingW) > 1f || abs(h - pendingH) > 1f) {
                pendingW = w
                pendingH = h
                pendingWdp = widthDp
                lastSizeChangeTimeNanos = nowNanos
            }
            // Debounce 150ms on size changes unless initial sizing
            val elapsed = nowNanos - lastSizeChangeTimeNanos
            if (simWidth <= 1f || elapsed >= 150_000_000L) {
                applyResize(pendingW, pendingH, pendingWdp, extraCw)
            }
        } else if (detailChanged || extraCwChanged) {
            applyResize(simWidth, simHeight, this.widthDp, extraCw)
        }
    }

    private fun applyResize(w: Float, h: Float, widthDp: Float, extraCw: Float) {
        simWidth = max(1f, w)
        simHeight = max(1f, h)
        this.widthDp = widthDp
        lastDetail = tuning.detail
        lastExtraCw = extraCw
        renderer.resize(simWidth, simHeight, widthDp, extraCw)
        simulation.resize(simWidth, simHeight)
    }
}

private sealed class AsciiPointerEvent {
    data class Down(val x: Float, val y: Float) : AsciiPointerEvent()
    data class Move(val x: Float, val y: Float) : AsciiPointerEvent()
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

    val context = androidx.compose.ui.platform.LocalContext.current
    val powerManager = remember(context) { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    val activityManager = remember(context) { context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager }
    val isLowRam = remember(activityManager) { activityManager?.isLowRamDevice == true }
    val qualityController = remember(isLowRam) {
        AsciiQualityController(isLowRamDevice = isLowRam).apply {
            maxRequestedDetail = config.detail
        }
    }

    val gpuRenderer = remember {
        if (AsciiGpuSupport.isSupported()) {
            try {
                AsciiGpuRenderer()
            } catch (t: Throwable) {
                AsciiGpuSupport.disableGpu(t)
                null
            }
        } else null
    }

    DisposableEffect(context, powerManager) {
        qualityController.isPowerSaveMode = powerManager?.isPowerSaveMode == true
        val thermalListener: PowerManager.OnThermalStatusChangedListener? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
                val listener = PowerManager.OnThermalStatusChangedListener { status ->
                    qualityController.thermalStatus = status
                }
                try {
                    powerManager.addThermalStatusListener(listener)
                } catch (_: Throwable) {}
                listener
            } else null

        val powerReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
                qualityController.isPowerSaveMode = powerManager?.isPowerSaveMode == true
            }
        }
        try {
            val filter = android.content.IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            context.registerReceiver(powerReceiver, filter)
        } catch (_: Throwable) {}

        onDispose {
            try {
                context.unregisterReceiver(powerReceiver)
            } catch (_: Throwable) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
                try {
                    powerManager?.removeThermalStatusListener(thermalListener)
                } catch (_: Throwable) {}
            }
        }
    }

    val isReduceMotion = remember(context) {
        try {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        } catch (_: Throwable) {
            false
        }
    }
    LaunchedEffect(isReduceMotion) {
        qualityController.isReduceMotion = isReduceMotion
    }

    val pointerQueue = remember { ConcurrentLinkedQueue<AsciiPointerEvent>() }
    val sizeRef = remember { AtomicReference<Triple<Float, Float, Float>?>(null) }
    val configRef = remember { AtomicReference(config) }
    SideEffect {
        configRef.set(config)
    }

    val frameRing = remember { AsciiFrameRing(3) }
    val publishedFrame = remember { AtomicReference<AsciiFrame?>(null) }
    var frameTick by remember { mutableLongStateOf(0L) }

    var displayInterp by remember { mutableFloatStateOf(1f) }
    var displayTick by remember { mutableLongStateOf(0L) }
    val lastSimNanos = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val simIntervalNanosRef = remember { java.util.concurrent.atomic.AtomicLong(33_333_333L) }

    DisposableEffect(pointerHub, scaleFactor) {
        if (pointerHub != null) {
            pointerHub.onDown = { rx, ry ->
                pointerQueue.offer(AsciiPointerEvent.Down(rx / scaleFactor, ry / scaleFactor))
            }
            pointerHub.onMove = { rx, ry ->
                pointerQueue.offer(AsciiPointerEvent.Move(rx / scaleFactor, ry / scaleFactor))
            }
        }
        onDispose {
            if (pointerHub != null) {
                pointerHub.onDown = null
                pointerHub.onMove = null
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    if (AsciiGpuSupport.isSupported() && (config.enabled || isPreview)) {
        LaunchedEffect(lifecycleOwner) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    androidx.compose.runtime.withFrameNanos { frameTimeNanos ->
                        val simStart = lastSimNanos.get()
                        val interval = simIntervalNanosRef.get().coerceAtLeast(1_000_000L)
                        val elapsed = frameTimeNanos - simStart
                        displayInterp = (elapsed.toFloat() / interval.toFloat()).coerceIn(0f, 1f)
                        displayTick++
                    }
                }
            }
        }
    }

    val engine = remember { AsciiEngineInstance(config.animalId) }

    LaunchedEffect(config.enabled, isPreview) {
        if (!config.enabled && !isPreview) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                var lastTimeNanos = System.nanoTime()

                while (isActive) {
                    val startStepNanos = System.nanoTime()
                    lastSimNanos.set(startStepNanos)
                    val elapsedNanos = startStepNanos - lastTimeNanos
                    val dt = if (lastTimeNanos == 0L) 0.016f else (elapsedNanos * 1e-9f).coerceIn(0.005f, 0.05f)
                    lastTimeNanos = startStepNanos

                    // 1. Drain queued pointer events in FIFO order
                    while (true) {
                        val event = pointerQueue.poll() ?: break
                        when (event) {
                            is AsciiPointerEvent.Down -> engine.simulation.onPointerDown(event.x, event.y)
                            is AsciiPointerEvent.Move -> engine.simulation.onPointerMove(event.x, event.y)
                        }
                    }

                    // 2. Thread-safe tuning and animal synchronization
                    val currentCfg = configRef.get()
                    val spec = AsciiCatalog.byId(currentCfg.animalId)
                    engine.tuning.syncFrom(currentCfg, spec)
                    engine.ensureAnimal(currentCfg.animalId)

                    // 3. Thread-safe sizing with debouncing and adaptive quality extraCw
                    val dims = sizeRef.get()
                    if (dims != null && dims.first > 1f && dims.second > 1f) {
                        engine.ensureSize(dims.first, dims.second, dims.third, qualityController.extraCw, startStepNanos)
                    }

                    if (engine.simWidth > 1f && engine.simHeight > 1f) {
                        engine.timeSec += dt
                        engine.renderer.begin(dt, engine.timeSec)
                        engine.simulation.step(dt, engine.timeSec, engine.renderer)

                        val writeFrame = frameRing.nextWriteBuffer()
                        if (AsciiGpuSupport.isSupported() && gpuRenderer != null) {
                            writeFrame.copyFrom(engine.renderer, engine.timeSec)
                            gpuRenderer.uploadFrame(writeFrame)
                        } else {
                            writeFrame.record(engine.renderer, engine.timeSec)
                        }
                        publishedFrame.set(writeFrame)

                        frameTick++
                    }

                    // 4. Update adaptive quality controller and frame rate throttling
                    val workTimeNanos = System.nanoTime() - startStepNanos
                    val costMs = workTimeNanos * 1e-6f
                    qualityController.recordFrameCost(costMs)

                    val baseIntervalMs = if (isPreview) 22f else qualityController.intervalMs
                    val targetIntervalMs = if (AsciiGpuSupport.isSupported() && costMs < 8.0f && !isPreview) {
                        minOf(baseIntervalMs, 16.6f)
                    } else {
                        baseIntervalMs
                    }
                    val targetIntervalNanos = (targetIntervalMs * 1_000_000L).toLong()
                    simIntervalNanosRef.set(targetIntervalNanos)

                    val sleepNanos = targetIntervalNanos - workTimeNanos
                    if (sleepNanos > 1_000_000L) {
                        delay(sleepNanos / 1_000_000L)
                    } else {
                        yield()
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            publishedFrame.set(null)
            pointerQueue.clear()
        }
    }

    val pointerModifier = Modifier.pointerInput(scaleFactor, consumeTouches) {
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
                    pointerQueue.offer(AsciiPointerEvent.Down(sx, sy))
                    if (consumeTouches) first.consume()
                } else if (first.pressed) {
                    pointerQueue.offer(AsciiPointerEvent.Move(sx, sy))
                    if (consumeTouches) first.consume()
                }
                if (changes.none { it.pressed }) break
            }
        }
    }

    val fallbackFillPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
        }
    }
    val fallbackTextPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    val fallbackCharBuf = remember { CharArray(1) }

    Canvas(
        modifier = modifier
            .graphicsLayer()
            .then(pointerModifier)
            .onSizeChanged { intSize ->
                if (intSize.width > 0 && intSize.height > 0) {
                    val w = intSize.width / scaleFactor
                    val h = intSize.height / scaleFactor
                    val wDp = intSize.width / density
                    sizeRef.set(Triple(w, h, wDp))
                }
            }
    ) {
        @Suppress("UNUSED_EXPRESSION")
        frameTick
        @Suppress("UNUSED_EXPRESSION")
        displayTick

        val w = size.width / scaleFactor
        val h = size.height / scaleFactor
        val wDp = size.width / density
        if (w > 1f && h > 1f) {
            sizeRef.set(Triple(w, h, wDp))
        }

        val frame = publishedFrame.get() ?: return@Canvas
        drawIntoCanvas { composeCanvas ->
            val native = composeCanvas.nativeCanvas
            val saveCount = native.save()
            native.scale(scaleFactor, scaleFactor)

            frame.isBusy.set(true)
            var gpuDrawn = false
            try {
                if (AsciiGpuSupport.isSupported() && gpuRenderer != null) {
                    gpuDrawn = gpuRenderer.draw(native, frame, displayInterp, scaleFactor)
                }
                if (!gpuDrawn) {
                    if (frame.bitmap == null) {
                        frame.record(engine.renderer, frame.timeSec)
                    }
                    val bmp = frame.bitmap
                    if (bmp != null && !bmp.isRecycled) {
                        native.drawBitmap(bmp, 0f, 0f, null)
                    } else {
                        val pic = frame.picture
                        if (pic != null) {
                            native.drawPicture(pic)
                        } else {
                            frame.renderFallback(native, fallbackFillPaint, fallbackTextPaint, fallbackCharBuf)
                        }
                    }
                }
            } finally {
                frame.isBusy.set(false)
                native.restoreToCount(saveCount)
            }
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
fun AsciiBackgroundSettingsPage(
    config: AsciiBackgroundConfig,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    showBackButton: Boolean = true,
    bottomPadding: Dp = 40.dp
) {
    val scrollState = rememberScrollState()
    val currentSpec = remember(config.animalId) { AsciiCatalog.byId(config.animalId) }
    var expandedPreview by remember { mutableStateOf(false) }
    val previewHeight by animateDpAsState(
        targetValue = if (expandedPreview) 260.dp else 180.dp,
        animationSpec = tween(260),
        label = "asciiPreviewHeight"
    )

    // Category filter: 0 = Creatures, 1 = Scenes
    var categoryTab by remember(currentSpec.category) {
        mutableIntStateOf(if (currentSpec.category == "scene") 1 else 0)
    }

    // Settings control subtab: 0 = Motion, 1 = Color, 2 = Look & Grid
    var controlTab by remember { mutableIntStateOf(0) }

    val asciiEnabled = LocalAppSettings.current.ascii.enabled

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (asciiEnabled) Color.Transparent else IosPalette.systemGroupedBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // 1. FIXED TOP NAVIGATION BAR
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(IosPalette.systemGroupedBackground.copy(alpha = 0.94f))
                    .padding(horizontal = 16.dp)
            ) {
                if (showBackButton) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(IosPalette.secondarySystemGroupedBackground)
                            .border(0.5.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                            .bouncyClickable(pressedScale = 0.92f, onClick = onBack),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = GeekifyIcons.ArrowBack,
                            contentDescription = "Back",
                            tint = IosPalette.label,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Text(
                    text = "Background & Tweaks",
                    color = IosPalette.label,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 56.dp)
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = IosPalette.separator)

            // 2. PINNED HERO INTERACTIVE PREVIEW (Stays STILL while scrolling!)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
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
                            .padding(horizontal = 12.dp, vertical = 8.dp),
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
                            .padding(horizontal = 12.dp, vertical = 8.dp)
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

            // 3. SCROLLABLE CONTROLS BODY (Scrolls underneath the pinned preview)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 4.dp, bottom = bottomPadding)
                ) {
                    // MASTER TOGGLE
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

                    Spacer(Modifier.height(16.dp))

                    // CLEAN CATEGORY & ANIMATION PICKER
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

                    Spacer(Modifier.height(16.dp))

                    // USER-FRIENDLY TABBED CONTROLS
                    IosSettingsSection(title = "${currentSpec.name} tweaks") {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            // Clean Segmented Tabs (Single-line, no text wrapping)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF141722))
                                    .padding(3.dp)
                            ) {
                                val tabs = listOf("🎲 Motion", "🎨 Color", "⚙️ Look & Grid")
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
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
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

                                    // Animal specific controls
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

                                    IosSettingsDivider(insetStart = 16.dp)

                                    // Cruise Speed
                                    val speedVal = config.valueFor("speed", currentSpec).coerceIn(0.1f, 3f)
                                    IosSettingsSliderRow(
                                        title = "Speed",
                                        valueLabel = "${"%.2f".format(Locale.US, speedVal)}×",
                                        value = speedVal,
                                        valueRange = 0.1f..3f,
                                        onValueChange = { viewModel.setAsciiParam(currentSpec.id, "speed", snapStep(it, 0.05f, 0.1f, 3f)) }
                                    )

                                    IosSettingsDivider(insetStart = 16.dp)

                                    // Density / Population Count
                                    val densityVal = config.valueFor("density", currentSpec).coerceIn(0.1f, 4f)
                                    IosSettingsSliderRow(
                                        title = "Density",
                                        valueLabel = "${"%.2f".format(Locale.US, densityVal)}×",
                                        value = densityVal,
                                        valueRange = 0.1f..4f,
                                        onValueChange = { viewModel.setAsciiParam(currentSpec.id, "density", snapStep(it, 0.05f, 0.1f, 4f)) }
                                    )

                                    if (currentSpec.category == "creature") {
                                        IosSettingsDivider(insetStart = 16.dp)
                                        val swayVal = config.valueFor("sway", currentSpec).coerceIn(0f, 0.6f)
                                        IosSettingsSliderRow(
                                            title = "Sway",
                                            valueLabel = "%.2f".format(Locale.US, swayVal),
                                            value = swayVal,
                                            valueRange = 0f..0.6f,
                                            onValueChange = { viewModel.setAsciiParam(currentSpec.id, "sway", snapStep(it, 0.01f, 0f, 0.6f)) }
                                        )

                                        IosSettingsDivider(insetStart = 16.dp)
                                        val spreadVal = config.valueFor("spread", currentSpec).coerceIn(0f, 0.8f)
                                        IosSettingsSliderRow(
                                            title = "Spread",
                                            valueLabel = "%.2f".format(Locale.US, spreadVal),
                                            value = spreadVal,
                                            valueRange = 0f..0.8f,
                                            onValueChange = { viewModel.setAsciiParam(currentSpec.id, "spread", snapStep(it, 0.01f, 0f, 0.8f)) }
                                        )

                                        IosSettingsDivider(insetStart = 16.dp)
                                        val sizeVal = config.valueFor("size", currentSpec).coerceIn(0.3f, 2.5f)
                                        IosSettingsSliderRow(
                                            title = "Size",
                                            valueLabel = "${"%.2f".format(Locale.US, sizeVal)}×",
                                            value = sizeVal,
                                            valueRange = 0.3f..2.5f,
                                            onValueChange = { viewModel.setAsciiParam(currentSpec.id, "size", snapStep(it, 0.05f, 0.3f, 2.5f)) }
                                        )

                                        IosSettingsDivider(insetStart = 16.dp)
                                        val windVal = config.valueFor("wind", currentSpec).coerceIn(0f, 2.5f)
                                        IosSettingsSliderRow(
                                            title = "Wind gusts",
                                            valueLabel = "%.2f".format(Locale.US, windVal),
                                            value = windVal,
                                            valueRange = 0f..2.5f,
                                            onValueChange = { viewModel.setAsciiParam(currentSpec.id, "wind", snapStep(it, 0.05f, 0f, 2.5f)) }
                                        )

                                        IosSettingsDivider(insetStart = 16.dp)
                                        val gripVal = config.valueFor("grip", currentSpec).coerceIn(0.2f, 5f)
                                        IosSettingsSliderRow(
                                            title = "Hold course",
                                            valueLabel = "%.2f".format(Locale.US, gripVal),
                                            value = gripVal,
                                            valueRange = 0.2f..5f,
                                            onValueChange = { viewModel.setAsciiParam(currentSpec.id, "grip", snapStep(it, 0.05f, 0.2f, 5f)) }
                                        )

                                        IosSettingsDivider(insetStart = 16.dp)
                                        val sepVal = config.valueFor("separation", currentSpec).coerceIn(0f, 1000f)
                                        IosSettingsSliderRow(
                                            title = "Separation",
                                            valueLabel = "${sepVal.roundToInt()}",
                                            value = sepVal,
                                            valueRange = 0f..1000f,
                                            onValueChange = { viewModel.setAsciiParam(currentSpec.id, "separation", snapStep(it, 10f, 0f, 1000f)) }
                                        )

                                        IosSettingsDivider(insetStart = 16.dp)
                                        val cornerVal = config.boolFor("corner", currentSpec)
                                        IosSettingsSwitchRow(
                                            icon = GeekifyIcons.Tune,
                                            tileColor = IosPalette.systemOrange,
                                            title = "Corner to corner",
                                            subtitle = "Cross diagonally bottom-left to top-right",
                                            checked = cornerVal,
                                            onCheckedChange = { viewModel.setAsciiParam(currentSpec.id, "corner", if (it) 1f else 0f) }
                                        )

                                        if (!cornerVal) {
                                            IosSettingsDivider(insetStart = 16.dp)
                                            val dirVal = config.valueFor("direction", currentSpec).coerceIn(0f, 360f)
                                            IosSettingsSliderRow(
                                                title = "Direction (°)",
                                                valueLabel = "${dirVal.roundToInt()}°",
                                                value = dirVal,
                                                valueRange = 0f..360f,
                                                onValueChange = { viewModel.setAsciiParam(currentSpec.id, "direction", snapStep(it, 1f, 0f, 360f)) }
                                            )
                                        }
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
                                    val dnVal = config.valueFor("dayNight", currentSpec).coerceIn(0f, 2f)
                                    IosSettingsSliderRow(
                                        title = "Day / Night Cycle",
                                        valueLabel = if (dnVal <= 0.02f) "Off (Dark)" else "${"%.2f".format(Locale.US, dnVal)}×",
                                        value = dnVal,
                                        valueRange = 0f..2f,
                                        onValueChange = { viewModel.setAsciiParam(currentSpec.id, "dayNight", snapStep(it, 0.05f, 0f, 2f)) }
                                    )

                                    IosSettingsDivider(insetStart = 16.dp)
                                    val shimmerVal = config.valueFor("shimmer", currentSpec).coerceIn(0f, 1f)
                                    IosSettingsSliderRow(
                                        title = "Shimmer",
                                        valueLabel = "${(shimmerVal * 100f).roundToInt()}%",
                                        value = shimmerVal,
                                        valueRange = 0f..1f,
                                        onValueChange = { viewModel.setAsciiParam(currentSpec.id, "shimmer", snapStep(it, 0.01f, 0f, 1f)) }
                                    )

                                    IosSettingsDivider(insetStart = 16.dp)
                                    val sparkleVal = config.valueFor("sparkle", currentSpec).coerceIn(0f, 0.5f)
                                    IosSettingsSliderRow(
                                        title = "Sparkle",
                                        valueLabel = "${(sparkleVal * 100f).roundToInt()}%",
                                        value = sparkleVal,
                                        valueRange = 0f..0.5f,
                                        onValueChange = { viewModel.setAsciiParam(currentSpec.id, "sparkle", snapStep(it, 0.01f, 0f, 0.5f)) }
                                    )

                                    IosSettingsDivider(insetStart = 16.dp)
                                    val fogVal = config.valueFor("fog", currentSpec).coerceIn(0f, 1f)
                                    IosSettingsSliderRow(
                                        title = "Distance fade (Fog)",
                                        valueLabel = "${(fogVal * 100f).roundToInt()}%",
                                        value = fogVal,
                                        valueRange = 0f..1f,
                                        onValueChange = { viewModel.setAsciiParam(currentSpec.id, "fog", snapStep(it, 0.01f, 0f, 1f)) }
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
                                    val ripVal = config.valueFor("ripples", currentSpec).coerceIn(0f, 2.5f)
                                    IosSettingsSliderRow(
                                        title = "Water ripples",
                                        valueLabel = "%.2f".format(Locale.US, ripVal),
                                        value = ripVal,
                                        valueRange = 0f..2.5f,
                                        onValueChange = { viewModel.setAsciiParam(currentSpec.id, "ripples", snapStep(it, 0.05f, 0f, 2.5f)) }
                                    )

                                    IosSettingsDivider(insetStart = 16.dp)
                                    val trailVal = config.valueFor("trail", currentSpec).coerceIn(0f, 4f)
                                    IosSettingsSliderRow(
                                        title = "Trail Energy",
                                        valueLabel = "${"%.2f".format(Locale.US, trailVal)}×",
                                        value = trailVal,
                                        valueRange = 0f..4f,
                                        onValueChange = { viewModel.setAsciiParam(currentSpec.id, "trail", snapStep(it, 0.05f, 0f, 4f)) }
                                    )

                                    IosSettingsDivider(insetStart = 16.dp)
                                    val fadeVal = config.valueFor("trailFade", currentSpec).coerceIn(0.05f, 6f)
                                    IosSettingsSliderRow(
                                        title = "Trail Fade Rate",
                                        valueLabel = "${"%.2f".format(Locale.US, fadeVal)}/s",
                                        value = fadeVal,
                                        valueRange = 0.05f..6f,
                                        onValueChange = { viewModel.setAsciiParam(currentSpec.id, "trailFade", snapStep(it, 0.05f, 0.05f, 6f)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AsciiBackgroundSettingsSubPage(
    config: AsciiBackgroundConfig,
    viewModel: SettingsViewModel
) {
    AsciiBackgroundSettingsPage(
        config = config,
        viewModel = viewModel,
        onBack = {},
        showBackButton = false,
        bottomPadding = 40.dp
    )
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
