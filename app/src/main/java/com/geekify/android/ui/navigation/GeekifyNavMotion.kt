/*
 * Geekify Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.geekify.android.ui.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Immutable

/**
 * Single source of truth for application-wide navigation and subpage motion.
 *
 * Design invariant (Option A — Strict Non-Overlapping Staggered Timeline):
 * Because Geekify's [NavHost] sits above a single persistent `AsciiBackgroundCanvas` and
 * destinations render on `Color.Transparent` when ASCII is enabled, outgoing and incoming
 * destinations must NEVER both have non-zero alpha on the same frame.
 *
 * - Push:           outgoing clears in 0..100ms; incoming enters in 105..230ms (230ms total).
 * - Normal pop:     outgoing clears in 0..110ms; incoming enters in 110..230ms (230ms total).
 * - Predictive pop: outgoing clears in 0..190ms; incoming enters in 195..240ms (240ms total),
 *                   keeping the incoming destination strictly invisible (`alpha = 0f`) across
 *                   the uncommitted swipe range so only the untouched ASCII background is visible
 *                   behind the outgoing page. Canceling reverses smoothly back to 0 with no snap.
 */
object GeekifyNavMotion {

    // ---- Shared Timeline Constants (ms) ----
    const val PUSH_EXIT_DURATION_MS = 100
    const val PUSH_ENTER_DELAY_MS = 105
    const val PUSH_ENTER_DURATION_MS = 125

    const val POP_EXIT_DURATION_MS = 110
    const val POP_ENTER_DELAY_MS = 110
    const val POP_ENTER_DURATION_MS = 120

    const val PREDICTIVE_POP_EXIT_DURATION_MS = 190
    const val PREDICTIVE_POP_ENTER_DELAY_MS = 195
    const val PREDICTIVE_POP_ENTER_DURATION_MS = 45

    const val GESTURE_COMMIT_ENTER_DURATION_MS = 90
    const val GESTURE_CANCEL_RETURN_DURATION_MS = 140

    // ---- Shared Spatial Constants ----
    const val SLIDE_FRACTION = 0.08f
    const val POP_TARGET_SCALE = 0.96f
    const val PREDICTIVE_MAX_SLIDE_DP = 40f
    const val PREDICTIVE_MIN_SCALE = 0.96f
    const val PREDICTIVE_ALPHA_DROP = 0.28f

    /** Matches `BackEventCompat.EDGE_LEFT` (0) and `BackEventCompat.EDGE_RIGHT` (1). */
    const val SWIPE_EDGE_LEFT = 0
    const val SWIPE_EDGE_RIGHT = 1

    @Immutable
    data class NavPhaseWindow(
        val exitDurationMs: Int,
        val enterDelayMs: Int,
        val enterDurationMs: Int
    ) {
        val totalDurationMs: Int get() = enterDelayMs + enterDurationMs
        val hasOverlap: Boolean get() = enterDelayMs < exitDurationMs

        /** Normalized outgoing alpha at [timeMs] along `[0, totalDurationMs]`. */
        fun outgoingAlphaAt(timeMs: Float): Float {
            if (exitDurationMs <= 0) return 0f
            return (1f - (timeMs / exitDurationMs.toFloat())).coerceIn(0f, 1f)
        }

        /** Normalized incoming alpha at [timeMs] along `[0, totalDurationMs]`. */
        fun incomingAlphaAt(timeMs: Float): Float {
            if (timeMs <= enterDelayMs.toFloat()) return 0f
            if (enterDurationMs <= 0) return 1f
            return ((timeMs - enterDelayMs.toFloat()) / enterDurationMs.toFloat()).coerceIn(0f, 1f)
        }
    }

    @Immutable
    data class PredictiveBackTransform(
        val translationXPx: Float,
        val scale: Float,
        val alpha: Float
    ) {
        companion object {
            val Identity = PredictiveBackTransform(translationXPx = 0f, scale = 1f, alpha = 1f)
        }
    }

    val pushWindow = NavPhaseWindow(
        exitDurationMs = PUSH_EXIT_DURATION_MS,
        enterDelayMs = PUSH_ENTER_DELAY_MS,
        enterDurationMs = PUSH_ENTER_DURATION_MS
    )

    val popWindow = NavPhaseWindow(
        exitDurationMs = POP_EXIT_DURATION_MS,
        enterDelayMs = POP_ENTER_DELAY_MS,
        enterDurationMs = POP_ENTER_DURATION_MS
    )

    val predictivePopWindow = NavPhaseWindow(
        exitDurationMs = PREDICTIVE_POP_EXIT_DURATION_MS,
        enterDelayMs = PREDICTIVE_POP_ENTER_DELAY_MS,
        enterDurationMs = PREDICTIVE_POP_ENTER_DURATION_MS
    )

