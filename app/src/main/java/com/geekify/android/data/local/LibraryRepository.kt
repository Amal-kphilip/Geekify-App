package com.geekify.android.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.geekify.android.data.model.Track
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** An album or playlist from YouTube Music that the person added to Your Library (a link, not a copy of the tracks). */
@Serializable
data class SavedCollection(
    val id: String,
    val kind: String,          // CollectionKind name: ALBUM or PLAYLIST
    val title: String,
    val subtitle: String? = null,
    val thumbnailUrl: String? = null,
    val savedAt: Long = System.currentTimeMillis()
)

@Singleton
class LibraryRepository @Inject constructor(
    private val dao: PlaylistDao,
    private val likedDao: LikedDao,
    private val dataStore: DataStore<Preferences>,
    private val recommendationLog: dagger.Lazy<RecommendationLogRepository>
) {
    private val savedKey = stringPreferencesKey("saved_collections")
    private val savedJson = Json { ignoreUnknownKeys = true }

    private fun decodeSaved(prefs: Preferences): List<SavedCollection> =
        prefs[savedKey]?.let { runCatching { savedJson.decodeFromString<List<SavedCollection>>(it) }.getOrNull() } ?: emptyList()

    val savedCollections: Flow<List<SavedCollection>> = dataStore.data.map { decodeSaved(it).sortedByDescending { c -> c.savedAt } }

    /** Adds the collection if it is not saved yet, removes it if it is. Returns true when it is now saved. */
    suspend fun toggleSavedCollection(collection: SavedCollection): Boolean {
        var nowSaved = false
        dataStore.edit { prefs ->
            val current = decodeSaved(prefs)
            val updated = if (current.any { it.id == collection.id }) {
                current.filterNot { it.id == collection.id }
            } else {
                nowSaved = true
                current + collection.copy(savedAt = System.currentTimeMillis())
            }
            prefs[savedKey] = savedJson.encodeToString(updated)
        }
        if (nowSaved) {
            runCatching {
                recommendationLog.get().recordOutcome(
                    collection.id,
                    RecommendationOutcomeEvent.SAVED_ALBUM_OR_ARTIST
                )
            }
        }
        return nowSaved
    }

    suspend fun savedCollectionsOnce(): List<SavedCollection> = decodeSaved(dataStore.data.first())
    suspend fun replaceAllSavedCollections(list: List<SavedCollection>) {
        dataStore.edit { it[savedKey] = savedJson.encodeToString(list) }
    }

    val liked: Flow<List<Track>> = likedDao.all().map { it.map(LikedTrackEntity::toTrack) }
    val likedVideoIds: Flow<Set<String>> = likedDao.observeVideoIds().map { it.toSet() }
    suspend fun likedVideoIdsOnce(): Set<String> = likedDao.allVideoIdsOnce().toSet()
    suspend fun distinctLikedArtistsOnce(limit: Int = 150): List<String> =
        extractDistinctArtists(likedDao.artistProjectionsOnce(limit), limit)

    /** All playlists with their songs (so lists can show a cover and a real song count). */
    val playlists: Flow<List<LocalPlaylist>> = combine(dao.allPlaylists(), dao.allTracks()) { pls, tracks ->
        val byPlaylist = tracks.groupBy { it.playlistId }
        pls.map { pl ->
            LocalPlaylist(pl.id, pl.name, pl.createdAt, byPlaylist[pl.id].orEmpty().map(PlaylistTrackEntity::toTrack))
        }
    }

    suspend fun isLiked(videoId: String) = likedDao.isLiked(videoId)

    suspend fun toggleLike(track: Track) {
        if (likedDao.isLiked(track.videoId)) {
            likedDao.delete(track.toLiked())
            runCatching {
                recommendationLog.get().recordOutcome(
                    track.videoId,
                    RecommendationOutcomeEvent.UNLIKED
                )
            }
        } else {
            likedDao.insert(track.toLiked())
            runCatching {
                recommendationLog.get().recordOutcome(
                    track.videoId,
                    RecommendationOutcomeEvent.LIKED
                )
            }
        }
    }

    suspend fun createPlaylist(name: String): LocalPlaylist {
        val pl = PlaylistEntity(UUID.randomUUID().toString(), name.trim().ifBlank { "New playlist" }, System.currentTimeMillis())
        dao.insertPlaylist(pl)
        return LocalPlaylist(pl.id, pl.name, pl.createdAt, emptyList())
    }

    suspend fun renamePlaylist(id: String, name: String) = dao.rename(id, name)
    suspend fun deletePlaylist(id: String) {
        dao.deletePlaylist(id)
        dao.clearTracks(id)
    }

    fun playlistTracks(id: String): Flow<List<Track>> = dao.tracksFor(id).map { it.map(PlaylistTrackEntity::toTrack) }

    suspend fun addToPlaylist(id: String, track: Track) {
        val existing = dao.tracksOnce(id)
        if (existing.any { it.videoId == track.videoId }) return
        val nextPosition = nextPlaylistPosition(existing.map { it.position })
        dao.insertTrack(track.toPlaylistTrack(id, nextPosition))
        runCatching {
            recommendationLog.get().recordOutcome(
                track.videoId,
                RecommendationOutcomeEvent.ADDED_TO_PLAYLIST
            )
        }
    }

    suspend fun removeFromPlaylist(id: String, videoId: String) =
        dao.removeTrackAndCompactPositions(id, videoId)

    companion object {
        /** Computes the next sequential position even if earlier removals left gaps in [existingPositions]. */
        fun nextPlaylistPosition(existingPositions: List<Int>): Int =
            (existingPositions.maxOrNull() ?: -1) + 1
    }

    // Cloud sync helpers
    suspend fun likedOnce(): List<Track> = likedDao.allOnce().map(LikedTrackEntity::toTrack)
    suspend fun replaceAllLiked(tracks: List<Track>) {
        val newestFirstBase = System.currentTimeMillis()
        likedDao.replaceAll(
            tracks.mapIndexed { index, track ->
                track.toLiked(addedAt = newestFirstBase - index)
            }
        )
    }
    suspend fun playlistsOnce(): List<LocalPlaylist> {
        val allTracksByPlaylist = dao.allTracksOnce().groupBy { it.playlistId }
        return dao.allPlaylistsOnce().map { pl ->
            LocalPlaylist(
                pl.id,
                pl.name,
                pl.createdAt,
                allTracksByPlaylist[pl.id].orEmpty().map(PlaylistTrackEntity::toTrack)
            )
        }
    }
    suspend fun replaceAllPlaylists(pls: List<LocalPlaylist>) {
        val playlistEntities = pls.map { pl -> PlaylistEntity(pl.id, pl.name, pl.createdAt) }
        val trackEntities = pls.flatMap { pl ->
            pl.tracks.mapIndexed { i, t -> t.toPlaylistTrack(pl.id, i) }
        }
        dao.replaceAll(playlistEntities, trackEntities)
    }
}
