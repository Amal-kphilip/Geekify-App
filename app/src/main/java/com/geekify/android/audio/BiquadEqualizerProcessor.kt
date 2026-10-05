package com.geekify.android.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Five-band parametric equalizer built from RBJ "peaking" biquad filters, applied to 16-bit PCM.
 *
 * Gains can change at any moment from the UI thread: [setBands] only stores the new values, and the audio
 * thread recomputes coefficients at the start of the next buffer. No player re-initialisation is needed.
 * Boosts are compensated by an automatic pre-gain (headroom) so they cannot clip, and the output is clamped.
 * Input in any other format (for example 32-bit float) leaves the processor inactive instead of failing playback.
 */
@OptIn(UnstableApi::class)
class BiquadEqualizerProcessor : BaseAudioProcessor() {

    @Volatile private var enabled = false
    @Volatile private var requestedGains: FloatArray = FloatArray(BANDS)

    private var sampleRate = 0
    private var channels = 0
    private var appliedGains = FloatArray(BANDS) { Float.NaN }
    private var appliedEnabled = false
    private var headroom = 1f

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
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount <= 0) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        history = Array(channels * BANDS) { FloatArray(4) }
        appliedGains = FloatArray(BANDS) { Float.NaN }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val on = enabled
        if (!on || channels == 0) {
            out.put(inputBuffer)
        } else {
            refreshCoefficients(on)
            val frames = size / (2 * channels)
            for (frame in 0 until frames) {
                for (ch in 0 until channels) {
                    var x = inputBuffer.getShort() / 32768f * headroom
                    for (band in 0 until BANDS) {
                        if (!bandActive[band]) continue
                        val c = coef[band]
                        val h = history[ch * BANDS + band]
                        val y = c[0] * x + c[1] * h[0] + c[2] * h[1] - c[3] * h[2] - c[4] * h[3]
                        h[1] = h[0]; h[0] = x
                        h[3] = h[2]; h[2] = y
                        x = y
                    }
                    out.putShort((x * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
            // A partial trailing frame (not expected) is passed through untouched.
            while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        }
        out.flip()
    }

    override fun onFlush() {
        history.forEach { it.fill(0f) }
    }

    override fun onReset() {
        history = emptyArray()
        channels = 0
        sampleRate = 0
    }

    private fun refreshCoefficients(on: Boolean) {
        val gains = requestedGains
        if (on == appliedEnabled && gains.contentEquals(appliedGains)) return
        appliedEnabled = on
        appliedGains = gains.copyOf()
        headroom = 10f.pow(-(gains.maxOrNull() ?: 0f).coerceAtLeast(0f) / 20f)
        for (band in 0 until BANDS) {
            val gainDb = gains[band]
            val freq = EqPresets.BAND_HZ[band].toDouble()
            // Skip near-flat bands and bands the sample rate cannot represent.
            if (kotlin.math.abs(gainDb) < 0.05f || freq >= 0.45 * sampleRate) {
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
        const val Q = 1.0
    }
}
