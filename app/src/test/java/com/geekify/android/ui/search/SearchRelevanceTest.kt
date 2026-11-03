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

    @Test fun `cover and karaoke demoted unless query explicitly mentions them`() {
        val original = Track("orig1234567", "Starboy", "The Weeknd", durationSeconds = 230, album = "Starboy")
        val cover = Track("cov12345678", "Starboy (Cover)", "Random Singer", durationSeconds = 230)
        val karaoke = Track("kar12345678", "Starboy Karaoke", "Karaoke Pro", durationSeconds = 230)

        val rankedNormal = SearchRelevance.rankTracks("Starboy", listOf(cover, karaoke, original))
        assertEquals("orig1234567", rankedNormal.first().videoId)

        val rankedCover = SearchRelevance.rankTracks("Starboy cover", listOf(original, cover))
        assertEquals("cov12345678", rankedCover.first().videoId)
    }

    @Test fun `live and slowed versions demoted unless query asks for them`() {
        val original = Track("orig1234567", "After Hours", "The Weeknd", durationSeconds = 360, album = "After Hours")
        val live = Track("live1234567", "After Hours (Live in LA)", "The Weeknd", durationSeconds = 380)
        val slowed = Track("slow1234567", "After Hours (Slowed + Reverb)", "Vibe Edit", durationSeconds = 420)

        val ranked = SearchRelevance.rankTracks("After Hours", listOf(slowed, live, original))
        assertEquals("orig1234567", ranked.first().videoId)

        val liveRanked = SearchRelevance.rankTracks("After Hours Live", listOf(original, live))
        assertEquals("live1234567", liveRanked.first().videoId)
    }

    @Test fun `deduplicates identical song and artist keeping highest ranked version`() {
        val official = Track("off12345678", "Espresso", "Sabrina Carpenter", durationSeconds = 175, album = "Short n' Sweet")
        val repost = Track("rep12345678", "Espresso (Official Video)", "Sabrina Carpenter", durationSeconds = 175)

        val ranked = SearchRelevance.rankTracks("Espresso", listOf(repost, official))
        assertEquals(1, ranked.size)
        assertEquals("off12345678", ranked.first().videoId)
    }

    @Test fun `favorite artist boost elevates matching track`() {
        val songA = Track("songA123456", "Stay", "The Kid LAROI", durationSeconds = 140, album = "F*CK LOVE 3")
        val songB = Track("songB123456", "Stay", "Justin Bieber", durationSeconds = 140, album = "Justice")

        val ranked = SearchRelevance.rankTracks("Stay", listOf(songA, songB), favoriteArtists = setOf("Justin Bieber"))
        assertEquals("songB123456", ranked.first().videoId)
    }

    @Test fun `unicode and regional transliteration match accurately without stripping`() {
        val malare = Track("malare12345", "മലരേ (Malare)", "Vijay Yesudas", durationSeconds = 290, album = "Premam")
        val kesariya = Track("kes12345678", "Kesariya (From \"Brahmastra\")", "Arijit Singh", durationSeconds = 268, album = "Brahmastra")

        val rankedMalare = SearchRelevance.rankTracks("Malare", listOf(malare))
        assertEquals("malare12345", rankedMalare.first().videoId)

        val rankedRegional = SearchRelevance.rankTracks("മലരേ", listOf(malare))
        assertEquals("malare12345", rankedRegional.first().videoId)

        val rankedKesariya = SearchRelevance.rankTracks("kesariya arijit", listOf(kesariya))
        assertEquals("kes12345678", rankedKesariya.first().videoId)
    }
}
