package com.geekify.android.domain.offline

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import com.geekify.android.data.local.LibraryRepository
import com.geekify.android.data.model.Track
import com.geekify.android.domain.listening.ListeningBehaviorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class OfflineIntelligenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioCache: SimpleCache,
    private val libraryRepository: LibraryRepository,
    private val listeningBehaviorManager: ListeningBehaviorManager
) {
    /**
     * Total cached audio size in megabytes.
     */
    fun getCacheSizeMb(): Long {
        return (audioCache.cacheSpace / (1024L * 1024L)).coerceAtLeast(0L)
    }

    /**
     * Clears all cached media files while keeping user playlists and liked songs safe.
     */
    suspend fun clearAudioCache() = withContext(Dispatchers.IO) {
        val keys = audioCache.keys
        for (key in keys) {
            audioCache.removeResource(key)
        }
    }

    /**
     * Returns ranked tracks available offline (from liked songs and library) sorted by
     * affinity signals when network connection is absent.
     */
    suspend fun getRankedOfflineTracks(limit: Int = 50): List<Track> = withContext(Dispatchers.IO) {
        val liked = libraryRepository.likedOnce()
        val signals = listeningBehaviorManager.signals.value
        val artistDwells = signals.topArtistsDwellMs

        liked.sortedByDescending { track ->
            val dwell = artistDwells[track.artist] ?: 0L
            val replay = signals.replayCounts[track.videoId] ?: 0
            dwell + (replay * 60_000L)
        }.take(limit)
    }

    /**
     * Performs fast offline text search across liked library tracks.
     */
    suspend fun searchOffline(query: String): List<Track> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val q = query.trim().lowercase()
        val liked = libraryRepository.likedOnce()
        liked.filter { track ->
            track.title.lowercase().contains(q) ||
            track.artist.lowercase().contains(q)
        }
    }
}
