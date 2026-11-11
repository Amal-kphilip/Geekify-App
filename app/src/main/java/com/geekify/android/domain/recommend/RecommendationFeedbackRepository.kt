package com.geekify.android.domain.recommend

import com.geekify.android.data.local.FeedbackAction
import com.geekify.android.data.local.FeedbackTargetType
import com.geekify.android.data.local.RecommendationFeedbackDao
import com.geekify.android.data.local.UserRecommendationFeedbackEntity
import com.geekify.android.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecommendationFeedbackRepository @Inject constructor(
    private val dao: RecommendationFeedbackDao
) {
    val hiddenTrackIds: Flow<Set<String>> = dao.observeHiddenTrackIds().map { it.toSet() }
    val blockedArtists: Flow<Set<String>> = dao.observeBlockedArtists().map { it.map(String::lowercase).toSet() }
    val activeFeedback: Flow<List<UserRecommendationFeedbackEntity>> = dao.observeActiveFeedback()

    suspend fun recordTrackFeedback(
        track: Track,
        action: FeedbackAction
    ): Boolean {
        val entity = UserRecommendationFeedbackEntity(
            targetType = FeedbackTargetType.TRACK.name,
            targetId = track.videoId,
            targetName = track.title,
            action = action.name,
            createdAtMs = System.currentTimeMillis(),
            undo = false
        )
        dao.insertOrUpdate(entity)
        return true
    }

    suspend fun recordArtistFeedback(
        artistName: String,
        action: FeedbackAction
    ): Boolean {
        val normalized = artistName.trim().lowercase()
        if (normalized.isBlank()) return false
        val entity = UserRecommendationFeedbackEntity(
            targetType = FeedbackTargetType.ARTIST.name,
            targetId = normalized,
            targetName = artistName.trim(),
            action = action.name,
            createdAtMs = System.currentTimeMillis(),
            undo = false
        )
        dao.insertOrUpdate(entity)
        return true
    }

    suspend fun recordGenreOrMoodFeedback(
        type: FeedbackTargetType,
        key: String,
        displayName: String,
        action: FeedbackAction
    ): Boolean {
        val entity = UserRecommendationFeedbackEntity(
            targetType = type.name,
            targetId = key.trim().lowercase(),
            targetName = displayName.trim(),
            action = action.name,
            createdAtMs = System.currentTimeMillis(),
            undo = false
        )
        dao.insertOrUpdate(entity)
        return true
    }

    suspend fun undoFeedback(targetType: FeedbackTargetType, targetId: String): Boolean {
        return dao.undoFeedback(targetType.name, targetId.trim().lowercase()) > 0
    }

    suspend fun resetAllPreferences(): Boolean {
        return dao.clearAllFeedback() >= 0
    }

    suspend fun getHiddenTrackIdsOnce(): Set<String> = dao.getHiddenTrackIdsOnce().toSet()
    suspend fun getBlockedArtistsOnce(): Set<String> = dao.getBlockedArtistsOnce().map(String::lowercase).toSet()
}
