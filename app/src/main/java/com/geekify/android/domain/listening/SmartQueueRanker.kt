package com.geekify.android.domain.listening

import com.geekify.android.data.local.ListeningSessionEntity
import com.geekify.android.data.local.PlayStat
import com.geekify.android.data.model.Track
import com.geekify.android.domain.recommend.Recommender
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

/** A radio candidate plus a transparent, user-facing reason for its placement. */
data class RankedQueueTrack(val track: Track, val reason: String, val score: Double)

/**
 * Pre-aggregated listening history signals so [SmartQueueRanker] does not need to re-scan
 * thousands of raw session entities on every track transition.
 */
data class QueueRankingSignals(
    val trackStats: Map<String, TrackSessionSummary>,
    val artistStats: Map<String, ArtistSessionSummary>,
    val playsByTrack: Map<String, Int>,
    val likedIds: Set<String>,
    val likedArtists: Set<String>,
    val hiddenTrackIds: Set<String> = emptySet(),
    val blockedArtists: Set<String> = emptySet(),
    val builtAtMs: Long
) {
    data class TrackSessionSummary(
        val totalRows: Int,
        val meaningfulOutcomes: Int,
        val completionRate: Double,
        val skipRate: Double,
        val fallbackPlayCount: Int,
        val recentRepeatCount: Int
    )

    data class ArtistSessionSummary(
        val totalRows: Int,
        val completionRate: Double,
        val skipRate: Double
    )
}

/**
 * Re-ranks only the new related-track batch. It never reorders or removes the current/manual queue.
 * Signals are local: meaningful play counts, completed sessions, skips, likes, artist session affinity,
 * language/vibe continuity, and artist diversity.
 */
object SmartQueueRanker {
    private const val MIN_MEANINGFUL_MS = 10_000L
    private const val MAX_RANKING_CANDIDATES = 60

    fun buildSignals(
        sessions: List<ListeningSessionEntity>,
        playCounts: List<PlayStat>,
        likedTracks: List<Track>,
        now: Long = System.currentTimeMillis()
    ): QueueRankingSignals {
        val likedIds = likedTracks.asSequence().map { it.videoId }.toSet()
        val likedArtists = likedTracks.asSequence()
            .flatMap { artistNames(it).asSequence() }
            .map(::key)
            .filter(String::isNotBlank)
            .toSet()
        return buildSignals(
            sessions = sessions,
            playCounts = playCounts,
            likedIds = likedIds,
            likedArtists = likedArtists,
            now = now
        )
    }

    fun buildSignals(
        sessions: List<ListeningSessionEntity>,
        playCounts: List<PlayStat>,
        likedIds: Set<String>,
        likedArtists: Collection<String>,
        hiddenTrackIds: Set<String> = emptySet(),
        blockedArtists: Collection<String> = emptySet(),
        now: Long = System.currentTimeMillis()
    ): QueueRankingSignals {
        val sevenDaysAgo = now - 7L * 24 * 60 * 60_000L
        val trackStats = sessions.groupBy { it.videoId }.mapValues { (_, rows) ->
            val meaningfulRows = rows.filter { it.listenedMs >= MIN_MEANINGFUL_MS }
            val outcomes = meaningfulRows.size
            val completionRate = if (outcomes == 0) 0.0 else meaningfulRows.count { it.completed }.toDouble() / outcomes
            val skipRows = rows.count { it.skipped }
            val skipRate = if (rows.isEmpty()) 0.0 else skipRows.toDouble() / rows.size
            val fallbackPlays = meaningfulRows.count { it.completed || it.listenedMs >= MIN_MEANINGFUL_MS }
            val recentRepeats = rows.count { it.startedAt >= sevenDaysAgo }
            QueueRankingSignals.TrackSessionSummary(
                totalRows = rows.size,
                meaningfulOutcomes = outcomes,
                completionRate = completionRate,
                skipRate = skipRate,
                fallbackPlayCount = fallbackPlays,
                recentRepeatCount = recentRepeats
            )
        }
        val artistStats = sessions.groupBy { key(it.artist) }
            .filterKeys(String::isNotBlank)
            .mapValues { (_, rows) ->
                val completionRate = if (rows.isEmpty()) 0.0 else rows.count { it.completed }.toDouble() / rows.size
                val skipRate = if (rows.isEmpty()) 0.0 else rows.count { it.skipped }.toDouble() / rows.size
                QueueRankingSignals.ArtistSessionSummary(
                    totalRows = rows.size,
                    completionRate = completionRate,
                    skipRate = skipRate
                )
            }
        val playsByTrack = playCounts.associate { it.videoId to it.plays }
        val normalizedLikedArtists = likedArtists.asSequence()
            .map(::key)
            .filter(String::isNotBlank)
            .toSet()
        val normalizedBlockedArtists = blockedArtists.asSequence()
            .map(::key)
            .filter(String::isNotBlank)
            .toSet()

        return QueueRankingSignals(
            trackStats = trackStats,
            artistStats = artistStats,
            playsByTrack = playsByTrack,
            likedIds = likedIds,
            likedArtists = normalizedLikedArtists,
            hiddenTrackIds = hiddenTrackIds,
            blockedArtists = normalizedBlockedArtists,
            builtAtMs = now
        )
    }

