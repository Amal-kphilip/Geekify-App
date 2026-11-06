package com.geekify.android.domain.recommend.engine

import com.geekify.android.domain.listening.ListeningLanguageClassifier
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

enum class MusicGenre(val displayName: String) {
    POP("Pop"),
    ROCK("Rock"),
    HIP_HOP("Hip-hop"),
    RAP("Rap"),
    RNB("R&B"),
    CLASSICAL("Classical"),
    ELECTRONIC("Electronic"),
    DANCE("Dance"),
    JAZZ("Jazz"),
    LO_FI("Lo-fi"),
    AMBIENT("Ambient"),
    INDIE("Indie"),
    FOLK("Folk"),
    DEVOTIONAL("Devotional"),
    FILM_MUSIC("Film music"),
    ACOUSTIC("Acoustic"),
    METAL("Metal"),
    ALTERNATIVE("Alternative"),
    REGIONAL_TRADITIONAL("Regional music traditions")
}

enum class MusicMood(val displayName: String) {
    HAPPY("Happy"),
    SAD("Sad"),
    MELANCHOLIC("Melancholic"),
    ROMANTIC("Romantic"),
    CALM("Calm"),
    PEACEFUL("Peaceful"),
    ENERGETIC("Energetic"),
    AGGRESSIVE("Aggressive"),
    MOTIVATIONAL("Motivational"),
    NOSTALGIC("Nostalgic"),
    DREAMY("Dreamy"),
    ATMOSPHERIC("Atmospheric"),
    DARK("Dark"),
    UPLIFTING("Uplifting"),
    RELAXING("Relaxing"),
    EMOTIONAL("Emotional")
}

enum class ListeningContext(val displayName: String) {
    MORNING("Morning"),
    AFTERNOON("Afternoon"),
    EVENING("Evening"),
    LATE_NIGHT("Late night"),
    STUDY("Study"),
    RELAXATION("Relaxation"),
    WORKOUT("Workout"),
    TRAVEL("Travel"),
    PARTY("Party")
}

data class TrackClassification(
    val genres: Map<MusicGenre, Double>,
    val moods: Map<MusicMood, Double>,
    val primaryGenre: MusicGenre?,
    val primaryMood: MusicMood?
)

@Singleton
class MusicClassificationService @Inject constructor() {
    private val wordRegex = Regex("[\\p{L}\\p{N}_-]+")

