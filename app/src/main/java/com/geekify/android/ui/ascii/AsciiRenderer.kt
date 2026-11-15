package com.geekify.android.ui.ascii

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
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

const val CELL_NONE: Byte = 0
const val CELL_EMPTY: Byte = 0
const val CELL_SOLID: Byte = 1
const val CELL_SOFT: Byte = 2

private const val SCALE_RAMP = ".:;+=*%#"
private const val DARK_GLYPHS = "#%@"
private const val SPOT_GLYPHS = "o*"
private const val BODY_GLYPHS = "8#@"
private const val GLOW_RAMP = ".:+*"
private const val EDGE_HEAVY = ":;"
private const val EDGE_LIGHT = ".',`"

private val SUBSAMPLES = floatArrayOf(0.25f, 0.30f, 0.75f, 0.30f, 0.25f, 0.72f, 0.75f, 0.72f)

private val SPARKLE_R = intArrayOf(255, 228, 200)
private val SPARKLE_G = intArrayOf(255, 246, 244)
private val SPARKLE_B = intArrayOf(255, 255, 255)

private val CYCLE_R = floatArrayOf(6f, 36f, 24f, 42f)
private val CYCLE_G = floatArrayOf(7f, 22f, 52f, 20f)
private val CYCLE_B = floatArrayOf(12f, 48f, 86f, 38f)

fun lineGlyph(angle: Float): Char {
    var a = angle % PI.toFloat()
    if (a < 0f) a += PI.toFloat()
    val oct = (PI / 8.0).toFloat()
    return when {
        a < oct || a >= 7f * oct -> '-'
        a < 3f * oct -> '\\'
        a < 5f * oct -> '|'
        else -> '/'
    }
}

class PenInk {
    var r: Float = 0f
    var g: Float = 0f
    var b: Float = 0f
    var kind: Int = INK_SCALE
    var glyphs: String? = null
    var au: Float = 0f
    var av: Float = 0f

    fun set(r: Float, g: Float, b: Float, kind: Int = INK_SCALE, glyphs: String? = null): Boolean {
        this.r = r
        this.g = g
        this.b = b
        this.kind = kind
        this.glyphs = glyphs
        return true
    }
}

class Pen(val r: AsciiRenderer) {
    val ink = PenInk()
    var t: Float = 0f

    val cw: Float get() = r.cw
    val ch: Float get() = r.ch
    val cols: Int get() = r.cols
    val rows: Int get() = r.rows

    val contrast: FloatArray = floatArrayOf(150f, 160f, 200f)

    private val ptA = FloatArray(3)
    private val ptB = FloatArray(3)

    fun mixRgb(rVal: Float, gVal: Float, bVal: Float, fog: Float = 0f, boost: Float = 0f): Int {
        r.turn(rVal + boost, gVal + boost, bVal + boost, ptA)
        return r.fade(ptA[0], ptA[1], ptA[2], fog)
    }

    fun toWorld(p: Pose, u: Float, v: Float, out: FloatArray): FloatArray {
        val c = cos(p.heading)
        val s = sin(p.heading)
        out[0] = p.x + (u * c - v * s) * p.span
        out[1] = p.y + (u * s + v * c) * p.span
        return out
    }

    fun fogFor(depth: Float): Float {
        return (1f - depth) * r.tuning.fog
    }

    fun shape(
        p: Pose,
        reach: Float,
        sample: (u: Float, v: Float, px: Float, py: Float) -> Boolean,
        light: Float = 1f,
        tilt: Float = 0f,
        fog: Float = fogFor(p.depth)
    ) {
        r.shape(p, reach, sample, light, tilt, fog)
    }

    fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: FloatArray, fog: Float, tip: Char? = null) {
        r.line(x0, y0, x1, y1, color, fog, tip)
    }

    fun lineBody(p: Pose, u0: Float, v0: Float, u1: Float, v1: Float, color: FloatArray, tip: Char? = null) {
        val a = toWorld(p, u0, v0, ptA)
        val ax = a[0]; val ay = a[1]
        val b = toWorld(p, u1, v1, ptB)
        val bx = b[0]; val by = b[1]
        r.line(ax, ay, bx, by, color, fogFor(p.depth), tip)
    }

    fun dot(x: Float, y: Float, glyph: Char, color: FloatArray, fog: Float) {
        r.dot(x, y, glyph, color, fog)
    }

    fun spot(x: Float, y: Float, glyph: Char, color: FloatArray, fill: FloatArray, depth: Float) {
        r.spot(x, y, glyph, color, fill, fogFor(depth))
    }

    fun setCell(col: Int, row: Int, glyph: Char, color: FloatArray, fill: FloatArray? = null, fog: Float = 0f) {
        r.setCell(col, row, glyph, color, fill, fog)
    }

    fun shed(x: Float, y: Float, amount: Float) {
        r.shed(x, y, amount)
    }

    fun cellIndex(x: Float, y: Float): Int {
        return r.cellIndex(x, y)
    }
}

