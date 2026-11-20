package com.geekify.android.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.geekify.android.customization.LocalCustomizationState
import com.geekify.android.customization.PlayerProgressStyle
import com.geekify.android.customization.SeekbarCustomizationConfig
import com.geekify.android.customization.SeekbarGradientPreset
import com.geekify.android.customization.SeekbarThumbStyle
import com.geekify.android.ui.glass.rememberReducedMotion
import com.geekify.android.ui.theme.Lilac
import com.geekify.android.ui.theme.Lime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Resolved active/thumb/gradient colors for the seekbar and mini-player progress bar.
 */
data class ResolvedSeekbarPalette(
    val activeColor: Color,
    val thumbColor: Color,
    val gradientStart: Color,
    val gradientEnd: Color,
    val useGradient: Boolean,
    val inactiveTrackColor: Color
)

fun resolveSeekbarPalette(
    config: SeekbarCustomizationConfig,
    accentColor: Color,
    secondaryAccentColor: Color = Lilac,
    inactiveBaseColor: Color = Color.White
): ResolvedSeekbarPalette {
    val active = if (config.useCustomActiveColor) Color(config.customActiveColorArgb) else accentColor
    val thumb = if (config.useCustomThumbColor) Color(config.customThumbColorArgb) else active
    val useGradient = config.gradientEnabled
    val (gradStart, gradEnd) = when (config.gradientPreset) {
        SeekbarGradientPreset.ACCENT_HARMONY -> active to secondaryAccentColor
        else -> Color(config.gradientPreset.startArgb) to Color(config.gradientPreset.endArgb)
    }
    return ResolvedSeekbarPalette(
        activeColor = active,
        thumbColor = thumb,
        gradientStart = gradStart,
        gradientEnd = gradEnd,
        useGradient = useGradient,
        inactiveTrackColor = inactiveBaseColor.copy(alpha = config.inactiveTrackAlpha.coerceIn(0.08f, 0.65f))
    )
}

/**
 * Unified, customizable playback seekbar supporting all 8 [PlayerProgressStyle] variants,
 * 6 [SeekbarThumbStyle] options, custom thickness, corner radius, gradients, wave/glow animations,
 * and generous touch targets.
 *
 * Used identically in all 6 expanded player layouts and the Appearance live preview.
 */
