package com.geekify.android.ui.ascii

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Suppress("NOTHING_TO_INLINE")
internal inline fun packRgb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

@Suppress("NOTHING_TO_INLINE")
internal inline fun packArgb(a: Int, r: Int, g: Int, b: Int): Int =
    ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

const val INK_SCALE = 0
const val INK_DARK = 1
const val INK_SPOT = 2
const val INK_SHIMMER = 3
const val INK_GLASS = 4
const val INK_GLOW = 5
const val INK_BODY = 6
const val INK_LIT = 7

const val CELL_EMPTY: Byte = 0
const val CELL_SOFT: Byte = 1
const val CELL_SOLID: Byte = 2

private const val SCALE_RAMP = ".:;+=*%#"
private const val DARK_GLYPHS = "#%@"
private const val SPOT_GLYPHS = "o*"
private const val BODY_GLYPHS = "8#@"
private const val GLOW_RAMP = ".:+*"
private const val EDGE_HEAVY = ":;"
private const val EDGE_LIGHT = ".',`"

private val SUBSAMPLES_X = floatArrayOf(0.25f, 0.75f, 0.25f, 0.75f)
private val SUBSAMPLES_Y = floatArrayOf(0.30f, 0.30f, 0.72f, 0.72f)

private val CYCLE_STOPS = floatArrayOf(0.00f, 0.25f, 0.50f, 0.75f, 1.00f)
private val CYCLE_R = floatArrayOf(6f, 36f, 24f, 42f, 6f)
private val CYCLE_G = floatArrayOf(7f, 22f, 52f, 20f, 7f)
private val CYCLE_B = floatArrayOf(12f, 48f, 86f, 38f, 12f)

class SampleOut {
    var kind: Int = INK_SCALE
    var r: Float = 200f
    var g: Float = 210f
    var b: Float = 230f
    var hasFill: Boolean = false
    var fillR: Float = 0f
    var fillG: Float = 0f
    var fillB: Float = 0f
    var fillAlpha: Float = 0f
    var glyph: Char = '\u0000'

    fun set(
        kind: Int,
        r: Float,
        g: Float,
        b: Float,
        fillR: Float = 0f,
        fillG: Float = 0f,
        fillB: Float = 0f,
        fillAlpha: Float = 0f,
        glyph: Char = '\u0000'
    ): Boolean {
        this.kind = kind
        this.r = r
        this.g = g
        this.b = b
        this.hasFill = fillAlpha > 0f
        this.fillR = fillR
        this.fillG = fillG
        this.fillB = fillB
        this.fillAlpha = fillAlpha
        this.glyph = glyph
        return true
    }

    fun copyFrom(other: SampleOut) {
        this.kind = other.kind
        this.r = other.r
        this.g = other.g
        this.b = other.b
        this.hasFill = other.hasFill
        this.fillR = other.fillR
        this.fillG = other.fillG
        this.fillB = other.fillB
        this.fillAlpha = other.fillAlpha
        this.glyph = other.glyph
    }
}

class RippleItem(
    var x: Float,
    var y: Float,
    var age: Float = 0f,
    var maxRadius: Float = 110f,
    var strength: Float = 1f,
    var r: Float = 150f,
    var g: Float = 160f,
    var b: Float = 200f
)

class AsciiPen(val renderer: AsciiRenderer) {
    private val centerOut = SampleOut()
    private val probeOut = SampleOut()
    private val pickedOut = SampleOut()

    val cw: Float get() = renderer.cw
    val ch: Float get() = renderer.ch
    val cols: Int get() = renderer.cols
    val rows: Int get() = renderer.rows

    fun mixRgb(r: Float, g: Float, b: Float, fog: Float = 0f, boost: Float = 0f): Int {
        val bg = renderer.bgRgb
        var cr = lerpF(r + boost, bg[0], fog)
        var cg = lerpF(g + boost, bg[1], fog)
        var cb = lerpF(b + boost, bg[2], fog)
        if (renderer.hasHueMat) {
            val m = renderer.hueMat
            val nr = cr * m[0] + cg * m[1] + cb * m[2]
            val ng = cr * m[3] + cg * m[4] + cb * m[5]
            val nb = cr * m[6] + cg * m[7] + cb * m[8]
            cr = nr
            cg = ng
            cb = nb
        }
        return packRgb(
            clampF(cr, 0f, 255f).roundToInt(),
            clampF(cg, 0f, 255f).roundToInt(),
            clampF(cb, 0f, 255f).roundToInt()
        )
    }