    /**
     * Executes CPU-intensive queue ranking on [dispatcher] (defaults to [Dispatchers.Default])
     * so it never blocks the main thread.
     */
    suspend fun rankAsync(
        candidates: List<Track>,
        current: Track,
        signals: QueueRankingSignals,
        upcoming: List<Track>,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ): List<RankedQueueTrack> = withContext(dispatcher) {
        currentCoroutineContext().ensureActive()
        rankWithSignals(
            candidates = candidates,
            current = current,
            signals = signals,
            upcoming = upcoming
        )
    }

    suspend fun rankAsync(
        candidates: List<Track>,
        current: Track,
        sessions: List<ListeningSessionEntity>,
        playCounts: List<PlayStat>,
        likedTracks: List<Track>,
        upcoming: List<Track>,
        now: Long = System.currentTimeMillis(),
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ): List<RankedQueueTrack> = withContext(dispatcher) {
        currentCoroutineContext().ensureActive()
        val signals = buildSignals(sessions, playCounts, likedTracks, now)
        currentCoroutineContext().ensureActive()
        rankWithSignals(candidates, current, signals, upcoming)
    }

    fun rank(
        candidates: List<Track>,
        current: Track,
        sessions: List<ListeningSessionEntity>,
        playCounts: List<PlayStat>,
        likedTracks: List<Track>,
        upcoming: List<Track>,
        now: Long = System.currentTimeMillis()
    ): List<RankedQueueTrack> {
        val signals = buildSignals(sessions, playCounts, likedTracks, now)
        return rankWithSignals(candidates, current, signals, upcoming)
    }

