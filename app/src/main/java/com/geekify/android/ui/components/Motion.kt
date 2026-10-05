package com.geekify.android.ui.components

import android.graphics.Color as AndroidColor
import android.util.LruCache
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.geekify.android.ui.theme.InkElevated
import com.geekify.android.ui.theme.Lime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Clickable with a soft spring "press-in" scale, no ripple. Gives every tap a smooth feel. */
@Composable
fun Modifier.bouncyClickable(
    pressedScale: Float = 0.96f,
    onClick: () -> Unit
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(interactionSource = source, indication = null, onClick = onClick)
}

/**
 * [bouncyClickable] that also reacts to a long press (e.g. to remove a saved album or playlist).
 * Gives the same spring press-in, no ripple.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.bouncyCombinedClickable(
    pressedScale: Float = 0.96f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScaleLong"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = source,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
        )
}

// ---------------------------------------------------------------------------------------------
// Album-art colour sampling (no extra dependency): average the pixels of a tiny copy of the art.
// ---------------------------------------------------------------------------------------------

private val artColorCache = LruCache<String, Int>(96)

/** A dark, slightly saturated colour taken from the artwork at [url]. Falls back to [fallback] until loaded. */
@Composable
fun rememberArtColor(url: String?, fallback: Color = InkElevated): State<Color> {
    val context = LocalContext.current
    return produceState(initialValue = fallback, key1 = url) {
        if (url == null) {
            value = fallback
            return@produceState
        }
        artColorCache.get(url)?.let {
            value = Color(it)
            return@produceState
        }
        val argb: Int? = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(48)
                    .allowHardware(false)
                    .build()
                val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
                    ?: return@runCatching null
                averageDarkColor(result.image.toBitmap())
            }.getOrNull()
        }
        if (argb != null) {
            artColorCache.put(url, argb)
            value = Color(argb)
        }
    }
}

private fun averageDarkColor(bitmap: android.graphics.Bitmap): Int {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    var r = 0L
    var g = 0L
    var b = 0L
    for (p in pixels) {
        r += (p shr 16) and 0xFF
        g += (p shr 8) and 0xFF
        b += p and 0xFF
    }
    val n = pixels.size.coerceAtLeast(1)
    val hsv = FloatArray(3)
    AndroidColor.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
    hsv[1] = (hsv[1] * 1.15f).coerceIn(0f, 0.85f)
    hsv[2] = hsv[2].coerceIn(0.28f, 0.50f)
    return AndroidColor.HSVToColor(hsv)
}

// ---------------------------------------------------------------------------------------------
// Seek bar: straight track, lime progress, round thumb with a soft glow. It only draws the
// playback position. Between the ~0.5 s position updates it glides so the thumb never ticks.
// ---------------------------------------------------------------------------------------------

@Composable
fun SeekBar(
    fraction: Float,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = Lime,
    trackColor: Color = Color.White.copy(alpha = 0.22f),
    @Suppress("UNUSED_PARAMETER") isPlaying: Boolean = true
) {
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnFinished by rememberUpdatedState(onChangeFinished)
    var dragging by remember { mutableStateOf(false) }
    val thumbRadius by animateDpAsState(if (dragging) 10.dp else 8.dp, label = "thumb")

    val target = fraction.coerceIn(0f, 1f)
    var lastTarget by remember { mutableFloatStateOf(target) }
    val smoothFraction by animateFloatAsState(
        targetValue = target,
        animationSpec = if (dragging || abs(target - lastTarget) > 0.05f) snap() else tween(900, easing = LinearEasing),
        label = "seekFraction"
    )
    SideEffect { lastTarget = target }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    currentOnChange((offset.x / size.width).coerceIn(0f, 1f))
                    currentOnFinished()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        currentOnChange((offset.x / size.width).coerceIn(0f, 1f))
                    },
                    onDragEnd = {
                        dragging = false
                        currentOnFinished()
                    },
                    onDragCancel = {
                        dragging = false
                        currentOnFinished()
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        currentOnChange((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                )
            }
    ) {
        val strokeW = 4.dp.toPx()
        val cy = size.height / 2f
        val thumbR = thumbRadius.toPx()
        val endX = (size.width * smoothFraction).coerceIn(thumbR, size.width - thumbR)

        drawLine(trackColor, Offset(strokeW / 2f, cy), Offset(size.width - strokeW / 2f, cy), strokeW, StrokeCap.Round)
        drawLine(activeColor, Offset(strokeW / 2f, cy), Offset(endX, cy), strokeW, StrokeCap.Round)
        drawCircle(activeColor.copy(alpha = 0.22f), thumbR * 1.9f, Offset(endX, cy))
        drawCircle(activeColor, thumbR, Offset(endX, cy))
    }
}
