package com.geekify.android.ui.search

import com.geekify.android.data.model.Card
import com.geekify.android.data.model.Track
import java.text.Normalizer
import java.util.Locale

/**
 * Pure, deterministic search result re-ranking engine.
 *
 * Scored features & documented weights:
 * - Exact title match: +200
 * - Title prefix match: +150
 * - Title substring match: +100
 * - Exact artist match: +160
 * - Artist substring match: +90
 * - Album substring match: +35
 * - Title token match: +25 per token (plus +35 if all query tokens match)
 * - Artist token match: +15 per token
 * - Album token match: +5 per token
 * - Official audio boost: +80
 * - User favorite/liked artist boost: +50
 * - Cover demotion: -70 (unless query asks for "cover")
 * - Live demotion: -60 (unless query asks for "live")
 * - Karaoke / Instrumental demotion: -80 (unless query asks for it)
 * - Remix demotion: -50 (unless query asks for "remix")
 * - Lyric video demotion: -40 (unless query asks for "lyric" / "lyrics")
 * - Slowed / Reverb demotion: -70 (unless query asks for it)
 * - Deduplication across versions (preserves highest-scored official version)
 */
object SearchRelevance {

    const val WEIGHT_EXACT_TITLE = 200
    const val WEIGHT_TITLE_PREFIX = 150
    const val WEIGHT_TITLE_CONTAINS = 100
    const val WEIGHT_EXACT_ARTIST = 160
    const val WEIGHT_ARTIST_CONTAINS = 90
    const val WEIGHT_ALBUM_CONTAINS = 35
    const val WEIGHT_TOKEN_TITLE = 25
    const val WEIGHT_ALL_TOKENS_TITLE_BONUS = 35
    const val WEIGHT_TOKEN_ARTIST = 15
    const val WEIGHT_TOKEN_ALBUM = 5
    const val WEIGHT_OFFICIAL_AUDIO = 80
    const val WEIGHT_FAVORITE_ARTIST = 50

    const val PENALTY_COVER = 70
    const val PENALTY_LIVE = 60
    const val PENALTY_KARAOKE = 80
    const val PENALTY_REMIX = 50
    const val PENALTY_LYRICS = 40
    const val PENALTY_SLOWED_REVERB = 70

    fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    /**
     * Pure scoring function for a single track.
     */
    fun scoreTrack(
        query: String,
        track: Track,
        isFavoriteArtist: Boolean = false
    ): Int {
        val q = normalize(query)
        val titleText = normalize(track.title)
        val artistText = normalize(track.artist)
        val albumText = normalize(track.album)
        if (q.isEmpty()) return 0

        var score = 0

        // Title matches
        when {
            titleText == q -> score += WEIGHT_EXACT_TITLE
            titleText.startsWith(q) -> score += WEIGHT_TITLE_PREFIX
            titleText.contains(q) -> score += WEIGHT_TITLE_CONTAINS
        }

        // Artist matches
        when {
            artistText == q -> score += WEIGHT_EXACT_ARTIST
            artistText.contains(q) -> score += WEIGHT_ARTIST_CONTAINS
        }

        // Album matches
        if (albumText.isNotBlank() && albumText.contains(q)) {
            score += WEIGHT_ALBUM_CONTAINS
        }

        // Token breakdown
        val tokens = q.split(' ').filter { it.length > 1 }.distinct()
        if (tokens.isNotEmpty()) {
            val titleHits = tokens.count { token -> titleText.split(' ').any { it == token || it.startsWith(token) } }
            val artistHits = tokens.count { token -> artistText.contains(token) }
            val albumHits = tokens.count { token -> albumText.contains(token) }
            score += (titleHits * WEIGHT_TOKEN_TITLE) + (artistHits * WEIGHT_TOKEN_ARTIST) + (albumHits * WEIGHT_TOKEN_ALBUM)
            if (titleHits == tokens.size) score += WEIGHT_ALL_TOKENS_TITLE_BONUS
        }

        // Demotions & official boosts
        val rawTitle = track.title.lowercase(Locale.ROOT)
        val qLower = query.lowercase(Locale.ROOT)

        val isCover = rawTitle.contains("cover")
        val isLive = rawTitle.contains("live") || rawTitle.contains("concert")
        val isKaraoke = rawTitle.contains("karaoke") || rawTitle.contains("instrumental")
        val isRemix = rawTitle.contains("remix") || rawTitle.contains("mix")
        val isLyrics = rawTitle.contains("lyric") || rawTitle.contains("lyrics")
        val isSlowed = rawTitle.contains("slowed") || rawTitle.contains("reverb")

        if (isCover && !qLower.contains("cover")) score -= PENALTY_COVER
        if (isLive && !qLower.contains("live") && !qLower.contains("concert")) score -= PENALTY_LIVE
        if (isKaraoke && !qLower.contains("karaoke") && !qLower.contains("instrumental")) score -= PENALTY_KARAOKE
        if (isRemix && !qLower.contains("remix") && !qLower.contains("mix")) score -= PENALTY_REMIX
        if (isLyrics && !qLower.contains("lyric") && !qLower.contains("lyrics")) score -= PENALTY_LYRICS
        if (isSlowed && !qLower.contains("slowed") && !qLower.contains("reverb")) score -= PENALTY_SLOWED_REVERB

        // Official audio boost & favorite artist boost only apply when there's an actual query match
        if (score > 0) {
            val isNonOfficial = isCover || isLive || isKaraoke || isSlowed
            if (!isNonOfficial) {
                val hasAlbum = !track.album.isNullOrBlank() || !track.albumId.isNullOrBlank()
                val durationValid = (track.durationSeconds ?: 0) in 45..900
                if (hasAlbum || durationValid) {
                    score += WEIGHT_OFFICIAL_AUDIO
                }
            }

            if (isFavoriteArtist) {
                score += WEIGHT_FAVORITE_ARTIST
            }
        }

        return score
    }

