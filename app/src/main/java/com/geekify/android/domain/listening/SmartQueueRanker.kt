package com.geekify.android.domain.listening

import com.geekify.android.data.local.ListeningSessionEntity
import com.geekify.android.data.local.PlayStat
import com.geekify.android.data.model.Track
import com.geekify.android.domain.recommend.Recommender
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

/** A radio candidate plus a transparent, user-facing reason for its placement. */
data class RankedQueueTrack(val track: Track, val reason: String, val score: Double)

/**
 * Re-ranks only the new related-track batch. It never reorders or removes the current/manual queue.
 * Signals are local: meaningful play counts, completed sessions, skips, likes, artist session affinity,
 * language/vibe continuity, and artist diversity.
 */
object SmartQueueRanker {
    private const val MIN_MEANINGFUL_MS = 10_000L

    fun rank(
        candidates: List<Track>,
        current: Track,
        sessions: List<ListeningSessionEntity>,
        playCounts: List<PlayStat>,
        likedTracks: List<Track>,
        upcoming: List<Track>,
        now: Long = System.currentTimeMillis()
    ): List<RankedQueueTrack> {
        val alreadyQueued = (listOf(current) + upcoming).map { it.videoId }.toSet()
        val pool = candidates.distinctBy { it.videoId }.filter { it.videoId !in alreadyQueued }.toMutableList()
        if (pool.isEmpty()) return emptyList()

        val sessionsByTrack = sessions.groupBy { it.videoId }
        val sessionsByArtist = sessions.groupBy { key(it.artist) }.filterKeys(String::isNotBlank)
        val playsByTrack = playCounts.associate { it.videoId to it }
        val likedIds = likedTracks.map { it.videoId }.toSet()
        val likedArtists = likedTracks.flatMap { artistNames(it) }.map(::key).filter(String::isNotBlank).toSet()
        val currentLanguage = Recommender.languageOf(current.title, current.artist)
        val artistCounts = mutableMapOf<String, Int>()
        (listOf(current) + upcoming).flatMap(::artistNames).map(::key).filter(String::isNotBlank).forEach { artist ->
            artistCounts[artist] = (artistCounts[artist] ?: 0) + 1
        }

        data class Scored(
            val track: Track,
            val baseScore: Double,
            val likedTrack: Boolean,
            val likedArtist: Boolean,
            val playCount: Int,
            val recentRepeatCount: Int,
            val outcomes: Int,
            val completionRate: Double,
            val languageMatched: Boolean
        )
        val staticScores = pool.associate { track ->
            val rows = sessionsByTrack[track.videoId].orEmpty()
            val meaningfulRows = rows.filter { it.listenedMs >= MIN_MEANINGFUL_MS }
            val outcomes = meaningfulRows.size
            val completionRate = if (outcomes == 0) 0.0 else meaningfulRows.count { it.completed }.toDouble() / outcomes
            val skipRows = rows.count { it.skipped }
            val skipRate = if (rows.isEmpty()) 0.0 else skipRows.toDouble() / rows.size
            val playCount = playsByTrack[track.videoId]?.plays
                ?: meaningfulRows.count { it.completed || it.listenedMs >= MIN_MEANINGFUL_MS }
            val artistKeys = artistNames(track).map(::key).filter(String::isNotBlank)
            val recentRepeatCount = rows.count { it.startedAt >= now - 7L * 24 * 60 * 60_000L }
            var score = 0.0
            val likedTrack = track.videoId in likedIds
            val likedArtist = artistKeys.any { it in likedArtists }
            if (likedTrack) score += 5.0
            if (likedArtist) score += 1.7
            if (playCount > 0) score += minOf(1.5, ln(playCount + 1.0) * 0.65)
            if (outcomes > 0) score += (completionRate - 0.5) * 2.6
            score -= skipRate * 2.8

            // Artist session affinity: reward artists whose songs the listener finishes
            val artistRows = artistKeys.flatMap { sessionsByArtist[it].orEmpty() }
            if (artistRows.isNotEmpty() && rows.isEmpty()) {
                val artistComplete = artistRows.count { it.completed }.toDouble() / artistRows.size
                val artistSkip = artistRows.count { it.skipped }.toDouble() / artistRows.size
                score += (artistComplete - 0.5) * 0.75 - artistSkip * 0.85
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
            track.videoId to Scored(track, score, likedTrack, likedArtist, playCount, recentRepeatCount, outcomes, completionRate, languageMatched)
        }
        val ranked = mutableListOf<RankedQueueTrack>()
        while (pool.isNotEmpty()) {
            var best: Track? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (candidate in pool) {
                val scored = staticScores[candidate.videoId] ?: continue
                val primaryArtist = artistNames(candidate).firstOrNull()?.let(::key).orEmpty()
                val artistCount = artistCounts[primaryArtist] ?: 0
                val diversityPenalty = artistCount * 1.0
                val discoveryNudge = if (primaryArtist.isNotBlank() && artistCount == 0) 0.65 else 0.0
                val score = scored.baseScore - diversityPenalty + discoveryNudge
                if (score > bestScore) {
                    best = candidate
                    bestScore = score
                }
            }
            val selected = best ?: break
            val scored = staticScores[selected.videoId] ?: break
            val primaryArtist = artistNames(selected).firstOrNull()?.let(::key).orEmpty()
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
            pool.removeAll { it.videoId == selected.videoId }
            artistNames(selected).map(::key).filter(String::isNotBlank).forEach { artist ->
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
