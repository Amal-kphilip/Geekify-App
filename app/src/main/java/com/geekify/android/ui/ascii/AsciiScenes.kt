package com.geekify.android.ui.ascii

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
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// =========================================================================
// 15. RAIN ON WINDOW GLASS
// =========================================================================
private class BokehOrb(
    val x: Float, val y: Float, val r: Float,
    val cr: Float, val cg: Float, val cb: Float,
    val phase: Float
)

private class RainDrop(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    var mass: Float, var stuckTimer: Float,
    var seed: Float, var trailTimer: Float
)

private val BOKEH_COLORS = arrayOf(
    floatArrayOf(255f, 168f, 76f),
    floatArrayOf(82f, 198f, 255f),
    floatArrayOf(255f, 92f, 148f),
    floatArrayOf(118f, 238f, 186f),
    floatArrayOf(255f, 218f, 112f)
)

class RainSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "rain"
    private var width = 1f
    private var height = 1f
    private val drops = ArrayList<RainDrop>(48)
    private val bokeh = ArrayList<BokehOrb>(14)

    override fun resize(width: Float, height: Float) {
        this.width = width
        this.height = height
        bokeh.clear()
        for (i in 0 until 14) {
            val col = BOKEH_COLORS[i % BOKEH_COLORS.size]
            bokeh.add(
                BokehOrb(
                    x = (0.08f + 0.84f * hash1(i * 13.7f)) * width,
                    y = (0.12f + 0.76f * hash1(i * 29.3f)) * height,
                    r = lerpF(26f, 64f, hash1(i * 7.1f)),
                    cr = col[0], cg = col[1], cb = col[2],
                    phase = i * 1.4f
                )
            )
        }
    }

    private fun spawnDrop(x: Float = Random.nextFloat() * width, y: Float = Random.nextFloat() * height, big: Boolean = false): RainDrop {
        return RainDrop(
            x = x, y = y,
            vx = 0f, vy = 0f,
            mass = if (big) lerpF(1.35f, 2.2f, Random.nextFloat()) else lerpF(0.45f, 1.35f, Random.nextFloat()),
            stuckTimer = lerpF(0.2f, 1.8f, Random.nextFloat()),
            seed = Random.nextFloat() * 100f,
            trailTimer = 0f
        )
    }

    override fun onPointerDown(x: Float, y: Float) {
        for (k in 0 until 5) {
            drops.add(
                spawnDrop(
                    x + (Random.nextFloat() - 0.5f) * 44f,
                    y + (Random.nextFloat() - 0.5f) * 30f,
                    big = true
                )
            )
        }
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val downpour = tuning["downpour", 1.2f]
        val mergeRate = tuning["mergeRate", 1.15f]
        val refraction = tuning["refraction", 1.35f]
        val pen = renderer.pen

        // Blurred city bokeh lights behind glass
        for (i in 0 until bokeh.size) {
            val b = bokeh[i]
            val pulse = 0.85f + 0.15f * sin(time * 1.2f + b.phase)
            pen.disc(b.x, b.y, b.r * pulse * (0.7f + 0.3f * refraction), b.cr, b.cg, b.cb, 0.12f, 0.62f)
        }

        // Background rain streaks outside window
        val streaks = max(12, roundToInt(28f * downpour * tuning.density))
        for (s in 0 until streaks) {
            val sx = ((hash1(s * 19.1f) * width + time * 42f) % width)
            val sy = ((hash1(s * 31.7f) * height + time * (420f + (s % 5) * 60f) * tuning.speed) % height)
            pen.dot(sx, sy, '|', 110f, 155f, 205f, 0.2f, 0.58f)
            pen.dot(sx, sy + 11f, ':', 110f, 155f, 205f, 0.2f, 0.65f)
        }

        val target = max(14, roundToInt(36f * tuning.density * downpour))
        while (drops.size < target) drops.add(spawnDrop())
        while (drops.size > target + 12) drops.removeAt(0)

        for (i in drops.size - 1 downTo 0) {
            val d = drops[i]
            d.mass += dt * 0.08f * downpour
            if (d.mass < 1.0f && d.stuckTimer > 0f) {
                d.stuckTimer -= dt * downpour
                d.vy *= 0.85f
            } else {
                val grav = (42f + d.mass * 68f) * tuning.speed * downpour
                d.vy = lerpF(d.vy, grav, min(1f, dt * 4.5f))
                val meander = noise1(d.y * 0.035f + d.seed) * 28f
                d.vx = lerpF(d.vx, meander, min(1f, dt * 6f))
                d.x += d.vx * dt
                d.y += d.vy * dt

                d.trailTimer -= dt
                if (d.trailTimer <= 0f) {
                    d.trailTimer = 0.03f
                    pen.trail(d.x, d.y - 4f, 0.42f)
                    if (d.mass > 1.25f && Random.nextFloat() < 0.35f) {
                        d.mass *= 0.96f
                    }
                }
            }

            // Coalesce nearby droplets
            val mergeDist = (10f + d.mass * 5.5f) * mergeRate * tuning.size
            var j = i - 1
            while (j >= 0) {
                val o = drops[j]
                if (hypot(o.x - d.x, o.y - d.y) < mergeDist) {
                    val total = d.mass + o.mass
                    d.x = (d.x * d.mass + o.x * o.mass) / total
                    d.y = (d.y * d.mass + o.y * o.mass) / total
                    d.mass = min(3.2f, total * 0.92f)
                    d.stuckTimer = 0f
                    drops.removeAt(j)
                    break
                }
                j--
            }

            if (d.y > height + 30f) {
                drops[i] = spawnDrop(Random.nextFloat() * width, -10f)
            }
        }

        for (i in 0 until drops.size) {
            drawDrop(drops[i], refraction, pen)
        }
    }

    private fun roundToInt(v: Float): Int = v.roundToInt()

    private fun drawDrop(d: RainDrop, refraction: Float, pen: AsciiPen) {
        val s = tuning.size
        val rx = (5.5f + d.mass * 3.8f) * s
        val ry = rx * (1f + clampF(d.vy / 220f, 0f, 0.65f))

        var refR = 135f
        var refG = 205f
        var refB = 255f
        for (i in 0 until bokeh.size) {
            val b = bokeh[i]
            val dist = hypot(b.x - d.x, b.y - d.y)
            if (dist < b.r * 2.2f) {
                val w = (1f - dist / (b.r * 2.2f)) * 0.7f * min(1.5f, refraction)
                refR = lerpF(refR, b.cr, w)
                refG = lerpF(refG, b.cg, w)
                refB = lerpF(refB, b.cb, w)
            }
        }

        pen.shape(d.x, d.y, max(rx, ry) * 1.25f, 0.75f, 0.05f, (d.seed * 10f).toInt()) { px, py, out ->
            val dx = px - d.x
            val dy = py - d.y
            val taper = if (dy < 0f) (1f + dy / (ry * 1.6f)) else 1f
            val dist = hypot(dx / max(2f, rx * clampF(taper, 0.35f, 1.1f)), dy / ry)
            if (dist > 1f) return@shape false

            if (hypot(dx + rx * 0.28f, dy + ry * 0.28f) < rx * 0.36f) {
                return@shape out.set(INK_SHIMMER, 240f, 250f, 255f, 0f, 0f, 0f, 0f, '*')
            }
            if (dist > 0.76f) {
                return@shape out.set(INK_LIT, min(255f, refR + 35f), min(255f, refG + 35f), min(255f, refB + 35f))
            }
            out.set(INK_GLASS, refR, refG, refB, refR * 0.25f, refG * 0.28f, refB * 0.35f, 0.55f)
        }
    }
}

