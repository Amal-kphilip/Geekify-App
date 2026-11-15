package com.geekify.android.ui.ascii

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

const val MAX_ASCII_DETAIL = 2.0f
const val MIN_ASCII_GRID = 0.0f

@Immutable
data class AsciiControlSpec(
    val key: String,
    val label: String,
    val min: Float = 0f,
    val max: Float = 1f,
    val step: Float = 0.05f,
    val defaultValue: Float = 1f,
    val isToggle: Boolean = false
)

@Immutable
data class AsciiControlSection(
    val title: String,
    val controls: List<AsciiControlSpec>
)

@Immutable
data class AsciiAnimalSpec(
    val id: String,
    val name: String,
    val category: String, // "creature" or "scene"
    val swatchR: Int,
    val swatchG: Int,
    val swatchB: Int,
    val defaults: Map<String, Float>,
    val hideShared: Set<String> = emptySet(),
    val sections: List<AsciiControlSection> = emptyList()
) {
    val swatchColor: Color get() = Color(swatchR, swatchG, swatchB)
}

/**
 * Immutable persisted configuration for the ASCII Background Animation & Tweaks.
 * Always defaults to max ASCII detail (2.0f) and min Grid (0.0f).
 */
@Immutable
data class AsciiBackgroundConfig(
    val enabled: Boolean = true,
    val animalId: String = "butterflies",
    val detail: Float = MAX_ASCII_DETAIL,
    val grid: Float = MIN_ASCII_GRID,
    val hueShift: Float = 0f,
    val colorCycle: Boolean = false,
    val colorCycleSpeed: Float = 0.6f,
    val multiColorButterflies: Boolean = true,
    val params: Map<String, Float> = emptyMap()
) {
    fun valueFor(key: String, animalSpec: AsciiAnimalSpec = AsciiCatalog.byId(animalId)): Float {
        return when (key) {
            "detail" -> detail.coerceIn(0.5f, MAX_ASCII_DETAIL)
            "grid" -> grid.coerceIn(MIN_ASCII_GRID, 4.0f)
            "hueShift" -> hueShift.coerceIn(-180f, 180f)
            "colorCycle" -> if (colorCycle) 1f else 0f
            "colorCycleSpeed" -> colorCycleSpeed.coerceIn(0.1f, 3.0f)
            "multiColorButterflies" -> if (multiColorButterflies) 1f else 0f
            else -> params["${animalSpec.id}:$key"]
                ?: params[key]
                ?: animalSpec.defaults[key]
                ?: AsciiCatalog.SHARED_DEFAULTS[key]
                ?: 1f
        }
    }

    fun boolFor(key: String, animalSpec: AsciiAnimalSpec = AsciiCatalog.byId(animalId)): Boolean {
        return valueFor(key, animalSpec) >= 0.5f
    }
}

/**
 * Fast mutable tuning snapshot read by the 60fps simulation and renderer.
 * Avoids boxing or map lookups in tight inner loops.
 */
class AsciiTuning {
    // Shared Movement
    var density: Float = 1f
    var corner: Boolean = false
    var direction: Float = 49f
    var speed: Float = 1f
    var wind: Float = 0.25f
    var sway: Float = 0.04f
    var spread: Float = 0.06f
    var size: Float = 1f
    var farthest: Float = 0.45f
    var farBias: Float = 1.6f
    var grip: Float = 2f
    var separation: Float = 300f

    // Shared Look (Always defaults to max detail 2.0f and min grid 0.0f)
    var detail: Float = MAX_ASCII_DETAIL
    var grid: Float = MIN_ASCII_GRID
    var hueShift: Float = 0f
    var colorCycle: Boolean = false
    var colorCycleSpeed: Float = 0.6f
    var multiColorButterflies: Boolean = true
    var dayNight: Float = 0f
    var shimmer: Float = 0.3f
    var sparkle: Float = 0.12f
    var fog: Float = 0.75f
    var trail: Float = 1f
    var trailFade: Float = 1.3f
    var ripples: Float = 1f

