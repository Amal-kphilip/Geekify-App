package com.geekify.android.ui.player

import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationAndLyricsTransitionTest {

    @Test
    fun `computeSharedTransform returns identity at progress zero`() {
        val src = Rect(80f, 180f, 380f, 480f)
        val dst = Rect(18f, 44f, 62f, 88f)
        val tf = computeSharedTransform(src, dst, progress = 0f)
        assertEquals(SharedBoundsTransform.Identity, tf)
    }

    @Test
    fun `computeSharedTransform maps expanded artwork cleanly onto lyrics header thumbnail`() {
        val expandedArt = Rect(left = 80f, top = 180f, right = 380f, bottom = 480f) // 300x300, center (230, 330)
        val lyricsThumb = Rect(left = 18f, top = 44f, right = 62f, bottom = 88f)     // 44x44, center (40, 66)

        val mid = computeSharedTransform(expandedArt, lyricsThumb, progress = 0.5f)
        assertEquals(-95f, mid.translationX, 0.01f)
        assertEquals(-132f, mid.translationY, 0.01f)
        val expectedMidScale = 1f + (44f / 300f - 1f) * 0.5f
        assertEquals(expectedMidScale, mid.scaleX, 0.001f)
        assertEquals(expectedMidScale, mid.scaleY, 0.001f)

        val end = computeSharedTransform(expandedArt, lyricsThumb, progress = 1f)
        assertEquals(-190f, end.translationX, 0.01f)
        assertEquals(-264f, end.translationY, 0.01f)
        assertEquals(44f / 300f, end.scaleX, 0.001f)
        assertEquals(44f / 300f, end.scaleY, 0.001f)
    }

    @Test
    fun `computeSharedTransform uses fallback when destination bounds are not yet measured`() {
        val expandedArt = Rect(80f, 180f, 380f, 480f)
        val tf = computeSharedTransform(
            sourceBounds = expandedArt,
            destBounds = Rect.Zero,
            progress = 1f,
            fallbackScale = 0.16f,
            fallbackOffsetY = -220f
        )
        assertEquals(0f, tf.translationX, 0.01f)
        assertEquals(-220f, tf.translationY, 0.01f)
        assertEquals(0.16f, tf.scaleX, 0.001f)
        assertEquals(0.16f, tf.scaleY, 0.001f)
    }

    @Test
    fun `lyricsTransitionState starts closed and responds cleanly to predictive back and snapClosed`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val state = LyricsTransitionState(scope)

        assertEquals(0f, state.progress, 0.0001f)
        assertFalse(state.targetOpen)
        assertFalse(state.isVisible)
        assertTrue(state.isSettledClosed)
        assertFalse(state.isSettledOpen)

        // Predictive back drag scrubs smoothly with damped curve: 1f - 0.35f * 0.72f = 0.748f
        state.onPredictiveBackDrag(0.35f)
        scope.advanceUntilIdle()
        assertEquals(0.748f, state.progress, 0.001f)
        assertTrue(state.isVisible)
        assertFalse(state.isSettledClosed)
        assertFalse(state.isSettledOpen)

        // Snap closed resets immediately (used when Mini-Player collapse starts)
        state.snapClosed()
        scope.advanceUntilIdle()
        assertEquals(0f, state.progress, 0.0001f)
        assertFalse(state.targetOpen)
        assertFalse(state.isVisible)
        assertTrue(state.isSettledClosed)
    }

    @Test
    fun `playerTransitionState and lyricsTransitionState remain completely independent`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val playerState = PlayerTransitionState(scope = scope, initialProgress = 0f)
        val lyricsState = LyricsTransitionState(scope = scope)

        assertTrue(playerState.isFullyExpanded)
        assertTrue(lyricsState.isSettledClosed)

        lyricsState.open(reducedMotion = true)
        scope.advanceUntilIdle()
        assertTrue(lyricsState.isSettledOpen)
        assertTrue(playerState.isFullyExpanded)

        playerState.snapCollapsed()
        scope.advanceUntilIdle()
        assertTrue(playerState.isFullyCollapsed)
        // Caller explicitly calls lyricsState.snapClosed() when playerState collapses
        lyricsState.snapClosed()
        scope.advanceUntilIdle()
        assertTrue(lyricsState.isSettledClosed)
    }
}