    fun rankWithSignals(
        candidates: List<Track>,
        current: Track,
        signals: QueueRankingSignals,
        upcoming: List<Track>
    ): List<RankedQueueTrack> {
        val alreadyQueued = (listOf(current) + upcoming).map { it.videoId }.toSet()
        val pool = candidates.distinctBy { it.videoId }
            .filter { it.videoId !in alreadyQueued }
            .filter { it.videoId !in signals.hiddenTrackIds }
            .filter { track ->
                val primary = artistNames(track).firstOrNull()?.let(::key).orEmpty()
                primary !in signals.blockedArtists
            }
            .toMutableList()
        if (pool.isEmpty()) return emptyList()

        val currentLanguage = Recommender.languageOf(current.title, current.artist)
        val artistCounts = mutableMapOf<String, Int>()
        (listOf(current) + upcoming).flatMap(::artistNames).map(::key).filter(String::isNotBlank).forEach { artist ->
            artistCounts[artist] = (artistCounts[artist] ?: 0) + 1
        }

        data class Scored(
            val track: Track,
            val baseScore: Double,
            val primaryArtist: String,
            val artistKeys: List<String>,
            val likedTrack: Boolean,
            val likedArtist: Boolean,
            val playCount: Int,
            val recentRepeatCount: Int,
            val outcomes: Int,
            val completionRate: Double,
            val languageMatched: Boolean
        )
        val staticScores = pool.associate { track ->
            val trackStat = signals.trackStats[track.videoId]
            val totalRows = trackStat?.totalRows ?: 0
            val outcomes = trackStat?.meaningfulOutcomes ?: 0
            val completionRate = trackStat?.completionRate ?: 0.0
            val skipRate = trackStat?.skipRate ?: 0.0
            val playCount = signals.playsByTrack[track.videoId]
                ?: (trackStat?.fallbackPlayCount ?: 0)
            val artistKeys = artistNames(track).map(::key).filter(String::isNotBlank)
            val primaryArtist = artistKeys.firstOrNull().orEmpty()
            val recentRepeatCount = trackStat?.recentRepeatCount ?: 0
            var score = 0.0
            val likedTrack = track.videoId in signals.likedIds
            val likedArtist = artistKeys.any { it in signals.likedArtists }
            if (likedTrack) score += 5.0
            if (likedArtist) score += 1.7
            if (playCount > 0) score += minOf(1.5, ln(playCount + 1.0) * 0.65)
            if (outcomes > 0) score += (completionRate - 0.5) * 2.6
            score -= skipRate * 2.8

            // Artist session affinity: reward artists whose songs the listener finishes
            if (totalRows == 0 && artistKeys.isNotEmpty()) {
                val matchingArtistSummaries = artistKeys.mapNotNull { signals.artistStats[it] }
                val totalArtistSessions = matchingArtistSummaries.sumOf { it.totalRows }
                if (totalArtistSessions > 0) {
                    val weightedComplete = matchingArtistSummaries.sumOf { it.completionRate * it.totalRows } / totalArtistSessions
                    val weightedSkip = matchingArtistSummaries.sumOf { it.skipRate * it.totalRows } / totalArtistSessions
                    score += (weightedComplete - 0.5) * 0.75 - weightedSkip * 0.85
                }
            }

            // Language / script session continuity
            val trackLang = Recommender.languageOf(track.title, track.artist)
            val languageMatched = currentLanguage != null && trackLang == currentLanguage
            if (languageMatched) {
                score += 0.90
            }

            // Smooth pacing bonus when track lengths are in a similar sonic window
            val curDur = current.durationSeconds
            val candDur = track.durationSeconds
            if (curDur != null && candDur != null && curDur > 0 && candDur > 0) {
                val diff = abs(curDur - candDur)
                if (diff <= 45) score += 0.15
            }

            // Reward familiar tracks a little, but avoid recommending something just heard repeatedly.
            score -= minOf(2.0, maxOf(0, recentRepeatCount - 1) * 0.45)
            track.videoId to Scored(
                track = track,
                baseScore = score,
                primaryArtist = primaryArtist,
                artistKeys = artistKeys,
                likedTrack = likedTrack,
                likedArtist = likedArtist,
                playCount = playCount,
                recentRepeatCount = recentRepeatCount,
                outcomes = outcomes,
                completionRate = completionRate,
                languageMatched = languageMatched
            )
        }
        val activePool = if (pool.size > MAX_RANKING_CANDIDATES) {
            pool.sortedByDescending { staticScores[it.videoId]?.baseScore ?: Double.NEGATIVE_INFINITY }
                .take(MAX_RANKING_CANDIDATES)
                .toMutableList()
        } else {
            pool
        }
        val ranked = mutableListOf<RankedQueueTrack>()
        while (activePool.isNotEmpty()) {
            var bestIndex = -1
            var bestScored: Scored? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (i in activePool.indices) {
                val candidate = activePool[i]
                val scored = staticScores[candidate.videoId] ?: continue
                val primaryArtist = scored.primaryArtist
                val artistCount = artistCounts[primaryArtist] ?: 0
                val diversityPenalty = artistCount * 1.0
                val discoveryNudge = if (primaryArtist.isNotBlank() && artistCount == 0) 0.65 else 0.0
                val score = scored.baseScore - diversityPenalty + discoveryNudge
                if (score > bestScore) {
                    bestIndex = i
                    bestScored = scored
                    bestScore = score
                }
            }
            val scored = bestScored ?: break
            val selected = scored.track
            val primaryArtist = scored.primaryArtist
            val reason = when {
                scored.likedTrack -> "You liked this track"
                scored.likedArtist -> "From an artist you like"
                scored.playCount >= 3 && scored.recentRepeatCount <= 1 -> "You return to this track"
                scored.outcomes >= 2 && scored.completionRate >= 0.75 -> "You often finish this track"
                scored.languageMatched -> "Matches your current $currentLanguage vibe"
                primaryArtist.isNotBlank() && (artistCounts[primaryArtist] ?: 0) == 0 -> "A different artist to keep the queue varied"
                else -> "Related to the track you’re playing"
            }
            ranked += RankedQueueTrack(selected, reason, bestScore)
            activePool.removeAt(bestIndex)
            scored.artistKeys.forEach { artist ->
                artistCounts[artist] = (artistCounts[artist] ?: 0) + 1
            }
        }
        return ranked
    }

    private fun artistNames(track: Track): List<String> =
        track.artists.map { it.name.trim() }.filter(String::isNotBlank).ifEmpty {
            track.artist.trim().takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
        }

    private fun key(value: String): String = value.trim().lowercase(Locale.ROOT)
}
