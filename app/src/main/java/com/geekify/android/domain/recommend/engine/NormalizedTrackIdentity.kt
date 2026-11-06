package com.geekify.android.domain.recommend.engine

import com.geekify.android.data.model.Track
import java.util.Locale

enum class RecordingVariant {
    ORIGINAL,
    LIVE,
    REMASTER,
    COVER,
    REMIX,
    INSTRUMENTAL,
    SLOWED_REVERB,
    ACOUSTIC
}

data class NormalizedTrackIdentity(
    val canonicalKey: String,
    val normalizedTitle: String,
    val primaryArtistKey: String,
    val collaboratorKeys: List<String>,
    val variant: RecordingVariant,
    val explicit: Boolean,
    val isrc: String? = null,
    val mbRecordingId: String? = null,
    val providerTrackIds: Map<String, String> = emptyMap()
)

object TrackIdentityNormalizer {
    private val BRACKET_NOISE = Regex(
        "\\[(?:official|lyrics?|video|audio|hd|4k|full song|mv|visualizer|from\\s+)[^\\]]*]|\\((?:official|lyrics?|video|audio|hd|4k|full song|mv|visualizer|from\\s+)[^)]*\\)",
        RegexOption.IGNORE_CASE
    )
    private val ARTIST_SPLIT = Regex("[,&/|]|\\b(?:feat\\.?|ft\\.?|featuring|with|x)\\b", RegexOption.IGNORE_CASE)
    private val TOPIC_SUFFIX = Regex("\\s*-\\s*topic\\s*$", RegexOption.IGNORE_CASE)
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

    fun detectVariant(title: String, album: String? = null): RecordingVariant {
        val text = "${title.lowercase(Locale.ROOT)} ${album.orEmpty().lowercase(Locale.ROOT)}"
        return when {
            Regex("\\b(instrumental|karaoke|backing track)\\b").containsMatchIn(text) -> RecordingVariant.INSTRUMENTAL
            Regex("\\b(slowed|reverb|lofi flip)\\b").containsMatchIn(text) -> RecordingVariant.SLOWED_REVERB
            Regex("\\b(remix|bootleg|club mix|vip mix|mashup)\\b").containsMatchIn(text) -> RecordingVariant.REMIX
            Regex("\\b(cover|tribute)\\b").containsMatchIn(text) -> RecordingVariant.COVER
            Regex("\\b(live|concert|unplugged session|live at)\\b").containsMatchIn(text) -> RecordingVariant.LIVE
            Regex("\\b(remaster|remastered)\\b").containsMatchIn(text) -> RecordingVariant.REMASTER
            Regex("\\b(acoustic|unplugged)\\b").containsMatchIn(text) -> RecordingVariant.ACOUSTIC
            else -> RecordingVariant.ORIGINAL
        }
    }

    fun normalizeTitleCore(rawTitle: String): String {
        val stripped = BRACKET_NOISE.replace(rawTitle, " ")
            .substringBefore(" - Official")
            .substringBefore(" | ")
            .lowercase(Locale.ROOT)
        return NON_ALNUM.replace(stripped, " ").trim().replace(Regex("\\s+"), " ")
    }

    fun parseArtists(rawArtist: String): List<String> =
        ARTIST_SPLIT.split(TOPIC_SUFFIX.replace(rawArtist, ""))
            .map { NON_ALNUM.replace(TOPIC_SUFFIX.replace(it, "").lowercase(Locale.ROOT), " ").trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .distinct()

    fun normalize(
        track: Track,
        isrc: String? = null,
        mbRecordingId: String? = null,
        providerId: String = "ytm"
    ): NormalizedTrackIdentity {
        val variant = detectVariant(track.title, track.album)
        val normTitle = normalizeTitleCore(track.title)
        val artists = if (track.artists.isNotEmpty()) {
            track.artists.mapNotNull { a ->
                NON_ALNUM.replace(a.name.lowercase(Locale.ROOT), " ").trim().takeIf { it.isNotEmpty() }
            }
        } else {
            parseArtists(track.artist)
        }
        val primaryArtist = artists.firstOrNull() ?: "unknown"
        val collaborators = artists.drop(1)
        val explicitSuffix = if (track.explicit) "E" else "C"

        val canonicalKey = when {
            !isrc.isNullOrBlank() -> "isrc:${isrc.trim().uppercase(Locale.ROOT)}:${variant.name}:$explicitSuffix"
            !mbRecordingId.isNullOrBlank() -> "mbid:${mbRecordingId.trim().lowercase(Locale.ROOT)}:${variant.name}:$explicitSuffix"
            else -> "meta:$primaryArtist:$normTitle:${variant.name}:$explicitSuffix"
        }

        return NormalizedTrackIdentity(
            canonicalKey = canonicalKey,
            normalizedTitle = normTitle,
            primaryArtistKey = primaryArtist,
            collaboratorKeys = collaborators,
            variant = variant,
            explicit = track.explicit,
            isrc = isrc,
            mbRecordingId = mbRecordingId,
            providerTrackIds = mapOf(providerId to track.videoId)
        )
    }

    /**
     * Two tracks represent the same underlying recording only if their canonical identity matches
     * (including RecordingVariant so Live, Remix, Cover, Remaster, and Original are never conflated).
     */
    fun isSameRecording(a: NormalizedTrackIdentity, b: NormalizedTrackIdentity): Boolean {
        if (a.variant != b.variant) return false
        if (!a.isrc.isNullOrBlank() && !b.isrc.isNullOrBlank()) {
            return a.isrc.equals(b.isrc, ignoreCase = true) && a.explicit == b.explicit
        }
        if (!a.mbRecordingId.isNullOrBlank() && !b.mbRecordingId.isNullOrBlank()) {
            return a.mbRecordingId.equals(b.mbRecordingId, ignoreCase = true)
        }
        return a.normalizedTitle == b.normalizedTitle &&
            a.primaryArtistKey == b.primaryArtistKey &&
            a.explicit == b.explicit
    }
}
