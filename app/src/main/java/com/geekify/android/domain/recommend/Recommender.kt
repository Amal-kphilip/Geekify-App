package com.geekify.android.domain.recommend

import com.geekify.android.domain.listening.ListeningLanguageClassifier
import kotlin.math.ln
import kotlin.math.min
import kotlin.random.Random

/**
 * Pure Kotlin port of backend/app/services/recommender.py upgraded with
 * behavioral session signals (completion rate, skip penalty, repeat velocity), time-of-day
 * vibe affinity, 5-layer hybrid language resolution, and multi-cluster Daily Mixes.
 * No Android imports. Testable with plain JUnit.
 */
object Recommender {
    const val LIKE_WEIGHT = 1.6
    const val LIKE_DECAY = 0.96
    const val RECENT_WEIGHT = 1.0
    const val RECENT_DECAY = 0.90
    const val RANK_DECAY = 0.12
    const val CO_OCCURRENCE_BOOST = 0.35
    const val MAX_SEEDS = 8
    const val MAX_SEEDS_PER_ARTIST = 2
    const val MIN_TRACK_SECONDS = 45
    const val MAX_TRACK_SECONDS = 15 * 60
    /** Language is a strong preference signal, but never an absolute filter. */
    private const val LANGUAGE_AFFINITY_BOOST = 0.70
    /** Language preference also has a small influence on which songs become radio seeds. */
    private const val LANGUAGE_SEED_BOOST = 0.45
    private const val PREFERRED_LANGUAGE_BOOST = 0.25
    private const val LANGUAGE_DIVERSITY_DECAY = 0.92
    private const val LIKE_LANGUAGE_WEIGHT = 2.8
    private const val PLAYLIST_LANGUAGE_WEIGHT = 1.7
    private const val FREQUENT_LANGUAGE_WEIGHT = 1.2
    private const val SESSION_LANGUAGE_WEIGHT = 1.35
    private const val SESSION_LANGUAGE_LIMIT = 10
    /** Weight of a song played often: scaled by ln(1 + plays), so 5 plays outweighs a single like. */
    const val FREQUENT_WEIGHT = 1.1
    const val PLAYLIST_WEIGHT = 0.9
    const val PLAYLIST_DECAY = 0.97
    const val SAVED_ARTIST_WEIGHT = 0.8
    /** Candidates that were already offered recently are demoted by this factor, so mixes keep changing. */
    const val SHOWN_PENALTY = 0.35
    private const val SCORE_JITTER = 0.15

    const val HALF_LIFE_PLAYS_DAYS = 14.0
    const val HALF_LIFE_LIKES_PLAYLIST_DAYS = 60.0
    const val HALF_LIFE_SKIPS_DAYS = 3.0

    /**
     * Computes exponential time decay based on half-life: decay = 2^(-ageDays / halfLifeDays).
     * When timestampMs is missing (<= 0L), gracefully falls back to rank-based index decay approximation.
     */
    fun exponentialTimeDecay(
        timestampMs: Long,
        nowMs: Long,
        halfLifeDays: Double,
        fallbackIndex: Int,
        fallbackDecay: Double
    ): Double {
        if (timestampMs > 0L && nowMs >= timestampMs) {
            val ageDays = (nowMs - timestampMs).toDouble() / (1000.0 * 60.0 * 60.0 * 24.0)
            return Math.pow(0.5, ageDays / halfLifeDays)
        }
        // Fallback approximation for legacy signals or unit tests with no timestamps
        return Math.pow(fallbackDecay, fallbackIndex.toDouble())
    }

    data class Signal(
        val videoId: String,
        val artist: String = "",
        val title: String = "",
        val timestampMs: Long = 0L
    )

    /** A song with how many times it has been listened to. */
    data class PlayedSignal(
        val videoId: String,
        val artist: String = "",
        val title: String = "",
        val plays: Int = 1,
        val lastPlayedAtMs: Long = 0L
    )

    /**
     * Behavioral session quality signal derived from durable listening sessions (completion
     * rate, early skip rate, and recent 7-day repeat velocity).
     */
    data class SessionSignal(
        val videoId: String,
        val artist: String = "",
        val title: String = "",
        val completionRate: Double = 1.0,
        val skipRate: Double = 0.0,
        val recentPlays7d: Int = 0,
        val lastPlayedAtMs: Long = 0L
    )

    data class Candidate(
        val videoId: String,
        val title: String,
        val artist: String,
        val artistDisplay: String,
        val thumbnails: List<Any>,
        val duration: String?,
        val durationSeconds: Int?,
        val explicit: Boolean,
        val type: String,
        var score: Double = 0.0,
        val sources: MutableSet<String> = mutableSetOf(),
        val album: String? = null,
        val sourceArtists: MutableSet<String> = mutableSetOf(),
        var inferredLanguage: String? = null,
        var languageConfidence: Double = 1.0
    )

    data class MixResult(
        val id: String,
        val title: String,
        val subtitle: String,
        val videoIds: List<String>,
        val sources: Map<String, String> = emptyMap()
    )

    private fun akey(name: String?) = (name ?: "").trim().lowercase()

