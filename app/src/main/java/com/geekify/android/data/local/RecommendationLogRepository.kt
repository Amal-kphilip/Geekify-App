package com.geekify.android.data.local

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecommendationLogRepository @Inject constructor(
    private val dao: RecommendationLogDao
) {
    private val recentOutcomeDedupe = ConcurrentHashMap<String, Long>()

    data class ImpressionItem(
        val videoId: String,
        val position: Int,
        val sourceSeed: String?,
        val score: Double,
        val candidateSource: String? = null,
        val modelVersion: String = "v2.4-hybrid",
        val experimentGroup: String = "CONTROL"
    )

    suspend fun recordImpressions(
        accountId: String?,
        requestId: String,
        mixId: String,
        items: List<ImpressionItem>,
        shownAt: Long = System.currentTimeMillis()
    ): List<Long> {
        if (items.isEmpty()) return emptyList()
        val entities = items.map { item ->
            RecommendationImpressionEntity(
                accountId = accountId,
                requestId = requestId,
                mixId = mixId,
                videoId = item.videoId,
                position = item.position,
                sourceSeed = item.sourceSeed,
                score = item.score,
                shownAt = shownAt,
                candidateSource = item.candidateSource,
                modelVersion = item.modelVersion,
                experimentGroup = item.experimentGroup
            )
        }
        return dao.insertImpressions(entities)
    }

    suspend fun recordOutcome(
        videoId: String,
        event: RecommendationOutcomeEvent,
        at: Long = System.currentTimeMillis(),
        lookbackMs: Long = 30L * 24 * 60 * 60_000L,
        accountId: String? = null,
        playDurationMs: Long = 0L,
        requireMatchingAccount: Boolean = false
    ): Long? {
        val impression = dao.findRecentImpressionForVideo(videoId, at - lookbackMs) ?: return null
        if (requireMatchingAccount && impression.accountId != accountId) {
            return null
        }
        val dedupeKey = "${impression.id}:${event.name}:${at / 5_000L}"
        val previousAt = recentOutcomeDedupe.putIfAbsent(dedupeKey, at)
        if (previousAt != null && (at - previousAt) < 5_000L) {
            return null
        }
        return dao.insertOutcome(
            RecommendationOutcomeEntity(
                impressionId = impression.id,
                event = event.name,
                at = at,
                accountId = accountId ?: impression.accountId,
                playDurationMs = playDurationMs,
                dedupeKey = dedupeKey
            )
        )
    }

    suspend fun getImpressionCountsSince(sinceTimestamp: Long): Map<String, Int> =
        dao.getImpressionCountsSince(sinceTimestamp).associate { it.videoId to it.count }

    suspend fun pruneOlderThan(cutoffTimestamp: Long): Int = dao.pruneImpressionsOlderThan(cutoffTimestamp)

    suspend fun getImpressionsForRequest(requestId: String): List<RecommendationImpressionEntity> =
        dao.getImpressionsForRequest(requestId)

    suspend fun getOutcomesForImpression(impressionId: Long): List<RecommendationOutcomeEntity> =
        dao.getOutcomesForImpression(impressionId)
}
