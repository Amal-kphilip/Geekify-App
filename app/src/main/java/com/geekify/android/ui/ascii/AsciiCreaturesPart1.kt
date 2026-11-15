package com.geekify.android.ui.ascii

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
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
// 1. BUTTERFLIES
// =========================================================================
private class LobeSpec(
    val from: Float,
    val to: Float,
    val reach: Float,
    val peak: Float,
    val round: Float,
    val scallop: Float
)

private val BUTTERFLY_FORE = LobeSpec(-0.3f, 1f, 0.56f, 0.55f, 0.5f, 0f)
private val BUTTERFLY_HIND = LobeSpec(-1.45f, 0.15f, 0.44f, 0.48f, 0.42f, 0.04f)
private const val FORE_ROOT = 0.06f
private const val HIND_ROOT = -0.02f
private val BUTTERFLY_BODY = floatArrayOf(30f, 30f, 42f)
private const val FLAP_HZ = 2.6f
private const val BUTTERFLY_GLIDE = 0.35f
private const val BUTTERFLY_FLUTTER = 1.3f

private class ButterflySpeciesPalette(
    val baseR: Float, val baseG: Float, val baseB: Float,
    val darkR: Float, val darkG: Float, val darkB: Float,
    val spotR: Float, val spotG: Float, val spotB: Float
)

private val BUTTERFLY_SPECIES = arrayOf(
    // 0: Morpho Blue (Original)
    ButterflySpeciesPalette(50f, 135f, 255f, 22f, 28f, 62f, 236f, 240f, 255f),
    // 1: Monarch Orange-Gold
    ButterflySpeciesPalette(255f, 132f, 32f, 48f, 18f, 12f, 255f, 242f, 210f),
    // 2: Emerald Swallowtail
    ButterflySpeciesPalette(42f, 228f, 138f, 14f, 46f, 32f, 210f, 255f, 230f),
    // 3: Magenta Rose
    ButterflySpeciesPalette(252f, 68f, 178f, 52f, 14f, 38f, 255f, 220f, 242f),
    // 4: Golden Birdwing
    ButterflySpeciesPalette(255f, 212f, 44f, 46f, 32f, 10f, 255f, 248f, 215f),
    // 5: Amethyst Purple Emperor
    ButterflySpeciesPalette(170f, 96f, 255f, 32f, 16f, 56f, 235f, 215f, 255f),
    // 6: Tropical Cyan Ulysses
    ButterflySpeciesPalette(36f, 224f, 245f, 12f, 42f, 52f, 215f, 255f, 255f),
    // 7: Sunset Crimson Apollo
    ButterflySpeciesPalette(255f, 88f, 74f, 52f, 18f, 18f, 255f, 230f, 215f)
)

private fun lobeReach(l: LobeSpec, phi: Float, tIn: Float = (phi - l.from) / (l.to - l.from)): Float {
    if (tIn <= 0f || tIn >= 1f) return 0f
    val s = if (tIn < l.peak) (tIn / l.peak) * 0.5f else 0.5f + (tIn - l.peak) / (1f - l.peak) * 0.5f
    val r = l.reach * max(0f, sin(PI.toFloat() * s)).pow(l.round)
    return if (l.scallop > 0f) r * (1f - l.scallop * (0.5f + 0.5f * cos(tIn * PI.toFloat() * 14f))) else r
}

private fun bodyHalfWidth(u: Float): Float {
    if (u > 0.17f || u < -0.32f) return 0f
    if (u > 0.11f) return sqrt(max(0f, 0.03f * 0.03f - (u - 0.14f) * (u - 0.14f)))
    if (u > -0.04f) return 0.034f
    return 0.03f * (1f - 0.6f * ((-0.04f - u) / 0.28f))
}

class LobeResult {
    var t: Float = 0f
    var r: Float = 0f
}

private val globalLobe = LobeResult()

private fun inLobe(l: LobeSpec, du: Float, vw: Float, out: LobeResult = globalLobe): Boolean {
    val phi = atan2(du, vw)
    val t = (phi - l.from) / (l.to - l.from)
    val reach = lobeReach(l, phi, t)
    val r = hypot(du, vw)
    if (r > reach || reach <= 0f) return false
    out.t = t
    out.r = r / reach
    return true
}

class ButterfliesSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "butterflies"
    private val flock = Flock(
        tuning = tuning,
        area = 48000f,
        scale = 1f,
        speed = 90f
    )
    private val pose = Pose()
    private val atWorld = FloatArray(2)

    override fun resize(width: Float, height: Float) {
        flock.resize(width, height)
    }

    override fun onPointerDown(x: Float, y: Float) {
        flock.startle(x, y) { b, dx, dy, str ->
            val k = str * 420f * b.depth
            b.vx += dx * k
            b.vy += dy * k
            b.startledUntil = flock.time + lerpF(0.6f, 1.2f, Random.nextFloat())
            b.glideUntil = 0f
        }
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val kFlutter = tuning["flutter", 0f]
        val kGlide = tuning["glide", 2.75f]
        val kFlapRate = tuning["flapRate", 1f]
        val pen = renderer.pen

        flock.step(dt) { b ->
            b.flutterX = 0f
            b.flutterY = 0f
            b.paletteIndex = if (tuning.multiColorButterflies) {
                abs(b.id * 73856093) % BUTTERFLY_SPECIES.size
            } else {
                0
            }
        }

        for (i in 0 until flock.creatures.size) {
            val b = flock.creatures[i]
            val cruise = flock.cruise(b)
            flock.beginSteer()
            flock.steer(b, flock.heading + b.bias * tuning.spread, cruise, tuning.grip)

            val kick = sqrt(max(0f, dt)) * 520f * BUTTERFLY_FLUTTER * kFlutter * (1f - 0.8f * b.glide) * (0.5f + 0.5f * b.depth)
            b.flutterX += -b.flutterX * 3f * dt + (Random.nextFloat() * 2f - 1f) * kick
            b.flutterY += -b.flutterY * 3f * dt + (Random.nextFloat() * 2f - 1f) * kick
            flock.accX += b.flutterX
            flock.accY += b.flutterY

            val down = max(0f, -sin(b.phase)) * (1f - b.glide)
            flock.accX += cos(b.heading) * down * cruise * 1.4f
            flock.accY += sin(b.heading) * down * cruise * 1.4f

            flock.separate(b)
            val spd = flock.move(b, dt, cruise * 2.2f)
            if (flock.passed(b)) flock.reenter(b)
            if (spd > 12f) flock.face(b, dt)

            if (b.glide < 0.5f && time > b.glideUntil && spd > cruise * 0.5f && Random.nextFloat() < BUTTERFLY_GLIDE * kGlide * dt) {
                b.glideUntil = time + lerpF(0.5f, 1.4f, Random.nextFloat())
            }
            b.glide += ((if (time < b.glideUntil) 1f else 0f) - b.glide) * min(1f, dt * 6f)

            val urgency = clampF(spd / (cruise * 2f), 0f, 1f)
            b.phase += dt * TAU * FLAP_HZ * kFlapRate * (0.8f + 0.6f * urgency) * (1f - 0.9f * b.glide)
            val beat = max(0f, 0.5f + 0.5f * cos(b.phase)).pow(0.8f)
            b.open = 0.1f + 0.9f * (beat * (1f - b.glide) + 0.96f * b.glide)

            drawButterfly(b, pen)
        }
    }

    private fun drawButterfly(b: FlockCreature, pen: Pen) {
        val s = b.span
        val open = max(0.08f, b.open)
        pose.x = b.x
        pose.y = b.y
        pose.heading = b.heading + 0.06f * sin(b.phase)
        pose.span = s
        pose.depth = b.depth
        pose.id = b.id

        val pal = if (tuning.multiColorButterflies) {
            BUTTERFLY_SPECIES[b.paletteIndex]
        } else {
            BUTTERFLY_SPECIES[0]
        }

        val minBody = pen.cw * 0.4f
        pen.shape(pose, 0.75f, { u, v, _, _ ->
            val av = abs(v)
            val bw = bodyHalfWidth(u)
            if (bw > 0f && av * s <= max(bw * s, minBody)) {
                return@shape pen.ink.set(BUTTERFLY_BODY[0], BUTTERFLY_BODY[1], BUTTERFLY_BODY[2], INK_BODY)
            }
            val vw = av / open
            val part: Int
            if (inLobe(BUTTERFLY_FORE, u - FORE_ROOT, vw, globalLobe)) {
                part = 0
            } else if (inLobe(BUTTERFLY_HIND, u - HIND_ROOT, vw, globalLobe)) {
                part = 1
            } else {
                return@shape false
            }

            val t = globalLobe.t
            val r = globalLobe.r
            if (r > 0.86f) {
                if (part == 0 && r < 0.95f && abs(fracF(t * 5f) - 0.5f) < 0.14f) {
                    pen.ink.set(pal.spotR, pal.spotG, pal.spotB, INK_SPOT)
                } else {
                    pen.ink.set(pal.darkR, pal.darkG, pal.darkB, INK_DARK)
                }
            } else if (r < 0.08f) {
                pen.ink.set(pal.darkR + 4f, pal.darkG + 16f, pal.darkB + 48f, INK_DARK)
            } else if (part == 0 && t > 0.9f) {
                pen.ink.set(pal.darkR + 8f, pal.darkG + 22f, pal.darkB + 58f, INK_DARK)
            } else {
                pen.ink.set(
                    min(255f, pal.baseR + 70f * r),
                    min(255f, pal.baseG + 60f * r),
                    min(255f, pal.baseB + 40f * r),
                    INK_SHIMMER
                )
            }
            true
        }, light = 0.6f + 0.4f * b.open, tilt = b.open * 3f)

        // Antennae
        val sway = 0.012f * sin(pen.t * 3f + b.id)
        for (side in intArrayOf(-1, 1)) {
            pen.lineBody(pose, 0.16f, 0.012f * side, 0.37f + sway, (0.12f + sway) * side, pen.contrast, if (s > 120f) 'o' else null)
        }

        // Wingtip sparkle shed
        val stroke = max(0f, -sin(b.phase)) * (1f - b.glide)
        val phi = BUTTERFLY_FORE.from + (BUTTERFLY_FORE.to - BUTTERFLY_FORE.from) * BUTTERFLY_FORE.peak
        val tipU = FORE_ROOT + sin(phi) * BUTTERFLY_FORE.reach * 0.95f
        val tipV = cos(phi) * BUTTERFLY_FORE.reach * 0.95f * b.open
        for (side in intArrayOf(-1, 1)) {
            pen.toWorld(pose, tipU, tipV * side, atWorld)
            pen.shed(atWorld[0], atWorld[1], (0.02f + 0.1f * stroke) * b.depth)
        }
    }
}

