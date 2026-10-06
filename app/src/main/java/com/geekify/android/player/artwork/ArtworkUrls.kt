package com.geekify.android.player.artwork

import com.geekify.android.data.model.Thumbnail
import com.geekify.android.data.model.Track

/**
 * Pure (no Android classes) helpers that turn whatever a song carries into one URL that Android can
 * actually load for the media notification / lock screen. Kept free of android.net.Uri so it is unit-testable on the JVM.
 */
object ArtworkUrls {
    /** Notification / lock-screen artwork is shown small; 544 px is plenty and keeps decoding cheap. */
    const val NOTIFICATION_SIZE_PX = 544

    private val SIZE_TOKEN = Regex("w\\d+-h\\d+")

    /**
     * Returns an absolute https/content/file URL or null when [raw] is blank or unusable.
     * - protocol-relative `//host/x` becomes https
     * - plain `http://` for Google/YouTube image hosts is upgraded to https (they redirect cross-protocol,
     *   which many HTTP stacks refuse to follow)
     */
    fun normalize(raw: String?): String? {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return null
        val absolute = when {
            url.startsWith("//") -> "https:$url"
            else -> url
        }
        val lower = absolute.lowercase()
        return when {
            lower.startsWith("https://") -> absolute
            lower.startsWith("http://") && isGoogleImageHost(lower) -> "https://" + absolute.substring(7)
            lower.startsWith("http://") -> absolute
            lower.startsWith("content://") -> absolute
            lower.startsWith("file://") -> absolute
            lower.startsWith("android.resource://") -> absolute
            else -> null // bare paths and unknown schemes are not resolvable by Android: never expose them
        }
    }

    private fun isGoogleImageHost(lowerUrl: String): Boolean =
        listOf("googleusercontent.com", "ggpht.com", "ytimg.com", "youtube.com").any { lowerUrl.contains(it) }

    /** Picks the biggest supplied thumbnail and, for Google-hosted art, asks for a [sizePx] square. */
    fun best(thumbnails: List<Thumbnail>, sizePx: Int = NOTIFICATION_SIZE_PX): String? {
        val candidates = thumbnails.sortedByDescending { (it.width ?: 0) * (it.height ?: 0) }
        for (t in candidates) {
            val url = normalize(t.url) ?: continue
            return url.replace(SIZE_TOKEN, "w$sizePx-h$sizePx")
        }
        return null
    }

    /** Last resort for a YouTube video id whose thumbnails are missing (e.g. a track rebuilt from a bare id). */
    fun fromVideoId(videoId: String): String? =
        videoId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) }
            ?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

    fun forTrack(track: Track, sizePx: Int = NOTIFICATION_SIZE_PX): String? =
        best(track.thumbnails, sizePx) ?: fromVideoId(track.videoId)
}
