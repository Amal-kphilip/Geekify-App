package com.geekify.android.audio

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Manages genuine two-player overlapping crossfade transitions (two independently prepared
 * ExoPlayer instances with equal-power outgoing/incoming volume curves) as well as audio-focus
 * ducking and fallback single-player fades.
 */
class CrossfadeAudioTransitionController constructor(
    private val audioSettingsRepo: AudioSettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var fadeJob: Job? = null
    private var twoPlayerCrossfadeJob: Job? = null
    private var isDucked = false
    private var userVolume = 1.0f
    private var endFadeTriggeredForTrackId: String? = null

    val isFading: Boolean
        get() = fadeJob?.isActive == true || twoPlayerCrossfadeJob?.isActive == true

    val isTwoPlayerCrossfadeActive: Boolean
        get() = twoPlayerCrossfadeJob?.isActive == true

    var crossfadeDurationSeconds: Int = 0
        private set

    init {
        scope.launch {
            audioSettingsRepo.settings.collectLatest { settings ->
                crossfadeDurationSeconds = settings.crossfadeSeconds
            }
        }
    }

    fun setBaseVolume(volume: Float) {
        userVolume = volume.coerceIn(0f, 1f)
    }

    fun resetEndFadeGuard() {
        endFadeTriggeredForTrackId = null
    }

    fun shouldStartCrossfade(trackId: String, positionMs: Long, durationMs: Long): Boolean {
        if (crossfadeDurationSeconds <= 0 || durationMs <= 10_000L) return false
        if (endFadeTriggeredForTrackId == trackId || isTwoPlayerCrossfadeActive) return false
        val crossfadeMs = (crossfadeDurationSeconds * 1000L).coerceIn(1_000L, 12_000L)
        val remainingMs = durationMs - positionMs
        return remainingMs in 250L..crossfadeMs
    }

    /**
     * Genuine two-player overlapping crossfade:
     * Starts [incoming] while [outgoing] continues playing, smoothly ramping both volume curves
     * using equal-power gains (`cos` / `sin`) so both tracks genuinely overlap with steady loudness.
     */
    fun startTwoPlayerCrossfade(
        trackId: String,
        outgoing: ExoPlayer,
        incoming: ExoPlayer,
        durationMs: Long,
        onComplete: () -> Unit,
        onCancelled: () -> Unit = {}
    ) {
        cancelTwoPlayerCrossfade()
        fadeJob?.cancel()
        endFadeTriggeredForTrackId = trackId

        val clampedDurationMs = durationMs.coerceIn(500L, 12_000L)
        incoming.volume = 0f
        if (!incoming.playWhenReady) {
            incoming.play()
        }

        twoPlayerCrossfadeJob = scope.launch {
            try {
                val frameMs = 40L
                val steps = (clampedDurationMs / frameMs).toInt().coerceAtLeast(10)
                val startOutVol = outgoing.volume.coerceIn(0f, userVolume)
                for (i in 1..steps) {
                    val progress = i.toFloat() / steps.toFloat()
                    val (outGain, inGain) = computeEqualPowerGains(progress)
                    outgoing.volume = (startOutVol * outGain).coerceIn(0f, 1f)
                    incoming.volume = (userVolume * inGain).coerceIn(0f, 1f)
                    delay(frameMs)
                }
                outgoing.volume = 0f
                incoming.volume = userVolume
                onComplete()
            } catch (ce: CancellationException) {
                onCancelled()
                throw ce
            }
        }
    }

    fun cancelTwoPlayerCrossfade() {
        twoPlayerCrossfadeJob?.cancel()
        twoPlayerCrossfadeJob = null
    }

    fun fadeVolume(
        player: ExoPlayer,
        startVol: Float = player.volume,
        targetVol: Float,
        durationMs: Long,
        onComplete: (() -> Unit)? = null
    ) {
        fadeJob?.cancel()
        if (durationMs <= 0L) {
            player.volume = targetVol
            onComplete?.invoke()
            return
        }

        fadeJob = scope.launch {
            val steps = 25
            val stepDelay = durationMs / steps
            for (i in 1..steps) {
                val fraction = i.toFloat() / steps.toFloat()
                val current = if (targetVol > startVol) {
                    startVol + (targetVol - startVol) * sin(fraction * Math.PI.toFloat() / 2f)
                } else {
                    startVol - (startVol - targetVol) * (1f - cos(fraction * Math.PI.toFloat() / 2f))
                }
                player.volume = current.coerceIn(0f, 1f)
                delay(stepDelay)
            }
            player.volume = targetVol.coerceIn(0f, 1f)
            onComplete?.invoke()
        }
    }

    fun applyTrackStartFadeIn(player: ExoPlayer) {
        endFadeTriggeredForTrackId = null
        if (crossfadeDurationSeconds <= 0 || isTwoPlayerCrossfadeActive) {
            fadeJob?.cancel()
            player.volume = userVolume
            return
        }
        val durationMs = (crossfadeDurationSeconds * 1000L).coerceAtMost(6000L)
        fadeVolume(player, startVol = 0.05f, targetVol = userVolume, durationMs = durationMs)
    }

    fun maybeTriggerTrackEndFadeOut(player: ExoPlayer, trackId: String, positionMs: Long, durationMs: Long) {
        if (crossfadeDurationSeconds <= 0 || durationMs <= 15_000L || isTwoPlayerCrossfadeActive) return
        if (endFadeTriggeredForTrackId == trackId) return
        val fadeMs = (crossfadeDurationSeconds * 1000L).coerceAtMost(6000L)
        val remainingMs = durationMs - positionMs
        if (remainingMs in 200L..fadeMs) {
            endFadeTriggeredForTrackId = trackId
            fadeVolume(player, startVol = player.volume, targetVol = 0.05f, durationMs = remainingMs)
        }
    }

    fun applyTrackEndFadeOut(player: ExoPlayer, onComplete: (() -> Unit)? = null) {
        if (crossfadeDurationSeconds <= 0) {
            onComplete?.invoke()
            return
        }
        val durationMs = (crossfadeDurationSeconds * 1000L).coerceAtMost(6000L)
        fadeVolume(player, startVol = player.volume, targetVol = 0.05f, durationMs = durationMs, onComplete = onComplete)
    }

    fun duck(player: ExoPlayer) {
        if (isDucked) return
        isDucked = true
        userVolume = player.volume
        fadeVolume(player, startVol = player.volume, targetVol = 0.2f, durationMs = 300L)
    }

    fun unduck(player: ExoPlayer) {
        if (!isDucked) return
        isDucked = false
        fadeVolume(player, startVol = player.volume, targetVol = userVolume, durationMs = 400L)
    }

    companion object {
        /**
         * Equal-power crossfade gains `(outgoingGain, incomingGain)` for `progress in [0f, 1f]`.
         * Preserves perceived acoustic energy (`out^2 + in^2 = 1`) throughout the overlap.
         */
        fun computeEqualPowerGains(progress: Float): Pair<Float, Float> {
            val p = progress.coerceIn(0f, 1f)
            val angle = p * (Math.PI.toFloat() / 2f)
            return cos(angle) to sin(angle)
        }
    }
}
