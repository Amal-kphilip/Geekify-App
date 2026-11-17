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
    val trailDarkR: Int = 150,
    val trailDarkG: Int = 160,
    val trailDarkB: Int = 200,
    val defaults: Map<String, Float>,
    val hideShared: Set<String> = emptySet(),
    val sections: List<AsciiControlSection> = emptyList()
) {
    val hide: Set<String> get() = hideShared
    val swatchColor: Color get() = Color(swatchR, swatchG, swatchB)
    val trailColor: Color get() = Color(trailDarkR, trailDarkG, trailDarkB)
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
    val multiColorButterflies: Boolean = false,
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
    var multiColorButterflies: Boolean = false
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

    val ANIMALS: List<AsciiAnimalSpec> = listOf(
        // ---------------- 7 CREATURES ----------------
        AsciiAnimalSpec(
            id = "butterflies",
            name = "Butterfly",
            category = "creature",
            swatchR = 50, swatchG = 135, swatchB = 255,
            trailDarkR = 96, trailDarkG = 156, trailDarkB = 255,
            defaults = mapOf(
                "density" to 1.05f, "speed" to 1.05f, "sway" to 0f,
                "spread" to 0f, "size" to 1.25f, "farthest" to 0.18f, "farBias" to 1.7f,
                "grip" to 5f, "separation" to 700f, "trail" to 1.55f, "trailFade" to 0.15f,
                "flutter" to 0f, "glide" to 2.75f, "flapRate" to 1f
            ),
            hideShared = emptySet(),
            sections = listOf(
                AsciiControlSection(
                    title = "Flight",
                    controls = listOf(
                        AsciiControlSpec("flutter", "Flutter", 0f, 3f, 0.05f, 0f),
                        AsciiControlSpec("glide", "Glide", 0f, 4f, 0.05f, 2.75f),
                        AsciiControlSpec("flapRate", "Flap rate", 0.2f, 3f, 0.05f, 1f)
                    )
                )
            )
        ),
        AsciiAnimalSpec(
            id = "koi",
            name = "Koi",
            category = "creature",
            swatchR = 244, swatchG = 196, swatchB = 44,
            trailDarkR = 110, trailDarkG = 170, trailDarkB = 220,
            defaults = mapOf(
                "fog" to 0.64f, "density" to 1.6f, "speed" to 1.6f, "sway" to 0f,
                "spread" to 0f, "farthest" to 0.33f, "farBias" to 1.3f,
                "grip" to 2.65f, "separation" to 780f, "trail" to 4f, "trailFade" to 0.4f,
                "shimmer" to 0.35f, "sparkle" to 0.08f,
                "meander" to 0.4f, "tailBeat" to 1f, "foodRange" to 200f
            ),
            hideShared = emptySet(),
            sections = listOf(
                AsciiControlSection(
                    title = "Swimming",
                    controls = listOf(
                        AsciiControlSpec("meander", "Meander", 0f, 3f, 0.05f, 0.4f),
                        AsciiControlSpec("tailBeat", "Tail beat", 0.2f, 3f, 0.05f, 1f)
                    )
                ),
                AsciiControlSection(
                    title = "Feeding",
                    controls = listOf(
                        AsciiControlSpec("foodRange", "Food range", 0f, 500f, 10f, 200f)
                    )
                )
            )
        ),
        AsciiAnimalSpec(
            id = "jellyfish",
            name = "Jellyfish",
            category = "creature",
            swatchR = 232, swatchG = 56, swatchB = 182,
            trailDarkR = 255, trailDarkG = 130, trailDarkB = 214,
            defaults = mapOf(
                "speed" to 1.05f, "sway" to 0.04f, "spread" to 0.08f,
                "farthest" to 0.3f, "farBias" to 1.4f, "grip" to 1.4f,
                "trail" to 0.8f, "trailFade" to 0.6f, "sparkle" to 0.1f,
                "pulseRate" to 1f, "pulsePower" to 1f, "pulseGlow" to 1f, "tentacles" to 1f
            ),
            hideShared = setOf("shimmer"),
            sections = listOf(
                AsciiControlSection(
                    title = "Swimming",
                    controls = listOf(
                        AsciiControlSpec("pulseRate", "Pulse rate", 0.2f, 3f, 0.05f, 1f),
                        AsciiControlSpec("pulsePower", "Pulse power", 0.2f, 3f, 0.05f, 1f),
                        AsciiControlSpec("pulseGlow", "Pulse glow", 0f, 3f, 0.05f, 1f),
                        AsciiControlSpec("tentacles", "Arm length", 0f, 2.5f, 0.05f, 1f)
                    )
                )
            )
        ),
        AsciiAnimalSpec(
            id = "frogs",
            name = "Frog",
            category = "creature",
            swatchR = 104, swatchG = 184, swatchB = 76,
            trailDarkR = 110, trailDarkG = 200, trailDarkB = 220,
            defaults = mapOf(
                "fog" to 0f, "size" to 1.2f, "density" to 1.3f, "sway" to 0f,
                "spread" to 0f, "farthest" to 0.35f, "farBias" to 1.4f, "separation" to 450f,
                "trail" to 3.45f, "trailFade" to 0.4f, "shimmer" to 0.15f, "sparkle" to 0.05f,
                "hop" to 1f, "leap" to 0.45f, "height" to 1f
            ),
            hideShared = setOf("grip"),
            sections = listOf(
                AsciiControlSection(
                    title = "Leaping",
                    controls = listOf(
                        AsciiControlSpec("hop", "How often", 0.2f, 4f, 0.05f, 1f),
                        AsciiControlSpec("leap", "Leap length", 0.3f, 2.5f, 0.05f, 0.45f),
                        AsciiControlSpec("height", "Leap height", 0f, 2.5f, 0.05f, 1f)
                    )
                )
            )
        ),
        AsciiAnimalSpec(
            id = "cranes",
            name = "Crane",
            category = "creature",
            swatchR = 214, swatchG = 220, swatchB = 238,
            trailDarkR = 200, trailDarkG = 204, trailDarkB = 226,
            defaults = mapOf(
                "detail" to 1.7f, "density" to 0.8f, "speed" to 1.5f, "sway" to 0f, "spread" to 0f,
                "farthest" to 0.35f, "farBias" to 1.3f, "grip" to 3f,
                "separation" to 150f, "trail" to 0f, "shimmer" to 0.1f, "sparkle" to 0.04f,
                "wingbeat" to 1f, "glide" to 1f, "foldFlap" to 0f
            ),
            hideShared = emptySet(),
            sections = listOf(
                AsciiControlSection(
                    title = "Flight",
                    controls = listOf(
                        AsciiControlSpec("wingbeat", "Wingbeat", 0.3f, 2.5f, 0.05f, 1f),
                        AsciiControlSpec("glide", "Glide", 0f, 4f, 0.05f, 1f),
                        AsciiControlSpec("foldFlap", "Folding flap", 0f, 1f, 1f, 0f, isToggle = true)
                    )
                )
            )
        ),
        AsciiAnimalSpec(
            id = "crabs",
            name = "Crab",
            category = "creature",
            swatchR = 204, swatchG = 82, swatchB = 52,
            trailDarkR = 206, trailDarkG = 178, trailDarkB = 136,
            defaults = mapOf(
                "detail" to 1.7f, "fog" to 0.12f, "density" to 1.8f, "sway" to 0f,
                "spread" to 0f, "farthest" to 0.25f, "farBias" to 0.7f,
                "grip" to 3f, "separation" to 400f, "trail" to 1.2f, "trailFade" to 0.35f,
                "shimmer" to 0.1f, "sparkle" to 0.03f,
                "burst" to 1f, "rest" to 1f, "legSpeed" to 1f, "scare" to 0.55f
            ),
            hideShared = emptySet(),
            sections = listOf(
                AsciiControlSection(
                    title = "Scuttling",
                    controls = listOf(
                        AsciiControlSpec("burst", "Burst length", 0.2f, 4f, 0.05f, 1f),
                        AsciiControlSpec("rest", "Pause length", 0f, 4f, 0.05f, 1f),
                        AsciiControlSpec("legSpeed", "Leg speed", 0.2f, 3f, 0.05f, 1f),
                        AsciiControlSpec("scare", "Scare", 0f, 3f, 0.05f, 0.55f)
                    )
                )
            )
        ),
        AsciiAnimalSpec(
            id = "whale",
            name = "Whale",
            category = "creature",
            swatchR = 78, swatchG = 168, swatchB = 238,
            trailDarkR = 110, trailDarkG = 190, trailDarkB = 245,
            defaults = mapOf(
                "density" to 1f, "speed" to 1f, "size" to 0.65f, "farthest" to 0.35f,
                "farBias" to 1.4f, "grip" to 2.4f, "separation" to 190f,
                "trail" to 1.5f, "trailFade" to 0.75f, "shimmer" to 0.4f, "sparkle" to 0.15f,
                "whaleSize" to 1.25f, "partDistance" to 1.25f, "whaleSpeed" to 0.9f
            ),
            hideShared = emptySet(),
            sections = listOf(
                AsciiControlSection(
                    title = "Whale & Shoal",
                    controls = listOf(
                        AsciiControlSpec("whaleSize", "Whale scale", 0.6f, 2.2f, 0.05f, 1.25f),
                        AsciiControlSpec("partDistance", "Parting wake", 0.5f, 2.2f, 0.05f, 1.25f),
                        AsciiControlSpec("whaleSpeed", "Whale pace", 0.3f, 2f, 0.05f, 0.9f)
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
            trailDarkR = 130, trailDarkG = 205, trailDarkB = 255,
            defaults = mapOf(
                "direction" to 270f, "density" to 1.4f, "speed" to 1f, "size" to 1f,
                "farthest" to 0.35f, "farBias" to 1.4f,
                "trail" to 2.2f, "trailFade" to 0.45f, "shimmer" to 0.4f, "sparkle" to 0.22f,
                "downpour" to 1.2f, "mergeRate" to 1.15f, "refraction" to 1.35f
            ),
            hideShared = setOf("corner", "direction", "grip", "separation"),
            sections = listOf(
                AsciiControlSection(
                    title = "Window Glass",
                    controls = listOf(
                        AsciiControlSpec("downpour", "Rain intensity", 0.2f, 3f, 0.05f, 1.2f),
                        AsciiControlSpec("mergeRate", "Drop merging", 0.3f, 2.5f, 0.05f, 1.15f),
                        AsciiControlSpec("refraction", "Lens refraction", 0.2f, 2.5f, 0.05f, 1.35f)
                    )
                )
            )
        ),
        AsciiAnimalSpec(
            id = "snowfall",
            name = "Snowfall",
            category = "scene",
            swatchR = 214, swatchG = 236, swatchB = 255,
            trailDarkR = 195, trailDarkG = 224, trailDarkB = 255,
            defaults = mapOf(
                "direction" to 258f, "density" to 1.6f, "speed" to 1f, "size" to 1.1f,
                "farthest" to 0.14f, "farBias" to 1.85f, "grip" to 1.8f, "separation" to 60f,
                "trail" to 0.9f, "trailFade" to 1.8f, "shimmer" to 0.4f, "sparkle" to 0.25f, "wind" to 0.45f,
                "gustStrength" to 1.35f, "drift" to 1.2f, "crystalDetail" to 1.15f
            ),
            hideShared = emptySet(),
            sections = listOf(
                AsciiControlSection(
                    title = "Blizzard & Layers",
                    controls = listOf(
                        AsciiControlSpec("gustStrength", "Click gust", 0.2f, 3f, 0.05f, 1.35f),
                        AsciiControlSpec("drift", "Sideways flutter", 0f, 2.5f, 0.05f, 1.2f),
                        AsciiControlSpec("crystalDetail", "Crystal size", 0.5f, 2f, 0.05f, 1.15f)
                    )
                )
            )
        ),
        AsciiAnimalSpec(
            id = "waves",
            name = "Ocean Waves",
            category = "scene",
            swatchR = 56, swatchG = 178, swatchB = 232,
            trailDarkR = 125, trailDarkG = 215, trailDarkB = 255,
            defaults = mapOf(
                "speed" to 1f, "trail" to 1.2f, "trailFade" to 0.9f,
                "shimmer" to 0.4f, "sparkle" to 0.2f,
                "swellHeight" to 1.2f, "foam" to 1.15f, "ringStrength" to 1.35f
            ),
            hideShared = setOf("density", "corner", "direction", "sway", "spread", "size", "farthest", "farBias", "grip", "separation"),
            sections = listOf(
                AsciiControlSection(
                    title = "Swell & Stones",
                    controls = listOf(
                        AsciiControlSpec("swellHeight", "Swell height", 0.3f, 2.5f, 0.05f, 1.2f),
                        AsciiControlSpec("foam", "Whitecap foam", 0f, 2.5f, 0.05f, 1.15f),
                        AsciiControlSpec("ringStrength", "Stone rings", 0.3f, 2.5f, 0.05f, 1.35f)
                    )
                )
            )
        ),
        AsciiAnimalSpec(
            id = "aurora",
            name = "Aurora",
            category = "scene",
            swatchR = 64, swatchG = 242, swatchB = 168,
            trailDarkR = 80, trailDarkG = 245, trailDarkB = 175,
            defaults = mapOf(
                "density" to 1f, "speed" to 1f, "trail" to 0.6f, "trailFade" to 1.5f,
                "shimmer" to 0.45f, "sparkle" to 0.2f,
                "ribbons" to 3f, "curtainWave" to 1.15f, "coronaGlow" to 1.2f
            ),
            hideShared = setOf("density", "corner", "direction", "sway", "spread", "size", "farthest", "farBias", "grip", "separation"),
            sections = listOf(
                AsciiControlSection(
                    title = "Polar Sky",
                    controls = listOf(
                        AsciiControlSpec("ribbons", "Ribbon bands", 1f, 5f, 1f, 3f),
                        AsciiControlSpec("curtainWave", "Curtain folds", 0.3f, 2.5f, 0.05f, 1.15f),
                        AsciiControlSpec("coronaGlow", "Violet corona", 0.2f, 2.5f, 0.05f, 1.2f)
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