    /**
     * Pure scoring function for Card entities (albums, artists, playlists).
     */
    fun scoreCard(
        query: String,
        card: Card,
        isFavoriteArtist: Boolean = false
    ): Int {
        val q = normalize(query)
        val titleText = normalize(card.title)
        val subtitleText = normalize(card.subtitle)
        if (q.isEmpty()) return 0

        var score = 0
        when {
            titleText == q -> score += WEIGHT_EXACT_TITLE
            titleText.startsWith(q) -> score += WEIGHT_TITLE_PREFIX
            titleText.contains(q) -> score += WEIGHT_TITLE_CONTAINS
        }
        when {
            subtitleText == q -> score += WEIGHT_EXACT_ARTIST
            subtitleText.contains(q) -> score += WEIGHT_ARTIST_CONTAINS
        }

        val tokens = q.split(' ').filter { it.length > 1 }.distinct()
        if (tokens.isNotEmpty()) {
            val titleHits = tokens.count { token -> titleText.split(' ').any { it == token || it.startsWith(token) } }
            val subtitleHits = tokens.count { token -> subtitleText.contains(token) }
            score += (titleHits * WEIGHT_TOKEN_TITLE) + (subtitleHits * WEIGHT_TOKEN_ARTIST)
            if (titleHits == tokens.size) score += WEIGHT_ALL_TOKENS_TITLE_BONUS
        }

        if (score > 0 && isFavoriteArtist) {
            score += WEIGHT_FAVORITE_ARTIST
        }

        return score
    }

    fun cleanTitle(title: String): String {
        return title
            .replace(Regex("(?i)\\s*[\\[(](official\\s*)?(music\\s*)?(video|audio|lyric\\s*video|visualizer|video\\s*clip)[\\])]"), "")
            .replace(Regex("(?i)\\s*-\\s*(official\\s*)?(music\\s*)?(video|audio)"), "")
            .trim()
    }

    fun hasVersionMarker(title: String): Boolean {
        return Regex("(?i)[\\[(](official\\s*)?(music\\s*)?(video|audio|lyric\\s*video|visualizer|video\\s*clip)[\\])]|-\\s*(official\\s*)?(music\\s*)?(video|audio)").containsMatchIn(title)
    }

    /**
     * Re-ranks tracks, boosts official audio, applies demotions, and deduplicates identical song versions.
     */
    fun rankTracks(
        query: String,
        tracks: List<Track>,
        favoriteArtists: Set<String> = emptySet()
    ): List<Track> {
        val normalizedFavorites = favoriteArtists.map { normalize(it) }.toSet()

        val scored = tracks.withIndex().map { indexed ->
            val track = indexed.value
            val isFav = normalizedFavorites.any { fav ->
                val trackArtist = normalize(track.artist)
                trackArtist == fav || trackArtist.contains(fav)
            }
            indexed to scoreTrack(query, track, isFav)
        }

        // Drop clearly unrelated rows when the response contains genuine matches. If the source
        // returned items with incomplete metadata and none can be scored, retain server results.
        val relevant = scored.filter { it.second > 0 }.ifEmpty { scored }

        val sorted = relevant.sortedWith(
            compareByDescending<Pair<IndexedValue<Track>, Int>> { it.second }
                .thenBy { it.first.index }
        ).map { it.first.value }

        // Deduplicate the same song across versions (e.g. "Espresso" and "Espresso (Official Video)")
        // while preserving distinct songs or identical test tracks without version markers.
        val signaturesWithVersions = tracks.filter { hasVersionMarker(it.title) }
            .map { "${normalize(cleanTitle(it.title))}::${normalize(it.artist)}" }
            .toSet()

        val seenSignatures = mutableSetOf<String>()
        return sorted.filter { track ->
            val clean = cleanTitle(track.title)
            val normalizedTitle = normalize(clean)
            val normalizedArtist = normalize(track.artist)
            if (normalizedTitle.isBlank() || normalizedTitle == "unknown") {
                true
            } else {
                val signature = "$normalizedTitle::$normalizedArtist"
                if (signaturesWithVersions.contains(signature)) {
                    seenSignatures.add(signature)
                } else {
                    true
                }
            }
        }
    }

    fun rankCards(
        query: String,
        cards: List<Card>,
        favoriteArtists: Set<String> = emptySet()
    ): List<Card> {
        val normalizedFavorites = favoriteArtists.map { normalize(it) }.toSet()

        val scored = cards.withIndex().map { indexed ->
            val card = indexed.value
            val isFav = normalizedFavorites.any { fav ->
                val cardTitle = normalize(card.title)
                val cardSubtitle = normalize(card.subtitle)
                cardTitle == fav || cardSubtitle.contains(fav)
            }
            indexed to scoreCard(query, card, isFav)
        }

        val relevant = scored.filter { it.second > 0 }.ifEmpty { scored }

        return relevant.sortedWith(
            compareByDescending<Pair<IndexedValue<Card>, Int>> { it.second }
                .thenBy { it.first.index }
        ).map { it.first.value }
    }
}
