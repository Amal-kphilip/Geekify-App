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
 * User recommendation feedback targets: individual track, artist, genre, or mood.
 */
enum class FeedbackTargetType {
    TRACK,
    ARTIST,
    GENRE,
    MOOD
}

/**
 * User feedback actions on recommendations.
 */
enum class FeedbackAction {
    MORE_LIKE_THIS,
    LESS_LIKE_THIS,
    NOT_INTERESTED,
    HIDE_TRACK,
    BLOCK_ARTIST,
    PREFER_MOOD,
    EXPLORE_GENRE
}

@Entity(
    tableName = "user_recommendation_feedback",
    indices = [
        Index(value = ["targetType", "targetId"], unique = true),
        Index(value = ["action"]),
        Index(value = ["createdAtMs"])
    ]
)
data class UserRecommendationFeedbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val targetType: String,
    val targetId: String,
    val targetName: String,
    val action: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    val undo: Boolean = false,
    val synced: Boolean = false
)

@Dao
interface RecommendationFeedbackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(feedback: UserRecommendationFeedbackEntity): Long

    @Query("SELECT * FROM user_recommendation_feedback WHERE undo = 0 ORDER BY createdAtMs DESC")
    fun observeActiveFeedback(): Flow<List<UserRecommendationFeedbackEntity>>

    @Query("SELECT targetId FROM user_recommendation_feedback WHERE targetType = 'TRACK' AND (action = 'HIDE_TRACK' OR action = 'NOT_INTERESTED') AND undo = 0")
    fun observeHiddenTrackIds(): Flow<List<String>>

    @Query("SELECT targetId FROM user_recommendation_feedback WHERE targetType = 'TRACK' AND (action = 'HIDE_TRACK' OR action = 'NOT_INTERESTED') AND undo = 0")
    suspend fun getHiddenTrackIdsOnce(): List<String>

    @Query("SELECT targetId FROM user_recommendation_feedback WHERE targetType = 'ARTIST' AND action = 'BLOCK_ARTIST' AND undo = 0")
    fun observeBlockedArtists(): Flow<List<String>>

    @Query("SELECT targetId FROM user_recommendation_feedback WHERE targetType = 'ARTIST' AND action = 'BLOCK_ARTIST' AND undo = 0")
    suspend fun getBlockedArtistsOnce(): List<String>

    @Query("SELECT * FROM user_recommendation_feedback WHERE targetType = :targetType AND targetId = :targetId LIMIT 1")
    suspend fun getFeedback(targetType: String, targetId: String): UserRecommendationFeedbackEntity?

    @Query("UPDATE user_recommendation_feedback SET undo = 1 WHERE targetType = :targetType AND targetId = :targetId")
    suspend fun undoFeedback(targetType: String, targetId: String): Int

    @Query("DELETE FROM user_recommendation_feedback")
    suspend fun clearAllFeedback(): Int
}
