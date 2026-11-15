package com.geekify.android.ui.ascii

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// =========================================================================
// 1. BUTTERFLIES (With Multi-Coloured Butterfly Flock Support)
// =========================================================================
private class ButterflyPalette(
    val wingR: Float, val wingG: Float, val wingB: Float,
    val darkR: Float, val darkG: Float, val darkB: Float,
    val spotR: Float, val spotG: Float, val spotB: Float,
    val bodyR: Float, val bodyG: Float, val bodyB: Float
)

private val BUTTERFLY_PALETTES = arrayOf(
    // 0: Morpho Blue
    ButterflyPalette(50f, 135f, 255f, 15f, 25f, 55f, 200f, 230f, 255f, 28f, 36f, 64f),
    // 1: Monarch Orange-Gold
    ButterflyPalette(255f, 132f, 32f, 38f, 18f, 10f, 255f, 238f, 195f, 44f, 26f, 16f),
    // 2: Emerald Swallowtail
    ButterflyPalette(42f, 228f, 128f, 12f, 42f, 28f, 195f, 255f, 220f, 20f, 48f, 34f),
    // 3: Magenta Rose
    ButterflyPalette(252f, 68f, 178f, 48f, 14f, 36f, 255f, 215f, 240f, 52f, 20f, 40f),
    // 4: Golden Birdwing
    ButterflyPalette(255f, 212f, 42f, 45f, 32f, 8f, 255f, 248f, 200f, 48f, 38f, 14f),
    // 5: Amethyst Purple Emperor
    ButterflyPalette(168f, 92f, 255f, 28f, 14f, 52f, 230f, 205f, 255f, 36f, 22f, 58f),
    // 6: Tropical Cyan Ulysses
    ButterflyPalette(36f, 222f, 242f, 10f, 38f, 48f, 205f, 252f, 255f, 18f, 44f, 54f),
    // 7: Sunset Crimson Apollo
    ButterflyPalette(255f, 86f, 72f, 48f, 16f, 16f, 255f, 225f, 210f, 52f, 22f, 22f)
)

class ButterfliesSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "butterflies"
    private var width = 1f
    private var height = 1f
    private val flock = AsciiFlock(
        width = 1f,
        height = 1f,
        tuning = tuning,
        baseCount = 12,
        pad = 130f,
        minSpeed = 38f,
        maxSpeed = 68f,
        initMember = { b ->
            b.phase = Random.nextFloat() * TWO_PI
            b.flapFreq = lerpF(1.7f, 2.7f, Random.nextFloat())
            b.glideTimer = Random.nextFloat() * 3f
            b.gliding = false
            b.glideDuration = lerpF(0.6f, 1.5f, Random.nextFloat())
            b.wingOpen = 1f
            b.turnBias = 0f
        }
    )

    override fun resize(width: Float, height: Float) {
        this.width = width
        this.height = height
        flock.resize(width, height)
    }

    override fun onPointerDown(x: Float, y: Float) {
        for (i in 0 until flock.members.size) {
            val b = flock.members[i]
            val dx = b.x - x
            val dy = b.y - y
            val d = hypot(dx, dy)
            if (d < 220f) {
                b.heading = atan2(dy, dx)
                b.gliding = false
            }
        }
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val flutterAmt = tuning["flutter", 0f]
        val glideSetting = tuning["glide", 2.75f]
        val flapRate = tuning["flapRate", 1f]
        val pen = renderer.pen

        flock.update(dt, time, 1f) { b, _, desired, _ ->
            b.glideTimer -= dt
            if (b.glideTimer <= 0f) {
                if (!b.gliding && glideSetting > 0.05f && Random.nextFloat() < 0.55f) {
                    b.gliding = true
                    b.glideTimer = b.glideDuration * (0.4f + glideSetting * 0.45f)
                } else {
                    b.gliding = false
                    b.glideTimer = lerpF(1.1f, 2.6f, Random.nextFloat()) / max(0.25f, 0.6f + glideSetting * 0.2f)
                }
            }

            if (b.gliding) {
                b.wingOpen += (0.92f - b.wingOpen) * min(1f, dt * 6f)
            } else {
                b.phase += dt * b.flapFreq * flapRate * TWO_PI
                val raw = sin(b.phase)
                val shaped = if (raw >= 0f) raw.pow(0.65f) else -((-raw).pow(1.25f))
                b.wingOpen = 0.22f + 0.78f * (shaped * 0.5f + 0.5f)
            }

            val flutter = if (flutterAmt > 0.01f) {
                noise1(time * 2.8f + b.seed) * 0.35f * flutterAmt
            } else 0f
            val lift = if (b.gliding) 0f else cos(b.phase) * 0.06f
            desired + flutter + lift
        }

        for (i in 0 until flock.members.size) {
            drawButterfly(flock.members[i], pen)
        }
    }

    private fun drawButterfly(b: FlockMember, pen: AsciiPen) {
        val scale = b.scale
        val span = 46f * scale
        val open = b.wingOpen
        val boundR = span * 1.25f

        val forwardAngle = b.heading + HALF_PI
        val cosA = cos(-forwardAngle)
        val sinA = sin(-forwardAngle)

        val pal = if (tuning.multiColorButterflies) {
            BUTTERFLY_PALETTES[(b.id and 0x7fffffff) % BUTTERFLY_PALETTES.size]
        } else {
            BUTTERFLY_PALETTES[0]
        }

        if (!b.gliding && b.wingOpen < 0.38f && Random.nextFloat() < 0.35f) {
            val wx = span * 0.65f * open
            val c = cos(forwardAngle)
            val s = sin(forwardAngle)
            pen.trail(b.x + (-wx * c), b.y + (-wx * s), 0.32f)
            pen.trail(b.x + (wx * c), b.y + (wx * s), 0.32f)
        }

        val fAngle = -0.42f
        val fCos = cos(fAngle)
        val fSin = sin(fAngle)
        val hAngle = 0.52f
        val hCos = cos(hAngle)
        val hSin = sin(hAngle)

        pen.shape(b.x, b.y, boundR, b.z, b.fog, b.id) { px, py, out ->
            val dx = px - b.x
            val dy = py - b.y
            val lx = dx * cosA - dy * sinA
            val ly = dx * sinA + dy * cosA

            val bodyHalfLen = span * 0.42f
            val bodyRad = max(2.2f, span * 0.075f)
            if (abs(lx) < bodyRad * 1.25f && abs(ly) < bodyHalfLen) {
                val taper = 1f - 0.4f * (abs(ly) / bodyHalfLen)
                if (abs(lx) <= bodyRad * taper) {
                    return@shape out.set(
                        INK_BODY,
                        pal.bodyR, pal.bodyG, pal.bodyB,
                        pal.bodyR * 0.5f, pal.bodyG * 0.5f, pal.bodyB * 0.55f, 0.92f
                    )
                }
            }

            val ax = abs(lx) / max(0.18f, open)
            val ay = ly
            if (ax > span * 1.05f || abs(ay) > span * 0.95f) return@shape false

            // Forewing
            val fx = ax - span * 0.42f
            val fy = ay - (-span * 0.16f)
            val frx = fx * fCos - fy * fSin
            val fry = fx * fSin + fy * fCos
            val foreR1 = span * 0.46f
            val foreR2 = span * 0.30f
            val foreDist = hypot(frx / foreR1, fry / foreR2)

            // Hindwing
            val hx = ax - span * 0.30f
            val hy = ay - (span * 0.24f)
            val hrx = hx * hCos - hy * hSin
            val hry = hx * hSin + hy * hCos
            val hindR1 = span * 0.34f
            val hindR2 = span * 0.25f
            val hindDist = hypot(hrx / hindR1, hry / hindR2)

            val d = min(foreDist, hindDist)
            if (d > 1f) return@shape false

            if (d > 0.80f) {
                val spotCheck = sin(atan2(ay, ax) * 9f + b.seed)
                if (d > 0.86f && spotCheck > 0.65f) {
                    return@shape out.set(
                        INK_SPOT,
                        pal.spotR, pal.spotG, pal.spotB,
                        pal.darkR, pal.darkG, pal.darkB, 0.90f
                    )
                }
                return@shape out.set(
                    INK_DARK,
                    pal.darkR + 20f, pal.darkG + 20f, pal.darkB + 25f,
                    pal.darkR, pal.darkG, pal.darkB, 0.92f
                )
            }

            val sheen = 1f - d * 0.35f + (1f - open) * 0.18f
            val r = pal.wingR * sheen
            val g = pal.wingG * sheen
            val bl = min(255f, pal.wingB * (0.88f + 0.2f * sheen))
            out.set(INK_SCALE, r, g, bl)
        }

        // Antennae
        val antLen = span * 0.34f
        val headDist = span * 0.42f
        val cF = cos(forwardAngle)
        val sF = sin(forwardAngle)
        val headX = b.x + headDist * sF
        val headY = b.y - headDist * cF
        for (side in -1..1 step 2) {
            val aAngle = b.heading + side * 0.42f
            val tipX = headX + cos(aAngle) * antLen
            val tipY = headY + sin(aAngle) * antLen
            pen.dot(
                (headX + tipX) * 0.5f,
                (headY + tipY) * 0.5f,
                if (side < 0) '\\' else '/',
                150f, 165f, 210f,
                b.z, b.fog
            )
            pen.dot(tipX, tipY, '.', 190f, 205f, 240f, b.z, b.fog)
        }
    }
}

