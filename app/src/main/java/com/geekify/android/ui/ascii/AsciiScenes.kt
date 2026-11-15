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
import kotlin.math.sqrt
import kotlin.random.Random

// =========================================================================
// 8. RAIN ON WINDOW GLASS
// =========================================================================
private class BokehOrbSpec(
    var x: Float, var y: Float, val rad: Float,
    val r: Float, val g: Float, val b: Float
)

private fun sampleBokeh(nx: Float, ny: Float, t: Float, out: FloatArray) {
    var r = 18f + 16f * (1f - ny)
    var g = 28f + 26f * (1f - ny)
    var b = 52f + 44f * (1f - ny)

    val orbs = arrayOf(
        BokehOrbSpec(0.22f + 0.03f * sin(t * 0.2f), 0.62f, 0.26f, 255f, 165f, 68f),
        BokehOrbSpec(0.48f + 0.04f * cos(t * 0.15f), 0.54f, 0.28f, 72f, 204f, 255f),
        BokehOrbSpec(0.76f + 0.03f * sin(t * 0.25f + 1f), 0.58f, 0.25f, 248f, 92f, 168f),
        BokehOrbSpec(0.35f, 0.36f, 0.22f, 110f, 235f, 190f),
        BokehOrbSpec(0.64f, 0.32f, 0.22f, 255f, 210f, 95f)
    )

    for (o in orbs) {
        val d = hypot(nx - o.x, (ny - o.y) * 1.15f)
        if (d < o.rad) {
            val w = (1f - d / o.rad).pow(1.8f) * 0.72f
            r += o.r * w
            g += o.g * w
            b += o.b * w
        }
    }
    out[0] = clampF(r, 0f, 255f)
    out[1] = clampF(g, 0f, 255f)
    out[2] = clampF(b, 0f, 255f)
}

class RainSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "rain"
    private val flock = Flock(
        tuning = tuning,
        area = 28000f,
        scale = 0.45f,
        speed = 110f,
        maxCreatures = 190,
        windWeight = 0.9f
    )
    private val bokehRgb = FloatArray(3)
    private val dropBokeh = FloatArray(3)
    private val pose = Pose()

    override fun resize(width: Float, height: Float) {
        flock.resize(width, height)
    }

    override fun onPointerDown(x: Float, y: Float) {
        flock.events.add(StampEvent(x, y, 5, 1.4f))
        for (d in flock.creatures) {
            val dist = hypot(d.x - x, d.y - y)
            if (dist < 140f) {
                d.mass = min(2.2f, d.mass + 0.65f * (1f - dist / 140f))
                d.sliding = true
                d.vy = max(d.vy, 110f)
            }
        }
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val downpour = tuning["downpour", 1.2f]
        val mergeRate = tuning["mergeRate", 1.15f]
        val refraction = tuning["refraction", 1.35f]
        val pen = renderer.pen

        flock.step(dt) { d ->
            d.mass = lerpF(0.22f, 1.05f, Random.nextFloat())
            d.radius = (5f + d.mass * 11f) * (0.65f + 0.45f * d.depth) * tuning.size
            d.sliding = d.mass > 0.75f
            d.vx = 0f
            d.vy = if (d.sliding) lerpF(45f, 115f, Random.nextFloat()) else 0f
            d.wobble = Random.nextFloat() * TAU
        }

        // Merging drops
        val list = flock.creatures
        for (i in 0 until list.size) {
            val a = list[i]
            for (j in i + 1 until list.size) {
                val b = list[j]
                val dist = hypot(b.x - a.x, b.y - a.y)
                val touchR = (a.radius + b.radius) * 0.72f * mergeRate
                if (dist < touchR) {
                    val heavier = if (a.mass >= b.mass) a else b
                    val lighter = if (heavier === a) b else a
                    heavier.x = lerpF(heavier.x, lighter.x, 0.3f)
                    heavier.y = lerpF(heavier.y, lighter.y, 0.3f)
                    heavier.mass = min(2.4f, heavier.mass + lighter.mass * 0.65f)
                    heavier.sliding = true
                    heavier.vy = max(heavier.vy, 85f * downpour)
                    flock.events.add(StampEvent(heavier.x, heavier.y, 1, 0.55f))

                    lighter.x = Random.nextFloat() * flock.width
                    lighter.y = lerpF(-15f, flock.height * 0.75f, Random.nextFloat())
                    lighter.mass = lerpF(0.2f, 0.65f, Random.nextFloat())
                    lighter.sliding = false
                    lighter.vx = 0f
                    lighter.vy = 0f
                }
            }
        }

        for (d in list) {
            d.mass += dt * 0.14f * downpour * (0.5f + 0.5f * Random.nextFloat())
            d.radius = (5f + d.mass * 11f) * (0.65f + 0.45f * d.depth) * tuning.size
            if (d.mass > 0.82f) d.sliding = true

            if (!d.sliding) {
                d.vx = flock.windX * 0.04f
                d.vy = max(0f, d.mass - 0.55f) * 14f
                d.x += d.vx * dt
                d.y += d.vy * dt
            } else {
                d.wobble += dt * (4.5f + d.mass * 2.5f)
                val terminal = (95f + d.mass * 115f) * tuning.speed * downpour
                val slip = 0.65f + 0.35f * sin(d.wobble * 1.7f + d.id)
                d.vy = lerpF(d.vy, terminal * slip, min(1f, dt * 5f))
                d.vx = sin(d.wobble + d.y * 0.045f) * 34f + flock.windX * 0.35f
                d.x += d.vx * dt
                d.y += d.vy * dt

                if (d.vy > 45f) {
                    flock.events.add(StampEvent(d.x, d.y - d.radius * 0.6f, 0, 0.24f * d.depth))
                    d.mass = max(0.55f, d.mass - dt * 0.08f)
                }
            }

            if (d.y > flock.height + 40f || d.x < -40f || d.x > flock.width + 40f) {
                d.x = Random.nextFloat() * flock.width
                d.y = lerpF(-25f, flock.height * 0.25f, Random.nextFloat())
                d.mass = lerpF(0.35f, 1.1f, Random.nextFloat())
                d.sliding = d.mass > 0.75f
                d.vy = if (d.sliding) lerpF(40f, 90f, Random.nextFloat()) else 0f
            }
        }

        // Draw blurred city bokeh orbs behind window
        val cols = pen.cols
        val rows = pen.rows
        var r = 0
        while (r < rows) {
            val ny = (r + 0.5f) / rows
            var c = 0
            while (c < cols) {
                val nx = (c + 0.5f) / cols
                sampleBokeh(nx, ny, time, bokehRgb)
                val lum = (bokehRgb[0] + bokehRgb[1] + bokehRgb[2]) / 3f
                if (lum > 46f) {
                    pen.setCell(c, r, if (lum > 85f) ':' else '.', bokehRgb, null, 0.62f)
                }
                c += 2
            }
            r += 2
        }

        // Draw droplets
        val w = max(1f, flock.width)
        val h = max(1f, flock.height)
        for (d in flock.creatures) {
            val rad = max(7f, d.radius)
            val stretch = if (d.sliding) 1f + clampF(d.vy / 260f, 0f, 0.55f) else 1f
            pose.x = d.x
            pose.y = d.y
            pose.heading = HALF_PI
            pose.span = rad * 2.2f
            pose.depth = d.depth
            pose.id = d.id

            pen.shape(pose, 0.65f, { u, v, px, py ->
                val su = u / stretch
                val taper = if (su < 0f) 1f + su * 0.65f else 1f
                val sv = v / max(0.25f, taper)
                val r2 = su * su + sv * sv
                if (r2 > 0.22f) return@shape false
                val rNorm = sqrt(r2 / 0.22f)
                if (u > 0.08f && v < -0.04f && rNorm in 0.52f..0.86f) {
                    return@shape pen.ink.set(240f, 250f, 255f, INK_SPOT, "o*")
                }
                if (rNorm > 0.84f) {
                    return@shape pen.ink.set(95f, 165f, 225f, INK_GLASS, "()")
                }
                val refScale = 0.14f * refraction
                val rnx = clampF(px / w - sv * refScale, 0f, 1f)
                val rny = clampF(py / h - su * refScale, 0f, 1f)
                sampleBokeh(rnx, rny, time, dropBokeh)
                val boost = 1.35f + 0.35f * (1f - rNorm)
                pen.ink.set(
                    min(255f, dropBokeh[0] * boost + 35f),
                    min(255f, dropBokeh[1] * boost + 45f),
                    min(255f, dropBokeh[2] * boost + 60f),
                    if (rNorm < 0.48f) INK_LIT else INK_GLOW,
                    if (rNorm < 0.4f) "@#*" else ":+~"
                )
            }, light = 1.1f, fog = (1f - d.depth) * 0.35f)
        }
    }
}