    fun rgba(r: Float, g: Float, b: Float, a: Float, fog: Float = 0f): Int {
        val bg = renderer.bgRgb
        var cr = lerpF(r, bg[0], fog)
        var cg = lerpF(g, bg[1], fog)
        var cb = lerpF(b, bg[2], fog)
        if (renderer.hasHueMat) {
            val m = renderer.hueMat
            val nr = cr * m[0] + cg * m[1] + cb * m[2]
            val ng = cr * m[3] + cg * m[4] + cb * m[5]
            val nb = cr * m[6] + cg * m[7] + cb * m[8]
            cr = nr
            cg = ng
            cb = nb
        }
        return packArgb(
            (clampF(a, 0f, 1f) * 255f).roundToInt(),
            clampF(cr, 0f, 255f).roundToInt(),
            clampF(cg, 0f, 255f).roundToInt(),
            clampF(cb, 0f, 255f).roundToInt()
        )
    }

    fun dot(
        px: Float,
        py: Float,
        glyph: Char,
        r: Float,
        g: Float,
        b: Float,
        depth: Float = 0.5f,
        fog: Float = 0f,
        fillR: Float = 0f,
        fillG: Float = 0f,
        fillB: Float = 0f,
        fillAlpha: Float = 0f
    ) {
        val cx = (px / renderer.cw).toInt()
        val cy = (py / renderer.ch).toInt()
        if (cx < 0 || cx >= renderer.cols || cy < 0 || cy >= renderer.rows) return
        val idx = cy * renderer.cols + cx
        if (depth < renderer.cellDepth[idx]) return
        renderer.cellDepth[idx] = depth
        renderer.cellInk[idx] = glyph
        renderer.cellColor[idx] = mixRgb(r, g, b, fog)
        renderer.cellCoverage[idx] = 1f
        if (fillAlpha > 0f) {
            renderer.cellFlag[idx] = CELL_SOLID
            renderer.cellFill[idx] = rgba(fillR, fillG, fillB, fillAlpha, fog)
        } else {
            renderer.cellFlag[idx] = CELL_SOFT
        }
    }

    fun disc(
        cx: Float,
        cy: Float,
        radius: Float,
        r: Float,
        g: Float,
        b: Float,
        depth: Float = 0.5f,
        fog: Float = 0f,
        glyph: Char = '\u0000'
    ) {
        val cw = renderer.cw
        val ch = renderer.ch
        val cols = renderer.cols
        val rows = renderer.rows
        val x0 = max(0, floor((cx - radius) / cw).toInt())
        val x1 = min(cols - 1, ceil((cx + radius) / cw).toInt())
        val y0 = max(0, floor((cy - radius) / ch).toInt())
        val y1 = min(rows - 1, ceil((cy + radius) / ch).toInt())
        val col = mixRgb(r, g, b, fog)
        for (y in y0..y1) {
            val py = (y + 0.5f) * ch - cy
            val rowOff = y * cols
            for (x in x0..x1) {
                val px = (x + 0.5f) * cw - cx
                val d = hypot(px, py)
                if (d > radius) continue
                val idx = rowOff + x
                if (depth < renderer.cellDepth[idx]) continue
                val t = 1f - d / max(1f, radius)
                renderer.cellDepth[idx] = depth
                renderer.cellCoverage[idx] = t
                renderer.cellFlag[idx] = CELL_SOFT
                renderer.cellColor[idx] = col
                renderer.cellInk[idx] = if (glyph != '\u0000') {
                    glyph
                } else {
                    GLOW_RAMP[min(GLOW_RAMP.length - 1, (t * GLOW_RAMP.length).toInt())]
                }
            }
        }
    }

    fun trail(px: Float, py: Float, amount: Float = 0.4f) {
        renderer.depositEnergy(px, py, amount)
    }

    fun ripple(
        px: Float,
        py: Float,
        maxRadius: Float = 95f,
        strength: Float = 0.9f,
        r: Float = renderer.themeInkR,
        g: Float = renderer.themeInkG,
        b: Float = renderer.themeInkB
    ) {
        renderer.ripple(px, py, maxRadius, strength, r, g, b)
    }

