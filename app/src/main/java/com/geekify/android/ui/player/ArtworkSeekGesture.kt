package com.geekify.android.ui.player

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.ui.glass.rememberReducedMotion
import kotlin.math.abs

/**
 * Immutable event emitted whenever the user successfully triggers an artwork double-tap seek.
 * [nonce] ensures consecutive identical-duration seeks still re-trigger the arrow nudge & timer.
 */
data class ArtworkSeekFeedback(
    val deltaMs: Long,
    val nonce: Int
)

/**
 * Metrolist-inspired double-tap artwork seek modifier.
 * - Left half of the artwork rewinds; right half fast-forwards.
 * - Supports configurable 5–30s skip duration and optional progressive multiplier (<1s).
 * - When [seekEnabled] is false and [onTap] is null, attaches no pointer input at all so no
 *   accidental single-tap or gesture interference occurs.
 * - Coexists cleanly with horizontal swipe-to-skip (`detectHorizontalDragGestures`) because
 *   horizontal drags exceed touch slop and cancel tap detection automatically.
 */
@Composable
fun Modifier.artworkSeekGestures(
    trackId: String?,
    seekEnabled: Boolean,
    stepSeconds: Int,
    progressive: Boolean,
    onTap: (() -> Unit)? = null,
    onSeekDelta: (Long) -> Unit
): Modifier {
    val engine = remember { ArtworkDoubleTapSkipEngine() }
    LaunchedEffect(trackId) {
        engine.onTrackChanged(trackId)
    }
    LaunchedEffect(seekEnabled, progressive) {
        if (!seekEnabled || !progressive) {
            engine.reset()
        }
    }

    if (!seekEnabled && onTap == null) return this

    val currentEnabled by rememberUpdatedState(seekEnabled)
    val currentStepSeconds by rememberUpdatedState(stepSeconds)
    val currentProgressive by rememberUpdatedState(progressive)
    val currentTrackId by rememberUpdatedState(trackId)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnSeekDelta by rememberUpdatedState(onSeekDelta)

    return this.pointerInput(trackId, seekEnabled) {
        detectTapGestures(
            onTap = if (currentOnTap != null) {
                { currentOnTap?.invoke() }
            } else null,
            onDoubleTap = if (currentEnabled) {
                { offset ->
                    val deltaMs = engine.onDoubleTap(
                        trackId = currentTrackId,
                        tapX = offset.x,
                        widthPx = size.width.toFloat(),
                        enabled = currentEnabled,
                        stepSeconds = currentStepSeconds,
                        progressive = currentProgressive,
                        nowMs = SystemClock.elapsedRealtime()
                    )
                    if (deltaMs != null && deltaMs != 0L) {
                        currentOnSeekDelta(deltaMs)
                    }
                }
            } else null
        )
    }
}

/**
 * Animated Metrolist-inspired seek feedback badge rendered over the left (rewind) or right
 * (fast-forward) side of the album artwork. Uses Geekify's accent color, Sohne Breit typography,
 * and a high-contrast translucent frosted container so it remains readable on any artwork.
 */
@Composable
fun ArtworkSeekFeedbackOverlay(
    feedback: ArtworkSeekFeedback?,
    visible: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val deltaMs = feedback?.deltaMs ?: 0L
    val forward = deltaMs >= 0L
    val seconds = (abs(deltaMs) / 1_000L).toInt().coerceAtLeast(1)
    val signedLabel = if (forward) "+${seconds}s" else "-${seconds}s"
    val accessibilityDesc = if (forward) "Fast-forward $seconds seconds" else "Rewind $seconds seconds"

    val reducedMotion = rememberReducedMotion()
    val density = LocalDensity.current
    val nudgePx = with(density) { 10.dp.toPx() }
    val arrowNudge = remember { Animatable(0f) }

    LaunchedEffect(feedback?.nonce, reducedMotion) {
        if (feedback == null || reducedMotion) {
            arrowNudge.snapTo(0f)
            return@LaunchedEffect
        }
        arrowNudge.snapTo(if (forward) nudgePx else -nudgePx)
        arrowNudge.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    }

    val textShadow = remember(density) {
        Shadow(
            color = Color.Black.copy(alpha = 0.65f),
            offset = Offset(0f, with(density) { 1.dp.toPx() }),
            blurRadius = with(density) { 5.dp.toPx() }
        )
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = if (forward) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        AnimatedVisibility(
            visible = visible && feedback != null,
            enter = scaleIn(
                initialScale = if (reducedMotion) 1f else 0.78f,
                animationSpec = if (reducedMotion) tween(80) else spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) + fadeIn(tween(if (reducedMotion) 80 else 150)),
            exit = scaleOut(
                targetScale = if (reducedMotion) 1f else 0.88f,
                animationSpec = tween(if (reducedMotion) 80 else 180)
            ) + fadeOut(tween(if (reducedMotion) 80 else 180)),
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .widthIn(min = 84.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .border(1.dp, accentColor.copy(alpha = 0.42f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = accessibilityDesc
                        liveRegion = LiveRegionMode.Polite
                    }
            ) {
                Icon(
                    imageVector = if (forward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier
                        .size(30.dp)
                        .graphicsLayer {
                            translationX = arrowNudge.value
                        }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = signedLabel,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(shadow = textShadow)
                )
            }
        }
    }
}