// =========================================================================
// 2. KOI
// =========================================================================
private const val KOI_NODES = 14
private val KOI_WIDTHS = floatArrayOf(
    0.55f, 0.85f, 1.00f, 0.98f, 0.92f, 0.84f, 0.74f,
    0.63f, 0.52f, 0.41f, 0.31f, 0.22f, 0.16f, 0.38f
)

private class FoodPellet(var x: Float, var y: Float, var age: Float = 0f, var nibbleTimer: Float = 0f)

class KoiSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "koi"
    private var width = 1f
    private var height = 1f
    private val foods = ArrayList<FoodPellet>(12)
    private val segHit = SegmentHit()

    private val flock = AsciiFlock(
        width = 1f,
        height = 1f,
        tuning = tuning,
        baseCount = 9,
        pad = 170f,
        minSpeed = 28f,
        maxSpeed = 52f,
        initMember = { k ->
            k.varietyIndex = Random.nextInt(5)
            k.spotSeed = Random.nextFloat() * 100f
            k.phase = Random.nextFloat() * TWO_PI
            k.length = lerpF(74f, 96f, Random.nextFloat())
            k.spineX = FloatArray(KOI_NODES) { k.x }
            k.spineY = FloatArray(KOI_NODES) { k.y }
            k.finPhase = Random.nextFloat() * TWO_PI
        }
    )

    override fun resize(width: Float, height: Float) {
        this.width = width
        this.height = height
        flock.resize(width, height)
    }

    override fun onPointerDown(x: Float, y: Float) {
        if (foods.size > 8) foods.removeAt(0)
        foods.add(FoodPellet(x, y))
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val meander = tuning["meander", 0.4f]
        val tailBeat = tuning["tailBeat", 1f]
        val foodRange = tuning["foodRange", 200f]
        val pen = renderer.pen

        var fi = foods.size - 1
        while (fi >= 0) {
            val f = foods[fi]
            if (f.age == 0f) {
                pen.ripple(f.x, f.y, 85f, 0.9f, 240f, 200f, 110f)
            }
            f.age += dt
            f.nibbleTimer -= dt
            if (f.age > 9f) {
                foods.removeAt(fi)
                fi--
                continue
            }
            pen.disc(f.x, f.y, 7f, 245f, 205f, 110f, 0.95f, 0f, 'o')
            fi--
        }

        flock.update(dt, time, 1f) { k, _, desired, _ ->
            var nextDesired = desired + sin(time * 1.1f + k.seed) * 0.45f * meander

            if (foodRange > 5f && foods.isNotEmpty()) {
                var best: FoodPellet? = null
                var bestD = foodRange
                for (j in 0 until foods.size) {
                    val f = foods[j]
                    val d = hypot(f.x - k.x, f.y - k.y)
                    if (d < bestD) {
                        bestD = d
                        best = f
                    }
                }
                if (best != null) {
                    nextDesired = atan2(best.y - k.y, best.x - k.x)
                    if (bestD < 22f && best.nibbleTimer <= 0f) {
                        best.nibbleTimer = 0.45f
                        best.age += 2.2f
                        pen.ripple(best.x, best.y, 70f, 0.85f, 244f, 196f, 44f)
                    }
                }
            }
            nextDesired
        }

        for (i in 0 until flock.members.size) {
            val k = flock.members[i]
            updateSpine(k, dt, tailBeat)
            drawKoi(k, pen)
        }
    }

    private fun updateSpine(k: FlockMember, dt: Float, tailBeat: Float) {
        val segLen = (k.length * k.scale) / (KOI_NODES - 1)
        k.phase += dt * (2.6f + k.speed * 0.03f) * tailBeat * TWO_PI * 0.45f
        k.finPhase += dt * 4.2f

        val sx = k.spineX
        val sy = k.spineY
        if (hypot(sx[0] - k.x, sy[0] - k.y) > segLen * KOI_NODES * 1.8f) {
            val backX = -cos(k.heading)
            val backY = -sin(k.heading)
            for (i in 0 until KOI_NODES) {
                sx[i] = k.x + backX * segLen * i
                sy[i] = k.y + backY * segLen * i
            }
        } else {
            sx[0] = k.x
            sy[0] = k.y
        }

        for (i in 1 until KOI_NODES) {
            val prevX = sx[i - 1]
            val prevY = sy[i - 1]
            var dx = sx[i] - prevX
            var dy = sy[i] - prevY
            var ang = atan2(dy, dx)
            val t = i.toFloat() / (KOI_NODES - 1).toFloat()
            val wag = sin(k.phase - i * 0.48f) * (0.08f + t * t * 0.42f)
            ang += wag * 0.35f
            dx = cos(ang) * segLen
            dy = sin(ang) * segLen
            sx[i] = prevX + dx
            sy[i] = prevY + dy
        }

        val tailX = sx[KOI_NODES - 1]
        val tailY = sy[KOI_NODES - 1]
        k.MouthAndTailTrail(tailX, tailY)
    }

    private fun FlockMember.MouthAndTailTrail(@Suppress("UNUSED_PARAMETER") tx: Float, @Suppress("UNUSED_PARAMETER") ty: Float) {
        // Handled in drawKoi
    }

    private fun drawKoi(k: FlockMember, pen: AsciiPen) {
        val scale = k.scale
        val maxHalfW = 14.5f * scale
        val sx = k.spineX
        val sy = k.spineY

        val tailX = sx[KOI_NODES - 1]
        val tailY = sy[KOI_NODES - 1]
        if (Random.nextFloat() < 0.45f) {
            pen.trail(tailX, tailY, 0.28f)
        }
        if (Random.nextFloat() < 0.008f) {
            pen.ripple(k.x, k.y, 85f * scale, 0.65f, 220f, 200f, 160f)
        }

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until KOI_NODES) {
            if (sx[i] < minX) minX = sx[i]
            if (sx[i] > maxX) maxX = sx[i]
            if (sy[i] < minY) minY = sy[i]
            if (sy[i] > maxY) maxY = sy[i]
        }
        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        val boundR = hypot(maxX - minX, maxY - minY) * 0.5f + maxHalfW * 2.4f

        val p3x = sx[3]; val p3y = sy[3]
        val p4x = sx[4]; val p4y = sy[4]
        val pecAng = atan2(p3y - p4y, p3x - p4x)
        val pecSweep = 0.95f + sin(k.finPhase) * 0.18f
        val pecLen = maxHalfW * 1.55f

        val p7x = sx[7]; val p7y = sy[7]
        val p8x = sx[8]; val p8y = sy[8]
        val pelAng = atan2(p7y - p8y, p7x - p8x)
        val pelLen = maxHalfW * 1.0f

        val t1x = sx[KOI_NODES - 2]; val t1y = sy[KOI_NODES - 2]
        val t2x = sx[KOI_NODES - 1]; val t2y = sy[KOI_NODES - 1]
        val tailAng = atan2(t2y - t1y, t2x - t1x)
        val tailLen = maxHalfW * 1.45f

        pen.shape(cx, cy, boundR, k.z, k.fog, k.id) { px, py, out ->
            // 1. Pectoral fins
            for (side in -1..1 step 2) {
                val fa = pecAng + side * (HALF_PI + pecSweep * 0.45f)
                val bx = p3x + cos(fa) * pecLen
                val by = p3y + sin(fa) * pecLen
                val d = sdSegment(px, py, p3x, p3y, bx, by, maxHalfW * 0.42f)
                if (d <= 0f) {
                    return@shape out.set(INK_GLASS, 240f, 225f, 200f, 240f, 225f, 200f, 0.35f)
                }
            }
            // 2. Pelvic fins
            for (side in -1..1 step 2) {
                val fa = pelAng + side * (HALF_PI + 0.4f)
                val bx = p7x + cos(fa) * pelLen
                val by = p7y + sin(fa) * pelLen
                val d = sdSegment(px, py, p7x, p7y, bx, by, maxHalfW * 0.28f)
                if (d <= 0f) {
                    return@shape out.set(INK_GLASS, 235f, 215f, 190f, 235f, 215f, 190f, 0.3f)
                }
            }
            // 3. Forked caudal tail fin
            for (side in -1..1 step 2) {
                val fa = tailAng + side * 0.42f
                val bx = t2x + cos(fa) * tailLen
                val by = t2y + sin(fa) * tailLen
                val d = sdSegment(px, py, t1x, t1y, bx, by, maxHalfW * 0.36f)
                if (d <= 0f) {
                    return@shape out.set(INK_GLASS, 245f, 210f, 175f, 245f, 210f, 175f, 0.42f)
                }
            }

            // 4. Articulated spine body
            var bestNorm = 99f
            var bestU = 0f
            for (i in 0 until KOI_NODES - 1) {
                sdSegment2(px, py, sx[i], sy[i], sx[i + 1], sy[i + 1], segHit)
                val u = (i + segHit.t) / (KOI_NODES - 1)
                val w = lerpF(KOI_WIDTHS[i], KOI_WIDTHS[i + 1], segHit.t) * maxHalfW
                val norm = segHit.d / max(1f, w)
                if (norm < bestNorm) {
                    bestNorm = norm
                    bestU = u
                }
            }
            if (bestNorm > 1f) return@shape false

            // Eyes near head
            if (bestU > 0.04f && bestU < 0.11f && bestNorm > 0.58f && bestNorm < 0.92f) {
                return@shape out.set(INK_DARK, 30f, 30f, 40f, 15f, 15f, 22f, 0.95f, '@')
            }

            // Dorsal ridge
            if (bestU > 0.22f && bestU < 0.62f && bestNorm < 0.22f) {
                return@shape out.set(INK_SHIMMER, 255f, 240f, 200f)
            }

            val patch = sin(bestU * 13f + k.spotSeed) + cos(bestU * 7f - k.spotSeed * 0.7f) * 0.6f
            val shade = 1f - bestNorm * 0.28f
            when (k.varietyIndex) {
                0 -> { // kohaku
                    if (patch > 0.25f) {
                        out.set(INK_SCALE, 238f * shade, 74f * shade, 38f * shade)
                    } else {
                        out.set(INK_LIT, 242f * shade, 238f * shade, 228f * shade)
                    }
                }
                1 -> { // sanke
                    when {
                        patch > 0.85f -> out.set(INK_DARK, 38f, 38f, 48f, 18f, 18f, 26f, 0.92f)
                        patch > 0.1f -> out.set(INK_SCALE, 236f * shade, 78f * shade, 40f * shade)
                        else -> out.set(INK_LIT, 240f * shade, 236f * shade, 226f * shade)
                    }
                }
                2 -> { // showa
                    when {
                        patch > 0.35f -> out.set(INK_SCALE, 235f * shade, 68f * shade, 36f * shade)
                        patch < -0.35f -> out.set(INK_LIT, 238f * shade, 232f * shade, 222f * shade)
                        else -> out.set(INK_DARK, 42f, 42f, 54f, 18f, 18f, 26f, 0.92f)
                    }
                }
                3 -> { // ogon
                    out.set(INK_LIT, 248f * shade, 198f * shade, 48f * shade)
                }
                else -> { // chagoi
                    out.set(INK_SCALE, 198f * shade, 134f * shade, 68f * shade)
                }
            }
        }
    }
}

