package com.geekify.android.ui.ascii

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Picture
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Double/triple-buffered immutable frame snapshot produced by the background simulation thread.
 * When [picture] is present, drawing on the main thread is a single hardware-accelerated [Canvas.drawPicture]
 * call (~0.05 ms). If [picture] is null (e.g. in headless unit tests), [renderFallback] draws directly
 * from the cell arrays.
 */
class AsciiFrame(
    var width: Float = 0f,
    var height: Float = 0f,
    var cols: Int = 0,
    var rows: Int = 0,
    var cw: Float = 0f,
    var ch: Float = 0f,
    var activeBg: FloatArray = FloatArray(3),
    var grid: Float = 0f,
    var trail: Float = 0f,
    var timeSec: Float = 0f,
    var contrast: FloatArray = FloatArray(3),
    var cellFlag: ByteArray = ByteArray(0),
    var cellFill: IntArray = IntArray(0),
    var cellColor: IntArray = IntArray(0),
    var cellGlyph: CharArray = CharArray(0),
    var energy: FloatArray = FloatArray(0),
    var picture: Picture? = null
) {
    fun copyFrom(renderer: AsciiRenderer, time: Float) {
        width = renderer.width
        height = renderer.height
        cols = renderer.cols
        rows = renderer.rows
        cw = renderer.cw
        ch = renderer.ch
        activeBg[0] = renderer.activeBg[0]
        activeBg[1] = renderer.activeBg[1]
        activeBg[2] = renderer.activeBg[2]
        grid = renderer.tuning.grid
        trail = renderer.tuning.trail
        timeSec = time
        contrast[0] = renderer.pen.contrast[0]
        contrast[1] = renderer.pen.contrast[1]
        contrast[2] = renderer.pen.contrast[2]

        val n = cols * rows
        if (cellFlag.size != n) {
            cellFlag = ByteArray(n)
            cellFill = IntArray(n)
            cellColor = IntArray(n)
            cellGlyph = CharArray(n)
            energy = FloatArray(n)
        }
        System.arraycopy(renderer.cellFlag, 0, cellFlag, 0, n)
        System.arraycopy(renderer.cellFill, 0, cellFill, 0, n)
        System.arraycopy(renderer.cellColor, 0, cellColor, 0, n)
        System.arraycopy(renderer.cellGlyph, 0, cellGlyph, 0, n)
        if (renderer.energy.size == n) {
            System.arraycopy(renderer.energy, 0, energy, 0, n)
        }
    }

    fun record(renderer: AsciiRenderer, time: Float) {
        copyFrom(renderer, time)
        if (renderer.width <= 1f || renderer.height <= 1f || renderer.cols <= 0 || renderer.rows <= 0) {
            picture = null
            return
        }
        // Hardware-accelerated Skia display list playback via Picture was fully integrated and stabilized on Android 9+ (API 28).
        // On API < 28, Canvas.drawPicture on hardware surfaces can force software rendering or encounter unsupported ops.
        // On older API levels, picture remains null to safely fall back to array-based rendering on the UI thread.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            try {
                val pic = Picture()
                val w = kotlin.math.ceil(renderer.width).toInt().coerceAtLeast(1)
                val h = kotlin.math.ceil(renderer.height).toInt().coerceAtLeast(1)
                val canvas = pic.beginRecording(w, h)
                renderer.render(canvas)
                pic.endRecording()
                picture = pic
            } catch (_: Throwable) {
                picture = null
            }
        } else {
            picture = null
        }
    }

    fun renderFallback(
        canvas: Canvas,
        fillPaint: Paint,
        textPaint: Paint,
        singleCharBuf: CharArray
    ) {
        if (width <= 1f || height <= 1f || cols <= 0 || rows <= 0) return

        // 1. Solid canvas background
        fillPaint.color = packRgb(
            clampF(activeBg[0], 0f, 255f).safeRoundToInt(),
            clampF(activeBg[1], 0f, 255f).safeRoundToInt(),
            clampF(activeBg[2], 0f, 255f).safeRoundToInt()
        )
        canvas.drawRect(0f, 0f, width, height, fillPaint)

        // 2. Grid
        if (grid > 0.01f) {
            val ga = clampF(0.055f * grid, 0f, 0.35f)
            fillPaint.color = packArgb((ga * 255f).safeRoundToInt(), 255, 255, 255)
            var c = 0
            while (c <= cols) {
                val px = c * cw
                canvas.drawRect(px, 0f, px + 1f, height, fillPaint)
                c++
            }
            var r = 0
            while (r <= rows) {
                val py = r * ch
                canvas.drawRect(0f, py, width, py + 1f, fillPaint)
                r++
            }
        }

        // 3. Trail particles
        val dotSize = max(2f, floor(cw * 0.22f))
        val ox = (cw - dotSize) * 0.5f
        val oy = (ch - dotSize) * 0.5f
        val br = activeBg[0]; val bg = activeBg[1]; val bb = activeBg[2]
        val cr = clampF(contrast[0] + (br - contrast[0]) * 0f, 0f, 255f).safeRoundToInt()
        val cg = clampF(contrast[1] + (bg - contrast[1]) * 0f, 0f, 255f).safeRoundToInt()
        val cb = clampF(contrast[2] + (bb - contrast[2]) * 0f, 0f, 255f).safeRoundToInt()
        val inkCol = packRgb(cr, cg, cb)
        val ir = (inkCol ushr 16) and 0xFF
        val ig = (inkCol ushr 8) and 0xFF
        val ib = inkCol and 0xFF

        val n = cols * rows
        if (trail > 0.01f && energy.size == n) {
            for (i in 0 until n) {
                val e = energy[i]
                if (e < 0.12f || cellFlag[i] == CELL_SOLID) continue
                val col = i % cols
                val row = i / cols
                val twinkle = 0.75f + 0.25f * sin(timeSec * 5f + hash3(col, row, 3) * 40f)
                val alpha = clampF(e * twinkle * 0.42f, 0.08f, 0.75f)
                val size = if (e > 0.6f) dotSize + 1f else dotSize
                fillPaint.color = packArgb((alpha * 255f).safeRoundToInt(), ir, ig, ib)
                canvas.drawRect(col * cw + ox, row * ch + oy, col * cw + ox + size, row * ch + oy + size, fillPaint)
            }
        }

        // 4. Solid cells block fill
        for (i in 0 until n) {
            if (cellFlag[i] == CELL_SOLID) {
                val col = i % cols
                val row = i / cols
                fillPaint.color = cellFill[i]
                canvas.drawRect(col * cw + 1f, row * ch + 1f, (col + 1) * cw, (row + 1) * ch, fillPaint)
            }
        }

        // 5. Text glyphs
        val halfCw = cw * 0.5f
        textPaint.textSize = max(8f, ch * 0.74f)
        val textOffsetY = -(textPaint.descent() + textPaint.ascent()) * 0.5f
        val halfCh = ch * 0.5f + textOffsetY
        for (i in 0 until n) {
            if (cellFlag[i] == CELL_NONE) continue
            val col = i % cols
            val row = i / cols
            singleCharBuf[0] = cellGlyph[i]
            textPaint.color = cellColor[i]
            canvas.drawText(singleCharBuf, 0, 1, col * cw + halfCw, row * ch + halfCh, textPaint)
        }
    }
}

/**
 * Ring buffer managing triple-buffered [AsciiFrame] allocations without GC thrashing.
 */
class AsciiFrameRing(size: Int = 3) {
    private val frames = Array(size) { AsciiFrame() }
    private var writeIndex = 0

    fun nextWriteBuffer(): AsciiFrame {
        val f = frames[writeIndex % frames.size]
        writeIndex++
        return f
    }
}
