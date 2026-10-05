package com.geekify.android.domain.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommenderTest {

    @Test
    fun testSeedWeightsAndAffinity() {
        val liked = listOf(
            Recommender.Signal("v1", "Artist A", "Song 1"),
            Recommender.Signal("v2", "Artist B", "Song 2")
        )
        val recent = listOf(
            Recommender.Signal("v3", "Artist A", "Song 3")
        )

        val res = Recommender.seedWeights(liked, recent)
        // Artist A gets liked[0] weight + recent[0] weight
        // 1.6 + 1.0 = 2.6
        val artistAWeight = res.affinity["artist a"] ?: 0.0
        assertEquals(2.6, artistAWeight, 0.001)

        val seeds = Recommender.pickSeeds(res.weights, res.meta, 2)
        assertEquals(2, seeds.size)
        assertEquals("v1", seeds[0])
    }

    @Test
    fun testDiversifyCapsPerArtist() {
        val cands = listOf(
            Recommender.Candidate("v1", "T1", "artist a", "Artist A", emptyList(), "3:00", 180, false, "song", score = 10.0),
            Recommender.Candidate("v2", "T2", "artist a", "Artist A", emptyList(), "3:00", 180, false, "song", score = 9.0),
            Recommender.Candidate("v3", "T3", "artist a", "Artist A", emptyList(), "3:00", 180, false, "song", score = 8.0),
            Recommender.Candidate("v4", "T4", "artist a", "Artist A", emptyList(), "3:00", 180, false, "song", score = 7.0),
            Recommender.Candidate("v5", "T5", "artist b", "Artist B", emptyList(), "3:00", 180, false, "song", score = 5.0)
        )

        val diversified = Recommender.diversify(cands, 4, maxPerArtist = 2)
        val artistACount = diversified.count { it.artist == "artist a" }
        assertTrue(artistACount <= 2)
    }

    @Test
    fun frequentlyPlayedSongsOutweighASingleLike() {
        val res = Recommender.seedWeights(
            liked = listOf(Recommender.Signal("liked", "Artist A", "Liked")),
            recent = emptyList(),
            frequent = listOf(Recommender.PlayedSignal("often", "Artist B", "Often", plays = 8))
        )
        assertTrue((res.weights["often"] ?: 0.0) > (res.weights["liked"] ?: 0.0))
    }

    @Test
    fun variedSeedsKeepTheStrongestTwoAndStayWithinTheLimit() {
        val liked = (1..12).map { Recommender.Signal("v$it", "Artist $it", "Song $it") }
        val res = Recommender.seedWeights(liked, emptyList())
        repeat(20) { run ->
            val seeds = Recommender.pickSeedsVaried(res.weights, res.meta, 5, kotlin.random.Random(run))
            assertEquals(5, seeds.size)
            assertEquals(seeds.size, seeds.toSet().size)
            assertTrue("v1" in seeds && "v2" in seeds)
        }
    }

    private data class T(val id: String, val artist: String)

    @Test
    fun recentlyShownSongsAreDemotedAndOwnedSongsNeverReturn() {
        val related = mapOf("seed" to (1..12).map { T("t$it", "artist $it") })
        val mixes = Recommender.buildMixes(
            liked = listOf(Recommender.Signal("seed", "Seed Artist", "Seed")),
            recent = emptyList(),
            related = related,
            videoId = { it.id },
            artist = { it.artist },
            artistDisplay = { it.artist },
            title = { it.id },
            thumbs = { emptyList<Any>() },
            duration = { null },
            durationSeconds = { 180 },
            explicit = { false },
            type = { "song" },
            exclude = setOf("t12"),
            shown = setOf("t1"),
            seeds = listOf("seed"),
            random = kotlin.random.Random(0)
        ).first
        val main = mixes.first { it.id == "for-you" }
        assertTrue("t1" in main.videoIds)                 // demoted, not removed
        assertTrue(main.videoIds.first() != "t1")         // no longer at the top
        assertTrue("t12" !in main.videoIds)               // already owned: never recommended back
    }
}