// =========================================================================
// 3. JELLYFISH
// =========================================================================
private class JellyPalette(
    val bellR: Float, val bellG: Float, val bellB: Float,
    val rimR: Float, val rimG: Float, val rimB: Float,
    val coreR: Float, val coreG: Float, val coreB: Float,
    val tentR: Float, val tentG: Float, val tentB: Float
)

private val JELLY_PALETTES = arrayOf(
    JellyPalette(232f, 70f, 188f, 255f, 150f, 230f, 255f, 195f, 245f, 210f, 110f, 220f),
    JellyPalette(70f, 195f, 245f, 150f, 235f, 255f, 200f, 245f, 255f, 95f, 180f, 235f),
    JellyPalette(165f, 95f, 245f, 215f, 165f, 255f, 235f, 205f, 255f, 155f, 115f, 235f),
    JellyPalette(245f, 135f, 80f, 255f, 195f, 140f, 255f, 225f, 185f, 230f, 125f, 95f)
)

private const val ORAL_ARM_GLYPHS = "{}~§"

class JellyfishSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "jellyfish"
    private var width = 1f
    private var height = 1f
    private val snowX = FloatArray(38)
    private val snowY = FloatArray(38)
    private val snowZ = FloatArray(38)
    private val snowSeed = FloatArray(38)

    private val flock = AsciiFlock(
        width = 1f,
        height = 1f,
        tuning = tuning,
        baseCount = 8,
        pad = 210f,
        minSpeed = 20f,
        maxSpeed = 40f,
        initMember = { j ->
            j.paletteIndex = Random.nextInt(JELLY_PALETTES.size)
            j.phase = Random.nextFloat() * TWO_PI
            j.pulseFreq = lerpF(0.38f, 0.62f, Random.nextFloat())
            j.bellRadius = lerpF(30f, 44f, Random.nextFloat())
            j.tentacleSeeds = FloatArray(8) { Random.nextFloat() * 100f }
            j.pulseAmount = 0f
            j.bellContract = 0f
            j.glowBoost = 0f
        }
    )

    init {
        for (i in 0 until snowX.size) {
            snowX[i] = Random.nextFloat()
            snowY[i] = Random.nextFloat()
            snowZ[i] = Random.nextFloat()
            snowSeed[i] = Random.nextFloat() * 100f
        }
    }

    override fun resize(width: Float, height: Float) {
        this.width = width
        this.height = height
        flock.resize(width, height)
    }

    override fun onPointerDown(x: Float, y: Float) {
        for (i in 0 until flock.members.size) {
            val j = flock.members[i]
            if (hypot(j.x - x, j.y - y) < 240f) {
                j.phase = 0.05f
            }
        }
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val pulseRate = tuning["pulseRate", 1f]
        val pulsePower = tuning["pulsePower", 1f]
        val pulseGlow = tuning["pulseGlow", 1f]
        val tentLenMult = tuning["tentacles", 1f]
        val pen = renderer.pen

        // Ambient marine snow
        for (i in 0 until snowX.size) {
            snowY[i] = (snowY[i] + dt * 0.012f * (0.4f + snowZ[i])) % 1f
            val sx = ((snowX[i] + sin(time * 0.3f + snowSeed[i]) * 0.02f + 1f) % 1f) * width
            val sy = snowY[i] * height
            pen.dot(sx, sy, '.', 130f, 150f, 195f, snowZ[i] * 0.3f, 0.55f)
        }

        flock.update(dt, time, 1f) { j, _, desired, _ ->
            j.phase = (j.phase + dt * j.pulseFreq * pulseRate) % 1f
            val p = j.phase
            val contract = if (p < 0.32f) {
                sin((p / 0.32f) * PI.toFloat()).pow(1.1f)
            } else {
                0f
            }
            j.bellContract = contract
            j.glowBoost = contract * pulseGlow
            desired
        }

        for (i in 0 until flock.members.size) {
            val j = flock.members[i]
            val surge = (0.35f + 1.55f * j.bellContract * pulsePower) - 1f
            j.x += j.vx * surge * dt * 0.45f
            j.y += j.vy * surge * dt * 0.45f
            drawJelly(j, time, tentLenMult, pen)
        }
    }

    private fun drawJelly(j: FlockMember, time: Float, tentLenMult: Float, pen: AsciiPen) {
        val scale = j.scale
        val pal = JELLY_PALETTES[j.paletteIndex % JELLY_PALETTES.size]
        val contract = j.bellContract
        val rx = j.bellRadius * scale * (1.06f - 0.24f * contract)
        val ry = j.bellRadius * scale * (0.72f + 0.22f * contract)
        val glow = j.glowBoost * 45f

        val forwardAngle = j.heading + HALF_PI
        val cosA = cos(-forwardAngle)
        val sinA = sin(-forwardAngle)
        val cosF = cos(forwardAngle)
        val sinF = sin(forwardAngle)

        if (tentLenMult > 0.05f) {
            val tentLen = j.bellRadius * scale * 2.55f * tentLenMult
            val steps = max(8, floor(tentLen / 7f).toInt())

            // 8 thin outer tentacles
            for (t in 0 until 8) {
                val u = (t / 7f) * 2f - 1f
                val baseLX = u * rx * 0.82f
                val baseLY = ry * 0.28f
                val seed = j.tentacleSeeds[t]
                for (s in 1..steps) {
                    val v = s.toFloat() / steps.toFloat()
                    val wave = sin(time * 2.6f - v * 4.5f + seed) * rx * 0.32f * v
                    val lx = baseLX * (1f - v * 0.15f) + wave
                    val ly = baseLY + v * tentLen
                    val wx = j.x + lx * cosF - ly * sinF
                    val wy = j.y + lx * sinF + ly * cosF
                    val ch = if (v > 0.78f) '.' else if (v > 0.45f) ':' else ';'
                    val fade = min(0.92f, j.fog + v * 0.38f)
                    pen.dot(wx, wy, ch, pal.tentR + glow * 0.5f, pal.tentG + glow * 0.4f, pal.tentB + glow * 0.5f, j.z - 0.01f, fade)
                }
            }

            // 4 frilly oral arms in the center
            val armLen = tentLen * 0.78f
            val armSteps = max(6, floor(armLen / 6.5f).toInt())
            for (a in 0 until 4) {
                val u = ((a / 3f) - 0.5f) * rx * 0.52f
                for (s in 1..armSteps) {
                    val v = s.toFloat() / armSteps.toFloat()
                    val curl = sin(time * 3.1f - v * 5.5f + a * 1.7f + j.seed) * rx * 0.22f * v
                    val lx = u + curl
                    val ly = ry * 0.2f + v * armLen
                    val wx = j.x + lx * cosF - ly * sinF
                    val wy = j.y + lx * sinF + ly * cosF
                    val ch = ORAL_ARM_GLYPHS[(s + a) % ORAL_ARM_GLYPHS.length]
                    val fade = min(0.9f, j.fog + v * 0.3f)
                    pen.dot(wx, wy, ch, pal.rimR + glow * 0.6f, pal.rimG + glow * 0.5f, pal.rimB + glow * 0.6f, j.z, fade)
                    if (s == armSteps && Random.nextFloat() < 0.18f) {
                        pen.trail(wx, wy, 0.22f)
                    }
                }
            }
        }

        val boundR = max(rx, ry) * 1.25f
        pen.shape(j.x, j.y, boundR, j.z + 0.01f, j.fog, j.id) { px, py, out ->
            val dx = px - j.x
            val dy = py - j.y
            val lx = dx * cosA - dy * sinA
            val ly = dx * sinA + dy * cosA

            val scallop = sin((lx / max(1f, rx)) * PI.toFloat() * 4f) * ry * 0.07f
            if (ly > ry * 0.32f + scallop || ly < -ry * 1.05f) return@shape false

            val nx = lx / rx
            val ny = (ly + ry * 0.15f) / ry
            val d = hypot(nx, ny)
            if (d > 1f) return@shape false

            if (d > 0.80f || ly > ry * 0.16f) {
                return@shape out.set(
                    INK_LIT,
                    min(255f, pal.rimR + glow),
                    min(255f, pal.rimG + glow),
                    min(255f, pal.rimB + glow)
                )
            }

            if (hypot(nx * 1.4f, (ly + ry * 0.05f) / (ry * 0.48f)) < 0.65f) {
                return@shape out.set(
                    INK_GLOW,
                    min(255f, pal.coreR + glow),
                    min(255f, pal.coreG + glow),
                    min(255f, pal.coreB + glow)
                )
            }

            out.set(
                INK_GLASS,
                min(255f, pal.bellR + glow * 0.6f),
                min(255f, pal.bellG + glow * 0.5f),
                min(255f, pal.bellB + glow * 0.6f),
                pal.bellR * 0.22f, pal.bellG * 0.18f, pal.bellB * 0.28f, 0.68f
            )
        }
    }
}