    fun shape(
        cx: Float,
        cy: Float,
        boundRadius: Float,
        depth: Float,
        fog: Float,
        seed: Int,
        sample: (px: Float, py: Float, out: SampleOut) -> Boolean
    ) {
        val cw = renderer.cw
        val ch = renderer.ch
        val cols = renderer.cols
        val rows = renderer.rows
        val x0 = max(0, floor((cx - boundRadius) / cw).toInt())
        val x1 = min(cols - 1, ceil((cx + boundRadius) / cw).toInt())
        val y0 = max(0, floor((cy - boundRadius) / ch).toInt())
        val y1 = min(rows - 1, ceil((cy + boundRadius) / ch).toInt())
        val tick = renderer.shimmerTick
        val shimmerAmt = renderer.tuning.shimmer
        val sparkleAmt = renderer.tuning.sparkle

        for (y in y0..y1) {
            val rowOff = y * cols
            val baseY = y * ch
            for (x in x0..x1) {
                val idx = rowOff + x
                if (depth < renderer.cellDepth[idx] - 0.02f) continue

                val baseX = x * cw
                val hasCenter = sample(baseX + cw * 0.5f, baseY + ch * 0.5f, centerOut)
                var hits = if (hasCenter) 1 else 0
                if (hasCenter) {
                    pickedOut.copyFrom(centerOut)
                }
                var hasPicked = hasCenter

                for (s in 0 until 4) {
                    if (sample(baseX + cw * SUBSAMPLES_X[s], baseY + ch * SUBSAMPLES_Y[s], probeOut)) {
                        hits++
                        if (!hasPicked) {
                            pickedOut.copyFrom(probeOut)
                            hasPicked = true
                        }
                    }
                }

                if (hits == 0 || !hasPicked) continue
                val coverage = hits / 5f
                if (depth < renderer.cellDepth[idx] && coverage <= renderer.cellCoverage[idx]) continue

                renderer.cellDepth[idx] = depth
                renderer.cellCoverage[idx] = coverage

                val h = ((x * 73856093) xor (y * 19349663) xor (seed * 83492791)) and 0x7fffffff
                val hFloat = (h and 0xffff).toFloat() / 65535f

                if (coverage >= 0.6f && hasCenter) {
                    renderer.cellFlag[idx] = CELL_SOLID
                    val kind = pickedOut.kind

                    val sh = ((x * 374761393) xor (y * 668265263) xor (tick * 1442695041)) and 0x7fffffff
                    val sFloat = (sh and 0xffff).toFloat() / 65535f
                    val isSparkle = sFloat < sparkleAmt * 0.22f
                    val isShimmer = sFloat < shimmerAmt

                    var boost = 0f
                    val chGlyph: Char = when {
                        pickedOut.glyph != '\u0000' -> pickedOut.glyph
                        kind == INK_DARK -> DARK_GLYPHS[h % DARK_GLYPHS.length]
                        kind == INK_SPOT -> {
                            boost = 35f
                            SPOT_GLYPHS[h % SPOT_GLYPHS.length]
                        }
                        kind == INK_BODY -> BODY_GLYPHS[h % BODY_GLYPHS.length]
                        kind == INK_LIT -> {
                            boost = if (isSparkle) 60f else 28f
                            if (isSparkle) '*' else SCALE_RAMP[min(SCALE_RAMP.length - 1, 5 + (h % 3))]
                        }
                        kind == INK_SHIMMER -> {
                            boost = if (isSparkle) 70f else 32f
                            if (isSparkle) '*' else '+'
                        }
                        kind == INK_GLOW -> {
                            boost = 45f
                            GLOW_RAMP[min(GLOW_RAMP.length - 1, floor(coverage * GLOW_RAMP.length).toInt())]
                        }
                        kind == INK_GLASS -> {
                            if (isShimmer) '+' else SCALE_RAMP[min(SCALE_RAMP.length - 1, floor(coverage * 4f).toInt())]
                        }
                        else -> {
                            if (isSparkle) {
                                boost = 55f
                                '*'
                            } else {
                                val rampIdx = clampF(
                                    floor((coverage * 0.7f + hFloat * 0.35f) * SCALE_RAMP.length),
                                    2f,
                                    (SCALE_RAMP.length - 1).toFloat()
                                ).toInt()
                                val adj = if (isShimmer) min(SCALE_RAMP.length - 1, rampIdx + 1) else rampIdx
                                if (isShimmer) boost = 18f
                                SCALE_RAMP[adj]
                            }
                        }
                    }

                    renderer.cellInk[idx] = chGlyph
                    renderer.cellColor[idx] = mixRgb(pickedOut.r, pickedOut.g, pickedOut.b, fog, boost)
                    if (pickedOut.hasFill) {
                        renderer.cellFill[idx] = rgba(
                            pickedOut.fillR,
                            pickedOut.fillG,
                            pickedOut.fillB,
                            pickedOut.fillAlpha,
                            fog
                        )
                    } else {
                        renderer.cellFill[idx] = rgba(
                            pickedOut.r * 0.24f,
                            pickedOut.g * 0.26f,
                            pickedOut.b * 0.32f,
                            0.86f,
                            fog
                        )
                    }
                } else {
                    renderer.cellFlag[idx] = CELL_SOFT
                    val pool = if (coverage >= 0.4f) EDGE_HEAVY else EDGE_LIGHT
                    renderer.cellInk[idx] = pool[h % pool.length]
                    renderer.cellColor[idx] = mixRgb(pickedOut.r, pickedOut.g, pickedOut.b, fog, -10f)
                }
            }
        }
    }
}

