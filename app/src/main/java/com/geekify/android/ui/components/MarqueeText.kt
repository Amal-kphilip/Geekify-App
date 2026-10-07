package com.geekify.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Overflow-only, always-leftward marquee used by the Now Playing title/artist.
 * Two identical copies are placed one loop-distance apart so the loop reset is invisible.
 */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Center,
    gap: Dp = 32.dp,
    speedDpPerSecond: Float = 46f
) {
    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val measuredWidthPx = remember(text, style, density) {
            textMeasurer.measure(
                text = AnnotatedString(text),
                style = style,
                maxLines = 1,
                softWrap = false
            ).size.width
        }
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val gapPx = with(density) { gap.toPx() }
        val overflows = measuredWidthPx > containerWidthPx + 0.5f && containerWidthPx > 0f

        if (!overflows) {
            Text(
                text = text,
                modifier = Modifier.fillMaxWidth(),
                color = color,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                textAlign = textAlign
            )
        } else {
            val loopDistancePx = measuredWidthPx + gapPx
            val centeredStartPx = ((containerWidthPx - measuredWidthPx) / 2f).coerceAtLeast(0f)
            val offset = remember(text, containerWidthPx, measuredWidthPx, gapPx) {
                Animatable(centeredStartPx)
            }

            LaunchedEffect(text, containerWidthPx, measuredWidthPx, gapPx) {
                offset.snapTo(centeredStartPx)
                val pixelsPerSecond = with(density) { speedDpPerSecond.dp.toPx() }
                    .coerceAtLeast(1f)
                val durationMs = (loopDistancePx / pixelsPerSecond * 1_000f)
                    .roundToInt()
                    .coerceAtLeast(300)

                while (true) {
                    offset.animateTo(
                        targetValue = centeredStartPx - loopDistancePx,
                        animationSpec = tween(durationMillis = durationMs, easing = LinearEasing)
                    )
                    // The second copy is exactly where the first copy started, so this snap is invisible.
                    offset.snapTo(centeredStartPx)
                }
            }

            Row(
                modifier = Modifier
                    .wrapContentWidth(unbounded = true)
                    .graphicsLayer { translationX = offset.value },
                verticalAlignment = Alignment.CenterVertically
            ) {
                MarqueeCopy(text, style, color, textAlign)
                Spacer(Modifier.width(gap))
                MarqueeCopy(text, style, color, textAlign)
            }
        }
    }
}

@Composable
private fun MarqueeCopy(
    text: String,
    style: TextStyle,
    color: Color,
    textAlign: TextAlign
) {
    Text(
        text = text,
        color = color,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        textAlign = textAlign
    )
}
