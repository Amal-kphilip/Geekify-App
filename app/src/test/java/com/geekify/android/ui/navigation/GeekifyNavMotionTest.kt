package com.geekify.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeekifyNavMotionTest {

    @Test
    fun `push pop and predictivePop timelines never overlap and stay within 240ms`() {
        val windows = listOf(
            "push" to GeekifyNavMotion.pushWindow,
            "pop" to GeekifyNavMotion.popWindow,
            "predictivePop" to GeekifyNavMotion.predictivePopWindow
        )

        for ((name, window) in windows) {
            assertFalse("$name must not overlap", window.hasOverlap)
            assertTrue(
                "$name totalDurationMs (${window.totalDurationMs}) must be <= 240ms",
                window.totalDurationMs <= 240
            )
            assertTrue(
                "$name enterDelayMs (${window.enterDelayMs}) must be >= exitDurationMs (${window.exitDurationMs})",
                window.enterDelayMs >= window.exitDurationMs
            )

            // Sample every millisecond across the entire timeline to prove zero frame overlap
            for (ms in 0..window.totalDurationMs) {
                val outAlpha = window.outgoingAlphaAt(ms.toFloat())
                val inAlpha = window.incomingAlphaAt(ms.toFloat())
                assertEquals(
                    "$name overlap detected at ${ms}ms (out=$outAlpha, in=$inAlpha)",
                    0f,
                    outAlpha * inAlpha,
                    0.0001f
                )
            }
            assertEquals("$name starts at outgoing=1", 1f, window.outgoingAlphaAt(0f), 0.0001f)
            assertEquals("$name starts at incoming=0", 0f, window.incomingAlphaAt(0f), 0.0001f)
            assertEquals(
                "$name ends at outgoing=0",
                0f,
                window.outgoingAlphaAt(window.totalDurationMs.toFloat()),
                0.0001f
            )
            assertEquals(
                "$name ends at incoming=1",
                1f,
                window.incomingAlphaAt(window.totalDurationMs.toFloat()),
                0.0001f
            )
        }
    }

    @Test
    fun `predictivePop keeps incoming invisible across uncommitted swipe range`() {
        val window = GeekifyNavMotion.predictivePopWindow
        // Typical uncommitted Android back gestures stay well below 80% of total seek
        for (pct in 0..80) {
            val timeMs = (pct / 100f) * window.totalDurationMs
            assertEquals(
                "Incoming must remain invisible at $pct% uncommitted drag",
                0f,
                window.incomingAlphaAt(timeMs),
                0.0001f
            )
        }
        // Commit finishes at 100% with incoming fully visible and outgoing gone
        assertEquals(0f, window.outgoingAlphaAt(window.totalDurationMs.toFloat()), 0.0001f)
        assertEquals(1f, window.incomingAlphaAt(window.totalDurationMs.toFloat()), 0.0001f)
    }

    @Test
    fun `computePredictiveBackTransform returns exact identity at zero and respects swipe edge`() {
        val density = 3f

        assertEquals(
            GeekifyNavMotion.PredictiveBackTransform.Identity,
            GeekifyNavMotion.computePredictiveBackTransform(0f, density, GeekifyNavMotion.SWIPE_EDGE_LEFT)
        )
        assertEquals(
            GeekifyNavMotion.PredictiveBackTransform.Identity,
            GeekifyNavMotion.computePredictiveBackTransform(-0.2f, density, GeekifyNavMotion.SWIPE_EDGE_RIGHT)
        )

        val leftMid = GeekifyNavMotion.computePredictiveBackTransform(
            progress = 0.5f,
            density = density,
            swipeEdge = GeekifyNavMotion.SWIPE_EDGE_LEFT
        )
        assertEquals(0.5f * GeekifyNavMotion.PREDICTIVE_MAX_SLIDE_DP * density, leftMid.translationXPx, 0.01f)
        assertEquals(0.98f, leftMid.scale, 0.001f)
        assertEquals(1f - 0.5f * GeekifyNavMotion.PREDICTIVE_ALPHA_DROP, leftMid.alpha, 0.001f)

        val rightFull = GeekifyNavMotion.computePredictiveBackTransform(
            progress = 1.5f, // clamped to 1f
            density = density,
            swipeEdge = GeekifyNavMotion.SWIPE_EDGE_RIGHT
        )
        assertEquals(-GeekifyNavMotion.PREDICTIVE_MAX_SLIDE_DP * density, rightFull.translationXPx, 0.01f)
        assertEquals(GeekifyNavMotion.PREDICTIVE_MIN_SCALE, rightFull.scale, 0.001f)
        assertEquals(1f - GeekifyNavMotion.PREDICTIVE_ALPHA_DROP, rightFull.alpha, 0.001f)
    }
}
