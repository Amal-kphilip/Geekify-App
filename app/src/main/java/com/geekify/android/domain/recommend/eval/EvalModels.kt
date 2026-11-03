package com.geekify.android.domain.recommend.eval

import kotlinx.serialization.Serializable

@Serializable
data class EvalTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSeconds: Int = 210,
    val language: String? = null
)

@Serializable
data class EvalEvent(
    val track: EvalTrack,
    val timestamp: Long,
    val completed: Boolean = true,
    val skipped: Boolean = false,
    val isLiked: Boolean = false,
    val isPlaylist: Boolean = false
)

@Serializable
data class EvalListener(
    val id: String,
    val persona: String,
    val cutoffTimestamp: Long,
    val history: List<EvalEvent>,
    val candidatePool: Map<String, List<EvalTrack>>
)

data class EvalMetrics(
    val hitRateAt10: Double,
    val ndcgAt10: Double,
    val meanCompletionRateAt10: Double,
    val artistDiversityAt10: Double
)

data class UserEvalResult(
    val listenerId: String,
    val persona: String,
    val top10: List<String>,
    val hitCount: Int,
    val ndcg: Double,
    val artistDiversity: Double,
    val completionRate: Double
)

data class FullEvalReport(
    val metrics: EvalMetrics,
    val userReports: List<UserEvalResult>
)
