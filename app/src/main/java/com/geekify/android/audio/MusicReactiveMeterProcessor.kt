package com.geekify.android.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Lightweight read-only audio meter used by the Now Playing artwork animation.
 *
 * It never changes the audio: the input PCM is copied byte-for-byte to the output while a small
 * envelope detector measures short-term energy on the audio thread. A fast envelope is compared
 * with a slower baseline so the UI reacts to musical transients rather than sustained loudness.
 */
@OptIn(UnstableApi::class)
class MusicReactiveMeterProcessor : BaseAudioProcessor() {

    private val _beatIntensity = MutableStateFlow(0f)
    val beatIntensity: StateFlow<Float> = _beatIntensity.asStateFlow()

    @Volatile
    private var enabled = false

    private var initialized = false
    private var fastEnvelope = 0f
    private var slowEnvelope = 0f
    private var lastEmitNanos = 0L

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) resetMeter()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount > 0) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return

        val out = replaceOutputBuffer(size)

        if (!enabled) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        var sumSquares = 0.0
        var sampleCount = 0

        while (inputBuffer.remaining() >= 2) {
            val sample = inputBuffer.getShort()
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
            sampleCount++
            out.putShort(sample)
        }

        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()

        if (sampleCount == 0) return

        val rms = sqrt(sumSquares / sampleCount).toFloat().coerceIn(0f, 1f)
        // Convert loudness to a stable 0..1 range. The visual response is driven by the
        // transient detector below, so track mastering differences do not cause constant pulsing.
        val db = (20f * log10(rms.coerceAtLeast(0.0001f))).coerceIn(-48f, 0f)
        val normalizedLevel = ((db + 48f) / 48f).coerceIn(0f, 1f)

        if (!initialized) {
            fastEnvelope = normalizedLevel
            slowEnvelope = normalizedLevel
            initialized = true
        } else {
            val fastAttack = if (normalizedLevel > fastEnvelope) 0.42f else 0.10f
            val slowRate = 0.018f
            fastEnvelope += (normalizedLevel - fastEnvelope) * fastAttack
            slowEnvelope += (normalizedLevel - slowEnvelope) * slowRate
        }

        val intensity = ((fastEnvelope - slowEnvelope) * 5.0f).coerceIn(0f, 1f)
        val now = System.nanoTime()
        if (now - lastEmitNanos >= EMIT_INTERVAL_NANOS) {
            lastEmitNanos = now
            _beatIntensity.value = intensity
        }
    }

    override fun onFlush() {
        resetMeter()
    }

    override fun onReset() {
        resetMeter()
    }

    private fun resetMeter() {
        initialized = false
        fastEnvelope = 0f
        slowEnvelope = 0f
        lastEmitNanos = 0L
        _beatIntensity.value = 0f
    }

    private companion object {
        const val EMIT_INTERVAL_NANOS = 50_000_000L
    }
}
