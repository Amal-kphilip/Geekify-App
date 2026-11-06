package com.geekify.android.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Discrete conceptual states for the unified Now Playing <-> Mini-Player sheet.
 */
enum class PlayerSheetState {
    EXPANDED,
    COLLAPSING,
    COLLAPSED,
    EXPANDING
}

/**
 * Single source of truth for the Now Playing <-> Mini-Player shared transformation.
 *
 * Normalized [progress]:
 * - `0.0f` = Fully expanded ([PlayerSheetState.EXPANDED])
 * - `1.0f` = Fully collapsed ([PlayerSheetState.COLLAPSED])
 *
 * All visual elements (container surface, shared album artwork, title/artist typography,
 * playback controls, and navigation bar chrome) derive their transformations from [progress]
 * and the dynamically measured root-space layout bounds below.
 */
@Stable
class PlayerTransitionState(
    private val scope: CoroutineScope,
    initialProgress: Float = 1f
) {
    private val progressAnim = Animatable(initialProgress.coerceIn(0f, 1f))

    /** Current normalized collapse progress in `0.0f..1.0f` (`0f` = Expanded, `1f` = Collapsed). */
    val progress: Float
        get() = progressAnim.value.coerceIn(0f, 1f)

    /** Expansion fraction in `0.0f..1.0f` (`1f` = Expanded, `0f` = Collapsed). */
    val expandFraction: Float
        get() = (1f - progress).coerceIn(0f, 1f)

    var isDragging by mutableStateOf(false)
        private set

    var targetExpanded by mutableStateOf(initialProgress < 0.5f)
        private set

    val sheetState: PlayerSheetState by derivedStateOf {
        val p = progress
        when {
            !isDragging && !progressAnim.isRunning && p <= 0.001f -> PlayerSheetState.EXPANDED
            !isDragging && !progressAnim.isRunning && p >= 0.999f -> PlayerSheetState.COLLAPSED
            targetExpanded -> PlayerSheetState.EXPANDING
            else -> PlayerSheetState.COLLAPSING
        }
    }

    val isFullyExpanded: Boolean by derivedStateOf {
        sheetState == PlayerSheetState.EXPANDED
    }

    val isFullyCollapsed: Boolean by derivedStateOf {
        sheetState == PlayerSheetState.COLLAPSED
    }

    val isExpandedOrTransitioning: Boolean by derivedStateOf {
        progress < 0.999f || isDragging
    }

    // ---- Measured Root-Space Layout Bounds ----
    var rootBounds by mutableStateOf(Rect.Zero)
    var miniIsInline by mutableStateOf(false)

    var miniContainerBounds by mutableStateOf(Rect.Zero)
    var miniArtBounds by mutableStateOf(Rect.Zero)
    var miniTextBounds by mutableStateOf(Rect.Zero)
    var miniTitleBounds by mutableStateOf(Rect.Zero)
    var miniArtistBounds by mutableStateOf(Rect.Zero)
    var miniPlayBounds by mutableStateOf(Rect.Zero)
    var miniNextBounds by mutableStateOf(Rect.Zero)

    var expandedArtBounds by mutableStateOf(Rect.Zero)
    var expandedTextBounds by mutableStateOf(Rect.Zero)
    var expandedTitleBounds by mutableStateOf(Rect.Zero)
    var expandedArtistBounds by mutableStateOf(Rect.Zero)
    var expandedPlayBounds by mutableStateOf(Rect.Zero)
    var expandedNextBounds by mutableStateOf(Rect.Zero)

    /**
     * Effective drag range in pixels between full screen and the mini-player destination.
     */
    fun maxDragDistancePx(fallbackHeightPx: Float): Float {
        val rootH = if (rootBounds.height > 0f) rootBounds.height else fallbackHeightPx
        val miniTop = if (miniContainerBounds.height > 0f) miniContainerBounds.top else rootH * 0.82f
        return (miniTop * 0.78f).coerceAtLeast(280f)
    }

    /**
     * Resolve the mini-player container rectangle in local coordinates of [fullRect],
     * providing a deterministic fallback if layout measurement has not yet occurred.
     */
    fun resolvedMiniContainerInLocal(fullRect: Rect, density: Density): Rect {
        if (miniContainerBounds.width > 0f && miniContainerBounds.height > 0f) {
            return miniContainerBounds.translate(-rootBounds.left, -rootBounds.top)
        }
        val padH = with(density) { 16.dp.toPx() }
        val bottomOffset = with(density) { 96.dp.toPx() }
        val height = with(density) { (if (miniIsInline) 56.dp else 64.dp).toPx() }
        val top = (fullRect.height - bottomOffset - height).coerceAtLeast(0f)
        return Rect(
            left = padH,
            top = top,
            right = (fullRect.width - padH).coerceAtLeast(padH + 1f),
            bottom = top + height
        )
    }

    /**
     * Directly manipulate transition progress from a user drag gesture.
     */
    fun onDrag(newProgress: Float) {
        val clamped = newProgress.coerceIn(0f, 1f)
        targetExpanded = clamped < progress
        isDragging = true
        scope.launch {
            progressAnim.snapTo(clamped)
        }
    }

    /**
     * Incrementally update transition progress from a vertical drag delta (in pixels).
     * Positive `deltaPx` moves downward (towards collapsed = `1f`);
     * negative `deltaPx` moves upward (towards expanded = `0f`).
     */
    fun onDragDelta(deltaPx: Float, fallbackScreenHeightPx: Float) {
        val range = maxDragDistancePx(fallbackScreenHeightPx)
        val next = (progress + (deltaPx / range)).coerceIn(0f, 1f)
        targetExpanded = deltaPx < 0f
        isDragging = true
        scope.launch {
            progressAnim.snapTo(next)
        }
    }

    /**
     * Settle an interactive swipe-down collapse gesture started from the Expanded Player.
     *
     * Combines normalized progress (`>= 35%`), downward displacement (`>= 120dp`),
     * and release velocity (`>= 900dp/s`).
     */
    fun settleCollapseGesture(
        totalDragDownPx: Float,
        velocityPxPerSec: Float,
        density: Density,
        fallbackScreenHeightPx: Float
    ) {
        isDragging = false
        val dragDp = totalDragDownPx / density.density
        val velocityDpPerSec = velocityPxPerSec / density.density
        val normalizedVelocity = velocityPxPerSec / maxDragDistancePx(fallbackScreenHeightPx)

        val shouldCollapse = velocityDpPerSec >= COLLAPSE_VELOCITY_THRESHOLD_DP_PER_SEC ||
            ((progress >= COLLAPSE_PROGRESS_THRESHOLD || dragDp >= COLLAPSE_DISTANCE_THRESHOLD_DP) &&
                velocityDpPerSec > -350f)

        if (shouldCollapse) {
            collapse(initialVelocity = normalizedVelocity)
        } else {
            expand(initialVelocity = normalizedVelocity)
        }
    }

    /**
     * Settle an interactive swipe-up expansion gesture started from the Mini Player.
     */
    fun settleExpandGesture(
        totalDragUpPx: Float,
        velocityPxPerSec: Float,
        density: Density,
        fallbackScreenHeightPx: Float
    ) {
        isDragging = false
        val dragUpDp = totalDragUpPx / density.density
        val upwardVelocityDpPerSec = -velocityPxPerSec / density.density
        val normalizedVelocity = velocityPxPerSec / maxDragDistancePx(fallbackScreenHeightPx)
        val expandedAmount = 1f - progress

        val shouldExpand = upwardVelocityDpPerSec >= EXPAND_VELOCITY_THRESHOLD_DP_PER_SEC ||
            ((expandedAmount >= EXPAND_PROGRESS_THRESHOLD || dragUpDp >= EXPAND_DISTANCE_THRESHOLD_DP) &&
                upwardVelocityDpPerSec > -300f)

        if (shouldExpand) {
            expand(initialVelocity = normalizedVelocity)
        } else {
            collapse(initialVelocity = normalizedVelocity)
        }
    }

    /**
     * Smoothly animate to the fully expanded Now Playing state (`progress = 0.0f`).
     */
    fun expand(initialVelocity: Float = 0f, reducedMotion: Boolean = false) {
        isDragging = false
        targetExpanded = true
        scope.launch {
            progressAnim.animateTo(
                targetValue = 0f,
                initialVelocity = initialVelocity,
                animationSpec = if (reducedMotion) {
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 750f)
                } else {
                    spring(dampingRatio = 0.88f, stiffness = 420f)
                }
            )
        }
    }

    /**
     * Smoothly animate to the compact Mini Player state (`progress = 1.0f`).
     */
    fun collapse(initialVelocity: Float = 0f, reducedMotion: Boolean = false) {
        isDragging = false
        targetExpanded = false
        scope.launch {
            progressAnim.animateTo(
                targetValue = 1f,
                initialVelocity = initialVelocity,
                animationSpec = if (reducedMotion) {
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 750f)
                } else {
                    spring(dampingRatio = 0.88f, stiffness = 420f)
                }
            )
        }
    }

    /**
     * Immediately snap to collapsed state (used only when the playback queue is cleared).
     */
    fun snapCollapsed() {
        isDragging = false
        targetExpanded = false
        scope.launch {
            progressAnim.snapTo(1f)
        }
    }

    companion object {
        const val COLLAPSE_PROGRESS_THRESHOLD = 0.35f
        const val COLLAPSE_DISTANCE_THRESHOLD_DP = 120f
        const val COLLAPSE_VELOCITY_THRESHOLD_DP_PER_SEC = 900f

        const val EXPAND_PROGRESS_THRESHOLD = 0.25f
        const val EXPAND_DISTANCE_THRESHOLD_DP = 88f
        const val EXPAND_VELOCITY_THRESHOLD_DP_PER_SEC = 800f
    }
}

