package com.geekify.android.data.local

import com.geekify.android.data.model.Shelf
import com.geekify.android.data.source.MusicResult
import com.geekify.android.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Stores the most recent home-feed shelves so the next cold start (or an offline start) shows content instantly. */
@Singleton
class ShelfCacheRepository @Inject constructor(
    private val dao: ShelfCacheDao,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Shelf.serializer())

    /** The cached shelves, or null if nothing usable is stored (a corrupt row is treated as empty, never thrown). */
    suspend fun load(key: String = HOME): List<Shelf>? {
        val stored = (safeDbCall(io) { dao.get(key) } as? MusicResult.Success)?.value ?: return null
        return runCatching { json.decodeFromString(serializer, stored.json) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    suspend fun save(shelves: List<Shelf>, key: String = HOME): MusicResult<Unit> = safeDbCall(io) {
        if (shelves.isNotEmpty()) {
            dao.put(CachedShelfEntity(key, json.encodeToString(serializer, shelves.take(MAX_SHELVES)), System.currentTimeMillis()))
        }
    }

    companion object {
        const val HOME = "home"
        private const val MAX_SHELVES = 18
    }
}
