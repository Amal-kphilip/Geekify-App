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
    fun testGpuRendererCompilationAndDraw() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            AsciiGpuSupport.resetForTesting()
            val renderer = AsciiGpuRenderer()
            val frame = AsciiFrame(
                width = 320f,
                height = 240f,
                cols = 40,
                rows = 20,
                cw = 8f,
                ch = 12f,
                activeBg = floatArrayOf(10f, 10f, 15f),
                grid = 1f,
                trail = 1f,
                timeSec = 1f,
                contrast = floatArrayOf(150f, 160f, 200f),
                cellFlag = ByteArray(800) { CELL_SOLID },
                cellFill = IntArray(800) { 0xFF00FF00.toInt() },
                cellColor = IntArray(800) { 0xFFFFFFFF.toInt() },
                cellGlyph = CharArray(800) { '#' },
                energy = FloatArray(800) { 0.5f }
            )
            // Software bitmap canvas does not support RuntimeShader.
            // Renderer must gracefully catch this and switch to CPU fallback without crashing.
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val success = renderer.draw(canvas, frame, 1.0f, 1.0f)
            if (!success) {
                assertTrue(
                    "Should report software canvas limitation",
                    AsciiGpuSupport.disableReason?.contains("Software rendering") == true
                )
                assertTrue("GPU should be marked disabled on software canvas", AsciiGpuSupport.isGpuDisabled)
            }
        }
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
    fun benchmarkAvdMatrixCpuPath() {
        data class DeviceProfile(
            val name: String,
            val widthPx: Float,
            val heightPx: Float,
            val density: Float,
            val widthDp: Float
        )

        val matrix = listOf(
            DeviceProfile("Low-End Phone (API 28)", 720f, 1280f, 2.0f, 360f),
            DeviceProfile("Mid Phone (API 33)", 1080f, 2340f, 2.625f, 411f),
            DeviceProfile("Tablet (API 34)", 2560f, 1600f, 2.0f, 1280f),
            DeviceProfile("Foldable Folded", 1008f, 2268f, 2.625f, 384f),
            DeviceProfile("Foldable Unfolded", 1878f, 2346f, 2.125f, 884f),
            DeviceProfile("Real Device (Xiaomi Pad 6)", 1800f, 2880f, 2.5f, 720f)
        )

        for (profile in matrix) {
            val scaleFactor = (profile.density * 0.58f).coerceIn(1.0f, 2.2f)
            val simW = profile.widthPx / scaleFactor
            val simH = profile.heightPx / scaleFactor
            val detail = 2.0f

            for (sceneId in listOf("waves", "aurora")) {
                val spec = AsciiCatalog.byId(sceneId)
                val config = AsciiBackgroundConfig(animalId = sceneId, detail = detail)
                val tuning = AsciiTuning().apply { syncFrom(config, spec) }
                val renderer = AsciiRenderer(tuning)
                val sim = AsciiSimulationFactory.create(sceneId, tuning)

                renderer.resize(simW, simH, profile.widthDp)
                sim.resize(simW, simH)
                sim.onSelect()

                val frame = AsciiFrame()
                val dt = 0.033f
                var time = 0f

                // Warmup
                for (i in 0 until 5) {
                    time += dt
                    renderer.begin(dt, time)
                    sim.step(dt, time, renderer)
                    frame.record(renderer, time)
                }

                val frames = 30
                var totalSimNanos = 0L
                var totalBlitNanos = 0L
                var totalCopyNanos = 0L

                val w = kotlin.math.ceil(renderer.width).toInt().coerceAtLeast(1)
                val h = kotlin.math.ceil(renderer.height).toInt().coerceAtLeast(1)
                val pixelBuffer = IntArray(w * h)
                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val atlas = AsciiGlyphAtlas.getOrCreate(renderer.cw, renderer.ch)

                for (i in 0 until frames) {
                    time += dt
                    val t0 = System.nanoTime()
                    renderer.begin(dt, time)
                    sim.step(dt, time, renderer)
                    val t1 = System.nanoTime()

                    frame.copyFrom(renderer, time)
                    frame.blit(renderer, time, atlas, pixelBuffer, w, h)
                    val t2 = System.nanoTime()

                    bmp.setPixels(pixelBuffer, 0, w, 0, 0, w, h)
                    val t3 = System.nanoTime()

                    totalSimNanos += (t1 - t0)
                    totalBlitNanos += (t2 - t1)
                    totalCopyNanos += (t3 - t2)
                }

                val avgSimMs = (totalSimNanos.toDouble() / frames) / 1_000_000.0
                val avgBlitMs = (totalBlitNanos.toDouble() / frames) / 1_000_000.0
                val avgCopyMs = (totalCopyNanos.toDouble() / frames) / 1_000_000.0
                // Upload estimate for CPU texture upload over PCIe/mobile bus: ~0.8ms per megapixel
                val mp = (w * h) / 1_000_000.0
                val avgUploadMs = mp * 0.85
                val totalFrameMs = avgSimMs + avgBlitMs + avgCopyMs + avgUploadMs
                val achievedFps = if (totalFrameMs > 0.0) 1000.0 / totalFrameMs else 0.0

                println("MATRIX [${profile.name} | $sceneId]: res=${w}x${h} (${"%.2f".format(mp)}MP) cells=${renderer.cols * renderer.rows} | sim=${"%.2f".format(avgSimMs)}ms | blit=${"%.2f".format(avgBlitMs)}ms | copy=${"%.2f".format(avgCopyMs)}ms | upload=${"%.2f".format(avgUploadMs)}ms | total=${"%.2f".format(totalFrameMs)}ms | achievedFps=${"%.1f".format(achievedFps)}")
            }
        }
    }
}
