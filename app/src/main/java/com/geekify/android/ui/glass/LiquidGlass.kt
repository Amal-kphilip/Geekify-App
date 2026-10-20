package com.geekify.android.ui.glass

import android.graphics.BlurMaskFilter
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.geekify.android.ui.glass.backdrop.Backdrop
import com.geekify.android.ui.glass.backdrop.backdrops.LayerBackdrop
import com.geekify.android.ui.glass.backdrop.backdrops.layerBackdrop
import com.geekify.android.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import com.geekify.android.ui.glass.backdrop.drawBackdrop
import com.geekify.android.ui.glass.backdrop.effects.blur
import com.geekify.android.ui.glass.backdrop.effects.colorControls
import com.geekify.android.ui.glass.backdrop.effects.lens
import com.geekify.android.ui.glass.backdrop.highlight.Highlight
import com.geekify.android.ui.glass.backdrop.highlight.HighlightStyle
import com.geekify.android.ui.glass.backdrop.isRenderEffectSupported
import com.geekify.android.ui.glass.backdrop.shadow.Shadow
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/*
 * Geekify Liquid Glass - Ultra-premium physical glass material adapted from Convx
 *
 * Full multi-layer pipeline:
 *  1. BACKDROP CAPTURE: Hardware-accelerated GraphicsLayer recording of the scene behind the surface.
 *  2. VIBRANCY BOOST: Saturation multiplier using ColorMatrix to recreate authentic iOS translucency.
 *  3. BACKDROP BLUR: Hardware Gaussian blur using RenderEffect (Android 12+).
 *  4. AGSL LENS REFRACTION: Real-time optical physics using Signed Distance Fields (SDF).
 *     Light rays bend at the curved boundary using circleMap distortion, with optional chromatic aberration (RGB dispersion).
 *  5. SURFACE TINT & FROST: Theme-adaptive translucent tint, milky frost lift, and vertical sheen.
 *  6. SPECULAR "PLUCK" & RIM LIGHT: Directional highlight with AGSL shader (dot product of surface normal and light angle).
 *  7. DEPTH SHADOW: Soft shadow drawn strictly outside the shape (never bleed through glass).
 *  8. DUAL-AXIS DARK RIM: Inset diagonal gradient outlines to maintain crisp edges on bright backgrounds.
 *  9. INTERACTIVE TOUCH: Fluid press response with dynamic sheen and spring animations.
 */

/** The scene behind floating glass. Implements [Backdrop] and maintains [HazeState] for backwards compatibility. */
@Stable
class GlassBackdrop internal constructor(
    val layerBackdrop: LayerBackdrop,
    internal val state: HazeState = HazeState()
) : Backdrop by layerBackdrop

@Composable
fun rememberGlassBackdrop(baseBg: Color = Color.Transparent): GlassBackdrop {
    val layerBackdrop = rememberLayerBackdrop(
        onDraw = remember(baseBg) {
            val bg = baseBg
            {
                if (bg.alpha > 0f) drawRect(bg)
                drawContent()
            }
        }
    )
    val state = rememberHazeState()
    return remember(layerBackdrop, state) { GlassBackdrop(layerBackdrop, state) }
}

/** Marks this node (and everything inside it) as the content glass blurs. [enabled] = false skips the cost while nothing needs it. */
fun Modifier.glassBackdropSource(backdrop: GlassBackdrop, enabled: Boolean = true): Modifier =
    if (enabled) {
        this
            .layerBackdrop(backdrop.layerBackdrop)
            .hazeSource(backdrop.state)
    } else this

val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** How much a scrim dims the scene behind the glass. */
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
    val frost: Float = 0.06f,
    /** Saturation boost multiplier for vibrant color translucency. */
    val saturation: Float = 1.6f,
    /** Refraction lens depth from edge. */
    val lensHeight: Dp = 18.dp,
    /** Refraction warp displacement amount. */
    val lensAmount: Dp = 22.dp,
    /** Optical RGB chromatic aberration dispersion. */
    val chromaticAberration: Boolean = false,
    /** Spherical depth distortion. */
    val depthEffect: Boolean = false,
    /** Dark rim opacity for high-contrast edge separation. */
    val darkRimAlpha: Float = 0.32f
) {
    companion object {
        private val Smoke = Color(0xFF2B2934)

        /** Bottom navigation: the strongest, most "physical" liquid glass. */
        val Navigation = GlassStyle(
            blur = 3.dp,
            tint = Smoke,
            tintAlpha = 0.34f,
            rimAlpha = 0.85f,
            specularAlpha = 0.30f,
            shadowRadius = 22.dp,
            shadowAlpha = 0.50f,
            frost = 0.07f,
            saturation = 2.0f,
            lensHeight = 28.dp, // 0.60 * 48dp (Nav spec)
            lensAmount = 33.dp, // 0.69 * 48dp (Nav spec)
            darkRimAlpha = 0.32f
        )

        /** Mini player and small floating controls: subtler. */
        val Floating = GlassStyle(
            blur = 3.dp,
            tint = Smoke,
            tintAlpha = 0.42f,
            rimAlpha = 0.60f,
            specularAlpha = 0.20f,
            shadowRadius = 16.dp,
            shadowAlpha = 0.42f,
            frost = 0.06f,
            saturation = 1.8f,
            lensHeight = 18.dp,
            lensAmount = 22.dp,
            darkRimAlpha = 0.28f
        )

        /** Popups and dialogs: strong glass but dense enough to read text on. */
        val Overlay = GlassStyle(
            blur = 14.dp,
            tint = Smoke,
            tintAlpha = 0.58f,
            rimAlpha = 0.80f,
            specularAlpha = 0.26f,
            shadowRadius = 34.dp,
            shadowAlpha = 0.55f,
            frost = 0.06f,
            saturation = 1.5f,
            lensHeight = 16.dp,
            lensAmount = 20.dp,
            darkRimAlpha = 0.25f
        )

        /** Large surfaces with lots of text (profile panel, queue). */
        val Panel = GlassStyle(
            blur = 16.dp,
            tint = Smoke,
            tintAlpha = 0.64f,
            rimAlpha = 0.80f,
            specularAlpha = 0.22f,
            shadowRadius = 28.dp,
            shadowAlpha = 0.50f,
            frost = 0.05f,
            saturation = 1.4f,
            lensHeight = 14.dp,
            lensAmount = 18.dp,
            darkRimAlpha = 0.22f
        )
    }
}