class AsciiRenderer(val tuning: AsciiTuning) {
    var width: Float = 1f
    var height: Float = 1f
    var cw: Float = 16f
    var ch: Float = 23f
    var cols: Int = 1
    var rows: Int = 1

    val pen = Pen(this)

    var activeBg = floatArrayOf(6f, 7f, 12f)
    var hueMat = FloatArray(9)
    var hueOn: Boolean = false

    var energy = FloatArray(0)
    var energyTmp = FloatArray(0)

    var cellFlag = ByteArray(0)
    var cellFill = IntArray(0)
    var cellColor = IntArray(0)
    var cellGlyph = CharArray(0)

    private var fillPaint: Paint? = null
    private var textPaint: Paint? = null
    private var lastPaintedCw: Float = -1f
    private var textOffsetY: Float = 0f
    private val singleCharBuf = CharArray(1)

    fun turn(r: Float, g: Float, b: Float, out: FloatArray): FloatArray {
        if (!hueOn) {
            out[0] = r
            out[1] = g
            out[2] = b
            return out
        }
        val m = hueMat
        out[0] = m[0] * r + m[1] * g + m[2] * b
        out[1] = m[3] * r + m[4] * g + m[5] * b
        out[2] = m[6] * r + m[7] * g + m[8] * b
        return out
    }

    private val turnTmp = FloatArray(3)

    fun fade(r: Float, g: Float, b: Float, f: Float): Int {
        val br = activeBg[0]
        val bg = activeBg[1]
        val bb = activeBg[2]
        val cr = clampF(r + (br - r) * f, 0f, 255f).roundToInt()
        val cg = clampF(g + (bg - g) * f, 0f, 255f).roundToInt()
        val cb = clampF(b + (bb - b) * f, 0f, 255f).roundToInt()
        return packRgb(cr, cg, cb)
    }

    fun resize(w: Float, h: Float) {
        width = max(1f, w)
        height = max(1f, h)

        // Detail 2.0 = max detail (finest grid, ~48-56 cols on mobile);
        // Detail 1.0 = balanced (~36-42 cols); Detail 0.5 = chunky.
        val baseCols = (34f * max(0.5f, tuning.detail)).coerceIn(24f, 60f)
        cw = max(9f, floor(width / baseCols))
        ch = max(13f, (cw * 1.45f).roundToInt().toFloat())
        cols = ceil(width / cw).toInt()
        rows = ceil(height / ch).toInt()

        val n = cols * rows
        if (cellFlag.size != n) {
            energy = FloatArray(n)
            energyTmp = FloatArray(n)
            cellFlag = ByteArray(n)
            cellFill = IntArray(n)
            cellColor = IntArray(n)
            cellGlyph = CharArray(n)
        }
    }

    fun begin(dt: Float, flockTime: Float, dayPhase: Float = 0f) {
        pen.t = flockTime

        val totalHue = tuning.hueShift + (if (tuning.colorCycle) (flockTime * tuning.colorCycleSpeed * 45f) % 360f else 0f)
        if (abs(totalHue) > 0.5f) {
            hueOn = true
            hueMatrix(totalHue, hueMat)
        } else {
            hueOn = false
        }

        if (tuning.dayNight > 0f) {
            val p = (dayPhase % 1f + 1f) % 1f
            val idx = p * 4f
            val i0 = floor(idx).toInt() % 4
            val i1 = (i0 + 1) % 4
            val f = idx - floor(idx)
            val s = f * f * (3f - 2f * f)
            activeBg[0] = lerpF(CYCLE_R[i0], CYCLE_R[i1], s)
            activeBg[1] = lerpF(CYCLE_G[i0], CYCLE_G[i1], s)
            activeBg[2] = lerpF(CYCLE_B[i0], CYCLE_B[i1], s)
        } else {
            activeBg[0] = 6f
            activeBg[1] = 7f
            activeBg[2] = 12f
        }

        diffuse(dt)
        cellFlag.fill(CELL_NONE)
    }