    /**
     * Infers the language of a track using the unified 5-layer hybrid classifier:
     * Layer 1 (explicit metadata tags), Layer 2 (native Unicode scripts),
     * Layer 3 (romanized lexical & phonetic morphology), and Layer 4 (artist/composer taxonomy).
     * Generic English/Latin titles remain null so they never distort a listener's preference.
     */
    fun languageOf(title: String?, artist: String?): String? =
        ListeningLanguageClassifier.classifyForRecommender(title, artist)

    fun resolveLanguage(
        videoId: String?,
        title: String?,
        artist: String?,
        languageOverrides: Map<String, String> = emptyMap()
    ): String? {
        if (!videoId.isNullOrBlank()) {
            languageOverrides[videoId]?.let { override ->
                ListeningLanguageClassifier.normalizeRecommenderLanguage(override)?.let { return it }
            }
        }
        return languageOf(title, artist)
    }

    /**
     * Multi-language taste profile derived only from meaningful user behaviour.
     *
     * Long-term taste uses explicit likes, playlist additions, and real play counts.
     * Recent taste uses the actual history order, while session taste focuses on the
     * newest slice of that history. Each distribution is normalized independently so
     * one large raw signal cannot drown out the others.
     */
    data class LanguageAffinityProfile(
        val affinity: Map<String, Double>,
        val longTerm: Map<String, Double>,
        val recent: Map<String, Double>,
        val session: Map<String, Double>
    )

    fun languageAffinity(
        liked: List<Signal>,
        recent: List<Signal>,
        frequent: List<PlayedSignal> = emptyList(),
        playlist: List<Signal> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
        languageOverrides: Map<String, String> = emptyMap()
    ): LanguageAffinityProfile {
        val longTermRaw = mutableMapOf<String, Double>()
        val recentRaw = mutableMapOf<String, Double>()
        val sessionRaw = mutableMapOf<String, Double>()

        fun add(map: MutableMap<String, Double>, language: String?, weight: Double) {
            if (language == null || weight <= 0.0) return
            map[language] = (map[language] ?: 0.0) + weight
        }

        liked.forEachIndexed { index, signal ->
            add(
                longTermRaw,
                resolveLanguage(signal.videoId, signal.title, signal.artist, languageOverrides),
                LIKE_LANGUAGE_WEIGHT * exponentialTimeDecay(signal.timestampMs, nowMs, HALF_LIFE_LIKES_PLAYLIST_DAYS, index, LIKE_DECAY)
            )
        }
        playlist.forEachIndexed { index, signal ->
            add(
                longTermRaw,
                resolveLanguage(signal.videoId, signal.title, signal.artist, languageOverrides),
                PLAYLIST_LANGUAGE_WEIGHT * exponentialTimeDecay(signal.timestampMs, nowMs, HALF_LIFE_LIKES_PLAYLIST_DAYS, index, PLAYLIST_DECAY)
            )
        }
        frequent.forEach { signal ->
            val timeDecay = if (signal.lastPlayedAtMs > 0L) exponentialTimeDecay(signal.lastPlayedAtMs, nowMs, HALF_LIFE_PLAYS_DAYS, 0, 1.0) else 1.0
            val repeatedWeight = FREQUENT_LANGUAGE_WEIGHT * ln(1.0 + signal.plays.coerceAtLeast(1)) * timeDecay
            add(longTermRaw, resolveLanguage(signal.videoId, signal.title, signal.artist, languageOverrides), repeatedWeight)
        }
        recent.forEachIndexed { index, signal ->
            add(
                recentRaw,
                resolveLanguage(signal.videoId, signal.title, signal.artist, languageOverrides),
                RECENT_WEIGHT * exponentialTimeDecay(signal.timestampMs, nowMs, HALF_LIFE_PLAYS_DAYS, index, RECENT_DECAY)
            )
        }
        recent.take(SESSION_LANGUAGE_LIMIT).forEachIndexed { index, signal ->
            add(
                sessionRaw,
                resolveLanguage(signal.videoId, signal.title, signal.artist, languageOverrides),
                SESSION_LANGUAGE_WEIGHT * exponentialTimeDecay(signal.timestampMs, nowMs, 1.0, index, 0.80)
            )
        }

        val longTerm = normalizeDistribution(longTermRaw)
        val recentDistribution = normalizeDistribution(recentRaw)
        val sessionDistribution = normalizeDistribution(sessionRaw)
        val languages = (longTerm.keys + recentDistribution.keys + sessionDistribution.keys).toSet()
        val combinedRaw = languages.associateWith { language ->
            0.45 * (longTerm[language] ?: 0.0) +
                0.40 * (recentDistribution[language] ?: 0.0) +
                0.15 * (sessionDistribution[language] ?: 0.0)
        }.filterValues { it > 0.0 }
        val combined = normalizeDistribution(combinedRaw)

        return LanguageAffinityProfile(combined, longTerm, recentDistribution, sessionDistribution)
    }

    private fun normalizeDistribution(raw: Map<String, Double>): Map<String, Double> {
        val total = raw.values.sum()
        if (total <= 0.0) return emptyMap()
        return raw.mapValues { (_, value) -> value / total }
    }

