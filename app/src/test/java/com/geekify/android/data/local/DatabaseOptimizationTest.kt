package com.geekify.android.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class DatabaseOptimizationTest {

    @Test
    fun `migration 5 to 6 creates expected indexes for performance without dropping tables`() {
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

        MIGRATION_5_6.migrate(dbProxy)

        assertEquals(8, executedStatements.size)
        assertTrue(executedStatements.any { it.contains("index_liked_tracks_addedAt") })
        assertTrue(executedStatements.any { it.contains("index_history_tracks_playedAt") })
        assertTrue(executedStatements.any { it.contains("index_play_stats_playCount_lastPlayedAt") })
        assertTrue(executedStatements.any { it.contains("index_tracks_cachedAt") })
        assertTrue(executedStatements.any { it.contains("index_albums_cachedAt") })
        assertTrue(executedStatements.any { it.contains("index_artists_cachedAt") })
        assertTrue(executedStatements.any { it.contains("index_cached_shelves_cachedAt") })
        assertTrue(executedStatements.any { it.contains("index_playlist_tracks_playlistId_position") })
    }
}
