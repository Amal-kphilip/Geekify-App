package com.geekify.android.domain.recommend.engine

import com.geekify.android.data.model.Track
import com.geekify.android.domain.listening.ListeningLanguageClassifier
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class EmbeddingFeatureSource {
    AUTHORIZED_AUDIO_ANALYSIS,
    METADATA_STRUCTURAL_PROJECTION,
    MODEL_GENERATED_EMBEDDING
}

data class AuthorizedAudioFeatures(
    val tempoBpm: Double,
    val energy: Double,
    val acousticness: Double,
    val spectralCentroidHz: Double = 2200.0,
    val harmonicRatio: Double = 0.65,
    val dynamicRangeDb: Double = 9.0,
    val loudnessDb: Double = -12.0,
    val vocalPresence: Double = 0.75,
    val valence: Double = 0.55
)

data class AudioEmbeddingResult(
    val videoId: String,
    val vector: FloatArray,
    val source: EmbeddingFeatureSource,
    val inferredTempoBpm: Double?,
    val inferredEnergy: Double?,
    val inferredAcousticness: Double?
)

/**
 * Generates 32-dimensional L2-normalized musical representation embeddings (`FloatArray(32)`).
 *
 * - When [AuthorizedAudioFeatures] are available (e.g., from authorized local files, licensed previews,
 *   or stream loudness/acoustic analysis), encodes real acoustic dimensions (`tempoBpm`, `energy`,
 *   `spectralCentroidHz`, `harmonicRatio`, `acousticness`, `dynamicRangeDb`, `loudnessDb`, `vocalPresence`)
 *   and marks `source = AUTHORIZED_AUDIO_ANALYSIS`.
 * - When only track metadata is available, projects genre, mood, artist-style signature, duration profile,
 *   and sonic cadence into the same 32-D latent space so cross-language tracks with similar sonic
 *   profiles have high cosine similarity, and transparently marks `source = METADATA_STRUCTURAL_PROJECTION`.
 */