// =========================================================================
// 9. SNOWFALL & 6-FOLD CRYSTALS
// =========================================================================
private val SNOW_BRIGHT = floatArrayOf(242f, 248f, 255f)
private val SNOW_ICE = floatArrayOf(178f, 214f, 252f)
private val SNOW_FAR = floatArrayOf(132f, 166f, 214f)

class SnowfallSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "snowfall"
    private val flock = Flock(
        tuning = tuning,
        area = 21000f,
        scale = 0.55f,
        speed = 96f,
        maxCreatures = 240,
        windWeight = 1.8f
    )
    private val pose = Pose()

    override fun resize(width: Float, height: Float) {
        flock.resize(width, height)
    }

    override fun onPointerDown(x: Float, y: Float) {
        val gustStr = tuning["gustStrength", 1.35f]
        val gustDir = if (x < flock.width * 0.5f) -0.15f else -PI.toFloat() + 0.15f
        flock.triggerGust(gustDir, 1.45f * gustStr)
        flock.events.add(StampEvent(x, y, 7, 1.3f))
        for (s in flock.creatures) {
            val dx = s.x - x
            val dy = s.y - y
            val d = hypot(dx, dy).coerceAtLeast(1f)
            val falloff = clampF(1f - d / 560f, 0.2f, 1f)
            val layerBoost = (0.4f + 0.9f * s.depth) * gustStr
            s.vx += (dx / d * 260f + cos(gustDir) * 320f) * falloff * layerBoost
            s.vy += (dy / d * 180f - 95f) * falloff * layerBoost
            s.spinRate = lerpF(-5f, 5f, Random.nextFloat())
            s.startledUntil = flock.time + lerpF(0.9f, 1.8f, Random.nextFloat())
        }
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val drift = tuning["drift", 1.2f]
        val crystalDetail = tuning["crystalDetail", 1.15f]
        val pen = renderer.pen

        flock.step(dt) { s ->
            s.spin = Random.nextFloat() * TAU
            s.spinRate = lerpF(-2.2f, 2.2f, Random.nextFloat())
            s.swayPhase = Random.nextFloat() * TAU
        }

        for (i in 0 until flock.creatures.size) {
            val s = flock.creatures[i]
            val layerSpeed = 0.22f + 1.15f * (s.depth * s.depth)
            val fallSpeed = flock.speed * layerSpeed * tuning.speed
            flock.beginSteer()

            s.swayPhase += dt * (1.6f + s.depth * 1.4f)
            val flutter = sin(s.swayPhase) * 42f * drift * (0.4f + 0.6f * s.depth)
            val targetVx = cos(flock.heading) * fallSpeed + flutter + flock.windX * (0.5f + 0.9f * s.depth)
            val targetVy = abs(sin(flock.heading)) * fallSpeed + 28f * layerSpeed
            flock.accX += (targetVx - s.vx) * 2.4f
            flock.accY += (targetVy - s.vy) * 2.4f

            flock.move(s, dt, fallSpeed * 2.8f)
            s.spin += s.spinRate * dt

            val m = 45f
            if (s.y > flock.height + m) {
                s.y = -m
                s.x = Random.nextFloat() * flock.width
            }
            if (s.x > flock.width + m) s.x = -m
            if (s.x < -m) s.x = flock.width + m

            // Draw flake
            if (s.depth < 0.36f) {
                pen.dot(s.x, s.y, if (s.depth < 0.22f) '.' else ':', SNOW_FAR, s.depth)
                continue
            }
            if (s.depth < 0.68f) {
                val g = if (sin(s.spin * 2f) > 0f) '*' else '+'
                pen.dot(s.x, s.y, g, SNOW_ICE, s.depth)
                if (Random.nextFloat() < 0.15f) pen.shed(s.x, s.y, 0.04f * s.depth)
                continue
            }

            // Foreground 6-armed crystal
            val bigSpan = s.span * 0.78f * crystalDetail
            pose.x = s.x
            pose.y = s.y
            pose.heading = s.spin
            pose.span = bigSpan
            pose.depth = s.depth
            pose.id = s.id

            pen.shape(pose, 0.56f, { u, v, _, _ ->
                val r = hypot(u, v)
                if (r > 0.48f) return@shape false
                if (r < 0.08f) return@shape pen.ink.set(SNOW_BRIGHT[0], SNOW_BRIGHT[1], SNOW_BRIGHT[2], INK_SPOT, "*#")

                val a = atan2(v, u)
                val sector = PI.toFloat() / 3f
                val localA = abs((a % sector + sector + sector * 0.5f) % sector - sector * 0.5f)
                val along = r * cos(localA)
                val perp = r * sin(localA)
                val onMain = perp < 0.032f && along < 0.46f
                val b1 = abs(along - 0.22f - perp * 0.58f) < 0.026f && perp < 0.14f
                val b2 = abs(along - 0.34f - perp * 0.58f) < 0.024f && perp < 0.10f
                if (onMain || b1 || b2) {
                    return@shape if (r < 0.26f) {
                        pen.ink.set(SNOW_BRIGHT[0], SNOW_BRIGHT[1], SNOW_BRIGHT[2], INK_LIT, "*+#")
                    } else {
                        pen.ink.set(SNOW_ICE[0], SNOW_ICE[1], SNOW_ICE[2], INK_GLOW, "*+:")
                    }
                }
                false
            }, light = 1.1f)
            pen.shed(s.x, s.y, 0.07f * s.depth)
        }
    }
}

