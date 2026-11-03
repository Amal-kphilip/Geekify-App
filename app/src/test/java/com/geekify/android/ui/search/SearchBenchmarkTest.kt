package com.geekify.android.ui.search

import com.geekify.android.data.model.Card
import com.geekify.android.data.model.Track
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureNanoTime

class SearchBenchmarkTest {

    data class BenchmarkCase(
        val category: String,
        val query: String,
        val expectedTargetId: String,
        val candidates: List<Track>,
        val cardCandidates: List<Card> = emptyList()
    )

    private fun build100QueriesSuite(): List<BenchmarkCase> {
        val cases = mutableListOf<BenchmarkCase>()

        // 1. Typos (15 queries)
        val typos = listOf(
            Triple("blnding lghts", "t_blinding", "Blinding Lights"),
            Triple("shp of you", "t_shape", "Shape of You"),
            Triple("starboy the wekend", "t_starboy", "Starboy"),
            Triple("bad guy bilie eilish", "t_badguy", "Bad Guy"),
            Triple("levtating dua lipa", "t_levitating", "Levitating"),
            Triple("save your tears the weknd", "t_saveyourtears", "Save Your Tears"),
            Triple("dont start nw", "t_dontstartnow", "Don't Start Now"),
            Triple("as it ws harry styles", "t_asitwas", "As It Was"),
            Triple("stay justin bebr", "t_stay", "Stay"),
            Triple("heat wvs glass animals", "t_heatwaves", "Heat Waves"),
            Triple("ghost justin bebr", "t_ghost", "Ghost"),
            Triple("peches justin biebr", "t_peaches", "Peaches"),
            Triple("drivers lisence", "t_driverslicense", "Drivers License"),
            Triple("deja vu olivia rodrego", "t_dejavu", "Deja Vu"),
            Triple("good 4 u olivia", "t_good4u", "Good 4 U")
        )
        typos.forEach { (query, id, title) ->
            cases.add(
                BenchmarkCase(
                    category = "Typo",
                    query = query,
                    expectedTargetId = id,
                    candidates = listOf(
                        Track("noise_${id}_cov", "$title (Fan Cover)", "Random Artist", durationSeconds = 180),
                        Track("noise_${id}_slow", "$title (Slowed + Reverb)", "Vibe Beats", durationSeconds = 240),
                        Track(id, title, "Official Artist", album = "Official Album", durationSeconds = 210),
                        Track("noise_${id}_kar", "$title Karaoke", "Karaoke Pro", durationSeconds = 210)
                    )
                )
            )
        }

        // 2. Artist only (15 queries)
        val artists = listOf(
            "Taylor Swift", "The Weeknd", "Drake", "Ed Sheeran", "Billie Eilish",
            "Dua Lipa", "Arijit Singh", "Coldplay", "Eminem", "Post Malone",
            "BTS", "Kendrick Lamar", "Olivia Rodrigo", "Harry Styles", "Bruno Mars"
        )
        artists.forEachIndexed { idx, name ->
            val id = "art_$idx"
            cases.add(
                BenchmarkCase(
                    category = "Artist Only",
                    query = name,
                    expectedTargetId = id,
                    candidates = emptyList(),
                    cardCandidates = listOf(
                        Card("noise_playlist_$idx", "Best of $name 2024", "Curated Playlist", type = "playlist"),
                        Card("noise_tribute_$idx", "Tribute to $name", "Cover Band", type = "artist"),
                        Card(id, name, "Artist", type = "artist")
                    )
                )
            )
        }

        // 3. Album only (15 queries)
        val albums = listOf(
            Pair("After Hours", "The Weeknd"),
            Pair("Midnights", "Taylor Swift"),
            Pair("Divide", "Ed Sheeran"),
            Pair("Happier Than Ever", "Billie Eilish"),
            Pair("Future Nostalgia", "Dua Lipa"),
            Pair("Starboy", "The Weeknd"),
            Pair("Justice", "Justin Bieber"),
            Pair("Sour", "Olivia Rodrigo"),
            Pair("Fine Line", "Harry Styles"),
            Pair("Scorpion", "Drake"),
            Pair("Doo-Wops & Hooligans", "Bruno Mars"),
            Pair("Thriller", "Michael Jackson"),
            Pair("Abbey Road", "The Beatles"),
            Pair("Rumours", "Fleetwood Mac"),
            Pair("DAMN.", "Kendrick Lamar")
        )
        albums.forEachIndexed { idx, (albName, artName) ->
            val id = "alb_$idx"
            cases.add(
                BenchmarkCase(
                    category = "Album Only",
                    query = albName,
                    expectedTargetId = id,
                    candidates = emptyList(),
                    cardCandidates = listOf(
                        Card("noise_mix_$idx", "$albName Mix", "DJ Remix", type = "album"),
                        Card("noise_rev_$idx", "$albName Album Review", "Podcast", type = "album"),
                        Card(id, albName, artName, type = "album")
                    )
                )
            )
        }

        // 4. Song + Artist (20 queries)
        val songArtists = listOf(
            Triple("Blinding Lights The Weeknd", "s_01", "Blinding Lights" to "The Weeknd"),
            Triple("Shape of You Ed Sheeran", "s_02", "Shape of You" to "Ed Sheeran"),
            Triple("Anti-Hero Taylor Swift", "s_03", "Anti-Hero" to "Taylor Swift"),
            Triple("Starboy The Weeknd", "s_04", "Starboy" to "The Weeknd"),
            Triple("Flowers Miley Cyrus", "s_05", "Flowers" to "Miley Cyrus"),
            Triple("Stay The Kid LAROI", "s_06", "Stay" to "The Kid LAROI"),
            Triple("Levitating Dua Lipa", "s_07", "Levitating" to "Dua Lipa"),
            Triple("Bad Guy Billie Eilish", "s_08", "Bad Guy" to "Billie Eilish"),
            Triple("As It Was Harry Styles", "s_09", "As It Was" to "Harry Styles"),
            Triple("Kesariya Arijit Singh", "s_10", "Kesariya" to "Arijit Singh"),
            Triple("Believer Imagine Dragons", "s_11", "Believer" to "Imagine Dragons"),
            Triple("Someone Like You Adele", "s_12", "Someone Like You" to "Adele"),
            Triple("Perfect Ed Sheeran", "s_13", "Perfect" to "Ed Sheeran"),
            Triple("Shallow Lady Gaga", "s_14", "Shallow" to "Lady Gaga"),
            Triple("Positions Ariana Grande", "s_15", "Positions" to "Ariana Grande"),
            Triple("Peaches Justin Bieber", "s_16", "Peaches" to "Justin Bieber"),
            Triple("Save Your Tears The Weeknd", "s_17", "Save Your Tears" to "The Weeknd"),
            Triple("Ghost Justin Bieber", "s_18", "Ghost" to "Justin Bieber"),
            Triple("Dynamite BTS", "s_19", "Dynamite" to "BTS"),
            Triple("Industry Baby Lil Nas X", "s_20", "Industry Baby" to "Lil Nas X")
        )
        songArtists.forEach { (query, id, pair) ->
            val (songTitle, artistName) = pair
            cases.add(
                BenchmarkCase(
                    category = "Song + Artist",
                    query = query,
                    expectedTargetId = id,
                    candidates = listOf(
                        Track("noise_${id}_live", "$songTitle (Live at Stadium)", artistName, durationSeconds = 300),
                        Track("noise_${id}_cover", "$songTitle - Cover", "YouTube Musician", durationSeconds = 200),
                        Track(id, songTitle, artistName, album = "$songTitle Album", durationSeconds = 210),
                        Track("noise_${id}_inst", "$songTitle Instrumental", "Karaoke Club", durationSeconds = 210)
                    )
                )
            )
        }

        // 5. Lyric snippets (15 queries)
        val lyrics = listOf(
            Triple("i'm blinded by the lights", "lyr_01", "Blinding Lights" to "The Weeknd"),
            Triple("i'm in love with the shape of you", "lyr_02", "Shape of You" to "Ed Sheeran"),
            Triple("it's me hi i'm the problem it's me", "lyr_03", "Anti-Hero" to "Taylor Swift"),
            Triple("i can buy myself flowers", "lyr_04", "Flowers" to "Miley Cyrus"),
            Triple("i'm a bad guy duh", "lyr_05", "Bad Guy" to "Billie Eilish"),
            Triple("you want me i want you baby", "lyr_06", "Levitating" to "Dua Lipa"),
            Triple("you know it's not the same as it was", "lyr_07", "As It Was" to "Harry Styles"),
            Triple("i do the same thing I told you that I never would", "lyr_08", "Stay" to "The Kid LAROI"),
            Triple("late at night when all the world is sleeping", "lyr_09", "Save Your Tears" to "The Weeknd"),
            Triple("i took an arrow to the heart", "lyr_10", "Ghost" to "Justin Bieber"),
            Triple("sometimes all i think about is you", "lyr_11", "Heat Waves" to "Glass Animals"),
            Triple("i get my peaches out in georgia", "lyr_12", "Peaches" to "Justin Bieber"),
            Triple("red lights stop signs", "lyr_13", "Drivers License" to "Olivia Rodrigo"),
            Triple("god it's brutal out here", "lyr_14", "Brutal" to "Olivia Rodrigo"),
            Triple("look what you've done i'm a starboy", "lyr_15", "Starboy" to "The Weeknd")
        )
        lyrics.forEach { (query, id, pair) ->
            val (songTitle, artistName) = pair
            cases.add(
                BenchmarkCase(
                    category = "Lyric Snippet",
                    query = query,
                    expectedTargetId = id,
                    candidates = listOf(
                        Track("noise_${id}_lyricvid", "$songTitle (Official Lyric Video)", artistName, durationSeconds = 210),
                        Track(id, songTitle, artistName, album = "$songTitle - Single", durationSeconds = 210),
                        Track("noise_${id}_cover", "$songTitle (Cover)", "Acoustic Girl", durationSeconds = 180)
                    )
                )
            )
        }

        // 6. Transliterated titles (10 queries)
        val transliterated = listOf(
            Triple("Tum Hi Ho", "tr_01", "Tum Hi Ho" to "Arijit Singh"),
            Triple("Malare Ninne", "tr_02", "Malare" to "Vijay Yesudas"),
            Triple("Kesariya", "tr_03", "Kesariya" to "Arijit Singh"),
            Triple("Kal Ho Naa Ho", "tr_04", "Kal Ho Naa Ho" to "Sonu Nigam"),
            Triple("Appangal Embadhum", "tr_05", "Appangal Embadhum" to "Gopi Sundar"),
            Triple("Chaiyya Chaiyya", "tr_06", "Chaiyya Chaiyya" to "Sukhwinder Singh"),
            Triple("Dil Diyan Gallan", "tr_07", "Dil Diyan Gallan" to "Atif Aslam"),
            Triple("Agar Tum Saath Ho", "tr_08", "Agar Tum Saath Ho" to "Alka Yagnik"),
            Triple("Raataan Lambiyan", "tr_09", "Raataan Lambiyan" to "Jubin Nautiyal"),
            Triple("Pasoori", "tr_10", "Pasoori" to "Ali Sethi")
        )
        transliterated.forEach { (query, id, pair) ->
            val (songTitle, artistName) = pair
            cases.add(
                BenchmarkCase(
                    category = "Transliterated",
                    query = query,
                    expectedTargetId = id,
                    candidates = listOf(
                        Track("noise_${id}_cover", "$songTitle (Acoustic Cover)", "Cover Singer", durationSeconds = 220),
                        Track("noise_${id}_slow", "$songTitle (Slowed Reverb)", "Lo-Fi Music", durationSeconds = 270),
                        Track(id, songTitle, artistName, album = "Original Motion Picture Soundtrack", durationSeconds = 250)
                    )
                )
            )
        }

        // 7. Regional-language titles (10 queries)
        val regional = listOf(
            Triple("മലരേ", "reg_01", "മലരേ (Malare)" to "Vijay Yesudas"),
            Triple("കണ്ണാന കണ്ണേ", "reg_02", "കണ്ണാന കണ്ണേ" to "D. Imman"),
            Triple("तुम ही हो", "reg_03", "तुम ही हो (Tum Hi Ho)" to "Arijit Singh"),
            Triple("मन माझे", "reg_04", "मन माझे" to "Arijit Singh"),
            Triple("வாத்தி கமிங்", "reg_05", "வாத்தி கமிங் (Vaathi Coming)" to "Anirudh Ravichander"),
            Triple("నాటు నాటు", "reg_06", "నాటు నాటు (Naatu Naatu)" to "Rahul Sipligunj"),
            Triple("ചെമ്പകപ്പൂ", "reg_07", "ചെമ്പകപ്പൂ" to "K.J. Yesudas"),
            Triple("കുട്ടനാടൻ പുഞ്ചയിലെ", "reg_08", "കുട്ടനാടൻ പുഞ്ചയിലെ" to "Vidhu Prathap"),
            Triple("तेरे वास्ते", "reg_09", "तेरे वास्ते (Tere Vaaste)" to "Varun Jain"),
            Triple("രാവുകൾ", "reg_10", "രാവുകൾ" to "Job Kurian")
        )
        regional.forEach { (query, id, pair) ->
            val (songTitle, artistName) = pair
            cases.add(
                BenchmarkCase(
                    category = "Regional Script",
                    query = query,
                    expectedTargetId = id,
                    candidates = listOf(
                        Track("noise_${id}_remix", "$songTitle (Bass Boosted Remix)", "DJ", durationSeconds = 190),
                        Track("noise_${id}_live", "$songTitle (Live Concert)", artistName, durationSeconds = 310),
                        Track(id, songTitle, artistName, album = "Official Track", durationSeconds = 240)
                    )
                )
            )
        }

        return cases
    }