    fun classify(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
        tempoBpm: Double? = null,
        energy: Double? = null,
        acousticness: Double? = null
    ): TrackClassification {
        val text = "${title.lowercase(Locale.ROOT)} ${artist.lowercase(Locale.ROOT)} ${album.orEmpty().lowercase(Locale.ROOT)}"
        val tokens = wordRegex.findAll(text).map { it.value }.toSet()
        val detectedLang = ListeningLanguageClassifier.classifyForRecommender(title, artist)

        val genreScores = mutableMapOf<MusicGenre, Double>()
        fun addGenre(genre: MusicGenre, delta: Double) {
            genreScores[genre] = ((genreScores[genre] ?: 0.0) + delta).coerceIn(0.0, 1.0)
        }

        // Multi-label genre scoring
        if (tokens.any { it in setOf("lofi", "lo-fi", "chillhop", "study", "chill") }) {
            addGenre(MusicGenre.LO_FI, 0.85)
            addGenre(MusicGenre.AMBIENT, 0.45)
        }
        if (tokens.any { it in setOf("acoustic", "unplugged", "guitar", "piano", "stripped") } || (acousticness != null && acousticness >= 0.7)) {
            addGenre(MusicGenre.ACOUSTIC, 0.82)
            addGenre(MusicGenre.INDIE, 0.35)
        }
        if (tokens.any { it in setOf("hip", "hop", "hip-hop", "trap", "drill", "cypher", "freestyle", "dabzee", "fejo", "vedan", "thirumaali", "divine", "raftaar", "badshah", "eminem", "drake", "kendrick") }) {
            addGenre(MusicGenre.HIP_HOP, 0.85)
            addGenre(MusicGenre.RAP, 0.78)
        }
        if (tokens.any { it in setOf("edm", "electronic", "synth", "techno", "house", "trance", "dubstep", "electro") }) {
            addGenre(MusicGenre.ELECTRONIC, 0.85)
            addGenre(MusicGenre.DANCE, 0.65)
        }
        if (tokens.any { it in setOf("dance", "party", "club", "remix", "kuthu", "dappankuthu", "bhangra", "peppy", "disco") }) {
            addGenre(MusicGenre.DANCE, 0.80)
            addGenre(MusicGenre.POP, 0.45)
        }
        if (tokens.any { it in setOf("metal", "heavy", "thrash", "metalcore", "deathcore", "doom", "slayer", "metallica", "slipknot") }) {
            addGenre(MusicGenre.METAL, 0.90)
            addGenre(MusicGenre.ROCK, 0.65)
        }
        if (tokens.any { it in setOf("rock", "band", "grunge", "punk", "agam", "avial", "thaikkudam", "motherjane") }) {
            addGenre(MusicGenre.ROCK, 0.82)
            addGenre(MusicGenre.ALTERNATIVE, 0.50)
        }
        if (tokens.any { it in setOf("indie", "independent", "when", "chai", "met", "toast", "prateek", "kuhad", "anuv", "jain", "job", "kurian") }) {
            addGenre(MusicGenre.INDIE, 0.82)
            addGenre(MusicGenre.ALTERNATIVE, 0.45)
        }
        if (tokens.any { it in setOf("classical", "carnatic", "hindustani", "raaga", "raga", "kriti", "thillana", "symphony", "orchestra") }) {
            addGenre(MusicGenre.CLASSICAL, 0.88)
            addGenre(MusicGenre.REGIONAL_TRADITIONAL, 0.55)
        }
        if (tokens.any { it in setOf("folk", "naadan", "nadan", "oppana", "mappila", "baul", "garba", "lavani", "villupattu") }) {
            addGenre(MusicGenre.FOLK, 0.86)
            addGenre(MusicGenre.REGIONAL_TRADITIONAL, 0.72)
        }
        if (tokens.any { it in setOf("devotional", "bhajan", "mantra", "aarti", "qawwali", "sufi", "gospel", "christian", "keerthanam") }) {
            addGenre(MusicGenre.DEVOTIONAL, 0.88)
            addGenre(MusicGenre.REGIONAL_TRADITIONAL, 0.45)
        }
        if (tokens.any { it in setOf("jazz", "blues", "sax", "swing", "bossa") }) {
            addGenre(MusicGenre.JAZZ, 0.85)
        }
        if (tokens.any { it in setOf("rnb", "r&b", "soul", "weeknd", "sza", "usher") }) {
            addGenre(MusicGenre.RNB, 0.82)
            addGenre(MusicGenre.POP, 0.45)
        }
        if (tokens.any { it in setOf("ambient", "drone", "soundscape", "meditation", "sleep") }) {
            addGenre(MusicGenre.AMBIENT, 0.85)
        }

        // Indian film / regional soundtrack traditions
        val regionalLanguages = setOf("Malayalam", "Tamil", "Hindi", "Telugu", "Kannada", "Bengali", "Marathi", "Gujarati", "Odia", "Punjabi")
        if (detectedLang in regionalLanguages || tokens.any { it in setOf("ost", "soundtrack", "from", "movie", "film") }) {
            addGenre(MusicGenre.FILM_MUSIC, 0.74)
            addGenre(MusicGenre.REGIONAL_TRADITIONAL, 0.52)
        }
        if (genreScores.isEmpty()) {
            addGenre(MusicGenre.POP, 0.65)
        }

        // Multi-dimensional mood scoring (never assuming "Night" in a Metal/Rock track means calm/sleepy!)
        val moodScores = mutableMapOf<MusicMood, Double>()
        fun addMood(mood: MusicMood, delta: Double) {
            moodScores[mood] = ((moodScores[mood] ?: 0.0) + delta).coerceIn(0.0, 1.0)
        }

        val isHeavyGenre = (genreScores[MusicGenre.METAL] ?: 0.0) >= 0.6 ||
            (genreScores[MusicGenre.HIP_HOP] ?: 0.0) >= 0.7 ||
            (genreScores[MusicGenre.DANCE] ?: 0.0) >= 0.7

        if ((genreScores[MusicGenre.METAL] ?: 0.0) >= 0.6 || tokens.any { it in setOf("rage", "beast", "hukum", "aavesham", "illuminati", "monster", "fire", "roar") }) {
            addMood(MusicMood.AGGRESSIVE, 0.78)
            addMood(MusicMood.ENERGETIC, 0.88)
            addMood(MusicMood.MOTIVATIONAL, 0.62)
        }
        if (tokens.any { it in setOf("love", "romantic", "pranayam", "kadhal", "kaadhal", "pyaar", "ishq", "mohabbat", "prema", "sneham", "uyire", "kanmani", "kesariya", "pavizha") }) {
            addMood(MusicMood.ROMANTIC, 0.86)
            addMood(MusicMood.EMOTIONAL, 0.64)
            addMood(MusicMood.DREAMY, 0.48)
        }
        if (tokens.any { it in setOf("sad", "heartbreak", "viraham", "pain", "tears", "alone", "broken", "vidaparayukayano", "kanneer", "judai", "dard") }) {
            addMood(MusicMood.SAD, 0.85)
            addMood(MusicMood.MELANCHOLIC, 0.82)
            addMood(MusicMood.EMOTIONAL, 0.78)
        }
        if (tokens.any { it in setOf("nostalgia", "old", "evergreen", "ormakal", "orma", "memories", "rewind", "golden", "classic", "90s", "80s") }) {
            addMood(MusicMood.NOSTALGIC, 0.84)
            addMood(MusicMood.EMOTIONAL, 0.52)
        }
        if (!isHeavyGenre && (tokens.any { it in setOf("calm", "peaceful", "lofi", "lo-fi", "nilavu", "nilave", "mazha", "vennila", "breeze", "pulari", "acoustic", "unplugged", "ambient") } || (genreScores[MusicGenre.LO_FI] ?: 0.0) >= 0.6)) {
            addMood(MusicMood.CALM, 0.82)
            addMood(MusicMood.RELAXING, 0.80)
            addMood(MusicMood.PEACEFUL, 0.76)
            addMood(MusicMood.DREAMY, 0.64)
            addMood(MusicMood.ATMOSPHERIC, 0.58)
        }
        if (tokens.any { it in setOf("party", "dance", "celebration", "happy", "joy", "vibe", "kuthu", "bhangra", "peppy", "thallumaala") }) {
            addMood(MusicMood.HAPPY, 0.82)
            addMood(MusicMood.ENERGETIC, 0.86)
            addMood(MusicMood.UPLIFTING, 0.75)
        }
        if (tokens.any { it in setOf("workout", "gym", "motivation", "unstoppable", "rise", "power", "victory", "anthem") }) {
            addMood(MusicMood.MOTIVATIONAL, 0.88)
            addMood(MusicMood.ENERGETIC, 0.85)
            addMood(MusicMood.UPLIFTING, 0.74)
        }
        if (tokens.any { it in setOf("dark", "shadow", "midnight", "phonk", "afterhours") }) {
            addMood(MusicMood.DARK, 0.80)
            addMood(MusicMood.ATMOSPHERIC, 0.72)
        }

        // Incorporate audio features when present
        if (energy != null) {
            if (energy >= 0.75) addMood(MusicMood.ENERGETIC, 0.45)
            if (energy <= 0.38 && !isHeavyGenre) {
                addMood(MusicMood.CALM, 0.45)
                addMood(MusicMood.RELAXING, 0.45)
            }
        }
        if (tempoBpm != null) {
            if (tempoBpm >= 125.0) addMood(MusicMood.ENERGETIC, 0.35)
            if (tempoBpm <= 92.0 && !isHeavyGenre) addMood(MusicMood.MELANCHOLIC, 0.30)
        }

        if (moodScores.isEmpty()) {
            addMood(MusicMood.UPLIFTING, 0.55)
            addMood(MusicMood.EMOTIONAL, 0.45)
        }

        return TrackClassification(
            genres = genreScores,
            moods = moodScores,
            primaryGenre = genreScores.maxByOrNull { it.value }?.key,
            primaryMood = moodScores.maxByOrNull { it.value }?.key
        )
    }
}

