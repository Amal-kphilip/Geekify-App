package com.geekify.android.notifications

import com.geekify.android.data.model.Track

/** Copy for a re-engagement notification, derived only from locally stored playback history. */
data class EngagementMessage(val trackId: String, val title: String, val body: String)

object EngagementMessageFactory {
    /** Three completed listens are enough to treat a track as a repeat-listening preference. */
    const val REPEAT_PLAY_THRESHOLD = 3

    fun create(track: Track, playCount: Int): EngagementMessage {
        val title = track.title.notificationSafeText(72).ifBlank { "your recent song" }
        val artist = track.artist.notificationSafeText(56).takeUnless {
            it.isBlank() || it.equals("unknown", ignoreCase = true)
        }
        val repeated = playCount >= REPEAT_PLAY_THRESHOLD
        val body = when {
            repeated && artist != null -> "Revisit “$title” by $artist — one of your repeat-listened tracks."
            repeated -> "Revisit “$title” — one of your repeat-listened tracks."
            artist != null -> "Pick up with “$title” by $artist in your recent songs."
            else -> "Pick up with “$title” in your recent songs."
        }
        return EngagementMessage(
            trackId = track.videoId,
            title = if (repeated) "Your repeat pick is waiting" else "Pick up where you left off",
            body = body
        )
    }

    private fun String.notificationSafeText(maxLength: Int): String =
        replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(maxLength)
}
