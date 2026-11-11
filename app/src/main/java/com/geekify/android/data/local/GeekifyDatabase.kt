package com.geekify.android.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.geekify.android.data.model.ArtistRef
import com.geekify.android.data.model.Thumbnail
import com.geekify.android.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
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

@Entity(
    tableName = "liked_tracks",
    indices = [Index(value = ["addedAt"])]
)
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

@Entity(
    tableName = "history_tracks",
    indices = [Index(value = ["playedAt"])]
)
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
@Entity(
    tableName = "tracks",
    indices = [Index(value = ["cachedAt"])]
)
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

@Entity(
    tableName = "albums",
    indices = [Index(value = ["cachedAt"])]
)
data class AlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String?,
    val year: String?,
    val thumbnailUrl: String?,
    val cachedAt: Long
)

@Entity(
    tableName = "artists",
    indices = [Index(value = ["cachedAt"])]
)
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val thumbnailUrl: String?,
    val cachedAt: Long
)

/** The last home-feed shelves as JSON, so a cold start renders instantly (and offline). */
@Entity(
    tableName = "cached_shelves",
    indices = [Index(value = ["cachedAt"])]
)
data class CachedShelfEntity(
    @PrimaryKey val cacheKey: String,
    val json: String,
    val cachedAt: Long
)

fun Track.toEntity(now: Long = System.currentTimeMillis()) =
    TrackEntity(videoId, title, artist, artists, album, albumId, thumbnails, duration, durationSeconds, explicit, type, now)
fun TrackEntity.toTrack() = Track(videoId, title, artist, artists, album, albumId, thumbnails, duration, durationSeconds, explicit, type)

/** How often (and when last) a song was properly listened to. Drives the "frequently played" signal for recommendations. */
@Entity(
    tableName = "play_stats",
    indices = [Index(value = ["playCount", "lastPlayedAt"])]
)
data class PlayStatEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val playCount: Int,
    val lastPlayedAt: Long
)

/** One durable listening session, updated periodically while audio is actually playing. */
@Entity(
    tableName = "listening_sessions",
    indices = [Index(value = ["startedAt"]), Index(value = ["videoId"]), Index(value = ["artist"])]
)
data class ListeningSessionEntity(
    @PrimaryKey val sessionId: String,
    val videoId: String,
    val title: String,
    val artist: String,
    val artists: List<ArtistRef> = emptyList(),
    /** Active listening time accumulated while the player was really playing, not seek position. */
    val listenedMs: Long,
    val durationMs: Long,
    val startedAt: Long,
    val updatedAt: Long,
    val completed: Boolean = false,
    val skipped: Boolean = false
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long
)

@Entity(
    tableName = "recent_searches",
    indices = [Index(value = ["timestamp"])]
)
data class RecentSearchEntity(
    @PrimaryKey val query: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "recommendation_impressions",
    indices = [
        Index(value = ["videoId", "shownAt"]),
        Index(value = ["requestId"]),
        Index(value = ["accountId"]),
        Index(value = ["shownAt"])
    ]
)
data class RecommendationImpressionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val accountId: String?,
    val requestId: String,
    val mixId: String,
    val videoId: String,
    val position: Int,
    val sourceSeed: String?,
    val score: Double,
    val shownAt: Long = System.currentTimeMillis(),
    val candidateSource: String? = null,
    val modelVersion: String = "v2.4-hybrid",
    val experimentGroup: String = "CONTROL"
)

enum class RecommendationOutcomeEvent {
    IMPRESSION,
    MIX_CARD_IMPRESSION,
    MIX_OPENED,
    TRACK_SELECTED,
    PLAYED,
    PLAYED_30S,
    PLAYED_50_PERCENT,
    COMPLETED,
    SKIPPED_UNDER_10S,
    SKIPPED_EARLY,
    SKIPPED_FULL,
    REPLAYED,
    LIKED,
    UNLIKED,
    ADDED_TO_PLAYLIST,
    REMOVED_FROM_PLAYLIST,
    SAVED_ALBUM_OR_ARTIST,
    DISMISSED,
    HIDDEN,
    IGNORED
}

