package com.geekify.android.domain.recommend.eval

import com.geekify.android.domain.recommend.Recommender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class RecommendationEvaluationTest {

    @Test
    fun loadAndEvaluateRankingHarnessWithBeforeAfterComparison() {
        val listeners = FixtureLoader.loadSyntheticListeners()
        assertEquals(20, listeners.size)

        // Baseline pre-refactor values recorded in Phase 2
        val baselineHitRate = 0.7500
        val baselineNdcg = 0.6775
        val baselineMeanCompletion = 0.4270
        val baselineArtistDiversity = 0.5503

        // Upgraded ranking function using new Recommender.buildMixes (MMR, half-life time decay, exploration slot)
        val upgradedRankingFunction = {
            liked: List<Recommender.Signal>,
            recent: List<Recommender.Signal>,
            frequent: List<Recommender.PlayedSignal>,
            playlist: List<Recommender.Signal>,
            sessions: List<Recommender.SessionSignal>,
            candidatePool: Map<String, List<EvalTrack>> ->

            val (mixes, _) = Recommender.buildMixes(
                liked = liked,
                recent = recent,
                related = candidatePool,
                videoId = { it.videoId },
                artist = { it.artist },
                artistDisplay = { it.artist },
                title = { it.title },
                thumbs = { emptyList<Any>() },
                duration = { null },
                durationSeconds = { it.durationSeconds },
                explicit = { false },
                type = { "song" },
                frequent = frequent,
                playlist = playlist,
                sessions = sessions,
                random = Random(42)
            )

            val forYou = mixes.firstOrNull { it.id == "for-you" }
            forYou?.videoIds.orEmpty().ifEmpty {
                mixes.flatMap { it.videoIds }.distinct()
            }
        }

        val report = RecommendationEvaluator.evaluate(listeners, upgradedRankingFunction)

        println("=== Recommendation Evaluation Comparison ===")
        println("Metric                  | Baseline | Upgraded | Delta")
        println("------------------------|----------|----------|--------")
        println(String.format("Hit Rate @ 10           | %.4f   | %.4f   | %+.4f", baselineHitRate, report.metrics.hitRateAt10, report.metrics.hitRateAt10 - baselineHitRate))
        println(String.format("NDCG @ 10               | %.4f   | %.4f   | %+.4f", baselineNdcg, report.metrics.ndcgAt10, report.metrics.ndcgAt10 - baselineNdcg))
        println(String.format("Mean Completion Rate @ 10| %.4f   | %.4f   | %+.4f", baselineMeanCompletion, report.metrics.meanCompletionRateAt10, report.metrics.meanCompletionRateAt10 - baselineMeanCompletion))
        println(String.format("Artist Diversity @ 10   | %.4f   | %.4f   | %+.4f", baselineArtistDiversity, report.metrics.artistDiversityAt10, report.metrics.artistDiversityAt10 - baselineArtistDiversity))
        println("============================================")

        // Quality bar: Hit rate @ 10, NDCG @ 10, and artist diversity must not drop.
        assertTrue(
            "Hit rate @ 10 must not drop (baseline=$baselineHitRate, new=${report.metrics.hitRateAt10})",
            report.metrics.hitRateAt10 >= baselineHitRate
        )
        assertTrue(
            "NDCG @ 10 must not drop (baseline=$baselineNdcg, new=${report.metrics.ndcgAt10})",
            report.metrics.ndcgAt10 >= baselineNdcg
        )
        assertTrue(
            "Artist diversity @ 10 must not drop (baseline=$baselineArtistDiversity, new=${report.metrics.artistDiversityAt10})",
            report.metrics.artistDiversityAt10 >= baselineArtistDiversity
        )

        // Update docs/recommend-baseline.md
        val possibleDocsDirs = listOf(File("docs"), File("../docs"))
        for (dir in possibleDocsDirs) {
            if (!dir.exists()) dir.mkdirs()
            File(dir, "recommend-baseline.md").writeText(
                """# Recommendation Quality Baseline & Evaluation Report

## Evaluation Harness Overview
- **Harness:** Pure Kotlin offline evaluator (`domain/recommend/eval/RecommendationEvaluator.kt`)
- **Dataset:** 20 synthetic listeners (`src/test/resources/synthetic_listeners.json`)
  - Monolingual (Malayalam, Hindi, Tamil, Korean, Punjabi)
  - Multilingual (Malayalam + Tamil, Hindi + Punjabi, South Indian Tri-lingual, Pan-Indian, Desi-Western fusion)
  - English-only (Pop, Hip-Hop, Indie Rock, Electronic)
  - Cold start (Minimal 1-like, Single-artist 2-plays, Guest exploration)
  - Heavy repeat (Single track loop, Short album repeat, Lo-Fi study repeat)
- **Cutoff Protocol:** Train on events before listener `cutoffTimestamp`; ground truth test on events after cutoff.

## Before / After Comparison Table
| Metric | Pre-Refactor Baseline | Post-Refactor Upgraded | Delta | Acceptance Bar | Status |
|---|---|---|---|---|---|
| **Hit Rate @ 10** | ${String.format("%.4f", baselineHitRate)} | ${String.format("%.4f", report.metrics.hitRateAt10)} | ${String.format("%+.4f", report.metrics.hitRateAt10 - baselineHitRate)} | Non-decreasing (>= baseline) | PASS |
| **NDCG @ 10** | ${String.format("%.4f", baselineNdcg)} | ${String.format("%.4f", report.metrics.ndcgAt10)} | ${String.format("%+.4f", report.metrics.ndcgAt10 - baselineNdcg)} | Non-decreasing (>= baseline) | PASS |
| **Mean Completion Rate @ 10** | ${String.format("%.4f", baselineMeanCompletion)} | ${String.format("%.4f", report.metrics.meanCompletionRateAt10)} | ${String.format("%+.4f", report.metrics.meanCompletionRateAt10 - baselineMeanCompletion)} | Observational | PASS |
| **Artist Diversity @ 10** | ${String.format("%.4f", baselineArtistDiversity)} | ${String.format("%.4f", report.metrics.artistDiversityAt10)} | ${String.format("%+.4f", report.metrics.artistDiversityAt10 - baselineArtistDiversity)} | Non-decreasing (>= baseline) | PASS |

## Phase Reporting History
| Phase | Hit Rate @ 10 | NDCG @ 10 | Mean Completion @ 10 | Artist Diversity @ 10 | Status |
|---|---|---|---|---|---|
| **Phase 2 (Baseline)** | ${String.format("%.4f", baselineHitRate)} | ${String.format("%.4f", baselineNdcg)} | ${String.format("%.4f", baselineMeanCompletion)} | ${String.format("%.4f", baselineArtistDiversity)} | Baseline Recorded |
| **Phase 5 (Upgraded)** | ${String.format("%.4f", report.metrics.hitRateAt10)} | ${String.format("%.4f", report.metrics.ndcgAt10)} | ${String.format("%.4f", report.metrics.meanCompletionRateAt10)} | ${String.format("%.4f", report.metrics.artistDiversityAt10)} | Evaluated & Verified |
"""
            )
        }
    }
}
