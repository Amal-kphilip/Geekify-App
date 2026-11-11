package com.geekify.android.audio

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.sin

/**
 * Manages audio transitions: smooth crossfade volume curves between tracks and audio focus ducking/restoration.
 */
@Singleton
class CrossfadeAudioTransitionController @Inject constructor(
    private val audioSettingsRepo: AudioSettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var fadeJob: Job? = null
    private var isDucked = false
    private var userVolume = 1.0f

    var crossfadeDurationSeconds: Int = 0
        private set

    init {
        scope.launch {
            audioSettingsRepo.settings.collectLatest { settings ->
                crossfadeDurationSeconds = settings.crossfadeSeconds
            }
        }
    }

    /**
     * Executes an equal-power or linear volume fade from [startVol] to [targetVol] over [durationMs].
     */
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
                // Equal-power volume curve
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
        if (crossfadeDurationSeconds <= 0) {
            player.volume = userVolume
            return
        }
        val durationMs = (crossfadeDurationSeconds * 1000L).coerceAtMost(6000L)
        fadeVolume(player, startVol = 0.05f, targetVol = userVolume, durationMs = durationMs)
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
}
