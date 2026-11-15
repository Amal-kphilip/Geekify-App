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

const val TWO_PI = (PI * 2.0).toFloat()
const val HALF_PI = (PI * 0.5).toFloat()

@Suppress("NOTHING_TO_INLINE")
inline fun clampF(v: Float, lo: Float, hi: Float): Float =
    if (v < lo) lo else if (v > hi) hi else v

@Suppress("NOTHING_TO_INLINE")
inline fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

@Suppress("NOTHING_TO_INLINE")
inline fun smoothstepF(e0: Float, e1: Float, x: Float): Float {
    val t = clampF((x - e0) / (e1 - e0), 0f, 1f)
    return t * t * (3f - 2f * t)
}

fun hash1(n: Float): Float {
    val x = sin(n * 127.1f + 311.7f) * 43758.5453f
    return x - floor(x)
}

fun noise1(x: Float): Float {
    val i = floor(x)
    val f = x - i
    val u = f * f * (3f - 2f * f)
    return lerpF(hash1(i), hash1(i + 1f), u) * 2f - 1f
}

fun sdSegment(
    px: Float, py: Float,
    ax: Float, ay: Float,
    bx: Float, by: Float,
    r: Float
): Float {
    val vx = bx - ax
    val vy = by - ay
    val wx = px - ax
    val wy = py - ay
    val len2 = vx * vx + vy * vy
    val t = if (len2 > 1e-6f) clampF((wx * vx + wy * vy) / len2, 0f, 1f) else 0f
    val dx = wx - vx * t
    val dy = wy - vy * t
    return hypot(dx, dy) - r
}

class SegmentHit {
    var d: Float = 0f
    var t: Float = 0f
}

fun sdSegment2(
    px: Float, py: Float,
    ax: Float, ay: Float,
    bx: Float, by: Float,
    out: SegmentHit
): SegmentHit {
    val vx = bx - ax
    val vy = by - ay
    val wx = px - ax
    val wy = py - ay
    val len2 = vx * vx + vy * vy
    val t = if (len2 > 1e-6f) clampF((wx * vx + wy * vy) / len2, 0f, 1f) else 0f
    out.d = hypot(wx - vx * t, wy - vy * t)
    out.t = t
    return out
}

fun sdEllipse(px: Float, py: Float, rx: Float, ry: Float): Float {
    val nx = px / rx
    val ny = py / ry
    return (hypot(nx, ny) - 1f) * min(rx, ry)
}

@Suppress("NOTHING_TO_INLINE")
inline fun sdCircle(px: Float, py: Float, r: Float): Float = hypot(px, py) - r

fun sdBox(px: Float, py: Float, hx: Float, hy: Float): Float {
    val dx = abs(px) - hx
    val dy = abs(py) - hy
    return hypot(max(dx, 0f), max(dy, 0f)) + min(max(dx, dy), 0f)
}

/**
 * Lookup table for `r / (1 - e * cos(a))` over `a in [0, PI]`, 256 bins, `e = 0.42`.
 */
val ELLIPSE_SCALE: FloatArray = FloatArray(257) { i ->
    val a = (i.toFloat() / 256f) * PI.toFloat()
    (1f - 0.42f * cos(a)) / (1f - 0.42f * 0.42f)
}

fun hueMatrix(deg: Float, out: FloatArray = FloatArray(9)): FloatArray {
    val a = (deg * PI / 180.0).toFloat()
    val c = cos(a)
    val s = sin(a)
    out[0] = 0.213f + c * 0.787f - s * 0.213f
    out[1] = 0.715f - c * 0.715f - s * 0.715f
    out[2] = 0.072f - c * 0.072f + s * 0.928f
    out[3] = 0.213f - c * 0.213f + s * 0.143f
    out[4] = 0.715f + c * 0.285f + s * 0.140f
    out[5] = 0.072f - c * 0.072f - s * 0.283f
    out[6] = 0.213f - c * 0.213f - s * 0.787f
    out[7] = 0.715f - c * 0.715f + s * 0.715f
    out[8] = 0.072f + c * 0.928f + s * 0.072f
    return out
}

class FlockMember(val id: Int) {
    var x: Float = 0f
    var y: Float = 0f
    var z: Float = 0.5f
    var vx: Float = 0f
    var vy: Float = 0f
    var heading: Float = 0f
    var speed: Float = 60f
    var size: Float = 1f
    var seed: Float = 0f
    var laneOffset: Float = 0f
    var swayFreq: Float = 0.5f
    var swayPhase: Float = 0f
    var scale: Float = 1f
    var fog: Float = 0f

    // Reusable per-creature state fields so there is zero object allocation per frame
    var phase: Float = 0f
    var flapFreq: Float = 2f
    var glideTimer: Float = 0f
    var gliding: Boolean = false
    var glideDuration: Float = 1f
    var wingOpen: Float = 1f
    var turnBias: Float = 0f