@Singleton
class RecommendationContextEngine @Inject constructor(
    private val classificationService: MusicClassificationService
) {
    fun inferContextFromHour(hourOfDay: Int?): ListeningContext = when (hourOfDay) {
        in 5..11 -> ListeningContext.MORNING
        in 12..16 -> ListeningContext.AFTERNOON
        in 17..21 -> ListeningContext.EVENING
        else -> ListeningContext.LATE_NIGHT
    }

    /**
     * Computes contextual alignment in [0.92, 1.18] by combining:
     * 1. The track's classified mood/genre (not naive title substring matching, so a Metal track
     *    titled "Night" is never boosted as a calm late-night track).
     * 2. The user's personal historical mood/genre preference for that context, ensuring time of day
     *    never overrides the listener's core musical preferences.
     */
    fun contextAlignmentMultiplier(
        classification: TrackClassification,
        context: ListeningContext,
        userContextGenreAffinities: Map<MusicGenre, Double> = emptyMap()
    ): Double {
        val moods = classification.moods
        val genres = classification.genres

        val moodFit = when (context) {
            ListeningContext.MORNING -> maxOf(
                moods[MusicMood.PEACEFUL] ?: 0.0,
                moods[MusicMood.UPLIFTING] ?: 0.0,
                moods[MusicMood.CALM] ?: 0.0
            ) - 0.5 * (moods[MusicMood.AGGRESSIVE] ?: 0.0)

            ListeningContext.AFTERNOON -> maxOf(
                moods[MusicMood.CALM] ?: 0.0,
                moods[MusicMood.UPLIFTING] ?: 0.0,
                moods[MusicMood.HAPPY] ?: 0.0
            )

            ListeningContext.EVENING -> maxOf(
                moods[MusicMood.ENERGETIC] ?: 0.0,
                moods[MusicMood.HAPPY] ?: 0.0,
                moods[MusicMood.ROMANTIC] ?: 0.0
            )

            ListeningContext.LATE_NIGHT -> {
                val calmScore = maxOf(
                    moods[MusicMood.CALM] ?: 0.0,
                    moods[MusicMood.DREAMY] ?: 0.0,
                    moods[MusicMood.MELANCHOLIC] ?: 0.0,
                    moods[MusicMood.ROMANTIC] ?: 0.0,
                    moods[MusicMood.NOSTALGIC] ?: 0.0
                )
                val aggressivePenalty = (moods[MusicMood.AGGRESSIVE] ?: 0.0) * 0.75
                calmScore - aggressivePenalty
            }

            ListeningContext.STUDY -> maxOf(
                genres[MusicGenre.LO_FI] ?: 0.0,
                genres[MusicGenre.AMBIENT] ?: 0.0,
                genres[MusicGenre.CLASSICAL] ?: 0.0,
                moods[MusicMood.CALM] ?: 0.0
            ) - 0.6 * (moods[MusicMood.AGGRESSIVE] ?: 0.0)

            ListeningContext.RELAXATION -> maxOf(
                moods[MusicMood.RELAXING] ?: 0.0,
                moods[MusicMood.PEACEFUL] ?: 0.0,
                moods[MusicMood.CALM] ?: 0.0
            )

            ListeningContext.WORKOUT -> maxOf(
                moods[MusicMood.ENERGETIC] ?: 0.0,
                moods[MusicMood.MOTIVATIONAL] ?: 0.0,
                moods[MusicMood.AGGRESSIVE] ?: 0.0
            )

            ListeningContext.PARTY -> maxOf(
                moods[MusicMood.ENERGETIC] ?: 0.0,
                moods[MusicMood.HAPPY] ?: 0.0,
                genres[MusicGenre.DANCE] ?: 0.0
            )

            ListeningContext.TRAVEL -> maxOf(
                moods[MusicMood.UPLIFTING] ?: 0.0,
                moods[MusicMood.NOSTALGIC] ?: 0.0,
                moods[MusicMood.DREAMY] ?: 0.0
            )
        }

        val personalContextFit = if (userContextGenreAffinities.isNotEmpty()) {
            genres.entries.sumOf { (g, conf) -> conf * (userContextGenreAffinities[g] ?: 0.0) }
        } else 0.0

        val combined = (0.65 * moodFit + 0.35 * personalContextFit).coerceIn(-0.4, 0.9)
        return (1.0 + 0.16 * combined).coerceIn(0.92, 1.16)
    }
}
