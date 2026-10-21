package com.geekify.android.ui.glass

import android.app.ActivityManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * User-configurable parameters of the liquid glass effect.
 */
@Stable
data class GlassEffectConfig(
    val globalEnabled: Boolean = true,
    val vibrancy: Float = 2.0f,
    /** Blur in dp applied to glass pills. Nav spec = 3dp. */
    val blurRadius: Float = 3f,
    /** 0..1, mapped to 0..[LENS_MAX_DP] dp of lens refraction height. */
    val lensHeight: Float = 0.60f,
    /** 0..1, mapped to 0..[LENS_MAX_DP] dp of lens refraction amount. */
    val lensAmount: Float = 0.69f,
    val chromaticAberration: Boolean = false,
    val depthEffect: Boolean = false,
    /** [Color.Unspecified] means adaptive: derived from the theme surface. */
    val surfaceTintColor: Color = Color.Unspecified,
    /** Specular rim ("pluck") colour. [Color.Unspecified] keeps the default white. */
    val highlightColor: Color = Color.Unspecified,
    /** Specular rim opacity, 0..1. */
    val highlightOpacity: Float = EdgeHighlightAlpha,
    /** Which rendering style every glass surface uses. */
    val style: GlassRenderStyle = GlassRenderStyle.LIQUID,
    /** Wash painted over selection puck. */
    val puckColor: Color = Color.Unspecified,
    val puckOpacity: Float = 0.8f,
    val surfaceOpacity: Float = 0.5f,
    val textColor: Color = Color.Unspecified,
    val miniPlayerEnabled: Boolean = true,
    val navBarEnabled: Boolean = true,
    val sidePanelEnabled: Boolean = true,
    val sidePanelVibrancy: Float = 1.2f,
    val sidePanelBlurRadius: Float = 2f,
    val sidePanelLensHeight: Float = 0.4f,
    val sidePanelLensAmount: Float = 0.6f,
    val sidePanelColor: Color = Color.Unspecified,
    val sidePanelSurfaceOpacity: Float = 0.75f,
    val sidePanelTextColor: Color = Color.Unspecified,
) {
    fun isEnabledFor(component: GlassComponent): Boolean =
        globalEnabled && when (component) {
            GlassComponent.MINI_PLAYER -> miniPlayerEnabled
            GlassComponent.NAV_BAR -> navBarEnabled
            GlassComponent.SIDE_PANEL -> sidePanelEnabled
        }

    val anyComponentEnabled: Boolean
        get() = globalEnabled
}

/** Rendering modes for glass surfaces. */
enum class GlassRenderStyle {
    /** Blur + saturation + lens refraction + specular rim. The default. */
    LIQUID,

    /** Blur and saturation without lens refraction or an edge rim. */
    BLUR,

    /** Translucent tint without backdrop capture or blur. */
    TRANSPARENT,
}

enum class GlassComponent {
    MINI_PLAYER,
    NAV_BAR,
    SIDE_PANEL,
}

internal const val LENS_MAX_DP = 48f
internal const val MIN_GLASS_RESOLUTION_SCALE = 0.30f
internal const val FULL_QUALITY_BLUR_DP = 2.5f
internal val EdgeHighlightWidth = 0.8f.dp
internal const val EdgeHighlightAlpha = 0.55f
internal const val HighlightAngleFrozen = 45f

fun glassResolutionScale(blurRadiusDp: Float): Float {
    val t = (blurRadiusDp / FULL_QUALITY_BLUR_DP).coerceIn(0f, 1f)
    return 1f - t * (1f - MIN_GLASS_RESOLUTION_SCALE)
}

fun isGlassSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.S

@Composable
fun isLowRamDevice(): Boolean {
    val context = LocalContext.current
    return remember {
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice ?: false
    }
}

@Composable
fun isGlassAllowed(): Boolean = isGlassSupported() && !isLowRamDevice()

fun glassSaturation(vibrancy: Float): Float = 1f + 0.5f * vibrancy.coerceIn(0f, 2f)

fun glassContentColorFor(behind: Color, tint: Color, opacity: Float): Color {
    val effective = if (tint.isSpecified) {
        lerp(behind, tint, opacity.coerceIn(0f, 1f))
    } else {
        behind
    }
    return if (effective.luminance() > 0.5f) Color(0xFF1A1A1A) else Color.White
}

val LocalGlassEffectConfig = staticCompositionLocalOf { GlassEffectConfig() }