// =========================================================================
// 4. FROGS
// =========================================================================
class FrogsSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "frogs"
    private var width = 1f
    private var height = 1f
    private val segHit = SegmentHit()

    private val flock = AsciiFlock(
        width = 1f,
        height = 1f,
        tuning = tuning,
        baseCount = 9,
        pad = 150f,
        minSpeed = 30f,
        maxSpeed = 52f,
        initMember = { f ->
            f.stateInt = 0 // 0=rest, 1=jump
            f.restTimer = lerpF(0.4f, 1.8f, Random.nextFloat())
            f.jumpPhase = 0f
            f.jumpDuration = 0.55f
            f.jumpStartX = f.x
            f.jumpStartY = f.y
            f.jumpEndX = f.x
            f.jumpEndY = f.y
            f.jumpAngle = f.heading
            f.legExtend = 0f
            f.frontExtend = 0f
            f.bodyStretch = 1f
            f.heightArc = 0f
            f.blinkTimer = lerpF(1.5f, 4f, Random.nextFloat())
            f.blinking = false
        }
    )

    override fun resize(width: Float, height: Float) {
        this.width = width
        this.height = height
        flock.resize(width, height)
    }

    override fun onPointerDown(x: Float, y: Float) {
        for (i in 0 until flock.members.size) {
            val f = flock.members[i]
            if (f.stateInt == 0 && hypot(f.x - x, f.y - y) < 220f) {
                f.heading = atan2(f.y - y, f.x - x)
                f.restTimer = 0f
            }
        }
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val hopWait = tuning["hop", 1f]
        val leapSpeed = tuning["leap", 0.45f]
        val heightMult = tuning["height", 1f]
        val pen = renderer.pen

        flock.syncCount(1f)
        val baseAngle = flock.mainAngle()

        for (i in 0 until flock.members.size) {
            val f = flock.members[i]
            val depthScale = lerpF(tuning.farthest, 1f, f.z)
            f.scale = depthScale * tuning.size * f.size
            f.fog = (1f - f.z) * tuning.fog

            f.blinkTimer -= dt
            if (f.blinkTimer <= 0f) {
                f.blinking = !f.blinking
                f.blinkTimer = if (f.blinking) 0.14f else lerpF(2.0f, 4.5f, Random.nextFloat())
            }

            if (f.stateInt == 0) {
                f.restTimer -= dt
                f.legExtend += (0f - f.legExtend) * min(1f, dt * 10f)
                f.frontExtend += (0f - f.frontExtend) * min(1f, dt * 10f)
                f.bodyStretch += (1f - f.bodyStretch) * min(1f, dt * 10f)
                f.heightArc = 0f

                if (f.restTimer <= 0f) {
                    f.stateInt = 1
                    f.jumpPhase = 0f
                    f.jumpDuration = lerpF(0.42f, 0.62f, Random.nextFloat()) / max(0.25f, leapSpeed * 1.8f * tuning.speed)
                    val jumpAng = baseAngle + f.laneOffset * tuning.spread * 2.5f + (Random.nextFloat() - 0.5f) * 0.45f
                    f.heading = jumpAng
                    f.jumpAngle = jumpAng
                    val dist = lerpF(85f, 145f, Random.nextFloat()) * f.scale
                    f.jumpStartX = f.x
                    f.jumpStartY = f.y
                    f.jumpEndX = f.x + cos(jumpAng) * dist
                    f.jumpEndY = f.y + sin(jumpAng) * dist
                    pen.ripple(f.x, f.y, 75f * f.scale, 0.9f, 130f, 200f, 105f)
                }
            } else {
                f.jumpPhase += dt / f.jumpDuration
                val p = clampF(f.jumpPhase, 0f, 1f)
                val ease = smoothstepF(0f, 1f, p)
                f.x = lerpF(f.jumpStartX, f.jumpEndX, ease)
                f.y = lerpF(f.jumpStartY, f.jumpEndY, ease)
                f.heightArc = sin(p * PI.toFloat()) * 26f * f.scale * heightMult

                f.legExtend = if (p < 0.65f) {
                    smoothstepF(0f, 0.32f, p)
                } else {
                    1f - smoothstepF(0.65f, 1f, p)
                }
                f.frontExtend = if (p > 0.45f) sin(((p - 0.45f) / 0.55f) * PI.toFloat()) else 0f
                f.bodyStretch = 1f + 0.34f * sin(p * PI.toFloat())

                if (Random.nextFloat() < 0.4f) {
                    pen.trail(f.x, f.y - f.heightArc, 0.25f)
                }

                if (f.jumpPhase >= 1f) {
                    f.stateInt = 0
                    f.restTimer = lerpF(0.45f, 1.35f, Random.nextFloat()) * hopWait
                    pen.ripple(f.x, f.y, 90f * f.scale, 1.0f, 130f, 200f, 105f)

                    val pad = 160f * max(0.75f, f.scale)
                    if (f.x < -pad || f.x > width + pad || f.y < -pad || f.y > height + pad) {
                        val fresh = flock.spawn(false)
                        f.x = fresh.x
                        f.y = fresh.y
                        f.z = fresh.z
                        f.heading = fresh.heading
                    }
                }
            }
        }

        flock.members.sortBy { it.z }
        for (i in 0 until flock.members.size) {
            drawFrog(flock.members[i], pen)
        }
    }

    private fun drawFrog(f: FlockMember, pen: AsciiPen) {
        val scale = f.scale * (1f + (f.heightArc / 120f))
        val cx = f.x
        val cy = f.y - f.heightArc
        val forwardAngle = f.heading + HALF_PI
        val cosA = cos(-forwardAngle)
        val sinA = sin(-forwardAngle)

        val bodyRx = 15f * scale * (1f / f.bodyStretch.pow(0.35f))
        val bodyRy = 20f * scale * f.bodyStretch
        val ext = f.legExtend
        val fExt = f.frontExtend
        val boundR = (bodyRy + 32f * scale) * 1.25f

        pen.shape(cx, cy, boundR, f.z, f.fog, f.id) { px, py, out ->
            val dx = px - cx
            val dy = py - cy
            val lx = dx * cosA - dy * sinA
            val ly = dx * sinA + dy * cosA
            val ax = abs(lx)

            // Bulging eyes near snout
            val eyeX = bodyRx * 0.58f
            val eyeY = -bodyRy * 0.62f
            val eyeD = hypot(ax - eyeX, ly - eyeY)
            if (eyeD < 4.2f * scale) {
                return@shape out.set(
                    INK_LIT,
                    235f, 215f, 90f,
                    30f, 45f, 22f, 0.95f,
                    if (f.blinking) '-' else '@'
                )
            }

            // Torso ellipse
            val bDist = sdEllipse(lx, ly, bodyRx, bodyRy)
            if (bDist <= 0f) {
                val spot = sin(lx * 0.32f + f.seed) * cos(ly * 0.28f - f.seed)
                if (spot > 0.52f) {
                    return@shape out.set(INK_SPOT, 58f, 115f, 44f, 28f, 55f, 22f, 0.9f)
                }
                if (ax < 2.2f * scale) {
                    return@shape out.set(INK_LIT, 162f, 228f, 112f)
                }
                return@shape out.set(INK_SCALE, 104f, 184f, 76f)
            }

            // Hind legs
            val hipX = bodyRx * 0.72f
            val hipY = bodyRy * 0.55f
            val kneeX = lerpF(bodyRx * 1.45f, bodyRx * 0.92f, ext)
            val kneeY = lerpF(bodyRy * 0.10f, bodyRy * 1.35f, ext)
            val ankleX = lerpF(bodyRx * 0.95f, bodyRx * 0.78f, ext)
            val ankleY = lerpF(bodyRy * 0.82f, bodyRy * 2.15f, ext)
            val footX = lerpF(bodyRx * 1.35f, bodyRx * 0.95f, ext)
            val footY = lerpF(bodyRy * 0.95f, bodyRy * 2.65f, ext)

            val thighD = sdSegment(ax, ly, hipX, hipY, kneeX, kneeY, 4.4f * scale)
            val calfD = sdSegment(ax, ly, kneeX, kneeY, ankleX, ankleY, 3.5f * scale)
            val footD = sdSegment(ax, ly, ankleX, ankleY, footX, footY, 2.8f * scale)
            val hindD = min(thighD, min(calfD, footD))
            if (hindD <= 0f) {
                return@shape out.set(INK_SCALE, 92f, 168f, 66f)
            }

            // Front legs
            val shX = bodyRx * 0.75f
            val shY = -bodyRy * 0.32f
            val elX = bodyRx * 1.18f
            val elY = lerpF(-bodyRy * 0.35f, -bodyRy * 0.75f, fExt)
            val haX = bodyRx * 0.95f
            val haY = lerpF(-bodyRy * 0.72f, -bodyRy * 1.25f, fExt)
            val fArmD = min(
                sdSegment(ax, ly, shX, shY, elX, elY, 3.0f * scale),
                sdSegment(ax, ly, elX, elY, haX, haY, 2.5f * scale)
            )
            if (fArmD <= 0f) {
                return@shape out.set(INK_SCALE, 96f, 174f, 70f)
            }

            false
        }
    }
}

