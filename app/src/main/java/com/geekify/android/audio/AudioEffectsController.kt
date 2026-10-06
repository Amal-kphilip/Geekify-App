package com.geekify.android.audio

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.geekify.android.di.DefaultDispatcher
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
import javax.inject.Inject
import javax.inject.Singleton

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
@Singleton
class AudioEffectsController @Inject constructor(
    private val repository: AudioSettingsRepository,
    @DefaultDispatcher dispatcher: CoroutineDispatcher
) {
    val equalizer = BiquadEqualizerProcessor()
    val normalizer = NormalizationProcessor()
    val reactiveMeter = MusicReactiveMeterProcessor()

    /** Read-only visual intensity derived from the existing PCM audio path. */
    val beatIntensity: StateFlow<Float> = reactiveMeter.beatIntensity

    /** The processors in the order the audio sink runs them. */
    val processors: Array<AudioProcessor> get() = arrayOf(equalizer, normalizer, reactiveMeter)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _settings = MutableStateFlow(AudioSettings())
    val settings: StateFlow<AudioSettings> = _settings.asStateFlow()
    private var persistJob: Job? = null

    init {
        // Restore what was saved, then keep the processors in sync with every change.
        scope.launch {
            runCatching { repository.settings.first() }.getOrNull()?.let { _settings.value = it }
        }
        _settings.onEach { apply(it) }.launchIn(scope)
    }

    fun update(transform: (AudioSettings) -> AudioSettings) {
        val next = _settings.updateAndGet { transform(it).sanitized() }
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DELAY_MS)
            repository.update { next }
        }
    }

    /** Called when a new stream is about to play; feeds the volume normaliser (null = loudness unknown). */
    fun setTrackLoudness(loudnessDb: Float?) = normalizer.setTrackLoudnessDb(loudnessDb)

    /** Enables the read-only meter only while the Now Playing artwork needs it. */
    fun setArtworkReactiveEnabled(enabled: Boolean) = reactiveMeter.setEnabled(enabled)

    private fun apply(s: AudioSettings) {
        equalizer.setBands(s.eqEnabled, s.bandGainsDb)
        normalizer.setEnabled(s.normalize)
    }

    private companion object {
        const val PERSIST_DELAY_MS = 400L
    }
}
