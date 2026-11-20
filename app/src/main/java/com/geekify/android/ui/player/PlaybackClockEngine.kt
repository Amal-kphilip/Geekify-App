package com.geekify.android.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import com.geekify.android.lyrics.LyricsLine
import com.geekify.android.lyrics.LyricsWord
import com.geekify.android.player.PlaybackPosition
import kotlin.math.abs
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks a user-initiated seek target until the underlying ExoPlayer acknowledges it,
 * preventing the seekbar thumb or lyrics from snapping back to a stale pre-seek sample.
 */
data class PendingSeekPosition(
    val targetMs: Long,
    val previousPositionMs: Long,
    val createdAtNanos: Long = System.nanoTime()
) {
    /**
     * Returns true once the authoritative player position has moved closer to [targetMs]
     * than to [previousPositionMs] (or landed within 300ms of [targetMs]).
     */
    fun isAcknowledged(
        playerPositionMs: Long,
        renderedPositionMs: Long = playerPositionMs
    ): Boolean {
        if (targetMs == previousPositionMs) return true
        val closeEnough = abs(playerPositionMs - targetMs) <= 300L
        val closerToTarget = abs(playerPositionMs - targetMs) < abs(playerPositionMs - previousPositionMs) &&
            abs(renderedPositionMs - targetMs) <= abs(renderedPositionMs - previousPositionMs) + 300L
        return closeEnough || closerToTarget
    }

    /** Prevents a stale pending seek from persisting indefinitely if the player clamps or ignores it. */
    fun isExpired(nowNanos: Long, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val elapsedMs = (nowNanos - createdAtNanos) / 1_000_000L
        return elapsedMs >= timeoutMs
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_500L
    }
}

const val MAX_CLOCK_EXTRAPOLATION_MS = 1_500L
const val RESUME_FRAME_GAP_THRESHOLD_MS = 450L

/**
 * Pure monotonic-clock playback position interpolator shared by the Seekbar, Timers,
 * Mini-Player progress, and Synchronized Lyrics.
 *
 * - Advances smoothly at [playbackSpeed] while playing and not buffering.
 * - Freezes immediately when paused or buffering.
 * - Caps extrapolation beyond the last authoritative sample to [maxExtrapolationMs] so long
 *   pauses, backgrounding, or service stalls can never cause lyrics or seekbar to drift ahead.
 * - Never exceeds [durationMs] when duration is known.
 */
fun interpolatePlaybackPosition(
    sampledPositionMs: Long,
    elapsedRealtimeMs: Long,
    playbackSpeed: Float,
    durationMs: Long,
    isPlaying: Boolean,
    isBuffering: Boolean,
    maxExtrapolationMs: Long = MAX_CLOCK_EXTRAPOLATION_MS
): Long {
    val safeSample = sampledPositionMs.coerceAtLeast(0L)
    val maxPos = if (durationMs > 0L) durationMs else Long.MAX_VALUE
    if (!isPlaying || isBuffering || elapsedRealtimeMs <= 0L) {
        return safeSample.coerceAtMost(maxPos)
    }
    val boundedElapsedMs = elapsedRealtimeMs.coerceIn(0L, maxExtrapolationMs.coerceAtLeast(0L))
    val safeSpeed = if (playbackSpeed.isFinite()) playbackSpeed.coerceIn(0.25f, 4.0f) else 1.0f
    val deltaMs = (boundedElapsedMs.toDouble() * safeSpeed.toDouble()).toLong().coerceAtLeast(0L)
    return (safeSample + deltaMs).coerceIn(0L, maxPos)
}

/**
 * Baseline snapshot for the authoritative playback clock. Re-anchored immediately whenever
 * playback pauses, resumes after any duration, seeks, buffers, or returns from background.
 */
