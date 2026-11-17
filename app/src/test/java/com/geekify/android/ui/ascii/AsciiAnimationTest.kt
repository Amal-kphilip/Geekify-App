package com.geekify.android.ui.ascii

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.roundToInt

class AsciiAnimationTest {

    @Test
    fun `catalog contains all 7 creatures and 4 scenes with max detail and min grid defaults`() {
        assertEquals(7, AsciiCatalog.CREATURES.size)
        assertEquals(4, AsciiCatalog.SCENES.size)
        assertEquals(11, AsciiCatalog.ANIMALS.size)

        val defaultConfig = AsciiBackgroundConfig()
        assertEquals(MAX_ASCII_DETAIL, defaultConfig.detail, 0.0001f)
        assertEquals(MIN_ASCII_GRID, defaultConfig.grid, 0.0001f)
        org.junit.Assert.assertFalse(defaultConfig.multiColorButterflies)

        for (spec in AsciiCatalog.ANIMALS) {
            val cfg = AsciiBackgroundConfig(animalId = spec.id)
            assertEquals("Max detail for ${spec.id}", 2.0f, cfg.valueFor("detail", spec), 0.0001f)
            assertEquals("Min grid for ${spec.id}", 0.0f, cfg.valueFor("grid", spec), 0.0001f)
        }
    }

    @Test
    fun `all 11 simulations step and render ASCII cells and respond to pointer events`() {
        for (spec in AsciiCatalog.ANIMALS) {
            val config = AsciiBackgroundConfig(animalId = spec.id)
            val tuning = AsciiTuning().apply { syncFrom(config, spec) }
            val renderer = AsciiRenderer(tuning)
            val sim = AsciiSimulationFactory.create(spec.id, tuning)

            renderer.resize(640f, 420f)
            sim.resize(640f, 420f)
            sim.onSelect()

            var time = 0f
            val dt = 0.032f
            for (frame in 0 until 6) {
                time += dt
                renderer.begin(dt, time)
                if (frame == 2) {
                    sim.onPointerDown(320f, 210f)
                    sim.onPointerMove(340f, 220f)
                }
                sim.step(dt, time, renderer)
            }

            val nonEmptyCells = renderer.cellFlag.count { it != CELL_EMPTY }
            assertTrue("Expected non-empty ASCII cells for ${spec.id}, got $nonEmptyCells", nonEmptyCells > 0)
        }
    }

    @Test
    fun `color cycle and hue shift rotate rendered colors`() {
        val spec = AsciiCatalog.byId("koi")
        val baseConfig = AsciiBackgroundConfig(animalId = "koi", hueShift = 0f, colorCycle = false)
        val shiftedConfig = AsciiBackgroundConfig(animalId = "koi", hueShift = 120f, colorCycle = false)
        val cycleConfig = AsciiBackgroundConfig(animalId = "koi", hueShift = 0f, colorCycle = true, colorCycleSpeed = 1.5f)

        val rBase = AsciiRenderer(AsciiTuning().apply { syncFrom(baseConfig, spec) }).apply {
            resize(400f, 300f)
            begin(0.016f, 2.0f)
        }
        val rShifted = AsciiRenderer(AsciiTuning().apply { syncFrom(shiftedConfig, spec) }).apply {
            resize(400f, 300f)
            begin(0.016f, 2.0f)
        }
        val rCycle = AsciiRenderer(AsciiTuning().apply { syncFrom(cycleConfig, spec) }).apply {
            resize(400f, 300f)
            begin(0.016f, 2.0f)
        }

        val cBase = rBase.pen.mixRgb(240f, 120f, 40f, 0f)
        val cShifted = rShifted.pen.mixRgb(240f, 120f, 40f, 0f)
        val cCycle = rCycle.pen.mixRgb(240f, 120f, 40f, 0f)

        assertNotEquals(cBase, cShifted)
        assertNotEquals(cBase, cCycle)
    }

    @Test
    fun `multi-colored butterflies produce multiple distinct wing colors across flock`() {
        val spec = AsciiCatalog.byId("butterflies")
        val multiConfig = AsciiBackgroundConfig(animalId = "butterflies", multiColorButterflies = true)
        val tuning = AsciiTuning().apply { syncFrom(multiConfig, spec) }
        val renderer = AsciiRenderer(tuning)
        val sim = ButterfliesSim(tuning)

        renderer.resize(800f, 600f)
        sim.resize(800f, 600f)
        renderer.begin(0.016f, 1.0f)
        sim.step(0.016f, 1.0f, renderer)

        val uniqueColors = HashSet<Int>()
        for (i in renderer.cellFlag.indices) {
            if (renderer.cellFlag[i] == CELL_SOLID) {
                uniqueColors.add(renderer.cellColor[i])
            }
        }
        assertTrue("Expected multiple butterfly palette colors, found ${uniqueColors.size}", uniqueColors.size >= 4)
    }

