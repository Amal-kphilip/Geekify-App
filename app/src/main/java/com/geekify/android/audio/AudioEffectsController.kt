package com.geekify.android.audio

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/**
 * The single place where audio settings meet the audio pipeline.
 *
 *  UI --update()--> [settings] --> equalizer / normalizer parameters (live, on the audio thread's next buffer)
 *                           \--> PlaybackService applies speed, pitch and silence skipping to the running player
 *
 * [settings] is the in-memory source of truth, so sliders react instantly while dragging; changes are written
 * to DataStore a moment after the last one. Nothing here re-creates the player.
 */
@OptIn(UnstableApi::class)
class AudioEffectsController constructor(
    private val repository: AudioSettingsRepository,
    dispatcher: CoroutineDispatcher
) {
    val equalizer = BiquadEqualizerProcessor()
    val normalizer = NormalizationProcessor()
    val reactiveMeter = MusicReactiveMeterProcessor()

    private val secondaryEqualizer = BiquadEqualizerProcessor()
    private val secondaryNormalizer = NormalizationProcessor()

    /** The processors in the order the primary audio sink runs them. */
    val processors: Array<AudioProcessor> get() = arrayOf(equalizer, normalizer, reactiveMeter)

    /**
     * Independent processor chain for the secondary/preloaded ExoPlayer so two concurrent
     * [androidx.media3.exoplayer.audio.DefaultAudioSink] threads never mutate the same DSP state.
     */
    fun createProcessorChain(usePrimarySlot: Boolean): Array<AudioProcessor> {
        val current = _settings.value
        return if (usePrimarySlot) {
            equalizer.setBands(current.eqEnabled, current.bandGainsDb)
            normalizer.setEnabled(current.normalize)
            arrayOf(equalizer, normalizer, reactiveMeter)
        } else {
            secondaryEqualizer.setBands(current.eqEnabled, current.bandGainsDb)
            secondaryNormalizer.setEnabled(current.normalize)
            arrayOf(secondaryEqualizer, secondaryNormalizer)
        }
    }

    /** Updates loudness on the secondary/preloaded player's normalizer before crossfade starts. */
    fun setSecondaryTrackLoudness(loudnessDb: Float?) = secondaryNormalizer.setTrackLoudnessDb(loudnessDb)

    val beatIntensity: StateFlow<Float>
        get() = reactiveMeter.beatIntensity

    fun setReactiveMeterEnabled(enabled: Boolean) = reactiveMeter.setEnabled(enabled)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _settings = MutableStateFlow(AudioSettings())
    val settings: StateFlow<AudioSettings> = _settings.asStateFlow()
    private var persistJob: Job? = null
    @Volatile private var hasUserModified = false

    init {
        // Restore what was saved, then keep the processors in sync with every change.
        scope.launch {
            runCatching { repository.settings.first() }.getOrNull()?.let { saved ->
                if (!hasUserModified) {
                    _settings.value = saved
                }
            }
        }
        _settings.onEach { apply(it) }.launchIn(scope)
    }

    fun update(transform: (AudioSettings) -> AudioSettings) {
        hasUserModified = true
        val next = _settings.updateAndGet { transform(it).sanitized() }
        apply(next)
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DELAY_MS)
            repository.update { next }
        }
    }

    /** Called when a new stream is about to play; feeds the volume normaliser (null = loudness unknown). */
    fun setTrackLoudness(loudnessDb: Float?) = normalizer.setTrackLoudnessDb(loudnessDb)

    private fun apply(s: AudioSettings) {
        equalizer.setBands(s.eqEnabled, s.bandGainsDb)
        normalizer.setEnabled(s.normalize)
        secondaryEqualizer.setBands(s.eqEnabled, s.bandGainsDb)
        secondaryNormalizer.setEnabled(s.normalize)
    }

    private companion object {
        const val PERSIST_DELAY_MS = 400L
    }
}
