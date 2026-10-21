package com.geekify.android.domain.recommend

import kotlin.math.ln
import kotlin.math.min
import kotlin.random.Random

/**
 * Pure Kotlin port of backend/app/services/recommender.py upgraded with
 * behavioral session signals (completion rate, skip penalty, repeat velocity), time-of-day
 * vibe affinity, and multi-cluster Daily Mixes.
 * No Android imports. Testable with plain JUnit.
 */
object Recommender {
    const val LIKE_WEIGHT = 1.6
    const val LIKE_DECAY = 0.96
    const val RECENT_WEIGHT = 1.0
    const val RECENT_DECAY = 0.90
    const val RANK_DECAY = 0.12
    const val CO_OCCURRENCE_BOOST = 0.35
    const val MAX_SEEDS = 6
    const val MAX_SEEDS_PER_ARTIST = 2
    const val MAX_TRACK_SECONDS = 15 * 60
    /** Language is a strong preference signal, but never an absolute filter. */
    private const val LANGUAGE_AFFINITY_BOOST = 0.70
    /** Language preference also has a small influence on which songs become radio seeds. */
    private const val LANGUAGE_SEED_BOOST = 0.45
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

    data class Signal(val videoId: String, val artist: String = "", val title: String = "")

    /** A song with how many times it has been listened to. */
    data class PlayedSignal(
        val videoId: String,
        val artist: String = "",
        val title: String = "",
        val plays: Int = 1
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
        val sources: MutableSet<String> = mutableSetOf()
    )

    data class MixResult(val id: String, val title: String, val subtitle: String, val videoIds: List<String>)

    private fun akey(name: String?) = (name ?: "").trim().lowercase()

