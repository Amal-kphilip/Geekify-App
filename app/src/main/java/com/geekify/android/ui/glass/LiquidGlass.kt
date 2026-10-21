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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geekify.android.settings.LocalGlassConfig
import com.geekify.android.ui.glass.backdrop.Backdrop
import com.geekify.android.ui.glass.backdrop.BackdropEffectScope
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
 * Geekify Liquid Glass - Ultra-premium physical glass material
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

enum class GlassRole {
    Navigation,
    Floating,
    Preview,
    Overlay,
    Panel
}

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
    val darkRimAlpha: Float = 0.32f,
    /** Logical component role so identical optical presets remain distinguishable for per-surface toggles. */
    val role: GlassRole = GlassRole.Overlay
) {
    companion object {
        private val BaseGlassColor = Color(0xFF171717)

        /** Bottom navigation: crystal-clear liquid glass. */
        val Navigation = GlassStyle(
            blur = 3.dp,
            tint = BaseGlassColor,
            tintAlpha = 0.12f,
            rimAlpha = 0.38f,
            specularAlpha = 0.0f,
            shadowRadius = 8.dp,
            shadowAlpha = 0.15f,
            frost = 0.0f,
            saturation = 1.8f,
            lensHeight = 14.dp,
            lensAmount = 12.dp,
            darkRimAlpha = 0.0f,
            role = GlassRole.Navigation
        )

        /** Mini player and small floating controls: crystal-clear glass. */
        val Floating = GlassStyle(
            blur = 3.dp,
            tint = BaseGlassColor,
            tintAlpha = 0.12f,
            rimAlpha = 0.38f,
            specularAlpha = 0.0f,
            shadowRadius = 8.dp,
            shadowAlpha = 0.15f,
            frost = 0.0f,
            saturation = 1.8f,
            lensHeight = 14.dp,
            lensAmount = 12.dp,
            darkRimAlpha = 0.0f,
            role = GlassRole.Floating
        )

        /** Settings live optical preview capsule. */
        val Preview = GlassStyle(
            blur = 3.dp,
            tint = BaseGlassColor,
            tintAlpha = 0.12f,
            rimAlpha = 0.38f,
            specularAlpha = 0.0f,
            shadowRadius = 8.dp,
            shadowAlpha = 0.15f,
            frost = 0.0f,
            saturation = 1.8f,
            lensHeight = 14.dp,
            lensAmount = 12.dp,
            darkRimAlpha = 0.0f,
            role = GlassRole.Preview
        )

        /** Popups and dialogs: strong glass but readable. */
        val Overlay = GlassStyle(
            blur = 14.dp,
            tint = BaseGlassColor,
            tintAlpha = 0.45f,
            rimAlpha = 0.60f,
            specularAlpha = 0.0f,
            shadowRadius = 24.dp,
            shadowAlpha = 0.45f,
            frost = 0.0f,
            saturation = 1.6f,
            lensHeight = 16.dp,
            lensAmount = 20.dp,
            darkRimAlpha = 0.25f,
            role = GlassRole.Overlay
        )

        /** Large surfaces with lots of text (profile panel, queue). */
        val Panel = GlassStyle(
            blur = 16.dp,
            tint = BaseGlassColor,
            tintAlpha = 0.50f,
            rimAlpha = 0.60f,
            specularAlpha = 0.0f,
            shadowRadius = 24.dp,
            shadowAlpha = 0.45f,
            frost = 0.0f,
            saturation = 1.5f,
            lensHeight = 14.dp,
            lensAmount = 18.dp,
            darkRimAlpha = 0.22f,
            role = GlassRole.Panel
        )
    }
}

