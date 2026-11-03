package com.geekify.android.data.local

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecommendationLogRepository @Inject constructor(
    private val dao: RecommendationLogDao
) {
    data class ImpressionItem(
        val videoId: String,
        val position: Int,
        val sourceSeed: String?,
        val score: Double
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
                shownAt = shownAt
            )
        }
        return dao.insertImpressions(entities)
    }

    suspend fun recordOutcome(
        videoId: String,
        event: RecommendationOutcomeEvent,
        at: Long = System.currentTimeMillis(),
        lookbackMs: Long = 30L * 24 * 60 * 60_000L
    ): Long? {
        val impression = dao.findRecentImpressionForVideo(videoId, at - lookbackMs) ?: return null
        return dao.insertOutcome(
            RecommendationOutcomeEntity(
                impressionId = impression.id,
                event = event.name,
                at = at
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
