package com.geekify.android.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * Volume normalisation for 16-bit PCM: applies the gain that brings a track to YouTube's reference loudness
 * (from `loudnessDb`), then runs the signal through a soft limiter.
 *
 * The limiter is what keeps it safe: anything above 0.8 of full scale is bent smoothly towards (but never
 * reaches) full scale, so a boost can't clip or distort, and the correction itself is limited to -12..+6 dB.
 * When disabled the processor copies audio through unchanged. Settings may change from any thread.
 */
@OptIn(UnstableApi::class)
class NormalizationProcessor : BaseAudioProcessor() {

    @Volatile private var enabled = false
    @Volatile private var gain = 1f

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    /** Reported loudness of the track that is about to play; null (unknown) means no gain change. */
    fun setTrackLoudnessDb(loudnessDb: Float?) {
        gain = if (loudnessDb == null || !loudnessDb.isFinite()) 1f
        else 10f.pow((-loudnessDb).coerceIn(MIN_CORRECTION_DB, MAX_CORRECTION_DB) / 20f)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        if (!enabled) {
            out.put(inputBuffer)
        } else {
            val g = gain
            while (inputBuffer.remaining() >= 2) {
                val x = inputBuffer.getShort() / 32768f * g
                out.putShort((softLimit(x) * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
            while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        }
        out.flip()
    }

    private fun softLimit(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val limited = KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))
        return if (x < 0f) -limited else limited
    }

    private companion object {
        const val KNEE = 0.8f
        const val MIN_CORRECTION_DB = -12f
        const val MAX_CORRECTION_DB = 6f
    }
}
