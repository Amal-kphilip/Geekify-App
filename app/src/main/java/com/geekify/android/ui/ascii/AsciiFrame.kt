package com.geekify.android.ui.ascii

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Picture
import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Double/triple-buffered immutable frame snapshot produced by the background simulation thread.
 * The simulation thread composites the frame into an [IntArray] pixel buffer via a pre-rendered
 * [AsciiGlyphAtlas] and publishes a finished [Bitmap]. The main UI thread only issues a single
 * hardware-accelerated [Canvas.drawBitmap] call (~0.03 ms), completely eliminating the ~25,000
 * Skia drawText/drawRect ops that choked RenderThread in dense scenes like waves and aurora.
 *
 * If [bitmap] is null (e.g. in stripped headless environments), the existing [picture] or
 * [renderFallback] path is used.
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
    var bitmap: Bitmap? = null,
    var picture: Picture? = null
) {
    private var pixelBuffer: IntArray = IntArray(0)

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

    /**
     * Records and composites the renderer output on the simulation thread into [bitmap].
     * Avoids per-frame allocations by reusing [pixelBuffer] and [bitmap].
     */
    fun record(renderer: AsciiRenderer, time: Float) {
        copyFrom(renderer, time)
        if (renderer.width <= 1f || renderer.height <= 1f || renderer.cols <= 0 || renderer.rows <= 0) {
            return
        }

        val w = ceil(renderer.width).toInt().coerceAtLeast(1)
        val h = ceil(renderer.height).toInt().coerceAtLeast(1)
        val totalPixels = w * h

        // Try glyph-atlas software blit path first (fastest, single drawBitmap on UI thread)
        var blitSuccess = false
        try {
            if (pixelBuffer.size != totalPixels) {
                pixelBuffer = IntArray(totalPixels)
            }
            var bmp = bitmap
            if (bmp == null || bmp.isRecycled || bmp.width != w || bmp.height != h) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap = bmp
            }

            val atlas = AsciiGlyphAtlas.getOrCreate(renderer.cw, renderer.ch)
            blit(renderer, time, atlas, pixelBuffer, w, h)
            bmp.setPixels(pixelBuffer, 0, w, 0, 0, w, h)
            blitSuccess = true
        } catch (_: Throwable) {
            blitSuccess = false
        }

        if (!blitSuccess) {
            // Fallback: Picture path on API 28+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                try {
                    val pic = Picture()
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
    }

    /**
     * Composites the background, grid, trail, solid cells, and text glyphs into [buffer]
     * using the pre-rendered [atlas]. Zero object allocations in this hot path.
     */
    fun blit(
        renderer: AsciiRenderer,
        time: Float,
        atlas: AsciiGlyphAtlas,
        buffer: IntArray,
        widthInt: Int,
        heightInt: Int
    ) {
        val totalPixels = widthInt * heightInt
        val cols = renderer.cols
        val rows = renderer.rows
        val n = cols * rows
        val cwInt = renderer.cw.toInt().coerceAtLeast(1)
        val chInt = renderer.ch.toInt().coerceAtLeast(1)

        // 1. Solid canvas background
        val bgR = clampF(renderer.activeBg[0], 0f, 255f).safeRoundToInt()
        val bgG = clampF(renderer.activeBg[1], 0f, 255f).safeRoundToInt()
        val bgB = clampF(renderer.activeBg[2], 0f, 255f).safeRoundToInt()
        val bgPacked = (0xFF shl 24) or (bgR shl 16) or (bgG shl 8) or bgB
        Arrays.fill(buffer, 0, totalPixels, bgPacked)

        // 2. Subtle grid (only drawn if grid > 0.01f)
        if (renderer.tuning.grid > 0.01f) {
            val ga = clampF(0.055f * renderer.tuning.grid, 0f, 0.35f)
            val a = (ga * 255f).safeRoundToInt()
            val invA = 255 - a
            val gridColor = (0xFF shl 24) or
                (((255 * a + bgR * invA + 127) / 255) shl 16) or
                (((255 * a + bgG * invA + 127) / 255) shl 8) or
                ((255 * a + bgB * invA + 127) / 255)

            // Vertical lines
            for (c in 0..cols) {
                val px = c * cwInt
                if (px in 0 until widthInt) {
                    var idx = px
                    for (y in 0 until heightInt) {
                        buffer[idx] = gridColor
                        idx += widthInt
                    }
                }
            }
            // Horizontal lines
            for (r in 0..rows) {
                val py = r * chInt
                if (py in 0 until heightInt) {
                    val start = py * widthInt
                    val end = minOf(start + widthInt, totalPixels)
                    Arrays.fill(buffer, start, end, gridColor)
                }
            }
        }

        // 3. Trail particles
        if (renderer.tuning.trail > 0.01f && renderer.energy.size == n) {
            val dotSize = max(2, floor(renderer.cw * 0.22f).toInt())
            val ox = ((renderer.cw - dotSize) * 0.5f).toInt()
            val oy = ((renderer.ch - dotSize) * 0.5f).toInt()
            val inkCol = renderer.fade(renderer.pen.contrast[0], renderer.pen.contrast[1], renderer.pen.contrast[2], 0f)
            val ir = (inkCol ushr 16) and 0xFF
            val ig = (inkCol ushr 8) and 0xFF
            val ib = inkCol and 0xFF

            for (i in 0 until n) {
                val e = renderer.energy[i]
                if (e < 0.12f || renderer.cellFlag[i] == CELL_SOLID) continue
                val col = i % cols
                val row = i / cols
                val twinkle = 0.75f + 0.25f * sin(time * 5f + hash3(col, row, 3) * 40f)
                val alphaF = clampF(e * twinkle * 0.42f, 0.08f, 0.75f)
                val alpha = (alphaF * 255f).safeRoundToInt()
                val invA = 255 - alpha
                val size = if (e > 0.6f) dotSize + 1 else dotSize

                val startX = (col * cwInt + ox).coerceIn(0, widthInt)
                val startY = (row * chInt + oy).coerceIn(0, heightInt)
                val endX = (startX + size).coerceIn(0, widthInt)
                val endY = (startY + size).coerceIn(0, heightInt)

                for (py in startY until endY) {
                    var idx = py * widthInt + startX
                    for (px in startX until endX) {
                        val dst = buffer[idx]
                        val dr = (dst ushr 16) and 0xFF
                        val dg = (dst ushr 8) and 0xFF
                        val db = dst and 0xFF
                        val outR = (ir * alpha + dr * invA + 127) / 255
                        val outG = (ig * alpha + dg * invA + 127) / 255
                        val outB = (ib * alpha + db * invA + 127) / 255
                        buffer[idx] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                        idx++
                    }
                }
            }
        }

        // 4. Solid cells block fill
        for (i in 0 until n) {
            if (renderer.cellFlag[i] == CELL_SOLID) {
                val col = i % cols
                val row = i / cols
                val fillCol = renderer.cellFill[i]
                val startX = (col * cwInt + 1).coerceAtMost(widthInt)
                val startY = (row * chInt + 1).coerceAtMost(heightInt)
                val endX = ((col + 1) * cwInt).coerceAtMost(widthInt)
                val endY = ((row + 1) * chInt).coerceAtMost(heightInt)
                val len = endX - startX
                if (len > 0) {
                    for (py in startY until endY) {
                        val rowStart = py * widthInt + startX
                        Arrays.fill(buffer, rowStart, rowStart + len, fillCol)
                    }
                }
            }
        }

        // 5. Text glyphs via Glyph Atlas
        val masks = atlas.masks
        val atlasCw = atlas.cw
        val atlasCh = atlas.ch
        val cellStride = atlasCw * atlasCh

        for (i in 0 until n) {
            if (renderer.cellFlag[i] == CELL_NONE) continue
            val glyph = renderer.cellGlyph[i]
            val code = glyph.code
            if (code !in 33..126) continue // space (' ') is 32, empty mask

            val col = i % cols
            val row = i / cols
            val cellX = col * cwInt
            val cellY = row * chInt
            if (cellX >= widthInt || cellY >= heightInt) continue

            val color = renderer.cellColor[i]
            val sr = (color ushr 16) and 0xFF
            val sg = (color ushr 8) and 0xFF
            val sb = color and 0xFF

            val maskBase = code * cellStride
            val maxH = minOf(atlasCh, heightInt - cellY)
            val maxW = minOf(atlasCw, widthInt - cellX)

            for (gy in 0 until maxH) {
                val maskRow = maskBase + gy * atlasCw
                var dstIdx = (cellY + gy) * widthInt + cellX
                for (gx in 0 until maxW) {
                    val a = masks[maskRow + gx].toInt() and 0xFF
                    if (a == 255) {
                        buffer[dstIdx] = color
                    } else if (a > 0) {
                        val invA = 255 - a
                        val dst = buffer[dstIdx]
                        val dr = (dst ushr 16) and 0xFF
                        val dg = (dst ushr 8) and 0xFF
                        val db = dst and 0xFF
                        val outR = (sr * a + dr * invA + 127) / 255
                        val outG = (sg * a + dg * invA + 127) / 255
                        val outB = (sb * a + db * invA + 127) / 255
                        buffer[dstIdx] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                    }
                    dstIdx++
                }
            }
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
