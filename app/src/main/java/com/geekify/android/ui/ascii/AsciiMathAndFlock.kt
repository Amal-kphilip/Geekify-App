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

const val TAU = (PI * 2.0).toFloat()
const val TWO_PI = TAU
const val HALF_PI = (PI * 0.5).toFloat()

@Suppress("NOTHING_TO_INLINE")
inline fun clampF(v: Float, lo: Float, hi: Float): Float =
    if (v.isNaN() || v < lo) lo else if (v > hi) hi else v

@Suppress("NOTHING_TO_INLINE")
inline fun Float.safeRoundToInt(default: Int = 0): Int =
    if (this.isNaN() || this.isInfinite()) default else this.roundToInt()

@Suppress("NOTHING_TO_INLINE")
inline fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

@Suppress("NOTHING_TO_INLINE")
inline fun smoothstepF(e0: Float, e1: Float, x: Float): Float {
    val t = clampF((x - e0) / (e1 - e0), 0f, 1f)
    return t * t * (3f - 2f * t)
}

@Suppress("NOTHING_TO_INLINE")
inline fun fracF(x: Float): Float = x - floor(x)

fun angleDiff(a: Float, b: Float): Float {
    var d = (b - a) % TAU
    if (d > PI.toFloat()) d -= TAU
    if (d < -PI.toFloat()) d += TAU
    return d
}

fun hash3(x: Int, y: Int, z: Int): Float {
    var h = (x * 374761393) xor (y * 668265263) xor (z * 1442695041)
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h.toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
}

fun hash3(x: Float, y: Float, z: Float): Float {
    return hash3(x.toInt(), y.toInt(), z.toInt())
}

class SegmentState {
    var t: Float = 0f
}

val globalSeg = SegmentState()

fun toSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float, outSeg: SegmentState = globalSeg): Float {
    val sx = bx - ax
    val sy = by - ay
    val len2 = sx * sx + sy * sy
    val t = if (len2 > 1e-6f) clampF(((px - ax) * sx + (py - ay) * sy) / len2, 0f, 1f) else 0f
    outSeg.t = t
    return hypot(px - ax - sx * t, py - ay - sy * t)
}

fun inEllipse(u: Float, v: Float, cu: Float, cv: Float, a: Float, b: Float): Boolean {
    val x = (u - cu) / a
    val y = (v - cv) / b
    return x * x + y * y <= 1f
}