@Entity(
    tableName = "recommendation_outcomes",
    foreignKeys = [
        ForeignKey(
            entity = RecommendationImpressionEntity::class,
            parentColumns = ["id"],
            childColumns = ["impressionId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["impressionId"]),
        Index(value = ["at"])
    ]
)
data class RecommendationOutcomeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val impressionId: Long,
    val event: String,
    val at: Long = System.currentTimeMillis(),
    val accountId: String? = null,
    val playDurationMs: Long = 0L,
    val dedupeKey: String? = null
)

@Entity(
    tableName = "track_features",
    indices = [
        Index(value = ["normalizedIdentity"]),
        Index(value = ["language"]),
        Index(value = ["updatedAt"])
    ]
)
data class TrackFeatureEntity(
    @PrimaryKey val videoId: String,
    val normalizedIdentity: String,
    val isrc: String? = null,
    val mbRecordingId: String? = null,
    val recordingVariant: String = "ORIGINAL",
    val language: String? = null,
    val languageConfidence: Double = 1.0,
    val genresJson: String = "{}",
    val moodsJson: String = "{}",
    val audioEmbeddingJson: String = "[]",
    val lyricsEmbeddingJson: String = "[]",
    val featureSource: String = "METADATA_STRUCTURAL_PROJECTION",
    val tempoBpm: Double? = null,
    val energy: Double? = null,
    val acousticness: Double? = null,
    val valence: Double? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "user_preference_profiles",
    indices = [Index(value = ["updatedAt"])]
)
data class UserPreferenceProfileEntity(
    @PrimaryKey val accountId: String,
    val tasteVectorJson: String = "[]",
    val artistAffinitiesJson: String = "{}",
    val genreAffinitiesJson: String = "{}",
    val moodAffinitiesJson: String = "{}",
    val languageAffinitiesJson: String = "{}",
    val contextAffinitiesJson: String = "{}",
    val negativeSignalsJson: String = "{}",
    val explorationTolerance: Double = 0.25,
    val modelVersion: String = "v2.4-hybrid",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "experiment_assignments",
    indices = [Index(value = ["experimentId", "treatmentGroup"])]
)
data class ExperimentAssignmentEntity(
    @PrimaryKey val accountId: String,
    val experimentId: String,
    val treatmentGroup: String,
    val assignedAt: Long = System.currentTimeMillis(),
    val killSwitchActive: Boolean = false
)

@Entity(
    tableName = "collaborative_interactions",
    indices = [
        Index(value = ["videoId", "timestampMs"]),
        Index(value = ["anonUserHash", "timestampMs"]),
        Index(value = ["artistKey"])
    ]
)
data class CollaborativeInteractionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val anonUserHash: String,
    val videoId: String,
    val artistKey: String,
    val interactionType: String,
    val weight: Double,
    val timestampMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "videoId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["playlistId", "position"])]
)
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
fun Track.toLiked(addedAt: Long = System.currentTimeMillis()) = LikedTrackEntity(videoId, title, artist, artists, album, albumId, thumbnails, duration, durationSeconds, explicit, type, addedAt)
fun Track.toHistory() = HistoryTrackEntity(videoId, title, artist, artists, album, albumId, thumbnails, duration, durationSeconds, explicit, type)
fun LikedTrackEntity.toTrack() = Track(videoId, title, artist, artists, album, albumId, thumbnails, duration, durationSeconds, explicit, type)
fun HistoryTrackEntity.toTrack() = Track(videoId, title, artist, artists, album, albumId, thumbnails, duration, durationSeconds, explicit, type)
fun PlaylistTrackEntity.toTrack() = Track(videoId, title, artist, artists, album, albumId, thumbnails, duration, durationSeconds, explicit, type)
fun Track.toPlaylistTrack(playlistId: String, position: Int) = PlaylistTrackEntity(playlistId, position, videoId, title, artist, artists, album, albumId, thumbnails, duration, durationSeconds, explicit, type)

@Serializable
@androidx.compose.runtime.Immutable
data class LocalPlaylist(val id: String, val name: String, val createdAt: Long, val tracks: List<Track>)

