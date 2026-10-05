package com.geekify.android.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

/** Song metadata remembered from anything the app has loaded (search, albums, artists), usable offline. */
@Entity(tableName = "tracks")
data class TrackEntity(
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
    val cachedAt: Long
)

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String?,
    val year: String?,
    val thumbnailUrl: String?,
    val cachedAt: Long
)

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val thumbnailUrl: String?,
    val cachedAt: Long
)

/** The last home-feed shelves as JSON, so a cold start renders instantly (and offline). */
@Entity(tableName = "cached_shelves")
data class CachedShelfEntity(
    @PrimaryKey val cacheKey: String,
    val json: String,
    val cachedAt: Long
)

fun Track.toEntity(now: Long = System.currentTimeMillis()) =
    TrackEntity(videoId, title, artist, artists, album, albumId, thumbnails, duration, durationSeconds, explicit, type, now)
fun TrackEntity.toTrack() = Track(videoId, title, artist, artists, album, albumId, thumbnails, duration, durationSeconds, explicit, type)

/** How often (and when last) a song was properly listened to. Drives the "frequently played" signal for recommendations. */
@Entity(tableName = "play_stats")
data class PlayStatEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val playCount: Int,
    val lastPlayedAt: Long
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
interface PlayStatDao {
    @Query("UPDATE play_stats SET playCount = playCount + 1, lastPlayedAt = :now, title = :title, artist = :artist WHERE videoId = :id")
    suspend fun bump(id: String, title: String, artist: String, now: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(e: PlayStatEntity): Long
    @Query("SELECT * FROM play_stats ORDER BY playCount DESC, lastPlayedAt DESC LIMIT :limit") suspend fun top(limit: Int): List<PlayStatEntity>
    @Query("DELETE FROM play_stats") suspend fun clear()
}

@Dao
interface CatalogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTracks(list: List<TrackEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAlbum(e: AlbumEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertArtist(e: ArtistEntity)
    @Query("SELECT * FROM tracks WHERE videoId = :id") fun observeTrack(id: String): Flow<TrackEntity?>
    @Query("SELECT * FROM tracks WHERE videoId = :id") suspend fun trackOnce(id: String): TrackEntity?
    @Query("SELECT * FROM albums ORDER BY cachedAt DESC") fun observeAlbums(): Flow<List<AlbumEntity>>
    @Query("SELECT * FROM artists ORDER BY cachedAt DESC") fun observeArtists(): Flow<List<ArtistEntity>>
    @Query("DELETE FROM tracks WHERE cachedAt < :before") suspend fun pruneTracks(before: Long)
    @Query("DELETE FROM albums WHERE cachedAt < :before") suspend fun pruneAlbums(before: Long)
    @Query("DELETE FROM artists WHERE cachedAt < :before") suspend fun pruneArtists(before: Long)
}

@Dao
interface ShelfCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(e: CachedShelfEntity)
    @Query("SELECT * FROM cached_shelves WHERE cacheKey = :key") suspend fun get(key: String): CachedShelfEntity?
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC") fun allPlaylists(): Flow<List<PlaylistEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPlaylist(p: PlaylistEntity)
    @Query("DELETE FROM playlists WHERE id=:id") suspend fun deletePlaylist(id: String)
    @Query("UPDATE playlists SET name=:name WHERE id=:id") suspend fun rename(id: String, name: String)
    @Query("SELECT * FROM playlist_tracks WHERE playlistId=:id ORDER BY position ASC") fun tracksFor(id: String): Flow<List<PlaylistTrackEntity>>
    @Query("SELECT * FROM playlist_tracks ORDER BY playlistId, position ASC") fun allTracks(): Flow<List<PlaylistTrackEntity>>
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
    entities = [LikedTrackEntity::class, HistoryTrackEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class, PlayStatEntity::class,
        TrackEntity::class, AlbumEntity::class, ArtistEntity::class, CachedShelfEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class GeekifyDatabase : RoomDatabase() {
    abstract fun likedDao(): LikedDao
    abstract fun historyDao(): HistoryDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playStatDao(): PlayStatDao
    abstract fun catalogDao(): CatalogDao
    abstract fun shelfCacheDao(): ShelfCacheDao
}

/** v2 -> v3: adds the offline catalog (tracks, albums, artists) and the cached home shelves. Nothing is dropped. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tracks` (`videoId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
                "`artists` TEXT NOT NULL, `album` TEXT, `albumId` TEXT, `thumbnails` TEXT NOT NULL, `duration` TEXT, " +
                "`durationSeconds` INTEGER, `explicit` INTEGER NOT NULL, `type` TEXT NOT NULL, `cachedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`videoId`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `albums` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT, `year` TEXT, " +
                "`thumbnailUrl` TEXT, `cachedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `artists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `thumbnailUrl` TEXT, " +
                "`cachedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `cached_shelves` (`cacheKey` TEXT NOT NULL, `json` TEXT NOT NULL, " +
                "`cachedAt` INTEGER NOT NULL, PRIMARY KEY(`cacheKey`))"
        )
    }
}

/** v1 -> v2: adds play counts. Existing liked songs, playlists and history are untouched. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `play_stats` (`videoId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`artist` TEXT NOT NULL, `playCount` INTEGER NOT NULL, `lastPlayedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`videoId`))"
        )
    }
}