    // Dynamic animal-specific parameters
    private val custom = HashMap<String, Float>(32)

    operator fun get(key: String, default: Float = 1f): Float = custom[key] ?: default

    fun bool(key: String, default: Boolean = false): Boolean {
        val v = custom[key] ?: return default
        return v >= 0.5f
    }

    fun syncFrom(config: AsciiBackgroundConfig, spec: AsciiAnimalSpec) {
        density = config.valueFor("density", spec)
        corner = config.boolFor("corner", spec)
        direction = config.valueFor("direction", spec)
        speed = config.valueFor("speed", spec)
        wind = config.valueFor("wind", spec)
        sway = config.valueFor("sway", spec)
        spread = config.valueFor("spread", spec)
        size = config.valueFor("size", spec)
        farthest = config.valueFor("farthest", spec)
        farBias = config.valueFor("farBias", spec)
        grip = config.valueFor("grip", spec)
        separation = config.valueFor("separation", spec)

        detail = config.detail.coerceIn(0.5f, MAX_ASCII_DETAIL)
        grid = config.grid.coerceIn(MIN_ASCII_GRID, 4.0f)
        hueShift = config.hueShift.coerceIn(-180f, 180f)
        colorCycle = config.colorCycle
        colorCycleSpeed = config.colorCycleSpeed.coerceIn(0.1f, 3.0f)
        multiColorButterflies = config.multiColorButterflies
        dayNight = config.valueFor("dayNight", spec)
        shimmer = config.valueFor("shimmer", spec)
        sparkle = config.valueFor("sparkle", spec)
        fog = config.valueFor("fog", spec)
        trail = config.valueFor("trail", spec)
        trailFade = config.valueFor("trailFade", spec)
        ripples = config.valueFor("ripples", spec)

        custom.clear()
        for ((k, v) in spec.defaults) {
            custom[k] = v
        }
        for (section in spec.sections) {
            for (ctrl in section.controls) {
                custom[ctrl.key] = config.valueFor(ctrl.key, spec)
            }
        }
    }
}

object AsciiCatalog {
    val SHARED_DEFAULTS: Map<String, Float> = mapOf(
        "density" to 1f,
        "corner" to 0f,
        "direction" to 49f,
        "speed" to 1f,
        "wind" to 0.25f,
        "sway" to 0.04f,
        "spread" to 0.06f,
        "size" to 1f,
        "farthest" to 0.45f,
        "farBias" to 1.6f,
        "grip" to 2f,
        "separation" to 300f,
        "detail" to MAX_ASCII_DETAIL,
        "grid" to MIN_ASCII_GRID,
        "hueShift" to 0f,
        "colorCycle" to 0f,
        "colorCycleSpeed" to 0.6f,
        "multiColorButterflies" to 1f,
        "dayNight" to 0f,
        "shimmer" to 0.3f,
        "sparkle" to 0.12f,
        "fog" to 0.75f,
        "trail" to 1f,
        "trailFade" to 1.3f,
        "ripples" to 1f
    )

    val LOOK_CONTROLS: List<AsciiControlSpec> = listOf(
        AsciiControlSpec("detail", "ASCII detail", 0.5f, MAX_ASCII_DETAIL, 0.05f, MAX_ASCII_DETAIL),
        AsciiControlSpec("grid", "Grid", MIN_ASCII_GRID, 4.0f, 0.05f, MIN_ASCII_GRID),
        AsciiControlSpec("dayNight", "Day / night cycle", 0f, 2f, 0.05f, 0f),
        AsciiControlSpec("shimmer", "Shimmer", 0f, 1f, 0.02f, 0.3f),
        AsciiControlSpec("sparkle", "Sparkle", 0f, 0.5f, 0.01f, 0.12f),
        AsciiControlSpec("fog", "Distance fade", 0f, 1f, 0.02f, 0.75f),
        AsciiControlSpec("trail", "Trail", 0f, 4f, 0.1f, 1f),
        AsciiControlSpec("trailFade", "Trail fade", 0.05f, 6f, 0.05f, 1.3f),
        AsciiControlSpec("ripples", "Water ripples", 0f, 2.5f, 0.05f, 1f)
    )

