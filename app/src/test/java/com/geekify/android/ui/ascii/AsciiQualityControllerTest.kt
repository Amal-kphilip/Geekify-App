package com.geekify.android.ui.ascii

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsciiQualityControllerTest {

    @Test
    fun `fast device stays at max quality without stepping down`() {
        var currentTimeNanos = 1_000_000_000L
        val controller = AsciiQualityController(isLowRamDevice = false) { currentTimeNanos }

        assertEquals(AsciiQualityController.MAX_FPS, controller.targetFps)
        assertEquals(0f, controller.extraCw)

        // Feed 100 frames with low cost (5ms << 60% of 33.3ms)
        for (i in 0 until 100) {
            currentTimeNanos += 33_000_000L
            controller.recordFrameCost(5.0f)
        }

        assertEquals(AsciiQualityController.MAX_FPS, controller.targetFps)
        assertEquals(0f, controller.extraCw)
        assertTrue("EMA should be close to 5ms", controller.frameCostEmaMs in 4.9f..5.1f)
    }

    @Test
    fun `slow device progressively steps down when under pressure`() {
        var currentTimeNanos = 1_000_000_000L
        val controller = AsciiQualityController(isLowRamDevice = false) { currentTimeNanos }

        assertEquals(AsciiQualityController.MAX_FPS, controller.targetFps) // 30
        assertEquals(0f, controller.extraCw) // 0

        // Step 1: Feed heavy frame -> steps down to Tier 1 (extraCw 1, fps 30)
        currentTimeNanos += 35_000_000L
        controller.recordFrameCost(35.0f)
        assertEquals(1f, controller.extraCw)
        assertEquals(AsciiQualityController.MAX_FPS, controller.targetFps)

        // Advance 1.1s past cooldown and feed heavy frame -> steps down to Tier 2 (extraCw 1, fps 24)
        currentTimeNanos += 1_100_000_000L
        controller.recordFrameCost(35.0f)
        assertEquals(1f, controller.extraCw)
        assertEquals(AsciiQualityController.MID_FPS, controller.targetFps)

        // Advance 1.1s past cooldown and feed heavy frame -> steps down to Tier 3 (extraCw 2, fps 24)
        currentTimeNanos += 1_100_000_000L
        controller.recordFrameCost(35.0f)
        assertEquals(2f, controller.extraCw)
        assertEquals(AsciiQualityController.MID_FPS, controller.targetFps)

        // Advance 1.1s past cooldown and feed heavy frame -> steps down to Tier 4 (extraCw 2, fps 20)
        currentTimeNanos += 1_100_000_000L
        controller.recordFrameCost(35.0f)
        assertEquals(2f, controller.extraCw)
        assertEquals(AsciiQualityController.MIN_FPS, controller.targetFps)

        // Already at lowest tier, should stay at Tier 4
        currentTimeNanos += 1_100_000_000L
        controller.recordFrameCost(35.0f)
        assertEquals(2f, controller.extraCw)
        assertEquals(AsciiQualityController.MIN_FPS, controller.targetFps)
    }

    @Test
    fun `hysteresis deadband prevents flapping between 40 percent and 60 percent interval`() {
        var currentTimeNanos = 1_000_000_000L
        val controller = AsciiQualityController(isLowRamDevice = false) { currentTimeNanos }

        // Interval at 30 fps is 33.3ms.
        // 40% = 13.3ms, 60% = 20.0ms.
        // Feed frames at 16.5ms (inside deadband) for 10 seconds.
        for (i in 0 until 300) {
            currentTimeNanos += 33_000_000L
            controller.recordFrameCost(16.5f)
        }

        // Must stay exactly at 30 fps and 0 extraCw, no flapping
        assertEquals(AsciiQualityController.MAX_FPS, controller.targetFps)
        assertEquals(0f, controller.extraCw)
    }

    @Test
    fun `recovers quality after sustained headroom of 5 seconds`() {
        var currentTimeNanos = 1_000_000_000L
        val controller = AsciiQualityController(isLowRamDevice = false) { currentTimeNanos }

        // Drive controller down to lowest tier (Tier 4) with 4 step-downs
        for (step in 0 until 4) {
            currentTimeNanos += 1_100_000_000L
            controller.recordFrameCost(50.0f)
        }
        assertEquals(AsciiQualityController.MIN_FPS, controller.targetFps) // 20
        assertEquals(2f, controller.extraCw)

        // Reset EMA to clean low cost so headroom timer starts at exact known time
        controller.resetTracking()
        currentTimeNanos += 50_000_000L
        controller.recordFrameCost(4.0f) // starts headroom timer at currentTimeNanos
        assertEquals(AsciiQualityController.MIN_FPS, controller.targetFps)
        assertEquals(2f, controller.extraCw)

        // 4.9 seconds of headroom: should NOT step up yet
        currentTimeNanos += 4_900_000_000L
        controller.recordFrameCost(4.0f)
        assertEquals(AsciiQualityController.MIN_FPS, controller.targetFps)
        assertEquals(2f, controller.extraCw)

        // Advance past 5.0 seconds (total 5.1s from start): should step up to Tier 3 (MID_FPS 24, extraCw 2)
        currentTimeNanos += 200_000_000L
        controller.recordFrameCost(4.0f)
        assertEquals(AsciiQualityController.MID_FPS, controller.targetFps) // 24
        assertEquals(2f, controller.extraCw)

        // Advance another 5.1 seconds: should step up to Tier 2 (MID_FPS 24, extraCw 1)
        currentTimeNanos += 5_100_000_000L
        controller.recordFrameCost(4.0f)
        assertEquals(AsciiQualityController.MID_FPS, controller.targetFps)
        assertEquals(1f, controller.extraCw)

        // Advance another 5.1 seconds: should step up to Tier 1 (MAX_FPS 30, extraCw 1)
        currentTimeNanos += 5_100_000_000L
        controller.recordFrameCost(4.0f)
        assertEquals(AsciiQualityController.MAX_FPS, controller.targetFps)
        assertEquals(1f, controller.extraCw)

        // Advance another 5.1 seconds: should restore Tier 0 (MAX_FPS 30, extraCw 0)
        currentTimeNanos += 5_100_000_000L
        controller.recordFrameCost(4.0f)
        assertEquals(AsciiQualityController.MAX_FPS, controller.targetFps)
        assertEquals(0f, controller.extraCw)
    }

    @Test
    fun `power save mode immediately clamps quality`() {
        var currentTimeNanos = 1_000_000_000L
        val controller = AsciiQualityController(isLowRamDevice = false) { currentTimeNanos }

        controller.isPowerSaveMode = true
        assertEquals(AsciiQualityController.LOW_POWER_FPS, controller.targetFps)
        assertEquals(1f, controller.extraCw)

        // Sustained light load should NOT step up while power save is active
        currentTimeNanos += 10_000_000_000L
        controller.recordFrameCost(2.0f)
        assertEquals(AsciiQualityController.LOW_POWER_FPS, controller.targetFps)
        assertEquals(1f, controller.extraCw)

        // Disabling power save immediately restores unconstrained quality
        controller.isPowerSaveMode = false
        assertEquals(AsciiQualityController.MAX_FPS, controller.targetFps)
        assertEquals(0f, controller.extraCw)
    }

    @Test
    fun `thermal throttling at or above MODERATE immediately clamps quality`() {
        var currentTimeNanos = 1_000_000_000L
        val controller = AsciiQualityController(isLowRamDevice = false) { currentTimeNanos }

        controller.thermalStatus = AsciiQualityController.THERMAL_STATUS_LIGHT
        assertEquals(AsciiQualityController.MAX_FPS, controller.targetFps)
        assertEquals(0f, controller.extraCw)

        controller.thermalStatus = AsciiQualityController.THERMAL_STATUS_MODERATE
        assertEquals(AsciiQualityController.LOW_POWER_FPS, controller.targetFps)
        assertEquals(1f, controller.extraCw)

        controller.thermalStatus = AsciiQualityController.THERMAL_STATUS_SEVERE
        assertEquals(AsciiQualityController.LOW_POWER_FPS, controller.targetFps)
        assertEquals(1f, controller.extraCw)
    }

    @Test
    fun `low RAM device initializes with conservative quality tier`() {
        val controller = AsciiQualityController(isLowRamDevice = true)
        assertEquals(AsciiQualityController.MID_FPS, controller.targetFps)
        assertEquals(1f, controller.extraCw)
    }
}