// =========================================================================
// 6. CRANES
// =========================================================================
class CranesSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "cranes"
    private var width = 1f
    private var height = 1f
    private val segHit = SegmentHit()

    private val flock = AsciiFlock(
        width = 1f,
        height = 1f,
        tuning = tuning,
        baseCount = 9,
        pad = 180f,
        minSpeed = 42f,
        maxSpeed = 68f,
        initMember = { c ->
            c.wingSpan = lerpF(54f, 72f, Random.nextFloat())
            c.flapPhase = Random.nextFloat() * TWO_PI
            c.glideTimer = lerpF(0.8f, 2.5f, Random.nextFloat())
            c.gliding = false
            c.glideAmount = 0f
            c.neckAngle = 0f
        }
    )

    override fun resize(width: Float, height: Float) {
        this.width = width
        this.height = height
        flock.resize(width, height)
    }

    override fun onPointerDown(x: Float, y: Float) {
        for (i in 0 until flock.members.size) {
            val c = flock.members[i]
            if (hypot(c.x - x, c.y - y) < 250f) {
                c.gliding = false
                c.glideTimer = 2f
            }
        }
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val wingbeat = tuning["wingbeat", 1f]
        val glideSetting = tuning["glide", 1f]
        val foldFlap = tuning.bool("foldFlap", false)
        val pen = renderer.pen

        flock.update(dt, time, 1f) { c, _, desired, _ ->
            c.glideTimer -= dt
            if (c.glideTimer <= 0f) {
                if (!c.gliding && glideSetting > 0.05f && Random.nextFloat() < 0.55f) {
                    c.gliding = true
                    c.glideTimer = lerpF(1.0f, 2.4f, Random.nextFloat()) * glideSetting
                } else {
                    c.gliding = false
                    c.glideTimer = lerpF(1.4f, 3.0f, Random.nextFloat())
                }
            }

            if (c.gliding) {
                c.glideAmount += (1f - c.glideAmount) * min(1f, dt * 4f)
            } else {
                c.glideAmount += (0f - c.glideAmount) * min(1f, dt * 5f)
                c.flapPhase += dt * 2.1f * wingbeat * TWO_PI
            }

            val diff = atan2(sin(desired - c.heading), cos(desired - c.heading))
            c.neckAngle += (clampF(diff * 1.5f, -0.35f, 0.35f) - c.neckAngle) * min(1f, dt * 6f)
            desired
        }

        for (i in 0 until flock.members.size) {
            drawCrane(flock.members[i], foldFlap, pen)
        }
    }

    private fun drawCrane(c: FlockMember, foldFlap: Boolean, pen: AsciiPen) {
        val scale = c.scale
        val span = c.wingSpan * scale
        val forwardAngle = c.heading + HALF_PI
        val cosA = cos(-forwardAngle)
        val sinA = sin(-forwardAngle)

        val stroke = lerpF(sin(c.flapPhase), 0.15f, c.glideAmount)
        val innerSweep = stroke * 0.22f
        val outerSweep = if (foldFlap && stroke < 0f) stroke * 0.65f else stroke * 0.35f
        val spanScale = if (foldFlap && stroke < 0f) (1f + stroke * 0.22f) else (1f - abs(stroke) * 0.10f)

        val wristX = span * 0.46f * spanScale
        val wristY = -span * 0.04f + innerSweep * span * 0.28f
        val tipX = span * 0.96f * spanScale
        val tipY = span * 0.06f + outerSweep * span * 0.48f

        if (!c.gliding && abs(stroke) > 0.75f && Random.nextFloat() < 0.35f) {
            val cF = cos(forwardAngle)
            val sF = sin(forwardAngle)
            pen.trail(c.x - tipX * cF, c.y - tipX * sF, 0.25f)
            pen.trail(c.x + tipX * cF, c.y + tipX * sF, 0.25f)
        }

        val boundR = span * 1.18f
        pen.shape(c.x, c.y, boundR, c.z, c.fog, c.id) { px, py, out ->
            val dx = px - c.x
            val dy = py - c.y
            val lx = dx * cosA - dy * sinA
            val ly = dx * sinA + dy * cosA
            val ax = abs(lx)

            // Trailing legs
            if (ly > span * 0.24f && ly < span * 0.72f && abs(ax - span * 0.045f) < max(1.5f, span * 0.025f)) {
                return@shape out.set(INK_DARK, 70f, 75f, 92f, 25f, 28f, 38f, 0.88f, '|')
            }

            // Outstretched slender neck, head, red crown & beak
            val neckTipX = sin(c.neckAngle) * span * 0.14f
            val neckTipY = -span * 0.56f
            sdSegment2(lx, ly, 0f, -span * 0.16f, neckTipX, neckTipY, segHit)
            val neckW = max(2.2f, span * 0.048f)
            if (segHit.d <= neckW) {
                if (segHit.t > 0.78f && segHit.t < 0.92f) {
                    return@shape out.set(INK_LIT, 238f, 58f, 58f, 120f, 22f, 22f, 0.92f, '@')
                }
                if (segHit.t >= 0.92f) {
                    return@shape out.set(INK_LIT, 210f, 195f, 120f)
                }
                if (segHit.t > 0.35f) {
                    return@shape out.set(INK_DARK, 38f, 42f, 54f, 18f, 20f, 28f, 0.92f)
                }
                return@shape out.set(INK_LIT, 236f, 240f, 250f)
            }

            // White torso
            if (sdEllipse(lx, ly, span * 0.14f, span * 0.25f) <= 0f) {
                if (ly > span * 0.16f) {
                    return@shape out.set(INK_DARK, 36f, 40f, 52f, 18f, 20f, 28f, 0.92f)
                }
                return@shape out.set(INK_LIT, 238f, 242f, 252f)
            }

            // Inner + outer wing segments
            sdSegment2(ax, ly, span * 0.08f, -span * 0.02f, wristX, wristY, segHit)
            val d1 = segHit.d
            val t1 = segHit.t
            val w1 = lerpF(span * 0.15f, span * 0.13f, t1)

            sdSegment2(ax, ly, wristX, wristY, tipX, tipY, segHit)
            val d2 = segHit.d
            val t2 = segHit.t
            val w2 = lerpF(span * 0.13f, span * 0.055f, t2)

            if (d1 <= w1 || d2 <= w2) {
                val u = if (d2 <= w2) (0.5f + t2 * 0.5f) else (t1 * 0.5f)
                val trailingEdge = if (d2 <= w2) (ly > wristY + (tipY - wristY) * t2 + w2 * 0.25f)
                else (ly > -span * 0.02f + (wristY + span * 0.02f) * t1 + w1 * 0.3f)

                if (u > 0.72f || trailingEdge) {
                    return@shape out.set(INK_DARK, 36f, 40f, 52f, 16f, 18f, 26f, 0.92f)
                }
                return@shape out.set(INK_LIT, 232f, 238f, 250f)
            }

            false
        }
    }
}