// =========================================================================
// 2. KOI
// =========================================================================
private const val KOI_NODES = 14
private const val PELLET_LIFE = 14f
private val KOI_WHITE = floatArrayOf(244f, 240f, 230f)
private val KOI_RED = floatArrayOf(222f, 66f, 40f)
private val KOI_BLACK = floatArrayOf(34f, 34f, 40f)
private val KOI_GOLD = floatArrayOf(238f, 182f, 64f)
private val KOI_ORANGE = floatArrayOf(236f, 124f, 44f)
private val KOI_FIN = floatArrayOf(236f, 226f, 214f)
private val KOI_FOOD = floatArrayOf(232f, 178f, 104f)
private val KOI_EYE = floatArrayOf(14f, 12f, 16f)
private val KOI_GLINT = floatArrayOf(255f, 252f, 246f)
private val KOI_VARIETIES = arrayOf("kohaku", "sanke", "showa", "ogon", "chagoi")

private fun koiHalfWidth(s: Float): Float {
    if (s < 0.12f) return 0.1f * max(0f, sin(s / 0.12f * PI.toFloat() / 2f)).pow(0.6f)
    if (s < 0.35f) return 0.1f + 0.016f * sin((s - 0.12f) / 0.23f * (PI.toFloat() / 2f))
    if (s < 0.82f) return 0.116f - 0.088f * smoothstepF(0.35f, 0.82f, s)
    return 0.028f + (s - 0.82f) / 0.18f * 0.1f
}

private fun koiBlotch(s: Float, a: Float, seed: Float): Float {
    return sin(s * 11f + seed) * cos(a * 22f + seed * 1.3f) +
        0.6f * sin(s * 23f + a * 15f + seed * 2.1f) +
        0.35f * sin(s * 5f - seed * 0.7f)
}

private class KoiFoodPellet(
    var x: Float, var y: Float, var vx: Float, var vy: Float, var born: Float
)

class KoiSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "koi"
    private val flock = Flock(
        tuning = tuning,
        area = 75000f,
        scale = 1.15f,
        speed = 50f,
        water = true,
        windWeight = 0.25f
    )
    private val food = ArrayList<KoiFoodPellet>(32)
    private val spine = FloatArray(KOI_NODES * 2)
    private val arc = FloatArray(KOI_NODES)
    private val pose = Pose()

    override fun resize(width: Float, height: Float) {
        flock.resize(width, height)
    }

    override fun onPointerDown(x: Float, y: Float) {
        flock.ripple(x, y, 95f, 1.2f, 76f)
        val n = 7 + Random.nextInt(5)
        for (i in 0 until n) {
            val a = Random.nextFloat() * TAU
            val r = 70f * sqrt(Random.nextFloat())
            food.add(
                KoiFoodPellet(
                    x = x + cos(a) * r,
                    y = y + sin(a) * r,
                    vx = cos(a) * lerpF(4f, 14f, Random.nextFloat()),
                    vy = sin(a) * lerpF(4f, 14f, Random.nextFloat()),
                    born = flock.time
                )
            )
            flock.events.add(StampEvent(x, y, 1, 0.5f))
        }
        if (food.size > 40) {
            while (food.size > 40) food.removeAt(0)
        }
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val meander = tuning["meander", 0.4f]
        val tailBeat = tuning["tailBeat", 1f]
        val foodRange = tuning["foodRange", 200f]
        val pen = renderer.pen

        // Update food pellets
        val expFric = exp(-dt * 0.8f)
        var fi = food.size - 1
        while (fi >= 0) {
            val p = food[fi]
            p.vx *= expFric
            p.vy *= expFric
            p.x += p.vx * dt
            p.y += p.vy * dt
            if (time - p.born > PELLET_LIFE) {
                food.removeAt(fi)
            }
            fi--
        }

        flock.step(dt) { k ->
            k.phase = Random.nextFloat() * TAU
            k.variety = (hash3(k.id, 7, 3) * KOI_VARIETIES.size).toInt() % KOI_VARIETIES.size
            val seg = k.span / (KOI_NODES - 1)
            for (node in 0 until KOI_NODES) {
                k.nodes[node * 2] = k.x - cos(k.heading) * seg * node
                k.nodes[node * 2 + 1] = k.y - sin(k.heading) * seg * node
            }
        }

        // Draw food
        for (p in food) {
            val age = (time - p.born) / PELLET_LIFE
            pen.dot(p.x, p.y, if (age < 0.7f) 'o' else '.', KOI_FOOD, min(0.9f, max(0f, age - 0.5f) * 1.6f))
        }

        for (i in 0 until flock.creatures.size) {
            val f = flock.creatures[i]
            var meal: KoiFoodPellet? = null
            var bestD = foodRange
            for (p in food) {
                val d = hypot(p.x - f.x, p.y - f.y)
                if (d < bestD) {
                    bestD = d
                    meal = p
                }
            }
            val pull = if (meal != null) 1f - bestD / foodRange else 0f
            val hunger = if (meal != null) 0.25f + 0.75f * pull else 0f

            var want = if (meal != null) {
                atan2(meal.y - f.y, meal.x - f.x)
            } else {
                flock.heading + f.bias * tuning.spread + meander * 0.6f * sin(time * 0.35f + f.id * 1.7f)
            }

            flock.beginSteer()
            flock.separate(f, tuning.separation, 0.5f)
            if (flock.accX != 0f || flock.accY != 0f) {
                val cr = flock.cruise(f)
                want = atan2(sin(want) * cr + flock.accY * 0.3f, cos(want) * cr + flock.accX * 0.3f)
            }

            val turn = lerpF(0.9f * tuning.grip, 2.6f, hunger) * dt
            f.heading += clampF(angleDiff(f.heading, want), -turn, turn)
            val spd = flock.cruise(f) * lerpF(0.85f + 0.25f * sin(time * 0.5f + f.id), 1.8f, hunger)
            f.vx = cos(f.heading) * spd
            f.vy = sin(f.heading) * spd
            f.x += f.vx * dt
            f.y += f.vy * dt
            f.phase += dt * (2f + spd / 25f) * tailBeat

            val n = f.nodes
            val seg = f.span / (KOI_NODES - 1)
            n[0] = f.x
            n[1] = f.y
            var prev = f.heading + PI.toFloat()
            for (node in 1 until KOI_NODES) {
                var a = atan2(n[node * 2 + 1] - n[node * 2 - 1], n[node * 2] - n[node * 2 - 2])
                a = prev + clampF(angleDiff(prev, a), -0.22f, 0.22f)
                n[node * 2] = n[node * 2 - 2] + cos(a) * seg
                n[node * 2 + 1] = n[node * 2 - 1] + sin(a) * seg
                prev = a
            }

            if (meal != null && hypot(meal.x - f.x, meal.y - f.y) < f.span * 0.1f + 6f) {
                food.remove(meal)
                flock.events.add(StampEvent(meal.x, meal.y, 1, 0.7f))
                flock.ripple(meal.x, meal.y, 48f * f.depth, 0.9f * f.depth, 54f)
            }
            if (flock.passed(f, f.span * 1.3f)) flock.reenter(f)

            drawKoi(f, pen)
        }
    }

    private fun drawKoi(f: FlockCreature, pen: Pen) {
        val L = f.span
        val n = f.nodes
        val variety = KOI_VARIETIES[f.variety]
        val seed = f.id * 1.37f
        val white = KOI_WHITE
        val fin = KOI_FIN

        arc[0] = 0f
        for (i in 0 until KOI_NODES) {
            val s = i.toFloat() / (KOI_NODES - 1).toFloat()
            val a = max(0, i - 1)
            val b = min(KOI_NODES - 1, i + 1)
            var tx = n[a * 2] - n[b * 2]
            var ty = n[a * 2 + 1] - n[b * 2 + 1]
            val tl = hypot(tx, ty).coerceAtLeast(1f)
            tx /= tl
            ty /= tl
            val off = L * 0.06f * max(0f, s).pow(1.6f) * sin(f.phase - s * 4.5f) * min(1.5f, tuning["tailBeat", 1f])
            spine[i * 2] = n[i * 2] - ty * off
            spine[i * 2 + 1] = n[i * 2 + 1] + tx * off
            if (i > 0) {
                arc[i] = arc[i - 1] + hypot(spine[i * 2] - spine[i * 2 - 2], spine[i * 2 + 1] - spine[i * 2 - 1])
            }
        }
        val total = arc[KOI_NODES - 1].coerceAtLeast(1f)
        val mid = KOI_NODES / 2

        pose.x = spine[mid * 2]
        pose.y = spine[mid * 2 + 1]
        pose.heading = f.heading
        pose.span = L
        pose.depth = f.depth
        pose.id = f.id

        val finBeat = 0.8f + 0.2f * sin(f.phase * 0.7f)
        pen.shape(pose, 0.72f, { _, _, px, py ->
            var best = Float.POSITIVE_INFINITY
            var s = 0f
            var a = 0f
            var past = 0f
            for (i in 0 until KOI_NODES - 1) {
                val ax = spine[i * 2]; val ay = spine[i * 2 + 1]
                val sx = spine[i * 2 + 2] - ax
                val sy = spine[i * 2 + 3] - ay
                val len2 = (sx * sx + sy * sy).coerceAtLeast(1f)
                val raw = ((px - ax) * sx + (py - ay) * sy) / len2
                val q = clampF(raw, 0f, 1f)
                val sl = sqrt(len2)
                if (i == KOI_NODES - 2 && raw > 1f) {
                    val across = abs(sx * (py - ay) - sy * (px - ax)) / sl
                    if (across * across < best) {
                        best = across * across
                        s = 1f
                        past = (raw - 1f) * sl / L
                        a = (across / L) * (if (sx * (py - ay) - sy * (px - ax) >= 0f) 1f else -1f)
                    }
                    continue
                }
                val d2 = (px - ax - sx * q) * (px - ax - sx * q) + (py - ay - sy * q) * (py - ay - sy * q)
                if (d2 < best) {
                    best = d2
                    s = (arc[i] + q * sl) / total
                    a = (sqrt(d2) / L) * (if (sx * (py - ay) - sy * (px - ax) >= 0f) 1f else -1f)
                    past = 0f
                }
            }

            val aa = abs(a)
            val w = koiHalfWidth(s)
            pen.ink.au = s
            pen.ink.av = a
            if (past > 0f) return@shape false

            val pect = s in 0.17f..0.34f && aa < w + 0.1f * finBeat * sin((s - 0.17f) / 0.17f * PI.toFloat())
            val pelvic = s in 0.48f..0.6f && aa < w + 0.05f * sin((s - 0.48f) / 0.12f * PI.toFloat())
            if (aa > w) {
                return@shape if (pect || pelvic) pen.ink.set(fin[0], fin[1], fin[2], INK_GLASS, "-=") else false
            }
            if (s > 0.82f) {
                if (s > 0.93f && aa < (s - 0.93f) / 0.07f * 0.06f) return@shape false
                return@shape pen.ink.set(fin[0], fin[1], fin[2], INK_GLASS, if (s > 0.9f) "/\\" else "=")
            }

            val patch = koiBlotch(s, a, seed) > 0.25f
            val across2 = aa / max(1e-4f, w)
            when (variety) {
                "kohaku" -> {
                    if (patch && s < 0.8f) pen.ink.set(KOI_RED[0], KOI_RED[1], KOI_RED[2]) else pen.ink.set(white[0], white[1], white[2])
                }
                "sanke" -> {
                    if (hash3((s * 30f).roundToInt(), (a * 60f).roundToInt(), f.id) > 0.9f && s > 0.15f) {
                        pen.ink.set(KOI_BLACK[0], KOI_BLACK[1], KOI_BLACK[2], INK_DARK)
                    } else if (patch && s < 0.8f) {
                        pen.ink.set(KOI_RED[0], KOI_RED[1], KOI_RED[2])
                    } else {
                        pen.ink.set(white[0], white[1], white[2])
                    }
                }
                "showa" -> {
                    if (koiBlotch(s, a, seed + 4f) > 0.55f) {
                        pen.ink.set(white[0], white[1], white[2])
                    } else if (patch) {
                        pen.ink.set(KOI_RED[0], KOI_RED[1], KOI_RED[2])
                    } else {
                        pen.ink.set(KOI_BLACK[0], KOI_BLACK[1], KOI_BLACK[2], INK_DARK, "%#")
                    }
                }
                "ogon" -> {
                    pen.ink.set(KOI_GOLD[0], KOI_GOLD[1], KOI_GOLD[2], INK_SHIMMER)
                }
                else -> {
                    pen.ink.set(KOI_ORANGE[0] * (1f - 0.15f * across2), KOI_ORANGE[1] * (1f - 0.15f * across2), KOI_ORANGE[2], INK_SHIMMER)
                }
            }
            true
        })

        // Eyes
        val across = koiHalfWidth(0.09f) * 0.55f * L
        if (2f * across >= pen.cw) {
            val along = 0.09f * total
            var idx = 0
            while (idx < KOI_NODES - 2 && arc[idx + 1] < along) idx++
            val sl = (arc[idx + 1] - arc[idx]).coerceAtLeast(1f)
            val q = (along - arc[idx]) / sl
            val dx = (spine[idx * 2 + 2] - spine[idx * 2]) / sl
            val dy = (spine[idx * 2 + 3] - spine[idx * 2 + 1]) / sl
            val hx = spine[idx * 2] + dx * q * sl
            val hy = spine[idx * 2 + 1] + dy * q * sl
            for (side in intArrayOf(-1, 1)) {
                pen.spot(hx - dy * across * side, hy + dx * across * side, '\u25CF', KOI_GLINT, KOI_EYE, f.depth)
            }
        }
        pen.shed(spine[(KOI_NODES - 2) * 2], spine[(KOI_NODES - 2) * 2 + 1], 0.02f * f.depth)
    }
}

