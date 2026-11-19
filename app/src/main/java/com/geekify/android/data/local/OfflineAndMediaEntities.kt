package com.geekify.android.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Separates temporary streaming audio cache ([KIND_CACHE]) from permanent offline downloads ([KIND_DOWNLOAD]).
 * LRU eviction only ever touches [KIND_CACHE], never deleting user downloads.
 */
@Entity(
    tableName = "cached_audio",
    indices = [
        Index(value = ["kind", "lastAccessedAt"]),
        Index(value = ["kind"])
    ]
)
data class CachedAudioEntity(
    @PrimaryKey val videoId: String,
    val kind: String, // "CACHE" or "DOWNLOAD"
    val filePath: String,
    val mimeType: String,
    val contentLength: Long,
    val durationMs: Long = 0L,
    val title: String = "",
    val artist: String = "",
    val thumbnailUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val KIND_CACHE = "CACHE"
        const val KIND_DOWNLOAD = "DOWNLOAD"
    }
}

@Dao
interface CachedAudioDao {
    @Query("SELECT * FROM cached_audio WHERE videoId = :videoId LIMIT 1")
    suspend fun find(videoId: String): CachedAudioEntity?

    @Query("SELECT * FROM cached_audio WHERE kind = :kind ORDER BY lastAccessedAt ASC")
    suspend fun byKindOldestFirst(kind: String): List<CachedAudioEntity>

    @Query("SELECT * FROM cached_audio WHERE kind = :kind ORDER BY createdAt DESC")
    fun observeByKind(kind: String): Flow<List<CachedAudioEntity>>

    @Query("SELECT COALESCE(SUM(contentLength), 0) FROM cached_audio WHERE kind = :kind")
    suspend fun totalBytesByKind(kind: String): Long

    @Query("SELECT COUNT(*) FROM cached_audio WHERE kind = :kind")
    suspend fun countByKind(kind: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedAudioEntity)

    @Query("UPDATE cached_audio SET lastAccessedAt = :timestamp WHERE videoId = :videoId")
    suspend fun touch(videoId: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM cached_audio WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("DELETE FROM cached_audio WHERE kind = :kind")
    suspend fun deleteAllByKind(kind: String)
}

/**
 * Persistent offline download queue entries. Survives app restarts and process death.
 */
@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["status", "createdAt"]),
        Index(value = ["playlistId"])
    ]
)
data class DownloadEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val albumId: String? = null,
    val playlistId: String? = null,
    val status: String, // QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
    val progress: Float = 0f,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val filePath: String? = null,
    val mimeType: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_PAUSED = "PAUSED"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY updatedAt DESC")
    fun observeCompleted(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE videoId = :videoId LIMIT 1")
    suspend fun find(videoId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextQueued(): DownloadEntity?

    @Query("UPDATE downloads SET status = 'QUEUED', updatedAt = :now WHERE status = 'RUNNING'")
    suspend fun requeueInterrupted(now: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("UPDATE downloads SET status = :status, progress = :progress, bytesDownloaded = :bytes, totalBytes = :total, updatedAt = :now WHERE videoId = :videoId")
    suspend fun updateProgress(
        videoId: String,
        status: String,
        progress: Float,
        bytes: Long,
        total: Long,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE downloads SET status = :status, errorMessage = :error, updatedAt = :now WHERE videoId = :videoId")
    suspend fun updateStatus(
        videoId: String,
        status: String,
        error: String? = null,
        now: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM downloads WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("DELETE FROM downloads")
    suspend fun clearAll()
}

/**
 * Shazam music recognition history persisted locally in Room.
 */
@Entity(
    tableName = "recognition_history",
    indices = [
        Index(value = ["recognizedAt"]),
        Index(value = ["trackId"])
    ]
)
data class RecognitionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val coverArtUrl: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val label: String? = null,
    val shazamUrl: String? = null,
    val spotifyUrl: String? = null,
    val appleMusicUrl: String? = null,
    val isrc: String? = null,
    val youtubeVideoId: String? = null,
    val recognizedAt: Long = System.currentTimeMillis()
)

@Dao
interface RecognitionHistoryDao {
    @Query("SELECT * FROM recognition_history ORDER BY recognizedAt DESC")
    fun observeAll(): Flow<List<RecognitionHistoryEntity>>

    @Query(
        "SELECT * FROM recognition_history " +
            "WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR COALESCE(album, '') LIKE '%' || :query || '%' " +
            "ORDER BY recognizedAt DESC"
    )
    fun search(query: String): Flow<List<RecognitionHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RecognitionHistoryEntity): Long

    @Query("UPDATE recognition_history SET youtubeVideoId = :videoId WHERE id = :id")
    suspend fun updateYoutubeVideoId(id: Long, videoId: String)

    @Query("DELETE FROM recognition_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM recognition_history")
    suspend fun clearAll()
}

/**
 * Cached synchronized/unsynchronized lyrics with per-track offset, translation, and romanization.
 */
@Entity(
    tableName = "lyrics",
    indices = [Index(value = ["updatedAt"])]
)
data class LyricsEntity(
    @PrimaryKey val videoId: String,
    val rawLyrics: String,
    val provider: String,
    val offsetMs: Long = 0L,
    val translatedLyrics: String? = null,
    val translationLanguage: String? = null,
    val romanizedLyrics: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE videoId = :videoId LIMIT 1")
    suspend fun find(videoId: String): LyricsEntity?

    @Query("SELECT * FROM lyrics WHERE videoId = :videoId LIMIT 1")
    fun observe(videoId: String): Flow<LyricsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LyricsEntity)

    @Query("UPDATE lyrics SET offsetMs = :offsetMs, updatedAt = :now WHERE videoId = :videoId")
    suspend fun updateOffset(videoId: String, offsetMs: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM lyrics WHERE videoId = :videoId")
    suspend fun delete(videoId: String)
}
