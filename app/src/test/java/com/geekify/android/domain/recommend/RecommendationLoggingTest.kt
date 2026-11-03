package com.geekify.android.domain.recommend

import androidx.sqlite.db.SupportSQLiteDatabase
import com.geekify.android.data.local.MIGRATION_7_8
import com.geekify.android.data.local.RecommendationImpressionEntity
import com.geekify.android.data.local.RecommendationLogDao
import com.geekify.android.data.local.RecommendationLogRepository
import com.geekify.android.data.local.RecommendationOutcomeEntity
import com.geekify.android.data.local.RecommendationOutcomeEvent
import com.geekify.android.data.local.VideoImpressionCount
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class RecommendationLoggingTest {

    @Test
    fun migration7to8CreatesImpressionsAndOutcomesTablesWithIndexes() {
        val executedStatements = mutableListOf<String>()

        val dbProxy = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            object : InvocationHandler {
                override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
                    if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                        executedStatements.add(args[0] as String)
                    }
                    return null
                }
            }
        ) as SupportSQLiteDatabase

        MIGRATION_7_8.migrate(dbProxy)

        assertTrue(executedStatements.any { it.contains("CREATE TABLE IF NOT EXISTS `recommendation_impressions`") })
        assertTrue(executedStatements.any { it.contains("CREATE TABLE IF NOT EXISTS `recommendation_outcomes`") })
        assertTrue(executedStatements.any { it.contains("index_recommendation_impressions_videoId_shownAt") })
        assertTrue(executedStatements.any { it.contains("index_recommendation_outcomes_impressionId") })
    }

    @Test
    fun playedTrackLinksToMostRecentImpression() = runBlocking {
        val fakeDao = FakeRecommendationLogDao()
        val repo = RecommendationLogRepository(fakeDao)

        // 1. Record impression of mix
        val shownAt = System.currentTimeMillis() - 5000L
        repo.recordImpressions(
            accountId = "user123",
            requestId = "req-uuid-1",
            mixId = "for-you",
            items = listOf(
                RecommendationLogRepository.ImpressionItem("vid_abc", 0, "seed_1", 4.5),
                RecommendationLogRepository.ImpressionItem("vid_def", 1, "seed_1", 3.8)
            ),
            shownAt = shownAt
        )

        // 2. Play the track
        val outcomeId = repo.recordOutcome("vid_abc", RecommendationOutcomeEvent.PLAYED)
        assertNotNull(outcomeId)

        val outcomes = fakeDao.outcomes
        assertEquals(1, outcomes.size)
        assertEquals(fakeDao.impressions.first().id, outcomes.first().impressionId)
        assertEquals("PLAYED", outcomes.first().event)
    }

    @Test
    fun retentionDeletesImpressionsOlderThan30DaysAndCascadesOutcomes() = runBlocking {
        val fakeDao = FakeRecommendationLogDao()
        val repo = RecommendationLogRepository(fakeDao)

        val oldTimestamp = System.currentTimeMillis() - (35L * 24 * 60 * 60 * 1000L)
        val freshTimestamp = System.currentTimeMillis() - (5L * 24 * 60 * 60 * 1000L)

        repo.recordImpressions(
            accountId = "user123",
            requestId = "req-old",
            mixId = "for-you",
            items = listOf(RecommendationLogRepository.ImpressionItem("vid_old", 0, null, 1.0)),
            shownAt = oldTimestamp
        )

        repo.recordImpressions(
            accountId = "user123",
            requestId = "req-fresh",
            mixId = "for-you",
            items = listOf(RecommendationLogRepository.ImpressionItem("vid_fresh", 0, null, 1.0)),
            shownAt = freshTimestamp
        )

        repo.recordOutcome("vid_old", RecommendationOutcomeEvent.COMPLETED, at = oldTimestamp + 1000)
        repo.recordOutcome("vid_fresh", RecommendationOutcomeEvent.PLAYED, at = freshTimestamp + 1000)

        assertEquals(2, fakeDao.impressions.size)
        assertEquals(2, fakeDao.outcomes.size)

        // Prune older than 30 days
        val cutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000L)
        val pruned = repo.pruneOlderThan(cutoff)

        assertEquals(1, pruned)
        assertEquals(1, fakeDao.impressions.size)
        assertEquals("vid_fresh", fakeDao.impressions.first().videoId)
        assertEquals(1, fakeDao.outcomes.size)
    }

    private class FakeRecommendationLogDao : RecommendationLogDao {
        val impressions = mutableListOf<RecommendationImpressionEntity>()
        val outcomes = mutableListOf<RecommendationOutcomeEntity>()
        private var nextImpressionId = 1L
        private var nextOutcomeId = 1L

        override suspend fun insertImpressions(impressions: List<RecommendationImpressionEntity>): List<Long> {
            val ids = mutableListOf<Long>()
            for (item in impressions) {
                val id = nextImpressionId++
                this.impressions.add(item.copy(id = id))
                ids.add(id)
            }
            return ids
        }

        override suspend fun insertOutcome(outcome: RecommendationOutcomeEntity): Long {
            val id = nextOutcomeId++
            outcomes.add(outcome.copy(id = id))
            return id
        }

        override suspend fun findRecentImpressionForVideo(videoId: String, sinceTimestamp: Long): RecommendationImpressionEntity? {
            return impressions
                .filter { it.videoId == videoId && it.shownAt >= sinceTimestamp }
                .maxByOrNull { it.shownAt }
        }

        override suspend fun getImpressionCountsSince(sinceTimestamp: Long): List<VideoImpressionCount> {
            return impressions
                .filter { it.shownAt >= sinceTimestamp }
                .groupBy { it.videoId }
                .map { (vid, list) -> VideoImpressionCount(vid, list.size) }
        }

        override suspend fun getImpressionsForRequest(requestId: String): List<RecommendationImpressionEntity> {
            return impressions.filter { it.requestId == requestId }.sortedBy { it.position }
        }

        override suspend fun getOutcomesForImpression(impressionId: Long): List<RecommendationOutcomeEntity> {
            return outcomes.filter { it.impressionId == impressionId }.sortedBy { it.at }
        }

        override suspend fun pruneImpressionsOlderThan(timestamp: Long): Int {
            val toRemove = impressions.filter { it.shownAt < timestamp }
            val removedIds = toRemove.map { it.id }.toSet()
            impressions.removeAll(toRemove)
            outcomes.removeAll { it.impressionId in removedIds }
            return toRemove.size
        }

        override suspend fun countImpressions(): Int = impressions.size

        override suspend fun countOutcomes(): Int = outcomes.size
    }
}