    fun diffuse(dt: Float) {
        val n = cols * rows
        if (energy.size != n) return
        val decay = exp(-dt * tuning.trailFade)
        val c = cols
        val r = rows
        val e = energy
        val tmp = energyTmp
        for (row in 0 until r) {
            val rowOff = row * c
            for (col in 0 until c) {
                val i = rowOff + col
                val l = if (col > 0) e[i - 1] else e[i]
                val rt = if (col < c - 1) e[i + 1] else e[i]
                val u = if (row > 0) e[i - c] else e[i]
                val d = if (row < r - 1) e[i + c] else e[i]
                tmp[i] = (e[i] * 4f + l + rt + u + d) * 0.125f * decay
            }
        }
        energy = tmp
        energyTmp = e
    }

    fun cellIndex(x: Float, y: Float): Int {
        val c = floor(x / cw).toInt()
        val r = floor(y / ch).toInt()
        if (c < 0 || r < 0 || c >= cols || r >= rows) return -1
        return r * cols + c
    }

    fun shed(x: Float, y: Float, amount: Float) {
        val i = cellIndex(x, y)
        if (i >= 0 && i < energy.size) {
            energy[i] = min(1.6f, energy[i] + amount * tuning.trail)
        }
    }

    fun stamp(x: Float, y: Float, radius: Int, amount: Float) {
        val c0 = floor(x / cw).toInt()
        val r0 = floor(y / ch).toInt()
        for (r in (r0 - radius)..(r0 + radius)) {
            for (c in (c0 - radius)..(c0 + radius)) {
                if (c < 0 || r < 0 || c >= cols || r >= rows) continue
                val d = hypot(c - c0.toFloat(), (r - r0.toFloat()) * 1.4f)
                if (d > radius + 0.5f) continue
                val i = r * cols + c
                energy[i] = min(1.6f, energy[i] + amount * (1f - d / (radius + 1f)))
            }
        }
    }

    fun drawRipples(ripples: List<RippleEffect>) {
        val baseCol = floatArrayOf(110f, 190f, 235f)
        for (rp in ripples) {
            val life = 1f - rp.r / rp.maxR
            if (life <= 0.02f) continue
            val col = baseCol
            col[0] = rp.colorR; col[1] = rp.colorG; col[2] = rp.colorB
            val fog = clampF(1f - life * rp.strength * 0.85f, 0.12f, 0.94f)
            val steps = clampF(ceil(TAU * rp.r / (cw * 0.9f)), 12f, 96f).toInt()
            for (s in 0 until steps) {
                val a = (s.toFloat() / steps.toFloat()) * TAU
                val x = rp.x + cos(a) * rp.r
                val y = rp.y + sin(a) * rp.r * 0.82f
                val ca = abs(cos(a))
                val glyph = if (life > 0.55f) {
                    if (ca > 0.7f) '(' else '~'
                } else if (life > 0.25f) {
                    ':'
                } else {
                    '.'
                }
                val g = if (glyph == '(' && cos(a) > 0f) ')' else glyph
                dot(x, y, g, col, fog)
            }
        }
    }

    private fun probe(
        sample: (u: Float, v: Float, px: Float, py: Float) -> Boolean,
        p: Pose,
        hc: Float,
        hs: Float,
        px: Float,
        py: Float
    ): Boolean {
        val dx = px - p.x
        val dy = py - p.y
        val u = (dx * hc + dy * hs) / p.span
        val v = (dy * hc - dx * hs) / p.span
        pen.ink.glyphs = null
        pen.ink.au = u
        pen.ink.av = v
        return sample(u, v, px, py)
    }

