package com.geekify.android.ui.glass

import android.graphics.BlurMaskFilter
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/*
 * Geekify Liquid Glass - a material for FLOATING surfaces only (nav bar, mini player, panels, popups,
 * dialogs, sheets). Main content is never glass.
 *
 * Layers, bottom to top:
 *  1. BACKDROP BLUR   - Haze samples the content behind the surface and blurs only that region (Android 12+).
 *  2. TINT            - translucent material colour over the blurred backdrop (heavier on large, text-heavy panels).
 *  3. FROST + INNER GRADIENT - a milky lift and top-light/bottom-shade so it reads as a thin physical sheet.
 *  4. CONTENT         - icons / text, always sharp.
 *  5. SPECULAR + RIM  - soft top-left highlight and a thin shape-aware edge light, both stronger while pressed.
 *  6. DEPTH SHADOW    - drawn only OUTSIDE the shape so it never shows through the translucent glass.
 * Refraction (AGSL lens distortion) is not included in this version.
 */

/** The scene behind floating glass. Create with [rememberGlassBackdrop]; mark the content with [glassBackdropSource]. */
@Stable
class GlassBackdrop internal constructor(internal val state: HazeState)

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val state = rememberHazeState()
    return remember(state) { GlassBackdrop(state) }
}

/** Marks this node (and everything inside it) as the content glass blurs. [enabled] = false skips the cost while nothing needs it. */
fun Modifier.glassBackdropSource(backdrop: GlassBackdrop, enabled: Boolean = true): Modifier =
    if (enabled) this.hazeSource(backdrop.state) else this

val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** How much a scrim dims the scene behind the glass; the glass darkens by the same amount so it does not look brighter than its surroundings. */
val LocalGlassDim = compositionLocalOf { 0f }

@Stable
data class GlassStyle(
    val blur: Dp,
    val tint: Color,
    val tintAlpha: Float,
    val rimAlpha: Float,
    val specularAlpha: Float,
    val shadowRadius: Dp,
    val shadowAlpha: Float,
    /** Uniform milky lift so the glass reads as a lighter material even over near-black content. */
    val frost: Float = 0.06f
) {
    companion object {
        private val Smoke = Color(0xFF2B2934)

        /** Bottom navigation: the strongest, most "physical" glass. */
        val Navigation = GlassStyle(22.dp, Smoke, 0.34f, 0.85f, 0.30f, 22.dp, 0.50f, frost = 0.07f)

        /** Mini player and small floating controls: subtler. */
        val Floating = GlassStyle(20.dp, Smoke, 0.42f, 0.60f, 0.20f, 16.dp, 0.42f, frost = 0.06f)

        /** Popups and dialogs: strong glass but dense enough to read text on. */
        val Overlay = GlassStyle(28.dp, Smoke, 0.58f, 0.80f, 0.26f, 34.dp, 0.55f, frost = 0.06f)

        /** Large surfaces with lots of text (profile panel, queue). */
        val Panel = GlassStyle(28.dp, Smoke, 0.64f, 0.80f, 0.22f, 28.dp, 0.50f, frost = 0.05f)
    }
}

/**
 * Turns this surface into Liquid Glass.
 * @param pressProgress 0..1, read in the draw phase (so a press does not recompose): stronger highlight + brightness.
 * @param highlightEnd extra vertical edge light on the trailing edge (for the profile panel that slides in from the start).
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape,
    style: GlassStyle = GlassStyle.Overlay,
    backdrop: GlassBackdrop? = LocalGlassBackdrop.current,
    pressProgress: () -> Float = { 0f },
    highlightEnd: Boolean = false
): Modifier {
    val density = LocalDensity.current
    val dim = LocalGlassDim.current
    val shadowPaint = remember(style, density.density) {
        Paint().apply {
            color = Color.Black.copy(alpha = style.shadowAlpha)
            asFrameworkPaint().maskFilter = BlurMaskFilter(with(density) { style.shadowRadius.toPx() }, BlurMaskFilter.Blur.NORMAL)
        }
    }

    val shaped = this
        // Depth: a soft shadow drawn ONLY outside the shape (an elevation shadow would show through translucent glass).
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply {
                when (outline) {
                    is Outline.Rectangle -> addRect(outline.rect)
                    is Outline.Rounded -> addRoundRect(outline.roundRect)
                    is Outline.Generic -> addPath(outline.path)
                }
            }
            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.clipPath(path, ClipOp.Difference)
                canvas.translate(0f, style.shadowRadius.toPx() * 0.35f)
                canvas.drawOutline(outline, shadowPaint)
                canvas.restore()
            }
        }
        .clip(shape)

    val blurred = if (backdrop != null) {
        shaped.hazeEffect(state = backdrop.state) {
            blurRadius = style.blur
            tints = listOf(HazeTint(style.tint.copy(alpha = style.tintAlpha)))
            noiseFactor = 0.02f
        }
    } else {
        shaped.background(style.tint.copy(alpha = 0.94f)) // no backdrop available: solid, never see-through
    }

    return blurred.drawWithContent {
        val press = pressProgress().coerceIn(0f, 1f)

        if (dim > 0f) drawRect(Color.Black.copy(alpha = dim)) // match the dimmed surroundings
        if (style.frost > 0f) drawRect(Color.White.copy(alpha = style.frost))
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.10f),
                0.45f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.10f)
            )
        )
        if (press > 0f) drawRect(Color.White.copy(alpha = 0.05f * press))

        drawContent()

        // Specular: soft, directional (top-left), stronger while pressed. Never continuously animated.
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = style.specularAlpha + 0.12f * press), Color.Transparent),
                center = Offset(size.width * 0.20f, size.height * 0.02f),
                radius = size.maxDimension * 0.55f
            )
        )

        // Rim light: bright where the light hits (top-left), faint opposite, thin and shape aware.
        val outline = shape.createOutline(size, layoutDirection, this)
        val rim = Brush.linearGradient(
            0f to Color.White.copy(alpha = style.rimAlpha + 0.18f * press),
            0.35f to Color.White.copy(alpha = 0.07f),
            0.70f to Color.White.copy(alpha = 0.05f),
            1f to Color.White.copy(alpha = style.rimAlpha * 0.55f),
            start = Offset.Zero,
            end = Offset(size.width, size.height)
        )
        drawOutline(outline, rim, style = Stroke(width = 2.4.dp.toPx())) // half is clipped, ~1.2dp visible
        drawOutline(outline, Color.White.copy(alpha = 0.035f), style = Stroke(width = 9.dp.toPx())) // soft inner glow = thickness

        if (highlightEnd) {
            val x = size.width - 1.dp.toPx()
            drawLine(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.25f to Color.White.copy(alpha = 0.30f),
                    0.75f to Color.White.copy(alpha = 0.12f),
                    1f to Color.Transparent
                ),
                start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 1.6.dp.toPx()
            )
        }
    }
}

/** Consumes taps so nothing underneath this surface can receive them (hit testing stops at the topmost pointer-input node). */
fun Modifier.consumeTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/** Reports "a finger is down on this surface" without consuming anything, so children and click handlers still work. */
fun Modifier.trackPress(pressed: MutableState<Boolean>): Modifier = pointerInput(pressed) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        pressed.value = true
        waitForUpOrCancellation(pass = PointerEventPass.Initial)
        pressed.value = false
    }
}

/** True when the user turned animations off in system settings; callers then snap instead of animating. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
