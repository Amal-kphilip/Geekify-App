package com.geekify.android.domain.recommend.engine

import androidx.sqlite.db.SupportSQLiteDatabase
import com.geekify.android.data.local.CollaborativeInteractionEntity
import com.geekify.android.data.local.ExperimentAssignmentEntity
import com.geekify.android.data.local.MIGRATION_8_9
import com.geekify.android.data.local.RecommendationEngineDao
import com.geekify.android.data.local.TrackFeatureEntity
import com.geekify.android.data.local.UserPreferenceProfileEntity
import com.geekify.android.data.model.ArtistPage
import com.geekify.android.data.model.CollectionKind
import com.geekify.android.data.model.CollectionPage
import com.geekify.android.data.model.HomeResponse
import com.geekify.android.data.model.SearchResponse
import com.geekify.android.data.model.SearchType
import com.geekify.android.data.model.Track
import com.geekify.android.data.source.MusicResult
import com.geekify.android.data.source.MusicSource
import com.geekify.android.domain.recommend.Recommender
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class HybridRecommendationEngineTest {

    private lateinit var fakeDao: FakeRecommendationEngineDao

    @Before
    fun setUp() {
        fakeDao = FakeRecommendationEngineDao()
    }

    // -----------------------------------------------------------------------
    // 1. Database Migration 8 -> 9 & Schema Verification
    // -----------------------------------------------------------------------

    @Test
    fun `migration 8 to 9 adds provenance columns and creates all v9 AI recommendation tables`() {
        val executedStatements = mutableListOf<String>()

        val dbProxy = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            object : InvocationHandler {
                override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
                    if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                        executedStatements.add(args[0] as String)
                    }
                    return null
                }
            }
        ) as SupportSQLiteDatabase

        MIGRATION_8_9.migrate(dbProxy)

        assertTrue(executedStatements.any { it.contains("ALTER TABLE `recommendation_impressions` ADD COLUMN `candidateSource`") })
        assertTrue(executedStatements.any { it.contains("ALTER TABLE `recommendation_impressions` ADD COLUMN `modelVersion`") })
        assertTrue(executedStatements.any { it.contains("ALTER TABLE `recommendation_impressions` ADD COLUMN `experimentGroup`") })
        assertTrue(executedStatements.any { it.contains("CREATE TABLE IF NOT EXISTS `track_features`") })
        assertTrue(executedStatements.any { it.contains("CREATE TABLE IF NOT EXISTS `user_preference_profiles`") })
        assertTrue(executedStatements.any { it.contains("CREATE TABLE IF NOT EXISTS `experiment_assignments`") })
        assertTrue(executedStatements.any { it.contains("CREATE TABLE IF NOT EXISTS `collaborative_interactions`") })
    }

    // -----------------------------------------------------------------------
    // 2. Canonical Track Identity & Variant Disambiguation Tests
    // -----------------------------------------------------------------------

    @Test
    fun `track identity normalizer strips YouTube noise while distinguishing original live remix and cover variants`() {
        val originalVideo = Track(
            videoId = "v1",
            title = "Illuminati (From \"Aavesham\") [Official Video] | 4K",
            artist = "Sushin Shyam feat. Dabzee - Topic"
        )
        val originalLyric = Track(
            videoId = "v2",
            title = "Illuminati (Lyric Video)",
            artist = "Sushin Shyam, Dabzee"
        )
        val liveVersion = Track(
            videoId = "v3",
            title = "Illuminati (Live at Kochi Concert)",
            artist = "Sushin Shyam"
        )
        val remixVersion = Track(
            videoId = "v4",
            title = "Illuminati (DJ Club Remix)",
            artist = "Sushin Shyam"
        )
        val slowedVersion = Track(
            videoId = "v5",
            title = "Illuminati [Slowed + Reverb]",
            artist = "Sushin Shyam"
        )

        val norm1 = TrackIdentityNormalizer.normalize(originalVideo)
        val norm2 = TrackIdentityNormalizer.normalize(originalLyric)
        val normLive = TrackIdentityNormalizer.normalize(liveVersion)
        val normRemix = TrackIdentityNormalizer.normalize(remixVersion)
        val normSlowed = TrackIdentityNormalizer.normalize(slowedVersion)

        assertEquals(RecordingVariant.ORIGINAL, norm1.variant)
        assertEquals(RecordingVariant.ORIGINAL, norm2.variant)
        assertEquals("illuminati", norm1.normalizedTitle)
        assertEquals("sushin shyam", norm1.primaryArtistKey)
        assertTrue("Official video and lyric video of same studio song should match", TrackIdentityNormalizer.isSameRecording(norm1, norm2))

        assertEquals(RecordingVariant.LIVE, normLive.variant)
        assertEquals(RecordingVariant.REMIX, normRemix.variant)
        assertEquals(RecordingVariant.SLOWED_REVERB, normSlowed.variant)
        assertFalse("Original and Live must not collapse into same recording", TrackIdentityNormalizer.isSameRecording(norm1, normLive))
        assertFalse("Original and Remix must not collapse into same recording", TrackIdentityNormalizer.isSameRecording(norm1, normRemix))
    }

    // -----------------------------------------------------------------------
    // 3. Genre, Mood & Contextual Re-Ranking Tests
    // -----------------------------------------------------------------------

    @Test
    fun `classification service and context engine prevent false positive Night keyword on heavy metal tracks`() {
        val classifier = MusicClassificationService()
        val contextEngine = RecommendationContextEngine(classifier)

        // Heavy Metal track with "Night" in the title
        val metalNight = classifier.classify(
            title = "Into The Black Night (Heavy Metal)",
            artist = "Metallica",
            album = "Master of Metal",
            durationSeconds = 310
        )
        assertTrue("Should classify as METAL or ROCK", metalNight.genres.containsKey(MusicGenre.METAL) || metalNight.genres.containsKey(MusicGenre.ROCK))

        // Acoustic calm track
        val calmAcoustic = classifier.classify(
            title = "Mazha Nila Unplugged",
            artist = "KS Harisankar",
            album = "Acoustic Sessions",
            durationSeconds = 240
        )

        val lateNightMetalMult = contextEngine.contextAlignmentMultiplier(metalNight, ListeningContext.LATE_NIGHT)
        val lateNightCalmMult = contextEngine.contextAlignmentMultiplier(calmAcoustic, ListeningContext.LATE_NIGHT)

        assertTrue(
            "Calm acoustic track ($lateNightCalmMult) must outrank heavy metal track titled 'Night' ($lateNightMetalMult) in LATE_NIGHT context",
            lateNightCalmMult > lateNightMetalMult
        )
        assertTrue("Heavy metal track should be penalized (< 1.0) in calm LATE_NIGHT context unless listener prefers Metal", lateNightMetalMult < 1.0)
    }

    // -----------------------------------------------------------------------
    // 4. Audio Embeddings (32-D) & Multilingual Semantic Lyrics Tests
    // -----------------------------------------------------------------------

    @Test
    fun `audio embedding service generates L2 normalized 32D vectors and distinguishes authorized vs structural source`() {
        val classificationService = MusicClassificationService()
        val audioService = AudioEmbeddingService(classificationService)

        val trackA = Track(videoId = "a1", title = "Cherathukal", artist = "Sushin Shyam", durationSeconds = 215)
        val trackB = Track(videoId = "a2", title = "Uyire", artist = "Sid Sriram", durationSeconds = 225)
        val trackWorkout = Track(videoId = "a3", title = "Beast Mode GymEDM", artist = "Anirudh Ravichander", durationSeconds = 180)

        val embProjected = audioService.embedTrack(trackA)
        assertEquals(32, embProjected.vector.size)
        assertEquals(EmbeddingFeatureSource.METADATA_STRUCTURAL_PROJECTION, embProjected.source)

        val embAuthorized = audioService.embedTrack(
            track = trackA,
            authorizedAudio = AuthorizedAudioFeatures(
                tempoBpm = 92.0,
                energy = 0.38,
                acousticness = 0.82,
                valence = 0.45,
                dynamicRangeDb = 11.5
            )
        )
        assertEquals(EmbeddingFeatureSource.AUTHORIZED_AUDIO_ANALYSIS, embAuthorized.source)

        val simMelody = audioService.cosineSimilarity(embProjected.vector, audioService.embedTrack(trackB).vector)
        val simWorkout = audioService.cosineSimilarity(embProjected.vector, audioService.embedTrack(trackWorkout).vector)
        assertTrue("Melodic tracks should have higher audio embedding cosine similarity than EDM workout track", simMelody > simWorkout)
    }

    @Test
    fun `lyrics semantic service aligns romantic rain nostalgia across Malayalam Tamil and Hindi without storing raw lyrics`() {
        val lyricsService = LyricsSemanticService()

        val malayalamRainRomance = lyricsService.extractSemanticVector(
            title = "Mazha Nila Pranayam",
            artist = "Vineeth Sreenivasan"
        )
        val tamilRainRomance = lyricsService.extractSemanticVector(
            title = "Mazhai Nila Kadhal",
            artist = "Sid Sriram"
        )
        val hindiRainRomance = lyricsService.extractSemanticVector(
            title = "Baarish Ishq Dil",
            artist = "Arijit Singh"
        )
        val gymPartyBanger = lyricsService.extractSemanticVector(
            title = "Club Dance Party Mashup",
            artist = "DJ Badshah"
        )

        val simMlTa = lyricsService.semanticSimilarity(malayalamRainRomance, tamilRainRomance)
        val simMlHi = lyricsService.semanticSimilarity(malayalamRainRomance, hindiRainRomance)
        val simMlParty = lyricsService.semanticSimilarity(malayalamRainRomance, gymPartyBanger)

        assertTrue("Cross-language Malayalam <-> Tamil rain/romance similarity ($simMlTa) should be high", simMlTa > 0.70)
        assertTrue("Cross-language Malayalam <-> Hindi rain/romance similarity ($simMlHi) should beat party banger ($simMlParty)", simMlHi > simMlParty)
    }

    // -----------------------------------------------------------------------
    // 5. Multi-User Collaborative Filtering & Privacy Tests
    // -----------------------------------------------------------------------

    @Test
    fun `collaborative engine enforces temporal cutoff deduplicates rapid plays and bridges cross-user co-occurrence`() {
        val collab = CollaborativeRecommendationEngine()
        val u1 = collab.anonymizeUserId("user_alpha")
        val u2 = collab.anonymizeUserId("user_beta")
        val u3 = collab.anonymizeUserId("user_gamma")

        assertNotEquals("user_alpha", u1)
        assertEquals("Deterministic salted SHA-256 hash", u1, collab.anonymizeUserId("user_alpha"))

        val interactions = listOf(
            // User 1 & User 2 both love vid_kumbalangi and vid_premam before cutoff=2000
            UserItemInteraction(u1, "vid_kumbalangi", "sushin shyam", CollaborativeInteractionType.LIKED, timestampMs = 1000L),
            UserItemInteraction(u1, "vid_premam", "rajesh murugesan", CollaborativeInteractionType.COMPLETED, timestampMs = 1100L),
            // Duplicate within 5s window should be deduplicated
            UserItemInteraction(u1, "vid_premam", "rajesh murugesan", CollaborativeInteractionType.COMPLETED, timestampMs = 1102L),
            UserItemInteraction(u2, "vid_kumbalangi", "sushin shyam", CollaborativeInteractionType.ADDED_TO_PLAYLIST, timestampMs = 1200L),
            UserItemInteraction(u2, "vid_premam", "rajesh murugesan", CollaborativeInteractionType.LIKED, timestampMs = 1300L),
            // User 3 likes vid_kumbalangi before cutoff
            UserItemInteraction(u3, "vid_kumbalangi", "sushin shyam", CollaborativeInteractionType.LIKED, timestampMs = 1400L),
            // Future interaction after cutoff=2000 must not leak
            UserItemInteraction(u1, "vid_future_leak", "future artist", CollaborativeInteractionType.LIKED, timestampMs = 9000L),
            UserItemInteraction(u2, "vid_future_leak", "future artist", CollaborativeInteractionType.LIKED, timestampMs = 9100L)
        )

        val snapshot = collab.train(
            interactions = interactions,
            cutoffTimestampMs = 2000L,
            minCohortListeners = 2,
            epochs = 12
        )

        assertFalse("Future event after cutoff must not enter trained model", snapshot.itemVectors.containsKey("vid_future_leak"))

        val scorePremamForU3 = collab.scoreCandidate(
            anonUserHash = u3,
            userPositiveVideoIds = setOf("vid_kumbalangi"),
            userTopArtists = setOf("sushin shyam"),
            candidateVideoId = "vid_premam",
            candidateArtistKey = "rajesh murugesan",
            snapshot = snapshot
        )
        val scoreColdTrack = collab.scoreCandidate(
            anonUserHash = u3,
            userPositiveVideoIds = setOf("vid_kumbalangi"),
            userTopArtists = setOf("sushin shyam"),
            candidateVideoId = "vid_unheard_xyz",
            candidateArtistKey = "unheard artist",
            snapshot = snapshot
        )

        assertTrue("Co-listened track ($scorePremamForU3) must outscore unheard cold-start track ($scoreColdTrack)", scorePremamForU3 > scoreColdTrack)
        assertEquals(0.0, scoreColdTrack, 1e-6)
    }

    // -----------------------------------------------------------------------
    // 6. Neural Two-Tower + Ranking MLP & Chronological Validation Gate Tests
    // -----------------------------------------------------------------------

    @Test
    fun `neural engine ranks high-affinity candidates above skipped candidates and supports chronological training and rollback`() {
        val neural = NeuralRecommendationEngine()

        val strongCandidate = NeuralRankingFeatures(
            twoTowerSimilarity = 0.88,
            collaborativeScore = 0.80,
            artistAffinity = 0.85,
            languageAffinity = 0.92,
            audioSimilarity = 0.84,
            lyricsSimilarity = 0.78,
            genreAffinity = 0.80,
            moodAffinity = 0.75,
            contextAlignment = 0.85,
            noveltyScore = 0.65,
            popularityPrior = 0.75,
            freshnessScore = 0.80,
            candidateSourceConfidence = 0.95,
            historicalCompletionRate = 0.90,
            historicalSkipPenalty = 0.05,
            recentExposurePenalty = 0.0
        )

        val heavilySkippedCandidate = NeuralRankingFeatures(
            twoTowerSimilarity = 0.25,
            collaborativeScore = 0.10,
            artistAffinity = 0.15,
            languageAffinity = 0.20,
            audioSimilarity = 0.30,
            lyricsSimilarity = 0.25,
            genreAffinity = 0.20,
            moodAffinity = 0.20,
            contextAlignment = 0.20,
            noveltyScore = 0.30,
            popularityPrior = 0.40,
            freshnessScore = 0.30,
            candidateSourceConfidence = 0.50,
            historicalCompletionRate = 0.15,
            historicalSkipPenalty = 0.90,
            recentExposurePenalty = 0.85
        )

        val pStrong = neural.predictOutcomeProbability(strongCandidate)
        val pSkipped = neural.predictOutcomeProbability(heavilySkippedCandidate)
        assertTrue("Strong candidate probability ($pStrong) must exceed skipped candidate ($pSkipped)", pStrong > pSkipped)

        // Chronological training on 20 time-ordered samples
        val trainingExamples = (1..20).map { idx ->
            val positive = idx % 2 == 0
            TrainingExample(
                userTowerInput = FloatArray(32) { 0.5f },
                itemTowerInput = FloatArray(32) { if (positive) 0.5f else -0.3f },
                rankingFeatures = if (positive) strongCandidate else heavilySkippedCandidate,
                outcomeLabel = if (positive) 1.0 else 0.0,
                timestampMs = 1000L + idx * 100L
            )
        }
        val promotedMeta = neural.trainAndValidateChronologically(
            examples = trainingExamples,
            newVersionTag = "neural-two-tower-mlp-v1.2-test",
            epochs = 15
        )
        assertEquals("neural-two-tower-mlp-v1.2-test", promotedMeta.modelVersion)
        assertEquals("neural-two-tower-mlp-v1.2-test", neural.currentModelMetadata().modelVersion)

        // Rollback verification
        val rolledBack = neural.rollbackModel()
        assertEquals("v2.4-neural-base", rolledBack.modelVersion)
    }

    // -----------------------------------------------------------------------
    // 7. Randomized A/B Testing, Persistence, SRM & Guardrail Kill-Switch Tests
    // -----------------------------------------------------------------------

    @Test
    fun `experiment manager persists deterministic group assignment detects SRM and triggers kill switch rollback`() = runBlocking {
        val manager = RecommendationExperimentManager()

        val g1 = manager.getOrAssignGroup("account_malayalam_lover", dao = fakeDao)
        val g2 = manager.getOrAssignGroup("account_malayalam_lover", dao = fakeDao)
        assertEquals("Group assignment must be deterministic and persisted in Room", g1, g2)

        val stored = fakeDao.getExperimentAssignment("account_malayalam_lover")
        assertNotNull(stored)
        assertEquals(g1.name, stored?.treatmentGroup)

        // SRM check
        val srmBalanced = manager.detectSampleRatioMismatch(
            mapOf(
                ExperimentTreatmentGroup.CONTROL to 250,
                ExperimentTreatmentGroup.TREATMENT_A_CONTENT to 245,
                ExperimentTreatmentGroup.TREATMENT_B_COLLAB to 255,
                ExperimentTreatmentGroup.TREATMENT_C_NEURAL to 250
            )
        )
        assertFalse("Balanced 25/25/25/25 split must not trigger SRM", srmBalanced)

        val srmBroken = manager.detectSampleRatioMismatch(
            mapOf(
                ExperimentTreatmentGroup.CONTROL to 500,
                ExperimentTreatmentGroup.TREATMENT_A_CONTENT to 150,
                ExperimentTreatmentGroup.TREATMENT_B_COLLAB to 150,
                ExperimentTreatmentGroup.TREATMENT_C_NEURAL to 200
            )
        )
        assertTrue("Skewed bucket distribution must trigger SRM alert", srmBroken)

        // Kill-switch verification
        manager.activateKillSwitch()
        assertEquals(ExperimentTreatmentGroup.CONTROL, manager.getOrAssignGroup("account_malayalam_lover", dao = fakeDao))
        manager.resetKillSwitch()
    }

    // -----------------------------------------------------------------------
    // 8. Multi-Provider Discovery & Hybrid Pipeline End-to-End Integration Test
    // -----------------------------------------------------------------------

    @Test
    fun `musicbrainz provider parses recording JSON and hybrid pipeline produces distinct mixes and caches features`() = runBlocking {
        val fakeMbJson = """
            {
              "recordings": [
                {
                  "id": "mbid-111",
                  "title": "Parayuvaan",
                  "length": 242000,
                  "isrcs": ["INZ011900123"],
                  "artist-credit": [{"name": "Sid Sriram"}],
                  "releases": [{"title": "Ishq"}],
                  "tags": [{"name": "malayalam"}, {"name": "melody"}]
                }
              ]
            }
        """.trimIndent()

        val fakeMusicSource = object : MusicSource {
            override suspend fun home(): MusicResult<HomeResponse> = throw UnsupportedOperationException()
            override suspend fun search(query: String, type: SearchType?): MusicResult<SearchResponse> =
                MusicResult.Success(
                    SearchResponse(
                        query = query,
                        songs = listOf(
                            Track(videoId = "yt_parayuvaan", title = "Parayuvaan", artist = "Sid Sriram", durationSeconds = 242)
                        )
                    )
                )
            override suspend fun track(videoId: String): MusicResult<Track> = throw UnsupportedOperationException()
            override suspend fun artist(channelId: String): MusicResult<ArtistPage> = throw UnsupportedOperationException()
            override suspend fun collection(id: String, kind: CollectionKind): MusicResult<CollectionPage> = throw UnsupportedOperationException()
            override suspend fun related(videoId: String): MusicResult<List<Track>> =
                MusicResult.Success(
                    listOf(
                        Track(videoId = "yt_rel_1", title = "Uyire", artist = "Sid Sriram", durationSeconds = 230)
                    )
                )
        }

        val mbProvider = MusicBrainzCandidateProvider(
            httpClient = OkHttpClient(),
            musicSource = fakeMusicSource
        )
        val mbCandidates = mbProvider.parseMusicBrainzRecordings(fakeMbJson, limit = 5)
        assertEquals(1, mbCandidates.size)
        assertEquals("yt_parayuvaan", mbCandidates.first().track.videoId)
        assertEquals("mbid-111", mbCandidates.first().identity.mbRecordingId)
        assertEquals("INZ011900123", mbCandidates.first().identity.isrc)

        // End-to-End HybridRecommendationPipeline execution
        val classificationService = MusicClassificationService()
        val contextEngine = RecommendationContextEngine(classificationService)
        val audioService = AudioEmbeddingService(classificationService)
        val lyricsService = LyricsSemanticService()
        val collabEngine = CollaborativeRecommendationEngine()
        val neuralEngine = NeuralRecommendationEngine()
        val expManager = RecommendationExperimentManager()

        val pipeline = HybridRecommendationPipeline(
            classificationService = classificationService,
            contextEngine = contextEngine,
            audioEmbeddingService = audioService,
            lyricsSemanticService = lyricsService,
            collaborativeEngine = collabEngine,
            neuralEngine = neuralEngine,
            experimentManager = expManager
        )

        val liked = (1..8).map { i ->
            Track(
                videoId = "liked_ml_$i",
                title = "Malayalam Melody Song $i",
                artist = if (i % 2 == 0) "Sushin Shyam" else "Vineeth Sreenivasan",
                durationSeconds = 220
            )
        }
        val recent = (1..8).map { i ->
            Track(
                videoId = "recent_hi_$i",
                title = "Hindi Romantic Song $i",
                artist = "Arijit Singh",
                durationSeconds = 210
            )
        }
        val relatedCandidates = (1..24).map { i ->
            Track(
                videoId = "cand_$i",
                title = when {
                    i <= 10 -> "Puthiya Nila Melody $i"
                    i <= 18 -> "Dil Pyar Romantic $i"
                    else -> "Indie Midnight Chill Lo-fi $i"
                },
                artist = when {
                    i <= 4 -> "Sushin Shyam"
                    i <= 12 -> "KS Harisankar $i"
                    i <= 18 -> "Arijit Singh"
                    else -> "Prateek Kuhad $i"
                },
                durationSeconds = 205 + i
            )
        }

        val output = pipeline.execute(
            accountId = "user_e2e_test",
            likedTracks = liked,
            recentTracks = recent,
            playlistTracks = emptyList(),
            frequentSignals = liked.map { Recommender.PlayedSignal(it.videoId, it.artist, it.title, plays = 5) },
            savedArtists = listOf("Sushin Shyam"),
            sessionSignals = liked.map {
                Recommender.SessionSignal(
                    videoId = it.videoId,
                    artist = it.artist,
                    title = it.title,
                    completionRate = 0.92,
                    skipRate = 0.05,
                    recentPlays7d = 4,
                    lastPlayedAtMs = 1_700_000_000_000L
                )
            },
            relatedBySeed = mapOf("liked_ml_1" to relatedCandidates),
            activeSeeds = listOf("liked_ml_1"),
            shownVideoIds = emptySet(),
            shownCounts = emptyMap(),
            hourOfDay = 21,
            preferredContentLanguage = "Malayalam",
            languageOverrides = emptyMap(),
            pauseListenHistory = false,
            engineDao = fakeDao,
            nowMs = 1_700_000_100_000L
        )

        assertTrue("Pipeline must produce multiple distinct recommendation mixes", output.mixes.size >= 4)
        assertTrue("Pipeline must record per-candidate score diagnostics", output.diagnostics.isNotEmpty())
        val mixIds = output.mixes.map { it.id }.toSet()
        assertTrue("Must include Made for You", "for-you" in mixIds)
        assertTrue("Must include On Repeat", "on-repeat" in mixIds)
        assertTrue("Must include More Like <Track>", mixIds.any { it.startsWith("more-like-track-") })
        assertTrue("Must include Exploration (Sound Horizon)", "exploration" in mixIds)

        // Verify Room feature cache and user preference profile were persisted
        val cachedFeature = fakeDao.getTrackFeature("cand_1")
        assertNotNull("Candidate features must be persisted in Room track_features", cachedFeature)
        val cachedProfile = fakeDao.getUserProfile("user_e2e_test")
        assertNotNull("User 32-D preference profile must be persisted in Room", cachedProfile)
    }

    private class FakeRecommendationEngineDao : RecommendationEngineDao {
        private val features = mutableMapOf<String, TrackFeatureEntity>()
        private val profiles = mutableMapOf<String, UserPreferenceProfileEntity>()
        private val assignments = mutableMapOf<String, ExperimentAssignmentEntity>()
        private val interactions = mutableListOf<CollaborativeInteractionEntity>()
        private var nextInteractionId = 1L

        override suspend fun upsertTrackFeature(feature: TrackFeatureEntity) {
            features[feature.videoId] = feature
        }

        override suspend fun upsertTrackFeatures(features: List<TrackFeatureEntity>) {
            for (f in features) this.features[f.videoId] = f
        }

        override suspend fun getTrackFeature(videoId: String): TrackFeatureEntity? = features[videoId]

        override suspend fun getTrackFeatures(videoIds: List<String>): List<TrackFeatureEntity> =
            videoIds.mapNotNull { features[it] }

        override suspend fun pruneTrackFeaturesBefore(before: Long): Int {
            val toRemove = features.values.filter { it.updatedAt < before }.map { it.videoId }
            toRemove.forEach { features.remove(it) }
            return toRemove.size
        }

        override suspend fun upsertUserProfile(profile: UserPreferenceProfileEntity) {
            profiles[profile.accountId] = profile
        }

        override suspend fun getUserProfile(accountId: String): UserPreferenceProfileEntity? = profiles[accountId]

        override suspend fun deleteUserProfile(accountId: String) {
            profiles.remove(accountId)
        }

        override suspend fun upsertExperimentAssignment(assignment: ExperimentAssignmentEntity) {
            assignments[assignment.accountId] = assignment
        }

        override suspend fun getExperimentAssignment(accountId: String): ExperimentAssignmentEntity? =
            assignments[accountId]

        override suspend fun insertCollaborativeInteraction(interaction: CollaborativeInteractionEntity): Long {
            val id = nextInteractionId++
            interactions.add(interaction.copy(id = id))
            return id
        }

        override suspend fun insertCollaborativeInteractions(interactions: List<CollaborativeInteractionEntity>) {
            for (i in interactions) insertCollaborativeInteraction(i)
        }

        override suspend fun getRecentCollaborativeInteractions(since: Long, limit: Int): List<CollaborativeInteractionEntity> =
            interactions.filter { it.timestampMs >= since }.sortedByDescending { it.timestampMs }.take(limit)

        override suspend fun deleteCollaborativeInteractionsForUser(anonUserHash: String) {
            interactions.removeAll { it.anonUserHash == anonUserHash }
        }

        override suspend fun pruneCollaborativeInteractionsBefore(before: Long): Int {
            val beforeSize = interactions.size
            interactions.removeAll { it.timestampMs < before }
            return beforeSize - interactions.size
        }
    }
}
