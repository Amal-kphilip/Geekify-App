package com.geekify.android.audio

import org.junit.Assert.assertEquals
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
}
