package com.geekify.android

import com.geekify.android.domain.recommend.Recommender
import com.geekify.android.domain.recommend.engine.HybridRecommendationPipeline
import com.geekify.android.domain.recommend.engine.MusicGenre
import com.geekify.android.domain.recommend.engine.MusicMood
import com.geekify.android.lyrics.LyricsClient
import com.geekify.android.lyrics.LyricsLookupOutcome
import com.geekify.android.ui.home.resolveGridVisibleCount
import com.geekify.android.update.AppUpdateManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BugFixesAndFeatureIntegrationTest {

    // ---- 1. Lyrics Retrieval & BetterLyrics HTTP 401 Circuit Breaker ----

    @Test
    fun `betterLyrics 401 trips auth circuit breaker and falls back to lrclib`() = runBlocking {
        val betterLyricsHits = AtomicInteger(0)
        val lrclibHits = AtomicInteger(0)

        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                when {
                    url.contains("lyrics-api.boidu.dev") -> {
                        betterLyricsHits.incrementAndGet()
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(401)
                            .message("Unauthorized")
                            .body("{}".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    url.contains("lrclib.net/api/search") -> {
                        lrclibHits.incrementAndGet()
                        val json = """
                            [{
                              "trackName": "Test Song",
                              "artistName": "Test Artist",
                              "duration": 200.0,
                              "syncedLyrics": "[00:05.00]Hello world\n[00:10.00]Second line"
                            }]
                        """.trimIndent()
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(json.toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    else -> {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                }
            })
            .build()

        val client = LyricsClient(mockHttp)
        val first = client.fetchOutcome(
            title = "Test Song (Official Video)",
            artist = "Test Artist",
            durationSeconds = 200,
            videoId = null
        )
        assertTrue("Should fall back cleanly to LrcLib", first is LyricsLookupOutcome.Success)
        assertEquals("LrcLib", (first as LyricsLookupOutcome.Success).lyrics.provider)
        assertEquals("BetterLyrics must be called only once before tripping auth circuit breaker", 1, betterLyricsHits.get())
        assertTrue("BetterLyrics should now be auth-blocked", client.isProviderAuthBlocked("BetterLyrics"))

        // Second lookup must NOT hit BetterLyrics again during cooldown
        client.fetchOutcome(title = "Another Song", artist = "Another Artist", durationSeconds = 180, videoId = null)
        assertEquals("BetterLyrics must not be retried while auth-blocked", 1, betterLyricsHits.get())
        assertTrue(lrclibHits.get() >= 2)
    }

    @Test
    fun `betterLyrics 401 with other providers returning 404 yields NoMatch not connection error`() = runBlocking {
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                val code = if (url.contains("lyrics-api.boidu.dev")) 401 else 404
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 401) "Unauthorized" else "Not Found")
                    .body("".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val client = LyricsClient(mockHttp)
        val outcome = client.fetchOutcome(
            title = "Instrumental Track Without Lyrics",
            artist = "Unknown Composer",
            durationSeconds = 190,
            videoId = null
        )
        assertTrue("Expected NoMatch when fallback providers checked and returned 404, got $outcome", outcome is LyricsLookupOutcome.NoMatch)
    }

    @Test
    fun `auth circuit breaker expires after cooldown`() {
        var clock = 1_000L
        val client = LyricsClient(OkHttpClient(), nowMsProvider = { clock })
        client.markProviderAuthBlocked("BetterLyrics")
        assertTrue(client.isProviderAuthBlocked("BetterLyrics"))

        clock += LyricsClient.AUTH_FAILURE_COOLDOWN_MS + 1L
        assertFalse(client.isProviderAuthBlocked("BetterLyrics"))
    }

    // ---- 2. Curated Recommendation Descriptions ----

    @Test
    fun `resolveForYouSubtitle matches actual mix languages accurately`() {
        assertEquals(
            "More Malayalam music, based on what you play",
            Recommender.resolveForYouSubtitle(List(10) { "Malayalam" })
        )
        assertEquals(
            "More English music, based on what you play",
            Recommender.resolveForYouSubtitle(List(9) { "English" } + listOf(null))
        )
        assertEquals(
            "More music picked for you",
            Recommender.resolveForYouSubtitle(List(5) { "Malayalam" } + List(5) { "English" })
        )
        assertEquals(
            "More music picked for you",
            Recommender.resolveForYouSubtitle(List(8) { null })
        )
        // Mixed where Malayalam is only 30% must NEVER claim Malayalam-specific subtitle
        assertEquals(
            "More music picked for you",
            Recommender.resolveForYouSubtitle(List(3) { "Malayalam" } + List(7) { "English" })
        )
    }

    // ---- 3. Home & See All Grid Slot Filling ----

    @Test
    fun `resolveGridVisibleCount eliminates avoidable empty slots in 2 and 3 column grids`() {
        // 2-column grid
        assertEquals(6, resolveGridVisibleCount(totalAvailable = 10, requestedCount = 5, columns = 2))
        assertEquals(6, resolveGridVisibleCount(totalAvailable = 10, requestedCount = 6, columns = 2))
        // 3-column grid
        assertEquals(6, resolveGridVisibleCount(totalAvailable = 10, requestedCount = 5, columns = 3))
        assertEquals(21, resolveGridVisibleCount(totalAvailable = 25, requestedCount = 20, columns = 3))
        // Genuine end-of-collection edge case: do NOT fabricate placeholders beyond totalAvailable
        assertEquals(6, resolveGridVisibleCount(totalAvailable = 7, requestedCount = 6, columns = 2))
        assertEquals(7, resolveGridVisibleCount(totalAvailable = 7, requestedCount = 8, columns = 2))
        assertEquals(7, resolveGridVisibleCount(totalAvailable = 7, requestedCount = 7, columns = 3))
        assertEquals(0, resolveGridVisibleCount(totalAvailable = 0, requestedCount = 6, columns = 2))
    }

    // ---- 6. Update Release History Pagination ----

    @Test
    fun `release pagination uses page size above 8`() {
        assertTrue(
            "RELEASES_PAGE_SIZE must exceed the old 8-release limit",
            AppUpdateManager.RELEASES_PAGE_SIZE >= 30
        )
    }

    // ---- 7. More Like This & Less Like This Ranking Delta ----

    @Test
    fun `computeFeedbackScoreDelta boosts positive tracks and artists and penalizes negative ones selectively`() {
        val positiveBoost = HybridRecommendationPipeline.computeFeedbackScoreDelta(
            candidateVideoId = "cand_1",
            candidateArtistNormalized = "the weeknd",
            candidatePrimaryGenre = MusicGenre.POP,
            candidatePrimaryMood = MusicMood.ENERGETIC,
            moreLikeTrackIds = setOf("seed_1"),
            lessLikeTrackIds = setOf("bad_1"),
            moreLikeArtists = setOf("the weeknd"),
            lessLikeArtists = setOf("disliked artist"),
            positiveGenres = setOf(MusicGenre.POP),
            positiveMoods = setOf(MusicMood.ENERGETIC)
        )
        assertTrue("Positive artist + genre + mood must yield strong boost", positiveBoost >= 0.45)

        val negativeTrackPenalty = HybridRecommendationPipeline.computeFeedbackScoreDelta(
            candidateVideoId = "bad_1",
            candidateArtistNormalized = "disliked artist",
            candidatePrimaryGenre = MusicGenre.ROCK,
            candidatePrimaryMood = MusicMood.CALM,
            moreLikeTrackIds = emptySet(),
            lessLikeTrackIds = setOf("bad_1"),
            moreLikeArtists = emptySet(),
            lessLikeArtists = setOf("disliked artist"),
            positiveGenres = emptySet(),
            positiveMoods = emptySet()
        )
        assertTrue("Less Like This track must be strongly demoted", negativeTrackPenalty <= -0.85)

        // Unrelated artist in same language/genre must NOT be penalized by Less Like This
        val unrelatedTrackDelta = HybridRecommendationPipeline.computeFeedbackScoreDelta(
            candidateVideoId = "other_track",
            candidateArtistNormalized = "unrelated artist",
            candidatePrimaryGenre = MusicGenre.ROCK,
            candidatePrimaryMood = MusicMood.CALM,
            moreLikeTrackIds = emptySet(),
            lessLikeTrackIds = setOf("bad_1"),
            moreLikeArtists = emptySet(),
            lessLikeArtists = setOf("disliked artist"),
            positiveGenres = emptySet(),
            positiveMoods = emptySet()
        )
        assertEquals(0.0, unrelatedTrackDelta, 0.0001)
    }
}
