package com.geekify.android.ui.player

import com.geekify.android.ui.theme.GeekifyIcons

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private const val PLAY_ANGLE = 120f
private const val PARK_ANGLE = 80f
private const val ROTATION_DURATION_MS = 8_000
// With the existing arm geometry, these offsets sweep the stylus from ~94% of
// the record radius to ~16%, leaving a small gap before the center spindle.
private const val TONEARM_OUTER_SWEEP_OFFSET = -23f
private const val TONEARM_INNER_SWEEP_OFFSET = 11f
private const val TONEARM_SWEEP_DURATION_MS = 5_200
private const val BEAT_FLOOR = 0.08f
private const val MAX_PULSE_SCALE = 0.05f
private val VinylLime = Color(0xFFC6EA5A)

/**
 * Animated record artwork for the expanded Now Playing screen, also serving as the continuous
 * shared artwork element during Now Playing <-> Mini-Player transitions.
 *
 * Reads [collapseProgress] strictly inside draw/graphicsLayer scopes and coarse [derivedStateOf]
 * gates so transition frames never trigger recomposition.
 */
@Composable
fun VinylTonearmCover(
    trackId: String,
    coverUrl: String?,
    direction: Int,
    isPlaying: Boolean,
    beatIntensity: StateFlow<Float>,
    baseScale: Float,
    modifier: Modifier = Modifier,
    collapseProgress: () -> Float = { 0f },
    onTonearmLanded: (String) -> Unit = {},
    discSize: Dp = 300.dp,
) {
    var shownTrackId by remember { mutableStateOf(trackId) }
    var shownUrl by remember { mutableStateOf(coverUrl) }

    val travel = with(LocalDensity.current) { (discSize * 1.1f).toPx() }
    val discX = remember { Animatable(0f) }
    val dropScale = remember { Animatable(1f) }
    val extraSpin = remember { Animatable(0f) }
    val spin = remember { Animatable(0f) }
    val arm = remember { Animatable(PLAY_ANGLE) }
    val tonearmSway = remember { Animatable(0f) }
    var tonearmLanded by remember { mutableStateOf(true) }

    val isExpandedActive by remember(collapseProgress) {
        derivedStateOf { collapseProgress() < 0.995f }
    }
    val isBeatActive by remember(collapseProgress) {
        derivedStateOf { collapseProgress() < 0.5f }
    }
    val currentBeatIntensity by if (isPlaying && isBeatActive) {
        beatIntensity.collectAsStateWithLifecycle()
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    val normalizedBeat = ((currentBeatIntensity - BEAT_FLOOR) / (1f - BEAT_FLOOR))
        .coerceIn(0f, 1f)
    val beatScale by animateFloatAsState(
        targetValue = baseScale * (1f + normalizedBeat * MAX_PULSE_SCALE),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 420f
        ),
        label = "vinylBeatScale"
    )

    // Keep the record spinning while playing and visible; when fully collapsed into the mini-player,
    // reset spin to 0° so subsequent expansions start seamlessly upright from the mini-player cover.
    LaunchedEffect(isPlaying, isExpandedActive) {
        if (!isExpandedActive) {
            spin.snapTo(0f)
            return@LaunchedEffect
        }
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            spin.animateTo(
                targetValue = (spin.value % 360f) + 360f,
                animationSpec = tween(ROTATION_DURATION_MS, easing = LinearEasing)
            )
            spin.snapTo(spin.value % 360f)
        }
    }

    // Slowly track from near the outer rim to just before the center, then back out.
    LaunchedEffect(isPlaying, trackId, shownTrackId, tonearmLanded, isExpandedActive) {
        if (!isPlaying || !tonearmLanded || trackId != shownTrackId || !isExpandedActive) {
            tonearmSway.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 220,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                )
            )
            return@LaunchedEffect
        }

        while (true) {
            tonearmSway.animateTo(
                targetValue = TONEARM_OUTER_SWEEP_OFFSET,
                animationSpec = tween(
                    durationMillis = TONEARM_SWEEP_DURATION_MS,
                    easing = LinearEasing
                )
            )
            tonearmSway.animateTo(
                targetValue = TONEARM_INNER_SWEEP_OFFSET,
                animationSpec = tween(
                    durationMillis = TONEARM_SWEEP_DURATION_MS,
                    easing = LinearEasing
                )
            )
        }
    }

    // Always keep artwork URL up to date if metadata resolves after the initial track emission.
    LaunchedEffect(trackId, coverUrl) {
        if (trackId == shownTrackId && coverUrl != null && shownUrl != coverUrl) {
            shownUrl = coverUrl
        }
    }

    // The track ID—not the artwork URL—is the transition key so songs with shared album art animate too.
    LaunchedEffect(trackId) {
        if (trackId == shownTrackId) {
            if (shownUrl == null && coverUrl != null) shownUrl = coverUrl
            return@LaunchedEffect
        }

        // If the track changes while collapsing, collapsed, or expanding, update destination
        // content immediately without throwing the disc sideways across the transition path.
        if (collapseProgress() > 0.08f) {
            shownUrl = coverUrl
            shownTrackId = trackId
            tonearmSway.snapTo(0f)
            discX.snapTo(0f)
            extraSpin.snapTo(0f)
            dropScale.snapTo(1f)
            arm.snapTo(PLAY_ANGLE)
            tonearmLanded = true
            onTonearmLanded(trackId)
            return@LaunchedEffect
        }

        val travelDirection = if (direction < 0) -1f else 1f

        // Reset every transient value first, preventing a rapid series of skips from leaving the
        // arm or record stranded halfway through an interrupted animation.
        tonearmLanded = false
        tonearmSway.snapTo(0f)
        discX.snapTo(0f)
        extraSpin.snapTo(0f)
        dropScale.snapTo(1f)
        arm.snapTo(PLAY_ANGLE)

        coroutineScope {
            launch {
                arm.animateTo(
                    PARK_ANGLE,
                    animationSpec = tween(350, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                )
            }
            launch {
                delay(250)
                launch {
                    discX.animateTo(
                        targetValue = -travelDirection * travel,
                        animationSpec = tween(400, easing = androidx.compose.animation.core.FastOutLinearInEasing)
                    )
                }
                launch {
                    extraSpin.animateTo(-travelDirection * 220f, tween(400))
                }
            }
        }

        // The new record enters from the opposite side and settles with a small bounce.
        shownUrl = coverUrl
        shownTrackId = trackId
        discX.snapTo(travelDirection * travel)
        extraSpin.snapTo(travelDirection * 160f)
        dropScale.snapTo(1.07f)

        coroutineScope {
            launch {
                discX.animateTo(
                    0f,
                    spring(dampingRatio = 0.6f, stiffness = 250f)
                )
            }
            launch { extraSpin.animateTo(0f, tween(550, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
            launch { dropScale.animateTo(1f, tween(550, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
        }

        arm.animateTo(
            PLAY_ANGLE,
            spring(dampingRatio = 0.5f, stiffness = 200f)
        )
        // Playback is released only after the stylus reaches the playing position.
        tonearmLanded = true
        onTonearmLanded(trackId)
    }

    val displayUrl = shownUrl ?: coverUrl

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Only the disc is rotated/moved; its tonearm remains fixed in the player coordinate space.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = collapseProgress().coerceIn(0f, 1f)
                    val smoothP = p * p * (3f - 2f * p)
                    val effectiveScale = lerp(dropScale.value * beatScale, 1f, smoothP)
                    translationX = discX.value * (1f - smoothP)
                    scaleX = effectiveScale
                    scaleY = effectiveScale
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = collapseProgress().coerceIn(0f, 1f)
                        val smoothP = p * p * (3f - 2f * p)
                        val rawAngle = ((spin.value + extraSpin.value) % 360f + 360f) % 360f
                        val shortestDelta = if (rawAngle > 180f) rawAngle - 360f else rawAngle
                        rotationZ = shortestDelta * (1f - smoothP)
                        shadowElevation = lerp(28.dp.toPx(), 0f, p)
                        shape = CircleShape
                        clip = true
                    }
                    .background(Color(0xFF101014)),
                contentAlignment = Alignment.Center
            ) {
                if (displayUrl != null) {
                    AsyncImage(
                        model = displayUrl,
                        contentDescription = "Vinyl record artwork",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = GeekifyIcons.MusicNote,
                        contentDescription = null,
                        tint = Color(0xFF8B8B95),
                        modifier = Modifier.fillMaxSize(0.42f)
                    )
                }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = collapseProgress().coerceIn(0f, 1f)
                            alpha = (1f - p * 1.85f).coerceIn(0f, 1f)
                        }
                ) {
                    val radius = size.minDimension / 2f
                    val grooveColor = Color.Black.copy(alpha = 0.20f)
                    for (i in 3..19) {
                        drawCircle(
                            color = grooveColor,
                            radius = radius * i / 20f,
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                    listOf(0.56f, 0.73f, 0.87f).forEach { ratio ->
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.38f),
                            radius = radius * ratio,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                    drawArc(
                        color = Color.White.copy(alpha = 0.13f),
                        startAngle = 200f,
                        sweepAngle = 32f,
                        useCenter = true,
                        topLeft = Offset.Zero,
                        size = size
                    )
                    drawArc(
                        color = Color.White.copy(alpha = 0.10f),
                        startAngle = 20f,
                        sweepAngle = 32f,
                        useCenter = true,
                        topLeft = Offset.Zero,
                        size = size
                    )
                    drawCircle(
                        color = Color(0xFF0A0A0C),
                        radius = radius - 3.dp.toPx(),
                        style = Stroke(width = 6.dp.toPx())
                    )
                    drawCircle(color = Color(0xFF96969C), radius = radius * 0.10f)
                    drawCircle(color = Color(0xFF141416), radius = radius * 0.06f)
                    drawCircle(color = Color.White.copy(alpha = 0.32f), radius = radius * 0.018f)
                }
            }
        }

        // The larger canvas deliberately overflows the disc bounds to place the pivot at the upper right.
        // Fade out cleanly during collapse so the tonearm never appears inside the mini-player.
        Canvas(
            Modifier
                .requiredSize(discSize * 2.8f)
                .graphicsLayer {
                    val p = collapseProgress().coerceIn(0f, 1f)
                    alpha = (1f - p * 2.8f).coerceIn(0f, 1f)
                }
        ) {
            val p = collapseProgress().coerceIn(0f, 1f)
            if (p >= 0.38f) return@Canvas
            val unit = (size.width / 5.6f) / 410f
            val pivot = center + Offset(445f * unit, -368f * unit)

            fun unitVector(angle: Float): Offset = Offset(
                cos(Math.toRadians(angle.toDouble())).toFloat(),
                sin(Math.toRadians(angle.toDouble())).toFloat()
            )

            val collapseRetract = p * 18f
            val angle = (arm.value - collapseRetract) + tonearmSway.value * (1f - p)
            val armDirection = unitVector(angle)
            // Lift is derived from the main animation only, not from the small playing sway.
            val lift = (((PLAY_ANGLE - (arm.value - collapseRetract)) / (PLAY_ANGLE - PARK_ANGLE)) * 3f)
                .coerceIn(0f, 1f)

            fun drawTonearm(offset: Offset, shadow: Boolean) {
                fun toneColor(color: Color) = if (shadow) Color.Black.copy(alpha = 0.35f) else color
                val pOffset = pivot + offset
                val end = pOffset + armDirection * (470f * unit)
                val head = end + unitVector(angle + 24f) * (85f * unit)
                val counterweight = pOffset - armDirection * (120f * unit)

                drawCircle(toneColor(Color(0xFF26262A)), 70f * unit, pOffset)
                drawCircle(toneColor(Color(0xFF46464C)), 58f * unit, pOffset)
                drawLine(toneColor(Color(0xFF34343A)), pOffset, counterweight, 56f * unit, StrokeCap.Round)
                drawLine(toneColor(VinylLime), counterweight, pOffset - armDirection * (135f * unit), 60f * unit, StrokeCap.Round)
                drawLine(toneColor(Color(0xFFCDCDD3)), pOffset, end, 20f * unit, StrokeCap.Round)
                drawLine(toneColor(Color(0xFF28282E)), end, head, 44f * unit, StrokeCap.Round)
                drawCircle(toneColor(VinylLime), 13f * unit, head)
                drawCircle(toneColor(Color(0xFF6E6E74)), 26f * unit, pOffset)
                drawCircle(toneColor(VinylLime), 10f * unit, pOffset)
            }

            drawTonearm(
                Offset((6f + 12f * lift) * unit, (10f + 34f * lift) * unit),
                shadow = true
            )
            drawTonearm(Offset.Zero, shadow = false)
        }
    }
}