class AsciiRenderer(val tuning: AsciiTuning) {
    var width: Float = 1f
    var height: Float = 1f
    var cw: Float = 10f
    var ch: Float = 14f
    var cols: Int = 1
    var rows: Int = 1

    val themeBgR = 6f
    val themeBgG = 7f
    val themeBgB = 12f
    val themeInkR = 150f
    val themeInkG = 160f
    val themeInkB = 200f
    val themeTrailAlpha = 0.85f

    val bgRgb = floatArrayOf(themeBgR, themeBgG, themeBgB)
    var hasHueMat: Boolean = false
    val hueMat = FloatArray(9)

    var cellCoverage = FloatArray(0)
    var cellInk = CharArray(0)
    var cellColor = IntArray(0)
    var cellFill = IntArray(0)
    var cellFlag = ByteArray(0)
    var cellDepth = FloatArray(0)

    var energy = FloatArray(0)
    var nextEnergy = FloatArray(0)

    val ripples = ArrayList<RippleItem>(16)
    var shimmerTick: Int = 0
    private var lastDetail: Float = tuning.detail

    val pen = AsciiPen(this)

    private var fillPaint: Paint? = null
    private var textPaint: Paint? = null
    private var lastPaintedCw: Float = -1f
    private var textOffsetY: Float = 4f
    private val singleCharBuf = CharArray(1)

    fun resize(w: Float, h: Float) {
        width = max(1f, w)
        height = max(1f, h)
        rebuildGrid()
    }

    fun rebuildGrid() {
        val targetCw = clampF(
            roundToInt((width / 124f) / max(0.35f, tuning.detail)).toFloat(),
            5f,
            24f
        )
        cw = targetCw
        ch = (cw * 1.45f).roundToInt().toFloat().coerceAtLeast(7f)
        cols = max(1, ceil(width / cw).toInt())
        rows = max(1, ceil(height / ch).toInt())
        val n = cols * rows
        if (cellCoverage.size != n) {
            cellCoverage = FloatArray(n)
            cellInk = CharArray(n)
            cellColor = IntArray(n)
            cellFill = IntArray(n)
            cellFlag = ByteArray(n)
            cellDepth = FloatArray(n)
            energy = FloatArray(n)
            nextEnergy = FloatArray(n)
        }
        lastDetail = tuning.detail
    }

    private fun roundToInt(v: Float): Int = v.roundToInt()