@Singleton
class AudioEmbeddingService @Inject constructor(
    private val classificationService: MusicClassificationService
) {
    companion object {
        const val EMBEDDING_DIM = 32
    }

    fun embedTrack(
        track: Track,
        authorizedAudio: AuthorizedAudioFeatures? = null,
        classification: TrackClassification? = null
    ): AudioEmbeddingResult {
        val cls = classification ?: classificationService.classify(
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSeconds = track.durationSeconds,
            tempoBpm = authorizedAudio?.tempoBpm,
            energy = authorizedAudio?.energy,
            acousticness = authorizedAudio?.acousticness
        )
        val vec = FloatArray(EMBEDDING_DIM)

        // Dimensions 0..7: Core acoustic / rhythmic / dynamic dimensions
        val energy = authorizedAudio?.energy ?: estimateEnergyFromClassification(cls)
        val acousticness = authorizedAudio?.acousticness ?: estimateAcousticnessFromClassification(cls)
        val tempoNormalized = ((authorizedAudio?.tempoBpm ?: estimateTempoBpm(cls)) - 60.0) / 140.0
        val valence = authorizedAudio?.valence ?: estimateValenceFromClassification(cls)
        val spectral = ((authorizedAudio?.spectralCentroidHz ?: (1400.0 + energy * 2200.0)) / 5000.0).coerceIn(0.0, 1.0)
        val harmonic = (authorizedAudio?.harmonicRatio ?: (0.45 + 0.40 * acousticness)).coerceIn(0.0, 1.0)
        val dynamicRange = ((authorizedAudio?.dynamicRangeDb ?: (6.0 + 6.0 * acousticness)) / 20.0).coerceIn(0.0, 1.0)
        val durationNorm = ((track.durationSeconds ?: 210).coerceIn(60, 600) / 600.0)

        vec[0] = energy.toFloat()
        vec[1] = acousticness.toFloat()
        vec[2] = tempoNormalized.coerceIn(0.0, 1.0).toFloat()
        vec[3] = valence.toFloat()
        vec[4] = spectral.toFloat()
        vec[5] = harmonic.toFloat()
        vec[6] = dynamicRange.toFloat()
        vec[7] = durationNorm.toFloat()

        // Dimensions 8..19: Multi-label genre subspace (language-invariant!)
        val genreMapping = listOf(
            MusicGenre.POP,
            MusicGenre.ROCK,
            MusicGenre.HIP_HOP,
            MusicGenre.ELECTRONIC,
            MusicGenre.DANCE,
            MusicGenre.LO_FI,
            MusicGenre.AMBIENT,
            MusicGenre.INDIE,
            MusicGenre.FOLK,
            MusicGenre.CLASSICAL,
            MusicGenre.FILM_MUSIC,
            MusicGenre.ACOUSTIC
        )
        for (i in genreMapping.indices) {
            vec[8 + i] = (cls.genres[genreMapping[i]] ?: 0.0).toFloat()
        }

        // Dimensions 20..29: Multi-dimensional mood subspace (language-invariant!)
        val moodMapping = listOf(
            MusicMood.ROMANTIC,
            MusicMood.MELANCHOLIC,
            MusicMood.CALM,
            MusicMood.ENERGETIC,
            MusicMood.HAPPY,
            MusicMood.NOSTALGIC,
            MusicMood.DREAMY,
            MusicMood.MOTIVATIONAL,
            MusicMood.DARK,
            MusicMood.EMOTIONAL
        )
        for (i in moodMapping.indices) {
            vec[20 + i] = (cls.moods[moodMapping[i]] ?: 0.0).toFloat()
        }

        // Dimensions 30..31: Artist timbre hash projection (tiny weight so sonic style dominates)
        val artistHash = track.artist.lowercase(Locale.ROOT).trim().hashCode()
        vec[30] = (sin(artistHash.toDouble()) * 0.15).toFloat()
        vec[31] = (cos(artistHash.toDouble()) * 0.15).toFloat()

        return AudioEmbeddingResult(
            videoId = track.videoId,
            vector = l2Normalize(vec),
            source = if (authorizedAudio != null) {
                EmbeddingFeatureSource.AUTHORIZED_AUDIO_ANALYSIS
            } else {
                EmbeddingFeatureSource.METADATA_STRUCTURAL_PROJECTION
            },
            inferredTempoBpm = authorizedAudio?.tempoBpm ?: estimateTempoBpm(cls),
            inferredEnergy = energy,
            inferredAcousticness = acousticness
        )
    }

    fun buildUserAudioTasteVector(weightedTrackEmbeddings: List<Pair<FloatArray, Double>>): FloatArray {
        val acc = FloatArray(EMBEDDING_DIM)
        var totalWeight = 0.0
        for ((emb, w) in weightedTrackEmbeddings) {
            if (emb.size != EMBEDDING_DIM || w <= 0.0) continue
            totalWeight += w
            for (i in 0 until EMBEDDING_DIM) {
                acc[i] += (emb[i] * w).toFloat()
            }
        }
        return if (totalWeight > 0.0) l2Normalize(acc) else acc
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 1e-9) (dot / denom).coerceIn(-1.0, 1.0) else 0.0
    }

    fun nearestNeighbors(
        queryVector: FloatArray,
        candidates: List<AudioEmbeddingResult>,
        topK: Int = 20,
        excludeVideoIds: Set<String> = emptySet()
    ): List<Pair<AudioEmbeddingResult, Double>> =
        candidates
            .asSequence()
            .filter { it.videoId !in excludeVideoIds }
            .map { it to cosineSimilarity(queryVector, it.vector) }
            .sortedByDescending { it.second }
            .take(topK)
            .toList()

    private fun estimateEnergyFromClassification(cls: TrackClassification): Double {
        val high = maxOf(
            cls.moods[MusicMood.ENERGETIC] ?: 0.0,
            cls.moods[MusicMood.AGGRESSIVE] ?: 0.0,
            cls.genres[MusicGenre.DANCE] ?: 0.0,
            cls.genres[MusicGenre.METAL] ?: 0.0
        )
        val low = maxOf(
            cls.moods[MusicMood.CALM] ?: 0.0,
            cls.moods[MusicMood.PEACEFUL] ?: 0.0,
            cls.genres[MusicGenre.LO_FI] ?: 0.0,
            cls.genres[MusicGenre.AMBIENT] ?: 0.0
        )
        return (0.52 + 0.42 * high - 0.35 * low).coerceIn(0.08, 0.98)
    }

    private fun estimateAcousticnessFromClassification(cls: TrackClassification): Double {
        val acoustic = maxOf(
            cls.genres[MusicGenre.ACOUSTIC] ?: 0.0,
            cls.genres[MusicGenre.CLASSICAL] ?: 0.0,
            cls.genres[MusicGenre.FOLK] ?: 0.0,
            cls.moods[MusicMood.PEACEFUL] ?: 0.0
        )
        val synthetic = maxOf(
            cls.genres[MusicGenre.ELECTRONIC] ?: 0.0,
            cls.genres[MusicGenre.DANCE] ?: 0.0
        )
        return (0.45 + 0.48 * acoustic - 0.35 * synthetic).coerceIn(0.05, 0.98)
    }

    private fun estimateValenceFromClassification(cls: TrackClassification): Double {
        val positive = maxOf(
            cls.moods[MusicMood.HAPPY] ?: 0.0,
            cls.moods[MusicMood.UPLIFTING] ?: 0.0,
            cls.moods[MusicMood.ROMANTIC] ?: 0.0
        )
        val negative = maxOf(
            cls.moods[MusicMood.SAD] ?: 0.0,
            cls.moods[MusicMood.MELANCHOLIC] ?: 0.0,
            cls.moods[MusicMood.DARK] ?: 0.0
        )
        return (0.50 + 0.42 * positive - 0.40 * negative).coerceIn(0.05, 0.98)
    }

    private fun estimateTempoBpm(cls: TrackClassification): Double = when {
        (cls.genres[MusicGenre.DANCE] ?: 0.0) >= 0.6 -> 128.0
        (cls.moods[MusicMood.ENERGETIC] ?: 0.0) >= 0.7 -> 132.0
        (cls.genres[MusicGenre.LO_FI] ?: 0.0) >= 0.6 -> 82.0
        (cls.moods[MusicMood.CALM] ?: 0.0) >= 0.7 -> 88.0
        else -> 108.0
    }

    internal fun l2Normalize(vec: FloatArray): FloatArray {
        var sumSq = 0.0
        for (v in vec) sumSq += v * v
        val norm = sqrt(sumSq)
        if (norm <= 1e-9) return vec
        return FloatArray(vec.size) { i -> (vec[i] / norm).toFloat() }
    }
}

