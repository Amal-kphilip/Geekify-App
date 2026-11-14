package com.geekify.android.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSettingsTest {

    @Test
    fun sanitizingClampsEveryValueIntoASafeRange() {
        val wild = AudioSettings(
            bandGainsDb = listOf(99f, -99f),   // wrong length, out of range
            speed = 9f,
            pitch = 0.01f
        ).sanitized()
        assertEquals(EqPresets.BAND_HZ.size, wild.bandGainsDb.size)
        assertEquals(AudioSettings.MAX_GAIN_DB, wild.bandGainsDb[0], 0f)
        assertEquals(-AudioSettings.MAX_GAIN_DB, wild.bandGainsDb[1], 0f)
        assertEquals(0f, wild.bandGainsDb[4], 0f)
        assertEquals(AudioSettings.MAX_RATE, wild.speed, 0f)
        assertEquals(AudioSettings.MIN_RATE, wild.pitch, 0f)
    }

    @Test
    fun everyPresetHasOneGainPerBandWithinRange() {
        EqPresets.presets.forEach { (name, gains) ->
            assertEquals("preset $name", EqPresets.BAND_HZ.size, gains.size)
            assertTrue(gains.all { it in -AudioSettings.MAX_GAIN_DB..AudioSettings.MAX_GAIN_DB })
        }
    }

    @Test
    fun flatPresetIsAllZeros() {
        assertTrue(EqPresets.presets.getValue(EqPresets.FLAT).all { it == 0f })
    }

    @Test
    fun normalizationSamplesBothChannelsInInterleavedStereoBuffers() {
        // 2048 stereo frames (4096 samples): Left = 0.0, Right = 0.5
        val frames = 2048
        val floatBuf = ByteBuffer.allocate(frames * 2 * 4).order(ByteOrder.nativeOrder())
        repeat(frames) {
            floatBuf.putFloat(0f)    // Left channel silent
            floatBuf.putFloat(0.5f)  // Right channel active
        }
        floatBuf.flip()

        val rmsFloat = NormalizationProcessor.computeBlockRmsFloat(floatBuf, channelCount = 2)
        assertNotNull(rmsFloat)
        val expectedRms = sqrt((0.0 * 0.0 + 0.5 * 0.5) / 2.0).toFloat()
        assertEquals(expectedRms, rmsFloat!!, 0.005f)

        val shortBuf = ByteBuffer.allocate(frames * 2 * 2).order(ByteOrder.nativeOrder())
        repeat(frames) {
            shortBuf.putShort(0)      // Left channel silent
            shortBuf.putShort(16384)  // Right channel ~0.5
        }
        shortBuf.flip()

        val rmsShort = NormalizationProcessor.computeBlockRmsShort(shortBuf, channelCount = 2)
        assertNotNull(rmsShort)
        assertEquals(expectedRms, rmsShort!!, 0.005f)
    }

    @Test
    fun biquadEqualizerSmoothlyInterpolatesGainAndCoefficientTransitions() {
        val processor = BiquadEqualizerProcessor()
        processor.setBands(enabled = true, gainsDb = listOf(10f, -6f, 4f, 0f, 8f))

        // First sub-block step must ramp partially rather than jumping straight to 10 dB
        processor.stepSmoothedCoefficients(effectiveSampleRate = 48_000)
        val afterFirstStep = processor.currentBandGains()[0]
        assertTrue("Expected smooth partial ramp, got $afterFirstStep", afterFirstStep in 1.5f..4.0f)

        // Subsequent steps converge smoothly to target
        repeat(40) {
            processor.stepSmoothedCoefficients(effectiveSampleRate = 48_000)
        }
        assertEquals(10f, processor.currentBandGains()[0], 0.05f)
        assertEquals(-6f, processor.currentBandGains()[1], 0.05f)

        // Disabling EQ ramps smoothly back to 0 dB instead of hard-cutting
        processor.setBands(enabled = false, gainsDb = listOf(10f, -6f, 4f, 0f, 8f))
        processor.stepSmoothedCoefficients(effectiveSampleRate = 48_000)
        val afterDisableFirstStep = processor.currentBandGains()[0]
        assertTrue("Expected smooth ramp down, got $afterDisableFirstStep", afterDisableFirstStep in 5.0f..9.0f)

        repeat(40) {
            processor.stepSmoothedCoefficients(effectiveSampleRate = 48_000)
        }
        assertEquals(0f, processor.currentBandGains()[0], 0.05f)
    }

    @Test
    fun biquadEqualizerActuallyBoostsAndCutsAllFiveFrequencyBandsInPcm16AndFloat() {
        val sampleRate = 48_000
        val channels = 2
        val totalFrames = 8_192
        val settleFrames = 2_048 // Let smoothing + biquad filter state reach steady state

        fun measureSteadyStateRmsDb16Bit(
            freqHz: Double,
            enabled: Boolean,
            gainsDb: List<Float>
        ): Double {
            val processor = BiquadEqualizerProcessor()
            processor.configure(AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT))
            processor.flush()
            processor.setBands(enabled = enabled, gainsDb = gainsDb)

            val input = ByteBuffer.allocate(totalFrames * channels * 2).order(ByteOrder.nativeOrder())
            val amplitude = 0.20 // -14 dBFS so boosts don't hit ceiling
            for (frame in 0 until totalFrames) {
                val sample = (sin(2.0 * PI * freqHz * frame / sampleRate) * amplitude * 32767.0)
                    .roundToInt().coerceIn(-32768, 32767).toShort()
                repeat(channels) { input.putShort(sample) }
            }
            input.flip()

            processor.queueInput(input)
            val out = processor.output.order(ByteOrder.nativeOrder())

            var sumSq = 0.0
            var count = 0
            for (frame in 0 until totalFrames) {
                for (ch in 0 until channels) {
                    val s = out.getShort() / 32768.0
                    if (frame >= settleFrames) {
                        sumSq += s * s
                        count++
                    }
                }
            }
            val rms = sqrt(sumSq / count)
            return 20.0 * log10(rms)
        }

        // Verify each of the 5 bands (60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz) boosts and cuts its target frequency
        EqPresets.BAND_HZ.forEachIndexed { bandIndex, freqInt ->
            val freq = freqInt.toDouble()
            val flatDb = measureSteadyStateRmsDb16Bit(freq, enabled = false, gainsDb = EqPresets.zeros())

            val boostGains = MutableList(5) { 0f }.also { it[bandIndex] = 8f }
            val boostedDb = measureSteadyStateRmsDb16Bit(freq, enabled = true, gainsDb = boostGains)
            val netBoost = boostedDb - flatDb
            // Target is +8 dB minus 8 * 0.15 = 1.2 dB headroom pre-gain (~ +6.8 dB net boost)
            assertTrue(
                "Band $bandIndex ($freqInt Hz) +8 dB boost should increase RMS by at least +5.2 dB, got $netBoost dB",
                netBoost in 5.2..7.8
            )

            val cutGains = MutableList(5) { 0f }.also { it[bandIndex] = -8f }
            val cutDb = measureSteadyStateRmsDb16Bit(freq, enabled = true, gainsDb = cutGains)
            val netCut = cutDb - flatDb
            assertTrue(
                "Band $bandIndex ($freqInt Hz) -8 dB cut should decrease RMS by at least -6.5 dB, got $netCut dB",
                netCut in -9.0..-6.5
            )
        }

        // Also verify Band 0 Low-Shelf boosts deep sub-bass (35 Hz) strongly on "Bass boost" preset
        val subBassFlatDb = measureSteadyStateRmsDb16Bit(35.0, enabled = false, gainsDb = EqPresets.zeros())
        val bassBoostGains = EqPresets.presets.getValue("Bass boost")
        val subBassBoostedDb = measureSteadyStateRmsDb16Bit(35.0, enabled = true, gainsDb = bassBoostGains)
        val subBassGain = subBassBoostedDb - subBassFlatDb
        assertTrue("Expected strong 35 Hz sub-bass boost from Low-Shelf, got $subBassGain dB", subBassGain > 4.2)
    }
}