// =========================================================================
// 7. CRABS
// =========================================================================
class CrabsSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "crabs"
    private var width = 1f
    private var height = 1f
    private val segHit = SegmentHit()

    private val flock = AsciiFlock(
        width = 1f,
        height = 1f,
        tuning = tuning,
        baseCount = 9,
        pad = 150f,
        minSpeed = 38f,
        maxSpeed = 68f,
        initMember = { c ->
            c.walkDir = if (Random.nextFloat() < 0.5f) 1f else -1f
            c.bodyAngle = c.heading - c.walkDir * HALF_PI
            c.stateInt = 0 // 0=run, 1=pause
            c.burstTimer = lerpF(0.6f, 1.8f, Random.nextFloat())
            c.legPhase = Random.nextFloat() * TWO_PI
            c.clawRaise = 0f
            c.clawOpen = 0.25f
            c.stepRippleTimer = 0.2f
        }
    )

    override fun resize(width: Float, height: Float) {
        this.width = width
        this.height = height
        flock.resize(width, height)
    }

    override fun onPointerDown(x: Float, y: Float) {
        for (i in 0 until flock.members.size) {
            val c = flock.members[i]
            if (hypot(c.x - x, c.y - y) < 230f) {
                c.stateInt = 0
                c.burstTimer = 1.1f
                c.clawRaise = 1f
                c.clawOpen = 0.9f
                c.heading = atan2(c.y - y, c.x - x)
            }
        }
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val burstDur = tuning["burst", 1f]
        val restDur = tuning["rest", 1f]
        val legSpeed = tuning["legSpeed", 1f]
        val scareAmt = tuning["scare", 0.55f]
        val pen = renderer.pen

        flock.update(dt, time, 1f) { c, idx, desired, _ ->
            c.burstTimer -= dt
            if (c.burstTimer <= 0f) {
                if (c.stateInt == 0 && restDur > 0.05f && Random.nextFloat() < 0.6f) {
                    c.stateInt = 1
                    c.burstTimer = lerpF(0.35f, 1.1f, Random.nextFloat()) * restDur
                } else {
                    c.stateInt = 0
                    c.burstTimer = lerpF(0.65f, 1.6f, Random.nextFloat()) * burstDur
                    if (Random.nextFloat() < 0.35f) c.walkDir *= -1f
                }
            }

            var nearThreat = false
            for (j in 0 until flock.members.size) {
                if (j == idx) continue
                val o = flock.members[j]
                if (hypot(o.x - c.x, o.y - c.y) < 95f * c.scale) {
                    nearThreat = true
                    break
                }
            }
            val targetClaw = if (nearThreat) min(1f, 0.45f + scareAmt * 0.4f) else (scareAmt * 0.22f)
            c.clawRaise += (targetClaw - c.clawRaise) * min(1f, dt * 6f)
            c.clawOpen = 0.2f + 0.65f * c.clawRaise

            if (c.stateInt == 0) {
                c.legPhase += dt * 14f * legSpeed * tuning.speed
                c.stepRippleTimer -= dt
                if (c.stepRippleTimer <= 0f) {
                    c.stepRippleTimer = 0.28f
                    if (Random.nextFloat() < 0.45f) {
                        pen.ripple(c.x, c.y, 55f * c.scale, 0.5f, 210f, 130f, 95f)
                    }
                }
            }

            c.bodyAngle = c.heading - c.walkDir * HALF_PI
            desired
        }

        for (i in 0 until flock.members.size) {
            val c = flock.members[i]
            if (c.stateInt == 1) {
                c.x -= c.vx * dt * 0.92f
                c.y -= c.vy * dt * 0.92f
            }
            drawCrab(c, pen)
        }
    }

    private fun drawCrab(c: FlockMember, pen: AsciiPen) {
        val scale = c.scale
        val rx = 20f * scale
        val ry = 14f * scale
        val cosA = cos(-c.bodyAngle)
        val sinA = sin(-c.bodyAngle)
        val boundR = rx * 2.4f

        if (c.stateInt == 0 && Random.nextFloat() < 0.35f) {
            pen.trail(c.x, c.y, 0.22f)
        }

        pen.shape(c.x, c.y, boundR, c.z, c.fog, c.id) { px, py, out ->
            val dx = px - c.x
            val dy = py - c.y
            val lx = dx * cosA - dy * sinA
            val ly = dx * sinA + dy * cosA
            val ax = abs(lx)

            // Stalked eyes
            if (ly < -ry * 0.85f && ly > -ry * 1.25f && abs(ax - rx * 0.36f) < 2.6f * scale) {
                return@shape out.set(INK_LIT, 245f, 230f, 170f, 30f, 20f, 18f, 0.95f, '@')
            }

            // Carapace with toothed front rim
            val teeth = if (ly < 0f) sin((lx / max(1f, rx)) * PI.toFloat() * 6f) * 1.4f * scale else 0f
            val shellD = sdEllipse(lx, ly, rx, ry + teeth)
            if (shellD <= 0f) {
                val groove = abs(hypot(lx / (rx * 0.55f), ly / (ry * 0.55f)) - 0.85f)
                if (groove < 0.14f) {
                    return@shape out.set(INK_DARK, 135f, 46f, 28f, 62f, 20f, 14f, 0.92f)
                }
                return@shape out.set(INK_SCALE, 212f, 86f, 54f)
            }

            // 4 pairs of jointed walking legs
            for (l in 0 until 4) {
                val u = l / 3f
                val baseX = rx * (0.78f - u * 0.14f)
                val baseY = lerpF(-ry * 0.25f, ry * 0.68f, u)
                val gait = sin(c.legPhase + l * 1.55f + (if (lx < 0f) PI.toFloat() else 0f))
                val kneeX = baseX + rx * 0.55f
                val kneeY = baseY - ry * 0.18f + gait * 3.2f * scale
                val tipX = baseX + rx * 0.95f
                val tipY = baseY + ry * 0.32f - gait * 3.6f * scale

                val dLeg = min(
                    sdSegment(ax, ly, baseX, baseY, kneeX, kneeY, 2.3f * scale),
                    sdSegment(ax, ly, kneeX, kneeY, tipX, tipY, 1.9f * scale)
                )
                if (dLeg <= 0f) {
                    return@shape out.set(INK_SCALE, 186f, 72f, 44f)
                }
            }

            // Chelae (front claws)
            val shX = rx * 0.65f
            val shY = -ry * 0.55f
            val elX = rx * (1.05f + c.clawRaise * 0.15f)
            val elY = -ry * (1.05f + c.clawRaise * 0.35f)
            val clawX = rx * (0.78f + c.clawRaise * 0.22f)
            val clawY = -ry * (1.48f + c.clawRaise * 0.45f)

            val armD = sdSegment(ax, ly, shX, shY, elX, elY, 3.0f * scale)
            sdSegment2(ax, ly, elX, elY, clawX, clawY, segHit)
            val pincerW = lerpF(3.6f, 5.2f, segHit.t) * scale
            if (armD <= 0f || segHit.d <= pincerW) {
                if (segHit.d <= pincerW && segHit.t > 0.65f) {
                    return@shape out.set(INK_LIT, 245f, 195f, 145f)
                }
                return@shape out.set(INK_SCALE, 224f, 94f, 58f)
            }

            false
        }
    }
}