    // Koi / Whale / Spine
    var varietyIndex: Int = 0
    var spotSeed: Float = 0f
    var length: Float = 80f
    var spineX: FloatArray = FloatArray(0)
    var spineY: FloatArray = FloatArray(0)
    var finPhase: Float = 0f
    var mouthOpen: Float = 0f

    // Jellyfish
    var paletteIndex: Int = 0
    var bellRadius: Float = 36f
    var pulseFreq: Float = 0.5f
    var tentacleSeeds: FloatArray = FloatArray(0)
    var pulseAmount: Float = 0f
    var bellContract: Float = 0f
    var glowBoost: Float = 0f

    // Dragonfly
    var colorIndex: Int = 0
    var stateInt: Int = 0
    var stateTimer: Float = 0f
    var targetAngle: Float = 0f
    var wingPhase: Float = 0f
    var hoverX: Float = 0f
    var hoverY: Float = 0f

    // Frog
    var restTimer: Float = 0f
    var jumpPhase: Float = 0f
    var jumpDuration: Float = 0.5f
    var jumpStartX: Float = 0f
    var jumpStartY: Float = 0f
    var jumpEndX: Float = 0f
    var jumpEndY: Float = 0f
    var jumpAngle: Float = 0f
    var legExtend: Float = 0f
    var frontExtend: Float = 0f
    var bodyStretch: Float = 1f
    var heightArc: Float = 0f
    var blinkTimer: Float = 2f
    var blinking: Boolean = false

    // Crane
    var wingSpan: Float = 64f
    var flapPhase: Float = 0f
    var glideAmount: Float = 0f
    var neckAngle: Float = 0f

    // Crab
    var walkDir: Float = 1f
    var bodyAngle: Float = 0f
    var burstTimer: Float = 1f
    var legPhase: Float = 0f
    var clawRaise: Float = 0f
    var clawOpen: Float = 0f
    var stepRippleTimer: Float = 0.2f

    // Starling
    var bank: Float = 0f

    // Firefly
    var naturalFreq: Float = 2f
    var flash: Float = 0f

    // Moth
    var orbitDir: Float = 1f
    var prefRadius: Float = 90f
    var dustTimer: Float = 0.2f

    // Seahorse / Turtle
    var isTurtle: Boolean = false
    var bobPhase: Float = 0f
    var curlPhase: Float = 0f
    var dorsalPhase: Float = 0f
    var flipperPhase: Float = 0f

    // Bat
    var emergeT: Float = 0f
    var streamOffset: Float = 0f
    var spiralPhase: Float = 0f

    // Ant
    var carrying: Boolean = false
    var dir: Float = 1f
    var pathT: Float = 0f
    var laneBias: Float = 0f
}

/**
 * Shared movement & depth manager for a group of animals.
 */