    fun shape(
        p: Pose,
        reach: Float,
        sample: (u: Float, v: Float, px: Float, py: Float) -> Boolean,
        light: Float = 1f,
        tilt: Float = 0f,
        fog: Float = pen.fogFor(p.depth)
    ) {
        val S = p.span
        val hc = cos(p.heading)
        val hs = sin(p.heading)
        val R = reach * S
        val c0 = max(0, floor((p.x - R) / cw).toInt())
        val c1 = min(cols - 1, floor((p.x + R) / cw).toInt())
        val r0 = max(0, floor((p.y - R) / ch).toInt())
        val r1 = min(rows - 1, floor((p.y + R) / ch).toInt())
        val t = pen.t
        val shimmer = tuning.shimmer
        val sparkle = tuning.sparkle

        for (r in r0..r1) {
            val rowOff = r * cols
            val y = r * ch
            for (c in c0..c1) {
                val x = c * cw
                val center = probe(sample, p, hc, hs, x + cw * 0.5f, y + ch * 0.5f)
                var hits = if (center) 1 else 0
                var ir = pen.ink.r
                var ig = pen.ink.g
                var ib = pen.ink.b
                val kind = pen.ink.kind
                val glyphs = pen.ink.glyphs
                val au = pen.ink.au
                val av = pen.ink.av

                var k2 = 0
                while (k2 < SUBSAMPLES.size) {
                    if (probe(sample, p, hc, hs, x + SUBSAMPLES[k2] * cw, y + SUBSAMPLES[k2 + 1] * ch)) {
                        if (hits == 0) {
                            ir = pen.ink.r
                            ig = pen.ink.g
                            ib = pen.ink.b
                        }
                        hits++
                    }
                    k2 += 2
                }
                if (hits == 0) continue

                val coverage = hits / 5f
                val i = rowOff + c
                val solid = center && coverage >= 0.6f
                if (!solid && cellFlag[i] == CELL_SOLID) continue

                turn(ir, ig, ib, turnTmp)
                ir = turnTmp[0]; ig = turnTmp[1]; ib = turnTmp[2]

                var k = light * (1f + (hash3(c, r, floor(t * 6f).toInt()) - 0.5f) * 0.06f)
                if (solid && kind == INK_SHIMMER) {
                    k *= 1f - 0.4f * shimmer + shimmer * sin(t * 2.2f + au * 9f + av * 6f + tilt + p.heading * 2f)
                }
                ir *= k
                ig *= k
                ib *= k

                val key = (hash3((au * S / (cw * 0.9f)).roundToInt(), (av * S / (cw * 0.9f)).roundToInt(), p.id) * 4294967295.0).toLong()
                val keyInt = key.toInt()

                if (!solid) {
                    val pool = if (coverage >= 0.5f) EDGE_HEAVY else EDGE_LIGHT
                    cellFlag[i] = CELL_SOFT
                    cellColor[i] = fade(ir, ig, ib, min(1f, fog + 0.55f - 0.45f * coverage))
                    val glyphIdx = abs((keyInt ushr 8) % pool.length)
                    cellGlyph[i] = pool[glyphIdx]
                    continue
                }

                if (kind == INK_GLOW) {
                    if (cellFlag[i] == CELL_SOLID) continue
                    val lum = (0.3f * ir + 0.59f * ig + 0.11f * ib) / 255f
                    val pool = glyphs ?: GLOW_RAMP
                    cellFlag[i] = CELL_SOFT
                    cellColor[i] = fade(ir, ig, ib, fog)
                    val glyphIdx = if (glyphs != null) {
                        abs((keyInt ushr 8) % pool.length)
                    } else {
                        clampF(floor(lum * pool.length), 0f, (pool.length - 1).toFloat()).toInt()
                    }
                    cellGlyph[i] = pool[glyphIdx]
                    continue
                }

                cellFlag[i] = CELL_SOLID
                val roll = hash3(keyInt, floor(t * 2.4f + (keyInt % 997) / 97f).toInt(), 7)

                if (kind == INK_GLASS) {
                    cellFill[i] = fade(ir, ig, ib, min(1f, 0.62f + fog * 0.38f))
                    val pool = glyphs ?: SCALE_RAMP
                    cellColor[i] = if (roll < sparkle) {
                        val sIdx = abs(keyInt % 3)
                        packRgb(SPARKLE_R[sIdx], SPARKLE_G[sIdx], SPARKLE_B[sIdx])
                    } else {
                        fade(ir, ig, ib, fog)
                    }
                    cellGlyph[i] = pool[abs((keyInt ushr 8) % pool.length)]
                    continue
                }

                if (kind == INK_LIT) {
                    cellFill[i] = fade(ir * 0.58f, ig * 0.58f, ib * 0.58f, fog)
                    val pool = glyphs ?: ".:*"
                    cellColor[i] = if (roll < sparkle) {
                        val sIdx = abs(keyInt % 3)
                        packRgb(SPARKLE_R[sIdx], SPARKLE_G[sIdx], SPARKLE_B[sIdx])
                    } else {
                        fade(min(255f, ir * 1.05f + 50f), min(255f, ig * 1.05f + 50f), min(255f, ib * 1.05f + 50f), fog)
                    }
                    cellGlyph[i] = pool[abs((keyInt ushr 8) % pool.length)]
                    continue
                }

                val fill = fade(ir, ig, ib, fog)
                cellFill[i] = fill
                val fr = (fill ushr 16) and 0xFF
                val fg = (fill ushr 8) and 0xFF
                val fb = fill and 0xFF

                val pool: String
                val gr: Float; val gg: Float; val gb: Float
                when (kind) {
                    INK_BODY -> {
                        pool = glyphs ?: BODY_GLYPHS
                        gr = fr * 1.6f + 44f; gg = fg * 1.6f + 40f; gb = fb * 1.6f + 36f
                    }
                    INK_DARK -> {
                        pool = glyphs ?: DARK_GLYPHS
                        gr = fr * 1.7f + 34f; gg = fg * 1.7f + 30f; gb = fb * 1.7f + 30f
                    }
                    INK_SPOT -> {
                        pool = glyphs ?: SPOT_GLYPHS
                        gr = fr * 0.4f; gg = fg * 0.4f; gb = fb * 0.4f
                    }
                    else -> {
                        pool = glyphs ?: SCALE_RAMP
                        gr = fr * 0.42f; gg = fg * 0.42f; gb = fb * 0.42f
                    }
                }

                val chChar: Char = if (glyphs != null || (kind != INK_SCALE && kind != INK_SHIMMER)) {
                    pool[abs((keyInt ushr 8) % pool.length)]
                } else {
                    val lum = (0.3f * fr + 0.59f * fg + 0.11f * fb) / 255f
                    val jitter = (keyInt ushr 4) % 3 - 1
                    val chosen = clampF(floor((1f - lum) * (pool.length - 0.01f)) + jitter, 0f, (pool.length - 1).toFloat()).toInt()
                    pool[chosen]
                }
                cellGlyph[i] = chChar

                val glints = if (kind == INK_SHIMMER) sparkle else if (kind == INK_SCALE) sparkle / 3f else 0f
                cellColor[i] = if (roll < glints) {
                    val sIdx = abs(keyInt % 3)
                    packRgb(SPARKLE_R[sIdx], SPARKLE_G[sIdx], SPARKLE_B[sIdx])
                } else {
                    packRgb(clampF(gr, 0f, 255f).roundToInt(), clampF(gg, 0f, 255f).roundToInt(), clampF(gb, 0f, 255f).roundToInt())
                }
            }
        }
    }

    fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: FloatArray, fog: Float, tip: Char? = null) {
        turn(color[0], color[1], color[2], turnTmp)
        val packed = fade(turnTmp[0], turnTmp[1], turnTmp[2], fog)
        val glyph = lineGlyph(atan2(y1 - y0, x1 - x0))
        val len = hypot(x1 - x0, y1 - y0)
        val steps = max(1, ceil(len / (cw * 0.5f)).toInt())
        var last = -1
        for (s in 1..steps) {
            val i = cellIndex(x0 + (x1 - x0) * s / steps, y0 + (y1 - y0) * s / steps)
            if (i < 0 || cellFlag[i] == CELL_SOLID) continue
            cellFlag[i] = CELL_SOFT
            cellColor[i] = packed
            cellGlyph[i] = glyph
            last = i
        }
        if (tip != null && last >= 0) {
            cellGlyph[last] = tip
        }
    }

    fun dot(x: Float, y: Float, glyph: Char, color: FloatArray, fog: Float) {
        val i = cellIndex(x, y)
        if (i < 0 || cellFlag[i] == CELL_SOLID) return
        turn(color[0], color[1], color[2], turnTmp)
        cellFlag[i] = CELL_SOFT
        cellColor[i] = fade(turnTmp[0], turnTmp[1], turnTmp[2], fog)
        cellGlyph[i] = glyph
    }

    fun spot(x: Float, y: Float, glyph: Char, color: FloatArray, fill: FloatArray, fog: Float) {
        val i = cellIndex(x, y)
        if (i < 0) return
        turn(color[0], color[1], color[2], turnTmp)
        val tr = turnTmp[0]; val tg = turnTmp[1]; val tb = turnTmp[2]
        turn(fill[0], fill[1], fill[2], turnTmp)
        val fr = turnTmp[0]; val fg = turnTmp[1]; val fb = turnTmp[2]

        cellFlag[i] = CELL_SOLID
        cellFill[i] = fade(fr, fg, fb, fog)
        cellColor[i] = fade(tr, tg, tb, fog)
        cellGlyph[i] = glyph
    }

    fun setCell(col: Int, row: Int, glyph: Char, color: FloatArray, fill: FloatArray? = null, fog: Float = 0f) {
        if (col < 0 || row < 0 || col >= cols || row >= rows) return
        val i = row * cols + col
        turn(color[0], color[1], color[2], turnTmp)
        val tr = turnTmp[0]; val tg = turnTmp[1]; val tb = turnTmp[2]
        if (fill != null) {
            turn(fill[0], fill[1], fill[2], turnTmp)
            cellFlag[i] = CELL_SOLID
            cellFill[i] = fade(turnTmp[0], turnTmp[1], turnTmp[2], fog)
        } else {
            if (cellFlag[i] == CELL_SOLID) return
            cellFlag[i] = CELL_SOFT
        }
        cellColor[i] = fade(tr, tg, tb, fog)
        cellGlyph[i] = glyph
    }

    fun render(canvas: Canvas) {
        val fp = fillPaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }.also { fillPaint = it }

        val tp = textPaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }.also { textPaint = it }

        if (lastPaintedCw != cw) {
            tp.textSize = max(8f, ch * 0.74f)
            textOffsetY = -(tp.descent() + tp.ascent()) * 0.5f
            lastPaintedCw = cw
        }

        // 1. Solid canvas background
        fp.color = packRgb(
            clampF(activeBg[0], 0f, 255f).roundToInt(),
            clampF(activeBg[1], 0f, 255f).roundToInt(),
            clampF(activeBg[2], 0f, 255f).roundToInt()
        )
        canvas.drawRect(0f, 0f, width, height, fp)

        // 2. Subtle grid (default 0.0 min, only drawn if grid > 0.01f)
        if (tuning.grid > 0.01f) {
            val ga = clampF(0.055f * tuning.grid, 0f, 0.35f)
            fp.color = packArgb((ga * 255f).roundToInt(), 255, 255, 255)
            var c = 0
            while (c <= cols) {
                val px = c * cw
                canvas.drawRect(px, 0f, px + 1f, height, fp)
                c++
            }
            var r = 0
            while (r <= rows) {
                val py = r * ch
                canvas.drawRect(0f, py, width, py + 1f, fp)
                r++
            }
        }

        // 3. Trail particles
        val t = pen.t
        val dotSize = max(2f, floor(cw * 0.22f))
        val ox = (cw - dotSize) * 0.5f
        val oy = (ch - dotSize) * 0.5f
        val inkCol = fade(pen.contrast[0], pen.contrast[1], pen.contrast[2], 0f)
        val ir = (inkCol ushr 16) and 0xFF
        val ig = (inkCol ushr 8) and 0xFF
        val ib = inkCol and 0xFF

        val n = cols * rows
        if (tuning.trail > 0.01f && energy.size == n) {
            for (i in 0 until n) {
                val e = energy[i]
                if (e < 0.12f || cellFlag[i] == CELL_SOLID) continue
                val col = i % cols
                val row = i / cols
                val twinkle = 0.75f + 0.25f * sin(t * 5f + hash3(col, row, 3) * 40f)
                val alpha = clampF(e * twinkle * 0.42f, 0.08f, 0.75f)
                val size = if (e > 0.6f) dotSize + 1f else dotSize
                fp.color = packArgb((alpha * 255f).roundToInt(), ir, ig, ib)
                canvas.drawRect(col * cw + ox, row * ch + oy, col * cw + ox + size, row * ch + oy + size, fp)
            }
        }

        // 4. Solid cells block fill
        for (i in 0 until n) {
            if (cellFlag[i] == CELL_SOLID) {
                val col = i % cols
                val row = i / cols
                fp.color = cellFill[i]
                canvas.drawRect(col * cw + 1f, row * ch + 1f, (col + 1) * cw, (row + 1) * ch, fp)
            }
        }

        // 5. Text glyphs
        val halfCw = cw * 0.5f
        val halfCh = ch * 0.5f + textOffsetY
        val buf = singleCharBuf
        for (i in 0 until n) {
            if (cellFlag[i] == CELL_NONE) continue
            val col = i % cols
            val row = i / cols
            buf[0] = cellGlyph[i]
            tp.color = cellColor[i]
            canvas.drawText(buf, 0, 1, col * cw + halfCw, row * ch + halfCh, tp)
        }
    }
}
