package com.geekify.android.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
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
}
