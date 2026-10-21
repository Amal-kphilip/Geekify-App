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

    suspend fun save(session: ListeningSessionEntity) = dao.upsert(session)

    suspend fun latest(limit: Int = 2_000): List<ListeningSessionEntity> =
        dao.latest(limit.coerceIn(1, 20_000))

    suspend fun pruneOlderThan(timestamp: Long) = dao.pruneBefore(timestamp)

    suspend fun clear() = dao.clear()
}
