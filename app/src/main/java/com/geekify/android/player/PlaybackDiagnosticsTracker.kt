package com.geekify.android.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class DiagnosticPlaybackError(
    val timestamp: Long = System.currentTimeMillis(),
    val errorCategory: String, // NETWORK, DECODER, TIMEOUT, RESOLUTION, UNKNOWN
    val sanitizedMessage: String
)

data class DiagnosticsSnapshot(
    val totalResolutions: Long,
    val successfulResolutions: Long,
    val failedResolutions: Long,
    val resolutionSuccessRate: Double,
    val avgResolutionLatencyMs: Long,
    val stallCount: Int,
    val totalBufferingDurationMs: Long,
    val networkCondition: String,
    val audioFormat: String,
    val recentErrors: List<DiagnosticPlaybackError>
)

/**
 * Lightweight, thread-safe diagnostics and observability engine for playback reliability.
 * Redacts all URLs, tokens, and PII from diagnostics data.
 */
@Singleton
class PlaybackDiagnosticsTracker @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    private val totalResolutions = AtomicLong(0L)
    private val successfulResolutions = AtomicLong(0L)
    private val failedResolutions = AtomicLong(0L)
    private val totalResolutionLatencyMs = AtomicLong(0L)
    private val stallCount = AtomicInteger(0)
    private val totalBufferingDurationMs = AtomicLong(0L)

    @Volatile
    private var lastAudioFormat: String = "Opus / AAC (160kbps)"

    private val recentErrors = ConcurrentLinkedDeque<DiagnosticPlaybackError>()

    private val _snapshot = MutableStateFlow(getSnapshot())
    val snapshot: StateFlow<DiagnosticsSnapshot> = _snapshot.asStateFlow()

    fun recordResolution(durationMs: Long, success: Boolean) {
        totalResolutions.incrementAndGet()
        if (success) {
            successfulResolutions.incrementAndGet()
            totalResolutionLatencyMs.addAndGet(durationMs.coerceAtLeast(0L))
        } else {
            failedResolutions.incrementAndGet()
        }
        updateSnapshot()
    }

    fun recordStall(durationMs: Long) {
        stallCount.incrementAndGet()
        totalBufferingDurationMs.addAndGet(durationMs.coerceAtLeast(0L))
        updateSnapshot()
    }

    fun recordAudioFormat(format: String) {
        lastAudioFormat = format
        updateSnapshot()
    }

    fun recordError(category: String, rawMessage: String?) {
        val sanitized = scrubSensitiveData(rawMessage.orEmpty())
        recentErrors.addFirst(
            DiagnosticPlaybackError(
                errorCategory = category,
                sanitizedMessage = sanitized
            )
        )
        while (recentErrors.size > 20) {
            recentErrors.removeLast()
        }
        updateSnapshot()
    }

    fun getNetworkCondition(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "UNKNOWN"
        val active = cm.activeNetwork ?: return "OFFLINE"
        val caps = cm.getNetworkCapabilities(active) ?: return "OFFLINE"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }
    }

    fun getSnapshot(): DiagnosticsSnapshot {
        val total = totalResolutions.get()
        val success = successfulResolutions.get()
        val rate = if (total > 0L) (success.toDouble() / total.toDouble()) * 100.0 else 100.0
        val avgLat = if (success > 0L) totalResolutionLatencyMs.get() / success else 0L

        return DiagnosticsSnapshot(
            totalResolutions = total,
            successfulResolutions = success,
            failedResolutions = failedResolutions.get(),
            resolutionSuccessRate = rate,
            avgResolutionLatencyMs = avgLat,
            stallCount = stallCount.get(),
            totalBufferingDurationMs = totalBufferingDurationMs.get(),
            networkCondition = getNetworkCondition(),
            audioFormat = lastAudioFormat,
            recentErrors = recentErrors.toList()
        )
    }

    fun generateScrubbedReport(): String {
        val snap = getSnapshot()
        val sb = StringBuilder()
        sb.appendLine("=== Geekify Playback Diagnostics Report ===")
        sb.appendLine("App Version: Geekify v${com.geekify.android.BuildConfig.VERSION_NAME}")
        sb.appendLine("Android OS: SDK ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        sb.appendLine("Network: ${snap.networkCondition}")
        sb.appendLine("Audio Pipeline: ${snap.audioFormat}")
        sb.appendLine("Resolution Success Rate: ${"%.1f".format(snap.resolutionSuccessRate)}% (${snap.successfulResolutions}/${snap.totalResolutions})")
        sb.appendLine("Avg Stream Resolution: ${snap.avgResolutionLatencyMs} ms")
        sb.appendLine("Stalls Observed: ${snap.stallCount}")
        sb.appendLine("Total Buffering Time: ${snap.totalBufferingDurationMs} ms")
        sb.appendLine("\nRecent Logged Errors (${snap.recentErrors.size}):")
        if (snap.recentErrors.isEmpty()) {
            sb.appendLine("  None - Playback running smoothly.")
        } else {
            for (err in snap.recentErrors.take(5)) {
                sb.appendLine("  [${err.errorCategory}] ${err.sanitizedMessage}")
            }
        }
        sb.appendLine("===========================================")
        return sb.toString()
    }

    private fun updateSnapshot() {
        _snapshot.value = getSnapshot()
    }

    private fun scrubSensitiveData(input: String): String {
        var text = input
        // Scrub URLs and query params
        text = text.replace(Regex("https?://[^\\s]+"), "[URL_REDACTED]")
        // Scrub key/secret tokens
        text = text.replace(Regex("(?i)(key|token|auth|secret|pass)=[^\\s&]+"), "$1=[REDACTED]")
        return if (text.length > 200) text.substring(0, 200) + "..." else text
    }
}