fun spotted(u: Float, v: Float, cell: Float, radius: Float, seed: Int): Boolean {
    val gu = u / cell
    val gv = v / cell
    val cu = floor(gu).toInt()
    val cv = floor(gv).toInt()
    val jx = 0.25f + 0.5f * hash3(cu, cv, seed)
    val jy = 0.25f + 0.5f * hash3(cu, cv, seed + 11)
    val r = radius / cell * (0.6f + 0.6f * hash3(cu, cv, seed + 23))
    val du = gu - cu - jx
    val dv = gv - cv - jy
    return du * du + dv * dv < r * r
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

class Pose(
    var x: Float = 0f,
    var y: Float = 0f,
    var heading: Float = 0f,
    var span: Float = 100f,
    var depth: Float = 0.5f,
    var id: Int = 1
)

class FlockCreature(val id: Int) {
    var roll: Float = 0f
    var depth: Float = 1f
    var span: Float = 100f
    var x: Float = 0f
    var y: Float = 0f
    var vx: Float = 0f
    var vy: Float = 0f
    var heading: Float = 0f
    var bias: Float = 0f
    var startledUntil: Float = 0f
    var gone: Boolean = false

    // Butterfly
    var phase: Float = 0f
    var open: Float = 1f
    var glide: Float = 0f
    var glideUntil: Float = 0f
    var flutterX: Float = 0f
    var flutterY: Float = 0f
    var paletteIndex: Int = 0

    // Koi
    var nodes: FloatArray = FloatArray(28) // 14 nodes * 2 (x,y)
    var variety: Int = 0

    // Jellyfish
    var squeeze: Float = 0f
    var turn: Float = 0f

    // Frog
    var leaping: Boolean = false
    var until: Float = 0f
    var aim: Float = 0f
    var s: Float = 0f
    var dur: Float = 0.5f
    var fromX: Float = 0f
    var fromY: Float = 0f
    var toX: Float = 0f
    var toY: Float = 0f

    // Crane
    var pace: Float = 1f

    // Crab
    var running: Boolean = true
    var facing: Float = 1f
    var gait: Float = 0f
    var alarm: Float = 0f
    var fleeDir: Float = 0f
    var fleeSpeed: Float = 0f
    var fleeFrom: Float = 0f

    // Whale
    var scaleMul: Float = 1f

    // Rain / Snow / Scenes
    var mass: Float = 1f
    var radius: Float = 8f
    var sliding: Boolean = false
    var wobble: Float = 0f
    var spin: Float = 0f
    var spinRate: Float = 0f
    var swayPhase: Float = 0f
}

class RippleEffect(
    var x: Float,
    var y: Float,
    var r: Float = 2f,
    var maxR: Float = 75f,
    var strength: Float = 1f,
    var speed: Float = 68f,
    var colorR: Float = 110f,
    var colorG: Float = 190f,
    var colorB: Float = 235f
)

class StampEvent(
    var x: Float,
    var y: Float,
    var radius: Int,
    var amount: Float
)

class Flock(
    val tuning: AsciiTuning,
    var area: Float = 50000f,
    var scale: Float = 1f,
    var speed: Float = 90f,
    var maxCreatures: Int = 240,
    var water: Boolean = false,
    var windWeight: Float = 1f
) {
    var width: Float = 1f
    var height: Float = 1f
    var time: Float = 0f
    val creatures = ArrayList<FlockCreature>(64)
    val events = ArrayList<StampEvent>(16)
    val ripples = ArrayList<RippleEffect>(32)
    var base: Float = 120f

    var accX: Float = 0f
    var accY: Float = 0f

    var windX: Float = 0f
    var windY: Float = 0f
    var gust: Float = 0f
    var gustBoost: Float = 0f
    var gustDir: Float = 0f

    var dayPhase: Float = 0f
    var daylight: Float = 0f
    var nextId: Int = 1

    private val referenceSize = 160f
    private val startleRadius = 240f

    fun resize(w: Float, h: Float) {
        val first = width <= 1f
        width = max(1f, w)
        height = max(1f, h)
        base = clampF(min(width, height) * 0.2f, 80f, 190f) * scale
        if (first) populate(scatter = true)
    }

    val course: Float
        get() {
            return if (tuning.corner) {
                atan2(-height, width)
            } else {
                -tuning.direction * PI.toFloat() / 180f
            }
        }

    val heading: Float
        get() {
            val t = time
            return course + tuning.sway * (sin(t * 0.13f) + 0.5f * sin(t * 0.31f + 1.7f))
        }

    val target: Int
        get() {
            val shrink = (base / scale / referenceSize).let { it * it }
            val cap = (maxCreatures * max(1f, tuning.density * 0.75f)).roundToInt()
            val want = (width * height / (area * shrink) * tuning.density).roundToInt()
            return clampF(want.toFloat(), 1f, cap.toFloat()).toInt()
        }

    fun depthOf(roll: Float): Float {
        return tuning.farthest + (1f - tuning.farthest) * roll.pow(tuning.farBias)
    }

    fun cruise(c: FlockCreature): Float {
        val dnFactor = if (tuning.dayNight > 0f) 0.82f + 0.32f * daylight else 1f
        return speed * (0.55f + 0.45f * c.depth) * tuning.speed * dnFactor
    }

    fun ripple(x: Float, y: Float, maxR: Float = 75f, strength: Float = 1f, speed: Float = 68f, r: Float = 110f, g: Float = 190f, b: Float = 235f) {
        val rScale = tuning.ripples
        if (rScale <= 0f) return
        if (ripples.size > 32) ripples.removeAt(0)
        ripples.add(
            RippleEffect(
                x = x, y = y, r = 2f,
                maxR = maxR * (0.85f + 0.3f * rScale),
                strength = strength * rScale,
                speed = speed,
                colorR = r, colorG = g, colorB = b
            )
        )
    }

    fun triggerGust(angle: Float = course, boost: Float = 1.2f) {
        gustDir = angle
        gustBoost = min(2.5f, gustBoost + boost)
    }

    fun startle(x: Float, y: Float, customStartle: ((FlockCreature, Float, Float, Float) -> Unit)? = null) {
        if (water) {
            ripple(x, y, 95f, 1.2f, 76f)
            ripple(x, y, 55f, 0.85f, 48f)
        }
        events.add(StampEvent(x, y, 6, 1.4f))
        for (i in 0 until creatures.size) {
            val c = creatures[i]
            val dx = c.x - x
            val dy = c.y - y
            val d = hypot(dx, dy).coerceAtLeast(1f)
            if (d > startleRadius) continue
            val strength = 1f - d / startleRadius
            if (customStartle != null) {
                customStartle(c, dx / d, dy / d, strength)
            } else {
                val k = strength * 420f * c.depth
                c.vx += (dx / d) * k
                c.vy += (dy / d) * k
                c.startledUntil = time + lerpF(0.6f, 1.2f, Random.nextFloat())
            }
        }
    }

    fun step(dt: Float, onCreatureEnter: ((FlockCreature) -> Unit)? = null) {
        time += dt
        val t = time
        val dn = tuning.dayNight
        if (dn > 0f) {
            dayPhase = (dayPhase + dt * 0.025f * dn) % 1f
            daylight = 0.5f - 0.5f * cos(dayPhase * TAU)
        } else {
            dayPhase = 0f
            daylight = 0f
        }

        val w = tuning.wind
        gustBoost *= exp(-dt * 1.4f)
        val wave = max(0f, sin(t * 0.45f) * sin(t * 0.19f + 1.1f))
        val pulse = wave.pow(2.2f) * 1.6f
        gust = w * (0.35f + pulse) + gustBoost
        val wAngle = course + 0.45f * sin(t * 0.23f) + (if (gustBoost > 0.05f) angleDiff(course, gustDir) * 0.6f else 0f)
        val windMag = gust * 68f
        windX = cos(wAngle) * windMag
        windY = sin(wAngle) * windMag

        for (i in ripples.size - 1 downTo 0) {
            val rp = ripples[i]
            rp.r += rp.speed * dt
            if (rp.r >= rp.maxR) ripples.removeAt(i)
        }

        populate(scatter = false, onCreatureEnter = onCreatureEnter)

        for (i in 0 until creatures.size) {
            val c = creatures[i]
            c.depth = depthOf(c.roll)
            c.span = base * c.depth * tuning.size
        }
    }

    fun populate(scatter: Boolean, onCreatureEnter: ((FlockCreature) -> Unit)? = null) {
        val want = target
        while (creatures.size > want) {
            val idx = Random.nextInt(creatures.size)
            creatures[idx].gone = true
            creatures.removeAt(idx)
        }
        while (creatures.size < want) {
            val c = spawn(onCreatureEnter)
            if (scatter || Random.nextFloat() < 0.5f) {
                c.x = Random.nextFloat() * width
                c.y = Random.nextFloat() * height
                onCreatureEnter?.invoke(c)
            }
            insert(c)
        }
    }

    private fun insert(c: FlockCreature) {
        var idx = creatures.indexOfFirst { it.roll > c.roll }
        if (idx < 0) idx = creatures.size
        creatures.add(idx, c)
    }

    private fun spawn(onCreatureEnter: ((FlockCreature) -> Unit)?): FlockCreature {
        val c = FlockCreature(nextId++)
        c.roll = Random.nextFloat()
        c.depth = depthOf(c.roll)
        c.span = base * c.depth * tuning.size
        reenter(c, onCreatureEnter)
        return c
    }

    fun reenter(c: FlockCreature, onCreatureEnter: ((FlockCreature) -> Unit)? = null) {
        val m = c.span * 0.6f
        val cosC = cos(course)
        val sinC = sin(course)
        val sideways = height * abs(cosC)
        val vertical = width * abs(sinC)
        if (Random.nextFloat() * (sideways + vertical) < sideways) {
            c.x = if (cosC >= 0f) -m else width + m
            c.y = Random.nextFloat() * height
        } else {
            c.x = Random.nextFloat() * width
            c.y = if (sinC <= 0f) height + m else -m
        }
        val cr = cruise(c)
        c.heading = course
        c.vx = cosC * cr
        c.vy = sinC * cr
        c.bias = lerpF(-1f, 1f, Random.nextFloat())
        onCreatureEnter?.invoke(c)
    }

    fun passed(c: FlockCreature, margin: Float = c.span): Boolean {
        val cosC = cos(course)
        val sinC = sin(course)
        val out = (cosC > 0.05f && c.x > width + margin) ||
            (cosC < -0.05f && c.x < -margin) ||
            (sinC < -0.05f && c.y < -margin) ||
            (sinC > 0.05f && c.y > height + margin)
        val lost = c.x < -3f * margin || c.x > width + 3f * margin || c.y < -3f * margin || c.y > height + 3f * margin
        return out || lost
    }

    fun beginSteer() {
        accX = 0f
        accY = 0f
    }

    fun steer(c: FlockCreature, dir: Float, speed: Float, grip: Float) {
        accX += (cos(dir) * speed - c.vx) * grip
        accY += (sin(dir) * speed - c.vy) * grip
    }

    fun separate(c: FlockCreature, strength: Float = tuning.separation, range: Float = 0.42f) {
        if (strength <= 0f) return
        for (i in 0 until creatures.size) {
            val o = creatures[i]
            if (o === c || abs(o.depth - c.depth) > 0.18f) continue
            val ox = c.x - o.x
            val oy = c.y - o.y
            val d = hypot(ox, oy)
            val r = (c.span + o.span) * range
            if (d < r && d > 1e-3f) {
                val w = (1f - d / r) * strength
                accX += (ox / d) * w
                accY += (oy / d) * w
            }
        }
    }

    fun move(c: FlockCreature, dt: Float, maxSpd: Float): Float {
        val lightFactor = windWeight * (1.35f - 0.5f * c.depth)
        c.vx += (accX + windX * lightFactor * 0.85f) * dt
        c.vy += (accY + windY * lightFactor * 0.85f) * dt
        var speed = hypot(c.vx, c.vy)
        val cap = maxSpd + (if (time < c.startledUntil) 300f else 0f) + gust * 45f * lightFactor
        if (speed > cap && speed > 0.001f) {
            val s = cap / speed
            c.vx *= s
            c.vy *= s
            speed = cap
        }
        c.x += c.vx * dt
        c.y += c.vy * dt
        return speed
    }

    fun face(c: FlockCreature, dt: Float, rate: Float = 7f, angle: Float = atan2(c.vy, c.vx)) {
        val turn = rate * dt
        c.heading += clampF(angleDiff(c.heading, angle), -turn, turn)
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
