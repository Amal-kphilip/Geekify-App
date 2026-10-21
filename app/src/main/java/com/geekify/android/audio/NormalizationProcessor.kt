package com.geekify.android.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Volume normalisation for 16-bit and float PCM:
 *  - When YouTube provides track `loudnessDb`, applies the exact reference loudness correction (-12..+6 dB).
 *  - When `loudnessDb` is missing (common on several InnerTube clients), falls back to a smooth adaptive RMS
 *    normaliser targeting -14 dBFS so "Normalize volume" always evens out quiet and loud tracks.
 *  - Runs all output through a soft limiter at 0.80 full-scale so boosts never clip or distort.
 */
@OptIn(UnstableApi::class)
class NormalizationProcessor : BaseAudioProcessor() {

    @Volatile private var enabled = false
    @Volatile private var hasTrackLoudness = false
    @Volatile private var metadataGain = 1f

    private var encoding = C.ENCODING_INVALID
    private var adaptiveGain = 1f
    private var runningRms = TARGET_RMS

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    /** Reported loudness of the track that is about to play; null triggers smooth adaptive RMS normalisation. */
    fun setTrackLoudnessDb(loudnessDb: Float?) {
        if (loudnessDb != null && loudnessDb.isFinite()) {
            hasTrackLoudness = true
            metadataGain = 10f.pow((-loudnessDb).coerceIn(MIN_CORRECTION_DB, MAX_CORRECTION_DB) / 20f)
        } else {
            hasTrackLoudness = false
            metadataGain = 1f
        }
        adaptiveGain = 1f
        runningRms = TARGET_RMS
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        encoding = enc
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(size).order(ByteOrder.nativeOrder())
        if (!enabled) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        if (encoding == C.ENCODING_PCM_FLOAT) {
            val g = currentGainForFloatBuffer(inputBuffer)
            while (inputBuffer.remaining() >= 4) {
                val x = inputBuffer.getFloat() * g
                out.putFloat(softLimit(x))
            }
        } else {
            val g = currentGainForShortBuffer(inputBuffer)
            while (inputBuffer.remaining() >= 2) {
                val x = (inputBuffer.getShort() / 32768f) * g
                out.putShort((softLimit(x) * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }

    private fun currentGainForShortBuffer(buf: ByteBuffer): Float {
        if (hasTrackLoudness) return metadataGain
        val startPos = buf.position()
        val count = buf.remaining() / 2
        if (count <= 0) return adaptiveGain
        var sumSq = 0.0
        var i = 0
        val step = maxOf(1, count / 128)
        while (i < count) {
            val s = buf.getShort(startPos + i * 2) / 32768.0
            sumSq += s * s
            i += step
        }
        val measuredSamples = (count + step - 1) / step
        val blockRms = sqrt(sumSq / measuredSamples.coerceAtLeast(1)).toFloat()
        return updateAdaptiveGain(blockRms)
    }

    private fun currentGainForFloatBuffer(buf: ByteBuffer): Float {
        if (hasTrackLoudness) return metadataGain
        val startPos = buf.position()
        val count = buf.remaining() / 4
        if (count <= 0) return adaptiveGain
        var sumSq = 0.0
        var i = 0
        val step = maxOf(1, count / 128)
        while (i < count) {
            val s = buf.getFloat(startPos + i * 4).toDouble()
            sumSq += s * s
            i += step
        }
        val measuredSamples = (count + step - 1) / step
        val blockRms = sqrt(sumSq / measuredSamples.coerceAtLeast(1)).toFloat()
        return updateAdaptiveGain(blockRms)
    }

    private fun updateAdaptiveGain(blockRms: Float): Float {
        // Ignore near-silence so quiet intros or gaps aren't artificially boosted.
        if (blockRms > SILENCE_FLOOR_RMS) {
            runningRms += (blockRms - runningRms) * 0.08f
            val desiredGain = (TARGET_RMS / runningRms.coerceAtLeast(0.02f)).coerceIn(MIN_GAIN_LINEAR, MAX_GAIN_LINEAR)
            val rate = if (desiredGain < adaptiveGain) 0.20f else 0.05f
            adaptiveGain += (desiredGain - adaptiveGain) * rate
        }
        return adaptiveGain
    }

    override fun onFlush() {
        if (!hasTrackLoudness) {
            adaptiveGain = 1f
            runningRms = TARGET_RMS
        }
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        adaptiveGain = 1f
        runningRms = TARGET_RMS
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
        const val TARGET_RMS = 0.20f // ~ -14 dBFS
        const val SILENCE_FLOOR_RMS = 0.008f
        const val MIN_GAIN_LINEAR = 0.35f // ~ -9 dB
        const val MAX_GAIN_LINEAR = 2.00f // ~ +6 dB
    }
}
