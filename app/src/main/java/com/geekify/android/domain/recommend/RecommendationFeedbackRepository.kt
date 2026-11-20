package com.geekify.android.domain.recommend

import com.geekify.android.data.local.FeedbackAction
import com.geekify.android.data.local.FeedbackTargetType
import com.geekify.android.data.local.RecommendationFeedbackDao
import com.geekify.android.data.local.UserRecommendationFeedbackEntity
import com.geekify.android.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
class RecommendationFeedbackRepository constructor(
    private val dao: RecommendationFeedbackDao
) {
    val hiddenTrackIds: Flow<Set<String>> = dao.observeHiddenTrackIds().map { it.toSet() }
    val blockedArtists: Flow<Set<String>> = dao.observeBlockedArtists().map { it.map(String::lowercase).toSet() }
    val activeFeedback: Flow<List<UserRecommendationFeedbackEntity>> = dao.observeActiveFeedback()

    fun observeTrackFeedbackAction(videoId: String): Flow<FeedbackAction?> =
        dao.observeTrackFeedback(videoId).map { entity ->
            entity?.takeIf { !it.undo }?.let {
                runCatching { FeedbackAction.valueOf(it.action) }.getOrNull()
            }
        }

    /**
     * Persists explicit track feedback and prevents duplicate submissions.
     * For [FeedbackAction.MORE_LIKE_THIS] / [FeedbackAction.LESS_LIKE_THIS], also stores a
     * companion artist affinity row so other tracks by the same artist are ranked accordingly
     * without blocking unrelated music from the same language or genre.
     */
    suspend fun recordTrackFeedback(
        track: Track,
        action: FeedbackAction
    ): Boolean {
        val existing = dao.getFeedback(FeedbackTargetType.TRACK.name, track.videoId)
        if (existing != null && !existing.undo && existing.action == action.name) {
            return false // Already recorded — prevent duplicate submission
        }
        val entity = UserRecommendationFeedbackEntity(
            targetType = FeedbackTargetType.TRACK.name,
            targetId = track.videoId,
            targetName = if (track.artist.isNotBlank()) "${track.title} — ${track.artist}" else track.title,
            action = action.name,
            createdAtMs = System.currentTimeMillis(),
            undo = false
        )
        dao.insertOrUpdate(entity)
        if (action == FeedbackAction.MORE_LIKE_THIS || action == FeedbackAction.LESS_LIKE_THIS) {
            val normalizedArtist = track.artist.trim().lowercase()
            if (normalizedArtist.isNotBlank() && normalizedArtist != "unknown") {
                val existingArtist = dao.getFeedback(FeedbackTargetType.ARTIST.name, normalizedArtist)
                // Never overwrite an explicit BLOCK_ARTIST rule with softer feedback
                if (existingArtist == null || existingArtist.undo || existingArtist.action != FeedbackAction.BLOCK_ARTIST.name) {
                    dao.insertOrUpdate(
                        UserRecommendationFeedbackEntity(
                            targetType = FeedbackTargetType.ARTIST.name,
                            targetId = normalizedArtist,
                            targetName = track.artist.trim(),
                            action = action.name,
                            createdAtMs = System.currentTimeMillis(),
                            undo = false
                        )
                    )
                }
            }
        }
        return true
    }

    /**
     * Toggles [action] for [track]: if [action] is already active for this track, clears it
     * and returns `null`; otherwise records [action] and returns [action].
     */
    suspend fun toggleTrackFeedback(
        track: Track,
        action: FeedbackAction
    ): FeedbackAction? {
        val existing = dao.getFeedback(FeedbackTargetType.TRACK.name, track.videoId)
        if (existing != null && !existing.undo && existing.action == action.name) {
            dao.undoFeedback(FeedbackTargetType.TRACK.name, track.videoId)
            val normalizedArtist = track.artist.trim().lowercase()
            if (normalizedArtist.isNotBlank()) {
                val artistRow = dao.getFeedback(FeedbackTargetType.ARTIST.name, normalizedArtist)
                if (artistRow != null && !artistRow.undo && artistRow.action == action.name) {
                    dao.undoFeedback(FeedbackTargetType.ARTIST.name, normalizedArtist)
                }
            }
            return null
        }
        recordTrackFeedback(track, action)
        return action
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