// =========================================================================
// 3. JELLYFISH
// =========================================================================
private val JELLY_CROWN = floatArrayOf(255f, 168f, 230f)
private val JELLY_BODY = floatArrayOf(232f, 56f, 182f)
private val JELLY_RIM = floatArrayOf(255f, 200f, 240f)
private val JELLY_LOBES = floatArrayOf(255f, 214f, 244f)
private val JELLY_DOTS = floatArrayOf(255f, 240f, 250f)
private val JELLY_FRILL = floatArrayOf(255f, 214f, 240f)
private val JELLY_CORE = floatArrayOf(204f, 56f, 172f)
private val JELLY_TENTACLE = floatArrayOf(232f, 100f, 204f)
private val JELLY_SNOW = floatArrayOf(255f, 170f, 226f)

class JellyfishSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "jellyfish"
    private val flock = Flock(
        tuning = tuning,
        area = 95000f,
        scale = 1.6f,
        speed = 55f
    )
    private val pose = Pose()
    private val atWorld = FloatArray(2)
    private val btWorld = FloatArray(2)

    override fun resize(width: Float, height: Float) {
        flock.resize(width, height)
    }

    override fun onPointerDown(x: Float, y: Float) {
        flock.startle(x, y) { j, _, _, str ->
            j.phase = floor(j.phase / TAU) * TAU + TAU
            val boost = 260f * str * j.depth
            j.vx += cos(j.heading) * boost
            j.vy += sin(j.heading) * boost
            j.startledUntil = flock.time + 0.8f
        }
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val pulseRate = tuning["pulseRate", 1f]
        val pulsePower = tuning["pulsePower", 1f]
        val pen = renderer.pen

        flock.step(dt) { j ->
            j.phase = Random.nextFloat() * TAU
            j.squeeze = 0f
            j.turn = 0f
        }

        // Draw marine snow
        val nSnow = max(12, (flock.width * flock.height / 6000f).roundToInt())
        for (i in 0 until nSnow) {
            val rise = 3f + 9f * hash3(i, 3, 1)
            val sx = hash3(i, 1, 1) * flock.width + sin(time * 0.25f + i) * 6f
            val sy = ((hash3(i, 2, 1) * flock.height - time * rise) % flock.height + flock.height) % flock.height
            val twinkle = 0.5f + 0.5f * sin(time * (0.8f + 1.6f * hash3(i, 4, 1)) + i)
            val big = hash3(i, 5, 1) > 0.92f
            pen.dot(sx, sy, if (big) '*' else '.', JELLY_SNOW, min(0.95f, 0.35f + 0.5f * (1f - twinkle) + (if (big) 0f else 0.15f)))
        }

        for (i in 0 until flock.creatures.size) {
            val j = flock.creatures[i]
            j.phase += dt * TAU * 0.6f * pulseRate * (0.9f + 0.2f * j.roll)
            val p = fracF(j.phase / TAU)
            j.squeeze = if (p < 0.22f) smoothstepF(0f, 0.22f, p) else 1f - smoothstepF(0.22f, 1f, p)

            val want = flock.heading + j.bias * tuning.spread
            val before = j.heading
            j.heading += clampF(angleDiff(j.heading, want), -0.6f * tuning.grip * dt, 0.6f * tuning.grip * dt)
            j.turn += (angleDiff(before, j.heading) / max(1e-4f, dt) - j.turn) * min(1f, dt * 3f)

            val cruise = flock.cruise(j)
            flock.beginSteer()
            val thrust = if (p < 0.22f) 6.2f * cruise * pulsePower else 0f
            flock.accX += cos(j.heading) * thrust - j.vx * 1.4f
            flock.accY += sin(j.heading) * thrust - j.vy * 1.4f
            flock.separate(j, tuning.separation, 0.5f)
            flock.move(j, dt, cruise * 3f)
            if (flock.passed(j, j.span * 1.4f)) flock.reenter(j)

            drawJelly(j, pen)
        }
    }

    private fun drawJelly(j: FlockCreature, pen: Pen) {
        val s = j.span
        val sq = j.squeeze
        val t = pen.t
        pose.x = j.x
        pose.y = j.y
        pose.heading = j.heading
        pose.span = s
        pose.depth = j.depth
        pose.id = j.id

        val len = tuning["tentacles", 1f]
        val baseFog = pen.fogFor(j.depth)
        val a = 0.32f * (1f + 0.14f * sq)
        val b = 0.3f * (1f - 0.2f * sq)
        val u0 = -0.04f
        val sway = clampF(-j.turn * 0.5f, -0.5f, 0.5f)

        val p = fracF(j.phase / TAU)
        val pulseGlowVal = if (p < 0.16f) smoothstepF(0f, 0.16f, p) else exp(-(p - 0.16f) * 4.5f)
        val flash = pulseGlowVal * tuning["pulseGlow", 1f]

        // 8 outer tentacles
        for (n in 0 until 8) {
            val v0 = (n / 7f - 0.5f) * 2f * b * 0.6f
            var pu = u0 - 0.02f
            var pv = v0
            val segs = 14
            val length = 1.5f * len * (0.75f + 0.35f * hash3(j.id, n, 4))
            for (i in 1..segs) {
                val f = i.toFloat() / segs.toFloat()
                val u = u0 - 0.02f - f * length * (1f - 0.12f * sq)
                val v = v0 * (1f - 0.3f * f) + 0.04f * sin(j.phase - f * 3.5f + n * 1.3f) * f + sway * f * f * 0.4f
                pen.toWorld(pose, pu, pv, atWorld)
                pen.toWorld(pose, u, v, btWorld)
                pen.line(atWorld[0], atWorld[1], btWorld[0], btWorld[1], JELLY_TENTACLE, min(0.95f, baseFog + 0.45f + 0.35f * f))
                pu = u
                pv = v
            }
        }

        // 4 oral arms
        val frillChars = "{}~§"
        for (n in 0 until 4) {
            val v0 = (n / 3f - 0.5f) * 0.11f
            val segs = 26
            val length = 1.05f * len * (0.8f + 0.3f * hash3(j.id, n, 7))
            for (i in 1..segs) {
                val f = i.toFloat() / segs.toFloat()
                val u = u0 - f * length
                val v = v0 * (1f - 0.4f * f) + 0.05f * sin(j.phase * 0.9f - f * 4.5f + n * 1.7f) * f + sway * f * 0.25f
                val w = 0.055f * (1f - 0.5f * f) * cos(f * 9f + n * 1.3f + t * 0.6f)
                val fog = min(0.95f, baseFog + 0.3f * f)
                pen.toWorld(pose, u, v, atWorld)
                pen.dot(atWorld[0], atWorld[1], if (i % 3 != 0) ':' else '|', JELLY_CORE, min(0.95f, fog + 0.15f))
                for (side in intArrayOf(-1, 1)) {
                    pen.toWorld(pose, u, v + w * side, atWorld)
                    val ch = frillChars[abs((i + n + (if (side > 0) 1 else 0)) % frillChars.length)]
                    pen.dot(atWorld[0], atWorld[1], ch, JELLY_FRILL, fog)
                }
            }
            pen.toWorld(pose, u0 - length, v0, atWorld)
            pen.shed(atWorld[0], atWorld[1], 0.015f * j.depth)
        }

        // Outer glow rim
        val h = 1.3f * (1f + 0.18f * flash)
        pen.shape(pose, 0.32f * h + 0.1f, { u, v, _, _ ->
            val r = hypot((u - u0) / (a * h), v / (b * h))
            if (r < 1f && u > u0 - 0.08f) {
                pen.ink.set(JELLY_RIM[0], JELLY_RIM[1], JELLY_RIM[2], INK_GLOW, ".")
            } else {
                false
            }
        }, fog = clampF(baseFog + 0.55f - 0.3f * flash, 0f, 0.95f))

        // Main bell body
        pen.shape(pose, 0.42f, { u, v, _, _ ->
            val du = (u - u0) / a
            val dv = v / b
            val r2 = du * du + dv * dv
            val margin = u0 - 0.04f * dv * dv
            if (r2 > 1f || u < margin) {
                if (abs(dv) > 1f || u > margin || u < margin - 0.04f) return@shape false
                val lobe = 0.04f * sin(fracF((dv + 1f) * 5.5f) * PI.toFloat())
                return@shape if (u > margin - lobe) pen.ink.set(JELLY_LOBES[0], JELLY_LOBES[1], JELLY_LOBES[2], INK_LIT, "o") else false
            }
            val r = sqrt(r2)
            if (r > 0.88f) return@shape pen.ink.set(JELLY_RIM[0], JELLY_RIM[1], JELLY_RIM[2], INK_LIT, ":;")

            val th = atan2(dv, du)
            if (du > 0.3f) {
                val gu = (du * 11f).roundToInt()
                val gv = (dv * 13f).roundToInt()
                val near = hypot(du * 11f - gu, dv * 13f - gv) < 0.32f
                if (near && hash3(gu, gv, j.id) > 0.45f) {
                    return@shape pen.ink.set(JELLY_DOTS[0], JELLY_DOTS[1], JELLY_DOTS[2], INK_LIT, ".'")
                }
            }
            if (r > 0.35f && fracF(th / PI.toFloat() * 6f) < 0.1f) {
                return@shape pen.ink.set(JELLY_RIM[0], JELLY_RIM[1], JELLY_RIM[2], INK_LIT, "|")
            }
            val glow = 0.75f + 0.25f * max(clampF(du, 0f, 1f), r * r)
            val cR = lerpF(JELLY_BODY[0], JELLY_CROWN[0], clampF(du, 0f, 1f) * 0.8f)
            val cG = lerpF(JELLY_BODY[1], JELLY_CROWN[1], clampF(du, 0f, 1f) * 0.8f)
            val cB = lerpF(JELLY_BODY[2], JELLY_CROWN[2], clampF(du, 0f, 1f) * 0.8f)
            pen.ink.set(cR * glow, cG * glow, cB * glow, INK_LIT, " .")
        }, light = (0.92f + 0.18f * sq) * (1f + 0.6f * flash))
    }
}

