package com.geekify.android.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Five-band parametric equalizer built from RBJ "peaking" biquad filters, applied to 16-bit or 32-bit float PCM.
 *
 * Gains can change at any moment from the UI thread: [setBands] only stores the new values, and the audio
 * thread recomputes coefficients at the start of the next buffer. No player re-initialisation is needed.
 * Mild headroom compensation plus a smooth soft-limiter prevent clipping while preserving full perceived boost.
 */
@OptIn(UnstableApi::class)
class BiquadEqualizerProcessor : BaseAudioProcessor() {

    @Volatile private var enabled = false
    @Volatile private var requestedGains: FloatArray = FloatArray(BANDS)

    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var appliedGains = FloatArray(BANDS) { Float.NaN }
    private var appliedEnabled = false
    private var preGain = 1f

    // Per band: b0, b1, b2, a1, a2 (already divided by a0). Bands with ~0 dB are bypassed.
    private val coef = Array(BANDS) { FloatArray(5) }
    private val bandActive = BooleanArray(BANDS)
    // Per channel and band: x1, x2, y1, y2.
    private var history: Array<FloatArray> = emptyArray()

    fun setBands(enabled: Boolean, gainsDb: List<Float>) {
        requestedGains = FloatArray(BANDS) { (gainsDb.getOrNull(it) ?: 0f).coerceIn(-MAX_DB, MAX_DB) }
        this.enabled = enabled
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if ((enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) || inputAudioFormat.channelCount <= 0) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        encoding = enc
        history = Array(channels * BANDS) { FloatArray(4) }
        appliedGains = FloatArray(BANDS) { Float.NaN }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(size).order(ByteOrder.nativeOrder())
        val on = enabled
        if (!on || channels == 0) {
            out.put(inputBuffer)
        } else {
            refreshCoefficients(on)
            if (encoding == C.ENCODING_PCM_FLOAT) {
                val frames = size / (4 * channels)
                for (frame in 0 until frames) {
                    for (ch in 0 until channels) {
                        val x = inputBuffer.getFloat() * preGain
                        out.putFloat(processSample(x, ch))
                    }
                }
            } else {
                val frames = size / (2 * channels)
                for (frame in 0 until frames) {
                    for (ch in 0 until channels) {
                        val x = (inputBuffer.getShort() / 32768f) * preGain
                        val y = processSample(x, ch)
                        out.putShort((y * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
                    }
                }
            }
            while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        }
        out.flip()
    }

    private fun processSample(input: Float, ch: Int): Float {
        var x = input
        for (band in 0 until BANDS) {
            if (!bandActive[band]) continue
            val c = coef[band]
            val h = history[ch * BANDS + band]
            val y = c[0] * x + c[1] * h[0] + c[2] * h[1] - c[3] * h[2] - c[4] * h[3]
            h[1] = h[0]
            h[0] = x
            h[3] = h[2]
            h[2] = if (y.isFinite()) y else 0f
            x = h[2]
        }
        return softLimit(x)
    }

    private fun softLimit(x: Float): Float {
        val a = abs(x)
        if (a <= LIMITER_KNEE) return x
        val limited = LIMITER_KNEE + (1f - LIMITER_KNEE) * tanh((a - LIMITER_KNEE) / (1f - LIMITER_KNEE))
        return if (x < 0f) -limited else limited
    }

    override fun onFlush() {
        history.forEach { it.fill(0f) }
    }

    override fun onReset() {
        history = emptyArray()
        channels = 0
        sampleRate = 0
        encoding = C.ENCODING_INVALID
    }

    private fun refreshCoefficients(on: Boolean) {
        val gains = requestedGains
        if (on == appliedEnabled && gains.contentEquals(appliedGains)) return
        appliedEnabled = on
        appliedGains = gains.copyOf()
        // Mild headroom compensation (-0.22 dB per dB of peak boost) paired with the soft limiter
        // keeps boosted bands audibly punchy without cutting overall volume in half.
        val maxBoostDb = (gains.maxOrNull() ?: 0f).coerceAtLeast(0f)
        preGain = 10f.pow(-(maxBoostDb * 0.22f) / 20f)
        for (band in 0 until BANDS) {
            val gainDb = gains[band]
            val freq = EqPresets.BAND_HZ[band].toDouble()
            if (abs(gainDb) < 0.05f || freq >= 0.45 * sampleRate) {
                bandActive[band] = false
                continue
            }
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * freq / sampleRate
            val alpha = sin(w0) / (2.0 * Q)
            val cosW = cos(w0)
            val a0 = 1.0 + alpha / a
            coef[band][0] = ((1.0 + alpha * a) / a0).toFloat()
            coef[band][1] = ((-2.0 * cosW) / a0).toFloat()
            coef[band][2] = ((1.0 - alpha * a) / a0).toFloat()
            coef[band][3] = ((-2.0 * cosW) / a0).toFloat()
            coef[band][4] = ((1.0 - alpha / a) / a0).toFloat()
            bandActive[band] = true
        }
    }

    private companion object {
        const val BANDS = 5
        const val MAX_DB = 12f
        const val Q = 0.9
        const val LIMITER_KNEE = 0.85f
    }
}