    fun dominantLanguage(liked: List<Signal>, recent: List<Signal>): String? =
        languageAffinity(liked, recent).affinity.maxByOrNull { it.value }?.key

    data class WeightResult(
        val weights: Map<String, Double>,
        val meta: Map<String, Signal>,
        val affinity: Map<String, Double>
    )

    /**
     * Combines every taste signal into one weight per song and one affinity per artist:
     * likes, recent plays, frequently played songs, songs placed in playlists, artists whose
     * albums / playlists were saved, and optional session completion/skip signals.
     */
    fun seedWeights(
        liked: List<Signal>,
        recent: List<Signal>,
        frequent: List<PlayedSignal> = emptyList(),
        playlist: List<Signal> = emptyList(),
        savedArtists: List<String> = emptyList(),
        languageAffinity: Map<String, Double> = emptyMap(),
        sessions: List<SessionSignal> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
        languageOverrides: Map<String, String> = emptyMap()
    ): WeightResult {
        val weights = mutableMapOf<String, Double>()
        val meta = mutableMapOf<String, Signal>()
        val affinity = mutableMapOf<String, Double>()
        frequent.forEach { s ->
            val timeDecay = if (s.lastPlayedAtMs > 0L) exponentialTimeDecay(s.lastPlayedAtMs, nowMs, HALF_LIFE_PLAYS_DAYS, 0, 1.0) else 1.0
            val w = FREQUENT_WEIGHT * Math.log(1.0 + s.plays.coerceAtLeast(1)) * timeDecay
            weights[s.videoId] = (weights[s.videoId] ?: 0.0) + w
            meta.putIfAbsent(s.videoId, Signal(s.videoId, s.artist, s.title, s.lastPlayedAtMs))
            val ak = akey(s.artist); if (ak.isNotEmpty()) affinity[ak] = (affinity[ak] ?: 0.0) + w
        }
        playlist.forEachIndexed { i, s ->
            val w = PLAYLIST_WEIGHT * exponentialTimeDecay(s.timestampMs, nowMs, HALF_LIFE_LIKES_PLAYLIST_DAYS, i, PLAYLIST_DECAY)
            weights[s.videoId] = (weights[s.videoId] ?: 0.0) + w
            meta.putIfAbsent(s.videoId, s)
            val ak = akey(s.artist); if (ak.isNotEmpty()) affinity[ak] = (affinity[ak] ?: 0.0) + w
        }
        savedArtists.forEach { name ->
            val ak = akey(name); if (ak.isNotEmpty()) affinity[ak] = (affinity[ak] ?: 0.0) + SAVED_ARTIST_WEIGHT
        }
        liked.forEachIndexed { i, s ->
            val w = LIKE_WEIGHT * exponentialTimeDecay(s.timestampMs, nowMs, HALF_LIFE_LIKES_PLAYLIST_DAYS, i, LIKE_DECAY)
            weights[s.videoId] = (weights[s.videoId] ?: 0.0) + w
            meta.putIfAbsent(s.videoId, s)
            val ak = akey(s.artist); if (ak.isNotEmpty()) affinity[ak] = (affinity[ak] ?: 0.0) + w
        }
        recent.forEachIndexed { i, s ->
            val w = RECENT_WEIGHT * exponentialTimeDecay(s.timestampMs, nowMs, HALF_LIFE_PLAYS_DAYS, i, RECENT_DECAY)
            weights[s.videoId] = (weights[s.videoId] ?: 0.0) + w
            meta.putIfAbsent(s.videoId, s)
            val ak = akey(s.artist); if (ak.isNotEmpty()) affinity[ak] = (affinity[ak] ?: 0.0) + w
        }
        // Apply session completion / skip / repeat-velocity adjustments when available
        if (sessions.isNotEmpty()) {
            sessions.forEach { sess ->
                meta.putIfAbsent(sess.videoId, Signal(sess.videoId, sess.artist, sess.title, sess.lastPlayedAtMs))
                val ak = akey(sess.artist)
                val completionBonus = (sess.completionRate - 0.5).coerceIn(-0.4, 0.5) * 0.85
                val skipDecay = if (sess.lastPlayedAtMs > 0L) exponentialTimeDecay(sess.lastPlayedAtMs, nowMs, HALF_LIFE_SKIPS_DAYS, 0, 1.0) else 1.0
                val skipPenalty = sess.skipRate.coerceIn(0.0, 1.0) * 0.75 * skipDecay
                val repeatBoost = if (sess.recentPlays7d >= 2) ln(1.0 + sess.recentPlays7d) * 0.45 else 0.0
                val netDelta = completionBonus - skipPenalty + repeatBoost
                val currentW = weights[sess.videoId] ?: 0.0
                if (currentW > 0.0 || netDelta > 0.25) {
                    var updated = (currentW + netDelta).coerceAtLeast(if (currentW > 0.0) currentW * 0.35 else 0.0)
                    // Heavily skipped tracks should not dominate seed selection
                    if (sess.skipRate >= 0.6 && sess.completionRate < 0.35) {
                        updated *= 0.15
                    }
                    if (updated > 0.0) weights[sess.videoId] = updated
                }
                if (ak.isNotEmpty()) {
                    val currentAff = affinity[ak] ?: 0.0
                    val updatedAff = (currentAff + netDelta * 0.7).coerceAtLeast(if (currentAff > 0.0) currentAff * 0.40 else 0.0)
                    if (updatedAff > 0.0) affinity[ak] = updatedAff
                }
            }
        }
        if (languageAffinity.isNotEmpty()) {
            weights.keys.toList().forEach { videoId ->
                val signal = meta[videoId] ?: return@forEach
                val language = resolveLanguage(videoId, signal.title, signal.artist, languageOverrides)
                val preference = language?.let { languageAffinity[it] } ?: 0.0
                if (preference > 0.0) {
                    weights[videoId] = (weights[videoId] ?: 0.0) * (1.0 + LANGUAGE_SEED_BOOST * preference)
                }
            }
        }
        return WeightResult(weights, meta, affinity)
    }

