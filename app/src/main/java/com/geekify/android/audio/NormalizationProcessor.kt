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
 *  - Samples whole multi-channel frames so interleaved stereo (L/R) is measured symmetrically across all channels.
 *  - Runs all output through a soft limiter at 0.80 full-scale so boosts never clip or distort.
 */
@OptIn(UnstableApi::class)
class NormalizationProcessor : BaseAudioProcessor() {

    @Volatile private var enabled = false
    @Volatile private var hasTrackLoudness = false
    @Volatile private var metadataGain = 1f

    private var encoding = C.ENCODING_INVALID
    private var channels = 2
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
        if ((enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) || inputAudioFormat.channelCount <= 0) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        encoding = enc
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
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
        val blockRms = computeBlockRmsShort(buf, channels) ?: return adaptiveGain
        return updateAdaptiveGain(blockRms)
    }

    private fun currentGainForFloatBuffer(buf: ByteBuffer): Float {
        if (hasTrackLoudness) return metadataGain
        val blockRms = computeBlockRmsFloat(buf, channels) ?: return adaptiveGain
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
        channels = 2
        adaptiveGain = 1f
        runningRms = TARGET_RMS
    }

    private fun softLimit(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val limited = KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))
        return if (x < 0f) -limited else limited
    }

    companion object {
        private const val KNEE = 0.8f
        private const val MIN_CORRECTION_DB = -12f
        private const val MAX_CORRECTION_DB = 6f
        private const val TARGET_RMS = 0.20f // ~ -14 dBFS
        private const val SILENCE_FLOOR_RMS = 0.008f
        private const val MIN_GAIN_LINEAR = 0.35f // ~ -9 dB
        private const val MAX_GAIN_LINEAR = 2.00f // ~ +6 dB

        /**
         * Computes RMS across interleaved 16-bit PCM frames by stepping in whole frames and
         * sampling all channels (`0 until channelCount`) at each visited frame.
         */
        internal fun computeBlockRmsShort(buf: ByteBuffer, channelCount: Int): Float? {
            val chCount = channelCount.coerceAtLeast(1)
            val startPos = buf.position()
            val totalSamples = buf.remaining() / 2
            val totalFrames = totalSamples / chCount
            if (totalFrames <= 0) return null

            val frameStep = maxOf(1, totalFrames / 128)
            var sumSq = 0.0
            var sampledCount = 0
            var frame = 0
            while (frame < totalFrames) {
                val baseSampleIndex = frame * chCount
                for (ch in 0 until chCount) {
                    val s = buf.getShort(startPos + (baseSampleIndex + ch) * 2) / 32768.0
                    sumSq += s * s
                    sampledCount++
                }
                frame += frameStep
            }
            return if (sampledCount > 0) sqrt(sumSq / sampledCount).toFloat() else null
        }

        /**
         * Computes RMS across interleaved 32-bit float PCM frames by stepping in whole frames and
         * sampling all channels (`0 until channelCount`) at each visited frame.
         */
        internal fun computeBlockRmsFloat(buf: ByteBuffer, channelCount: Int): Float? {
            val chCount = channelCount.coerceAtLeast(1)
            val startPos = buf.position()
            val totalSamples = buf.remaining() / 4
            val totalFrames = totalSamples / chCount
            if (totalFrames <= 0) return null

            val frameStep = maxOf(1, totalFrames / 128)
            var sumSq = 0.0
            var sampledCount = 0
            var frame = 0
            while (frame < totalFrames) {
                val baseSampleIndex = frame * chCount
                for (ch in 0 until chCount) {
                    val s = buf.getFloat(startPos + (baseSampleIndex + ch) * 4).toDouble()
                    sumSq += s * s
                    sampledCount++
                }
                frame += frameStep
            }
            return if (sampledCount > 0) sqrt(sumSq / sampledCount).toFloat() else null
        }
    }
}
