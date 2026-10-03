package com.geekify.android.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.geekify.android.data.model.Track
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
    private val dataStore: DataStore<Preferences>
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
        return nowSaved
    }

    suspend fun savedCollectionsOnce(): List<SavedCollection> = decodeSaved(dataStore.data.first())
    suspend fun replaceAllSavedCollections(list: List<SavedCollection>) {
        dataStore.edit { it[savedKey] = savedJson.encodeToString(list) }
    }

    val liked: Flow<List<Track>> = likedDao.all().map { it.map(LikedTrackEntity::toTrack) }

    val playlists: Flow<List<LocalPlaylist>> = dao.allPlaylists().map { pls ->
        pls  // tracks loaded per-playlist when needed
            .map { pl -> LocalPlaylist(pl.id, pl.name, pl.createdAt, emptyList()) }
    }

    suspend fun isLiked(videoId: String) = likedDao.isLiked(videoId)

    suspend fun toggleLike(track: Track) {
        if (likedDao.isLiked(track.videoId)) likedDao.delete(track.toLiked())
        else likedDao.insert(track.toLiked())
    }

    suspend fun createPlaylist(name: String): LocalPlaylist {
        val pl = PlaylistEntity(UUID.randomUUID().toString(), name.trim().ifBlank { "New playlist" }, System.currentTimeMillis())
        dao.insertPlaylist(pl)
        return LocalPlaylist(pl.id, pl.name, pl.createdAt, emptyList())
    }

    suspend fun renamePlaylist(id: String, name: String) = dao.rename(id, name)
    suspend fun deletePlaylist(id: String) { dao.deletePlaylist(id); dao.clearTracks(id) }

    fun playlistTracks(id: String): Flow<List<Track>> = dao.tracksFor(id).map { it.map(PlaylistTrackEntity::toTrack) }

    suspend fun addToPlaylist(id: String, track: Track) {
        val existing = dao.tracksOnce(id)
        if (existing.any { it.videoId == track.videoId }) return
        dao.insertTrack(track.toPlaylistTrack(id, existing.size))
    }

    suspend fun removeFromPlaylist(id: String, videoId: String) = dao.removeTrack(id, videoId)

    // Cloud sync helpers
    suspend fun likedOnce(): List<Track> = likedDao.allOnce().map(LikedTrackEntity::toTrack)
    suspend fun replaceAllLiked(tracks: List<Track>) { likedDao.clear(); likedDao.insertAll(tracks.map(Track::toLiked)) }
    suspend fun playlistsOnce(): List<LocalPlaylist> {
        return dao.allPlaylistsOnce().map { pl ->
            LocalPlaylist(pl.id, pl.name, pl.createdAt, dao.tracksOnce(pl.id).map(PlaylistTrackEntity::toTrack))
        }
    }
    suspend fun replaceAllPlaylists(pls: List<LocalPlaylist>) {
        // Delete old
        dao.allPlaylistsOnce().forEach { dao.deletePlaylist(it.id); dao.clearTracks(it.id) }
        pls.forEach { pl ->
            dao.insertPlaylist(PlaylistEntity(pl.id, pl.name, pl.createdAt))
            dao.insertAllTracks(pl.tracks.mapIndexed { i, t -> t.toPlaylistTrack(pl.id, i) })
        }
    }
}