    fun pickSeeds(weights: Map<String, Double>, meta: Map<String, Signal>, limit: Int = MAX_SEEDS): List<String> {
        val seeds = mutableListOf<String>()
        val perArtist = mutableMapOf<String, Int>()
        for (vid in weights.entries.sortedByDescending { it.value }.map { it.key }) {
            val a = akey(meta[vid]?.artist)
            if (a.isNotEmpty() && (perArtist[a] ?: 0) >= MAX_SEEDS_PER_ARTIST) continue
            seeds.add(vid)
            perArtist[a] = (perArtist[a] ?: 0) + 1
            if (seeds.size >= limit) break
        }
        return seeds
    }

    /**
     * Like [pickSeeds], but always keeps the two strongest seeds, guarantees representation for
     * secondary language clusters present in the listener's taste pool, and fills the rest by
     * weighted random choice from the next-best ones.
     */
    fun pickSeedsVaried(
        weights: Map<String, Double>,
        meta: Map<String, Signal>,
        limit: Int = MAX_SEEDS,
        random: Random = Random.Default,
        languageOverrides: Map<String, String> = emptyMap()
    ): List<String> {
        val ranked = pickSeeds(weights, meta, limit * 3)
        if (ranked.size <= limit) return ranked
        val picked = ranked.take(2).toMutableList()
        val pool = ranked.drop(2).toMutableList()

        // Ensure secondary language clusters in the listener's pool get at least one seed slot
        val coveredLanguages = picked.mapNotNull { vid ->
            val s = meta[vid]
            resolveLanguage(vid, s?.title, s?.artist, languageOverrides)
        }.toMutableSet()

        val secondaryCandidates = pool.filter { vid ->
            val s = meta[vid]
            val lang = resolveLanguage(vid, s?.title, s?.artist, languageOverrides)
            lang != null && lang !in coveredLanguages
        }
        for (cand in secondaryCandidates) {
            if (picked.size >= (limit - 1).coerceAtLeast(2)) break
            val s = meta[cand]
            val lang = resolveLanguage(cand, s?.title, s?.artist, languageOverrides) ?: continue
            if (lang !in coveredLanguages) {
                picked.add(cand)
                pool.remove(cand)
                coveredLanguages.add(lang)
            }
        }

        while (picked.size < limit && pool.isNotEmpty()) {
            val total = pool.sumOf { weights[it] ?: 0.0 }
            if (total <= 0.0) {
                picked.add(pool.removeAt(0))
                continue
            }
            var r = random.nextDouble() * total
            var idx = pool.indexOfFirst { r -= (weights[it] ?: 0.0); r <= 0.0 }
            if (idx < 0) idx = pool.lastIndex
            picked.add(pool.removeAt(idx))
        }
        return picked
    }

    /**
     * Evaluates candidate track quality and filters or demotes YouTube noise:
     * - Returns 0.0 (hard reject) for ultra-short clips (< 45s), long mixes (> 15m),
     *   ringtones, status videos, karaoke, or instrumental backing tracks.
     * - Returns 0.35 (heavy demotion) for unrequested 8D audio, nightcore, bass-boosted,
     *   mashups, jukeboxes, DJ remixes, or tribute covers.
     */
    fun candidateQualityMultiplier(
        title: String,
        artist: String,
        durationSeconds: Int?,
        seedTitles: List<String> = emptyList()
    ): Double {
        if (durationSeconds != null && (durationSeconds < MIN_TRACK_SECONDS || durationSeconds > MAX_TRACK_SECONDS)) {
            return 0.0
        }
        val lowerTitle = title.lowercase()
        val combined = "$lowerTitle ${artist.lowercase()}"

        if (HARD_REJECT_PATTERNS.any { combined.contains(it) }) {
            return 0.0
        }

        val seedContext = seedTitles.joinToString(" ").lowercase()
        for (pattern in SOFT_DEMOTE_PATTERNS) {
            if (combined.contains(pattern) && !seedContext.contains(pattern)) {
                return 0.35
            }
        }
        return 1.0
    }

    private val HARD_REJECT_PATTERNS = listOf(
        "ringtone",
        "status video",
        "whatsapp status",
        "full screen status",
        "karaoke",
        "instrumental version",
        "backing track",
        "dialogue promo",
        "teaser",
        "trailer"
    )