    @Test
    fun `100-query search benchmark achieves over 85 percent top-1 accuracy`() {
        val suite = build100QueriesSuite()
        println("=== Running 100-Query Search Quality Benchmark ===")
        println("Total Queries: ${suite.size}")

        var rawTop1Hits = 0
        var rankedTop1Hits = 0
        var totalProcessingNano = 0L

        suite.forEachIndexed { index, testCase ->
            val isCardBenchmark = testCase.cardCandidates.isNotEmpty()

            val rawTopId = if (isCardBenchmark) {
                testCase.cardCandidates.firstOrNull()?.id
            } else {
                testCase.candidates.firstOrNull()?.videoId
            }
            if (rawTopId == testCase.expectedTargetId) {
                rawTop1Hits++
            }

            val elapsedNano = measureNanoTime {
                val rankedTopId = if (isCardBenchmark) {
                    val ranked = SearchRelevance.rankCards(testCase.query, testCase.cardCandidates)
                    ranked.firstOrNull()?.id
                } else {
                    val ranked = SearchRelevance.rankTracks(testCase.query, testCase.candidates)
                    ranked.firstOrNull()?.videoId
                }

                if (rankedTopId == testCase.expectedTargetId) {
                    rankedTop1Hits++
                }
            }
            totalProcessingNano += elapsedNano
        }

        val rawTop1Rate = (rawTop1Hits.toDouble() / suite.size) * 100.0
        val rankedTop1Rate = (rankedTop1Hits.toDouble() / suite.size) * 100.0
        val avgTimePerQueryMs = (totalProcessingNano.toDouble() / suite.size) / 1_000_000.0

        println("--------------------------------------------------")
        println("Raw InnerTube Server Order Top-1 Hit Rate : String.format(%.1f%%, $rawTop1Rate)")
        println("Geekify Re-Ranked Top-1 Hit Rate          : String.format(%.1f%%, $rankedTop1Rate)")
        println("Average Re-Ranking Duration per Query     : String.format(%.3f ms, $avgTimePerQueryMs)")
        println("--------------------------------------------------")

        // Target: Top-1 hit rate of at least 85% on the test set
        assertTrue(
            "Expected top-1 hit rate >= 85%, got $rankedTop1Rate%",
            rankedTop1Rate >= 85.0
        )
    }
}
