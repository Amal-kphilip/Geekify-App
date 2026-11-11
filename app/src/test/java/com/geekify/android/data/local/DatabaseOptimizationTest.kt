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

    @Test
    fun `all migrations from v1 to v9 preserve user library tables and create expected schema elements`() {
        val migrations = listOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9
        )
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

        migrations.forEach { it.migrate(dbProxy) }

        assertTrue(executedStatements.any { it.contains("CREATE TABLE IF NOT EXISTS `play_stats`") })
        assertTrue(executedStatements.any { it.contains("CREATE TABLE IF NOT EXISTS `tracks`") })
        assertTrue(executedStatements.any { it.contains("CREATE TABLE IF NOT EXISTS `listening_sessions`") })
        assertTrue(executedStatements.any { it.contains("CREATE TABLE IF NOT EXISTS `playlist_tracks_new`") })
        assertTrue(executedStatements.any { it.contains("INSERT INTO `playlist_tracks_new`") })
        assertTrue(executedStatements.any { it.contains("CREATE TABLE IF NOT EXISTS `recent_searches`") })
        assertTrue(executedStatements.any { it.contains("CREATE TABLE IF NOT EXISTS `recommendation_impressions`") })
        assertTrue(executedStatements.any { it.contains("CREATE TABLE IF NOT EXISTS `track_features`") })
        // Verify core user tables (liked_tracks, history_tracks, playlists) are never dropped across any migration
        assertTrue(executedStatements.none { it.contains("DROP TABLE `liked_tracks`", ignoreCase = true) })
        assertTrue(executedStatements.none { it.contains("DROP TABLE `history_tracks`", ignoreCase = true) })
        assertTrue(executedStatements.none { it.contains("DROP TABLE `playlists`", ignoreCase = true) })
    }

    @Test
    fun `type converters handle empty corrupted and valid JSON lists safely`() {
        val converters = Converters()
        assertEquals(emptyList<com.geekify.android.data.model.ArtistRef>(), converters.jsonToArtists(""))
        assertEquals(emptyList<com.geekify.android.data.model.ArtistRef>(), converters.jsonToArtists("not-valid-json"))

        val artists = listOf(com.geekify.android.data.model.ArtistRef(name = "Sushin Shyam", id = "uc_sushin"))
        val encoded = converters.artistsToJson(artists)
        assertEquals(artists, converters.jsonToArtists(encoded))
    }
}
