package com.geekify.android.data.sync

import com.geekify.android.data.local.LocalPlaylist
import com.geekify.android.data.local.ListeningSessionEntity
import com.geekify.android.data.local.SavedCollection
import com.geekify.android.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRepositoryTest {

    private val trackA = Track(videoId = "vid_a", title = "Song A", artist = "Artist A")
    private val trackB = Track(videoId = "vid_b", title = "Song B", artist = "Artist B")
    private val trackC = Track(videoId = "vid_c", title = "Song C", artist = "Artist C")

    @Test
    fun unsynchronizedLocalChangesAreMergedNotOverwrittenByCloud() {
        val local = SyncRepository.CloudData(
            liked = listOf(trackA, trackB),
            playlists = listOf(LocalPlaylist("pl1", "My Mix", 2000L, listOf(trackA, trackB))),
            history = listOf(trackB to 5000L),
            saved = listOf(SavedCollection("col1", "ALBUM", "Album 1", savedAt = 3000L)),
            updatedAt = 5000L
        )
        val cloud = SyncRepository.CloudData(
            liked = listOf(trackA, trackC),
            playlists = listOf(LocalPlaylist("pl1", "Old Name", 1000L, listOf(trackC))),
            history = listOf(trackA to 4000L),
            saved = listOf(SavedCollection("col2", "PLAYLIST", "Playlist 2", savedAt = 2000L)),
            updatedAt = 4000L
        )

        val resolution = SyncRepository.resolveSyncData(
            local = local,
            cloud = cloud,
            firstTime = false,
            localDirty = true,
            localModifiedAt = 5000L,
            lastSyncedAt = 4000L
        )

        assertTrue(resolution.shouldPushToCloud)
        val merged = resolution.dataToApply
        assertNotNull(merged)
        assertEquals(listOf("vid_a", "vid_b", "vid_c"), merged!!.liked.map { it.videoId })
        assertEquals(1, merged.playlists.size)
        assertEquals("My Mix", merged.playlists.first().name)
        assertEquals(listOf("vid_a", "vid_b", "vid_c"), merged.playlists.first().tracks.map { it.videoId })
        assertEquals(listOf("col1", "col2"), merged.saved?.map { it.id })
    }

    @Test
    fun cleanLocalStateAppliesCloudWithoutRedundantPush() {
        val local = SyncRepository.CloudData(
            liked = listOf(trackA),
            playlists = emptyList(),
            history = listOf(trackB to 6000L),
            saved = emptyList(),
            updatedAt = 2000L
        )
        val cloud = SyncRepository.CloudData(
            liked = listOf(trackC),
            playlists = listOf(LocalPlaylist("pl2", "Cloud List", 3000L, listOf(trackC))),
            history = listOf(trackA to 4000L),
            saved = null,
            updatedAt = 5000L
        )

        val resolution = SyncRepository.resolveSyncData(
            local = local,
            cloud = cloud,
            firstTime = false,
            localDirty = false,
            localModifiedAt = 2000L,
            lastSyncedAt = 2000L
        )

        assertFalse(resolution.shouldPushToCloud)
        val applied = resolution.dataToApply
        assertNotNull(applied)
        assertEquals(listOf("vid_c"), applied!!.liked.map { it.videoId })
        // History is always merged newest-first so recent local plays never disappear
        assertEquals(listOf("vid_b", "vid_a"), applied.history.map { it.first.videoId })
    }

    @Test
    fun nullCloudDataPushesLocalLibrarySafely() {
        val local = SyncRepository.CloudData(
            liked = listOf(trackA),
            playlists = emptyList(),
            history = emptyList(),
            saved = emptyList(),
            updatedAt = 1000L
        )

        val resolution = SyncRepository.resolveSyncData(
            local = local,
            cloud = null,
            firstTime = true,
            localDirty = false,
            localModifiedAt = 0L,
            lastSyncedAt = 0L
        )

        assertTrue(resolution.shouldPushToCloud)
        assertEquals(null, resolution.dataToApply)
    }

    @Test
    fun listeningSessionsMergeByIdAndKeepNewestCheckpoint() {
        val earlier = ListeningSessionEntity(
            sessionId = "session-1",
            videoId = "vid_a",
            title = "Song A",
            artist = "Artist A",
            listenedMs = 15_000L,
            durationMs = 180_000L,
            startedAt = 1_000L,
            updatedAt = 2_000L
        )
        val newer = earlier.copy(
            listenedMs = 30_000L,
            updatedAt = 3_000L,
            completed = true
        )

        val merged = SyncRepository.mergeSessions(listOf(earlier), listOf(newer))

        assertEquals(1, merged.size)
        assertEquals(30_000L, merged.single().listenedMs)
        assertEquals(3_000L, merged.single().updatedAt)
        assertTrue(merged.single().completed)
    }

    @Test
    fun cleanLibraryStillPushesListeningSessionsMissingFromCloud() {
        val localSession = ListeningSessionEntity(
            sessionId = "local-session",
            videoId = "vid_a",
            title = "Song A",
            artist = "Artist A",
            listenedMs = 15_000L,
            durationMs = 180_000L,
            startedAt = 1_000L,
            updatedAt = 2_000L
        )
        val local = SyncRepository.CloudData(
            liked = emptyList(),
            playlists = emptyList(),
            history = emptyList(),
            saved = emptyList(),
            sessions = listOf(localSession)
        )
        val cloud = SyncRepository.CloudData(
            liked = emptyList(),
            playlists = emptyList(),
            history = emptyList(),
            saved = emptyList()
        )

        val resolution = SyncRepository.resolveSyncData(
            local = local,
            cloud = cloud,
            firstTime = false,
            localDirty = false,
            localModifiedAt = 0L,
            lastSyncedAt = 0L
        )

        assertTrue(resolution.shouldPushToCloud)
        assertEquals("local-session", resolution.dataToApply?.sessions?.single()?.sessionId)
    }

    @Test
    fun syncStateIncludesOfflineStatus() {
        val states = com.geekify.android.data.auth.SyncState.values().map { it.name }
        assertTrue(states.contains("IDLE"))
        assertTrue(states.contains("SYNCING"))
        assertTrue(states.contains("SAVED"))
        assertTrue(states.contains("OFFLINE"))
        assertTrue(states.contains("ERROR"))
    }
}
