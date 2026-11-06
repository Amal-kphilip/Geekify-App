package com.geekify.android.ui.player

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerTransitionStateTest {

    private val density = Density(density = 3f, fontScale = 1f)

    private class SteppingFrameClock : MonotonicFrameClock {
        private var frameTimeNanos = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            frameTimeNanos += 16_000_000L // 16ms per frame (~60fps)
            return onFrame(frameTimeNanos)
        }
    }

    private fun TestScope.scopeWithFrameClock(): CoroutineScope =
        CoroutineScope(coroutineContext + SteppingFrameClock())

    @Test
    fun `initial collapsed and expanded states are consistent`() = runTest {
        val scope = scopeWithFrameClock()
        val collapsedState = PlayerTransitionState(scope, initialProgress = 1f)
        assertEquals(1f, collapsedState.progress, 0.0001f)
        assertEquals(0f, collapsedState.expandFraction, 0.0001f)
        assertEquals(PlayerSheetState.COLLAPSED, collapsedState.sheetState)
        assertTrue(collapsedState.isFullyCollapsed)
        assertFalse(collapsedState.isFullyExpanded)

        val expandedState = PlayerTransitionState(scope, initialProgress = 0f)
        assertEquals(0f, expandedState.progress, 0.0001f)
        assertEquals(1f, expandedState.expandFraction, 0.0001f)
        assertEquals(PlayerSheetState.EXPANDED, expandedState.sheetState)
        assertTrue(expandedState.isFullyExpanded)
        assertFalse(expandedState.isFullyCollapsed)
    }

    @Test
    fun `computeSharedTransform interpolates exact source to destination bounds`() {
        val expandedArt = Rect(left = 120f, top = 300f, right = 960f, bottom = 1140f) // 840x840, center=(540, 720)
        val miniArt = Rect(left = 78f, top = 1980f, right = 216f, bottom = 2118f) // 138x138, center=(147, 2049)

        val atZero = computeSharedTransform(expandedArt, miniArt, progress = 0f)
        assertEquals(0f, atZero.translationX, 0.001f)
        assertEquals(0f, atZero.translationY, 0.001f)
        assertEquals(1f, atZero.scaleX, 0.001f)
        assertEquals(1f, atZero.scaleY, 0.001f)

        val atHalf = computeSharedTransform(expandedArt, miniArt, progress = 0.5f)
        assertEquals((147f - 540f) * 0.5f, atHalf.translationX, 0.01f)
        assertEquals((2049f - 720f) * 0.5f, atHalf.translationY, 0.01f)
        val expectedHalfScale = (1f + (138f / 840f)) * 0.5f
        assertEquals(expectedHalfScale, atHalf.scaleX, 0.001f)

        val atOne = computeSharedTransform(expandedArt, miniArt, progress = 1f)
        assertEquals(147f - 540f, atOne.translationX, 0.01f)
        assertEquals(2049f - 720f, atOne.translationY, 0.01f)
        assertEquals(138f / 840f, atOne.scaleX, 0.001f)
        assertEquals(138f / 840f, atOne.scaleY, 0.001f)
    }

    @Test
    fun `small downward drag cancels and springs back to expanded`() = runTest {
        val state = PlayerTransitionState(scopeWithFrameClock(), initialProgress = 0f)
        state.rootBounds = Rect(0f, 0f, 1080f, 2400f)
        state.miniContainerBounds = Rect(48f, 1950f, 1032f, 2142f)

        // Drag down by 12% (< 35% threshold and < 120dp)
        state.onDrag(0.12f)
        advanceUntilIdle()
        assertTrue(state.isDragging)
        assertEquals(0.12f, state.progress, 0.001f)

        state.settleCollapseGesture(
            totalDragDownPx = 60f * density.density, // 60dp
            velocityPxPerSec = 100f * density.density, // 100dp/s
            density = density,
            fallbackScreenHeightPx = 2400f
        )
        advanceUntilIdle()
        assertEquals(0f, state.progress, 0.001f)
        assertEquals(PlayerSheetState.EXPANDED, state.sheetState)
    }

    @Test
    fun `long downward drag or fast fling completes collapse to mini player`() = runTest {
        val state = PlayerTransitionState(scopeWithFrameClock(), initialProgress = 0f)
        state.rootBounds = Rect(0f, 0f, 1080f, 2400f)
        state.miniContainerBounds = Rect(48f, 1950f, 1032f, 2142f)

        // 1. Distance/progress threshold collapse
        state.onDrag(0.42f)
        advanceUntilIdle()
        state.settleCollapseGesture(
            totalDragDownPx = 150f * density.density,
            velocityPxPerSec = 200f * density.density,
            density = density,
            fallbackScreenHeightPx = 2400f
        )
        advanceUntilIdle()
        assertEquals(1f, state.progress, 0.001f)
        assertEquals(PlayerSheetState.COLLAPSED, state.sheetState)

        // 2. Fast downward fling from small drag progress
        state.expand(reducedMotion = true)
        advanceUntilIdle()
        assertEquals(0f, state.progress, 0.001f)

        state.onDrag(0.08f)
        advanceUntilIdle()
        state.settleCollapseGesture(
            totalDragDownPx = 35f * density.density,
            velocityPxPerSec = 1150f * density.density, // >= 900dp/s fling
            density = density,
            fallbackScreenHeightPx = 2400f
        )
        advanceUntilIdle()
        assertEquals(1f, state.progress, 0.001f)
        assertEquals(PlayerSheetState.COLLAPSED, state.sheetState)
    }

    @Test
    fun `swipe up from mini player expands when threshold or velocity met`() = runTest {
        val state = PlayerTransitionState(scopeWithFrameClock(), initialProgress = 1f)
        state.rootBounds = Rect(0f, 0f, 1080f, 2400f)
        state.miniContainerBounds = Rect(48f, 1950f, 1032f, 2142f)

        // Small upward drag that should cancel back to collapsed
        state.onDrag(0.92f)
        advanceUntilIdle()
        state.settleExpandGesture(
            totalDragUpPx = 30f * density.density,
            velocityPxPerSec = -120f * density.density,
            density = density,
            fallbackScreenHeightPx = 2400f
        )
        advanceUntilIdle()
        assertEquals(1f, state.progress, 0.001f)
        assertEquals(PlayerSheetState.COLLAPSED, state.sheetState)

        // Sufficient upward drag expands to 0f
        state.onDrag(0.68f)
        advanceUntilIdle()
        state.settleExpandGesture(
            totalDragUpPx = 120f * density.density,
            velocityPxPerSec = -400f * density.density,
            density = density,
            fallbackScreenHeightPx = 2400f
        )
        advanceUntilIdle()
        assertEquals(0f, state.progress, 0.001f)
        assertEquals(PlayerSheetState.EXPANDED, state.sheetState)
    }
}
