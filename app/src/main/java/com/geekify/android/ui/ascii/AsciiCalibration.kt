package com.geekify.android.ui.ascii

import com.geekify.android.BuildConfig

data class CalibrationColor(val name: String, val r: Int, val g: Int, val b: Int)

object AsciiCalibration {
    @Volatile
    var activePath: String? = null // null = normal, "cpu" = force CPU, "gpu" = force GPU
        set(value) {
            field = value
            if (value == "gpu") {
                AsciiGpuSupport.resetForTesting()
            }
        }

    @Volatile
    var gridEnabled: Boolean = false

    @Volatile
    var forcedAnimal: String? = null

    val isEnabled: Boolean
        get() = BuildConfig.DEBUG && gridEnabled

    val CALIBRATION_COLORS = listOf(
        CalibrationColor("Pure Red", 255, 0, 0),
        CalibrationColor("Pure Green", 0, 255, 0),
        CalibrationColor("Pure Blue", 0, 0, 255),
        CalibrationColor("Butterfly", 50, 135, 255),
        CalibrationColor("Crab", 204, 82, 52),
        CalibrationColor("Koi", 244, 196, 44),
        CalibrationColor("Aurora", 64, 242, 168)
    )

    fun populateCalibrationGrid(renderer: AsciiRenderer) {
        val cols = renderer.cols
        val rows = renderer.rows
        if (cols <= 0 || rows <= 0) return

        renderer.activeBg[0] = 0f
        renderer.activeBg[1] = 0f
        renderer.activeBg[2] = 0f
        renderer.tuning.grid = 0f
        renderer.tuning.trail = 0f
        renderer.tuning.shimmer = 0f
        renderer.tuning.sparkle = 0f
        renderer.tuning.fog = 0f
        renderer.tuning.hueShift = 0f
        renderer.tuning.colorCycle = false

        if (renderer.energy.size == cols * rows) {
            renderer.energy.fill(0f)
        }

        val usableRows = (rows * 0.75f).toInt().coerceAtLeast(CALIBRATION_COLORS.size)
        for (r in 0 until rows) {
            val target = if (r < usableRows) {
                val colorIdx = (r * CALIBRATION_COLORS.size / usableRows).coerceIn(0, CALIBRATION_COLORS.lastIndex)
                CALIBRATION_COLORS[colorIdx]
            } else null
            val packed = if (target != null) (0xFF shl 24) or (target.r shl 16) or (target.g shl 8) or target.b else 0

            for (c in 0 until cols) {
                val i = r * cols + c
                if (target == null) {
                    renderer.cellFlag[i] = 0
                    renderer.cellFill[i] = 0
                    renderer.cellGlyph[i] = ' '
                    renderer.cellColor[i] = 0
                } else if (c < cols / 2) {
                    // Left half: Solid fill
                    renderer.cellFlag[i] = CELL_SOLID
                    renderer.cellFill[i] = packed
                    renderer.cellGlyph[i] = ' '
                    renderer.cellColor[i] = packed
                } else {
                    // Right half: Text glyph '#'
                    renderer.cellFlag[i] = CELL_SOFT
                    renderer.cellFill[i] = 0
                    renderer.cellGlyph[i] = '#'
                    renderer.cellColor[i] = packed
                }
            }
        }
    }
}