// =========================================================================
// 7. WHALE & BAITFISH SHOAL
// =========================================================================
private const val WHALE_NODES = 14
private val WHALE_WIDTHS = floatArrayOf(
    0.58f, 0.88f, 1.00f, 0.96f, 0.88f, 0.78f, 0.66f,
    0.54f, 0.42f, 0.32f, 0.23f, 0.16f, 0.12f, 0.42f
)

private class BaitFish(
    var x: Float, var y: Float, var z: Float,
    var vx: Float, var vy: Float, var heading: Float,
    var seed: Float
)

class WhaleSim(private val tuning: AsciiTuning) : AsciiSimulation {
    override val id: String = "whale"
    private var width = 1f
    private var height = 1f
    private val fish = ArrayList<BaitFish>(64)
    private val segHit = SegmentHit()

    private val whales = AsciiFlock(
        width = 1f,
        height = 1f,
        tuning = tuning,
        baseCount = 2,
        pad = 260f,
        minSpeed = 24f,
        maxSpeed = 38f,
        initMember = { w ->
            w.z = lerpF(0.65f, 0.95f, Random.nextFloat())
            w.length = lerpF(185f, 235f, Random.nextFloat())
            w.phase = Random.nextFloat() * TWO_PI
            w.spineX = FloatArray(WHALE_NODES) { w.x }
            w.spineY = FloatArray(WHALE_NODES) { w.y }
            w.mouthOpen = 0f
        }
    )

    override fun resize(width: Float, height: Float) {
        this.width = width
        this.height = height
        whales.resize(width, height)
    }

    private fun syncFish() {
        val target = max(16, (52f * tuning.density).roundToInt())
        while (fish.size < target) {
            val ang = whales.mainAngle() + (Random.nextFloat() - 0.5f) * 0.5f
            fish.add(
                BaitFish(
                    x = Random.nextFloat() * width,
                    y = Random.nextFloat() * height,
                    z = lerpF(0.25f, 0.95f, Random.nextFloat()),
                    vx = cos(ang) * 55f,
                    vy = sin(ang) * 55f,
                    heading = ang,
                    seed = Random.nextFloat() * 100f
                )
            )
        }
        while (fish.size > target) {
            fish.removeAt(fish.lastIndex)
        }
    }

    override fun onPointerDown(x: Float, y: Float) {
        for (i in 0 until whales.members.size) {
            val w = whales.members[i]
            w.heading = atan2(y - w.y, x - w.x)
            w.mouthOpen = 1f
        }
    }

