package com.geekify.android.domain.recommend.eval

import com.geekify.android.domain.recommend.Recommender
import kotlin.math.ln

/**
 * Pure Kotlin offline evaluator for music recommendation quality.
 * Splits each listener's history by cutoffTimestamp:
 * - Train: events before cutoff
 * - Test: events after cutoff (ground truth future plays)
 *
 * Metrics measured:
 * - Hit Rate @ 10: Fraction of users whose top 10 contains >= 1 track from their future test plays.
 * - NDCG @ 10: Normalized Discounted Cumulative Gain accounting for rank position of relevant test tracks.
 * - Mean Completion Rate @ 10: Expected or actual completion rate of top-10 recommended tracks.
 * - Artist Diversity @ 10: Average number of unique artists in the top-10 recommended tracks.
 */
object RecommendationEvaluator {

    fun evaluate(
        listeners: List<EvalListener>,
        rankingFunction: (
            liked: List<Recommender.Signal>,
            recent: List<Recommender.Signal>,
            frequent: List<Recommender.PlayedSignal>,
            playlist: List<Recommender.Signal>,
            sessions: List<Recommender.SessionSignal>,
            candidatePool: Map<String, List<EvalTrack>>
        ) -> List<String>
    ): FullEvalReport {
        val userReports = mutableListOf<UserEvalResult>()

        for (listener in listeners) {
            val trainEvents = listener.history.filter { it.timestamp < listener.cutoffTimestamp }
            val testEvents = listener.history.filter { it.timestamp >= listener.cutoffTimestamp }
            val testTrackIds = testEvents.map { it.track.videoId }.toSet()

            // Build train signals
            val liked = trainEvents
                .filter { it.isLiked }
                .sortedByDescending { it.timestamp }
                .map { Recommender.Signal(it.track.videoId, it.track.artist, it.track.title) }

            val recent = trainEvents
                .sortedByDescending { it.timestamp }
                .map { Recommender.Signal(it.track.videoId, it.track.artist, it.track.title) }

            val playlist = trainEvents
                .filter { it.isPlaylist }
                .sortedByDescending { it.timestamp }
                .map { Recommender.Signal(it.track.videoId, it.track.artist, it.track.title) }

            val playCounts = trainEvents.groupBy { it.track.videoId }.mapValues { it.value.size }
            val frequent = playCounts.map { (vid, count) ->
                val track = trainEvents.first { it.track.videoId == vid }.track
                Recommender.PlayedSignal(vid, track.artist, track.title, count)
            }

            val sessions = trainEvents.groupBy { it.track.videoId }.map { (vid, events) ->
                val sample = events.first().track
                val compRate = events.count { it.completed }.toDouble() / events.size
                val skipRate = events.count { it.skipped }.toDouble() / events.size
                val recent7d = events.size
                val lastPlayed = events.maxOf { it.timestamp }
                Recommender.SessionSignal(
                    videoId = vid,
                    artist = sample.artist,
                    title = sample.title,
                    completionRate = compRate,
                    skipRate = skipRate,
                    recentPlays7d = recent7d,
                    lastPlayedAtMs = lastPlayed
                )
            }

            // Run ranking function
            val recommendedIds = rankingFunction(liked, recent, frequent, playlist, sessions, listener.candidatePool)
            val top10 = recommendedIds.take(10)

            // Hit rate & NDCG calculation
            val hits = top10.count { it in testTrackIds }
            val ndcg = computeNdcg(top10, testEvents)

            // Artist diversity
            val allCandidates = listener.candidatePool.values.flatten().associateBy { it.videoId }
            val distinctArtists = top10.mapNotNull { allCandidates[it]?.artist?.lowercase()?.trim() }.distinct().size
            val diversityRatio = if (top10.isNotEmpty()) distinctArtists.toDouble() / top10.size.toDouble() else 0.0

            // Completion rate in top 10
            val testEventsByTrack = testEvents.associateBy { it.track.videoId }
            val completionRates = top10.map { vid ->
                testEventsByTrack[vid]?.let { if (it.completed) 1.0 else 0.0 }
                    ?: if (vid in testTrackIds) 0.8 else 0.5 // Estimated engagement for unobserved candidate
            }
            val avgCompletion = if (completionRates.isNotEmpty()) completionRates.average() else 0.0

            userReports.add(
                UserEvalResult(
                    listenerId = listener.id,
                    persona = listener.persona,
                    top10 = top10,
                    hitCount = hits,
                    ndcg = ndcg,
                    artistDiversity = diversityRatio,
                    completionRate = avgCompletion
                )
            )
        }

        val avgHitRate = if (userReports.isNotEmpty()) {
            userReports.count { it.hitCount > 0 }.toDouble() / userReports.size
        } else 0.0

        val avgNdcg = if (userReports.isNotEmpty()) userReports.map { it.ndcg }.average() else 0.0
        val avgCompletion = if (userReports.isNotEmpty()) userReports.map { it.completionRate }.average() else 0.0
        val avgDiversity = if (userReports.isNotEmpty()) userReports.map { it.artistDiversity }.average() else 0.0

        val overallMetrics = EvalMetrics(
            hitRateAt10 = avgHitRate,
            ndcgAt10 = avgNdcg,
            meanCompletionRateAt10 = avgCompletion,
            artistDiversityAt10 = avgDiversity
        )

        return FullEvalReport(overallMetrics, userReports)
    }

    private fun computeNdcg(top10: List<String>, testEvents: List<EvalEvent>): Double {
        if (top10.isEmpty() || testEvents.isEmpty()) return 0.0

        val testMap = testEvents.associate { it.track.videoId to (if (it.completed) 2.0 else 1.0) }

        var dcg = 0.0
        for ((idx, vid) in top10.withIndex()) {
            val rel = testMap[vid] ?: 0.0
            if (rel > 0.0) {
                dcg += rel / (ln((idx + 2).toDouble()) / ln(2.0))
            }
        }

        // Ideal DCG: sorted test relevances
        val idealRels = testMap.values.sortedDescending().take(10)
        var idcg = 0.0
        for ((idx, rel) in idealRels.withIndex()) {
            idcg += rel / (ln((idx + 2).toDouble()) / ln(2.0))
        }

        return if (idcg > 0.0) (dcg / idcg).coerceIn(0.0, 1.0) else 0.0
    }
}