// =========================================================================
// 4. FROGS
// =========================================================================
private val FROG_SKIN = floatArrayOf(104f, 184f, 76f)
private val FROG_LEG = floatArrayOf(92f, 164f, 66f)
private val FROG_SPOT = floatArrayOf(30f, 70f, 30f)
private val FROG_HALO = floatArrayOf(190f, 222f, 130f)
private val FROG_RIDGE = floatArrayOf(206f, 226f, 150f)
private val FROG_BAND = floatArrayOf(36f, 80f, 32f)
private val FROG_EYE_WHITE = floatArrayOf(240f, 240f, 228f)
private val FROG_PUPIL = floatArrayOf(16f, 16f, 12f)
private val FROG_LID = floatArrayOf(20f, 30f, 16f)
private val FROG_WEB = floatArrayOf(120f, 190f, 90f)

private val HIND_REST = arrayOf(floatArrayOf(-0.16f, 0.11f), floatArrayOf(0f, 0.22f), floatArrayOf(-0.19f, 0.25f), floatArrayOf(-0.04f, 0.31f))
private val HIND_KICK = arrayOf(floatArrayOf(-0.17f, 0.09f), floatArrayOf(-0.38f, 0.15f), floatArrayOf(-0.56f, 0.13f), floatArrayOf(-0.68f, 0.15f))
private val HIND_RADII = floatArrayOf(0.056f, 0.04f, 0.026f)

private val FRONT_REST = arrayOf(floatArrayOf(0.06f, 0.1f), floatArrayOf(0.09f, 0.19f), floatArrayOf(0.18f, 0.2f))
private val FRONT_AIR = arrayOf(floatArrayOf(0.06f, 0.1f), floatArrayOf(0.02f, 0.16f), floatArrayOf(-0.06f, 0.15f))
private val FRONT_REACH = arrayOf(floatArrayOf(0.08f, 0.11f), floatArrayOf(0.16f, 0.19f), floatArrayOf(0.27f, 0.19f))
private val FRONT_RADII = floatArrayOf(0.028f, 0.022f)

private fun frogHalfWidth(u: Float, breathe: Float): Float {
    val trunk = 0.155f * sqrt(max(0f, 1f - ((u + 0.1f) / 0.18f) * ((u + 0.1f) / 0.18f)))
    var head = 0.15f * breathe * sqrt(max(0f, 1f - ((u - 0.12f) / 0.18f) * ((u - 0.12f) / 0.18f)))
    if (u > 0.18f) head *= 1f - 0.5f * ((u - 0.18f) / 0.12f) * ((u - 0.18f) / 0.12f)
    return max(trunk, head)
}

private fun blendLimb(a: Array<FloatArray>, b: Array<FloatArray>, t: Float, out: Array<FloatArray>) {
    for (i in a.indices) {
        out[i][0] = lerpF(a[i][0], b[i][0], t)
        out[i][1] = lerpF(a[i][1], b[i][1], t)
    }
}

private class LimbSegHit {
    var t: Float = 0f
}

private val frogSegHit = LimbSegHit()

private fun onLimb(joints: Array<FloatArray>, radii: FloatArray, u: Float, v: Float, outHit: LimbSegHit = frogSegHit): Boolean {
    for (i in 0 until joints.size - 1) {
        val au = joints[i][0]; val av = joints[i][1]
        val bu = joints[i + 1][0]; val bv = joints[i + 1][1]
        val d = toSegment(u, v, au, av, bu, bv, globalSeg)
        if (d <= radii[i] * (1f - 0.25f * globalSeg.t)) {
            outHit.t = (i + globalSeg.t) / (joints.size - 1)
            return true
        }
    }
    return false
}

class FrogsSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "frogs"
    private val flock = Flock(
        tuning = tuning,
        area = 75000f,
        scale = 0.95f,
        speed = 80f,
        water = true,
        windWeight = 0.2f
    )
    private val pose = Pose()
    private val hindLimb = Array(4) { FloatArray(2) }
    private val frontLimb = Array(3) { FloatArray(2) }
    private val atWorld = FloatArray(2)

    override fun resize(width: Float, height: Float) {
        flock.resize(width, height)
    }

    override fun onPointerDown(x: Float, y: Float) {
        flock.startle(x, y) { f, dx, dy, str ->
            if (!f.leaping) {
                leap(f, atan2(dy, dx), 1f + str, onCourse = false)
                f.startledUntil = flock.time + f.dur
            }
        }
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val pen = renderer.pen
        val hop = tuning["hop", 1f]

        flock.step(dt) { f ->
            f.leaping = false
            f.s = 0f
            f.vx = 0f
            f.vy = 0f
            f.aim = flock.heading + f.bias * tuning.spread
            f.heading = f.aim
            f.until = flock.time + lerpF(0f, 1.5f, Random.nextFloat())
        }

        for (i in 0 until flock.creatures.size) {
            val f = flock.creatures[i]
            if (!f.leaping) {
                f.vx = 0f
                f.vy = 0f
                flock.face(f, dt, 2.5f, f.aim)
                if (time >= f.until) {
                    leap(f, f.aim, 1f, onCourse = true)
                }
            } else {
                f.s += dt / f.dur
                if (f.s >= 1f) {
                    f.leaping = false
                    f.x = f.toX
                    f.y = f.toY
                    f.vx = 0f
                    f.vy = 0f
                    f.until = time + lerpF(0.5f, 2.2f, Random.nextFloat()) / max(0.1f, hop * tuning.speed)
                    f.aim = flock.heading + (f.bias + lerpF(-0.7f, 0.7f, Random.nextFloat())) * tuning.spread
                    flock.events.add(StampEvent(f.x, f.y, 2, 0.7f * f.depth * tuning.trail))
                    flock.ripple(f.x, f.y, 55f * f.depth, 0.95f * f.depth, 62f)
                    if (flock.passed(f)) flock.reenter(f)
                } else {
                    f.x = lerpF(f.fromX, f.toX, f.s)
                    f.y = lerpF(f.fromY, f.toY, f.s)
                }
            }
            drawFrog(f, pen)
        }
    }

    private fun leap(f: FlockCreature, dirIn: Float, boost: Float, onCourse: Boolean) {
        var dir = dirIn
        if (onCourse) {
            dir = flock.heading + clampF(angleDiff(flock.heading, dir), -0.35f, 0.35f)
        }
        val cosD = cos(dir)
        val sinD = sin(dir)
        val reach = f.span * lerpF(2.4f, 3.6f, Random.nextFloat()) * tuning["leap", 0.45f] * tuning.speed * boost
        f.leaping = true
        f.s = 0f
        f.dur = lerpF(0.42f, 0.55f, Random.nextFloat()) * sqrt(max(0.1f, tuning["leap", 0.45f])) * (if (boost > 1f) 0.8f else 1f)
        f.heading = dir
        f.fromX = f.x
        f.fromY = f.y
        f.toX = f.x + cosD * reach
        f.toY = f.y + sinD * reach
        f.vx = (f.toX - f.fromX) / f.dur
        f.vy = (f.toY - f.fromY) / f.dur
    }

    private fun drawFrog(f: FlockCreature, pen: Pen) {
        val s = if (f.leaping) f.s else 0f
        val h = 4f * s * (1f - s) * tuning["height", 1f]
        val bigSpan = f.span * (1f + 0.35f * h)
        pose.x = f.x
        pose.y = f.y
        pose.heading = f.heading
        pose.span = bigSpan
        pose.depth = f.depth
        pose.id = f.id

        val kick = if (f.leaping) smoothstepF(0f, 0.16f, s) * (1f - smoothstepF(0.72f, 1f, s)) else 0f
        blendLimb(HIND_REST, HIND_KICK, kick, hindLimb)

        if (f.leaping) {
            if (s < 0.6f) {
                blendLimb(FRONT_REST, FRONT_AIR, smoothstepF(0f, 0.2f, s), frontLimb)
            } else {
                blendLimb(FRONT_AIR, FRONT_REACH, smoothstepF(0.6f, 0.95f, s), frontLimb)
            }
        } else {
            blendLimb(FRONT_REST, FRONT_REST, 0f, frontLimb)
        }

        val breathe = if (f.leaping) 1f else 1f + 0.045f * sin(pen.t * 6f + f.id)
        val blinking = !f.leaping && fracF(pen.t * 0.21f + f.id * 0.37f) < 0.035f

        // Webbed toes
        for (side in intArrayOf(-1, 1)) {
            val drawToes = { from: FloatArray, to: FloatArray, count: Int, length: Float, fan: Float ->
                val dir = atan2(to[1] - from[1], to[0] - from[0])
                for (ti in 0 until count) {
                    val a = dir + (ti.toFloat() / (count - 1).toFloat() - 0.5f) * fan
                    pen.lineBody(pose, to[0], to[1] * side, to[0] + cos(a) * length, (to[1] + sin(a) * length) * side, FROG_WEB)
                }
            }
            drawToes(hindLimb[2], hindLimb[3], 4, 0.075f, 1.2f)
            drawToes(frontLimb[1], frontLimb[2], 3, 0.045f, 1.1f)
        }

        pen.shape(pose, 0.9f, { u, v, _, _ ->
            val av = abs(v)
            val hw = frogHalfWidth(u, breathe)
            if (av <= hw && u > -0.29f && u < 0.3f) {
                if (abs(av - 0.11f) < 0.012f && u > -0.22f && u < 0.14f) {
                    return@shape pen.ink.set(FROG_RIDGE[0], FROG_RIDGE[1], FROG_RIDGE[2], INK_LIT)
                }
                if (u < 0.1f) {
                    if (spotted(u, v, 0.085f, 0.024f, f.id)) return@shape pen.ink.set(FROG_SPOT[0], FROG_SPOT[1], FROG_SPOT[2], INK_DARK)
                    if (spotted(u, v, 0.085f, 0.036f, f.id)) return@shape pen.ink.set(FROG_HALO[0], FROG_HALO[1], FROG_HALO[2], INK_LIT)
                }
                val g = 0.9f + 0.2f * (1f - av / max(hw, 1e-3f))
                return@shape pen.ink.set(FROG_SKIN[0] * g, FROG_SKIN[1] * g, FROG_SKIN[2] * g, INK_LIT)
            }

            val fu = hindLimb[3][0]; val fv = hindLimb[3][1]
            val au = hindLimb[2][0]; val av2 = hindLimb[2][1]
            val dir = atan2(fv - av2, fu - au)
            val off = atan2(av - fv, u - fu) - dir
            if (hypot(u - fu, av - fv) < 0.06f && abs(atan2(sin(off), cos(off))) < 0.5f) {
                return@shape pen.ink.set(FROG_WEB[0], FROG_WEB[1], FROG_WEB[2], INK_GLASS, ".")
            }

            if (onLimb(hindLimb, HIND_RADII, u, av, frogSegHit) || onLimb(frontLimb, FRONT_RADII, u, av, frogSegHit)) {
                return@shape if (fracF(frogSegHit.t * 4.5f) < 0.3f) {
                    pen.ink.set(FROG_BAND[0], FROG_BAND[1], FROG_BAND[2], INK_DARK)
                } else {
                    pen.ink.set(FROG_LEG[0], FROG_LEG[1], FROG_LEG[2], INK_LIT)
                }
            }
            false
        }, light = 1f + 0.15f * h)

        // Eyes
        for (side in intArrayOf(-1, 1)) {
            pen.toWorld(pose, 0.18f, 0.1f * side, atWorld)
            if (blinking) {
                pen.spot(atWorld[0], atWorld[1], '-', FROG_LID, FROG_SKIN, f.depth)
            } else {
                pen.spot(atWorld[0], atWorld[1], '\u25CF', FROG_PUPIL, FROG_EYE_WHITE, f.depth)
            }
        }
    }
}

// =========================================================================
// 5. CRANES
// =========================================================================
private const val CRANE_ROOT = 0.045f
private const val CRANE_WRIST = 0.25f
private const val CRANE_TIP = 0.5f
private val CRANE_WHITE = floatArrayOf(236f, 238f, 242f)
private val CRANE_BLACK = floatArrayOf(32f, 32f, 38f)
private val CRANE_NECK = floatArrayOf(78f, 78f, 92f)
private val CRANE_CROWN = floatArrayOf(226f, 36f, 36f)
private val CRANE_EYE = floatArrayOf(14f, 12f, 16f)
private val CRANE_GLINT = floatArrayOf(255f, 252f, 246f)
private val CRANE_BILL = floatArrayOf(196f, 196f, 150f)
private val CRANE_LEGS = floatArrayOf(60f, 60f, 66f)

private class CraneWingResult {
    var s: Float = 0f
    var q: Float = 0f
}

private val globalWing = CraneWingResult()

private fun onCraneWing(u: Float, vw: Float, out: CraneWingResult = globalWing): Boolean {
    if (vw < CRANE_ROOT || vw > CRANE_TIP) return false
    val s = (vw - CRANE_ROOT) / (CRANE_TIP - CRANE_ROOT)
    val le = 0.12f + 0.03f * s - 0.09f * s * s
    var te0 = (-0.075f + 0.03f * s) * 1.2f
    if (s < 0.78f) te0 += 0.012f * (0.5f + 0.5f * cos(s * TAU * 9f))
    val te = if (s > 0.72f) lerpF(te0, le - 0.015f, max(0f, (s - 0.72f) / 0.28f).pow(1.4f)) else te0
    if (u > le || u < te) return false
    val q = (u - te) / (le - te)
    if (s > 0.78f && q < 0.92f && fracF(q * 6f) < 0.24f) return false
    out.s = s
    out.q = q
    return true
}

private class CraneStroke {
    var arm: Float = 1f
    var hand: Float = 1f
    var sweep: Float = 0f
    var lift: Float = 0f
}

private val craneStroke = CraneStroke()

private fun evenStroke(c: FlockCreature) {
    val beat = max(0f, 0.5f + 0.5f * cos(c.phase)).pow(0.8f)
    val open = max(0.12f, 0.25f + 0.75f * (beat * (1f - c.glide) + c.glide))
    craneStroke.arm = open
    craneStroke.hand = open
    craneStroke.sweep = 0f
    craneStroke.lift = 0f
}