    override fun step(dt: Float, time: Float, renderer: AsciiRenderer) {
        val whaleSize = tuning["whaleSize", 1.25f]
        val partDist = tuning["partDistance", 1.25f]
        val whaleSpeed = tuning["whaleSpeed", 0.9f]
        val pen = renderer.pen

        whales.update(dt, time, 0.25f) { w, _, desired, _ ->
            w.mouthOpen = max(0f, w.mouthOpen - dt * 0.7f)
            desired + sin(time * 0.35f + w.seed) * 0.18f
        }

        for (i in 0 until whales.members.size) {
            val w = whales.members[i]
            w.x += w.vx * (whaleSpeed - 1f) * dt
            w.y += w.vy * (whaleSpeed - 1f) * dt
            updateWhaleSpine(w, dt, whaleSize)
        }

        syncFish()
        val mainAng = whales.mainAngle()
        for (i in 0 until fish.size) {
            val f = fish[i]
            var desired = mainAng + sin(time * 1.4f + f.seed) * 0.25f
            var speedBoost = 1f

            for (wi in 0 until whales.members.size) {
                val w = whales.members[wi]
                for (k in 0..9 step 3) {
                    val nx = w.spineX[k]
                    val ny = w.spineY[k]
                    val dx = f.x - nx
                    val dy = f.y - ny
                    val d = hypot(dx, dy)
                    val avoidR = 115f * whaleSize * partDist
                    if (d < avoidR && d > 1f) {
                        val flee = atan2(dy, dx)
                        val weight = (1f - d / avoidR).pow(1.3f)
                        desired = lerpF(desired, flee, min(1f, weight * 1.4f))
                        speedBoost = max(speedBoost, 1f + weight * 1.9f)
                    }
                }
            }

            val diff = atan2(sin(desired - f.heading), cos(desired - f.heading))
            f.heading += diff * min(1f, dt * 6f)
            val spd = 64f * tuning.speed * speedBoost * (0.65f + 0.35f * f.z)
            f.x += cos(f.heading) * spd * dt
            f.y += sin(f.heading) * spd * dt
            if (f.x < -40f) f.x = width + 35f
            if (f.x > width + 40f) f.x = -35f
            if (f.y < -40f) f.y = height + 35f
            if (f.y > height + 40f) f.y = -35f

            val ch = if (abs(cos(f.heading)) > 0.65f) {
                if (cos(f.heading) > 0f) '>' else '<'
            } else {
                if (sin(f.heading) > 0f) 'v' else '^'
            }
            val fog = (1f - f.z) * tuning.fog
            pen.dot(f.x, f.y, ch, 175f, 224f, 255f, f.z * 0.55f, fog)
        }

        for (i in 0 until whales.members.size) {
            drawWhale(whales.members[i], whaleSize, pen)
        }
    }

    private fun updateWhaleSpine(w: FlockMember, dt: Float, whaleSize: Float) {
        val scale = w.scale * whaleSize
        val segLen = (w.length * scale) / (WHALE_NODES - 1)
        w.phase += dt * 2.1f

        val sx = w.spineX
        val sy = w.spineY
        if (hypot(sx[0] - w.x, sy[0] - w.y) > segLen * WHALE_NODES * 1.8f) {
            val bx = -cos(w.heading)
            val by = -sin(w.heading)
            for (i in 0 until WHALE_NODES) {
                sx[i] = w.x + bx * segLen * i
                sy[i] = w.y + by * segLen * i
            }
        } else {
            sx[0] = w.x
            sy[0] = w.y
        }

        for (i in 1 until WHALE_NODES) {
            val prevX = sx[i - 1]
            val prevY = sy[i - 1]
            var ang = atan2(sy[i] - prevY, sx[i] - prevX)
            val t = i.toFloat() / (WHALE_NODES - 1).toFloat()
            ang += sin(w.phase - i * 0.42f) * (0.04f + t * t * 0.24f) * 0.35f
            sx[i] = prevX + cos(ang) * segLen
            sy[i] = prevY + sin(ang) * segLen
        }
    }

    private fun drawWhale(w: FlockMember, whaleSize: Float, pen: AsciiPen) {
        val scale = w.scale * whaleSize
        val maxHalfW = 28f * scale
        val sx = w.spineX
        val sy = w.spineY

        val tailX = sx[WHALE_NODES - 1]
        val tailY = sy[WHALE_NODES - 1]
        if (Random.nextFloat() < 0.55f) {
            pen.trail(tailX, tailY, 0.38f)
        }

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until WHALE_NODES) {
            if (sx[i] < minX) minX = sx[i]
            if (sx[i] > maxX) maxX = sx[i]
            if (sy[i] < minY) minY = sy[i]
            if (sy[i] > maxY) maxY = sy[i]
        }
        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        val boundR = hypot(maxX - minX, maxY - minY) * 0.5f + maxHalfW * 3.1f

        val p3x = sx[3]; val p3y = sy[3]
        val p4x = sx[4]; val p4y = sy[4]
        val pecAng = atan2(p3y - p4y, p3x - p4x)
        val pecLen = maxHalfW * 2.55f

        val t1x = sx[WHALE_NODES - 2]; val t1y = sy[WHALE_NODES - 2]
        val t2x = sx[WHALE_NODES - 1]; val t2y = sy[WHALE_NODES - 1]
        val tailAng = atan2(t2y - t1y, t2x - t1x)
        val flukeLen = maxHalfW * 1.85f

        pen.shape(cx, cy, boundR, w.z, w.fog, w.id) { px, py, out ->
            // Long scalloped humpback pectoral flippers
            for (side in -1..1 step 2) {
                val fa = pecAng + side * (HALF_PI + 0.38f)
                val bx = p3x + cos(fa) * pecLen
                val by = p3y + sin(fa) * pecLen
                sdSegment2(px, py, p3x, p3y, bx, by, segHit)
                val fw = lerpF(maxHalfW * 0.42f, maxHalfW * 0.14f, segHit.t)
                if (segHit.d <= fw) {
                    if (segHit.t > 0.55f) {
                        return@shape out.set(INK_LIT, 212f, 236f, 255f)
                    }
                    return@shape out.set(INK_SCALE, 96f, 174f, 236f)
                }
            }

            // Broad tail flukes
            for (side in -1..1 step 2) {
                val fa = tailAng + side * 0.62f
                val bx = t2x + cos(fa) * flukeLen
                val by = t2y + sin(fa) * flukeLen
                if (sdSegment(px, py, t1x, t1y, bx, by, maxHalfW * 0.34f) <= 0f) {
                    return@shape out.set(INK_LIT, 146f, 206f, 250f)
                }
            }

            // Articulated body with ventral throat pleats
            var bestNorm = 99f
            var bestU = 0f
            for (i in 0 until WHALE_NODES - 1) {
                sdSegment2(px, py, sx[i], sy[i], sx[i + 1], sy[i + 1], segHit)
                val u = (i + segHit.t) / (WHALE_NODES - 1)
                val rad = lerpF(WHALE_WIDTHS[i], WHALE_WIDTHS[i + 1], segHit.t) * maxHalfW
                val norm = segHit.d / max(1f, rad)
                if (norm < bestNorm) {
                    bestNorm = norm
                    bestU = u
                }
            }
            if (bestNorm > 1f) return@shape false

            // Eye
            if (bestU > 0.11f && bestU < 0.16f && bestNorm > 0.65f && bestNorm < 0.90f) {
                return@shape out.set(INK_LIT, 235f, 245f, 255f, 0f, 0f, 0f, 0f, '@')
            }

            // Ventral throat pleats near front
            if (bestU < 0.42f && bestNorm > 0.45f) {
                val pleat = floor(bestNorm * 10f).toInt() % 2 == 0
                if (pleat) {
                    return@shape out.set(INK_LIT, 168f, 218f, 252f, 0f, 0f, 0f, 0f, '=')
                }
            }

            val shade = 1f - bestNorm * 0.28f
            out.set(INK_SCALE, 72f * shade, 156f * shade, 228f * shade)
        }
    }
}
