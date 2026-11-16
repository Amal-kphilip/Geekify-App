package com.geekify.android.ui.home

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HeaderGlowTest {

    @Test
    fun asciiEnabled_glowHasNoOpaqueVerticalLayer() {
        val glow = CachedHeaderGlow(width = 1080f, height = 800f, asciiEnabled = true)
        assertNotNull(glow.violet)
        assertNotNull(glow.blue)
        assertNotNull(glow.pink)
        assertNotNull(glow.white)
        // With ASCII enabled, the opaque vertical fade to InkBackground must be omitted
        // so the ASCII animation canvas remains fully visible.
        assertNull(glow.vertical)
    }

    @Test
    fun asciiDisabled_glowIncludesVerticalFadeToInkBackground() {
        val glow = CachedHeaderGlow(width = 1080f, height = 800f, asciiEnabled = false)
        assertNotNull(glow.violet)
        assertNotNull(glow.blue)
        assertNotNull(glow.pink)
        assertNotNull(glow.white)
        // With ASCII disabled, the vertical gradient fades into InkBackground, matching page background.
        assertNotNull(glow.vertical)
    }
}