// =========================================================================
// 10. OCEAN WAVES
// =========================================================================
private const val WAVE_GLYPHS = " .,-~:=+*#%@"

private class StoneSplash(var x: Float, var y: Float, var t0: Float)

class WavesSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "waves"
    private val flock = Flock(
        tuning = tuning,
        area = 120000f,
        scale = 1f,
        speed = 55f,
        water = true
    )
    private val stones = ArrayList<StoneSplash>(12)
    private val waveCol = FloatArray(3)
    private val waveFill = FloatArray(3)

    override fun resize(width: Float, height: Float) {
        flock.resize(width, height)
    }

    override fun onPointerDown(x: Float, y: Float) {
        stones.add(StoneSplash(x, y, flock.time))
        if (stones.size > 10) stones.removeAt(0)
        flock.ripple(x, y, 160f, 1.35f, 92f, 195f, 240f, 255f)
        flock.ripple(x, y, 95f, 1.0f, 58f, 140f, 215f, 250f)
        flock.events.add(StampEvent(x, y, 5, 1.6f))
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        flock.step(dt)

        val swellH = tuning["swellHeight", 1.2f]
        val foam = tuning["foam", 1.15f]
        val ringStr = tuning["ringStrength", 1.35f]
        val pen = renderer.pen

        val cols = pen.cols
        val rows = pen.rows
        val cw = pen.cw
        val ch = pen.ch
        val t = time * tuning.speed
        val windBoost = 1f + flock.gust * 0.45f

        val activeStones = stones.filter { time - it.t0 < 7.5f }

        for (r in 0 until rows) {
            val ny = r.toFloat() / max(1f, rows.toFloat())
            val persp = 0.45f + 0.85f * ny
            for (c in 0 until cols) {
                val nx = c.toFloat() / max(1f, cols.toFloat())
                val px = (c + 0.5f) * cw
                val py = (r + 0.5f) * ch

                val w1 = sin(ny * 11.5f / persp - t * 1.85f + sin(nx * 4.2f + t * 0.4f) * 1.4f)
                val w2 = 0.55f * sin(ny * 21f - nx * 6.5f - t * 2.5f)
                val w3 = 0.3f * cos(ny * 33f + nx * 12f - t * 3.2f)
                var h = (w1 + w2 + w3) * 0.52f * swellH * windBoost

                var ringFoam = 0f
                for (st in activeStones) {
                    val age = time - st.t0
                    val d = hypot(px - st.x, (py - st.y) * 1.18f)
                    val front = age * 118f
                    val env = exp(-((d - front) / 56f).let { it * it }) * exp(-age * 0.42f)
                    if (env > 0.01f) {
                        val osc = cos((d - front) * 0.22f)
                        h += osc * env * 0.85f * ringStr
                        if (osc > 0.5f) ringFoam += env * osc * 0.7f
                    }
                }

                val norm = clampF((h + 1f) * 0.5f, 0f, 1f)
                val foamLine = norm + (hash3(c, r, floor(t * 4f).toInt()) - 0.5f) * 0.08f + ringFoam
                val isFoam = foamLine > 1.02f - 0.22f * foam

                if (isFoam) {
                    val fBright = clampF((foamLine - 0.75f) * 2.2f, 0.65f, 1f)
                    waveCol[0] = 215f * fBright + 40f
                    waveCol[1] = 242f * fBright + 13f
                    waveCol[2] = 255f
                    waveFill[0] = 42f; waveFill[1] = 118f; waveFill[2] = 168f
                    val glyph = if (foamLine > 1.08f) '@' else if (foamLine > 0.96f) '#' else '*'
                    pen.setCell(c, r, glyph, waveCol, waveFill, (1f - ny) * 0.35f)
                } else if (norm > 0.26f) {
                    val idx = clampF(floor(norm * (WAVE_GLYPHS.length - 2)), 1f, (WAVE_GLYPHS.length - 3).toFloat()).toInt()
                    waveCol[0] = lerpF(18f, 86f, norm)
                    waveCol[1] = lerpF(68f, 188f, norm)
                    waveCol[2] = lerpF(124f, 242f, norm)
                    val fill = if (norm > 0.66f) {
                        waveFill[0] = 16f; waveFill[1] = 52f; waveFill[2] = 96f
                        waveFill
                    } else {
                        null
                    }
                    pen.setCell(c, r, WAVE_GLYPHS[idx], waveCol, fill, (1f - ny) * 0.45f)
                }
            }
        }
    }
}