class AsciiFlock(
    var width: Float,
    var height: Float,
    val tuning: AsciiTuning,
    val baseCount: Int = 11,
    val pad: Float = 140f,
    val minSpeed: Float = 36f,
    val maxSpeed: Float = 72f,
    val initMember: ((FlockMember) -> Unit)? = null
) {
    val members = ArrayList<FlockMember>(32)
    private var nextId = 1

    fun resize(w: Float, h: Float) {
        width = w
        height = h
    }

    private fun pickDepth(): Float {
        val bias = max(0.2f, tuning.farBias)
        return 1f - Random.nextFloat().pow(1f / bias)
    }

    fun mainAngle(): Float {
        return if (tuning.corner) {
            atan2(-height, width)
        } else {
            (-tuning.direction * PI / 180.0).toFloat()
        }
    }

    fun syncCount(extraMultiplier: Float = 1f) {
        val areaScale = clampF((width * height) / (1280f * 760f), 0.45f, 2.2f)
        val target = max(1, (baseCount * areaScale * tuning.density * extraMultiplier).roundToInt())
        while (members.size < target) {
            members.add(spawn(initial = members.size < target - 1))
        }
        while (members.size > target) {
            members.removeAt(members.lastIndex)
        }
    }

    fun spawn(initial: Boolean = false): FlockMember {
        val z = pickDepth()
        val depthScale = lerpF(tuning.farthest, 1f, z)
        val angle = mainAngle() + (Random.nextFloat() - 0.5f) * 0.5f
        val x: Float
        val y: Float
        if (initial) {
            x = Random.nextFloat() * width
            y = Random.nextFloat() * height
        } else {
            val c = cos(angle)
            val s = sin(angle)
            if (Random.nextFloat() < 0.55f) {
                x = if (c >= 0f) -pad * 0.6f else width + pad * 0.6f
                y = Random.nextFloat() * (height + pad * 0.5f) - pad * 0.25f
            } else {
                x = Random.nextFloat() * (width + pad * 0.5f) - pad * 0.25f
                y = if (s >= 0f) -pad * 0.6f else height + pad * 0.6f
            }
        }
        val m = FlockMember(nextId++)
        m.x = x
        m.y = y
        m.z = z
        m.vx = cos(angle) * 40f
        m.vy = sin(angle) * 40f
        m.heading = angle
        m.speed = lerpF(minSpeed, maxSpeed, Random.nextFloat())
        m.size = lerpF(0.84f, 1.18f, Random.nextFloat())
        m.seed = Random.nextFloat() * 1000f
        m.laneOffset = (Random.nextFloat() - 0.5f) * 2f
        m.swayFreq = lerpF(0.28f, 0.65f, Random.nextFloat())
        m.swayPhase = Random.nextFloat() * TWO_PI
        m.scale = depthScale * tuning.size * m.size
        m.fog = (1f - z) * tuning.fog
        initMember?.invoke(m)
        return m
    }

    fun update(
        dt: Float,
        time: Float,
        extraMultiplier: Float = 1f,
        customSteer: ((FlockMember, Int, Float, Float) -> Float)? = null
    ) {
        syncCount(extraMultiplier)
        val baseAngle = mainAngle()
        val perpX = -sin(baseAngle)
        val perpY = cos(baseAngle)
        val sepRadius = tuning.separation

        for (i in 0 until members.size) {
            val m = members[i]
            val depthScale = lerpF(tuning.farthest, 1f, m.z)
            m.scale = depthScale * tuning.size * m.size
            m.fog = (1f - m.z) * tuning.fog

            // Wind gusts + gentle individual sway
            val gust = noise1(time * 0.28f + m.seed * 0.1f) * 0.28f * tuning.wind
            val sway = sin(time * m.swayFreq * TWO_PI + m.swayPhase) * tuning.sway * 3f
            var desiredAngle = baseAngle + m.laneOffset * tuning.spread * 3f + gust + sway

            // Gentle drift back toward the main diagonal stream
            val cx = m.x - width * 0.5f
            val cy = m.y - height * 0.5f
            val offAxis = (cx * perpX + cy * perpY) / max(240f, min(width, height))
            desiredAngle -= clampF(offAxis * 0.22f, -0.35f, 0.35f)

            // Soft separation from neighbors at similar depth
            if (sepRadius > 1f) {
                var pushX = 0f
                var pushY = 0f
                for (j in 0 until members.size) {
                    if (i == j) continue
                    val o = members[j]
                    if (abs(o.z - m.z) > 0.35f) continue
                    val dx = m.x - o.x
                    val dy = m.y - o.y
                    val d = hypot(dx, dy)
                    val minD = sepRadius * 0.32f * m.scale
                    if (d > 0.001f && d < minD) {
                        val f = (1f - d / minD) / d
                        pushX += dx * f
                        pushY += dy * f
                    }
                }
                if (pushX != 0f || pushY != 0f) {
                    desiredAngle += clampF(
                        sin(desiredAngle) * pushX - cos(desiredAngle) * pushY,
                        -0.5f,
                        0.5f
                    ) * 0.45f
                }
            }

            var speedMult = 1f
            if (customSteer != null) {
                val res = customSteer(m, i, desiredAngle, speedMult)
                if (!res.isNaN()) {
                    desiredAngle = res
                }
            }

            // Smooth angular turn
            val diff = atan2(sin(desiredAngle - m.heading), cos(desiredAngle - m.heading))
            m.heading += diff * min(1f, dt * tuning.grip * 1.4f)

            val spd = m.speed * tuning.speed * (0.55f + 0.55f * depthScale) * speedMult
            m.vx = cos(m.heading) * spd
            m.vy = sin(m.heading) * spd
            m.x += m.vx * dt
            m.y += m.vy * dt

            // Wrap around screen bounds
            val p = pad * max(0.75f, m.scale)
            if (m.x < -p || m.x > width + p || m.y < -p || m.y > height + p) {
                val fresh = spawn(initial = false)
                m.x = fresh.x
                m.y = fresh.y
                m.z = fresh.z
                m.heading = fresh.heading
                m.laneOffset = fresh.laneOffset
            }
        }

        // Sort far-to-near so nearer creatures paint on top
        members.sortBy { it.z }
    }
}

interface AsciiSimulation {
    val id: String
    fun onSelect() {}
    fun resize(width: Float, height: Float)
    fun step(dt: Float, time: Float, renderer: AsciiRenderer)
    fun onPointerDown(x: Float, y: Float) {}
    fun onPointerMove(x: Float, y: Float) {}
}
