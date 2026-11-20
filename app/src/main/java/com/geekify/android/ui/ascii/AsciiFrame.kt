package com.geekify.android.ui.ascii

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Picture
import java.util.Arrays
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

internal object AsciiParallelPool {
    val threadCount = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)
    val executor: java.util.concurrent.ExecutorService? = if (threadCount > 1) {
        Executors.newFixedThreadPool(threadCount) { r ->
            Thread {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                } catch (_: Throwable) {}
                r.run()
            }.apply {
                isDaemon = true
                name = "AsciiRowWorker"
            }
        }
    } else null
}

@Suppress("NOTHING_TO_INLINE")
private inline fun div255Fast(v: Int): Int = ((v + 128) * 257) ushr 16

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
    var trailColor: FloatArray = FloatArray(3),
    var cellFlag: ByteArray = ByteArray(0),
    var cellFill: IntArray = IntArray(0),
    var cellColor: IntArray = IntArray(0),
    var cellGlyph: CharArray = CharArray(0),
    var energy: FloatArray = FloatArray(0),
    var bitmap: Bitmap? = null,
    var picture: Picture? = null
) {
    val isBusy = AtomicBoolean(false)
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
        trailColor[0] = renderer.trailColor[0]
        trailColor[1] = renderer.trailColor[1]
        trailColor[2] = renderer.trailColor[2]

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
     * Avoids per-frame allocations by reusing [pixelBuffer], [wrappedIntBuffer], and [bitmap].
     */
    fun record(renderer: AsciiRenderer, time: Float) {
        if (isBusy.get()) return

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

            // Android byte-order convention:
            // Android Bitmap.Config.ARGB_8888 uses native kRGBA_8888_SkColorType in memory.
            // On little-endian architectures (ARM64, x86_64), an IntArray of 0xAARRGGBB Color ints
            // stores Byte 0 as Blue, Byte 1 as Green, Byte 2 as Red, and Byte 3 as Alpha.
            // copyPixelsFromBuffer performs a raw memcpy without endianness conversion, causing Red and
            // Blue channels to be swapped on display.
            // Bitmap.setPixels performs the proper Color-int to native SkColorType conversion uniformly.
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
     * Uses row-band parallelism on multi-core devices.
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
            val gridTarget = if (bgR + bgG + bgB > 384) 0 else 255
            val gridColor = (0xFF shl 24) or
                (div255Fast(gridTarget * a + bgR * invA) shl 16) or
                (div255Fast(gridTarget * a + bgG * invA) shl 8) or
                div255Fast(gridTarget * a + bgB * invA)

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

        // 3-5. Parallel row-band processing for trails, solid cells, and glyphs
        val pool = AsciiParallelPool
        val exec = pool.executor
        val threadCount = pool.threadCount

        if (exec != null && rows >= 16 && threadCount > 1) {
            val bands = threadCount
            val rowsPerBand = (rows + bands - 1) / bands
            val latch = CountDownLatch(bands)
            for (b in 0 until bands) {
                val rStart = b * rowsPerBand
                val rEnd = minOf(rows, rStart + rowsPerBand)
                if (rStart >= rEnd) {
                    latch.countDown()
                    continue
                }
                exec.execute {
                    try {
                        blitRows(renderer, time, atlas, buffer, widthInt, heightInt, rStart, rEnd)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await()
        } else {
            blitRows(renderer, time, atlas, buffer, widthInt, heightInt, 0, rows)
        }
    }

    private fun blitRows(
        renderer: AsciiRenderer,
        time: Float,
        atlas: AsciiGlyphAtlas,
        buffer: IntArray,
        widthInt: Int,
        heightInt: Int,
        rStart: Int,
        rEnd: Int
    ) {
        val cols = renderer.cols
        val cwInt = renderer.cw.toInt().coerceAtLeast(1)
        val chInt = renderer.ch.toInt().coerceAtLeast(1)
        val n = cols * renderer.rows

        // 3. Trail particles for rows rStart until rEnd
        if (renderer.tuning.trail > 0.01f && renderer.energy.size == n) {
            val dotSize = max(2, floor(renderer.cw * 0.22f).toInt())
            val ox = ((renderer.cw - dotSize) * 0.5f).toInt()
            val oy = ((renderer.ch - dotSize) * 0.5f).toInt()
            val inkCol = renderer.fade(renderer.trailColor[0], renderer.trailColor[1], renderer.trailColor[2], 0f)
            val ir = (inkCol ushr 16) and 0xFF
            val ig = (inkCol ushr 8) and 0xFF
            val ib = inkCol and 0xFF

            for (row in rStart until rEnd) {
                val rowBase = row * cols
                for (col in 0 until cols) {
                    val i = rowBase + col
                    val e = renderer.energy[i]
                    if (e < 0.12f || renderer.cellFlag[i] == CELL_SOLID) continue
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
                            val outR = div255Fast(ir * alpha + dr * invA)
                            val outG = div255Fast(ig * alpha + dg * invA)
                            val outB = div255Fast(ib * alpha + db * invA)
                            buffer[idx] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                            idx++
                        }
                    }
                }
            }
        }

        // 4. Solid cells block fill for rows rStart until rEnd
        for (row in rStart until rEnd) {
            val rowBase = row * cols
            for (col in 0 until cols) {
                val i = rowBase + col
                if (renderer.cellFlag[i] == CELL_SOLID) {
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
        }

        // 5. Text glyphs for rows rStart until rEnd
        val masks = atlas.masks
        val atlasCw = atlas.cw
        val atlasCh = atlas.ch
        val cellStride = atlasCw * atlasCh

        for (row in rStart until rEnd) {
            val rowBase = row * cols
            val cellY = row * chInt
            if (cellY >= heightInt) continue
            val maxH = minOf(atlasCh, heightInt - cellY)

            for (col in 0 until cols) {
                val i = rowBase + col
                if (renderer.cellFlag[i] == CELL_NONE) continue
                val glyph = renderer.cellGlyph[i]
                val code = glyph.code
                if (code !in 33..126) continue

                val cellX = col * cwInt
                if (cellX >= widthInt) continue
                val maxW = minOf(atlasCw, widthInt - cellX)

                val color = renderer.cellColor[i]
                val sr = (color ushr 16) and 0xFF
                val sg = (color ushr 8) and 0xFF
                val sb = color and 0xFF

                val maskBase = code * cellStride

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
                            val outR = div255Fast(sr * a + dr * invA)
                            val outG = div255Fast(sg * a + dg * invA)
                            val outB = div255Fast(sb * a + db * invA)
                            buffer[dstIdx] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                        }
                        dstIdx++
                    }
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
        val totalPixels = (ceil(width) * ceil(height)).toInt()
        if (totalPixels <= 0) return

        // 1. Background
        canvas.drawRGB(activeBg[0].toInt(), activeBg[1].toInt(), activeBg[2].toInt())

        // 2. Subtle grid
        if (grid > 0.01f) {
            val ga = clampF(0.055f * grid, 0f, 0.35f)
            fillPaint.color = packArgb((ga * 255f).safeRoundToInt(), 255, 255, 255)
            for (c in 0..cols) {
                canvas.drawLine(c * cw, 0f, c * cw, height, fillPaint)
            }
            for (r in 0..rows) {
                canvas.drawLine(0f, r * ch, width, r * ch, fillPaint)
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