    /**
     * Infers the language where titles or artists use a distinct Indic/Asian script. Latin
     * titles are deliberately left unknown rather than guessed, so they don't distort a
     * listener's actual preference.
     */
    fun languageOf(title: String?, artist: String?): String? {
        val text = "${title.orEmpty()} ${artist.orEmpty()}"
        return when {
            text.any { it in '\u0D00'..'\u0D7F' } -> "Malayalam"
            text.any { it in '\u0B80'..'\u0BFF' } -> "Tamil"
            text.any { it in '\u0C00'..'\u0C7F' } -> "Telugu"
            text.any { it in '\u0A00'..'\u0A7F' } -> "Punjabi"
            text.any { it in '\u0900'..'\u097F' } -> "Hindi"
            text.any { it in '\uAC00'..'\uD7AF' } -> "K-pop"
            text.any { it in '\u3040'..'\u30FF' } -> "Japanese"
            else -> null
        }
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
        playlist: List<Signal> = emptyList()
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
                languageOf(signal.title, signal.artist),
                LIKE_LANGUAGE_WEIGHT * Math.pow(LIKE_DECAY, index.toDouble())
            )
        }
        playlist.forEachIndexed { index, signal ->
            add(
                longTermRaw,
                languageOf(signal.title, signal.artist),
                PLAYLIST_LANGUAGE_WEIGHT * Math.pow(PLAYLIST_DECAY, index.toDouble())
            )
        }
        frequent.forEach { signal ->
            val repeatedWeight = FREQUENT_LANGUAGE_WEIGHT * ln(1.0 + signal.plays.coerceAtLeast(1))
            add(longTermRaw, languageOf(signal.title, signal.artist), repeatedWeight)
        }
        recent.forEachIndexed { index, signal ->
            add(
                recentRaw,
                languageOf(signal.title, signal.artist),
                RECENT_WEIGHT * Math.pow(RECENT_DECAY, index.toDouble())
            )
        }
        recent.take(SESSION_LANGUAGE_LIMIT).forEachIndexed { index, signal ->
            add(
                sessionRaw,
                languageOf(signal.title, signal.artist),
                SESSION_LANGUAGE_WEIGHT * Math.pow(0.80, index.toDouble())
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
        sessions: List<SessionSignal> = emptyList()
    ): WeightResult {
        val weights = mutableMapOf<String, Double>()
        val meta = mutableMapOf<String, Signal>()
        val affinity = mutableMapOf<String, Double>()
        frequent.forEach { s ->
            val w = FREQUENT_WEIGHT * Math.log(1.0 + s.plays.coerceAtLeast(1))
            weights[s.videoId] = (weights[s.videoId] ?: 0.0) + w
            meta.putIfAbsent(s.videoId, Signal(s.videoId, s.artist, s.title))
            val ak = akey(s.artist); if (ak.isNotEmpty()) affinity[ak] = (affinity[ak] ?: 0.0) + w
        }
        playlist.forEachIndexed { i, s ->
            val w = PLAYLIST_WEIGHT * Math.pow(PLAYLIST_DECAY, i.toDouble())
            weights[s.videoId] = (weights[s.videoId] ?: 0.0) + w
            meta.putIfAbsent(s.videoId, s)
            val ak = akey(s.artist); if (ak.isNotEmpty()) affinity[ak] = (affinity[ak] ?: 0.0) + w
        }
        savedArtists.forEach { name ->
            val ak = akey(name); if (ak.isNotEmpty()) affinity[ak] = (affinity[ak] ?: 0.0) + SAVED_ARTIST_WEIGHT
        }
        liked.forEachIndexed { i, s ->
            val w = LIKE_WEIGHT * Math.pow(LIKE_DECAY, i.toDouble())
            weights[s.videoId] = (weights[s.videoId] ?: 0.0) + w
            meta.putIfAbsent(s.videoId, s)
            val ak = akey(s.artist); if (ak.isNotEmpty()) affinity[ak] = (affinity[ak] ?: 0.0) + w
        }
        recent.forEachIndexed { i, s ->
            val w = RECENT_WEIGHT * Math.pow(RECENT_DECAY, i.toDouble())
            weights[s.videoId] = (weights[s.videoId] ?: 0.0) + w
            meta.putIfAbsent(s.videoId, s)
            val ak = akey(s.artist); if (ak.isNotEmpty()) affinity[ak] = (affinity[ak] ?: 0.0) + w
        }
        // Apply session completion / skip / repeat-velocity adjustments when available
        if (sessions.isNotEmpty()) {
            sessions.forEach { sess ->
                meta.putIfAbsent(sess.videoId, Signal(sess.videoId, sess.artist, sess.title))
                val ak = akey(sess.artist)
                val completionBonus = (sess.completionRate - 0.5).coerceIn(-0.4, 0.5) * 0.85
                val skipPenalty = sess.skipRate.coerceIn(0.0, 1.0) * 0.75
                val repeatBoost = if (sess.recentPlays7d >= 2) ln(1.0 + sess.recentPlays7d) * 0.45 else 0.0
                val netDelta = completionBonus - skipPenalty + repeatBoost
                val currentW = weights[sess.videoId] ?: 0.0
                if (currentW > 0.0 || netDelta > 0.25) {
                    val updated = (currentW + netDelta).coerceAtLeast(if (currentW > 0.0) currentW * 0.35 else 0.0)
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
                val language = languageOf(signal.title, signal.artist)
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
     * Like [pickSeeds], but always keeps the two strongest seeds and fills the rest by weighted random
     * choice from the next-best ones. Two refreshes therefore ask YouTube Music for different "radios"
     * and the mixes stay fresh, while still being driven by what the person actually plays.
     */
    fun pickSeedsVaried(
        weights: Map<String, Double>,
        meta: Map<String, Signal>,
        limit: Int = MAX_SEEDS,
        random: Random = Random.Default
    ): List<String> {
        val ranked = pickSeeds(weights, meta, limit * 3)
        if (ranked.size <= limit) return ranked
        val picked = ranked.take(2).toMutableList()
        val pool = ranked.drop(2).toMutableList()
        while (picked.size < limit && pool.isNotEmpty()) {
            val total = pool.sumOf { weights[it] ?: 0.0 }
            var r = random.nextDouble() * total
            var idx = pool.indexOfFirst { r -= (weights[it] ?: 0.0); r <= 0.0 }
            if (idx < 0) idx = pool.lastIndex
            picked.add(pool.removeAt(idx))
        }
        return picked
    }

    fun diversify(
        cands: List<Candidate>,
        n: Int,
        maxPerArtist: Int = 3,
        decay: Double = 0.65,
        languageDecay: Double = 1.0
    ): List<Candidate> {
        val pool = cands.toMutableList()
        val out = mutableListOf<Candidate>()
        val artistCount = mutableMapOf<String, Int>()
        val languageCount = mutableMapOf<String, Int>()
        while (pool.isNotEmpty() && out.size < n) {
            var best: Candidate? = null
            var bestEff = -1.0
            for (c in pool) {
                val artistOccurrences = artistCount[c.artist] ?: 0
                if (c.artist.isNotEmpty() && artistOccurrences >= maxPerArtist) continue
                val language = languageOf(c.title, c.artistDisplay)
                val languageOccurrences = language?.let { languageCount[it] } ?: 0
                val languageFactor = if (language != null) {
                    Math.pow(languageDecay, languageOccurrences.toDouble())
                } else {
                    1.0
                }
                val eff = c.score *
                    Math.pow(decay, artistOccurrences.toDouble()) *
                    languageFactor
                if (eff > bestEff) { best = c; bestEff = eff }
            }
            best ?: break
            out.add(best); pool.remove(best)
            artistCount[best.artist] = (artistCount[best.artist] ?: 0) + 1
            languageOf(best.title, best.artistDisplay)?.let { language ->
                languageCount[language] = (languageCount[language] ?: 0) + 1
            }
        }
        return out
    }

    /**
     * Context-aware time-of-day vibe multiplier for dayparting recommendations.
     * Gently boosts tracks whose title/artist cues align with the current time of day.
     */
    fun timeOfDayVibeMultiplier(title: String, artist: String, hourOfDay: Int?): Double {
        val hour = hourOfDay ?: return 1.0
        val text = "${title.lowercase()} ${artist.lowercase()}"
        return when (hour) {
            in 5..11 -> when {
                MORNING_KEYWORDS.any { text.contains(it) } -> 1.14
                LATE_NIGHT_HEAVY_KEYWORDS.any { text.contains(it) } -> 0.94
                else -> 1.0
            }
            in 12..16 -> when {
                AFTERNOON_KEYWORDS.any { text.contains(it) } -> 1.12
                else -> 1.0
            }
            in 17..21 -> when {
                EVENING_KEYWORDS.any { text.contains(it) } -> 1.15
                else -> 1.0
            }
            else -> when {
                NIGHT_KEYWORDS.any { text.contains(it) } -> 1.16
                else -> 1.0
            }
        }
    }

    private val MORNING_KEYWORDS = listOf("acoustic", "morning", "melody", "unplugged", "breeze", "sunshine", "classic", "peaceful")
    private val AFTERNOON_KEYWORDS = listOf("focus", "lofi", "lo-fi", "chill", "groove", "instrumental", "beats", "vibe")
    private val EVENING_KEYWORDS = listOf("party", "dance", "remix", "workout", "bass", "club", "hit", "anthem", "energetic")
    private val NIGHT_KEYWORDS = listOf("night", "lofi", "lo-fi", "slowed", "reverb", "chill", "romantic", "unplugged", "ambient", "acoustic", "melody")
    private val LATE_NIGHT_HEAVY_KEYWORDS = listOf("hardstyle", "rave", "workout")

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
        /** The seeds that [related] was fetched for. When null the strongest seeds are used. */
        seeds: List<String>? = null,
        random: Random = Random.Default,
        /** Optional behavioral session quality signals (completion rate, skips, 7-day repeat velocity). */
        sessions: List<SessionSignal> = emptyList(),
        /** Optional current hour of day (0..23) for dayparting vibe boost. */
        hourOfDay: Int? = null,
        /** Optional content-language preference from Settings to nudge regional discovery. */
        preferredContentLanguage: String? = null
    ): Pair<List<MixResult>, List<String>> {
        val languageProfile = languageAffinity(liked, recent, frequent, playlist)
        val mergedLanguageAffinity = languageProfile.affinity.toMutableMap()
        if (!preferredContentLanguage.isNullOrBlank()) {
            val current = mergedLanguageAffinity[preferredContentLanguage] ?: 0.0
            mergedLanguageAffinity[preferredContentLanguage] = (current + 0.35).coerceAtMost(1.0)
        }
        val preferredLanguage = mergedLanguageAffinity.maxByOrNull { it.value }?.key
        val (weights, meta, affinity) = seedWeights(
            liked,
            recent,
            frequent,
            playlist,
            savedArtists,
            languageAffinity = mergedLanguageAffinity,
            sessions = sessions
        )
        if (weights.isEmpty()) return Pair(emptyList(), emptyList())
        val activeSeeds = seeds ?: pickSeeds(weights, meta)
        val topW = activeSeeds.maxOfOrNull { weights[it] ?: 0.0 }?.takeIf { it > 0.0 } ?: 1.0
        val known = weights.keys + exclude
        val knownArtists = affinity.keys.toSet()
        val totalAff = affinity.values.sum().let { if (it == 0.0) 1.0 else it }

        // Artist-level completion / skip modifier from session history
        val artistSessionMod = if (sessions.isNotEmpty()) {
            sessions.groupBy { akey(it.artist) }
                .filterKeys { it.isNotEmpty() }
                .mapValues { (_, list) ->
                    val avgComp = list.map { it.completionRate }.average()
                    val avgSkip = list.map { it.skipRate }.average()
                    (1.0 + (avgComp - 0.5) * 0.35 - avgSkip * 0.40).coerceIn(0.55, 1.35)
                }
        } else {
            emptyMap()
        }

        val cands = mutableMapOf<String, Candidate>()
        for (seed in activeSeeds) {
            val w = (weights[seed] ?: 0.0) / topW
            val tracks = related[seed] ?: continue
            tracks.take(40).forEachIndexed { rank, t ->
                val vid = videoId(t)
                if (vid.isEmpty() || vid in known) return@forEachIndexed
                val dur = durationSeconds(t)
                if (dur != null && dur > MAX_TRACK_SECONDS) return@forEachIndexed
                val c = cands.getOrPut(vid) {
                    Candidate(vid, title(t), akey(artist(t)), artistDisplay(t), thumbs(t), duration(t), dur, explicit(t), type(t))
                }
                c.score += w / (1 + RANK_DECAY * rank)
                c.sources.add(seed)
            }
        }

        for (c in cands.values) {
            // Collaborative co-occurrence boost when multiple seeds lead to the same candidate
            c.score *= 1 + CO_OCCURRENCE_BOOST * (c.sources.size - 1)
            val share = (affinity[c.artist] ?: 0.0) / totalAff
            c.score *= 1 + min(0.6, share * 3)
            artistSessionMod[c.artist]?.let { mod -> c.score *= mod }
            val candidateLanguage = languageOf(c.title, c.artistDisplay)
            val candidateLanguageAffinity = candidateLanguage?.let { mergedLanguageAffinity[it] } ?: 0.0
            if (candidateLanguageAffinity > 0.0) {
                c.score *= 1 + LANGUAGE_AFFINITY_BOOST * candidateLanguageAffinity
            }
            c.score *= timeOfDayVibeMultiplier(c.title, c.artistDisplay, hourOfDay)
            if (c.videoId in shown) c.score *= SHOWN_PENALTY
            // A little noise so near-equal songs don't always appear in the same order.
            c.score *= 1 + random.nextDouble() * SCORE_JITTER
        }

        val everything = cands.values.toList()
        val mixes = mutableListOf<MixResult>()
        val languageDiversity = if (mergedLanguageAffinity.count { it.value >= 0.12 } >= 2) {
            LANGUAGE_DIVERSITY_DECAY
        } else {
            1.0
        }

        // 1. Made for you (Flagship personalized discovery mix)
        val main = diversify(everything, 30, languageDecay = languageDiversity)
        if (main.size >= 8) {
            val subtitle = preferredLanguage?.let { "More $it music, based on what you play" }
                ?: "Built from what you play and save"
            mixes.add(MixResult("for-you", "Made for you", subtitle, main.map { it.videoId }))
        }

        // 2. Fresh finds (Unheard artists discovery mix)
        val fresh = diversify(
            everything.filter { it.artist.isNotEmpty() && it.artist !in knownArtists },
            25,
            languageDecay = languageDiversity
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
            val picked = diversify(pool, 20, languageDecay = languageDiversity)
            if (picked.size >= 8) {
                shownArtistMixes++
                val display = names[artistKey] ?: artistKey.replaceFirstChar { it.uppercase() }
                mixes.add(MixResult("artist-$artistKey", "More like $display", "Similar sound, new tracks", picked.map { it.videoId }))
            }
        }

        // 4. Daily Vibe / Language Cluster Mixes (when sufficient distinct candidates exist)
        val topLanguages = mergedLanguageAffinity.entries.sortedByDescending { it.value }.map { it.key }
        for (lang in topLanguages.take(2)) {
            val langPool = everything.filter { languageOf(it.title, it.artistDisplay) == lang }
            val langPicked = diversify(langPool, 22, maxPerArtist = 3)
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

        val seedTitles = activeSeeds.mapNotNull { meta[it]?.title?.takeIf { t -> t.isNotEmpty() } }
        return Pair(mixes, seedTitles)
    }
}