    private val SOFT_DEMOTE_PATTERNS = listOf(
        "8d audio",
        "8d song",
        "nightcore",
        "bass boosted",
        "mashup",
        "jukebox",
        "nonstop",
        "non-stop",
        "dj remix",
        "cover version",
        "tribute to"
    )

    fun similarity(a: Candidate, b: Candidate): Double {
        val sameArtist = a.artist.isNotEmpty() && a.artist.equals(b.artist, ignoreCase = true)
        if (sameArtist) return 1.0
        val sameAlbum = !a.album.isNullOrBlank() && a.album.equals(b.album, ignoreCase = true)
        if (sameAlbum) return 0.5
        val langA = a.inferredLanguage ?: languageOf(a.title, a.artistDisplay) ?: "Latin"
        val langB = b.inferredLanguage ?: languageOf(b.title, b.artistDisplay) ?: "Latin"
        if (langA == langB) return 0.3
        return 0.0
    }

    /**
     * Maximal Marginal Relevance (MMR) diversification:
     * MMR_score = lambda * relevance - (1 - lambda) * max_similarity_to_selected.
     * Hard cap of maxPerArtist still applies.
     */
    fun diversifyMMR(
        cands: List<Candidate>,
        n: Int,
        maxPerArtist: Int = 3,
        lambda: Double = 0.7
    ): List<Candidate> {
        if (cands.isEmpty() || n <= 0) return emptyList()
        val pool = cands.toMutableList()
        val selected = mutableListOf<Candidate>()
        val artistCounts = mutableMapOf<String, Int>()
        val maxScore = pool.maxOfOrNull { it.score }?.takeIf { it > 0.0 } ?: 1.0

        while (pool.isNotEmpty() && selected.size < n) {
            var bestCand: Candidate? = null
            var bestMmr = Double.NEGATIVE_INFINITY

            for (c in pool) {
                val currentArtistCount = artistCounts[c.artist] ?: 0
                if (c.artist.isNotEmpty() && currentArtistCount >= maxPerArtist) continue

                val relevance = (c.score / maxScore).coerceIn(0.0, 1.0)
                val maxSim = if (selected.isEmpty()) {
                    0.0
                } else {
                    selected.maxOf { similarity(c, it) }
                }

                val mmrScore = lambda * relevance - (1.0 - lambda) * maxSim
                if (mmrScore > bestMmr) {
                    bestMmr = mmrScore
                    bestCand = c
                }
            }

            val picked = bestCand ?: break
            selected.add(picked)
            pool.remove(picked)
            if (picked.artist.isNotEmpty()) {
                artistCounts[picked.artist] = (artistCounts[picked.artist] ?: 0) + 1
            }
        }
        return selected
    }

    fun diversify(
        cands: List<Candidate>,
        n: Int,
        maxPerArtist: Int = 3,
        decay: Double = 0.65,
        languageDecay: Double = 1.0,
        lambda: Double = 0.7
    ): List<Candidate> = diversifyMMR(cands, n, maxPerArtist, lambda)

    private val WORD_REGEX = Regex("[\\p{L}\\p{N}_-]+")

    /**
     * Context-aware time-of-day vibe multiplier for dayparting recommendations.
     * Gently boosts tracks whose title/artist cues align with the current time of day.
     * Matches keywords on whole-word boundaries only.
     */
    fun timeOfDayVibeMultiplier(title: String, artist: String, hourOfDay: Int?): Double {
        val hour = hourOfDay ?: return 1.0
        val text = "${title.lowercase()} ${artist.lowercase()}"
        val words = WORD_REGEX.findAll(text).map { it.value }.toSet()
        fun matchesKeyword(keywords: List<String>): Boolean =
            keywords.any { kw ->
                if ('-' in kw || ' ' in kw) text.contains(kw) else kw in words
            }

        return when (hour) {
            in 5..11 -> when {
                matchesKeyword(MORNING_KEYWORDS) -> 1.14
                matchesKeyword(LATE_NIGHT_HEAVY_KEYWORDS) -> 0.94
                else -> 1.0
            }
            in 12..16 -> when {
                matchesKeyword(AFTERNOON_KEYWORDS) -> 1.12
                else -> 1.0
            }
            in 17..21 -> when {
                matchesKeyword(EVENING_KEYWORDS) -> 1.15
                else -> 1.0
            }
            else -> when {
                matchesKeyword(NIGHT_KEYWORDS) -> 1.16
                else -> 1.0
            }
        }
    }

    private val MORNING_KEYWORDS = listOf(
        "acoustic", "morning", "melody", "unplugged", "breeze", "sunshine", "classic", "peaceful",
        "pulari", "pular", "kaalai", "subah", "suprabhatam", "udayam", "malare", "poove"
    )
    private val AFTERNOON_KEYWORDS = listOf(
        "focus", "lofi", "lo-fi", "chill", "groove", "instrumental", "beats", "vibe",
        "soulful", "indie", "breeze", "drive"
    )
    private val EVENING_KEYWORDS = listOf(
        "party", "dance", "remix", "workout", "bass", "club", "hit", "anthem", "energetic",
        "kuthu", "mass", "dappankuthu", "bhangra", "peppy", "celebration", "beat", "thallumaala", "aavesham"
    )
    private val NIGHT_KEYWORDS = listOf(
        "night", "lofi", "lo-fi", "slowed", "reverb", "chill", "romantic", "unplugged", "ambient", "acoustic", "melody",
        "raavu", "raavil", "nilavu", "nilave", "iravu", "iravil", "raat", "shaam", "vennila", "mazha", "kanavu"
    )
    private val LATE_NIGHT_HEAVY_KEYWORDS = listOf("hardstyle", "rave", "workout")