// =========================================================================
// 16. SNOWFALL & CRYSTALS
// =========================================================================
private class Snowflake(
    var x: Float, var y: Float, var z: Float,
    var angle: Float, var spin: Float,
    var seed: Float, var radius: Float,
    var vx: Float = 0f, var vy: Float = 0f
)

private class WindGust(var x: Float, var y: Float, var vx: Float, var vy: Float, var age: Float = 0f)

class SnowfallSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "snowfall"
    private var width = 1f
    private var height = 1f
    private val flakes = ArrayList<Snowflake>(80)
    private val gusts = ArrayList<WindGust>(8)

    override fun resize(width: Float, height: Float) {
        this.width = width
        this.height = height
    }

    private fun spawnFlake(initial: Boolean = false): Snowflake {
        val z = Random.nextFloat().pow(1.35f)
        return Snowflake(
            x = Random.nextFloat() * width,
            y = if (initial) Random.nextFloat() * height else -30f,
            z = z,
            angle = Random.nextFloat() * TWO_PI,
            spin = (Random.nextFloat() - 0.5f) * 1.4f,
            seed = Random.nextFloat() * 100f,
            radius = lerpF(6f, 24f, z)
        )
    }

    override fun onPointerDown(x: Float, y: Float) {
        if (gusts.size > 5) gusts.removeAt(0)
        gusts.add(
            WindGust(
                x = x, y = y,
                vx = (if (Random.nextFloat() < 0.5f) 1f else -1f) * 180f,
                vy = -85f,
                age = 0f
            )
        )
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val gustStrength = tuning["gustStrength", 1.35f]
        val drift = tuning["drift", 1.2f]
        val crystalDetail = tuning["crystalDetail", 1.15f]
        val pen = renderer.pen

        val target = max(20, (64f * tuning.density).roundToInt())
        while (flakes.size < target) flakes.add(spawnFlake(true))
        while (flakes.size > target) flakes.removeAt(flakes.lastIndex)

        for (i in gusts.size - 1 downTo 0) {
            val g = gusts[i]
            g.age += dt
            if (g.age > 2.2f) gusts.removeAt(i)
        }

        val globalWind = sin(time * 0.35f) * 28f * gustStrength

        for (i in 0 until flakes.size) {
            val f = flakes[i]
            val depthSpd = 0.35f + 0.85f * f.z
            val flutter = sin(time * 1.8f + f.seed) * 24f * drift * (0.5f + 0.5f * f.z)
            var gx = 0f
            var gy = 0f

            for (j in 0 until gusts.size) {
                val g = gusts[j]
                val dx = f.x - g.x
                val dy = f.y - g.y
                val d = hypot(dx, dy)
                if (d < 220f && d > 1f) {
                    val fade = (1f - g.age / 2.2f) * (1f - d / 220f) * gustStrength
                    gx += (-dy / d * 190f + g.vx * 0.6f) * fade
                    gy += (dx / d * 190f + g.vy * 0.6f) * fade
                }
            }

            f.vx = (globalWind + flutter) * depthSpd + gx
            f.vy = (32f + f.z * 58f) * tuning.speed + gy
            f.x += f.vx * dt
            f.y += f.vy * dt
            f.angle += f.spin * dt

            if (f.x < -40f) f.x = width + 35f
            if (f.x > width + 40f) f.x = -35f
            if (f.y > height + 40f) {
                f.y = -30f
                f.x = Random.nextFloat() * width
            }
        }

        flakes.sortBy { it.z }
        for (i in 0 until flakes.size) {
            val f = flakes[i]
            val fog = (1f - f.z) * tuning.fog
            if (f.z < 0.68f) {
                val ch = if (f.z < 0.32f) '.' else if (f.z < 0.52f) '+' else '*'
                pen.dot(f.x, f.y, ch, 210f, 232f, 255f, f.z, fog)
            } else {
                if (Random.nextFloat() < 0.25f) pen.trail(f.x, f.y, 0.2f)
                drawCrystal(f, fog, crystalDetail, pen)
            }
        }
    }

    private fun drawCrystal(f: Snowflake, fog: Float, crystalDetail: Float, pen: AsciiPen) {
        val r = f.radius * tuning.size * crystalDetail
        val cosA = cos(-f.angle)
        val sinA = sin(-f.angle)

        pen.shape(f.x, f.y, r * 1.2f, f.z, fog, (f.seed * 10f).toInt()) { px, py, out ->
            val dx = px - f.x
            val dy = py - f.y
            val dist = hypot(dx, dy)
            if (dist > r) return@shape false

            val lx = dx * cosA - dy * sinA
            val ly = dx * sinA + dy * cosA
            val ang = atan2(ly, lx)
            val sector = (PI / 3.0).toFloat()
            val folded = abs((((ang % sector) + sector) % sector) - sector * 0.5f)
            val u = dist * cos(folded)
            val v = dist * sin(folded)

            val onSpine = v < max(1.6f, r * 0.085f) && u <= r
            val b1 = abs((u - r * 0.45f) * 0.7f - v * 0.7f) < max(1.4f, r * 0.07f) && u > r * 0.42f && u < r * 0.78f
            val b2 = abs((u - r * 0.68f) * 0.7f - v * 0.7f) < max(1.3f, r * 0.065f) && u > r * 0.65f && u < r * 0.92f
            val core = dist < r * 0.22f

            if (core) {
                return@shape out.set(INK_SHIMMER, 242f, 250f, 255f, 0f, 0f, 0f, 0f, '*')
            }
            if (onSpine || b1 || b2) {
                return@shape out.set(INK_LIT, 218f, 238f, 255f)
            }
            false
        }
    }
}

