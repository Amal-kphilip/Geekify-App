package com.geekify.android.domain.listening

import com.geekify.android.data.local.ListeningSessionEntity
import com.geekify.android.data.model.ArtistRef
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningStatsCalculatorTest {
    private val now = Instant.parse("2026-10-13T12:00:00Z").toEpochMilli()

    @Test
    fun `time totals ignore sessions shorter than ten seconds`() {
        val sessions = listOf(
            session("long", "track-a", "A song", "Artist A", now - DAY, 120_000L),
            session("tap", "track-b", "B song", "Artist B", now - DAY, 9_999L),
            session("old", "track-c", "C song", "Artist C", now - 40L * DAY, 300_000L)
        )

        val stats = ListeningStatsCalculator.calculate(sessions, now, ZoneOffset.UTC)

        assertEquals(120_000L, stats.last7DaysMs)
        assertEquals(120_000L, stats.last30DaysMs)
        assertEquals("track-a", stats.topTracks.single().videoId)
        assertEquals(7, stats.dailyListening.size)
        assertEquals(120_000L, stats.dailyListening.sumOf { it.listenedMs })
    }

    @Test
    fun `streak requires ten minutes per calendar day`() {
        val sessions = listOf(
            session("d0", "a", "A", "Artist", now, 600_000L),
            session("d1", "b", "B", "Artist", now - DAY, 400_000L),
            session("d1-more", "c", "C", "Artist", now - DAY + 1_000L, 200_000L),
            session("d2", "d", "D", "Artist", now - 2L * DAY, 600_000L),
            session("d3-short", "e", "E", "Artist", now - 3L * DAY, 599_999L)
        )

        val stats = ListeningStatsCalculator.calculate(sessions, now, ZoneOffset.UTC)

        assertEquals(3, stats.currentStreakDays)
        assertEquals(3, stats.longestStreakDays)
    }

    @Test
    fun `artist is discovered only when first meaningful listen is recent`() {
        val sessions = listOf(
            session("old-listen", "old-song", "Old song", "Known Artist", now - 40L * DAY, 120_000L),
            session("recent-known", "old-song", "Old song", "Known Artist", now - 2L * DAY, 120_000L),
            session("new-listen", "new-song", "New song", "New Artist", now - 3L * DAY, 90_000L)
        )

        val stats = ListeningStatsCalculator.calculate(sessions, now, ZoneOffset.UTC)

        assertEquals(listOf("New Artist"), stats.recentlyDiscoveredArtists.map { it.name })
    }

    @Test
    fun `language classifier identifies distinct scripts`() {
        assertEquals("Malayalam", ListeningLanguageClassifier.classify("മലയാളം പാട്ട്", "കലാകാരൻ"))
        assertEquals("Tamil", ListeningLanguageClassifier.classify("தமிழ் பாடல்", "கலைஞர்"))
        assertEquals("Devanagari (Hindi/Marathi etc.)", ListeningLanguageClassifier.classify("दिल से", "कलाकार"))
    }

    @Test
    fun `language classifier recognizes explicit title language tags`() {
        assertEquals("Malayalam", ListeningLanguageClassifier.classify("A Song [Malayalam]", "Artist"))
        assertEquals("Tamil", ListeningLanguageClassifier.classify("Track - Tamil", "Artist"))
    }

    @Test
    fun `language classifier recognizes high confidence romanized Indian titles`() {
        assertEquals("Malayalam", ListeningLanguageClassifier.classify("Pavizha Mazha", "Jonita Gandhi"))
        assertEquals("Tamil", ListeningLanguageClassifier.classify("Arabic Kuthu", "Jonita Gandhi"))
        assertEquals("Hindi", ListeningLanguageClassifier.classify("Tum Hi Ho", "Arijit Singh"))
        assertEquals("Telugu", ListeningLanguageClassifier.classify("Nuvvu Nenu Prema", "Artist"))
    }

    @Test
    fun `language classifier leaves ambiguous Latin titles unclassified`() {
        assertEquals("Latin script (language unclear)", ListeningLanguageClassifier.classify("Canción", "Artista"))
        assertEquals("Latin script (language unclear)", ListeningLanguageClassifier.classify("Summer Dreams", "The Band"))
    }



    @Test
    fun `language metadata parser maps ISO language codes`() {
        assertEquals(
            "Tamil",
            LyricsLanguageResponseParser.parse("""{"metadata":{"language":"ta"}}""")
        )
        assertEquals(
            "Malayalam",
            LyricsLanguageResponseParser.parse("""{"language":"ml-IN"}""")
        )
    }

    @Test
    fun `language metadata parser falls back to native lyrics script`() {
        assertEquals(
            "Malayalam",
            LyricsLanguageResponseParser.parse(
                """{"lyrics":"മഴത്തുള്ളികൾ പൊഴിഞ്ഞീടുമീ നാടൻ വഴി"}"""
            )
        )
    }

    @Test
    fun `language metadata parser ignores unavailable or malformed metadata`() {
        assertEquals(null, LyricsLanguageResponseParser.parse("""{"metadata":{"title":"A song"}}"""))
        assertEquals(null, LyricsLanguageResponseParser.parse("not json"))
    }

    @Test
    fun `single brief session does not create listening data`() {
        val stats = ListeningStatsCalculator.calculate(
            listOf(session("brief", "tap", "Brief", "Artist", now, 1_000L)),
            now,
            ZoneOffset.UTC
        )
        assertEquals(0L, stats.last30DaysMs)
        assertTrue(stats.topTracks.isEmpty())
        assertFalse(stats.hasData)
    }

    private fun session(
        id: String,
        videoId: String,
        title: String,
        artist: String,
        startedAt: Long,
        listenedMs: Long,
        artists: List<ArtistRef> = emptyList(),
        completed: Boolean = false,
        skipped: Boolean = false
    ) = ListeningSessionEntity(
        sessionId = id,
        videoId = videoId,
        title = title,
        artist = artist,
        artists = artists,
        listenedMs = listenedMs,
        durationMs = 240_000L,
        startedAt = startedAt,
        updatedAt = startedAt + listenedMs,
        completed = completed,
        skipped = skipped
    )

    private companion object { const val DAY = 24L * 60 * 60_000L }
}
