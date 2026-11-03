package com.geekify.android.domain.recommend

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class RecommendationPhase4Test {

    @Test
    fun testExponentialTimeDecayHalfLife() {
        val now = 1_700_000_000_000L
        val oneDayMs = 24 * 60 * 60 * 1000L

        // Two likes: one recent (1 day ago), one 120 days ago (two 60-day half-lives)
        val recentLike = Recommender.Signal("like_new", "Artist A", "New Track", timestampMs = now - oneDayMs)
        val oldLike = Recommender.Signal("like_old", "Artist B", "Old Track", timestampMs = now - (120 * oneDayMs))

        val result = Recommender.seedWeights(
            liked = listOf(recentLike, oldLike),
            recent = emptyList(),
            nowMs = now
        )

        val wNew = result.weights["like_new"] ?: 0.0
        val wOld = result.weights["like_old"] ?: 0.0

        assertTrue("New like weight ($wNew) must be significantly higher than 120-day-old like ($wOld)", wNew > wOld * 3.5)
        // 120 days with 60-day half-life should be around 0.25 * base
        assertEquals(0.25, (wOld / Recommender.LIKE_WEIGHT), 0.05)
    }

    @Test
    fun testMaximalMarginalRelevanceDiversification() {
        // Candidate 1 and Candidate 2 share artist "SameArtist"
        // Candidate 3 is by "OtherArtist"
        val cand1 = Recommender.Candidate("c1", "Track 1", "sameartist", "SameArtist", emptyList(), null, null, false, "song", score = 10.0)
        val cand2 = Recommender.Candidate("c2", "Track 2", "sameartist", "SameArtist", emptyList(), null, null, false, "song", score = 9.5)
        val cand3 = Recommender.Candidate("c3", "Track 3", "otherartist", "OtherArtist", emptyList(), null, null, false, "song", score = 8.5)

        val mmrSelected = Recommender.diversifyMMR(
            cands = listOf(cand1, cand2, cand3),
            n = 2,
            lambda = 0.7
        )

        assertEquals("First selected should be highest score c1", "c1", mmrSelected[0].videoId)
        // Under MMR, cand2 similarity to c1 is 1.0 (same artist).
        // Cand2 MMR score: 0.7 * (9.5/10.0) - 0.3 * 1.0 = 0.665 - 0.30 = 0.365
        // Cand3 MMR score: 0.7 * (8.5/10.0) - 0.3 * 0.0 = 0.595 - 0.00 = 0.595
        // So cand3 must be picked before cand2!
        assertEquals("Second selected under MMR must be cand3 from different artist", "c3", mmrSelected[1].videoId)
    }

    @Test
    fun testExplorationSlotInMadeForYou() {
        // 5 familiar artists in history
        val liked = (1..5).map { Recommender.Signal("seed_$it", "fam_artist_$it", "Track $it") }

        // Candidates: 15 tracks from 5 familiar artists (3 each) + 15 tracks from 5 unheard artists (3 each)
        val candidateList = buildList {
            for (a in 1..5) {
                for (t in 1..3) {
                    add(Recommender.Candidate("cand_fam_${a}_$t", "FamTrack", "fam_artist_$a", "Fam Artist $a", emptyList(), null, 200, false, "song", score = 10.0))
                }
            }
            for (a in 1..5) {
                for (t in 1..3) {
                    add(Recommender.Candidate("cand_unh_${a}_$t", "UnhTrack", "unh_artist_$a", "Unh Artist $a", emptyList(), null, 200, false, "song", score = 8.0))
                }
            }
        }
        val related = mapOf("seed_1" to candidateList)

        val (mixes, _) = Recommender.buildMixes(
            liked = liked,
            recent = emptyList(),
            related = related,
            videoId = { it.videoId },
            artist = { it.artist },
            artistDisplay = { it.artistDisplay },
            title = { it.title },
            thumbs = { it.thumbnails },
            duration = { it.duration },
            durationSeconds = { it.durationSeconds },
            explicit = { it.explicit },
            type = { it.type },
            random = Random(42)
        )

        val forYou = mixes.first { it.id == "for-you" }
        // In "Made for you", position 7 (the 8th track, 0-indexed 7) should be reserved for an exploration candidate
        assertTrue(forYou.videoIds.size >= 8)
        val slot7Vid = forYou.videoIds[7]
        assertTrue("Track at exploration slot index 7 must be from unheard artist (got $slot7Vid)",
            slot7Vid.startsWith("cand_unh_"))
        assertEquals("explore", forYou.sources[slot7Vid])
    }

    @Test
    fun testShownCountGraduatedDemotion() {
        val related = mapOf(
            "seed" to (1..10).map { i ->
                Recommender.Candidate("t$i", "Track $i", "artist_$i", "Artist $i", emptyList(), null, 180, false, "song")
            }
        )

        // t1 shown 4 times with zero plays
        val shownCounts = mapOf("t1" to 4)

        val (mixes, _) = Recommender.buildMixes(
            liked = listOf(Recommender.Signal("seed", "Seed Artist", "Seed")),
            recent = emptyList(),
            related = related,
            videoId = { it.videoId },
            artist = { it.artist },
            artistDisplay = { it.artistDisplay },
            title = { it.title },
            thumbs = { it.thumbnails },
            duration = { it.duration },
            durationSeconds = { it.durationSeconds },
            explicit = { it.explicit },
            type = { it.type },
            shownCounts = shownCounts,
            random = Random(1)
        )

        val forYou = mixes.first { it.id == "for-you" }
        // t1 was shown 4 times with zero plays, so demotion = 1.0 / (1 + 4*0.5) * 0.2 = 0.0667
        // It must NOT be at the top of the mix, but should still be in the candidate list
        assertNotEquals("t1", forYou.videoIds.first())
        assertTrue("t1 should still be present in recommendations even when demoted", forYou.videoIds.contains("t1"))
    }
}
