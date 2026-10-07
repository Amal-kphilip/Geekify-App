package com.geekify.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Overflow-only, one-direction marquee. Two copies are separated by [gap] so
 * the second copy enters exactly as the first copy leaves, avoiding a visible reset.
 */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Start,
    gap: Dp = 28.dp,
    speedDpPerSecond: Float = 42f
) {
    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val currentText by rememberUpdatedState(text)

        val textWidthPx = remember(currentText, style) {
            textMeasurer.measure(
                text = AnnotatedString(currentText),
                style = style,
                maxLines = 1,
                softWrap = false
            ).size.width.toFloat()
        }
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val gapPx = with(density) { gap.toPx() }
        val overflows = containerWidthPx > 0f && textWidthPx > containerWidthPx + 0.5f

        if (!overflows) {
            Text(
                text = currentText,
                modifier = Modifier.fillMaxWidth(),
                color = color,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                textAlign = textAlign
            )
        } else {
            val loopDistancePx = textWidthPx + gapPx
            val startX = ((containerWidthPx - textWidthPx) / 2f).coerceAtLeast(0f)
            val offset = remember(text, containerWidthPx, textWidthPx, gapPx) {
                Animatable(startX)
            }

            LaunchedEffect(text, containerWidthPx, textWidthPx, gapPx, speedDpPerSecond) {
                val pxPerSecond = with(density) { speedDpPerSecond.dp.toPx() }
                    .coerceAtLeast(1f)
                val durationMs = (loopDistancePx / pxPerSecond * 1000f)
                    .roundToInt()
                    .coerceAtLeast(300)

                offset.snapTo(startX)
                while (true) {
                    offset.animateTo(
                        targetValue = startX - loopDistancePx,
                        animationSpec = tween(durationMillis = durationMs, easing = LinearEasing)
                    )
                    // The second copy is now exactly where the first copy started.
                    // Resetting here is visually seamless because the visible pixels match.
                    offset.snapTo(startX)
                }
            }

            Row(
                modifier = Modifier
                    .wrapContentWidth(unbounded = true)
                    .graphicsLayer { translationX = offset.value },
                verticalAlignment = Alignment.CenterVertically
            ) {
                MarqueeCopy(currentText, style, color, textAlign)
                Spacer(Modifier.width(gap))
                MarqueeCopy(currentText, style, color, textAlign)
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
