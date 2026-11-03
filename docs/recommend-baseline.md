# Recommendation Quality Baseline & Evaluation Report

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
| **Hit Rate @ 10** | 0.7500 | 0.8000 | +0.0500 | Non-decreasing (>= baseline) | PASS |
| **NDCG @ 10** | 0.6775 | 0.7275 | +0.0500 | Non-decreasing (>= baseline) | PASS |
| **Mean Completion Rate @ 10** | 0.4270 | 0.4551 | +0.0281 | Observational | PASS |
| **Artist Diversity @ 10** | 0.5503 | 0.5690 | +0.0187 | Non-decreasing (>= baseline) | PASS |

## Phase Reporting History
| Phase | Hit Rate @ 10 | NDCG @ 10 | Mean Completion @ 10 | Artist Diversity @ 10 | Status |
|---|---|---|---|---|---|
| **Phase 2 (Baseline)** | 0.7500 | 0.6775 | 0.4270 | 0.5503 | Baseline Recorded |
| **Phase 5 (Upgraded)** | 0.8000 | 0.7275 | 0.4551 | 0.5690 | Evaluated & Verified |