@Composable
fun PlayerProgressBar(
    fraction: Float,
    config: SeekbarCustomizationConfig,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = Lime,
    secondaryAccentColor: Color = Lilac,
    inactiveBaseColor: Color = Color.White,
    isPlaying: Boolean = true,
    onDragStateChanged: ((Boolean) -> Unit)? = null
) {
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnFinished by rememberUpdatedState(onChangeFinished)
    val currentOnDragStateChanged by rememberUpdatedState(onDragStateChanged)

    val globalHaptics = LocalCustomizationState.current.gestures.hapticFeedback
    val hapticsEnabled = config.hapticFeedback && globalHaptics
    val haptics = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotion()

    var dragging by remember { mutableStateOf(false) }

    val safeFraction = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val palette = remember(config, activeColor, secondaryAccentColor, inactiveBaseColor) {
        resolveSeekbarPalette(
            config = config,
            accentColor = activeColor,
            secondaryAccentColor = secondaryAccentColor,
            inactiveBaseColor = inactiveBaseColor
        )
    }

    val isWaveStyle = config.style == PlayerProgressStyle.WAVY ||
        config.style == PlayerProgressStyle.WAVE_GLOW

    val animateWave = isWaveStyle &&
        isPlaying &&
        !dragging &&
        config.animationsEnabled &&
        config.waveAnimationEnabled &&
        !reducedMotion

    val speedMultiplier = config.waveSpeed.coerceIn(0.4f, 2.5f)
    val phaseDurationMs = (1600f / speedMultiplier).roundToInt().coerceIn(500, 4200)

    val wavePhase by if (animateWave) {
        rememberInfiniteTransition(label = "seekbarWavePhase").animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(phaseDurationMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "seekbarPhaseShift"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val waveFactor by animateFloatAsState(
        targetValue = if (animateWave) config.animationIntensity.coerceIn(0.2f, 1.8f) else 0f,
        animationSpec = tween(durationMillis = if (reducedMotion) 1 else 260),
        label = "seekbarWaveFactor"
    )

    val dragScale by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = tween(durationMillis = if (reducedMotion) 1 else 150),
        label = "seekbarDragScale"
    )

    val path = remember { Path() }
    val touchHeightDp = config.touchTargetHeightDp.coerceIn(32f, 56f).dp

    InteractiveSeekCanvas(
        modifier = modifier
            .fillMaxWidth()
            .height(touchHeightDp),
        onSeekStart = {
            if (!dragging) {
                dragging = true
                currentOnDragStateChanged?.invoke(true)
                if (hapticsEnabled) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
        },
        onSeekFraction = { f ->
            currentOnChange(f.coerceIn(0f, 1f))
        },
        onSeekEnd = {
            if (dragging) {
                dragging = false
                currentOnDragStateChanged?.invoke(false)
                if (hapticsEnabled) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            currentOnFinished()
        }
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@InteractiveSeekCanvas

        val cy = h / 2f
        val baseThicknessPx = config.thicknessDp.coerceIn(2f, 12f).dp.toPx()
        val cornerPx = config.cornerRadiusDp.coerceIn(0f, 12f).dp.toPx()
        val activeEnd = (w * safeFraction).coerceIn(0f, w)

        val activeBrush = if (palette.useGradient) {
            Brush.horizontalGradient(
                colors = listOf(palette.gradientStart, palette.gradientEnd),
                startX = 0f,
                endX = max(activeEnd, w * 0.25f)
            )
        } else {
            Brush.horizontalGradient(
                colors = listOf(palette.activeColor, palette.activeColor),
                startX = 0f,
                endX = max(w, 1f)
            )
        }

        when (config.style) {
            PlayerProgressStyle.CLASSIC_ROUNDED -> {
                drawClassicRoundedTrack(
                    width = w,
                    cy = cy,
                    activeEnd = activeEnd,
                    thicknessPx = baseThicknessPx,
                    cornerPx = cornerPx,
                    inactiveColor = palette.inactiveTrackColor,
                    activeBrush = activeBrush
                )
            }

            PlayerProgressStyle.SLIM_LINEAR -> {
                val slimPx = (baseThicknessPx * 0.65f).coerceIn(2.dp.toPx(), 6.dp.toPx())
                drawClassicRoundedTrack(
                    width = w,
                    cy = cy,
                    activeEnd = activeEnd,
                    thicknessPx = slimPx,
                    cornerPx = min(cornerPx, slimPx / 2f),
                    inactiveColor = palette.inactiveTrackColor,
                    activeBrush = activeBrush
                )
            }

            PlayerProgressStyle.THICK_BAR -> {
                val expandedBoost = 3.5.dp.toPx() * dragScale
                val capsuleH = (baseThicknessPx * 1.85f + expandedBoost).coerceIn(6.dp.toPx(), 18.dp.toPx())
                val capsuleCorner = min(cornerPx.coerceAtLeast(capsuleH * 0.35f), capsuleH / 2f)
                drawRoundRect(
                    color = palette.inactiveTrackColor,
                    topLeft = Offset(0f, cy - capsuleH / 2f),
                    size = Size(w, capsuleH),
                    cornerRadius = CornerRadius(capsuleCorner, capsuleCorner)
                )
                if (activeEnd > 0.5f) {
                    val minFill = min(capsuleH, w)
                    drawRoundRect(
                        brush = activeBrush,
                        topLeft = Offset(0f, cy - capsuleH / 2f),
                        size = Size(max(activeEnd, minFill * safeFraction.coerceAtLeast(0.15f)).coerceAtMost(w), capsuleH),
                        cornerRadius = CornerRadius(capsuleCorner, capsuleCorner)
                    )
                }
            }

            PlayerProgressStyle.WAVY -> {
                drawWavyTrack(
                    path = path,
                    width = w,
                    cy = cy,
                    activeEnd = activeEnd,
                    thicknessPx = baseThicknessPx,
                    inactiveColor = palette.inactiveTrackColor,
                    activeBrush = activeBrush,
                    wavelengthPx = (30.dp.toPx() / config.waveFrequency.coerceIn(0.5f, 2.2f)),
                    amplitudePx = config.waveAmplitudeDp.coerceIn(1f, 10f).dp.toPx() * waveFactor,
                    phase = wavePhase,
                    harmonicSquiggle = false
                )
            }

            PlayerProgressStyle.WAVE_GLOW -> {
                val glowAlpha = config.glowIntensity.coerceIn(0f, 1f) * if (isPlaying || dragging) 1f else 0.55f
                if (activeEnd > 3f && glowAlpha > 0.02f) {
                    val glowHeight = (baseThicknessPx * 3.4f + 6.dp.toPx()).coerceIn(10.dp.toPx(), h * 0.8f)
                    val glowColor = if (palette.useGradient) palette.gradientEnd else palette.activeColor
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                (if (palette.useGradient) palette.gradientStart else palette.activeColor).copy(alpha = 0.08f * glowAlpha),
                                glowColor.copy(alpha = 0.36f * glowAlpha),
                                glowColor.copy(alpha = 0.14f * glowAlpha)
                            ),
                            startX = 0f,
                            endX = activeEnd
                        ),
                        topLeft = Offset(0f, cy - glowHeight / 2f),
                        size = Size(activeEnd, glowHeight),
                        cornerRadius = CornerRadius(glowHeight / 2f, glowHeight / 2f)
                    )
                }
                drawWavyTrack(
                    path = path,
                    width = w,
                    cy = cy,
                    activeEnd = activeEnd,
                    thicknessPx = baseThicknessPx,
                    inactiveColor = palette.inactiveTrackColor,
                    activeBrush = activeBrush,
                    wavelengthPx = (32.dp.toPx() / config.waveFrequency.coerceIn(0.5f, 2.2f)),
                    amplitudePx = (config.waveAmplitudeDp.coerceIn(1f, 10f) * 0.85f).dp.toPx() * waveFactor,
                    phase = wavePhase,
                    harmonicSquiggle = false
                )
            }
        }

        // Render thumb respecting SeekbarThumbStyle (always drawn crisp over the track)
        drawSeekbarThumb(
            thumbStyle = config.thumbStyle,
            thumbSizeDp = config.thumbSizeDp.coerceIn(6f, 24f),
            centerX = activeEnd,
            centerY = cy,
            canvasWidth = w,
            thumbColor = palette.thumbColor,
            dragScale = dragScale,
            glowAlpha = if (config.style == PlayerProgressStyle.WAVE_GLOW) config.glowIntensity.coerceIn(0f, 1f) else 0f
        )
    }
}