// =========================================================================
// 17. AURORA BOREALIS
// =========================================================================
class AuroraSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "aurora"
    private var width = 1f
    private var height = 1f
    private var surge = 0f
    private var surgeX = 0.5f

    override fun resize(width: Float, height: Float) {
        this.width = width
        this.height = height
    }

    override fun onPointerDown(x: Float, y: Float) {
        surge = 1.35f
        surgeX = x / max(1f, width)
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val ribbons = roundToInt(tuning["ribbons", 3f]).coerceIn(1, 5)
        val waveAmt = tuning["curtainWave", 1.15f]
        val glowAmt = tuning["coronaGlow", 1.2f]
        val pen = renderer.pen

        if (surge > 0f) surge = max(0f, surge - dt * 0.55f)

        val cols = renderer.cols
        val rows = renderer.rows
        val cw = renderer.cw
        val ch = renderer.ch

        // Twinkling polar stars
        for (i in 0 until 54) {
            val sx = hash1(i * 17.3f) * width
            val sy = hash1(i * 41.9f) * height * 0.72f
            val tw = sin(time * (1.5f + (i % 5) * 0.4f) + i)
            if (tw > -0.2f) {
                pen.dot(sx, sy, if (tw > 0.65f) '+' else '.', 205f, 224f, 255f, 0.08f, 0.35f)
            }
        }

        // Flowing aurora curtains across cell columns
        for (r in 0 until ribbons) {
            val rNorm = r.toFloat() / max(1, ribbons - 1).toFloat()
            val baseYFrac = 0.24f + rNorm * 0.22f
            val curtainH = (height * (0.28f + 0.06f * sin(r * 2.1f))) * tuning.size

            for (gx in 0 until cols) {
                val u = gx.toFloat() / max(1, cols - 1).toFloat()
                val px = (gx + 0.5f) * cw
                val surgeWave = surge * exp(-((u - surgeX) * (u - surgeX)) / 0.06f) * sin(u * 18f - time * 6f) * 28f
                val fold = sin(u * 6.5f * waveAmt + time * 0.75f * tuning.speed + r * 1.9f) * 34f * waveAmt +
                    sin(u * 15f - time * 1.35f * tuning.speed + r * 3.1f) * 14f * waveAmt +
                    surgeWave

                val pleat = 0.55f + 0.45f * sin(u * 26f + time * 1.8f + r * 4.2f)
                val bottomY = height * baseYFrac + fold + curtainH * 0.48f
                val topY = bottomY - curtainH * (0.65f + 0.35f * pleat)

                val gy0 = max(0, floor(topY / ch).toInt())
                val gy1 = min(rows - 1, ceil(bottomY / ch).toInt())

                for (gy in gy0..gy1) {
                    val py = (gy + 0.5f) * ch
                    val v = (bottomY - py) / max(1f, bottomY - topY) // 0 at bright lower hem, 1 at top
                    if (v < 0f || v > 1f) continue

                    val intensity = ((1f - v).pow(0.75f)) * pleat * glowAmt * (1f + surge * 0.45f)
                    if (intensity < 0.16f) continue

                    val cr = lerpF(52f, 188f, smoothstepF(0.15f, 0.92f, v))
                    val cg = lerpF(248f, 78f, smoothstepF(0.25f, 0.95f, v))
                    val cb = lerpF(158f, 238f, smoothstepF(0.10f, 0.85f, v))

                    val glyph = if (v < 0.16f) '#' else if (v < 0.38f) '|' else if (v < 0.68f) ':' else '.'
                    val fillA = clampF((1f - v) * 0.48f * glowAmt, 0.08f, 0.55f)
                    pen.dot(
                        px, py, glyph,
                        min(255f, cr * intensity),
                        min(255f, cg * intensity),
                        min(255f, cb * intensity),
                        0.3f + r * 0.08f,
                        v * 0.45f,
                        cr * 0.28f, cg * 0.28f, cb * 0.32f, fillA
                    )
                }
            }
        }

        // Mountain ridge silhouette along horizon
        for (gx in 0 until cols) {
            val u = gx.toFloat() / max(1, cols - 1).toFloat()
            val px = (gx + 0.5f) * cw
            val ridgeH = height * (0.13f + 0.055f * sin(u * 5.2f + 0.6f) + 0.03f * cos(u * 13.4f) + 0.015f * sin(u * 29f))
            val ridgeY = height - ridgeH
            val gy0 = max(0, floor(ridgeY / ch).toInt())
            for (gy in gy0 until rows) {
                val py = (gy + 0.5f) * ch
                val rim = py - ridgeY < ch * 1.2f
                pen.dot(
                    px, py,
                    if (rim) '^' else '#',
                    if (rim) 58f else 22f,
                    if (rim) 96f else 32f,
                    if (rim) 88f else 44f,
                    0.92f, 0f,
                    10f, 14f, 22f, 0.95f
                )
            }
        }
    }

    private fun roundToInt(v: Float): Int = v.roundToInt()
}