data class LikedArtistProjection(
    val artist: String,
    val artists: List<ArtistRef> = emptyList()
)

fun extractDistinctArtists(projections: List<LikedArtistProjection>, limit: Int = 100): List<String> {
    if (projections.isEmpty() || limit <= 0) return emptyList()
    val seenNormalized = HashSet<String>()
    val result = ArrayList<String>(minOf(limit, projections.size * 2))
    for (row in projections) {
        val candidates = if (row.artists.isNotEmpty()) {
            row.artists.map { it.name } + listOf(row.artist)
        } else {
            listOf(row.artist)
        }
        for (raw in candidates) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            val key = trimmed.lowercase(java.util.Locale.ROOT)
            if (seenNormalized.add(key)) {
                result.add(trimmed)
                if (result.size >= limit) return result
            }
        }
    }
    return result
}

// ---- DAOs ----

@Dao
interface LikedDao {
    @Query("SELECT * FROM liked_tracks ORDER BY addedAt DESC, rowid DESC") fun all(): Flow<List<LikedTrackEntity>>
    @Query("SELECT EXISTS(SELECT 1 FROM liked_tracks WHERE videoId=:id)") suspend fun isLiked(id: String): Boolean
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(e: LikedTrackEntity)
    @Delete suspend fun delete(e: LikedTrackEntity)
    @Query("DELETE FROM liked_tracks") suspend fun clear()
    @Query("SELECT * FROM liked_tracks ORDER BY addedAt DESC, rowid DESC") suspend fun allOnce(): List<LikedTrackEntity>
    @Query("SELECT videoId FROM liked_tracks") suspend fun allVideoIdsOnce(): List<String>
    @Query("SELECT videoId FROM liked_tracks") fun observeVideoIds(): Flow<List<String>>
    @Query("SELECT artist, artists FROM liked_tracks ORDER BY addedAt DESC, rowid DESC LIMIT :limit")
    suspend fun artistProjectionsOnce(limit: Int = 100): List<LikedArtistProjection>
    @Query("SELECT artist, artists FROM liked_tracks ORDER BY addedAt DESC, rowid DESC LIMIT :limit")
    fun observeArtistProjections(limit: Int = 100): Flow<List<LikedArtistProjection>>
    suspend fun distinctArtistsOnce(limit: Int = 100): List<String> =
        extractDistinctArtists(artistProjectionsOnce(limit), limit)
    @Query("SELECT DISTINCT artist FROM liked_tracks WHERE artist != '' ORDER BY addedAt DESC LIMIT :limit")
    fun observeDistinctArtists(limit: Int = 100): Flow<List<String>>
    @Query("SELECT * FROM liked_tracks WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun searchLiked(query: String, limit: Int = 20): List<LikedTrackEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(list: List<LikedTrackEntity>)

    @Transaction
    suspend fun replaceAll(list: List<LikedTrackEntity>) {
        clear()
        if (list.isNotEmpty()) insertAll(list)
    }
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_tracks ORDER BY playedAt DESC LIMIT 40") fun recent(): Flow<List<HistoryTrackEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(e: HistoryTrackEntity)
    @Query("DELETE FROM history_tracks WHERE videoId=:id") suspend fun delete(id: String)
    @Query("DELETE FROM history_tracks WHERE videoId NOT IN (SELECT videoId FROM history_tracks ORDER BY playedAt DESC LIMIT 40)") suspend fun trim()
    @Query("DELETE FROM history_tracks") suspend fun clear()
    @Query("SELECT * FROM history_tracks ORDER BY playedAt DESC LIMIT 40") suspend fun allOnce(): List<HistoryTrackEntity>
    @Query("SELECT * FROM history_tracks WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun searchHistory(query: String, limit: Int = 20): List<HistoryTrackEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(list: List<HistoryTrackEntity>)

    @Transaction
    suspend fun replaceAll(list: List<HistoryTrackEntity>) {
        clear()
        if (list.isNotEmpty()) insertAll(list)
    }
}

@Dao
interface PlayStatDao {
    @Query("UPDATE play_stats SET playCount = playCount + 1, lastPlayedAt = :now, title = :title, artist = :artist WHERE videoId = :id")
    suspend fun bump(id: String, title: String, artist: String, now: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(e: PlayStatEntity): Long
    @Query("SELECT * FROM play_stats ORDER BY playCount DESC, lastPlayedAt DESC LIMIT :limit") suspend fun top(limit: Int): List<PlayStatEntity>
    @Query("SELECT * FROM play_stats ORDER BY playCount DESC, lastPlayedAt DESC LIMIT :limit") fun topFlow(limit: Int): Flow<List<PlayStatEntity>>
    @Query("SELECT DISTINCT artist FROM play_stats WHERE artist != '' ORDER BY playCount DESC, lastPlayedAt DESC LIMIT :limit")
    suspend fun topArtistsOnce(limit: Int = 25): List<String>
    @Query("SELECT DISTINCT artist FROM play_stats WHERE artist != '' ORDER BY playCount DESC, lastPlayedAt DESC LIMIT :limit")
    fun observeTopArtists(limit: Int = 25): Flow<List<String>>
    @Query("DELETE FROM play_stats") suspend fun clear()
}

@Dao
interface ListeningSessionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(session: ListeningSessionEntity): Long

    @Query("UPDATE listening_sessions SET videoId=:videoId, title=:title, artist=:artist, artists=:artists, listenedMs=:listenedMs, " +
        "durationMs=:durationMs, startedAt=:startedAt, updatedAt=:updatedAt, completed=:completed, skipped=:skipped " +
        "WHERE sessionId=:sessionId AND updatedAt < :updatedAt")
    suspend fun updateIfNewer(
        sessionId: String, videoId: String, title: String, artist: String, artists: List<ArtistRef>,
        listenedMs: Long, durationMs: Long, startedAt: Long, updatedAt: Long, completed: Boolean, skipped: Boolean
    )

    @Transaction
    suspend fun upsert(session: ListeningSessionEntity) {
        val inserted = insertIfMissing(session)
        if (inserted == -1L) {
            updateIfNewer(
                session.sessionId, session.videoId, session.title, session.artist, session.artists,
                session.listenedMs, session.durationMs, session.startedAt, session.updatedAt,
                session.completed, session.skipped
            )
        }
    }

    @Transaction
    suspend fun upsertAll(sessions: List<ListeningSessionEntity>) {
        sessions.forEach { upsert(it) }
    }

    @Query("SELECT * FROM listening_sessions ORDER BY startedAt DESC LIMIT :limit")
    fun observeAll(limit: Int = 5000): Flow<List<ListeningSessionEntity>>

    @Query("SELECT * FROM listening_sessions ORDER BY startedAt DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<ListeningSessionEntity>

    @Query("SELECT COALESCE(SUM(listenedMs), 0) FROM listening_sessions WHERE startedAt >= :since AND listenedMs >= :minMs")
    suspend fun totalListenedMsSince(since: Long, minMs: Long = 30_000L): Long

    @Query("DELETE FROM listening_sessions WHERE startedAt < :before")
    suspend fun pruneBefore(before: Long)

    @Query("DELETE FROM listening_sessions")
    suspend fun clear()
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
    @Query("DELETE FROM cached_shelves") suspend fun clear()
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC") fun allPlaylists(): Flow<List<PlaylistEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPlaylist(p: PlaylistEntity)
    @Query("DELETE FROM playlists WHERE id=:id") suspend fun deletePlaylist(id: String)
    @Query("DELETE FROM playlists") suspend fun clearPlaylists()
    @Query("UPDATE playlists SET name=:name WHERE id=:id") suspend fun rename(id: String, name: String)
    @Query("SELECT * FROM playlist_tracks WHERE playlistId=:id ORDER BY position ASC") fun tracksFor(id: String): Flow<List<PlaylistTrackEntity>>
    @Query("SELECT * FROM playlist_tracks ORDER BY playlistId, position ASC") fun allTracks(): Flow<List<PlaylistTrackEntity>>
    @Query("SELECT * FROM playlist_tracks ORDER BY playlistId, position ASC") suspend fun allTracksOnce(): List<PlaylistTrackEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTrack(t: PlaylistTrackEntity)
    @Query("DELETE FROM playlist_tracks WHERE playlistId=:pid AND videoId=:vid") suspend fun removeTrack(pid: String, vid: String)
    @Query("UPDATE playlist_tracks SET position = :newPosition WHERE playlistId = :pid AND videoId = :vid")
    suspend fun updateTrackPosition(pid: String, vid: String, newPosition: Int)
    @Query("DELETE FROM playlist_tracks WHERE playlistId=:id") suspend fun clearTracks(id: String)
    @Query("DELETE FROM playlist_tracks") suspend fun clearAllTracks()
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC") suspend fun allPlaylistsOnce(): List<PlaylistEntity>
    @Query("SELECT * FROM playlists WHERE name LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun searchPlaylists(query: String, limit: Int = 20): List<PlaylistEntity>
    @Query("SELECT * FROM playlist_tracks WHERE playlistId=:id ORDER BY position ASC") suspend fun tracksOnce(id: String): List<PlaylistTrackEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(list: List<PlaylistEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAllTracks(list: List<PlaylistTrackEntity>)

    @Transaction
    suspend fun removeTrackAndCompactPositions(pid: String, vid: String) {
        removeTrack(pid, vid)
        val remaining = tracksOnce(pid)
        remaining.forEachIndexed { index, entity ->
            if (entity.position != index) {
                updateTrackPosition(pid, entity.videoId, index)
            }
        }
    }

    @Transaction
    suspend fun replaceAll(playlists: List<PlaylistEntity>, tracks: List<PlaylistTrackEntity>) {
        clearAllTracks()
        clearPlaylists()
        if (playlists.isNotEmpty()) {
            insertAll(playlists)
            val validPlaylistIds = playlists.asSequence().map { it.id }.toSet()
            val validTracks = tracks.filter { it.playlistId in validPlaylistIds }
            if (validTracks.isNotEmpty()) insertAllTracks(validTracks)
        }
    }
}

@Dao
interface RecentSearchDao {
    @Query("SELECT * FROM recent_searches ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 30): Flow<List<RecentSearchEntity>>

    @Query("SELECT * FROM recent_searches ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentOnce(limit: Int = 30): List<RecentSearchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RecentSearchEntity)

    @Query("DELETE FROM recent_searches WHERE query = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM recent_searches")
    suspend fun clearAll()

    @Query("DELETE FROM recent_searches WHERE query NOT IN (SELECT query FROM recent_searches ORDER BY timestamp DESC LIMIT 30)")
    suspend fun pruneOld()

    @Transaction
    suspend fun addSearch(query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        insert(RecentSearchEntity(query = clean, timestamp = System.currentTimeMillis()))
        pruneOld()
    }
}

data class VideoImpressionCount(
    val videoId: String,
    val count: Int
)

@Dao
interface RecommendationLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImpressions(impressions: List<RecommendationImpressionEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutcome(outcome: RecommendationOutcomeEntity): Long

    @Query("SELECT * FROM recommendation_impressions WHERE videoId = :videoId AND shownAt >= :sinceTimestamp ORDER BY shownAt DESC LIMIT 1")
    suspend fun findRecentImpressionForVideo(videoId: String, sinceTimestamp: Long): RecommendationImpressionEntity?

    @Query("SELECT videoId, COUNT(*) as count FROM recommendation_impressions WHERE shownAt >= :sinceTimestamp GROUP BY videoId")
    suspend fun getImpressionCountsSince(sinceTimestamp: Long): List<VideoImpressionCount>

    @Query("SELECT * FROM recommendation_impressions WHERE requestId = :requestId ORDER BY position ASC")
    suspend fun getImpressionsForRequest(requestId: String): List<RecommendationImpressionEntity>

    @Query("SELECT * FROM recommendation_outcomes WHERE impressionId = :impressionId ORDER BY at ASC")
    suspend fun getOutcomesForImpression(impressionId: Long): List<RecommendationOutcomeEntity>

    @Query("DELETE FROM recommendation_impressions WHERE shownAt < :timestamp")
    suspend fun pruneImpressionsOlderThan(timestamp: Long): Int

    @Query("SELECT COUNT(*) FROM recommendation_impressions")
    suspend fun countImpressions(): Int

    @Query("SELECT COUNT(*) FROM recommendation_outcomes")
    suspend fun countOutcomes(): Int
}

@Dao
interface RecommendationEngineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrackFeature(feature: TrackFeatureEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrackFeatures(features: List<TrackFeatureEntity>)

    @Query("SELECT * FROM track_features WHERE videoId = :videoId")
    suspend fun getTrackFeature(videoId: String): TrackFeatureEntity?

    @Query("SELECT * FROM track_features WHERE videoId IN (:videoIds)")
    suspend fun getTrackFeatures(videoIds: List<String>): List<TrackFeatureEntity>

    @Query("DELETE FROM track_features WHERE updatedAt < :before")
    suspend fun pruneTrackFeaturesBefore(before: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUserProfile(profile: UserPreferenceProfileEntity)

    @Query("SELECT * FROM user_preference_profiles WHERE accountId = :accountId")
    suspend fun getUserProfile(accountId: String): UserPreferenceProfileEntity?

    @Query("DELETE FROM user_preference_profiles WHERE accountId = :accountId")
    suspend fun deleteUserProfile(accountId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExperimentAssignment(assignment: ExperimentAssignmentEntity)

    @Query("SELECT * FROM experiment_assignments WHERE accountId = :accountId")
    suspend fun getExperimentAssignment(accountId: String): ExperimentAssignmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollaborativeInteraction(interaction: CollaborativeInteractionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollaborativeInteractions(interactions: List<CollaborativeInteractionEntity>)

    @Query("SELECT * FROM collaborative_interactions WHERE timestampMs >= :since ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecentCollaborativeInteractions(since: Long, limit: Int = 5000): List<CollaborativeInteractionEntity>

    @Query("DELETE FROM collaborative_interactions WHERE anonUserHash = :anonUserHash")
    suspend fun deleteCollaborativeInteractionsForUser(anonUserHash: String)

    @Transaction
    suspend fun replaceUserCollaborativeInteractions(
        anonUserHash: String,
        interactions: List<CollaborativeInteractionEntity>
    ) {
        deleteCollaborativeInteractionsForUser(anonUserHash)
        if (interactions.isNotEmpty()) {
            insertCollaborativeInteractions(interactions)
        }
    }

    @Query("DELETE FROM collaborative_interactions WHERE timestampMs < :before")
    suspend fun pruneCollaborativeInteractionsBefore(before: Long): Int
}

// ---- Database ----

@Database(
    entities = [
        LikedTrackEntity::class, HistoryTrackEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class, PlayStatEntity::class,
        TrackEntity::class, AlbumEntity::class, ArtistEntity::class, CachedShelfEntity::class, ListeningSessionEntity::class,
        RecentSearchEntity::class, RecommendationImpressionEntity::class, RecommendationOutcomeEntity::class,
        TrackFeatureEntity::class, UserPreferenceProfileEntity::class, ExperimentAssignmentEntity::class, CollaborativeInteractionEntity::class,
        UserRecommendationFeedbackEntity::class
    ],
    version = 10,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class GeekifyDatabase : RoomDatabase() {
    abstract fun likedDao(): LikedDao
    abstract fun historyDao(): HistoryDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playStatDao(): PlayStatDao
    abstract fun listeningSessionDao(): ListeningSessionDao
    abstract fun catalogDao(): CatalogDao
    abstract fun shelfCacheDao(): ShelfCacheDao
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun recommendationLogDao(): RecommendationLogDao
    abstract fun recommendationEngineDao(): RecommendationEngineDao
    abstract fun recommendationFeedbackDao(): RecommendationFeedbackDao
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


/** v3 -> v4: adds durable listening sessions without touching existing library or play-count data. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `listening_sessions` (" +
                "`sessionId` TEXT NOT NULL, `videoId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
                "`artists` TEXT NOT NULL, `listenedMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `completed` INTEGER NOT NULL, " +
                "`skipped` INTEGER NOT NULL, PRIMARY KEY(`sessionId`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_listening_sessions_startedAt` ON `listening_sessions` (`startedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_listening_sessions_videoId` ON `listening_sessions` (`videoId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_listening_sessions_artist` ON `listening_sessions` (`artist`)")
    }
}

/** v4 -> v5: adds foreign-key CASCADE constraint and composite (playlistId, position) index on playlist_tracks. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `playlist_tracks_new` (" +
                "`playlistId` TEXT NOT NULL, `position` INTEGER NOT NULL, `videoId` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `artist` TEXT NOT NULL, `artists` TEXT NOT NULL, " +
                "`album` TEXT, `albumId` TEXT, `thumbnails` TEXT NOT NULL, `duration` TEXT, " +
                "`durationSeconds` INTEGER, `explicit` INTEGER NOT NULL, `type` TEXT NOT NULL, " +
                "PRIMARY KEY(`playlistId`, `videoId`), " +
                "FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE CASCADE ON DELETE CASCADE)"
        )
        db.execSQL(
            "INSERT INTO `playlist_tracks_new` (" +
                "`playlistId`, `position`, `videoId`, `title`, `artist`, `artists`, `album`, " +
                "`albumId`, `thumbnails`, `duration`, `durationSeconds`, `explicit`, `type`) " +
                "SELECT `playlistId`, `position`, `videoId`, `title`, `artist`, `artists`, `album`, " +
                "`albumId`, `thumbnails`, `duration`, `durationSeconds`, `explicit`, `type` " +
                "FROM `playlist_tracks` WHERE `playlistId` IN (SELECT `id` FROM `playlists`)"
        )
        db.execSQL("DROP TABLE `playlist_tracks`")
        db.execSQL("ALTER TABLE `playlist_tracks_new` RENAME TO `playlist_tracks`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId_position` " +
                "ON `playlist_tracks` (`playlistId`, `position`)"
        )
    }
}

/** v5 -> v6: adds indexes for addedAt, playCount, cachedAt, and playedAt to eliminate unindexed table scans. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_liked_tracks_addedAt` ON `liked_tracks` (`addedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_tracks_playedAt` ON `history_tracks` (`playedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_stats_playCount_lastPlayedAt` ON `play_stats` (`playCount`, `lastPlayedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_cachedAt` ON `tracks` (`cachedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_albums_cachedAt` ON `albums` (`cachedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_artists_cachedAt` ON `artists` (`cachedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_shelves_cachedAt` ON `cached_shelves` (`cachedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId_position` ON `playlist_tracks` (`playlistId`, `position`)")
    }
}

/** v6 -> v7: adds recent_searches table with timestamp index. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recent_searches` (`query` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`query`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recent_searches_timestamp` ON `recent_searches` (`timestamp`)")
    }
}

/** v7 -> v8: adds recommendation_impressions and recommendation_outcomes tables with indexes and foreign key. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recommendation_impressions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`accountId` TEXT, " +
                "`requestId` TEXT NOT NULL, " +
                "`mixId` TEXT NOT NULL, " +
                "`videoId` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, " +
                "`sourceSeed` TEXT, " +
                "`score` REAL NOT NULL, " +
                "`shownAt` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recommendation_impressions_videoId_shownAt` ON `recommendation_impressions` (`videoId`, `shownAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recommendation_impressions_requestId` ON `recommendation_impressions` (`requestId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recommendation_impressions_accountId` ON `recommendation_impressions` (`accountId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recommendation_impressions_shownAt` ON `recommendation_impressions` (`shownAt`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recommendation_outcomes` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`impressionId` INTEGER NOT NULL, " +
                "`event` TEXT NOT NULL, " +
                "`at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`impressionId`) REFERENCES `recommendation_impressions`(`id`) ON UPDATE CASCADE ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recommendation_outcomes_impressionId` ON `recommendation_outcomes` (`impressionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recommendation_outcomes_at` ON `recommendation_outcomes` (`at`)")
    }
}

/** v8 -> v9: adds provenance/experiment columns and tables for track features, user preference profiles, experiments, and collaborative interactions. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `recommendation_impressions` ADD COLUMN `candidateSource` TEXT")
        db.execSQL("ALTER TABLE `recommendation_impressions` ADD COLUMN `modelVersion` TEXT NOT NULL DEFAULT 'v2.4-hybrid'")
        db.execSQL("ALTER TABLE `recommendation_impressions` ADD COLUMN `experimentGroup` TEXT NOT NULL DEFAULT 'CONTROL'")

        db.execSQL("ALTER TABLE `recommendation_outcomes` ADD COLUMN `accountId` TEXT")
        db.execSQL("ALTER TABLE `recommendation_outcomes` ADD COLUMN `playDurationMs` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `recommendation_outcomes` ADD COLUMN `dedupeKey` TEXT")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `track_features` (" +
                "`videoId` TEXT NOT NULL, `normalizedIdentity` TEXT NOT NULL, `isrc` TEXT, `mbRecordingId` TEXT, " +
                "`recordingVariant` TEXT NOT NULL, `language` TEXT, `languageConfidence` REAL NOT NULL, " +
                "`genresJson` TEXT NOT NULL, `moodsJson` TEXT NOT NULL, `audioEmbeddingJson` TEXT NOT NULL, " +
                "`lyricsEmbeddingJson` TEXT NOT NULL, `featureSource` TEXT NOT NULL, `tempoBpm` REAL, " +
                "`energy` REAL, `acousticness` REAL, `valence` REAL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`videoId`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_features_normalizedIdentity` ON `track_features` (`normalizedIdentity`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_features_language` ON `track_features` (`language`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_features_updatedAt` ON `track_features` (`updatedAt`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_preference_profiles` (" +
                "`accountId` TEXT NOT NULL, `tasteVectorJson` TEXT NOT NULL, `artistAffinitiesJson` TEXT NOT NULL, " +
                "`genreAffinitiesJson` TEXT NOT NULL, `moodAffinitiesJson` TEXT NOT NULL, " +
                "`languageAffinitiesJson` TEXT NOT NULL, `contextAffinitiesJson` TEXT NOT NULL, " +
                "`negativeSignalsJson` TEXT NOT NULL, `explorationTolerance` REAL NOT NULL, " +
                "`modelVersion` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`accountId`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_preference_profiles_updatedAt` ON `user_preference_profiles` (`updatedAt`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `experiment_assignments` (" +
                "`accountId` TEXT NOT NULL, `experimentId` TEXT NOT NULL, `treatmentGroup` TEXT NOT NULL, " +
                "`assignedAt` INTEGER NOT NULL, `killSwitchActive` INTEGER NOT NULL, " +
                "PRIMARY KEY(`accountId`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_experiment_assignments_experimentId_treatmentGroup` ON `experiment_assignments` (`experimentId`, `treatmentGroup`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `collaborative_interactions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `anonUserHash` TEXT NOT NULL, " +
                "`videoId` TEXT NOT NULL, `artistKey` TEXT NOT NULL, `interactionType` TEXT NOT NULL, " +
                "`weight` REAL NOT NULL, `timestampMs` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_collaborative_interactions_videoId_timestampMs` ON `collaborative_interactions` (`videoId`, `timestampMs`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_collaborative_interactions_anonUserHash_timestampMs` ON `collaborative_interactions` (`anonUserHash`, `timestampMs`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_collaborative_interactions_artistKey` ON `collaborative_interactions` (`artistKey`)")
    }
}

/** v9 -> v10: adds user recommendation feedback table for explicit user controls (more like this, hide, block artist, etc.). */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_recommendation_feedback` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`targetType` TEXT NOT NULL, " +
                "`targetId` TEXT NOT NULL, " +
                "`targetName` TEXT NOT NULL, " +
                "`action` TEXT NOT NULL, " +
                "`createdAtMs` INTEGER NOT NULL, " +
                "`undo` INTEGER NOT NULL DEFAULT 0, " +
                "`synced` INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_user_recommendation_feedback_targetType_targetId` ON `user_recommendation_feedback` (`targetType`, `targetId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_recommendation_feedback_action` ON `user_recommendation_feedback` (`action`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_recommendation_feedback_createdAtMs` ON `user_recommendation_feedback` (`createdAtMs`)")
    }
}