private fun DrawScope.drawClassicRoundedTrack(
    width: Float,
    cy: Float,
    activeEnd: Float,
    thicknessPx: Float,
    cornerPx: Float,
    inactiveColor: Color,
    activeBrush: Brush
) {
    val cr = min(cornerPx, thicknessPx / 2f)
    drawRoundRect(
        color = inactiveColor,
        topLeft = Offset(0f, cy - thicknessPx / 2f),
        size = Size(width, thicknessPx),
        cornerRadius = CornerRadius(cr, cr)
    )
    if (activeEnd > 0.5f) {
        drawRoundRect(
            brush = activeBrush,
            topLeft = Offset(0f, cy - thicknessPx / 2f),
            size = Size(activeEnd, thicknessPx),
            cornerRadius = CornerRadius(cr, cr)
        )
    }
}

private fun DrawScope.drawWavyTrack(
    path: Path,
    width: Float,
    cy: Float,
    activeEnd: Float,
    thicknessPx: Float,
    inactiveColor: Color,
    activeBrush: Brush,
    wavelengthPx: Float,
    amplitudePx: Float,
    phase: Float,
    harmonicSquiggle: Boolean
) {
    // Inactive straight track from activeEnd to end
    val gap = 4.dp.toPx()
    if (activeEnd + gap < width) {
        drawLine(
            color = inactiveColor,
            start = Offset(activeEnd + gap, cy),
            end = Offset(width, cy),
            strokeWidth = thicknessPx,
            cap = StrokeCap.Round
        )
    }

    if (activeEnd <= 1f) return

    path.reset()
    val safeWavelength = wavelengthPx.coerceAtLeast(8.dp.toPx())
    val fadeWidth = 14.dp.toPx()
    val step = 3f
    var x = 0f
    path.moveTo(0f, cy)

    while (x <= activeEnd) {
        val startFade = (x / (fadeWidth * 0.6f)).coerceIn(0f, 1f)
        val endFade = ((activeEnd - x) / fadeWidth).coerceIn(0f, 1f)
        val envelope = min(startFade, endFade)
        val theta = (x / safeWavelength) * (2f * PI.toFloat()) - phase
        val wave = if (harmonicSquiggle) {
            sin(theta) * 0.82f + cos(theta * 2f) * 0.18f
        } else {
            sin(theta)
        }
        val y = cy + wave * amplitudePx * envelope
        path.lineTo(x, y)
        x += step
    }
    path.lineTo(activeEnd, cy)

    drawPath(
        path = path,
        brush = activeBrush,
        style = Stroke(
            width = thicknessPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

private fun DrawScope.drawSeekbarThumb(
    thumbStyle: SeekbarThumbStyle,
    thumbSizeDp: Float,
    centerX: Float,
    centerY: Float,
    canvasWidth: Float,
    thumbColor: Color,
    dragScale: Float,
    glowAlpha: Float
) {
    val boost = 1f + 0.24f * dragScale
    when (thumbStyle) {
        SeekbarThumbStyle.HIDDEN -> {
            // Only show a faint touch indicator while actively dragging so scrubbing stays readable
            if (dragScale > 0.05f) {
                val r = 4.5.dp.toPx() * dragScale
                drawCircle(
                    color = thumbColor.copy(alpha = 0.85f * dragScale),
                    radius = r,
                    center = Offset(centerX.coerceIn(r, max(r, canvasWidth - r)), centerY)
                )
            }
        }

        SeekbarThumbStyle.MINIMAL_DOT -> {
            val r = (thumbSizeDp * 0.28f).coerceIn(2.5f, 6f).dp.toPx() * boost
            val cx = centerX.coerceIn(r, max(r, canvasWidth - r))
            drawCircle(color = thumbColor, radius = r, center = Offset(cx, centerY))
        }

        SeekbarThumbStyle.SMALL_CIRCLE -> {
            val r = (thumbSizeDp * 0.38f).coerceIn(3.5f, 8.5f).dp.toPx() * boost
            val cx = centerX.coerceIn(r, max(r, canvasWidth - r))
            if (glowAlpha > 0.05f) {
                drawCircle(color = thumbColor.copy(alpha = 0.24f * glowAlpha), radius = r * 1.7f, center = Offset(cx, centerY))
            }
            drawCircle(color = thumbColor, radius = r, center = Offset(cx, centerY))
        }

        SeekbarThumbStyle.DEFAULT_CIRCLE -> {
            val r = (thumbSizeDp * 0.50f).coerceIn(5f, 12f).dp.toPx() * boost
            val cx = centerX.coerceIn(r, max(r, canvasWidth - r))
            if (glowAlpha > 0.05f) {
                drawCircle(color = thumbColor.copy(alpha = 0.25f * glowAlpha), radius = r * 1.65f, center = Offset(cx, centerY))
            }
            drawCircle(color = thumbColor, radius = r, center = Offset(cx, centerY))
        }

        SeekbarThumbStyle.LARGE_CIRCLE -> {
            val r = (thumbSizeDp * 0.68f).coerceIn(7f, 15f).dp.toPx() * boost
            val cx = centerX.coerceIn(r, max(r, canvasWidth - r))
            if (glowAlpha > 0.05f) {
                drawCircle(color = thumbColor.copy(alpha = 0.28f * glowAlpha), radius = r * 1.55f, center = Offset(cx, centerY))
            }
            drawCircle(color = thumbColor, radius = r, center = Offset(cx, centerY))
        }

        SeekbarThumbStyle.VERTICAL_PILL -> {
            val pillW = (thumbSizeDp * 0.30f).coerceIn(3.5f, 6.5f).dp.toPx()
            val pillH = (thumbSizeDp * 1.35f).coerceIn(13f, 26f).dp.toPx() * (1f + 0.14f * dragScale)
            val left = (centerX - pillW / 2f).coerceIn(0f, max(0f, canvasWidth - pillW))
            if (glowAlpha > 0.05f) {
                drawRoundRect(
                    color = thumbColor.copy(alpha = 0.24f * glowAlpha),
                    topLeft = Offset((left - 2.dp.toPx()).coerceAtLeast(0f), centerY - pillH * 0.6f),
                    size = Size(pillW + 4.dp.toPx(), pillH * 1.2f),
                    cornerRadius = CornerRadius(pillW, pillW)
                )
            }
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(left, centerY - pillH / 2f),
                size = Size(pillW, pillH),
                cornerRadius = CornerRadius(pillW / 2f, pillW / 2f)
            )
        }
    }
}

@Composable
private fun InteractiveSeekCanvas(
    modifier: Modifier,
    onSeekStart: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onSeekEnd: () -> Unit,
    onDraw: DrawScope.() -> Unit
) {
    val startState by rememberUpdatedState(onSeekStart)
    val changeState by rememberUpdatedState(onSeekFraction)
    val endState by rememberUpdatedState(onSeekEnd)

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (size.width > 0) {
                        startState()
                        changeState((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                        endState()
                    }
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        startState()
                        if (size.width > 0) {
                            changeState((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                        }
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        if (size.width > 0) {
                            changeState((change.position.x / size.width.toFloat()).coerceIn(0f, 1f))
                        }
                    },
                    onDragEnd = { endState() },
                    onDragCancel = { endState() }
                )
            },
        onDraw = onDraw
    )
}