    val MOVEMENT_CONTROLS: List<AsciiControlSpec> = listOf(
        AsciiControlSpec("density", "Density", 0.1f, 4f, 0.1f, 1f),
        AsciiControlSpec("corner", "Corner to corner", 0f, 1f, 1f, 0f, isToggle = true),
        AsciiControlSpec("direction", "Direction (°)", 0f, 360f, 1f, 49f),
        AsciiControlSpec("speed", "Speed", 0.1f, 3f, 0.05f, 1f),
        AsciiControlSpec("wind", "Wind gusts", 0f, 2.5f, 0.05f, 0.25f),
        AsciiControlSpec("sway", "Sway", 0f, 0.6f, 0.02f, 0.04f),
        AsciiControlSpec("spread", "Spread", 0f, 0.8f, 0.02f, 0.06f),
        AsciiControlSpec("size", "Size", 0.3f, 2.5f, 0.05f, 1f),
        AsciiControlSpec("farthest", "Farthest depth", 0.1f, 1f, 0.05f, 0.45f),
        AsciiControlSpec("farBias", "Far bias", 0.3f, 3f, 0.1f, 1.6f),
        AsciiControlSpec("grip", "Hold course", 0.2f, 5f, 0.1f, 2f),
        AsciiControlSpec("separation", "Separation", 0f, 1000f, 10f, 300f)
    )

    private val SCENE_HIDDEN_MOVEMENT = setOf(
        "corner", "direction", "wind", "sway", "spread", "farthest", "farBias", "grip", "separation"
    )