    private fun computeBg(time: Float) {
        val speed = tuning.dayNight
        if (speed <= 0.001f) {
            bgRgb[0] = themeBgR
            bgRgb[1] = themeBgG
            bgRgb[2] = themeBgB
            return
        }
        val phase = (time * speed * 0.04f) % 1f
        for (i in 0 until CYCLE_STOPS.size - 1) {
            val s0 = CYCLE_STOPS[i]
            val s1 = CYCLE_STOPS[i + 1]
            if (phase >= s0 && phase <= s1) {
                val u = smoothstepF(s0, s1, phase)
                bgRgb[0] = lerpF(CYCLE_R[i], CYCLE_R[i + 1], u)
                bgRgb[1] = lerpF(CYCLE_G[i], CYCLE_G[i + 1], u)
                bgRgb[2] = lerpF(CYCLE_B[i], CYCLE_B[i + 1], u)
                return
            }
        }
        bgRgb[0] = themeBgR
        bgRgb[1] = themeBgG
        bgRgb[2] = themeBgB
    }

    fun begin(dt: Float, time: Float) {
        if (abs(tuning.detail - lastDetail) > 0.01f) {
            rebuildGrid()
        }
        computeBg(time)
        val totalHue = tuning.hueShift + (if (tuning.colorCycle) (time * tuning.colorCycleSpeed * 45f) % 360f else 0f)
        if (abs(totalHue) > 0.5f) {
            hasHueMat = true
            hueMatrix(totalHue, hueMat)
        } else {
            hasHueMat = false
        }
        shimmerTick = floor(time * 14f).toInt()

        val n = cols * rows
        cellCoverage.fill(0f, 0, n)
        cellFlag.fill(CELL_EMPTY, 0, n)
        cellDepth.fill(-1f, 0, n)

        // Diffuse and fade trail energy field
        val decay = exp(-dt * tuning.trailFade)
        val c = cols
        val r = rows
        val e = energy
        val ne = nextEnergy
        for (y in 0 until r) {
            val rowOff = y * c
            for (x in 0 until c) {
                val i = rowOff + x
                val l = if (x > 0) e[i - 1] else e[i]
                val rt = if (x + 1 < c) e[i + 1] else e[i]
                val u = if (y > 0) e[i - c] else e[i]
                val d = if (y + 1 < r) e[i + c] else e[i]
                ne[i] = (e[i] * 4f + l + rt + u + d) * 0.125f * decay
            }
        }
        val tmp = energy
        energy = nextEnergy
        nextEnergy = tmp

        stepRipples(dt)
    }

    fun depositEnergy(px: Float, py: Float, amount: Float) {
        if (tuning.trail <= 0.001f) return
        val gx = (px / cw).toInt()
        val gy = (py / ch).toInt()
        if (gx < 0 || gx >= cols || gy < 0 || gy >= rows) return
        val idx = gy * cols + gx
        energy[idx] = min(1.25f, energy[idx] + amount * tuning.trail)
    }

    fun ripple(
        px: Float,
        py: Float,
        maxRadius: Float = 110f,
        strength: Float = 1f,
        r: Float = themeInkR,
        g: Float = themeInkG,
        b: Float = themeInkB
    ) {
        if (tuning.ripples <= 0.01f) return
        if (ripples.size > 18) ripples.removeAt(0)
        ripples.add(
            RippleItem(
                x = px,
                y = py,
                age = 0f,
                maxRadius = maxRadius,
                strength = strength * min(1.6f, tuning.ripples),
                r = r,
                g = g,
                b = b
            )
        )
    }

    private fun stepRipples(dt: Float) {
        if (ripples.isEmpty()) return
        var i = ripples.size - 1
        while (i >= 0) {
            val rp = ripples[i]
            rp.age += dt
            val duration = 1.6f
            if (rp.age >= duration) {
                ripples.removeAt(i)
                i--
                continue
            }
            val p = rp.age / duration
            val radius = rp.maxRadius * (0.15f + 0.85f * (1f - (1f - p) * (1f - p)))
            val fade = (1f - p) * rp.strength
            if (fade > 0.08f) {
                val steps = max(16, floor(radius * 0.5f).toInt())
                for (s in 0 until steps) {
                    val a = (s.toFloat() / steps.toFloat()) * TWO_PI
                    val rx = rp.x + cos(a) * radius
                    val ry = rp.y + sin(a) * radius * 0.78f
                    val gx = (rx / cw).toInt()
                    val gy = (ry / ch).toInt()
                    if (gx < 0 || gx >= cols || gy < 0 || gy >= rows) continue
                    val idx = gy * cols + gx
                    if (cellDepth[idx] >= 0f) continue
                    val c = cos(a)
                    val sn = sin(a)
                    val chGlyph = if (abs(c) > 0.78f) ':' else if (abs(sn) > 0.78f) '-' else '.'
                    cellFlag[idx] = CELL_SOFT
                    cellInk[idx] = chGlyph
                    cellColor[idx] = pen.mixRgb(rp.r, rp.g, rp.b, clampF(1f - fade, 0.25f, 0.85f))
                }
            }
            i--
        }
    }