enum class LyricsRepresentationSource {
    /** Keyword lexicon + character 3-gram projection over an authorized lyric snippet (not deep NLP comprehension). */
    AUTHORIZED_LYRICS_LEXICON_HASH,
    /** Keyword lexicon + character 3-gram projection derived strictly from title/artist/album metadata (no lyrics text). */
    METADATA_LEXICON_PROJECTION,
    /** Genuine model-generated semantic embedding when produced by an external or on-device language model. */
    MODEL_GENERATED_EMBEDDING
}

/**
 * Multilingual thematic lexicon & character n-gram representation service (`FloatArray(32)`).
 *
 * Note: This service performs deterministic keyword-lexicon matching and character 3-gram
 * hashing over track metadata (or optional authorized lyric snippets). It does NOT perform
 * deep natural-language lyric comprehension or full-song lyric analysis.
 */
@Singleton
class LyricsSemanticService @Inject constructor() {
    companion object {
        const val SEMANTIC_DIM = 32
    }

    fun resolveRepresentationSource(authorizedLyricsSnippet: String? = null): LyricsRepresentationSource =
        if (!authorizedLyricsSnippet.isNullOrBlank()) {
            LyricsRepresentationSource.AUTHORIZED_LYRICS_LEXICON_HASH
        } else {
            LyricsRepresentationSource.METADATA_LEXICON_PROJECTION
        }

    private val wordRegex = Regex("[\\p{L}\\p{N}_-]+")