    /**
     * Interleaves familiar high-affinity "anchor" tracks (~25-30%) with discovery tracks (~70-75%)
     * so personalized mixes feel cohesive and immediately playable while remaining discovery-forward.
     */
    fun <T> blendAnchorTracks(
        discoveryTracks: List<T>,
        anchorCandidates: List<T>,
        videoId: (T) -> String,
        artist: (T) -> String,
        targetCount: Int = 28,
        anchorInterval: Int = 4,
        maxPerArtist: Int = 3
    ): List<T> {
        if (discoveryTracks.isEmpty()) return anchorCandidates.take(targetCount)
        if (anchorCandidates.isEmpty()) return discoveryTracks.take(targetCount)

        val result = mutableListOf<T>()
        val usedIds = mutableSetOf<String>()
        val artistCounts = mutableMapOf<String, Int>()

        val discQueue = discoveryTracks.toMutableList()
        val anchorQueue = anchorCandidates.toMutableList()

        fun canAdd(item: T): Boolean {
            val id = videoId(item)
            if (id.isEmpty() || id in usedIds) return false
            val a = akey(artist(item))
            if (a.isNotEmpty() && (artistCounts[a] ?: 0) >= maxPerArtist) return false
            return true
        }

        fun recordAdd(item: T) {
            val id = videoId(item)
            val a = akey(artist(item))
            usedIds.add(id)
            if (a.isNotEmpty()) {
                artistCounts[a] = (artistCounts[a] ?: 0) + 1
            }
            result.add(item)
        }

        while (result.size < targetCount && (discQueue.isNotEmpty() || anchorQueue.isNotEmpty())) {
            val slot = result.size
            val wantAnchor = (slot % anchorInterval == 1) && anchorQueue.isNotEmpty()
            if (wantAnchor) {
                val anchorIdx = anchorQueue.indexOfFirst(::canAdd)
                if (anchorIdx >= 0) {
                    recordAdd(anchorQueue.removeAt(anchorIdx))
                    continue
                } else {
                    anchorQueue.clear()
                }
            }
            val discIdx = discQueue.indexOfFirst(::canAdd)
            if (discIdx >= 0) {
                recordAdd(discQueue.removeAt(discIdx))
            } else {
                val fallbackIdx = anchorQueue.indexOfFirst(::canAdd)
                if (fallbackIdx >= 0) {
                    recordAdd(anchorQueue.removeAt(fallbackIdx))
                } else {
                    break
                }
            }
        }
        return result
    }

