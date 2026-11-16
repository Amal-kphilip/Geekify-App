package com.geekify.android.ui.ascii

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AsciiDrawPathTest {

    private fun testSceneFidelity(sceneId: String, totalFrames: Int = 5) {
        val spec = AsciiCatalog.byId(sceneId)
        val config = AsciiBackgroundConfig(animalId = sceneId)
        val tuning = AsciiTuning().apply { syncFrom(config, spec) }
        val renderer = AsciiRenderer(tuning)
        val sim = AsciiSimulationFactory.create(sceneId, tuning)

        val w = 320
        val h = 240
        renderer.resize(w.toFloat(), h.toFloat())
        sim.resize(w.toFloat(), h.toFloat())
        sim.onSelect()

        var time = 0f
        val dt = 0.033f
        val frame = AsciiFrame()

        for (f in 1..totalFrames) {
            time += dt
            renderer.begin(dt, time)
            sim.step(dt, time, renderer)
            frame.record(renderer, time)
        }

        // 1. Direct path: direct Skia drawText/drawRect calls into Direct Bitmap
        val directBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val directCanvas = Canvas(directBitmap)
        renderer.render(directCanvas)

        // 2. New path: Glyph-atlas software blit into Bitmap
        val blitBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val blitCanvas = Canvas(blitBitmap)
        val frameBitmap = frame.bitmap
        assertTrue("Frame bitmap must not be null in atlas blit path", frameBitmap != null)
        blitCanvas.drawBitmap(frameBitmap!!, 0f, 0f, null)

        // 3. Pixel comparison
        val totalPixels = w * h
        val directPixels = IntArray(totalPixels)
        val blitPixels = IntArray(totalPixels)
        directBitmap.getPixels(directPixels, 0, w, 0, 0, w, h)
        blitBitmap.getPixels(blitPixels, 0, w, 0, 0, w, h)

        val bgR = clampF(renderer.activeBg[0], 0f, 255f).toInt()
        val bgG = clampF(renderer.activeBg[1], 0f, 255f).toInt()
        val bgB = clampF(renderer.activeBg[2], 0f, 255f).toInt()
        val bgPixel = (0xFF shl 24) or (bgR shl 16) or (bgG shl 8) or bgB

        var nonBgPixelsDirect = 0
        var nonBgPixelsBlit = 0
        var diffLe16 = 0
        var diffLe24 = 0
        var diffLe32 = 0
        var maxChannelDiff = 0
        var differingPixels = 0

        for (i in 0 until totalPixels) {
            val p1 = directPixels[i]
            val p2 = blitPixels[i]
            if (p1 != bgPixel) nonBgPixelsDirect++
            if (p2 != bgPixel) nonBgPixelsBlit++

            val rDiff = abs(((p1 ushr 16) and 0xFF) - ((p2 ushr 16) and 0xFF))
            val gDiff = abs(((p1 ushr 8) and 0xFF) - ((p2 ushr 8) and 0xFF))
            val bDiff = abs((p1 and 0xFF) - (p2 and 0xFF))
            val chDiff = maxOf(rDiff, gDiff, bDiff)

            if (chDiff > maxChannelDiff) {
                maxChannelDiff = chDiff
            }

            if (chDiff <= 16) diffLe16++
            if (chDiff <= 24) diffLe24++
            if (chDiff <= 32) diffLe32++

            // Allow anti-aliasing edge variations on font boundaries (threshold 32/255)
            if (chDiff <= 32) {
                // considered matching for visual fidelity
            } else {
                differingPixels++
            }
        }

        val matchRate = (totalPixels - differingPixels).toFloat() / totalPixels.toFloat()
        val mismatchPercent = (differingPixels.toFloat() / totalPixels.toFloat()) * 100f
        val pctLe16 = (diffLe16.toFloat() / totalPixels.toFloat()) * 100f
        val pctLe24 = (diffLe24.toFloat() / totalPixels.toFloat()) * 100f
        val pctLe32 = (diffLe32.toFloat() / totalPixels.toFloat()) * 100f

        println(
            "Scene [$sceneId]: totalPixels=$totalPixels, nonBgDirect=$nonBgPixelsDirect, " +
                "nonBgBlit=$nonBgPixelsBlit, maxChannelDiff=$maxChannelDiff, " +
                "le16=${"%.2f".format(pctLe16)}%, le24=${"%.2f".format(pctLe24)}%, le32=${"%.2f".format(pctLe32)}%, mismatch=${"%.2f".format(mismatchPercent)}%"
        )

        // Verify that the test is actually drawing non-background glyphs and content
        assertTrue(
            "Scene [$sceneId] direct must have non-background pixels, got $nonBgPixelsDirect",
            nonBgPixelsDirect > 0
        )
        assertTrue(
            "Scene [$sceneId] blit must have non-background pixels, got $nonBgPixelsBlit",
            nonBgPixelsBlit > 0
        )

        // Verify match rate >= 95% (mismatch <= 5%)
        assertTrue(
            "Scene [$sceneId] expected pixel match rate >= 95%, got ${(matchRate * 100f)}% (mismatch: ${mismatchPercent}%)",
            matchRate >= 0.95f
        )
    }

    @Test
    fun testWavesFidelity() {
        testSceneFidelity("waves")
    }

    @Test
    fun testAuroraFidelity() {
        testSceneFidelity("aurora")
    }

    @Test
    fun testButterfliesFidelity() {
        testSceneFidelity("butterflies")
    }

    @Test
    fun testKoiFidelity() {
        testSceneFidelity("koi")
    }

    @Test
    fun testAsciiGlyphAtlasCaching() {
        val atlas1 = AsciiGlyphAtlas.getOrCreate(8f, 12f)
        val atlas2 = AsciiGlyphAtlas.getOrCreate(8f, 12f)
        org.junit.Assert.assertSame("Same (cw, ch) must return cached atlas", atlas1, atlas2)

        val atlas3 = AsciiGlyphAtlas.getOrCreate(10f, 15f)
        org.junit.Assert.assertNotSame("Different (cw, ch) must return new atlas", atlas1, atlas3)
    }

    @Test
    fun benchmarkTabletPerformanceWavesAndAurora() {
        // Pixel Tablet landscape: 2560x1600 at density 2.0 (1280dp wide)
        // scaleFactor = (2.0 * 0.58).coerceIn(1.0, 2.2) = 1.16
        val simW = 2560f / 1.16f
        val simH = 1600f / 1.16f
        val widthDp = 1280f
        val detail = 2.0f

        for (sceneId in listOf("waves", "aurora")) {
            val spec = AsciiCatalog.byId(sceneId)
            val config = AsciiBackgroundConfig(animalId = sceneId, detail = detail)
            val tuning = AsciiTuning().apply { syncFrom(config, spec) }
            val renderer = AsciiRenderer(tuning)
            val sim = AsciiSimulationFactory.create(sceneId, tuning)

            renderer.resize(simW, simH, widthDp)
            sim.resize(simW, simH)
            sim.onSelect()

            val frame = AsciiFrame()
            val dt = 0.033f
            var time = 0f

            // Warmup
            for (i in 0 until 10) {
                time += dt
                renderer.begin(dt, time)
                sim.step(dt, time, renderer)
                frame.record(renderer, time)
            }

            // Timed run: 60 frames
            val frames = 60
            var totalSimNanos = 0L
            var totalBlitNanos = 0L

            for (i in 0 until frames) {
                time += dt
                val t0 = System.nanoTime()
                renderer.begin(dt, time)
                sim.step(dt, time, renderer)
                val t1 = System.nanoTime()
                frame.record(renderer, time)
                val t2 = System.nanoTime()

                totalSimNanos += (t1 - t0)
                totalBlitNanos += (t2 - t1)
            }

            val avgSimMs = (totalSimNanos.toDouble() / frames) / 1_000_000.0
            val avgBlitMs = (totalBlitNanos.toDouble() / frames) / 1_000_000.0
            val avgTotalMs = avgSimMs + avgBlitMs

            println("BENCHMARK [$sceneId]: cells=${renderer.cols * renderer.rows} (cols=${renderer.cols}, rows=${renderer.rows}, cw=${renderer.cw}, ch=${renderer.ch}) | sim=${"%.2f".format(avgSimMs)}ms | blit=${"%.2f".format(avgBlitMs)}ms | total=${"%.2f".format(avgTotalMs)}ms")
        }
    }
}