// =========================================================================
// 11. AURORA BOREALIS
// =========================================================================
private const val AURORA_GLYPHS = ".,:;!|/\\+*#^@"

private class AuroraSurge(var x: Float, var y: Float, var t0: Float)

class AuroraSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "aurora"
    private val flock = Flock(
        tuning = tuning,
        area = 120000f,
        scale = 1f,
        speed = 40f
    )
    private val surges = ArrayList<AuroraSurge>(8)
    private val starCol = floatArrayOf(190f, 215f, 255f)
    private val auroraCol = FloatArray(3)
    private val auroraFill = FloatArray(3)
    private val ridgeCol = floatArrayOf(26f, 44f, 52f)
    private val ridgeFill = floatArrayOf(8f, 12f, 18f)

    override fun resize(width: Float, height: Float) {
        flock.resize(width, height)
    }

    override fun onPointerDown(x: Float, y: Float) {
        surges.add(AuroraSurge(x, y, flock.time))
        if (surges.size > 6) surges.removeAt(0)
        flock.events.add(StampEvent(x, y, 8, 1.5f))
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        flock.step(dt)

        val ribbons = roundToInt(tuning["ribbons", 3f]).coerceIn(1, 5)
        val curtainWave = tuning["curtainWave", 1.15f]
        val coronaGlow = tuning["coronaGlow", 1.2f]
        val pen = renderer.pen

        val cols = pen.cols
        val rows = pen.rows
        val cw = pen.cw
        val t = time * tuning.speed
        val windShift = flock.windX * 0.003f

        val activeSurges = surges.filter { time - it.t0 < 5f }

        // Twinkling polar stars
        val maxStarRow = (rows * 0.85f).roundToInt()
        for (r in 0 until maxStarRow) {
            for (c in 0 until cols) {
                val h = hash3(c, r, 41)
                if (h > 0.986f) {
                    val twinkle = 0.5f + 0.5f * sin(t * 2.8f + h * 90f)
                    if (twinkle > 0.35f) {
                        pen.setCell(c, r, if (twinkle > 0.82f) '*' else '.', starCol, null, 1f - twinkle * 0.85f)
                    }
                }
            }
        }

        // Aurora ribbons
        for (b in 0 until ribbons) {
            val bandBaseY = 0.22f + b * 0.16f
            val bandSpeed = (0.38f + b * 0.14f) * (if (b % 2 == 0) 1f else -1f)
            for (c in 0 until cols) {
                val nx = c.toFloat() / max(1f, cols.toFloat())
                val fold = sin(nx * 5.2f * curtainWave + t * bandSpeed + b * 2.1f + windShift) * 0.09f +
                    sin(nx * 13.5f * curtainWave - t * bandSpeed * 1.4f + b * 4.3f) * 0.038f

                var surgeBoost = 0f
                var surgeRipple = 0f
                val px = c * cw
                for (sg in activeSurges) {
                    val age = time - sg.t0
                    val dist = abs(px - sg.x)
                    val waveFront = age * 360f
                    val ring = exp(-((dist - waveFront) / 95f).let { it * it }) * exp(-age * 0.65f)
                    surgeBoost += ring * 0.85f
                    surgeRipple += ring * sin(dist * 0.08f - age * 12f) * 0.045f
                }

                val baseRow = ((bandBaseY + fold + surgeRipple) * rows).roundToInt()
                val rayStrength = 0.55f + 0.45f * sin(nx * 34f + t * 1.9f + b * 7f) * cos(nx * 19f - t * 1.1f) + surgeBoost
                val rayRows = max(4, (rows * (0.22f + 0.14f * rayStrength)).roundToInt())

                for (dy in 0 until rayRows) {
                    val r = baseRow - dy
                    if (r < 0 || r >= rows) continue
                    val vFrac = dy.toFloat() / rayRows.toFloat()
                    val intensity = sin(vFrac.pow(0.45f) * PI.toFloat()) * (0.65f + 0.55f * rayStrength)
                    if (intensity < 0.14f) continue

                    val cr: Float
                    val cg: Float
                    val cb: Float
                    if (vFrac < 0.48f) {
                        val u = vFrac / 0.48f
                        cr = lerpF(52f, 46f, u)
                        cg = lerpF(255f, 224f, u)
                        cb = lerpF(138f, 212f, u)
                    } else {
                        val u = (vFrac - 0.48f) / 0.52f
                        cr = lerpF(46f, 218f * coronaGlow, u)
                        cg = lerpF(224f, 78f, u)
                        cb = lerpF(212f, 245f * coronaGlow, u)
                    }

                    val shift = clampF(
                        intensity * (AURORA_GLYPHS.length - 1) + sin(t * 3.5f + c * 0.35f + dy * 0.5f) * 1.6f,
                        0f,
                        (AURORA_GLYPHS.length - 1).toFloat()
                    ).toInt()

                    auroraCol[0] = clampF(cr, 0f, 255f)
                    auroraCol[1] = clampF(cg, 0f, 255f)
                    auroraCol[2] = clampF(cb, 0f, 255f)

                    val fill = if (intensity > 0.78f) {
                        auroraFill[0] = auroraCol[0] * 0.35f
                        auroraFill[1] = auroraCol[1] * 0.35f
                        auroraFill[2] = auroraCol[2] * 0.35f
                        auroraFill
                    } else {
                        null
                    }
                    pen.setCell(c, r, AURORA_GLYPHS[shift], auroraCol, fill, clampF(1f - intensity * 0.95f, 0.05f, 0.88f))
                }
            }
        }

        // Horizon mountain ridge
        for (c in 0 until cols) {
            val nx = c.toFloat() / max(1f, cols.toFloat())
            val ridgeH = 0.04f + 0.035f * sin(nx * 8.5f + 0.7f) + 0.02f * cos(nx * 21f) + 0.008f * sin(c * 1.7f)
            val topRow = rows - (ridgeH * rows).roundToInt()
            for (r in max(0, topRow) until rows) {
                pen.setCell(c, r, if (r == topRow) '^' else '#', ridgeCol, ridgeFill, 0f)
            }
        }
    }

    private fun roundToInt(v: Float): Int = v.roundToInt()
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