    /**
     * Build mixes from pre-fetched radio tracks.
     * @param liked up to 40 liked signals (newest first)
     * @param recent up to 30 recent signals (newest first)
     * @param related map of seedVideoId -> list of related Track-like objects (must have videoId, artist, durationSeconds)
     */
    fun <T> buildMixes(
        liked: List<Signal>,
        recent: List<Signal>,
        related: Map<String, List<T>>,
        videoId: (T) -> String,
        artist: (T) -> String,
        artistDisplay: (T) -> String,
        title: (T) -> String,
        thumbs: (T) -> List<Any>,
        duration: (T) -> String?,
        durationSeconds: (T) -> Int?,
        explicit: (T) -> Boolean,
        type: (T) -> String,
        frequent: List<PlayedSignal> = emptyList(),
        playlist: List<Signal> = emptyList(),
        savedArtists: List<String> = emptyList(),
        /** Songs the person already has (liked, played, in playlists): never recommended back in discovery mixes. */
        exclude: Set<String> = emptySet(),
        /** Songs offered in recent refreshes: demoted so the same ones don't keep coming back. */
        shown: Set<String> = emptySet(),
        /** Impression count in the last 7 days for graduated demotion. */
        shownCounts: Map<String, Int> = emptyMap(),
        /** Optional album accessor for MMR similarity computation. */
        album: (T) -> String? = { null },
        /** The seeds that [related] was fetched for. When null the strongest seeds are used. */
        seeds: List<String>? = null,
        random: Random = Random.Default,
        /** Optional behavioral session quality signals (completion rate, skips, 7-day repeat velocity). */
        sessions: List<SessionSignal> = emptyList(),
        /** Optional current hour of day (0..23) for dayparting vibe boost. */
        hourOfDay: Int? = null,
        /** Optional content-language preference from Settings to nudge regional discovery. */
        preferredContentLanguage: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        /** Optional resolved language overrides keyed by videoId. */
        languageOverrides: Map<String, String> = emptyMap()
    ): Pair<List<MixResult>, List<String>> {
        val languageProfile = languageAffinity(
            liked = liked,
            recent = recent,
            frequent = frequent,
            playlist = playlist,
            nowMs = nowMs,
            languageOverrides = languageOverrides
        )
        val listenerAffinity = languageProfile.affinity
        val preferredLanguage = preferredContentLanguage ?: listenerAffinity.maxByOrNull { it.value }?.key
        val (weights, meta, affinity) = seedWeights(
            liked,
            recent,
            frequent,
            playlist,
            savedArtists,
            languageAffinity = listenerAffinity,
            sessions = sessions,
            nowMs = nowMs,
            languageOverrides = languageOverrides
        )
        if (weights.isEmpty()) return Pair(emptyList(), emptyList())
        val activeSeeds = seeds ?: pickSeeds(weights, meta)
        val topW = activeSeeds.maxOfOrNull { weights[it] ?: 0.0 }?.takeIf { it > 0.0 } ?: 1.0
        val known = weights.keys + exclude
        val knownArtists = affinity.keys.toSet()
        val totalAff = affinity.values.sum().let { if (it == 0.0) 1.0 else it }

        val seedLanguages = activeSeeds.associateWith { seedId ->
            val s = meta[seedId]
            resolveLanguage(seedId, s?.title, s?.artist, languageOverrides)
        }
        val seedTitles = activeSeeds.mapNotNull { meta[it]?.title?.takeIf { t -> t.isNotEmpty() } }

        val cands = mutableMapOf<String, Candidate>()
        for (seed in activeSeeds) {
            val w = (weights[seed] ?: 0.0) / topW
            val seedArtist = akey(meta[seed]?.artist)
            val tracks = related[seed] ?: continue
            tracks.take(40).forEachIndexed { rank, t ->
                val vid = videoId(t)
                if (vid.isEmpty() || vid in known) return@forEachIndexed
                val dur = durationSeconds(t)
                val trackTitle = title(t)
                val trackArtistDisplay = artistDisplay(t)
                val qualityMult = candidateQualityMultiplier(trackTitle, trackArtistDisplay, dur, seedTitles)
                if (qualityMult <= 0.0) return@forEachIndexed

                val c = cands.getOrPut(vid) {
                    Candidate(
                        videoId = vid,
                        title = trackTitle,
                        artist = akey(artist(t)),
                        artistDisplay = trackArtistDisplay,
                        thumbnails = thumbs(t),
                        duration = duration(t),
                        durationSeconds = dur,
                        explicit = explicit(t),
                        type = type(t),
                        album = album(t)
                    )
                }
                c.score += (w / (1 + RANK_DECAY * rank)) * qualityMult
                c.sources.add(seed)
                if (seedArtist.isNotEmpty()) {
                    c.sourceArtists.add(seedArtist)
                }
            }
        }

        for (c in cands.values) {
            // Collaborative co-occurrence boost when multiple seeds lead to the same candidate
            c.score *= 1 + CO_OCCURRENCE_BOOST * (c.sources.size - 1)
            // Multi-artist seed convergence bonus: distinct seed artists agreeing on a track
            if (c.sourceArtists.size > 1) {
                c.score *= 1 + 0.12 * (c.sourceArtists.size - 1).coerceAtMost(3)
            }
            val share = (affinity[c.artist] ?: 0.0) / totalAff
            c.score *= 1 + min(0.6, share * 3)

            // Layer 1-4 direct language resolution, falling back to Layer 5 seed-cluster propagation
            val directLanguage = resolveLanguage(c.videoId, c.title, c.artistDisplay, languageOverrides)
            if (directLanguage != null) {
                c.inferredLanguage = directLanguage
                c.languageConfidence = 1.0
            } else {
                val langWeights = mutableMapOf<String, Double>()
                var totalSeedWeight = 0.0
                for (seedId in c.sources) {
                    val sw = (weights[seedId] ?: 1.0).coerceAtLeast(0.1)
                    totalSeedWeight += sw
                    val sl = seedLanguages[seedId]
                    if (sl != null) {
                        langWeights[sl] = (langWeights[sl] ?: 0.0) + sw
                    }
                }
                val topSeedLang = langWeights.maxByOrNull { it.value }
                if (topSeedLang != null && totalSeedWeight > 0.0 && (topSeedLang.value / totalSeedWeight) >= 0.65) {
                    c.inferredLanguage = topSeedLang.key
                    c.languageConfidence = 0.75
                }
            }

            val candidateLanguage = c.inferredLanguage
            val confidence = c.languageConfidence
            val candidateLanguageAffinity = candidateLanguage?.let { listenerAffinity[it] } ?: 0.0
            if (candidateLanguageAffinity > 0.0) {
                c.score *= 1 + LANGUAGE_AFFINITY_BOOST * candidateLanguageAffinity * confidence
            }
            if (!preferredContentLanguage.isNullOrBlank() &&
                candidateLanguage != null &&
                candidateLanguage.equals(preferredContentLanguage, ignoreCase = true)
            ) {
                c.score *= 1 + PREFERRED_LANGUAGE_BOOST * confidence
            }
            c.score *= timeOfDayVibeMultiplier(c.title, c.artistDisplay, hourOfDay)

            // Graduated shown demotion feature
            val count = shownCounts[c.videoId] ?: if (c.videoId in shown) 1 else 0
            if (count > 0) {
                var demotion = 1.0 / (1.0 + count * 0.5)
                val hasPlays = (weights[c.videoId] ?: 0.0) > 0.0
                if (count >= 3 && !hasPlays) {
                    demotion *= 0.2
                }
                c.score *= demotion
            }

            // A little noise so near-equal songs don't always appear in the same order.
            c.score *= 1 + random.nextDouble() * SCORE_JITTER
        }

        val everything = cands.values.toList()
        val mixes = mutableListOf<MixResult>()

        // 1. Made for you (Flagship personalized discovery mix with exploration slots)
        val exploreCandidates = everything
            .filter { it.artist.isNotEmpty() && it.artist !in knownArtists }
            .sortedByDescending { it.score }
            .toMutableList()

        val primaryList = diversifyMMR(everything, 30)
        val primaryQueue = primaryList.toMutableList()
        val main = mutableListOf<Candidate>()
        val mainSelectedIds = mutableSetOf<String>()

        while (main.size < 30 && (primaryQueue.isNotEmpty() || exploreCandidates.isNotEmpty())) {
            val nextSlotIndex = main.size
            if ((nextSlotIndex + 1) % 8 == 0 && exploreCandidates.any { it.videoId !in mainSelectedIds }) {
                val exp = exploreCandidates.first { it.videoId !in mainSelectedIds }
                exploreCandidates.remove(exp)
                exp.sources.add("explore")
                main.add(exp)
                mainSelectedIds.add(exp.videoId)
                primaryQueue.removeAll { it.videoId == exp.videoId }
            } else if (primaryQueue.isNotEmpty()) {
                val cand = primaryQueue.removeAt(0)
                if (cand.videoId !in mainSelectedIds) {
                    main.add(cand)
                    mainSelectedIds.add(cand.videoId)
                }
            } else {
                val rem = exploreCandidates.firstOrNull { it.videoId !in mainSelectedIds } ?: break
                exploreCandidates.remove(rem)
                rem.sources.add("explore")
                main.add(rem)
                mainSelectedIds.add(rem.videoId)
            }
        }

        if (main.size >= 8) {
            val subtitle = preferredLanguage?.let { "More $it music, based on what you play" }
                ?: "Built from what you play and save"
            val sourcesMap = main.associate {
                it.videoId to (if ("explore" in it.sources) "explore" else it.sources.firstOrNull().orEmpty())
            }
            mixes.add(MixResult("for-you", "Made for you", subtitle, main.map { it.videoId }, sourcesMap))
        }

        // 2. Fresh finds (Unheard artists discovery mix)
        val fresh = diversifyMMR(
            everything.filter { it.artist.isNotEmpty() && it.artist !in knownArtists },
            25
        )
        if (fresh.size >= 8) mixes.add(MixResult("fresh", "Fresh finds", "Artists you haven't played yet", fresh.map { it.videoId }))

        // 3. More like <artist> for top 2 seeding artists
        val seedByArtist = mutableMapOf<String, MutableList<String>>()
        for (s in activeSeeds) { val a = akey(meta[s]?.artist ?: ""); if (a.isNotEmpty()) seedByArtist.getOrPut(a) { mutableListOf() }.add(s) }
        val names = meta.values.filter { it.artist.isNotEmpty() }.associate { akey(it.artist) to it.artist }
        var shownArtistMixes = 0
        for ((artistKey, _) in affinity.entries.sortedByDescending { it.value }) {
            if (shownArtistMixes >= 2) break
            val aSeeds = seedByArtist[artistKey]?.toSet() ?: continue
            val pool = everything.filter { it.sources.any { s -> s in aSeeds } && it.artist != artistKey }
            val picked = diversifyMMR(pool, 20)
            if (picked.size >= 8) {
                shownArtistMixes++
                val display = names[artistKey] ?: artistKey.replaceFirstChar { it.uppercase() }
                mixes.add(MixResult("artist-$artistKey", "More like $display", "Similar sound, new tracks", picked.map { it.videoId }))
            }
        }

        // 4. Daily Vibe / Language Cluster Mixes (when sufficient distinct candidates exist)
        val topLanguages = (
            listOfNotNull(preferredContentLanguage) +
                listenerAffinity.entries
                    .filter { it.value >= 0.12 }
                    .sortedByDescending { it.value }
                    .map { it.key }
            ).distinct()
        for (lang in topLanguages.take(3)) {
            val langPool = everything.filter {
                (it.inferredLanguage ?: resolveLanguage(it.videoId, it.title, it.artistDisplay, languageOverrides)) == lang
            }
            val langPicked = diversifyMMR(langPool, 22, maxPerArtist = 3)
            if (langPicked.size >= 8) {
                mixes.add(
                    MixResult(
                        id = "daily-mix-${lang.lowercase()}",
                        title = "Daily Mix • $lang",
                        subtitle = "Your $lang favorites and sonic matches",
                        videoIds = langPicked.map { it.videoId }
                    )
                )
            }
        }

        return Pair(mixes, seedTitles)
    }
}