/**
 * Turns this surface into Liquid Glass using Convx's full AGSL lens refraction & backdrop pipeline.
 *
 * @param shape Shape of the surface (CornerBasedShape enables AGSL SDF lens refraction).
 * @param style Visual tuning preset for blur, refraction, specular, shadow and tint.
 * @param backdrop The source backdrop to refract and blur. Defaults to [LocalGlassBackdrop].
 * @param pressProgress 0..1, read in the draw phase (no recomposition) for interactive press responses.
 * @param highlightEnd Extra vertical edge light on the trailing edge.
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

    // Check device capability (Android 12+ for RenderEffect)
    val hasRenderEffect = isRenderEffectSupported() && !isLowRamDevice()

    if (!hasRenderEffect || backdrop == null) {
        // Fallback for unsupported devices or missing backdrop: soft outside shadow + tinted frosted surface
        val shadowPaint = remember(style, density.density) {
            Paint().apply {
                color = Color.Black.copy(alpha = style.shadowAlpha)
                asFrameworkPaint().maskFilter =
                    BlurMaskFilter(with(density) { style.shadowRadius.toPx() }, BlurMaskFilter.Blur.NORMAL)
            }
        }

        return this
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
            .background(style.tint.copy(alpha = if (backdrop == null) 0.94f else style.tintAlpha + 0.35f))
            .drawWithContent {
                val press = pressProgress().coerceIn(0f, 1f)
                if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
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

                // Rim light
                val outline = shape.createOutline(size, layoutDirection, this)
                val rim = Brush.linearGradient(
                    0f to Color.White.copy(alpha = style.rimAlpha + 0.18f * press),
                    0.35f to Color.White.copy(alpha = 0.07f),
                    0.70f to Color.White.copy(alpha = 0.05f),
                    1f to Color.White.copy(alpha = style.rimAlpha * 0.55f),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
                drawOutline(outline, rim, style = Stroke(width = 2.4.dp.toPx()))
            }
    }

    // Supported path: Hardware-accelerated Convx Liquid Glass with AGSL refraction & backdrop sampling
    val blurRadiusPx = with(density) { style.blur.toPx() }
    val lensHeightPx = with(density) { style.lensHeight.toPx() }
    val lensAmountPx = with(density) { style.lensAmount.toPx() }
    val cornerShape = shape as? CornerBasedShape ?: RoundedCornerShape(24.dp)

    val resolutionScale = glassResolutionScale(style.blur.value)

    return this
        .drawBackdrop(
            backdrop = backdrop.layerBackdrop,
            shape = { cornerShape },
            effects = {
                if (style.saturation != 1f) {
                    colorControls(saturation = style.saturation)
                }
                if (blurRadiusPx > 0f) {
                    blur(blurRadiusPx * resolutionScale)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    (lensHeightPx > 0f || lensAmountPx > 0f)
                ) {
                    lens(
                        refractionHeight = lensHeightPx * resolutionScale,
                        refractionAmount = lensAmountPx * resolutionScale,
                        depthEffect = style.depthEffect,
                        chromaticAberration = style.chromaticAberration,
                    )
                }
            },
            highlight = {
                val press = pressProgress().coerceIn(0f, 1f)
                Highlight(
                    width = EdgeHighlightWidth,
                    style = HighlightStyle.Default(
                        color = Color.White.copy(alpha = (style.rimAlpha * 0.6f + 0.18f * press).coerceIn(0f, 1f)),
                        angle = HighlightAngleFrozen
                    )
                )
            },
            shadow = {
                Shadow(
                    radius = style.shadowRadius,
                    color = Color.Black.copy(alpha = style.shadowAlpha)
                )
            },
            backdropScale = resolutionScale,
            onDrawSurface = {
                val press = pressProgress().coerceIn(0f, 1f)
                if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
                if (style.frost > 0f) drawRect(Color.White.copy(alpha = style.frost))
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.10f),
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.10f)
                    )
                )
                if (style.tintAlpha > 0f) {
                    drawRect(style.tint.copy(alpha = style.tintAlpha))
                }
                if (press > 0f) drawRect(Color.White.copy(alpha = 0.05f * press))
            },
            onDrawFront = {
                val press = pressProgress().coerceIn(0f, 1f)
                // Specular radial highlight
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = style.specularAlpha + 0.12f * press), Color.Transparent),
                        center = Offset(size.width * 0.20f, size.height * 0.02f),
                        radius = size.maxDimension * 0.55f
                    )
                )

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
        )
        .then(
            if (style.darkRimAlpha > 0f) {
                Modifier.outerDarkRim(
                    shape = shape,
                    width = 1.6.dp,
                    alpha = style.darkRimAlpha,
                    angle = HighlightAngleFrozen
                )
            } else {
                Modifier
            }
        )
}

/** Consumes taps so nothing underneath this surface can receive them. */
fun Modifier.consumeTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/** Reports "a finger is down on this surface" without consuming anything. */
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