data class PlaybackClockAnchor(
    val sampledPositionMs: Long,
    val anchorFrameNanos: Long,
    val lastFrameNanos: Long
) {
    fun advance(
        latestSampleMs: Long,
        frameNanos: Long,
        playbackSpeed: Float,
        durationMs: Long,
        isPlaying: Boolean,
        isBuffering: Boolean
    ): Pair<PlaybackClockAnchor, Long> {
        val safeSample = latestSampleMs.coerceAtLeast(0L)
        val frameGapMs = if (lastFrameNanos > 0L) ((frameNanos - lastFrameNanos) / 1_000_000L) else 0L
        val needsReanchor = !isPlaying ||
            isBuffering ||
            anchorFrameNanos <= 0L ||
            safeSample != sampledPositionMs ||
            frameGapMs > RESUME_FRAME_GAP_THRESHOLD_MS ||
            frameNanos < anchorFrameNanos

        val nextAnchor = if (needsReanchor) {
            PlaybackClockAnchor(
                sampledPositionMs = safeSample,
                anchorFrameNanos = frameNanos,
                lastFrameNanos = frameNanos
            )
        } else {
            copy(lastFrameNanos = frameNanos)
        }
        val elapsedMs = ((frameNanos - nextAnchor.anchorFrameNanos) / 1_000_000L).coerceAtLeast(0L)
        val computedPos = interpolatePlaybackPosition(
            sampledPositionMs = nextAnchor.sampledPositionMs,
            elapsedRealtimeMs = elapsedMs,
            playbackSpeed = playbackSpeed,
            durationMs = durationMs,
            isPlaying = isPlaying,
            isBuffering = isBuffering
        )
        return nextAnchor to computedPos
    }
}

/**
 * Safely computes normalized playback progress in `0f..1f`, guarding against unknown/zero duration,
 * negative positions, NaN, and Infinity.
 */
fun calculateProgressFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    val raw = positionMs.toDouble() / durationMs.toDouble()
    if (!raw.isFinite()) return 0f
    return raw.toFloat().coerceIn(0f, 1f)
}

/**
 * Formats milliseconds into `m:ss` or `h:mm:ss` for long tracks, never producing negative values.
 */
fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Authoritative lyrics timing & offset engine.
 * Applies track offset and global offset strictly ONCE, and performs accurate active-line and
 * word-timestamp lookups without ever fabricating uniform word durations when real timings exist.
 */
object LyricsSyncCalculator {
    fun effectiveLyricsTimeMs(
        playbackPositionMs: Long,
        trackOffsetMs: Long,
        globalOffsetMs: Long,
        anticipationMs: Long = 0L
    ): Long {
        return (playbackPositionMs + trackOffsetMs + globalOffsetMs + anticipationMs).coerceAtLeast(0L)
    }

    fun targetSeekPositionMs(
        lyricTimestampMs: Long,
        trackOffsetMs: Long,
        globalOffsetMs: Long,
        durationMs: Long = 0L
    ): Long {
        val raw = (lyricTimestampMs - trackOffsetMs - globalOffsetMs).coerceAtLeast(0L)
        return if (durationMs > 0L) raw.coerceAtMost(durationMs) else raw
    }

    /**
     * Finds the active lyric line index for [effectiveTimeMs].
     * - Returns -1 before the first timestamped line.
     * - Handles duplicate timestamps cleanly by advancing to the latest line sharing that timestamp.
     * - Ignores lines with negative/missing timestamps.
     */
    fun findActiveLineIndex(lines: List<LyricsLine>, effectiveTimeMs: Long): Int {
        if (lines.isEmpty() || effectiveTimeMs < 0L) return -1
        var active = -1
        for (i in lines.indices) {
            val t = lines[i].timeMs
            if (t < 0L) continue
            if (t <= effectiveTimeMs) {
                active = i
            } else {
                break
            }
        }
        return active
    }

    /**
     * Finds the currently active word index using actual word [LyricsWord.startMs] timestamps.
     * Returns -1 if the line has no word timing or playback hasn't reached the first word yet.
     */
    fun findActiveWordIndex(words: List<LyricsWord>, effectiveTimeMs: Long): Int {
        if (words.isEmpty() || effectiveTimeMs < 0L) return -1
        var active = -1
        for (i in words.indices) {
            val start = words[i].startMs
            if (start < 0L) continue
            if (start <= effectiveTimeMs) {
                active = i
            } else {
                break
            }
        }
        return active
    }
}

/**
 * Metrolist-style progressive seek multiplier:
 * - Consecutive double-taps within [thresholdMs] (default 1000ms) increment the multiplier.
 * - After [thresholdMs] has elapsed, resets back to 1.
 */
fun nextSeekMultiplier(
    previousMultiplier: Int,
    elapsedMs: Long,
    thresholdMs: Long = 1_000L
): Int = if (elapsedMs in 0L until thresholdMs) previousMultiplier.coerceAtLeast(1) + 1 else 1

/**
 * Computes a clamped relative seek target in milliseconds.
 * - Returns `null` for non-seekable media (`durationMs <= 0L`) or a zero delta.
 * - Clamps backward seeking to `0L` and forward seeking to `durationMs`.
 */