/**
 * Turns this surface into Liquid Glass using the full AGSL lens refraction & backdrop pipeline.
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
    val glassConfig = LocalGlassConfig.current

    val isFloatingOrNav = style.role == GlassRole.Navigation ||
        style.role == GlassRole.Floating ||
        style.role == GlassRole.Preview
    val componentGlassEnabled = when (style.role) {
        GlassRole.Navigation -> glassConfig.enabled && glassConfig.navBarGlass
        GlassRole.Floating -> glassConfig.enabled && glassConfig.miniPlayerGlass
        GlassRole.Preview, GlassRole.Overlay, GlassRole.Panel -> glassConfig.enabled
    }

    val effectiveStyle = if (isFloatingOrNav) {
        style.copy(
            blur = glassConfig.blurDp.dp,
            saturation = glassConfig.saturation,
            lensAmount = glassConfig.lensAmountDp.dp,
            lensHeight = glassConfig.lensHeightDp.dp,
            chromaticAberration = glassConfig.chromaticAberration,
            depthEffect = glassConfig.depthEffect,
            tintAlpha = glassConfig.tintAlpha
        )
    } else {
        style.copy(
            chromaticAberration = glassConfig.chromaticAberration,
            depthEffect = glassConfig.depthEffect
        )
    }

    // Check device capability (Android 12+ for RenderEffect)
    val hasRenderEffect = isRenderEffectSupported() && !isLowRamDevice() && componentGlassEnabled

    if (!hasRenderEffect || backdrop == null) {
        // Fallback for unsupported devices or missing backdrop: soft outside shadow + tinted frosted surface
        val shadowPaint = remember(effectiveStyle, density.density) {
            Paint().apply {
                color = Color.Black.copy(alpha = effectiveStyle.shadowAlpha)
                asFrameworkPaint().maskFilter =
                    BlurMaskFilter(with(density) { effectiveStyle.shadowRadius.toPx() }, BlurMaskFilter.Blur.NORMAL)
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
                    canvas.translate(0f, effectiveStyle.shadowRadius.toPx() * 0.35f)
                    canvas.drawOutline(outline, shadowPaint)
                    canvas.restore()
                }
            }
            .clip(shape)
            .background(effectiveStyle.tint.copy(alpha = if (!componentGlassEnabled) 0.88f else if (backdrop == null) 0.35f else (effectiveStyle.tintAlpha + 0.20f).coerceIn(0.15f, 0.40f)))
            .drawWithContent {
                val press = pressProgress().coerceIn(0f, 1f)
                if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
                if (effectiveStyle.frost > 0f) drawRect(Color.White.copy(alpha = effectiveStyle.frost))
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
                    0f to Color.White.copy(alpha = effectiveStyle.rimAlpha + 0.18f * press),
                    0.35f to Color.White.copy(alpha = 0.07f),
                    0.70f to Color.White.copy(alpha = 0.05f),
                    1f to Color.White.copy(alpha = effectiveStyle.rimAlpha * 0.55f),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
                drawOutline(outline, rim, style = Stroke(width = 2.4.dp.toPx()))
            }
    }

    // Supported path: Hardware-accelerated Liquid Glass with AGSL refraction & backdrop sampling
    val blurRadiusPx = with(density) { effectiveStyle.blur.toPx() }
    val lensHeightPx = with(density) { effectiveStyle.lensHeight.toPx() }
    val lensAmountPx = with(density) { effectiveStyle.lensAmount.toPx() }
    val cornerShape = shape as? CornerBasedShape ?: RoundedCornerShape(24.dp)

    val resolutionScale = glassResolutionScale(effectiveStyle.blur.value)

    val effectsBlock: BackdropEffectScope.() -> Unit = remember(
        effectiveStyle,
        blurRadiusPx,
        lensHeightPx,
        lensAmountPx,
        resolutionScale
    ) {
        {
            if (effectiveStyle.saturation != 1f) {
                colorControls(saturation = effectiveStyle.saturation)
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
                    depthEffect = effectiveStyle.depthEffect,
                    chromaticAberration = effectiveStyle.chromaticAberration,
                )
            }
        }
    }

    val highlightBlock: () -> Highlight? = remember(effectiveStyle.rimAlpha, pressProgress) {
        {
            val press = pressProgress().coerceIn(0f, 1f)
            Highlight(
                width = EdgeHighlightWidth,
                style = HighlightStyle.Default(
                    color = Color.White.copy(alpha = (effectiveStyle.rimAlpha * 0.6f + 0.18f * press).coerceIn(0f, 1f)),
                    angle = HighlightAngleFrozen
                )
            )
        }
    }

    val shadowBlock: () -> Shadow? = remember(effectiveStyle.shadowRadius, effectiveStyle.shadowAlpha) {
        {
            Shadow(
                radius = effectiveStyle.shadowRadius,
                color = Color.Black.copy(alpha = effectiveStyle.shadowAlpha)
            )
        }
    }

    val surfaceBlock: DrawScope.() -> Unit = remember(effectiveStyle, dim, pressProgress) {
        {
            val press = pressProgress().coerceIn(0f, 1f)
            if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
            if (effectiveStyle.frost > 0f) drawRect(Color.White.copy(alpha = effectiveStyle.frost))
            if (effectiveStyle.tintAlpha > 0f) {
                drawRect(effectiveStyle.tint.copy(alpha = effectiveStyle.tintAlpha))
            }
            if (press > 0f) drawRect(Color.White.copy(alpha = 0.05f * press))
        }
    }

    val frontBlock: DrawScope.() -> Unit = remember(effectiveStyle.specularAlpha, highlightEnd, pressProgress) {
        {
            if (effectiveStyle.specularAlpha > 0f) {
                val press = pressProgress().coerceIn(0f, 1f)
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = effectiveStyle.specularAlpha + 0.12f * press), Color.Transparent),
                        center = Offset(size.width * 0.20f, size.height * 0.02f),
                        radius = size.maxDimension * 0.55f
                    )
                )
            }

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

    return this
        .drawBackdrop(
            backdrop = backdrop.layerBackdrop,
            shape = { cornerShape },
            effects = effectsBlock,
            highlight = highlightBlock,
            shadow = shadowBlock,
            backdropScale = resolutionScale,
            onDrawSurface = surfaceBlock,
            onDrawFront = frontBlock
        )
        .then(
            if (effectiveStyle.darkRimAlpha > 0f) {
                Modifier.outerDarkRim(
                    shape = shape,
                    width = 1.6.dp,
                    alpha = effectiveStyle.darkRimAlpha,
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
