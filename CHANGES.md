# Recommendation Pipeline Overhaul & Quality Enhancements

## 1. Summary of Changes

### Phase 1: Impression and Outcome Logging
- **Room Logging Entities**: Added `RecommendationImpressionEntity` and `RecommendationOutcomeEntity` with indexes on `requestId`, `mixId`, `videoId`, and `shownAt`.
- **Database Migration**: Added `MIGRATION_7_8` in `GeekifyDatabase.kt` and bumped Room database version from 7 to 8. Auto-generated Room schema `8.json`.
- **Outcome Hooks**:
  - `PlaybackService.kt`: Logs `PLAYED`, `COMPLETED`, and `SKIPPED_EARLY` (plays under 30s) against recent impressions.
  - `LibraryRepository.kt`: Logs `LIKED` and `ADDED_TO_PLAYLIST` against recent impressions.
- **Background Retention Worker**: Added `RecommendationPruneWorker` scheduled on application start to prune impressions and outcomes older than 30 days.
- **Tests**: `RecommendationLoggingTest.kt` verifies table schema creation, index creation, foreign key relations, and playback outcome linking.

---

### Phase 2: Evaluation Harness
- **Pure Kotlin Offline Evaluator**: Built `RecommendationEvaluator.kt` and `EvalModels.kt` in `domain/recommend/eval/` without Android framework dependencies.
- **Synthetic Fixture Dataset**: Created `src/test/resources/synthetic_listeners.json` with 20 synthetic listeners across 5 personas: monolingual, multilingual, English-only, cold start, and heavy repeat.
- **Metrics Tracked**: Hit Rate @ 10, NDCG @ 10, Mean Completion Rate @ 10, Artist Diversity @ 10.
- **Baseline Documented**: Recorded pre-refactor metrics in `docs/recommend-baseline.md`.

---

### Phase 3: Verified Bug Fixes
1. **Language Preference Isolation**:
   - *Fix*: Content language preference from Settings is applied as a direct candidate score boost (`+25%`) rather than being injected into the user's affinity profile. Setting a preference never triggers language diversity penalties on the preferred language.
   - *Test*: `RecommendationBugFixesTest.testLanguagePreferenceNeverReducesMalayalamForHindiDominantListener`.
2. **Universal Language Diversity**:
   - *Fix*: Language diversity applies to all candidates, treating untagged Latin-script items as a consistent `"Latin"` language bucket so they are subject to diversity balancing alongside regional scripts.
   - *Test*: `RecommendationBugFixesTest.testLanguageDiversityAppliesToLatinScriptTracks`.
3. **Whole-Word Vibe Keyword Matching**:
   - *Fix*: Time-of-day vibe keywords now match on whole word boundaries (`\b`) rather than substring matching. "White" no longer matches "hit" and "Crave" no longer matches "rave".
   - *Test*: `RecommendationBugFixesTest.testTimeOfDayKeywordWholeWordMatching`.
4. **Regional Script Detection & Settings Mapping**:
   - *Fix*: Added Unicode script detection for Bengali (`\u0980..\u09FF`), Kannada (`\u0C80..\u0CFF`), Gujarati (`\u0A80..\u0AFF`), and Odia (`\u0B00..\u0B7F`). Han-only characters remain unknown (`null`). Added language codes (`bn`, `kn`, `gu`, `or`, `pa`) to `HomeViewModel` and `SettingsScreen`.
   - *Test*: `RecommendationBugFixesTest.testScriptDetectionForNewIndicLanguages`.
5. **Session Signal Single-Entry**:
   - *Fix*: Removed duplicate `artistSessionMod` in `buildMixes`. Session completion/skips enter artist affinity once via `seedWeights`.
   - *Test*: `RecommenderTest.testSessionCompletionBoostsAndSkipsDemoteSeedWeights`.
6. **Consistent Language Weights**:
   - *Fix*: `HomeViewModel` and `Recommender.buildMixes` compute seed and candidate weights from the identical listening language profile.

---

### Phase 4: Ranking Improvements
1. **Exponential Half-Life Time Decay**:
   - *Fix*: Replaced index decay with continuous exponential time decay ($2^{-\Delta t / t_{\text{half}}}$):
     - Plays: 14-day half-life.
     - Likes & Playlist Adds: 60-day half-life.
     - Skips: 3-day half-life.
     - Graceful fallback: Signals without timestamps use index-based decay approximation.
   - *Test*: `RecommendationPhase4Test.testExponentialTimeDecayHalfLife`.
2. **Maximal Marginal Relevance (MMR) Diversification**:
   - *Fix*: Formulated as $\text{MMR} = \lambda \cdot \text{relevance} - (1 - \lambda) \cdot \max_{s \in S} \text{similarity}(c, s)$ with default $\lambda = 0.7$ and hard cap of 3 tracks per artist. Similarity: 1.0 same artist, 0.5 same album, 0.3 same language.
   - *Test*: `RecommendationPhase4Test.testMaximalMarginalRelevanceDiversification`.
3. **Exploration Slot**:
   - *Fix*: Reserved every 8th slot (positions 7, 15, 23, ...) in "Made for you" for high-scoring candidates from unheard artists (zero plays in history). Marked with `source = "explore"` in impression logs.
   - *Test*: `RecommendationPhase4Test.testExplorationSlotInMadeForYou`.
4. **Graduated Impression Demotion**:
   - *Fix*: Replaced flat 0.35 multiplier with graduated feature `demotion = 1.0 / (1.0 + shownCount * 0.5)` based on 7-day impression count, with an extra $0.2\times$ factor for tracks shown $\ge 3$ times with 0 plays. Never completely excluded.
   - *Test*: `RecommendationPhase4Test.testShownCountGraduatedDemotion`.

---

## 2. Evaluation Metric Comparison (Phase 5)

| Metric | Pre-Refactor Baseline | Post-Refactor Upgraded | Delta | Acceptance Bar | Status |
|---|---|---|---|---|---|
| **Hit Rate @ 10** | 0.7500 | 0.8000 | +0.0500 | Non-decreasing (>= baseline) | PASS |
| **NDCG @ 10** | 0.6775 | 0.7275 | +0.0500 | Non-decreasing (>= baseline) | PASS |
| **Mean Completion Rate @ 10** | 0.4270 | 0.4551 | +0.0281 | Observational | PASS |
| **Artist Diversity @ 10** | 0.5503 | 0.5690 | +0.0187 | Non-decreasing (>= baseline) | PASS |

---

## 3. Items Requiring Device / Live Verification

The following items cannot be fully verified offline in JUnit unit tests and should be checked on a physical device:
1. **Live YouTube Related Radio Fetching**:
   - Verification: Launch app connected to network, verify that "Made for you", "Fresh finds", and "More like <artist>" mixes populate with thumbnails and playable streams.
2. **Live WorkManager Periodic Pruning**:
   - Verification: Trigger `RecommendationPruneWorker` via `adb shell cmd jobscheduler run` to confirm Room prune execution in background.
3. **Audio Playback Outcome Logging**:
   - Verification: Play a recommended song to completion, verify via database inspection (`recommendation_outcomes`) that a `COMPLETED` row is inserted. Skip before 30s to verify `SKIPPED_EARLY`.
4. **UI Settings Content Language Selection**:
   - Verification: Open Settings > Content & Region > Content Language, select Gujarati / Bengali / Kannada / Odia / Punjabi, and verify that the Home screen top picks and daily mixes adapt without UI jank.
