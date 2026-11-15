package com.geekify.android.ui.ascii

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AsciiDrawPathTest {

    @Test
    fun `render N frames and compare real canvas pixels between direct renderer and buffered AsciiFrame`() {
        val animalId = "koi"
        val spec = AsciiCatalog.byId(animalId)
        val config = AsciiBackgroundConfig(animalId = animalId)
        val tuning = AsciiTuning().apply { syncFrom(config, spec) }
        val renderer = AsciiRenderer(tuning)
        val sim = AsciiSimulationFactory.create(animalId, tuning)

        val w = 320
        val h = 240
        renderer.resize(w.toFloat(), h.toFloat())
        sim.resize(w.toFloat(), h.toFloat())
        sim.onSelect()

        var time = 0f
        val dt = 0.033f
        val totalFrames = 5
        val frame = AsciiFrame()

        val fillPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
        val textPaint = Paint().apply {
            isAntiAlias = true
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val charBuf = CharArray(1)

        for (f in 1..totalFrames) {
            time += dt
            renderer.begin(dt, time)
            sim.step(dt, time, renderer)
            frame.record(renderer, time)
        }

        // 1. Render direct path into Bitmap 1
        val directBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val directCanvas = Canvas(directBitmap)
        renderer.render(directCanvas)

        // 2. Render buffered AsciiFrame into Bitmap 2
        val bufferedBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bufferedCanvas = Canvas(bufferedBitmap)
        val pic = frame.picture
        if (pic != null) {
            bufferedCanvas.drawPicture(pic)
        } else {
            frame.renderFallback(bufferedCanvas, fillPaint, textPaint, charBuf)
        }

        // 3. Pixel comparison
        val totalPixels = w * h
        val directPixels = IntArray(totalPixels)
        val bufferedPixels = IntArray(totalPixels)
        directBitmap.getPixels(directPixels, 0, w, 0, 0, w, h)
        bufferedBitmap.getPixels(bufferedPixels, 0, w, 0, 0, w, h)

        var matchingPixels = 0
        for (i in 0 until totalPixels) {
            val p1 = directPixels[i]
            val p2 = bufferedPixels[i]
            if (p1 == p2) {
                matchingPixels++
            } else {
                val rDiff = abs(((p1 ushr 16) and 0xFF) - ((p2 ushr 16) and 0xFF))
                val gDiff = abs(((p1 ushr 8) and 0xFF) - ((p2 ushr 8) and 0xFF))
                val bDiff = abs((p1 and 0xFF) - (p2 and 0xFF))
                // Allow font antialiasing edge variation on text glyph boundaries
                if (rDiff <= 16 && gDiff <= 16 && bDiff <= 16) {
                    matchingPixels++
                }
            }
        }

        val matchRate = matchingPixels.toFloat() / totalPixels.toFloat()
        assertTrue(
            "Expected pixel match rate >= 95%, got ${(matchRate * 100f)}% ($matchingPixels/$totalPixels)",
            matchRate >= 0.95f
        )
    }
}
