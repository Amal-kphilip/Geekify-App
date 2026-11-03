package com.geekify.android.ui.search

import com.geekify.android.data.model.Card
import com.geekify.android.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRelevanceTest {
    @Test fun `exact title match ranks before weak server result`() {
        val tracks = listOf(
            Track("weak1234567", "A Song for You", "Other Artist"),
            Track("exact123456", "Malare", "Vijay Yesudas"),
            Track("artist12345", "Another Song", "Malare Band")
        )
        assertEquals("exact123456", SearchRelevance.rankTracks("Malare", tracks).first().videoId)
    }

    @Test fun `artist and accent-insensitive matching contribute to relevance`() {
        val cards = listOf(
            Card("other", "Random Album", "Various Artists"),
            Card("target", "Bésame Mucho", "Luis Miguel")
        )
        assertEquals("target", SearchRelevance.rankCards("Besame Mucho", cards).first().id)
    }

    @Test fun `unrelated rows are removed when relevant results exist`() {
        val tracks = listOf(
            Track("unrelated123", "Completely Different Song", "Unknown Artist"),
            Track("match1234567", "Blinding Lights", "The Weeknd"),
            Track("alsoOther123", "Another Track", "Someone Else")
        )
        assertEquals(listOf("match1234567"), SearchRelevance.rankTracks("Blinding Lights", tracks).map { it.videoId })
    }

    @Test fun `unknown metadata retains source results instead of returning empty`() {
        val tracks = listOf(
            Track("one12345678", "Unknown", "Unknown"),
            Track("two12345678", "Unknown", "Unknown")
        )
        assertEquals(2, SearchRelevance.rankTracks("Some Query", tracks).size)
    }

    @Test fun `equal scores preserve server order`() {
        val tracks = listOf(
            Track("one12345678", "Song", "Artist"),
            Track("two12345678", "Song", "Artist")
        )
        assertEquals(listOf("one12345678", "two12345678"), SearchRelevance.rankTracks("Song", tracks).map { it.videoId })
    }
}
