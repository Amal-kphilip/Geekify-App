package com.geekify.android.domain.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private data class T(val id: String, val artist: String, val title: String = "Song")

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
            title = { it.title },
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
        assertTrue("t1" in main.videoIds)
        assertTrue(main.videoIds.first() != "t1")
        assertTrue("t12" !in main.videoIds)
    }

    @Test
    fun MalayalamDominantListenerGetsMalayalamAffinity() {
        val profile = Recommender.languageAffinity(
            liked = List(8) { Recommender.Signal("m$it", "കലാകാരൻ", "പാട്ട് $it") },
            recent = listOf(Recommender.Signal("m9", "കലാകാരൻ", "പാട്ട് 9")),
            frequent = listOf(Recommender.PlayedSignal("m10", "കലാകാരൻ", "പാട്ട് 10", plays = 8))
        )
        assertTrue((profile.affinity["Malayalam"] ?: 0.0) > 0.95)
    }

    @Test
    fun HindiDominantListenerGetsHindiAffinity() {
        val profile = Recommender.languageAffinity(
            liked = List(8) { Recommender.Signal("h$it", "कलाकार", "गाना $it") },
            recent = listOf(Recommender.Signal("h9", "कलाकार", "गाना 9")),
            frequent = listOf(Recommender.PlayedSignal("h10", "कलाकार", "गाना 10", plays = 8))
        )
        assertTrue((profile.affinity["Hindi"] ?: 0.0) > 0.95)
    }

    @Test
    fun TamilDominantListenerGetsTamilAffinity() {
        val profile = Recommender.languageAffinity(
            liked = List(8) { Recommender.Signal("t$it", "கலைஞர்", "பாட்டு $it") },
            recent = emptyList()
        )
        assertTrue((profile.affinity["Tamil"] ?: 0.0) > 0.95)
    }

    @Test
    fun MultilingualListenerKeepsMultipleLanguagesEligible() {
        val profile = Recommender.languageAffinity(
            liked = listOf(
                Recommender.Signal("m1", "കലാകാരൻ", "പാട്ട്"),
                Recommender.Signal("h1", "कलाकार", "गाना"),
                Recommender.Signal("t1", "கலைஞர்", "பாட்டு"),
                Recommender.Signal("m2", "കലാകാരൻ", "പാട്ട് 2"),
                Recommender.Signal("h2", "कलाकार", "गाना 2")
            ),
            recent = emptyList()
        )
        assertTrue((profile.affinity["Malayalam"] ?: 0.0) > 0.0)
        assertTrue((profile.affinity["Hindi"] ?: 0.0) > 0.0)
        assertTrue((profile.affinity["Tamil"] ?: 0.0) > 0.0)
    }

    @Test
    fun FiftyThirtyTwentyProfilePreservesRelativeLanguageOrder() {
        val liked = buildList {
            repeat(5) { add(Recommender.Signal("m$it", "കലാകാരൻ", "പാട്ട് $it")) }
            repeat(3) { add(Recommender.Signal("h$it", "कलाकार", "गाना $it")) }
            repeat(2) { add(Recommender.Signal("t$it", "கலைஞர்", "பாட்டு $it")) }
        }
        val profile = Recommender.languageAffinity(liked, emptyList())
        assertTrue((profile.affinity["Malayalam"] ?: 0.0) > (profile.affinity["Hindi"] ?: 0.0))
        assertTrue((profile.affinity["Hindi"] ?: 0.0) > (profile.affinity["Tamil"] ?: 0.0))
    }

    @Test
    fun RecentHindiTrendCanTemporarilyOutrankLongTermMalayalam() {
        val profile = Recommender.languageAffinity(
            liked = List(8) { Recommender.Signal("m$it", "കലാകാരൻ", "പാട്ട് $it") },
            recent = List(8) { Recommender.Signal("h$it", "कलाकार", "गाना $it") } + List(2) { Recommender.Signal("mr$it", "കലാകാരൻ", "പാട്ട് r$it") },
            frequent = listOf(
                Recommender.PlayedSignal("m9", "കലാകാരൻ", "പാട്ട് 9", plays = 10),
                Recommender.PlayedSignal("h9", "कलाकार", "गाना 9", plays = 2)
            )
        )
        assertTrue((profile.longTerm["Malayalam"] ?: 0.0) > (profile.longTerm["Hindi"] ?: 0.0))
        assertTrue((profile.recent["Hindi"] ?: 0.0) > (profile.recent["Malayalam"] ?: 0.0))
        assertTrue((profile.affinity["Hindi"] ?: 0.0) > (profile.affinity["Malayalam"] ?: 0.0))
    }

    @Test
    fun SessionLanguageUsesNewestHistorySlice() {
        val recent = buildList {
            repeat(6) { add(Recommender.Signal("h$it", "कलाकार", "गाना $it")) }
            repeat(4) { add(Recommender.Signal("m$it", "കലാകാരൻ", "പാട്ട് $it")) }
            repeat(10) { add(Recommender.Signal("t$it", "கலைஞர்", "பாட்டு $it")) }
        }
        val profile = Recommender.languageAffinity(emptyList(), recent)
        assertTrue((profile.session["Hindi"] ?: 0.0) > (profile.session["Tamil"] ?: 0.0))
        assertTrue((profile.session["Hindi"] ?: 0.0) > (profile.session["Malayalam"] ?: 0.0))
    }

    @Test
    fun RepeatedPlaysIncreaseLanguageAffinity() {
        val baseline = Recommender.languageAffinity(
            liked = emptyList(),
            recent = emptyList(),
            frequent = listOf(
                Recommender.PlayedSignal("m", "കലാകാരൻ", "പാട്ട്", plays = 2),
                Recommender.PlayedSignal("h", "कलाकार", "गाना", plays = 2)
            )
        )
        val repeated = Recommender.languageAffinity(
            liked = emptyList(),
            recent = emptyList(),
            frequent = listOf(
                Recommender.PlayedSignal("m", "കലാകാരൻ", "പാട്ട്", plays = 20),
                Recommender.PlayedSignal("h", "कलाकार", "गाना", plays = 2)
            )
        )
        assertTrue((repeated.affinity["Malayalam"] ?: 0.0) > (baseline.affinity["Malayalam"] ?: 0.0))
    }

    @Test
    fun LikesAffectLanguageAffinity() {
        val noLikes = Recommender.languageAffinity(
            liked = emptyList(),
            recent = listOf(Recommender.Signal("h", "कलाकार", "गाना"))
        )
        val liked = Recommender.languageAffinity(
            liked = listOf(Recommender.Signal("m", "കലാകാരൻ", "പാട്ട്")),
            recent = listOf(Recommender.Signal("h", "कलाकार", "गाना"))
        )
        assertTrue((liked.longTerm["Malayalam"] ?: 0.0) > (liked.longTerm["Hindi"] ?: 0.0))
        assertTrue((liked.affinity["Malayalam"] ?: 0.0) > (noLikes.affinity["Malayalam"] ?: 0.0))
    }

    @Test
    fun PlaylistAdditionsAffectLanguageAffinity() {
        val profile = Recommender.languageAffinity(
            liked = emptyList(),
            recent = listOf(Recommender.Signal("h", "कलाकार", "गाना")),
            playlist = List(3) { Recommender.Signal("m$it", "കലാകാരൻ", "പാട്ട് $it") }
        )
        assertTrue((profile.longTerm["Malayalam"] ?: 0.0) > (profile.longTerm["Hindi"] ?: 0.0))
    }

    @Test
    fun UnknownLanguageIsNotAssumedToBeEnglish() {
        assertNull(Recommender.languageOf("Blinding Lights", "The Weeknd"))
        val profile = Recommender.languageAffinity(
            liked = listOf(Recommender.Signal("x", "The Weeknd", "Blinding Lights")),
            recent = emptyList()
        )
        assertTrue(profile.affinity.isEmpty())
    }

    @Test
    fun LanguagePreferenceBoostsSeedWeightWithoutHardFiltering() {
        val base = Recommender.seedWeights(
            liked = listOf(
                Recommender.Signal("m", "കലാകാരൻ", "പാട്ട്"),
                Recommender.Signal("h", "कलाकार", "गाना")
            ),
            recent = emptyList()
        )
        val profile = Recommender.languageAffinity(
            liked = listOf(
                Recommender.Signal("m", "കലാകാരൻ", "പാട്ട്"),
                Recommender.Signal("m2", "കലാകാരൻ", "പാട്ട് 2"),
                Recommender.Signal("m3", "കലാകാരൻ", "പാട്ട് 3")
            ),
            recent = emptyList()
        )
        val boosted = Recommender.seedWeights(
            liked = listOf(
                Recommender.Signal("m", "കലാകാരൻ", "പാട്ട്"),
                Recommender.Signal("h", "कलाकार", "गाना")
            ),
            recent = emptyList(),
            languageAffinity = profile.affinity
        )
        assertTrue((boosted.weights["m"] ?: 0.0) > (base.weights["m"] ?: 0.0))
        assertTrue((boosted.weights["h"] ?: 0.0) > 0.0)
    }

    @Test
    fun LanguageDiversityIsSoftNotHard() {
        val candidates = listOf(
            Recommender.Candidate("m1", "പാട്ട് 1", "m1", "m1", emptyList(), null, null, false, "song", score = 10.0),
            Recommender.Candidate("m2", "പാട്ട് 2", "m2", "m2", emptyList(), null, null, false, "song", score = 9.9),
            Recommender.Candidate("h1", "गाना 1", "h1", "h1", emptyList(), null, null, false, "song", score = 8.5)
        )
        val result = Recommender.diversify(candidates, 3, languageDecay = 0.92)
        assertEquals(3, result.size)
        assertTrue(result.any { it.videoId == "m1" })
        assertTrue(result.any { it.videoId == "h1" })
    }

    @Test
    fun StrongArtistAffinityRemainsPresent() {
        val result = Recommender.seedWeights(
            liked = listOf(
                Recommender.Signal("a1", "Hindi Star", "गाना 1"),
                Recommender.Signal("a2", "Hindi Star", "गाना 2"),
                Recommender.Signal("a3", "Hindi Star", "गाना 3")
            ),
            recent = listOf(Recommender.Signal("m1", "Malayalam Artist", "പാട്ട്")),
            frequent = listOf(Recommender.PlayedSignal("m2", "Malayalam Artist", "പാട്ട് 2", plays = 20))
        )
        assertTrue((result.affinity["hindi star"] ?: 0.0) > 0.0)
        assertTrue((result.affinity["malayalam artist"] ?: 0.0) > 0.0)
    }

    @Test
    fun ShownPenaltyStillAppliesAfterLanguageScoring() {
        val related = mapOf("seed" to (1..10).map { i ->
            T("t$i", if (i % 2 == 0) "കലാകാരൻ" else "कलाकार", if (i % 2 == 0) "പാട്ട് $i" else "गाना $i")
        })
        val mixes = Recommender.buildMixes(
            liked = listOf(Recommender.Signal("seed", "Seed Artist", "Seed")),
            recent = emptyList(),
            related = related,
            videoId = { it.id },
            artist = { it.artist },
            artistDisplay = { it.artist },
            title = { it.title },
            thumbs = { emptyList<Any>() },
            duration = { null },
            durationSeconds = { 180 },
            explicit = { false },
            type = { "song" },
            shown = setOf("t2"),
            seeds = listOf("seed"),
            random = kotlin.random.Random(1)
        ).first.firstOrNull { it.id == "for-you" }
        assertTrue(mixes == null || mixes.videoIds.indexOf("t2") > 0)
    }

    @Test
    fun EmptyHistoryProducesEmptyLanguageProfile() {
        val profile = Recommender.languageAffinity(emptyList(), emptyList(), emptyList(), emptyList())
        assertTrue(profile.affinity.isEmpty())
        assertTrue(profile.longTerm.isEmpty())
        assertTrue(profile.recent.isEmpty())
        assertTrue(profile.session.isEmpty())
    }

    @Test
    fun ExistingSeedAndArtistLogicStillWorks() {
        val liked = listOf(
            Recommender.Signal("v1", "Artist A", "Song 1"),
            Recommender.Signal("v2", "Artist A", "Song 2"),
            Recommender.Signal("v3", "Artist B", "Song 3")
        )
        val result = Recommender.seedWeights(liked, emptyList())
        val seeds = Recommender.pickSeeds(result.weights, result.meta, 3)
        assertEquals(3, seeds.size)
        assertTrue(seeds.take(3).contains("v1"))
        assertTrue((result.affinity["artist a"] ?: 0.0) > (result.affinity["artist b"] ?: 0.0))
    }
}