    fun end(canvas: Canvas) {
        val fp = fillPaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }.also { fillPaint = it }

        val tp = textPaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }.also { textPaint = it }

        if (lastPaintedCw != cw) {
            tp.textSize = max(7f, cw * 1.08f)
            textOffsetY = -(tp.descent() + tp.ascent()) * 0.5f
            lastPaintedCw = cw
        }

        // 1. Background
        fp.color = packRgb(
            clampF(bgRgb[0], 0f, 255f).roundToInt(),
            clampF(bgRgb[1], 0f, 255f).roundToInt(),
            clampF(bgRgb[2], 0f, 255f).roundToInt()
        )
        canvas.drawRect(0f, 0f, width, height, fp)

        // 2. Subtle grid (defaults to 0.0f min; drawn when grid > 0.01f)
        if (tuning.grid > 0.01f) {
            val gAlpha = clampF(0.055f * tuning.grid, 0f, 0.35f)
            fp.color = packArgb((gAlpha * 255f).roundToInt(), 255, 255, 255)
            var x = 0
            while (x <= cols) {
                val px = x * cw
                canvas.drawRect(px, 0f, px + 1f, height, fp)
                x++
            }
            var y = 0
            while (y <= rows) {
                val py = y * ch
                canvas.drawRect(0f, py, width, py + 1f, fp)
                y++
            }
        }

        // 3. Soft diffused trail field
        if (tuning.trail > 0.01f) {
            val dotSize = max(2f, floor(cw * 0.24f))
            val ox = (cw - dotSize) * 0.5f
            val oy = (ch - dotSize) * 0.5f
            val inkCol = pen.mixRgb(themeInkR, themeInkG, themeInkB, 0f)
            val ir = (inkCol ushr 16) and 0xFF
            val ig = (inkCol ushr 8) and 0xFF
            val ib = inkCol and 0xFF

            for (b in 1..6) {
                val lo = b * 0.05f
                val hi = if (b == 6) 99f else (b + 1) * 0.05f
                val alpha = min(0.28f, (b * 0.042f) * themeTrailAlpha)
                fp.color = packArgb((alpha * 255f).roundToInt(), ir, ig, ib)
                for (y in 0 until rows) {
                    val rowOff = y * cols
                    val py = y * ch + oy
                    for (x in 0 until cols) {
                        val i = rowOff + x
                        if (cellFlag[i] == CELL_SOLID) continue
                        val v = energy[i]
                        if (v >= lo && v < hi) {
                            val tw = (((x * 31) xor (y * 17) xor shimmerTick) and 3)
                            if (tw != 0) {
                                canvas.drawRect(
                                    x * cw + ox,
                                    py,
                                    x * cw + ox + dotSize,
                                    py + dotSize,
                                    fp
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Solid cell background blocks
        for (y in 0 until rows) {
            val rowOff = y * cols
            val py = y * ch
            for (x in 0 until cols) {
                val i = rowOff + x
                if (cellFlag[i] == CELL_SOLID) {
                    fp.color = cellFill[i]
                    canvas.drawRect(x * cw, py, x * cw + cw, py + ch, fp)
                }
            }
        }

        // 5. Foreground ASCII glyphs
        val buf = singleCharBuf
        val halfCw = cw * 0.5f
        val halfCh = ch * 0.52f + textOffsetY
        for (y in 0 until rows) {
            val rowOff = y * cols
            val cy = y * ch + halfCh
            for (x in 0 until cols) {
                val i = rowOff + x
                if (cellFlag[i] == CELL_EMPTY) continue
                buf[0] = cellInk[i]
                tp.color = cellColor[i]
                canvas.drawText(buf, 0, 1, x * cw + halfCw, cy, tp)
            }
        }
    }
}