    /**
     * Pure predictive-back transform shared by in-screen `PredictiveBackHandler`s
     * (Settings subpages and Personalization Studio categories) so they match `NavHost`.
     */
    fun computePredictiveBackTransform(
        progress: Float,
        density: Float,
        swipeEdge: Int = SWIPE_EDGE_LEFT
    ): PredictiveBackTransform {
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0f) return PredictiveBackTransform.Identity
        val direction = if (swipeEdge == SWIPE_EDGE_RIGHT) -1f else 1f
        return PredictiveBackTransform(
            translationXPx = direction * p * PREDICTIVE_MAX_SLIDE_DP * density,
            scale = 1f - (1f - PREDICTIVE_MIN_SCALE) * p,
            alpha = (1f - PREDICTIVE_ALPHA_DROP * p).coerceIn(0f, 1f)
        )
    }

    // ---- NavHost Transitions ----

    fun enterTransition(): EnterTransition =
        fadeIn(
            tween(
                durationMillis = PUSH_ENTER_DURATION_MS,
                delayMillis = PUSH_ENTER_DELAY_MS,
                easing = FastOutSlowInEasing
            )
        ) + slideInHorizontally(
            tween(
                durationMillis = PUSH_ENTER_DURATION_MS,
                delayMillis = PUSH_ENTER_DELAY_MS,
                easing = FastOutSlowInEasing
            )
        ) { fullWidth -> (fullWidth * SLIDE_FRACTION).toInt() }

    fun exitTransition(): ExitTransition =
        fadeOut(
            tween(
                durationMillis = PUSH_EXIT_DURATION_MS,
                easing = FastOutSlowInEasing
            )
        )

    fun popEnterTransition(): EnterTransition =
        fadeIn(
            tween(
                durationMillis = POP_ENTER_DURATION_MS,
                delayMillis = POP_ENTER_DELAY_MS,
                easing = FastOutSlowInEasing
            )
        )

    fun popExitTransition(): ExitTransition =
        slideOutHorizontally(
            tween(
                durationMillis = POP_EXIT_DURATION_MS,
                easing = FastOutSlowInEasing
            )
        ) { fullWidth -> (fullWidth * SLIDE_FRACTION).toInt() } +
            scaleOut(
                animationSpec = tween(
                    durationMillis = POP_EXIT_DURATION_MS,
                    easing = FastOutSlowInEasing
                ),
                targetScale = POP_TARGET_SCALE
            ) +
            fadeOut(
                tween(
                    durationMillis = POP_EXIT_DURATION_MS,
                    easing = FastOutSlowInEasing
                )
            )

    fun predictivePopEnterTransition(@Suppress("UNUSED_PARAMETER") swipeEdge: Int): EnterTransition =
        fadeIn(
            tween(
                durationMillis = PREDICTIVE_POP_ENTER_DURATION_MS,
                delayMillis = PREDICTIVE_POP_ENTER_DELAY_MS,
                easing = FastOutSlowInEasing
            )
        )

    fun predictivePopExitTransition(swipeEdge: Int): ExitTransition {
        val direction = if (swipeEdge == SWIPE_EDGE_RIGHT) -1 else 1
        return slideOutHorizontally(
            tween(
                durationMillis = PREDICTIVE_POP_EXIT_DURATION_MS,
                easing = FastOutSlowInEasing
            )
        ) { fullWidth -> (fullWidth * SLIDE_FRACTION * direction).toInt() } +
            scaleOut(
                animationSpec = tween(
                    durationMillis = PREDICTIVE_POP_EXIT_DURATION_MS,
                    easing = FastOutSlowInEasing
                ),
                targetScale = POP_TARGET_SCALE
            ) +
            fadeOut(
                tween(
                    durationMillis = PREDICTIVE_POP_EXIT_DURATION_MS,
                    easing = FastOutSlowInEasing
                )
            )
    }

    // ---- In-Screen Subpage AnimatedContent Transition (Settings & Personalization) ----

    fun subPageTransition(
        isForward: Boolean,
        lastBackWasGesture: Boolean
    ): ContentTransform = when {
        isForward -> ContentTransform(
            targetContentEnter = enterTransition(),
            initialContentExit = exitTransition(),
            sizeTransform = null
        )
        lastBackWasGesture -> ContentTransform(
            targetContentEnter = fadeIn(
                tween(
                    durationMillis = GESTURE_COMMIT_ENTER_DURATION_MS,
                    easing = FastOutSlowInEasing
                )
            ),
            initialContentExit = ExitTransition.None,
            sizeTransform = null
        )
        else -> ContentTransform(
            targetContentEnter = popEnterTransition(),
            initialContentExit = popExitTransition(),
            sizeTransform = null
        )
    }
}
