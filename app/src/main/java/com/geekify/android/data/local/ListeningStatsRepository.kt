package com.geekify.android.data.local

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Persists listening sessions locally. No listening-history data is uploaded by this repository. */
@Singleton
class ListeningStatsRepository @Inject constructor(
    private val dao: ListeningSessionDao
) {
    fun observeSessions(): Flow<List<ListeningSessionEntity>> = dao.observeAll()

    private var lastPruneTime = 0L

    suspend fun autoPruneIfStale(maxAgeMs: Long = 180L * 24 * 3600 * 1000) {
        val now = System.currentTimeMillis()
        if (now - lastPruneTime > 24L * 3600 * 1000) {
            lastPruneTime = now
            pruneOlderThan(now - maxAgeMs)
        }
    }

    suspend fun save(session: ListeningSessionEntity) {
        dao.upsert(session)
        autoPruneIfStale()
    }

    suspend fun latest(limit: Int = 2_000): List<ListeningSessionEntity> =
        dao.latest(limit.coerceIn(1, 20_000))

    suspend fun pruneOlderThan(timestamp: Long) = dao.pruneBefore(timestamp)

    suspend fun clear() = dao.clear()
}