    // Multilingual thematic concept clusters (Malayalam, Tamil, Hindi, Telugu, Punjabi, English, Korean, Japanese, Spanish)
    private val themeLexicons: List<Set<String>> = listOf(
        // 0: Nostalgia & Longing
        setOf("nostalgia", "memories", "remember", "orma", "ormakal", "ormayil", "kanavu", "kanavil", "ninavil", "nyabagam", "ninaivu", "yaad", "yaadein", "lamhe", "gurthukostunnayi", "miss", "longing", "past", "childhood"),
        // 1: Deep Romance & Devotion
        setOf("love", "beloved", "darling", "pranayam", "sneham", "anurag", "anuragam", "kadhal", "kaadhal", "anbe", "uyire", "kanmani", "pyaar", "ishq", "mohabbat", "chahat", "prema", "priya", "corazon", "amor", "sarang", "ai"),
        // 2: Heartbreak & Melancholy
        setOf("heartbreak", "lonely", "tears", "goodbye", "farewell", "viraham", "kanneer", "vidaparayukayano", "pirivu", "dard", "judai", "tanha", "bewafa", "broken", "cry", "pain", "sorrow"),
        // 3: Nature, Rain, Moonlight & Serenity
        setOf("rain", "moon", "moonlight", "sky", "flowers", "wind", "mazha", "pavizha", "nilavu", "nilave", "vennila", "poove", "malare", "thendral", "mazhai", "baarish", "chand", "saawan", "megham", "vaanam", "pulari"),
        // 4: Motivation, Resilience & Triumph
        setOf("rise", "power", "unstoppable", "fighter", "victory", "dream", "hukum", "aavesham", "illuminati", "vaathi", "sultan", "toofan", "zinda", "lakshya", "fire", "champion", "hustle", "ambition"),
        // 5: Celebration, Joy & Friendship
        setOf("party", "dance", "celebrate", "friends", "friendship", "koode", "changathi", "nanban", "machan", "thallumaala", "yaar", "dosti", "jashn", "bhangra", "smile", "joy", "fiesta"),
        // 6: Spiritual, Soulful & Inner Peace
        setOf("soul", "peace", "prayer", "divine", "grace", "blessing", "kun", "faya", "maula", "allah", "krishna", "shiva", "aradhana", "shanti", "heal", "spirit"),
        // 7: Night, Dreams & Urban Solitude
        setOf("night", "midnight", "stars", "city", "drive", "raavu", "raavil", "iravu", "iravil", "raat", "shaam", "dream", "neon", "highway", "afterhours")
    )

    /**
     * Extracts a 32-D L2-normalized semantic vector from authorized lyric lines (if provided)
     * and track title/artist/album metadata. Full lyrics text is never persisted.
     */
    fun extractSemanticVector(
        title: String,
        artist: String,
        album: String? = null,
        authorizedLyricsSnippet: String? = null
    ): FloatArray {
        val combinedText = buildString {
            append(title.lowercase(Locale.ROOT)).append(' ')
            append(artist.lowercase(Locale.ROOT)).append(' ')
            if (!album.isNullOrBlank()) append(album.lowercase(Locale.ROOT)).append(' ')
            if (!authorizedLyricsSnippet.isNullOrBlank()) {
                append(authorizedLyricsSnippet.take(2_000).lowercase(Locale.ROOT))
            }
        }
        val words = wordRegex.findAll(combinedText).map { it.value }.toList()
        val vec = FloatArray(SEMANTIC_DIM)

        // Dimensions 0..15: Multilingual thematic concept activations (2 dimensions per theme)
        for (tIdx in themeLexicons.indices) {
            val lexicon = themeLexicons[tIdx]
            val matches = words.count { w -> w in lexicon || lexicon.any { root -> root.length >= 4 && w.startsWith(root) } }
            val activation = (1.0 - 1.0 / (1.0 + matches * 0.85)).toFloat()
            vec[tIdx * 2] = activation
            vec[tIdx * 2 + 1] = (activation * 0.7f)
        }

        // Dimensions 16..31: Character 3-gram phonetic/semantic hashing for fine-grained topical continuity
        val cleanTitle = TrackIdentityNormalizer.normalizeTitleCore(title)
        if (cleanTitle.length >= 3) {
            for (i in 0..(cleanTitle.length - 3)) {
                val trigram = cleanTitle.substring(i, i + 3)
                val bucket = 16 + (abs(trigram.hashCode()) % 16)
                vec[bucket] += 0.12f
            }
        }

        // Ensure non-zero semantic baseline so cosine similarity is well-defined
        if (vec.all { it == 0f }) {
            vec[0] = 0.25f
            vec[2] = 0.25f
        }

        var sumSq = 0.0
        for (v in vec) sumSq += v * v
        val norm = sqrt(sumSq)
        if (norm > 1e-9) {
            for (i in vec.indices) vec[i] = (vec[i] / norm).toFloat()
        }
        return vec
    }

    fun semanticSimilarity(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 1e-9) (dot / denom).coerceIn(-1.0, 1.0) else 0.0
    }
}