fun computeSeekByTargetMs(
    currentPositionMs: Long,
    durationMs: Long,
    deltaMs: Long
): Long? {
    if (durationMs <= 0L || deltaMs == 0L) return null
    val safePos = currentPositionMs.coerceIn(0L, durationMs)
    return (safePos + deltaMs).coerceIn(0L, durationMs)
}

/**
 * Stateful, pure double-tap artwork skip calculator mirroring Metrolist's `artworkSeekGestures()`
 * progressive timing logic. Resets automatically when tracks change or when the feature is disabled.
 */
class ArtworkDoubleTapSkipEngine(
    private val progressiveThresholdMs: Long = 1_000L
) {
    private var multiplier: Int = 1
    private var lastTapMs: Long = -1L
    private var lastTrackId: String? = null

    fun reset() {
        multiplier = 1
        lastTapMs = -1L
    }

    fun onTrackChanged(trackId: String?) {
        if (trackId != lastTrackId) {
            lastTrackId = trackId
            reset()
        }
    }

    /**
     * Processes a double-tap at horizontal coordinate [tapX] on an artwork surface of width [widthPx].
     * Returns the signed delta in milliseconds (negative for left/rewind, positive for right/forward),
     * or `null` if disabled or invalid geometry.
     */
    fun onDoubleTap(
        trackId: String?,
        tapX: Float,
        widthPx: Float,
        enabled: Boolean,
        stepSeconds: Int,
        progressive: Boolean,
        nowMs: Long
    ): Long? {
        if (!enabled || widthPx <= 0f) {
            reset()
            return null
        }
        if (trackId != lastTrackId) {
            lastTrackId = trackId
            reset()
        }
        val elapsedMs = if (lastTapMs >= 0L) nowMs - lastTapMs else Long.MAX_VALUE
        multiplier = if (progressive) {
            nextSeekMultiplier(multiplier, elapsedMs, progressiveThresholdMs)
        } else {
            1
        }
        lastTapMs = nowMs

        val normalizedStepSec = com.geekify.android.customization.GestureCustomizationConfig
            .normalizeSkipDurationSeconds(stepSeconds)
        val amountMs = normalizedStepSec.toLong() * 1_000L * multiplier.toLong()
        return if (tapX < widthPx / 2f) -amountMs else amountMs
    }
}

/**
 * Authoritative, frame-interpolated playback position State driven by a single source of truth
 * ([positionFlow]). Automatically stops frame updates when paused/buffering and cancels/resets
 * immediately when [trackId] changes.
 */
@Composable
fun rememberAuthoritativePlaybackPosition(
    trackId: String?,
    positionFlow: StateFlow<PlaybackPosition>,
    isPlaying: Boolean,
    isBuffering: Boolean,
    playbackSpeed: Float,
    active: Boolean = true
): State<Long> {
    val rawPos by positionFlow.collectAsState()
    val latestRawPos by rememberUpdatedState(rawPos)
    val latestSpeed by rememberUpdatedState(playbackSpeed)
    val shouldInterpolate = active && isPlaying && !isBuffering

    // Force a fresh re-anchor whenever the host lifecycle resumes from background/lockscreen
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val resumeGeneration = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                resumeGeneration.intValue++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return produceState(
        initialValue = rawPos.positionMs.coerceAtLeast(0L),
        key1 = trackId,
        key2 = shouldInterpolate,
        key3 = if (shouldInterpolate) resumeGeneration.intValue else rawPos.positionMs
    ) {
        // Always snap immediately to the latest authoritative player position on pause, resume, or track change
        value = latestRawPos.positionMs.coerceAtLeast(0L)
        if (!shouldInterpolate) return@produceState

        var anchor = PlaybackClockAnchor(
            sampledPositionMs = latestRawPos.positionMs.coerceAtLeast(0L),
            anchorFrameNanos = 0L,
            lastFrameNanos = 0L
        )

        while (true) {
            withFrameNanos { frameNanos ->
                val sample = latestRawPos
                val (nextAnchor, computedPos) = anchor.advance(
                    latestSampleMs = sample.positionMs,
                    frameNanos = frameNanos,
                    playbackSpeed = latestSpeed,
                    durationMs = sample.durationMs,
                    isPlaying = true,
                    isBuffering = false
                )
                anchor = nextAnchor
                value = computedPos
            }
        }
    }
}
