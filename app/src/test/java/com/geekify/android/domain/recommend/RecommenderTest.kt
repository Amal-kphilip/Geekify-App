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
}