// =========================================================================
// 18. OCEAN WAVES
// =========================================================================
private class WaveSplash(var x: Float, var y: Float, var age: Float = 0f)

class WavesSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "waves"
    private var width = 1f
    private var height = 1f
    private val splashes = ArrayList<WaveSplash>(8)

    override fun resize(width: Float, height: Float) {
        this.width = width
        this.height = height
    }

    override fun onPointerDown(x: Float, y: Float) {
        if (splashes.size > 6) splashes.removeAt(0)
        splashes.add(WaveSplash(x, y, 0f))
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val swellH = tuning["swellHeight", 1.2f]
        val foamAmt = tuning["foam", 1.15f]
        val ringStr = tuning["ringStrength", 1.35f]
        val pen = renderer.pen

        for (i in splashes.size - 1 downTo 0) {
            val s = splashes[i]
            if (s.age == 0f) {
                pen.ripple(s.x, s.y, 120f, 1.0f, 175f, 235f, 255f)
            }
            s.age += dt
            if (s.age > 3.2f) splashes.removeAt(i)
        }

        val cols = renderer.cols
        val rows = renderer.rows
        val cw = renderer.cw
        val ch = renderer.ch
        val spd = tuning.speed

        for (gy in 0 until rows) {
            val v = gy.toFloat() / max(1, rows - 1).toFloat()
            val py = (gy + 0.5f) * ch
            for (gx in 0 until cols) {
                val u = gx.toFloat() / max(1, cols - 1).toFloat()
                val px = (gx + 0.5f) * cw

                val w1 = sin(u * 7.5f + v * 5.2f - time * 1.85f * spd)
                val w2 = sin(u * 4.2f - v * 8.4f - time * 1.35f * spd + 1.7f) * 0.7f
                val w3 = cos(u * 13.0f + v * 9.5f - time * 2.4f * spd) * 0.35f
                var h = (w1 + w2 + w3) * 0.48f * swellH

                for (i in 0 until splashes.size) {
                    val s = splashes[i]
                    val d = hypot(px - s.x, (py - s.y) * 1.25f)
                    val waveR = s.age * 165f
                    val band = abs(d - waveR)
                    if (band < 48f) {
                        val env = (1f - band / 48f) * (1f - s.age / 3.2f) * ringStr
                        h += cos((d - waveR) * 0.18f) * env * 0.85f
                    }
                }

                val foamThresh = 1.02f - 0.24f * min(1.8f, foamAmt)
                if (h > foamThresh) {
                    val f = clampF((h - foamThresh) / 0.55f, 0f, 1f)
                    val chGlyph = if (f > 0.55f) '#' else if (f > 0.25f) '*' else '~'
                    pen.dot(
                        px, py, chGlyph,
                        lerpF(155f, 245f, f),
                        lerpF(225f, 252f, f),
                        255f,
                        0.65f, 0.05f,
                        55f, 125f, 165f, 0.55f * f
                    )
                } else if (h > 0.18f) {
                    val c = clampF((h - 0.18f) / 0.85f, 0f, 1f)
                    val chGlyph = if (c > 0.5f) '=' else '~'
                    pen.dot(
                        px, py, chGlyph,
                        lerpF(38f, 96f, c),
                        lerpF(132f, 206f, c),
                        lerpF(192f, 246f, c),
                        0.45f, 0.18f
                    )
                } else if (h > -0.25f && ((gx + gy) % 2 == 0)) {
                    pen.dot(px, py, '.', 34f, 108f, 168f, 0.25f, 0.42f)
                }
            }
        }
    }
}

object AsciiSimulationFactory {
    fun create(animalId: String, tuning: AsciiTuning): AsciiSimulation = when (animalId) {
        "butterflies" -> ButterfliesSim(tuning)
        "koi" -> KoiSim(tuning)
        "jellyfish" -> JellyfishSim(tuning)
        "frogs" -> FrogsSim(tuning)
        "cranes" -> CranesSim(tuning)
        "crabs" -> CrabsSim(tuning)
        "whale" -> WhaleSim(tuning)
        "rain" -> RainSim(tuning)
        "snowfall" -> SnowfallSim(tuning)
        "waves" -> WavesSim(tuning)
        "aurora" -> AuroraSim(tuning)
        else -> ButterfliesSim(tuning)
    }
}
