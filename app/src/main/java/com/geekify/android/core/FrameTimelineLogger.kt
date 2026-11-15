package com.geekify.android.core

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import com.geekify.android.BuildConfig
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.ceil

/**
 * Debug-only FrameTimeline / JankStats logger using Window.OnFrameMetricsAvailableListener (API 24+).
 * Tracks frame durations in nanoseconds and computes p50/p90/p99 latency percentiles and jank ratios
 * for scrolling and navigation transitions.
 */
object FrameTimelineLogger {
    private var isAttached = false
    private val frameDurationsMs = CopyOnWriteArrayList<Float>()
    private var activeInteractionTag: String? = null

    fun attach(activity: Activity) {
        if (!BuildConfig.DEBUG || isAttached || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val window = activity.window ?: return
        val handler = Handler(Looper.getMainLooper())

        try {
            window.addOnFrameMetricsAvailableListener(
                { _, frameMetrics, _ ->
                    val totalNs = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
                    val durationMs = totalNs / 1_000_000f
                    frameDurationsMs.add(durationMs)
                    if (frameDurationsMs.size > 2000) {
                        frameDurationsMs.removeAt(0)
                    }
                },
                handler
            )
            isAttached = true
            Timber.d("[FrameTimeline] Attached FrameMetrics listener for performance analysis")
        } catch (e: Throwable) {
            Timber.w(e, "[FrameTimeline] Failed to attach FrameMetrics listener")
        }
    }

    fun startInteraction(tag: String) {
        if (!BuildConfig.DEBUG) return
        activeInteractionTag = tag
        frameDurationsMs.clear()
        Timber.d("[FrameTimeline] Started interaction tracking: %s", tag)
    }

    fun endInteraction(tag: String): Stats? {
        if (!BuildConfig.DEBUG) return null
        val count = frameDurationsMs.size
        if (count == 0) {
            Timber.d("[FrameTimeline] No frames recorded for interaction: %s", tag)
            activeInteractionTag = null
            return null
        }

        val sorted = frameDurationsMs.sorted()
        val p50 = percentile(sorted, 0.50)
        val p90 = percentile(sorted, 0.90)
        val p99 = percentile(sorted, 0.99)
        val jankFrames = sorted.count { it > 16.67f }
        val jankPercentage = (jankFrames.toFloat() / count.toFloat()) * 100f

        val stats = Stats(
            tag = tag,
            totalFrames = count,
            p50Ms = p50,
            p90Ms = p90,
            p99Ms = p99,
            jankFrames = jankFrames,
            jankPercentage = jankPercentage
        )
        Timber.i(
            "[FrameTimeline] %s (%d frames): p50=%.2fms, p90=%.2fms, p99=%.2fms, jank=%.1f%% (%d/%d)",
            tag, count, p50, p90, p99, jankPercentage, jankFrames, count
        )
        activeInteractionTag = null
        return stats
    }

    private fun percentile(sorted: List<Float>, p: Double): Float {
        if (sorted.isEmpty()) return 0f
        val idx = ceil(p * sorted.size).toInt() - 1
        return sorted[idx.coerceIn(0, sorted.lastIndex)]
    }

    data class Stats(
        val tag: String,
        val totalFrames: Int,
        val p50Ms: Float,
        val p90Ms: Float,
        val p99Ms: Float,
        val jankFrames: Int,
        val jankPercentage: Float
    )
}