private fun foldingStroke(c: FlockCreature) {
    val p = fracF(c.phase / TAU)
    val ease = { x: Float -> 0.5f - 0.5f * cos(PI.toFloat() * x) }
    var elev: Float
    var sweep: Float
    var handRaise: Float
    if (p < 0.6f) {
        val x = p / 0.6f
        elev = lerpF(0.85f, -0.6f, ease(x))
        sweep = -0.12f * sin(PI.toFloat() * x)
        handRaise = 0f
        craneStroke.lift = sin(PI.toFloat() * x)
    } else {
        val x = (p - 0.6f) / 0.4f
        elev = lerpF(-0.6f, 0.85f, ease(x))
        sweep = 1.05f * sin(PI.toFloat() * x)
        handRaise = 0.45f * sin(PI.toFloat() * x)
        craneStroke.lift = 0f
    }
    val g = c.glide
    elev = lerpF(elev, 0.1f, g)
    craneStroke.sweep = lerpF(sweep, 0f, g)
    craneStroke.arm = cos(elev)
    craneStroke.hand = max(0.2f, cos(elev + (if (elev >= 0f) 1f else -1f) * handRaise * (1f - g)))
    craneStroke.lift *= 1f - g
}

private class UnfoldPoint {
    var u: Float = 0f
    var v: Float = 0f
}

private val globalUnfold = UnfoldPoint()

private fun unfold(u: Float, av: Float, out: UnfoldPoint = globalUnfold): UnfoldPoint {
    val wristV = CRANE_ROOT + (CRANE_WRIST - CRANE_ROOT) * craneStroke.arm
    val pu = 0.03f
    val du = u - pu
    val dv = av - wristV
    val cs = cos(craneStroke.sweep)
    val sn = sin(craneStroke.sweep)
    val dv0 = -du * sn + dv * cs
    if (av <= wristV || dv0 < 0f) {
        out.u = u
        out.v = CRANE_ROOT + (av - CRANE_ROOT) / craneStroke.arm
    } else {
        out.u = pu + du * cs + dv * sn
        out.v = CRANE_WRIST + dv0 / craneStroke.hand
    }
    return out
}

class CranesSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "cranes"
    private val flock = Flock(
        tuning = tuning,
        area = 55000f,
        scale = 1.45f,
        speed = 85f
    )
    private val pose = Pose()
    private val atWorld = FloatArray(2)

    override fun resize(width: Float, height: Float) {
        flock.resize(width, height)
    }

    override fun onPointerDown(x: Float, y: Float) {
        flock.startle(x, y)
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val wingbeat = tuning["wingbeat", 1f]
        val glide = tuning["glide", 1f]
        val pen = renderer.pen

        flock.step(dt) { c ->
            c.phase = Random.nextFloat() * TAU
            c.pace = 0.8f + 0.4f * hash3(c.id, 5, 9)
            c.glide = 0f
            c.glideUntil = 0f
        }

        for (i in 0 until flock.creatures.size) {
            val c = flock.creatures[i]
            val cruise = flock.cruise(c)
            val course = flock.heading + c.bias * tuning.spread
            flock.beginSteer()
            flock.steer(c, course, cruise, tuning.grip)
            flock.separate(c, tuning.separation, 0.45f)
            flock.move(c, dt, cruise * 1.6f)
            if (flock.passed(c)) flock.reenter(c)
            flock.face(c, dt, 2f, course)

            if (c.glide < 0.5f && time > c.glideUntil && Random.nextFloat() < 0.25f * glide * dt) {
                c.glideUntil = time + lerpF(1f, 2.5f, Random.nextFloat())
            }
            c.glide += ((if (time < c.glideUntil) 1f else 0f) - c.glide) * min(1f, dt * 3f)
            c.phase += dt * TAU * 1.25f * wingbeat * c.pace * (1f - 0.92f * c.glide)

            drawCrane(c, pen)
        }
    }

    private fun drawCrane(c: FlockCreature, pen: Pen) {
        if (tuning.bool("foldFlap", false)) foldingStroke(c) else evenStroke(c)
        val s = c.span * (1f + 0.025f * craneStroke.lift)
        pose.x = c.x
        pose.y = c.y
        pose.heading = c.heading
        pose.span = s
        pose.depth = c.depth
        pose.id = c.id

        val neckW = max(0.018f, pen.cw * 0.45f / s)
        val headR = max(0.032f, pen.cw * 0.6f / s)

        for (side in intArrayOf(-1, 1)) {
            pen.lineBody(pose, -0.2f, 0.014f * side, -0.62f, 0.022f * side, CRANE_LEGS)
        }
        pen.lineBody(pose, 0.47f, 0f, 0.6f, 0f, CRANE_BILL)

        pen.shape(pose, 0.66f, { u, v, _, _ ->
            val av = abs(v)
            if (hypot(u - 0.44f, av) < headR) return@shape pen.ink.set(CRANE_BLACK[0], CRANE_BLACK[1], CRANE_BLACK[2], INK_DARK)
            if (u in 0.12f..0.43f && av < neckW) {
                return@shape if (u > 0.28f) {
                    pen.ink.set(CRANE_NECK[0], CRANE_NECK[1], CRANE_NECK[2], INK_DARK)
                } else {
                    pen.ink.set(CRANE_WHITE[0], CRANE_WHITE[1], CRANE_WHITE[2])
                }
            }
            if (inEllipse(u, av, -0.19f, 0f, 0.08f, 0.045f + 0.4f * max(0f, -0.12f - u).pow(1.2f))) {
                return@shape pen.ink.set(CRANE_WHITE[0], CRANE_WHITE[1], CRANE_WHITE[2])
            }
            if (inEllipse(u, av, 0f, 0f, 0.16f, 0.055f)) {
                return@shape pen.ink.set(CRANE_WHITE[0], CRANE_WHITE[1], CRANE_WHITE[2])
            }
            if (av <= CRANE_ROOT) return@shape false

            val uf = unfold(u, av, globalUnfold)
            if (!onCraneWing(uf.u, uf.v, globalWing)) return@shape false
            if (globalWing.q < 0.42f && globalWing.s < 0.7f) {
                return@shape pen.ink.set(CRANE_BLACK[0], CRANE_BLACK[1], CRANE_BLACK[2], INK_DARK)
            }
            val k = 0.9f + 0.1f * globalWing.q
            pen.ink.set(CRANE_WHITE[0] * k, CRANE_WHITE[1] * k, CRANE_WHITE[2] * k, INK_SCALE)
        }, light = 0.82f + 0.18f * craneStroke.arm)

        // Crown spot
        pen.toWorld(pose, 0.445f, 0f, atWorld)
        pen.spot(atWorld[0], atWorld[1], '@', floatArrayOf(255f, 214f, 200f), CRANE_CROWN, c.depth)

        if (0.064f * s >= pen.cw * 1.2f) {
            val eyeV = max(0.032f, pen.cw / s)
            for (side in intArrayOf(-1, 1)) {
                pen.toWorld(pose, 0.44f, eyeV * side, atWorld)
                pen.spot(atWorld[0], atWorld[1], '\u25CF', CRANE_GLINT, CRANE_EYE, c.depth)
            }
        }
    }
}

// =========================================================================
// 6. CRABS
// =========================================================================
private val CRAB_EYE = floatArrayOf(14f, 10f, 10f)
private val CRAB_GLINT = floatArrayOf(255f, 252f, 246f)
private val CRAB_SHELL = floatArrayOf(204f, 82f, 52f)
private val CRAB_PALE = floatArrayOf(232f, 132f, 92f)
private val CRAB_FLECK = floatArrayOf(140f, 46f, 32f)
private val CRAB_CLAW = floatArrayOf(214f, 92f, 56f)
private val CRAB_TIP = floatArrayOf(60f, 30f, 30f)
private val CRAB_LEG = floatArrayOf(186f, 72f, 44f)
private val CRAB_HIPS = floatArrayOf(0.07f, -0.01f, -0.08f, -0.14f)

class CrabsSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "crabs"
    private val flock = Flock(
        tuning = tuning,
        area = 70000f,
        scale = 0.8f,
        speed = 95f
    )
    private val pose = Pose()
    private val atWorld = FloatArray(2)

    override fun resize(width: Float, height: Float) {
        flock.resize(width, height)
    }

    override fun onPointerDown(x: Float, y: Float) {
        val scare = tuning["scare", 0.55f]
        if (scare <= 0f) return
        flock.startle(x, y) { c, dx, dy, str ->
            c.fleeDir = atan2(dy, dx) + lerpF(-0.35f, 0.35f, Random.nextFloat())
            c.fleeSpeed = flock.cruise(c) * (2.5f + 3f * str) * scare
            c.fleeFrom = flock.time
            c.startledUntil = flock.time + (1f + 1.5f * str) * sqrt(scare)
            c.vx = cos(c.fleeDir) * c.fleeSpeed
            c.vy = sin(c.fleeDir) * c.fleeSpeed
            c.running = true
            c.until = c.startledUntil + lerpF(0.3f, 1f, Random.nextFloat())
            c.alarm = 1f
        }
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val burst = tuning["burst", 1f]
        val rest = tuning["rest", 1f]
        val legSpeed = tuning["legSpeed", 1f]
        val pen = renderer.pen

        flock.step(dt) { c ->
            c.running = true
            c.until = flock.time + lerpF(0.3f, 1.2f, Random.nextFloat())
            c.heading = flock.course - c.facing * PI.toFloat() / 2f
            c.facing = if (Random.nextFloat() < 0.5f) 1f else -1f
            c.gait = Random.nextFloat() * TAU
        }

        for (i in 0 until flock.creatures.size) {
            val c = flock.creatures[i]
            if (time >= c.until) {
                c.running = !c.running || rest <= 0f
                c.until = time + (if (c.running) lerpF(0.5f, 1.6f, Random.nextFloat()) * burst else lerpF(0.3f, 1.4f, Random.nextFloat()) * rest)
            }

            val cruise = flock.cruise(c)
            val course = flock.heading + c.bias * tuning.spread
            val fleeing = time < c.startledUntil
            val calm = if (fleeing) (time - c.fleeFrom) / max(1e-4f, c.startledUntil - c.fleeFrom) else 1f
            val walk = if (fleeing) c.fleeDir else course

            flock.beginSteer()
            if (fleeing) {
                flock.steer(c, c.fleeDir, max(cruise, c.fleeSpeed * (1f - calm * calm)), 8f)
            } else {
                flock.steer(c, course, if (c.running) cruise else 0f, tuning.grip * 2f)
            }
            flock.separate(c)
            val spd = flock.move(c, dt, if (fleeing) max(cruise * 2f, c.fleeSpeed) else cruise * 2f)
            if (flock.passed(c)) flock.reenter(c)

            val side = if (fleeing && abs(angleDiff(c.heading + c.facing * PI.toFloat() / 2f, walk)) > PI.toFloat() / 2f) -c.facing else c.facing
            val want = walk - side * PI.toFloat() / 2f
            val turn = (if (fleeing) 10f else 3f) * dt
            c.heading += clampF(angleDiff(c.heading, want), -turn, turn)
            c.gait += dt * spd * legSpeed / max(4f, c.span * 0.09f)
            c.alarm = max(0f, c.alarm - dt * 0.7f)

            drawCrab(c, pen)
        }
    }

    private fun drawCrab(c: FlockCreature, pen: Pen) {
        val s = c.span
        pose.x = c.x
        pose.y = c.y
        pose.heading = c.heading
        pose.span = s
        pose.depth = c.depth
        pose.id = c.id

        // 4 walking legs on each side
        for (side in intArrayOf(-1, 1)) {
            for (idx in CRAB_HIPS.indices) {
                val hu = CRAB_HIPS[idx]
                val ph = c.gait + idx * PI.toFloat() * 0.5f + (if (side > 0) PI.toFloat() else 0f)
                val lift = max(0f, sin(ph))
                val reach = 0.04f * cos(ph)
                val splay = (idx - 1.5f) * 0.06f
                val kneeU = hu + splay * 0.6f
                val kneeV = (0.36f - 0.03f * lift) * side
                val tipU = hu + splay * 1.4f + reach
                val tipV = (0.52f + 0.03f * cos(ph) - 0.06f * lift) * side
                pen.lineBody(pose, hu, 0.22f * side, kneeU, kneeV, CRAB_LEG)
                pen.lineBody(pose, kneeU, kneeV, tipU, tipV, CRAB_LEG)
                if (lift < 0.05f && cos(ph) > 0.9f) {
                    pen.toWorld(pose, tipU, tipV, atWorld)
                    pen.shed(atWorld[0], atWorld[1], 0.12f * c.depth)
                }
            }
        }

        val raise = c.alarm
        pen.shape(pose, 0.62f, { u, v, _, _ ->
            val av = abs(v)
            val su = abs(u / 0.17f)
            val sv = av / 0.27f
            val teeth = if (u > 0.06f) 0.06f * (0.5f + 0.5f * cos(sv * PI.toFloat() * 7f)) else 0f
            if (max(0f, su).pow(2.2f) + max(0f, sv).pow(2.4f) <= 1f - teeth) {
                if (spotted(u, v, 0.05f, 0.012f, c.id)) return@shape pen.ink.set(CRAB_FLECK[0], CRAB_FLECK[1], CRAB_FLECK[2], INK_DARK)
                if (abs(av - 0.08f) < 0.01f && u > -0.08f && u < 0.05f) return@shape pen.ink.set(CRAB_FLECK[0], CRAB_FLECK[1], CRAB_FLECK[2], INK_DARK, "|")
                return@shape if (inEllipse(u, av, 0.01f, 0f, 0.1f, 0.13f)) {
                    pen.ink.set(CRAB_PALE[0], CRAB_PALE[1], CRAB_PALE[2])
                } else {
                    pen.ink.set(CRAB_SHELL[0], CRAB_SHELL[1], CRAB_SHELL[2])
                }
            }

            val armU = 0.26f + 0.07f * raise
            val armV = 0.25f + 0.05f * raise
            if (toSegment(u, av, 0.12f, 0.17f, armU, armV) < 0.034f) {
                return@shape pen.ink.set(CRAB_CLAW[0], CRAB_CLAW[1], CRAB_CLAW[2], INK_SCALE)
            }

            val cu = 0.34f + 0.08f * raise
            val cv = 0.19f + 0.04f * raise
            val sz = 1f + 0.15f * raise
            val ang = 0.55f
            val lx = ((u - cu) * cos(ang) - (av - cv) * sin(ang)) / sz
            val ly = ((u - cu) * sin(ang) + (av - cv) * cos(ang)) / sz
            if ((lx / 0.09f) * (lx / 0.09f) + (ly / 0.055f) * (ly / 0.055f) <= 1f) {
                val gap = 0.15f + 0.55f * raise
                if (lx > 0.02f && abs(ly) < gap * (lx - 0.02f)) return@shape false
                return@shape if (lx > 0.06f) pen.ink.set(CRAB_TIP[0], CRAB_TIP[1], CRAB_TIP[2], INK_DARK) else pen.ink.set(CRAB_CLAW[0], CRAB_CLAW[1], CRAB_CLAW[2], INK_SCALE)
            }
            false
        })

        // Eyes
        val eyeV = clampF(pen.cw * 0.55f / s, 0.06f, 0.14f)
        for (side in intArrayOf(-1, 1)) {
            pen.toWorld(pose, 0.2f, eyeV * side, atWorld)
            pen.spot(atWorld[0], atWorld[1], '\u25CF', CRAB_GLINT, CRAB_EYE, c.depth)
        }
    }
}

// =========================================================================
// 7. WHALE & SHOAL
// =========================================================================
private const val WHALE_NODES = 14
private val WHALE_BACK = floatArrayOf(64f, 98f, 144f)
private val WHALE_BELLY = floatArrayOf(184f, 212f, 236f)
private val WHALE_FLIPPER = floatArrayOf(212f, 234f, 248f)
private val WHALE_DARK = floatArrayOf(32f, 52f, 84f)
private val FISH_SILVER = floatArrayOf(154f, 214f, 248f)
private val FISH_BACK = floatArrayOf(72f, 134f, 188f)

private class GiantWhale(
    val id: Int,
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    var heading: Float,
    var span: Float,
    var scaleMul: Float,
    var phase: Float,
    val nodes: FloatArray = FloatArray(WHALE_NODES * 2)
)

class WhaleSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "whale"
    private val flock = Flock(
        tuning = tuning,
        area = 42000f,
        scale = 0.52f,
        speed = 86f,
        water = true,
        windWeight = 0.25f
    )
    private val pod = ArrayList<GiantWhale>(4)
    private val pose = Pose()

    override fun resize(width: Float, height: Float) {
        flock.resize(width, height)
    }

    override fun onPointerDown(x: Float, y: Float) {
        flock.startle(x, y)
    }

    private fun spawnWhale(id: Int, scatter: Boolean): GiantWhale {
        val course = flock.heading
        val scaleMul = if (id == 0) 1f else 0.58f + 0.34f * hash3(id, 7, 13)
        val span = min(flock.width, flock.height) * 0.62f * tuning["whaleSize", 1.25f] * scaleMul
        val cosC = cos(course)
        val sinC = sin(course)
        var wx: Float
        var wy: Float
        if (scatter) {
            wx = if (id == 0) flock.width * 0.42f else flock.width * (0.2f + 0.6f * hash3(id, 3, 5))
            wy = if (id == 0) flock.height * 0.54f else flock.height * (0.2f + 0.6f * hash3(id, 11, 9))
        } else {
            wx = if (cosC >= 0f) -span * 0.35f else flock.width + span * 0.35f
            wy = if (sinC <= 0f) flock.height + span * 0.35f else -span * 0.35f
            val perp = course + PI.toFloat() / 2f
            val off = (hash3(id, floor(flock.time).toInt(), 2) - 0.5f) * min(flock.width, flock.height) * 0.55f
            wx += cos(perp) * off
            wy += sin(perp) * off
        }
        val nodes = FloatArray(WHALE_NODES * 2)
        val step = span / (WHALE_NODES - 1)
        for (i in 0 until WHALE_NODES) {
            nodes[i * 2] = wx - cosC * step * i
            nodes[i * 2 + 1] = wy - sinC * step * i
        }
        return GiantWhale(id, wx, wy, cosC * 46f, sinC * 46f, course, span, scaleMul, id * 2.1f, nodes)
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val whaleSize = tuning["whaleSize", 1.25f]
        val partDist = tuning["partDistance", 1.25f]
        val whaleSpeed = tuning["whaleSpeed", 0.9f]
        val pen = renderer.pen

        // Manage whales
        val wantWhales = clampF((0.35f + tuning.density * 0.85f).roundToInt().toFloat(), 1f, 3f).toInt()
        while (pod.size > wantWhales) pod.removeAt(pod.lastIndex)
        while (pod.size < wantWhales) pod.add(spawnWhale(pod.size, scatter = true))

        val course = flock.heading
        for (w in pod) {
            w.span = min(flock.width, flock.height) * 0.62f * whaleSize * w.scaleMul
            val spd = 46f * whaleSpeed * tuning.speed * (0.88f + 0.12f * w.scaleMul)
            val want = course + 0.14f * sin(time * 0.22f + w.id * 1.9f)
            w.heading += clampF(angleDiff(w.heading, want), -0.45f * dt, 0.45f * dt)
            w.vx = cos(w.heading) * spd
            w.vy = sin(w.heading) * spd
            w.x += w.vx * dt
            w.y += w.vy * dt
            w.phase += dt * 1.65f * whaleSpeed

            val step = w.span / (WHALE_NODES - 1)
            w.nodes[0] = w.x
            w.nodes[1] = w.y
            var prev = w.heading + PI.toFloat()
            for (i in 1 until WHALE_NODES) {
                val f = i.toFloat() / (WHALE_NODES - 1).toFloat()
                val sway = 0.08f * f * f * sin(w.phase - f * 3.2f)
                var a = atan2(w.nodes[i * 2 + 1] - w.nodes[i * 2 - 1], w.nodes[i * 2] - w.nodes[i * 2 - 2])
                a = prev + clampF(angleDiff(prev, a + sway), -0.18f, 0.18f)
                w.nodes[i * 2] = w.nodes[i * 2 - 2] + cos(a) * step
                w.nodes[i * 2 + 1] = w.nodes[i * 2 - 1] + sin(a) * step
                prev = a
            }
            if (Random.nextFloat() < dt * 0.65f) {
                flock.ripple(w.nodes[4], w.nodes[5], w.span * 0.24f, 0.95f, 42f)
            }

            val margin = w.span * 1.15f
            val cosC = cos(course); val sinC = sin(course)
            if ((cosC > 0.05f && w.x > flock.width + margin) ||
                (cosC < -0.05f && w.x < -margin) ||
                (sinC < -0.05f && w.y < -margin) ||
                (sinC > 0.05f && w.y > flock.height + margin)
            ) {
                val fresh = spawnWhale(w.id, scatter = false)
                w.x = fresh.x
                w.y = fresh.y
                w.heading = fresh.heading
                fresh.nodes.copyInto(w.nodes)
            }
        }

        flock.step(dt) { f ->
            f.phase = Random.nextFloat() * TAU
        }

        // Baitfish shoal steering
        for (i in 0 until flock.creatures.size) {
            val f = flock.creatures[i]
            val cruise = flock.cruise(f)
            flock.beginSteer()
            val shoalDir = flock.heading + f.bias * tuning.spread + 0.25f * sin(time * 1.2f + f.id * 0.2f)
            flock.steer(f, shoalDir, cruise, tuning.grip)

            // Parting around giant whales
            for (w in pod) {
                val partR = w.span * 0.28f * partDist
                for (node in 0 until WHALE_NODES step 2) {
                    val wx = w.nodes[node * 2]
                    val wy = w.nodes[node * 2 + 1]
                    val dx = f.x - wx
                    val dy = f.y - wy
                    val d = hypot(dx, dy).coerceAtLeast(1f)
                    val segR = partR * (if (node == 2) 1.35f else 1f - (node.toFloat() / WHALE_NODES.toFloat()) * 0.35f)
                    if (d < segR) {
                        val push = max(0f, 1f - d / segR).pow(1.3f) * 1150f
                        flock.accX += (dx / d) * push
                        flock.accY += (dy / d) * push
                        f.startledUntil = max(f.startledUntil, time + 0.25f)
                    }
                }
            }

            flock.separate(f, tuning.separation, 0.45f)
            val spd = flock.move(f, dt, cruise * 2.5f)
            if (flock.passed(f)) flock.reenter(f)
            if (spd > 8f) flock.face(f, dt, 9f)
            f.phase += dt * (8f + spd * 0.08f)
        }

        // Draw deep fish
        for (f in flock.creatures) {
            if (f.depth < 0.72f) drawBaitFish(f, pen)
        }
        // Draw giant whales
        for (w in pod) {
            drawGiantWhale(w, pen)
        }
        // Draw shallow fish
        for (f in flock.creatures) {
            if (f.depth >= 0.72f) drawBaitFish(f, pen)
        }
    }

    private fun drawBaitFish(f: FlockCreature, pen: Pen) {
        val s = f.span
        val swish = 0.08f * sin(f.phase)
        pose.x = f.x
        pose.y = f.y
        pose.heading = f.heading + swish
        pose.span = s
        pose.depth = f.depth
        pose.id = f.id

        pen.shape(pose, 0.55f, { u, v, _, _ ->
            val wave = 0.03f * sin(f.phase - u * 8f)
            val av = abs(v - wave)
            if (u in -0.35f..0.32f && av < 0.085f * (1f - ((u - 0.04f) / 0.35f) * ((u - 0.04f) / 0.35f))) {
                return@shape if (av < 0.04f) pen.ink.set(FISH_BACK[0], FISH_BACK[1], FISH_BACK[2], INK_SCALE) else pen.ink.set(FISH_SILVER[0], FISH_SILVER[1], FISH_SILVER[2], INK_SHIMMER)
            }
            if (u in -0.48f..-0.32f && av < 0.03f + (-0.32f - u) * 0.45f) {
                return@shape pen.ink.set(FISH_SILVER[0], FISH_SILVER[1], FISH_SILVER[2], INK_LIT)
            }
            false
        }, light = 0.9f + 0.2f * swish)
    }

    private fun drawGiantWhale(w: GiantWhale, pen: Pen) {
        val s = w.span
        val n = w.nodes
        val cx = n[12]
        val cy = n[13]
        pose.x = cx
        pose.y = cy
        pose.heading = w.heading
        pose.span = s
        pose.depth = 0.88f
        pose.id = 7001 + w.id

        val shX = n[6]; val shY = n[7]
        val shAngle = atan2(n[5] - n[9], n[4] - n[8])
        val finSweep = 0.58f + 0.14f * sin(w.phase * 0.7f)
        val finLen = s * 0.34f
        val tx = n[(WHALE_NODES - 1) * 2]
        val ty = n[(WHALE_NODES - 1) * 2 + 1]
        val tAngle = atan2(n[(WHALE_NODES - 2) * 2 + 1] - ty, n[(WHALE_NODES - 2) * 2] - tx)
        val flukeSpan = s * 0.27f

        pen.shape(pose, 0.85f, { _, _, px, py ->
            var bestD = Float.POSITIVE_INFINITY
            var bodyS = 0f
            var signedSide = 1f
            for (i in 0 until WHALE_NODES - 1) {
                val ax = n[i * 2]; val ay = n[i * 2 + 1]
                val bx = n[(i + 1) * 2]; val by = n[(i + 1) * 2 + 1]
                val d = toSegment(px, py, ax, ay, bx, by, globalSeg)
                if (d < bestD) {
                    bestD = d
                    bodyS = (i + globalSeg.t) / (WHALE_NODES - 1)
                    signedSide = if ((px - ax) * (by - ay) - (py - ay) * (bx - ax) >= 0f) 1f else -1f
                }
            }

            val halfW = s * (if (bodyS < 0.25f) 0.145f * max(0f, sin(max(0.02f, bodyS) / 0.25f * (PI.toFloat() / 2f))).pow(0.65f) else 0.145f * max(0.08f, 1f - (bodyS - 0.25f) / 0.75f).pow(1.35f))
            if (bestD <= halfW) {
                pen.ink.au = bodyS - 0.5f
                pen.ink.av = (bestD / max(1f, halfW)) * signedSide * 0.2f
                val edge = bestD / max(1f, halfW)
                if (abs(bodyS - 0.16f) < 0.022f && edge < 0.22f) {
                    return@shape pen.ink.set(WHALE_DARK[0], WHALE_DARK[1], WHALE_DARK[2], INK_DARK, "oo")
                }
                if (edge > 0.78f && bodyS < 0.55f) {
                    val pleat = abs(sin(edge * 28f)) < 0.35f
                    return@shape if (pleat) pen.ink.set(WHALE_DARK[0], WHALE_DARK[1], WHALE_DARK[2], INK_DARK, "=-") else pen.ink.set(WHALE_BELLY[0], WHALE_BELLY[1], WHALE_BELLY[2], INK_LIT)
                }
                if (edge < 0.18f && bodyS in 0.58f..0.72f) {
                    return@shape pen.ink.set(WHALE_DARK[0], WHALE_DARK[1], WHALE_DARK[2], INK_DARK)
                }
                return@shape pen.ink.set(WHALE_BACK[0], WHALE_BACK[1], WHALE_BACK[2], INK_SHIMMER)
            }

            // Pectoral flippers
            for (side in intArrayOf(-1, 1)) {
                val fa = shAngle + PI.toFloat() + side * (PI.toFloat() * 0.5f - finSweep)
                val fx = shX + cos(fa) * finLen
                val fy = shY + sin(fa) * finLen
                val fd = toSegment(px, py, shX, shY, fx, fy, globalSeg)
                val ft = globalSeg.t
                val tubercle = 1f + 0.18f * sin(ft * PI.toFloat() * 7f)
                val fw = s * 0.048f * sin(PI.toFloat() * clampF(ft, 0.05f, 0.98f)) * tubercle
                if (fd <= fw) {
                    return@shape if (ft > 0.35f || fd > fw * 0.55f) pen.ink.set(WHALE_FLIPPER[0], WHALE_FLIPPER[1], WHALE_FLIPPER[2], INK_LIT) else pen.ink.set(WHALE_BACK[0], WHALE_BACK[1], WHALE_BACK[2], INK_SHIMMER)
                }
            }

            // Flukes
            for (side in intArrayOf(-1, 1)) {
                val la = tAngle + PI.toFloat() + side * 1.18f
                val lx = tx + cos(la) * flukeSpan
                val ly = ty + sin(la) * flukeSpan
                val ld = toSegment(px, py, tx, ty, lx, ly, globalSeg)
                val lt = globalSeg.t
                val lw = s * 0.052f * sin(PI.toFloat() * clampF(lt, 0.06f, 0.96f))
                if (ld <= lw) {
                    return@shape if (lt > 0.65f) pen.ink.set(WHALE_FLIPPER[0], WHALE_FLIPPER[1], WHALE_FLIPPER[2], INK_LIT) else pen.ink.set(WHALE_DARK[0], WHALE_DARK[1], WHALE_DARK[2], INK_SCALE)
                }
            }
            false
        }, light = 1.05f)

        pen.shed(tx, ty, 0.22f)
        if (Random.nextFloat() < 0.15f) flock.ripple(tx, ty, s * 0.18f, 0.65f, 38f)
    }
}
