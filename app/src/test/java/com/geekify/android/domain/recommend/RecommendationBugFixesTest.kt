package com.geekify.android.domain.recommend

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class RecommendationBugFixesTest {

    @Test
    fun testScriptDetectionForNewIndicLanguages() {
        // Bengali (U+0980–09FF)
        assertEquals("Bengali", Recommender.languageOf("আমার সোনার বাংলা", "রবীন্দ্রনাথ"))
        // Kannada (U+0C80–0CFF)
        assertEquals("Kannada", Recommender.languageOf("ಕನ್ನಡ ಹಾಡು", "ಕಲಾವಿದ"))
        // Gujarati (U+0A80–0AFF)
        assertEquals("Gujarati", Recommender.languageOf("ગુજરાતી ગીત", "કલાકાર"))
        // Odia (U+0B00–0B7F)
        assertEquals("Odia", Recommender.languageOf("ଓଡ଼ିଆ ଗୀତ", "କଳାକାର"))
        // Han-only text stays unknown (null)
        assertNull(Recommender.languageOf("漢字歌", "歌手名"))
    }

    @Test
    fun testTimeOfDayKeywordWholeWordMatching() {
        // "White" must not match "hit" (EVENING_KEYWORDS) at 18:00
        val whiteMultiplier = Recommender.timeOfDayVibeMultiplier("White Flag", "Dido", 18)
        assertEquals(1.0, whiteMultiplier, 0.001)

        // "Crave" must not match "rave" (LATE_NIGHT_HEAVY_KEYWORDS) at 1:00
        val craveMultiplier = Recommender.timeOfDayVibeMultiplier("Crave You", "Flight Facilities", 1)
        assertEquals(1.0, craveMultiplier, 0.001)

        // Actual whole word "hit" should match in evening
        val hitMultiplier = Recommender.timeOfDayVibeMultiplier("Summer Hit", "Artist", 18)
        assertEquals(1.15, hitMultiplier, 0.001)
    }

    @Test
    fun testLanguageDiversityAppliesToLatinScriptTracks() {
        // Candidate list with 6 Latin tracks from different artists
        val latinCandidates = (1..6).map { i ->
            Recommender.Candidate(
                videoId = "latin_$i",
                title = "English Song $i",
                artist = "latin_artist_$i",
                artistDisplay = "Latin Artist $i",
                thumbnails = emptyList(),
                duration = "3:30",
                durationSeconds = 210,
                explicit = false,
                type = "song",
                score = 10.0
            )
        }

        // When languageDecay = 0.5, subsequent Latin tracks must be decayed if language diversity applies to Latin
        val diversified = Recommender.diversify(
            cands = latinCandidates,
            n = 5,
            maxPerArtist = 1,
            decay = 1.0,
            languageDecay = 0.5
        )

        // If Latin is treated as a language bucket, subsequent items must have received language decay
        // (10.0, 5.0, 2.5, 1.25, 0.625)
        assertEquals(5, diversified.size)
        // Check that diversification actually decayed subsequent tracks when competing against another category
        val mixedCandidates = listOf(
            Recommender.Candidate("lat_1", "Latin 1", "a1", "A1", emptyList(), null, null, false, "song", 10.0),
            Recommender.Candidate("lat_2", "Latin 2", "a2", "A2", emptyList(), null, null, false, "song", 9.0),
            Recommender.Candidate("lat_3", "Latin 3", "a3", "A3", emptyList(), null, null, false, "song", 8.0),
            // Indic candidate with score 7.0
            Recommender.Candidate("ml_1", "മലയാളം പാട്ട്", "m1", "M1", emptyList(), null, null, false, "song", 7.0)
        )
        val diversifiedMixed = Recommender.diversify(mixedCandidates, n = 3, languageDecay = 0.5)
        // In the bugged version, Latin tracks never receive languageDecay, so lat_1, lat_2, lat_3 are chosen (all > 7.0).
        // With Latin language diversity, lat_2 effective score = 9.0 * 0.5 = 4.5 < ml_1 (7.0), so ml_1 must be chosen before lat_2!
        assertTrue("Indic track should be picked over second Latin track when language decay applies to Latin",
            diversifiedMixed.map { it.videoId }.contains("ml_1"))
    }

    @Test
    fun testLanguagePreferenceNeverReducesMalayalamForHindiDominantListener() {
        val hindiLiked = (1..15).map {
            Recommender.Signal("hi_$it", "अरिजीत सिंह", "नमस्ते गीत $it")
        }
        val hindiRecent = (1..10).map {
            Recommender.Signal("hi_r_$it", "नेहा कक्कड़", "दिल $it")
        }

        val seedTracks = (1..10).map {
            Recommender.Candidate(
                videoId = "cand_hi_$it",
                title = "गीत $it",
                artist = "गायक $it",
                artistDisplay = "गायक $it",
                thumbnails = emptyList(),
                duration = "3:30",
                durationSeconds = 210,
                explicit = false,
                type = "song"
            )
        } + (1..10).map {
            Recommender.Candidate(
                videoId = "cand_ml_$it",
                title = "പാട്ട് $it",
                artist = "ഗായകൻ $it",
                artistDisplay = "ഗായകൻ $it",
                thumbnails = emptyList(),
                duration = "3:30",
                durationSeconds = 210,
                explicit = false,
                type = "song"
            )
        }

        val relatedMap = mapOf(hindiLiked.first().videoId to seedTracks)

        val (mixesWithoutPref, _) = Recommender.buildMixes(
            liked = hindiLiked,
            recent = hindiRecent,
            related = relatedMap,
            videoId = { it.videoId },
            artist = { it.artist },
            artistDisplay = { it.artistDisplay },
            title = { it.title },
            thumbs = { it.thumbnails },
            duration = { it.duration },
            durationSeconds = { it.durationSeconds },
            explicit = { it.explicit },
            type = { it.type },
            random = Random(123),
            preferredContentLanguage = null
        )

        val (mixesWithPref, _) = Recommender.buildMixes(
            liked = hindiLiked,
            recent = hindiRecent,
            related = relatedMap,
            videoId = { it.videoId },
            artist = { it.artist },
            artistDisplay = { it.artistDisplay },
            title = { it.title },
            thumbs = { it.thumbnails },
            duration = { it.duration },
            durationSeconds = { it.durationSeconds },
            explicit = { it.explicit },
            type = { it.type },
            random = Random(123),
            preferredContentLanguage = "Malayalam"
        )

        val mlCountWithout = mixesWithoutPref.firstOrNull { it.id == "for-you" }
            ?.videoIds.orEmpty().count { it.startsWith("cand_ml_") }
        val mlCountWith = mixesWithPref.firstOrNull { it.id == "for-you" }
            ?.videoIds.orEmpty().count { it.startsWith("cand_ml_") }

        assertTrue(
            "Setting Malayalam preference must not decrease Malayalam count (without=$mlCountWithout, with=$mlCountWith)",
            mlCountWith >= mlCountWithout
        )
    }
}
