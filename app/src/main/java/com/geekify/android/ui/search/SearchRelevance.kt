package com.geekify.android.ui.search

import com.geekify.android.data.model.Card
import com.geekify.android.data.model.Track
import java.text.Normalizer
import java.util.Locale

/**
 * Small, deterministic client-side relevance pass over YouTube Music's ordered search results.
 * Server order is retained for equal scores; exact title/artist matches are promoted so a broad
 * nested renderer response cannot put a weak match in the Top Result slot.
 */
internal object SearchRelevance {
    private fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun score(query: String, title: String?, secondary: String?, tertiary: String? = null): Int {
        val q = normalize(query)
        val titleText = normalize(title)
        val secondaryText = normalize(secondary)
        val tertiaryText = normalize(tertiary)
        if (q.isEmpty()) return 0

        var result = 0
        when {
            titleText == q -> result += 180
            titleText.startsWith(q) -> result += 145
            titleText.contains(q) -> result += 115
        }
        when {
            secondaryText == q -> result += 135
            secondaryText.contains(q) -> result += 100
        }
        if (tertiaryText.contains(q)) result += 35

        val tokens = q.split(' ').filter { it.length > 1 }.distinct()
        if (tokens.isNotEmpty()) {
            val titleHits = tokens.count { token -> titleText.split(' ').any { it == token || it.startsWith(token) } }
            val secondaryHits = tokens.count { token -> secondaryText.contains(token) }
            val tertiaryHits = tokens.count { token -> tertiaryText.contains(token) }
            result += (titleHits * 22) + (secondaryHits * 15) + (tertiaryHits * 5)
            if (titleHits == tokens.size) result += 25
        }
        return result
    }

    fun rankTracks(query: String, tracks: List<Track>): List<Track> {
        val ranked = tracks.withIndex().map { indexed ->
            indexed to score(query, indexed.value.title, indexed.value.artist, indexed.value.album)
        }
        // Drop clearly unrelated rows when the response contains genuine matches. If the source
        // returned items with incomplete metadata and none can be scored, retain server results
        // rather than turning a valid response into an empty screen.
        val relevant = ranked.filter { it.second > 0 }.ifEmpty { ranked }
        return relevant.sortedWith(
            compareByDescending<Pair<IndexedValue<Track>, Int>> { it.second }
                .thenBy { it.first.index }
        ).map { it.first.value }
    }

    fun rankCards(query: String, cards: List<Card>): List<Card> {
        val ranked = cards.withIndex().map { indexed ->
            indexed to score(query, indexed.value.title, indexed.value.subtitle)
        }
        val relevant = ranked.filter { it.second > 0 }.ifEmpty { ranked }
        return relevant.sortedWith(
            compareByDescending<Pair<IndexedValue<Card>, Int>> { it.second }
                .thenBy { it.first.index }
        ).map { it.first.value }
    }
}
