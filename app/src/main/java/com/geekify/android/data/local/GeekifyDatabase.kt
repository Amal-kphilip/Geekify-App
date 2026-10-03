package com.geekify.android.data.local

import androidx.room.*
import com.geekify.android.data.model.ArtistRef
import com.geekify.android.data.model.Thumbnail
import com.geekify.android.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ---- Type Converters ----

class Converters {
    private val json = Json { ignoreUnknownKeys = true }
    @TypeConverter fun thumbsToJson(v: List<Thumbnail>): String = json.encodeToString(v)
    @TypeConverter fun jsonToThumbs(v: String): List<Thumbnail> = runCatching { json.decodeFromString<List<Thumbnail>>(v) }.getOrDefault(emptyList())
    @TypeConverter fun artistsToJson(v: List<ArtistRef>): String = json.encodeToString(v)
    @TypeConverter fun jsonToArtists(v: String): List<ArtistRef> = runCatching { json.decodeFromString<List<ArtistRef>>(v) }.getOrDefault(emptyList())
}

// ---- Entities ----

@Entity(tableName = "liked_tracks")
data class LikedTrackEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val artists: List<ArtistRef>,
    val album: String?,
    val albumId: String?,
    val thumbnails: List<Thumbnail>,
    val duration: String?,
    val durationSeconds: Int?,
    val explicit: Boolean,
    val type: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "history_tracks")
data class HistoryTrackEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val artists: List<ArtistRef>,
    val album: String?,
    val albumId: String?,
    val thumbnails: List<Thumbnail>,
    val duration: String?,
    val durationSeconds: Int?,
    val explicit: Boolean,
    val type: String,
    val playedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long
)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "videoId"])
data class PlaylistTrackEntity(
    val playlistId: String,
    val position: Int,
    val videoId: String,
    val title: String,
    val artist: String,
    val artists: List<ArtistRef>,
    val album: String?,
    val albumId: String?,
    val thumbnails: List<Thumbnail>,
    val duration: String?,
    val durationSeconds: Int?,
    val explicit: Boolean,
    val type: String
)

// Helper extensions
fun Track.toLiked() = LikedTrackEntity(videoId, title, artist, artists, album, albumId, thumbnails, duration, durationSeconds, explicit, type)
fun Track.toHistory() = HistoryTrackEntity(videoId, title, artist, artists, album, albumId, thumbnails, duration, durationSeconds, explicit, type)
fun LikedTrackEntity.toTrack() = Track(videoId, title, artist, artists, album, albumId, thumbnails, duration, durationSeconds, explicit, type)
fun HistoryTrackEntity.toTrack() = Track(videoId, title, artist, artists, album, albumId, thumbnails, duration, durationSeconds, explicit, type)
fun PlaylistTrackEntity.toTrack() = Track(videoId, title, artist, artists, album, albumId, thumbnails, duration, durationSeconds, explicit, type)
fun Track.toPlaylistTrack(playlistId: String, position: Int) = PlaylistTrackEntity(playlistId, position, videoId, title, artist, artists, album, albumId, thumbnails, duration, durationSeconds, explicit, type)

data class LocalPlaylist(val id: String, val name: String, val createdAt: Long, val tracks: List<Track>)

// ---- DAOs ----

@Dao
interface LikedDao {
    @Query("SELECT * FROM liked_tracks ORDER BY addedAt DESC") fun all(): Flow<List<LikedTrackEntity>>
    @Query("SELECT EXISTS(SELECT 1 FROM liked_tracks WHERE videoId=:id)") suspend fun isLiked(id: String): Boolean
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(e: LikedTrackEntity)
    @Delete suspend fun delete(e: LikedTrackEntity)
    @Query("DELETE FROM liked_tracks") suspend fun clear()
    @Query("SELECT * FROM liked_tracks ORDER BY addedAt DESC") suspend fun allOnce(): List<LikedTrackEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(list: List<LikedTrackEntity>)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_tracks ORDER BY playedAt DESC LIMIT 40") fun recent(): Flow<List<HistoryTrackEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(e: HistoryTrackEntity)
    @Query("DELETE FROM history_tracks WHERE videoId=:id") suspend fun delete(id: String)
    @Query("DELETE FROM history_tracks WHERE videoId NOT IN (SELECT videoId FROM history_tracks ORDER BY playedAt DESC LIMIT 40)") suspend fun trim()
    @Query("DELETE FROM history_tracks") suspend fun clear()
    @Query("SELECT * FROM history_tracks ORDER BY playedAt DESC LIMIT 40") suspend fun allOnce(): List<HistoryTrackEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(list: List<HistoryTrackEntity>)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC") fun allPlaylists(): Flow<List<PlaylistEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPlaylist(p: PlaylistEntity)
    @Query("DELETE FROM playlists WHERE id=:id") suspend fun deletePlaylist(id: String)
    @Query("UPDATE playlists SET name=:name WHERE id=:id") suspend fun rename(id: String, name: String)
    @Query("SELECT * FROM playlist_tracks WHERE playlistId=:id ORDER BY position ASC") fun tracksFor(id: String): Flow<List<PlaylistTrackEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTrack(t: PlaylistTrackEntity)
    @Query("DELETE FROM playlist_tracks WHERE playlistId=:pid AND videoId=:vid") suspend fun removeTrack(pid: String, vid: String)
    @Query("DELETE FROM playlist_tracks WHERE playlistId=:id") suspend fun clearTracks(id: String)
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC") suspend fun allPlaylistsOnce(): List<PlaylistEntity>
    @Query("SELECT * FROM playlist_tracks WHERE playlistId=:id ORDER BY position ASC") suspend fun tracksOnce(id: String): List<PlaylistTrackEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(list: List<PlaylistEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAllTracks(list: List<PlaylistTrackEntity>)
}

// ---- Database ----

@Database(
    entities = [LikedTrackEntity::class, HistoryTrackEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class GeekifyDatabase : RoomDatabase() {
    abstract fun likedDao(): LikedDao
    abstract fun historyDao(): HistoryDao
    abstract fun playlistDao(): PlaylistDao
}