@Composable
fun rememberPlayerTransitionState(
    initialExpanded: Boolean = false
): PlayerTransitionState {
    val scope = rememberCoroutineScope()
    return remember {
        PlayerTransitionState(
            scope = scope,
            initialProgress = if (initialExpanded) 0f else 1f
        )
    }
}

/**
 * Compute center-to-center translation and uniform scale to morph an element laid out at
 * [sourceBounds] into [destBounds] at normalized progress [progress] (`0f..1f`).
 */
internal data class SharedBoundsTransform(
    val translationX: Float,
    val translationY: Float,
    val scaleX: Float,
    val scaleY: Float
) {
    companion object {
        val Identity = SharedBoundsTransform(0f, 0f, 1f, 1f)
    }
}

internal fun computeSharedTransform(
    sourceBounds: Rect,
    destBounds: Rect,
    progress: Float,
    fallbackScale: Float = 1f,
    fallbackOffsetY: Float = 0f
): SharedBoundsTransform {
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0.0001f) return SharedBoundsTransform.Identity
    if (sourceBounds.width <= 0f || sourceBounds.height <= 0f ||
        destBounds.width <= 0f || destBounds.height <= 0f
    ) {
        val s = androidx.compose.ui.util.lerp(1f, fallbackScale, p)
        return SharedBoundsTransform(
            translationX = 0f,
            translationY = fallbackOffsetY * p,
            scaleX = s,
            scaleY = s
        )
    }

    val dx = (destBounds.center.x - sourceBounds.center.x) * p
    val dy = (destBounds.center.y - sourceBounds.center.y) * p
    val targetScaleX = destBounds.width / sourceBounds.width
    val targetScaleY = destBounds.height / sourceBounds.height
    val uniformScale = androidx.compose.ui.util.lerp(1f, minOf(targetScaleX, targetScaleY), p)
    return SharedBoundsTransform(
        translationX = dx,
        translationY = dy,
        scaleX = uniformScale,
        scaleY = uniformScale
    )
}