    val ANIMALS: List<AsciiAnimalSpec> = listOf(
        // ---------------- 7 CREATURES ----------------
        AsciiAnimalSpec(
            id = "butterflies",
            name = "Butterfly",
            category = "creature",
            swatchR = 50, swatchG = 135, swatchB = 255,
            defaults = mapOf(
                "density" to 1f, "speed" to 1f, "wind" to 0.25f, "sway" to 0.04f,
                "spread" to 0.06f, "size" to 1f, "shimmer" to 0.3f, "trail" to 1f, "ripples" to 0.4f,
                "flutter" to 0f, "glide" to 2.75f, "flapRate" to 1f, "multiColorButterflies" to 1f
            ),
            sections = listOf(
                AsciiControlSection(
                    title = "Flight",
                    controls = listOf(
                        AsciiControlSpec("multiColorButterflies", "Multiple coloured butterflies", 0f, 1f, 1f, 1f, isToggle = true),
                        AsciiControlSpec("flutter", "Flutter", 0f, 3f, 0.05f, 0f),
                        AsciiControlSpec("glide", "Glide pause", 0f, 4f, 0.05f, 2.75f),
                        AsciiControlSpec("flapRate", "Wingbeat speed", 0.2f, 3f, 0.05f, 1f)
                    )
                )
            )
        ),
        AsciiAnimalSpec(
            id = "koi",
            name = "Koi",
            category = "creature",
            swatchR = 244, swatchG = 196, swatchB = 44,
            defaults = mapOf(
                "density" to 1f, "speed" to 0.75f, "wind" to 0.15f, "sway" to 0.18f,
                "spread" to 0.15f, "size" to 1f, "shimmer" to 0.32f, "sparkle" to 0.08f,
                "trail" to 1.4f, "trailFade" to 1.1f, "ripples" to 1.3f,
                "meander" to 0.4f, "tailBeat" to 1f, "foodRange" to 200f
            ),
            sections = listOf(
                AsciiControlSection(
                    title = "Swimming",
                    controls = listOf(
                        AsciiControlSpec("meander", "Lazy S-curves", 0f, 3f, 0.05f, 0.4f),
                        AsciiControlSpec("tailBeat", "Tail beat", 0.2f, 3f, 0.05f, 1f)
                    )
                ),
                AsciiControlSection(
                    title = "Feeding",
                    controls = listOf(
                        AsciiControlSpec("foodRange", "Food attract range", 0f, 500f, 10f, 200f)
                    )
                )
            )
        ),
        AsciiAnimalSpec(
            id = "jellyfish",
            name = "Jellyfish",
            category = "creature",
            swatchR = 232, swatchG = 56, swatchB = 182,
            defaults = mapOf(
                "density" to 1f, "corner" to 0f, "direction" to 65f, "speed" to 0.55f,
                "wind" to 0.2f, "sway" to 0.15f, "spread" to 0.12f, "size" to 1f,
                "shimmer" to 0.45f, "sparkle" to 0.15f, "trail" to 1.5f, "trailFade" to 0.9f, "ripples" to 0.6f,
                "pulseRate" to 1f, "pulsePower" to 1f, "pulseGlow" to 1f, "tentacles" to 1f
            ),
            sections = listOf(
                AsciiControlSection(
                    title = "Bell & tentacles",
                    controls = listOf(
                        AsciiControlSpec("pulseRate", "Pulse rate", 0.2f, 3f, 0.05f, 1f),
                        AsciiControlSpec("pulsePower", "Jet surge", 0.2f, 3f, 0.05f, 1f),
                        AsciiControlSpec("pulseGlow", "Pulse glow", 0f, 3f, 0.05f, 1f),
                        AsciiControlSpec("tentacles", "Tentacle length", 0f, 2.5f, 0.05f, 1f)
                    )
                )
            )
        ),
        AsciiAnimalSpec(
            id = "frogs",
            name = "Frog",
            category = "creature",
            swatchR = 104, swatchG = 184, swatchB = 76,
            defaults = mapOf(
                "density" to 1f, "speed" to 1f, "wind" to 0f, "sway" to 0.02f,
                "spread" to 0.12f, "size" to 1f, "shimmer" to 0.22f, "sparkle" to 0.08f,
                "trail" to 1.1f, "trailFade" to 1.5f, "ripples" to 1.8f,
                "hop" to 1f, "leap" to 0.45f, "height" to 1f
            ),
            sections = listOf(
                AsciiControlSection(
                    title = "Jumping",
                    controls = listOf(
                        AsciiControlSpec("hop", "Rest between hops", 0.2f, 4f, 0.05f, 1f),
                        AsciiControlSpec("leap", "Leap speed", 0.3f, 2.5f, 0.05f, 0.45f),
                        AsciiControlSpec("height", "Jump arc", 0f, 2.5f, 0.05f, 1f)
                    )
                )
            )
        ),
        AsciiAnimalSpec(
            id = "cranes",
            name = "Crane",
            category = "creature",
            swatchR = 214, swatchG = 220, swatchB = 238,
            defaults = mapOf(
                "density" to 1f, "speed" to 0.9f, "wind" to 0.15f, "sway" to 0.03f,
                "spread" to 0.04f, "size" to 1f, "shimmer" to 0.2f, "sparkle" to 0.06f,
                "trail" to 0.6f, "trailFade" to 1.8f, "ripples" to 0.3f,
                "wingbeat" to 1f, "glide" to 1f, "foldFlap" to 0f
            ),
            sections = listOf(
                AsciiControlSection(
                    title = "Flight",
                    controls = listOf(
                        AsciiControlSpec("wingbeat", "Wingbeat speed", 0.3f, 2.5f, 0.05f, 1f),
                        AsciiControlSpec("glide", "Glide length", 0f, 4f, 0.1f, 1f),
                        AsciiControlSpec("foldFlap", "Wrist-folding flap", 0f, 1f, 1f, 0f, isToggle = true)
                    )
                )
            )
        ),
        AsciiAnimalSpec(
            id = "crabs",
            name = "Crab",
            category = "creature",
            swatchR = 204, swatchG = 82, swatchB = 52,
            defaults = mapOf(
                "density" to 1f, "speed" to 0.9f, "wind" to 0f, "sway" to 0.04f,
                "spread" to 0.15f, "size" to 1f, "shimmer" to 0.25f, "sparkle" to 0.06f,
                "trail" to 0.8f, "trailFade" to 1.1f, "ripples" to 1f,
                "burst" to 1f, "rest" to 1f, "legSpeed" to 1f, "scare" to 0.55f
            ),
            sections = listOf(
                AsciiControlSection(
                    title = "Scuttling",
                    controls = listOf(
                        AsciiControlSpec("burst", "Run distance", 0.2f, 4f, 0.1f, 1f),
                        AsciiControlSpec("rest", "Pause duration", 0f, 4f, 0.1f, 1f),
                        AsciiControlSpec("legSpeed", "Leg speed", 0.2f, 3f, 0.05f, 1f),
                        AsciiControlSpec("scare", "Claw-up alertness", 0f, 3f, 0.05f, 0.55f)
                    )
                )
            )
        ),
        AsciiAnimalSpec(
            id = "whale",
            name = "Whale",
            category = "creature",
            swatchR = 78, swatchG = 168, swatchB = 238,
            defaults = mapOf(
                "density" to 1.1f, "speed" to 0.85f, "wind" to 0.1f, "sway" to 0.08f,
                "spread" to 0.12f, "size" to 1f, "shimmer" to 0.4f, "sparkle" to 0.14f,
                "trail" to 1.3f, "trailFade" to 1.1f, "ripples" to 1f,
                "whaleSize" to 1.25f, "partDistance" to 1.25f, "whaleSpeed" to 0.9f
            ),
            sections = listOf(
                AsciiControlSection(
                    title = "Abyssal Giant & Shoal",
                    controls = listOf(
                        AsciiControlSpec("whaleSize", "Whale scale", 0.6f, 2.2f, 0.05f, 1.25f),
                        AsciiControlSpec("partDistance", "Shoal parting", 0.5f, 2.2f, 0.05f, 1.25f),
                        AsciiControlSpec("whaleSpeed", "Whale glide", 0.3f, 2f, 0.05f, 0.9f)
                    )
                )
            )
        ),

        // ---------------- 4 SCENES ----------------
        AsciiAnimalSpec(
            id = "rain",
            name = "Rain",
            category = "scene",
            swatchR = 94, swatchG = 196, swatchB = 255,
            defaults = mapOf(
                "density" to 1.15f, "speed" to 1f, "size" to 1f,
                "shimmer" to 0.45f, "sparkle" to 0.22f, "trail" to 2.2f, "trailFade" to 0.85f, "ripples" to 1.2f,
                "downpour" to 1.2f, "mergeRate" to 1.15f, "refraction" to 1.35f
            ),
            hideShared = SCENE_HIDDEN_MOVEMENT,
            sections = listOf(
                AsciiControlSection(
                    title = "Window Glass",
                    controls = listOf(
                        AsciiControlSpec("downpour", "Rain intensity", 0.2f, 3f, 0.05f, 1.2f),
                        AsciiControlSpec("mergeRate", "Droplet merging", 0.3f, 2.5f, 0.05f, 1.15f),
                        AsciiControlSpec("refraction", "Bokeh glow", 0.2f, 2.5f, 0.05f, 1.35f)
                    )
                )
            )
        ),
        AsciiAnimalSpec(
            id = "snowfall",
            name = "Snowfall",
            category = "scene",
            swatchR = 214, swatchG = 236, swatchB = 255,
            defaults = mapOf(
                "density" to 1.2f, "speed" to 0.9f, "size" to 1f,
                "shimmer" to 0.4f, "sparkle" to 0.22f, "trail" to 1.1f, "trailFade" to 1.4f, "ripples" to 0.3f,
                "gustStrength" to 1.35f, "drift" to 1.2f, "crystalDetail" to 1.15f
            ),
            hideShared = SCENE_HIDDEN_MOVEMENT,
            sections = listOf(
                AsciiControlSection(
                    title = "Winter Flurry",
                    controls = listOf(
                        AsciiControlSpec("gustStrength", "Wind gust swirl", 0.2f, 3f, 0.05f, 1.35f),
                        AsciiControlSpec("drift", "Lateral flutter", 0f, 2.5f, 0.05f, 1.2f),
                        AsciiControlSpec("crystalDetail", "Foreground crystals", 0.5f, 2f, 0.05f, 1.15f)
                    )
                )
            )
        ),
        AsciiAnimalSpec(
            id = "waves",
            name = "Ocean Waves",
            category = "scene",
            swatchR = 56, swatchG = 178, swatchB = 232,
            defaults = mapOf(
                "density" to 1f, "speed" to 1f, "size" to 1f,
                "detail" to MAX_ASCII_DETAIL, "grid" to MIN_ASCII_GRID,
                "shimmer" to 0.45f, "sparkle" to 0.2f, "trail" to 1.3f, "trailFade" to 1.2f, "ripples" to 1.4f,
                "swellHeight" to 1.2f, "foam" to 1.15f, "ringStrength" to 1.35f
            ),
            hideShared = SCENE_HIDDEN_MOVEMENT,
            sections = listOf(
                AsciiControlSection(
                    title = "Swell & Surf",
                    controls = listOf(
                        AsciiControlSpec("swellHeight", "Swell amplitude", 0.3f, 2.5f, 0.05f, 1.2f),
                        AsciiControlSpec("foam", "Whitecap foam", 0f, 2.5f, 0.05f, 1.15f),
                        AsciiControlSpec("ringStrength", "Splash wave rings", 0.3f, 2.5f, 0.05f, 1.35f)
                    )
                )
            )
        ),
        AsciiAnimalSpec(
            id = "aurora",
            name = "Aurora",
            category = "scene",
            swatchR = 64, swatchG = 242, swatchB = 168,
            defaults = mapOf(
                "density" to 1f, "speed" to 1f, "size" to 1f,
                "detail" to MAX_ASCII_DETAIL, "grid" to MIN_ASCII_GRID,
                "shimmer" to 0.5f, "sparkle" to 0.25f, "trail" to 1.8f, "trailFade" to 1.1f, "ripples" to 0.5f,
                "ribbons" to 3f, "curtainWave" to 1.15f, "coronaGlow" to 1.2f
            ),
            hideShared = SCENE_HIDDEN_MOVEMENT,
            sections = listOf(
                AsciiControlSection(
                    title = "Polar Sky",
                    controls = listOf(
                        AsciiControlSpec("ribbons", "Ribbon curtains", 1f, 5f, 1f, 3f),
                        AsciiControlSpec("curtainWave", "Pleat fold wave", 0.3f, 2.5f, 0.05f, 1.15f),
                        AsciiControlSpec("coronaGlow", "Solar intensity", 0.2f, 2.5f, 0.05f, 1.2f)
                    )
                )
            )
        )
    )

    val CREATURES: List<AsciiAnimalSpec> = ANIMALS.filter { it.category == "creature" }
    val SCENES: List<AsciiAnimalSpec> = ANIMALS.filter { it.category == "scene" }

    fun byId(id: String): AsciiAnimalSpec =
        ANIMALS.firstOrNull { it.id == id } ?: ANIMALS.first()
}
