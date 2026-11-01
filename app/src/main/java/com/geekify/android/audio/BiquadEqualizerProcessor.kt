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
 * Gains and enable/disable state can change at any moment from the UI thread: [setBands] stores the target
 * values, and the audio thread smoothly interpolates band gains, biquad coefficients, and headroom pre-gain
 * across 32-frame sub-blocks so preset switches, slider movements, and EQ toggles never produce audible clicks.
 */
@OptIn(UnstableApi::class)
class BiquadEqualizerProcessor : BaseAudioProcessor() {

    @Volatile private var enabled = false
    @Volatile private var requestedGains: FloatArray = FloatArray(BANDS)

    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID

    /** Currently smoothed gain (dB) per band. Ramps smoothly toward [requestedGains] (or 0 dB when disabled). */
    private var smoothedGains = FloatArray(BANDS)
    private var preGain = 1f
    private var historyClearedWhileBypassed = true

    // Per band: b0, b1, b2, a1, a2 (already divided by a0). Bands with ~0 dB are bypassed.
    private val coef = Array(BANDS) { FloatArray(5).also { it[0] = 1f } }
    private val bandActive = BooleanArray(BANDS)
    // Per channel and band: x1, x2, y1, y2.
    private var history: Array<FloatArray> = emptyArray()

    fun setBands(enabled: Boolean, gainsDb: List<Float>) {
        requestedGains = FloatArray(BANDS) { (gainsDb.getOrNull(it) ?: 0f).coerceIn(-MAX_DB, MAX_DB) }
        this.enabled = enabled
    }

    internal fun currentBandGains(): FloatArray = smoothedGains.copyOf()

    internal fun currentCoefficients(band: Int): FloatArray = coef[band].copyOf()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if ((enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) || inputAudioFormat.channelCount <= 0) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        encoding = enc
        history = Array(channels * BANDS) { FloatArray(4) }
        smoothedGains = FloatArray(BANDS)
        preGain = 1f
        historyClearedWhileBypassed = true
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(size).order(ByteOrder.nativeOrder())

        if (channels == 0 || isSettledBypass()) {
            if (!historyClearedWhileBypassed) {
                history.forEach { it.fill(0f) }
                historyClearedWhileBypassed = true
            }
            out.put(inputBuffer)
            out.flip()
            return
        }

        historyClearedWhileBypassed = false
        if (encoding == C.ENCODING_PCM_FLOAT) {
            val frames = size / (4 * channels)
            for (frame in 0 until frames) {
                if (frame % SMOOTHING_SUBBLOCK_FRAMES == 0) {
                    stepSmoothedCoefficients()
                }
                for (ch in 0 until channels) {
                    val x = inputBuffer.getFloat() * preGain
                    out.putFloat(processSample(x, ch))
                }
            }
        } else {
            val frames = size / (2 * channels)
            for (frame in 0 until frames) {
                if (frame % SMOOTHING_SUBBLOCK_FRAMES == 0) {
                    stepSmoothedCoefficients()
                }
                for (ch in 0 until channels) {
                    val x = (inputBuffer.getShort() / 32768f) * preGain
                    val y = processSample(x, ch)
                    out.putShort((y * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
        }
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }

    private fun isSettledBypass(): Boolean {
        if (enabled) return false
        for (band in 0 until BANDS) {
            if (abs(smoothedGains[band]) >= BYPASS_EPSILON_DB) return false
        }
        return true
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
        smoothedGains = FloatArray(BANDS)
        channels = 0
        sampleRate = 0
        encoding = C.ENCODING_INVALID
        preGain = 1f
        historyClearedWhileBypassed = true
    }

    /**
     * Advances [smoothedGains] toward the target gains by one sub-block step and updates
     * biquad filter coefficients and headroom [preGain] without clearing filter state.
     */
    internal fun stepSmoothedCoefficients(effectiveSampleRate: Int = sampleRate) {
        val sr = if (effectiveSampleRate > 0) effectiveSampleRate else 48_000
        val on = enabled
        val targets = requestedGains
        var maxBoostDb = 0f

        for (band in 0 until BANDS) {
            val targetDb = if (on) targets[band] else 0f
            val currentDb = smoothedGains[band]
            val delta = targetDb - currentDb
            val nextDb = if (abs(delta) <= SNAP_EPSILON_DB) {
                targetDb
            } else {
                currentDb + delta * SMOOTHING_ALPHA
            }
            smoothedGains[band] = nextDb
            if (nextDb > maxBoostDb) maxBoostDb = nextDb

            val freq = EqPresets.BAND_HZ[band].toDouble()
            if (abs(nextDb) < BYPASS_EPSILON_DB || freq >= 0.45 * sr) {
                bandActive[band] = false
                coef[band][0] = 1f
                coef[band][1] = 0f
                coef[band][2] = 0f
                coef[band][3] = 0f
                coef[band][4] = 0f
                continue
            }
            val a = 10.0.pow(nextDb / 40.0)
            val w0 = 2.0 * PI * freq / sr
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

        // Mild headroom compensation (-0.22 dB per dB of peak boost) paired with the soft limiter
        // keeps boosted bands audibly punchy without cutting overall volume in half.
        val targetPreGain = 10f.pow(-(maxBoostDb * 0.22f) / 20f)
        preGain += (targetPreGain - preGain) * SMOOTHING_ALPHA
    }

    private companion object {
        const val BANDS = 5
        const val MAX_DB = 12f
        const val Q = 0.9
        const val LIMITER_KNEE = 0.85f
        const val SMOOTHING_SUBBLOCK_FRAMES = 32
        const val SMOOTHING_ALPHA = 0.22f
        const val SNAP_EPSILON_DB = 0.015f
        const val BYPASS_EPSILON_DB = 0.02f
    }
}
