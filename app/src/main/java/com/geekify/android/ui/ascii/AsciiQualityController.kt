package com.geekify.android.ui.ascii

import kotlin.math.max
import kotlin.math.min

/**
 * Adaptive quality controller for ASCII rendering.
 *
 * Monitors frame execution times and environmental constraints (thermal, battery saver,
 * low-RAM, reduced motion) to dynamically scale render workload:
 * - Regulates target simulation/blit FPS: 30 -> 24 -> 20.
 * - Regulates character cell width step-up (extraCw: 0 -> 1 -> 2), reducing grid cell count.
 * - Uses an Exponential Moving Average (EMA) with hysteresis (60% step-down threshold,
 *   40% step-up threshold with a 5-second sustained headroom timer) to prevent flapping.
 * - Respects power-save mode, thermal throttling, low-RAM, and reduce-motion.
 */
class AsciiQualityController(
    val isLowRamDevice: Boolean = false,
    private val clockNanos: () -> Long = { System.nanoTime() }
) {
    companion object {
        const val THERMAL_STATUS_NONE = 0
        const val THERMAL_STATUS_LIGHT = 1
        const val THERMAL_STATUS_MODERATE = 2
        const val THERMAL_STATUS_SEVERE = 3
        const val THERMAL_STATUS_CRITICAL = 4

        const val MAX_FPS = 30
        const val MID_FPS = 24
        const val MIN_FPS = 20
        const val LOW_POWER_FPS = 20

        const val HEADROOM_TIME_NANOS = 5_000_000_000L // 5.0 seconds
        const val STEP_DOWN_COOLDOWN_NANOS = 1_000_000_000L // 1.0 second cooldown between step downs
        const val EMA_ALPHA = 0.10f

        // Tiers:
        // Tier 0: 30 fps, extraCw 0
        // Tier 1: 30 fps, extraCw 1
        // Tier 2: 24 fps, extraCw 1
        // Tier 3: 24 fps, extraCw 2
        // Tier 4: 20 fps, extraCw 2
        private val TIER_FPS = intArrayOf(MAX_FPS, MAX_FPS, MID_FPS, MID_FPS, MIN_FPS)
        private val TIER_EXTRA_CW = floatArrayOf(0f, 1f, 1f, 2f, 2f)
        const val MAX_TIER = 4
    }

    var isPowerSaveMode: Boolean = false
    var thermalStatus: Int = THERMAL_STATUS_NONE
    var isReduceMotion: Boolean = false
    var maxRequestedDetail: Float = 2.0f

    private var currentTier: Int = if (isLowRamDevice) 2 else 0
    private var lastStepDownNanos: Long = 0L
    private var headroomStartNanos: Long = 0L

    var frameCostEmaMs: Float = 0f
        private set

    val targetFps: Int
        get() {
            val baseFps = TIER_FPS[currentTier]
            return when {
                isPowerSaveMode || thermalStatus >= THERMAL_STATUS_MODERATE || isReduceMotion ->
                    min(baseFps, LOW_POWER_FPS)
                isLowRamDevice -> min(baseFps, MID_FPS)
                else -> baseFps
            }
        }

    val extraCw: Float
        get() {
            val baseExtraCw = TIER_EXTRA_CW[currentTier]
            return when {
                isReduceMotion -> max(baseExtraCw, 2f)
                isPowerSaveMode || thermalStatus >= THERMAL_STATUS_MODERATE || isLowRamDevice ->
                    max(baseExtraCw, 1f)
                else -> baseExtraCw
            }
        }

    val intervalMs: Float get() = 1000f / targetFps.coerceAtLeast(1)

    /**
     * Records the duration of a completed frame in milliseconds.
     * Updates EMA and triggers quality tier step-down or step-up.
     */
    fun recordFrameCost(costMs: Float) {
        val safeCost = max(0.1f, costMs)
        if (frameCostEmaMs <= 0f) {
            frameCostEmaMs = safeCost
        } else {
            frameCostEmaMs = frameCostEmaMs * (1f - EMA_ALPHA) + safeCost * EMA_ALPHA
        }

        val now = clockNanos()
        val currentInterval = intervalMs
        val stepDownThreshold = currentInterval * 0.60f
        val stepUpThreshold = currentInterval * 0.40f

        // Under environmental restrictions, do not allow stepping up
        if (isRestrictedEnvironment()) {
            headroomStartNanos = 0L
            if (frameCostEmaMs > stepDownThreshold && now - lastStepDownNanos >= STEP_DOWN_COOLDOWN_NANOS) {
                stepDown()
                lastStepDownNanos = now
            }
            return
        }

        if (frameCostEmaMs > stepDownThreshold) {
            // Under load: step down with cooldown to avoid thrashing
            headroomStartNanos = 0L
            if (now - lastStepDownNanos >= STEP_DOWN_COOLDOWN_NANOS) {
                stepDown()
                lastStepDownNanos = now
            }
        } else if (frameCostEmaMs < stepUpThreshold) {
            // Headroom available: require sustained 5s before stepping up
            if (headroomStartNanos == 0L) {
                headroomStartNanos = now
            } else if (now - headroomStartNanos >= HEADROOM_TIME_NANOS) {
                stepUp()
                headroomStartNanos = now // reset for next step up
            }
        } else {
            // Hysteresis deadband: between 40% and 60% interval, reset recovery timer
            headroomStartNanos = 0L
        }
    }

    private fun isRestrictedEnvironment(): Boolean {
        return isPowerSaveMode || thermalStatus >= THERMAL_STATUS_MODERATE || isReduceMotion
    }

    private fun stepDown() {
        if (currentTier < MAX_TIER) {
            currentTier++
        }
    }

    private fun stepUp() {
        val minTier = if (isLowRamDevice) 2 else 0
        if (currentTier > minTier) {
            currentTier--
        }
    }

    /**
     * Resets EMA and headroom timers (e.g. after screen resize or scene change).
     */
    fun resetTracking() {
        frameCostEmaMs = 0f
        headroomStartNanos = 0L
        lastStepDownNanos = 0L
    }
}