    @Test
    fun `single-threaded direct execution vs AsciiFrame buffered copy produces bit-for-bit identical cell arrays`() {
        for (animalId in listOf("butterflies", "koi", "cranes", "rain", "aurora")) {
            val spec = AsciiCatalog.byId(animalId)
            val config = AsciiBackgroundConfig(animalId = animalId)
            val tuning = AsciiTuning().apply { syncFrom(config, spec) }
            val renderer = AsciiRenderer(tuning)
            val sim = AsciiSimulationFactory.create(animalId, tuning)

            renderer.resize(480f, 320f)
            sim.resize(480f, 320f)
            sim.onSelect()

            val frame = AsciiFrame()
            val dt = 0.033f
            var time = 0f
            for (step in 1..5) {
                time += dt
                renderer.begin(dt, time)
                sim.step(dt, time, renderer)
                frame.copyFrom(renderer, time)

                // Assert bit-for-bit equivalence between renderer buffers and frame buffers
                org.junit.Assert.assertArrayEquals("cellFlag mismatch at step $step for $animalId", renderer.cellFlag, frame.cellFlag)
                org.junit.Assert.assertArrayEquals("cellFill mismatch at step $step for $animalId", renderer.cellFill, frame.cellFill)
                org.junit.Assert.assertArrayEquals("cellColor mismatch at step $step for $animalId", renderer.cellColor, frame.cellColor)
                org.junit.Assert.assertArrayEquals("cellGlyph mismatch at step $step for $animalId", renderer.cellGlyph, frame.cellGlyph)
                assertEquals(renderer.width, frame.width, 0.001f)
                assertEquals(renderer.height, frame.height, 0.001f)
                assertEquals(renderer.cols, frame.cols)
                assertEquals(renderer.rows, frame.rows)
            }
        }
    }

    @Test
    fun `AsciiFrameRing cycles buffers without overwriting currently published frame`() {
        val ring = AsciiFrameRing(3)
        val f1 = ring.nextWriteBuffer()
        val f2 = ring.nextWriteBuffer()
        val f3 = ring.nextWriteBuffer()
        val f4 = ring.nextWriteBuffer()

        org.junit.Assert.assertNotSame(f1, f2)
        org.junit.Assert.assertNotSame(f2, f3)
        org.junit.Assert.assertSame(f1, f4)
    }

    @Test
    fun `resize with widthDp up to 411dp produces identical output to legacy formula across details`() {
        val details = floatArrayOf(0.4f, 0.5f, 1.0f, 1.3f, 2.0f)
        val testSizes = listOf(
            Triple(360f, 640f, 360f),
            Triple(411f, 800f, 411f),
            Triple(320f, 480f, 320f),
            Triple(400f, 700f, 0f) // default 0f widthDp (tests without widthDp)
        )

        for (detail in details) {
            val tuning = AsciiTuning().apply { this.detail = detail }
            val renderer = AsciiRenderer(tuning)

            for ((w, h, wDp) in testSizes) {
                renderer.resize(w, h, wDp)

                val legacyTargetCols = (64f * max(0.4f, detail)).coerceIn(24f, 160f)
                val legacyCw = max(4f, kotlin.math.floor(w / legacyTargetCols))
                val legacyCh = max(6f, (legacyCw * 1.45f).roundToInt().toFloat())
                val legacyCols = kotlin.math.ceil(w / legacyCw).toInt()
                val legacyRows = kotlin.math.ceil(h / legacyCh).toInt()

                assertEquals("cw mismatch for detail=$detail, w=$w, wDp=$wDp", legacyCw, renderer.cw, 0.001f)
                assertEquals("ch mismatch for detail=$detail, w=$w, wDp=$wDp", legacyCh, renderer.ch, 0.001f)
                assertEquals("cols mismatch for detail=$detail, w=$w, wDp=$wDp", legacyCols, renderer.cols)
                assertEquals("rows mismatch for detail=$detail, w=$w, wDp=$wDp", legacyRows, renderer.rows)
            }
        }
    }

    @Test
    fun `resize scales targetCols on tablets to maintain roughly constant on-screen cell size in dp`() {
        val tuning = AsciiTuning().apply { this.detail = 2.0f }
        val renderer = AsciiRenderer(tuning)

        // Baseline: phone 411dp
        val phoneDensity = 2.625f
        val phoneScaleFactor = (phoneDensity * 0.58f).coerceIn(1.0f, 2.2f)
        val phoneSimW = (411f * phoneDensity) / phoneScaleFactor
        val phoneSimH = (891f * phoneDensity) / phoneScaleFactor
        renderer.resize(phoneSimW, phoneSimH, 411f)
        val phoneCellDp = (renderer.cw * phoneScaleFactor) / phoneDensity

        // Tablets: 600dp, 800dp, 1280dp
        val tabletDensity = 2.0f
        val tabletScaleFactor = (tabletDensity * 0.58f).coerceIn(1.0f, 2.2f)

        for (tabletWdp in listOf(600f, 800f, 1280f)) {
            val simW = (tabletWdp * tabletDensity) / tabletScaleFactor
            val simH = (900f * tabletDensity) / tabletScaleFactor
            renderer.resize(simW, simH, tabletWdp)

            val tabletCellDp = (renderer.cw * tabletScaleFactor) / tabletDensity

            // The cell size in dp on tablets should be close to phone cell size (~2.5 - 3.8 dp)
            assertTrue("Expected tablet cell size in dp ($tabletCellDp) to stay close to phone ($phoneCellDp)",
                kotlin.math.abs(tabletCellDp - phoneCellDp) < 1.5f)

            // Total cells must respect MAX_CELLS
            assertTrue("Cell count ${renderer.cols * renderer.rows} must not exceed MAX_CELLS",
                renderer.cols * renderer.rows <= MAX_CELLS)
        }
    }

    @Test
    fun `MAX_CELLS guard prevents buffer explosion on ultra large displays`() {
        val tuning = AsciiTuning().apply { this.detail = 2.0f }
        val renderer = AsciiRenderer(tuning)

        // Simulated ultra-wide 4K canvas (e.g. 3840x2160, widthDp = 2500f)
        renderer.resize(3840f, 2160f, 2500f)
        val totalCells = renderer.cols * renderer.rows
        assertTrue("Expected cells ($totalCells) <= $MAX_CELLS", totalCells <= MAX_CELLS)
    }
}
