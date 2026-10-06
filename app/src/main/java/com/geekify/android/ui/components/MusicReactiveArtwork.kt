package com.geekify.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.collectAsState

/**
 * Premium, audio-reactive artwork treatment for the Now Playing cover.
 * Rotation pauses exactly where it is when playback pauses; the transient meter adds only a
 * bounded scale pulse so loudness never turns into a distracting zoom.
 */
@Composable
fun MusicReactiveArtwork(
    isPlaying: Boolean,
    beatIntensity: StateFlow<Float>,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val currentBeatIntensity by beatIntensity.collectAsState()
    val rotation = remember { Animatable(0f) }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            val start = rotation.value
            rotation.animateTo(
                targetValue = start + 360f,
                animationSpec = tween(
                    durationMillis = ROTATION_DURATION_MS,
                    easing = LinearEasing
                )
            )
            rotation.snapTo(rotation.value % 360f)
        }
    }

    val normalizedBeat = ((currentBeatIntensity - BEAT_FLOOR) / (1f - BEAT_FLOOR))
        .coerceIn(0f, 1f)
    val targetScale = 1f + normalizedBeat * MAX_PULSE_SCALE
    val beatScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 420f
        ),
        label = "artBeatScale"
    )

    Box(
        modifier = modifier.graphicsLayer {
            rotationZ = rotation.value
            scaleX = beatScale
            scaleY = beatScale
        },
        content = content
    )
}

private const val ROTATION_DURATION_MS = 45_000
private const val BEAT_FLOOR = 0.08f
private const val MAX_PULSE_SCALE = 0.05f
