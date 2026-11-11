package com.geekify.android.domain.recommend.engine

import com.geekify.android.data.local.CollaborativeInteractionEntity
import com.geekify.android.data.local.RecommendationEngineDao
import com.geekify.android.data.local.TrackFeatureEntity
import com.geekify.android.data.local.UserPreferenceProfileEntity
import com.geekify.android.data.model.Mix
import com.geekify.android.data.model.Track
import com.geekify.android.domain.recommend.Recommender
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random

data class CandidateScoreDiagnostics(
    val videoId: String,
    val title: String,
    val artist: String,
    val language: String?,
    val primaryGenre: String?,
    val primaryMood: String?,
    val experimentGroup: ExperimentTreatmentGroup,
    val modelVersion: String,
    val finalScore: Double,
    val neuralProbability: Double,
    val features: NeuralRankingFeatures,
    val isFallbackModel: Boolean = true,
    val audioFeatureSource: EmbeddingFeatureSource = EmbeddingFeatureSource.METADATA_STRUCTURAL_PROJECTION,
    val lyricsFeatureSource: LyricsRepresentationSource = LyricsRepresentationSource.METADATA_LEXICON_PROJECTION
)

data class HybridPipelineOutput(
    val mixes: List<Mix>,
    val mixSources: Map<String, Map<String, String>>,
    val diagnostics: Map<String, CandidateScoreDiagnostics>,
    val experimentGroup: ExperimentTreatmentGroup,
    val modelVersion: String,
    val latencyMs: Long,
    val modelMetadata: ModelVersionMetadata = ModelVersionMetadata(
        modelVersion = modelVersion,
        trainedAtMs = 0L,
        trainSamples = 0,
        validationLoss = 0.0,
        validationAccuracy = 0.0,
        isFallback = true
    ),
    val isFallbackModel: Boolean = modelMetadata.isFallback,
    val featureCacheHits: Int = 0,
    val featureCacheMisses: Int = 0
)

/**
 * Orchestrates the 6-stage hybrid recommendation pipeline:
 * Stage 1: Multi-source candidate normalization & deduplication
 * Stage 2: Candidate quality & variant filtering
 * Stage 3: Multi-modal feature generation (Collaborative, Two-Tower Neural, Audio 32-D, Lyrics Semantic 32-D, Genre/Mood, Context)
 * Stage 4: Treatment-aware personalized ranking (Control, Treatment A Content, Treatment B Collaborative, Treatment C Neural MLP)
 * Stage 5: Taste-cluster-aware MMR diversification
 * Stage 6: Construction of 8 distinct recommendation mix products (Made for You, Language & Genre/Mood Daily Mixes,
 *          Fresh Finds, More Like <Track>, More Like <Artist>, On Repeat, Repeat Rewind, and Exploration).
 */
@Singleton
class HybridRecommendationPipeline @Inject constructor(
    private val classificationService: MusicClassificationService,
    private val contextEngine: RecommendationContextEngine,
    private val audioEmbeddingService: AudioEmbeddingService,
    private val lyricsSemanticService: LyricsSemanticService,
    private val collaborativeEngine: CollaborativeRecommendationEngine,
    private val neuralEngine: NeuralRecommendationEngine,
    private val experimentManager: RecommendationExperimentManager
) {
    companion object {
        const val FEATURE_CACHE_SCHEMA_VERSION = "v2.5.9-feat-1"
        const val FEATURE_CACHE_TTL_MS = 30L * 24 * 3600_000L
        const val NEURAL_MODEL_STATE_PREFIX = "__neural_model_state__:"
        private const val MAX_MEMORY_CACHE_ENTRIES = 1_000
        private const val SQLITE_BATCH_CHUNK_SIZE = 400

        fun formatVersionedFeatureSource(source: EmbeddingFeatureSource): String =
            "${source.name}@$FEATURE_CACHE_SCHEMA_VERSION"

        fun isSupportedFeatureSourceVersion(rawSource: String): Boolean {
            if (rawSource.isBlank()) return false
            if (rawSource.endsWith("@$FEATURE_CACHE_SCHEMA_VERSION")) return true
            return rawSource == EmbeddingFeatureSource.METADATA_STRUCTURAL_PROJECTION.name ||
                rawSource == EmbeddingFeatureSource.AUTHORIZED_AUDIO_ANALYSIS.name
        }

        fun parseEmbeddingFeatureSource(rawSource: String): EmbeddingFeatureSource {
            val prefix = rawSource.substringBefore('@')
            return runCatching { EmbeddingFeatureSource.valueOf(prefix) }
                .getOrDefault(EmbeddingFeatureSource.METADATA_STRUCTURAL_PROJECTION)
        }
    }

    private data class ResolvedTrackFeatureBundle(
        val videoId: String,
        val classification: TrackClassification,
        val audioEmbedding: AudioEmbeddingResult,
        val lyricsEmbedding: FloatArray,
        val entity: TrackFeatureEntity,
        val fromCache: Boolean
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val parsedFeatureMemoryCache = ConcurrentHashMap<String, ResolvedTrackFeatureBundle>()
    private val totalComputationCounter = AtomicInteger(0)

    /** Total number of expensive track feature computations performed by this instance (for diagnostics & tests). */
    fun totalFeatureComputations(): Int = totalComputationCounter.get()

    fun resetFeatureComputationCounter() {
        totalComputationCounter.set(0)
        parsedFeatureMemoryCache.clear()
    }

    suspend fun execute(
        accountId: String?,
        likedTracks: List<Track>,
        recentTracks: List<Track>,
        playlistTracks: List<Track>,
        frequentSignals: List<Recommender.PlayedSignal>,
        savedArtists: List<String>,
        sessionSignals: List<Recommender.SessionSignal>,
        relatedBySeed: Map<String, List<Track>>,
        activeSeeds: List<String>,
        shownVideoIds: Set<String>,
        shownCounts: Map<String, Int>,
        hourOfDay: Int?,
        preferredContentLanguage: String?,
        languageOverrides: Map<String, String>,
        pauseListenHistory: Boolean = false,
        engineDao: RecommendationEngineDao? = null,
        nowMs: Long = System.currentTimeMillis(),
        random: Random = Random.Default
    ): HybridPipelineOutput {
        currentCoroutineContext().ensureActive()
        val startNs = System.nanoTime()
        val treatmentGroup = experimentManager.getOrAssignGroup(accountId, dao = engineDao)

        // Restore persisted trained neural model if currently on untrained fallback
        val modelStateKey = "$NEURAL_MODEL_STATE_PREFIX${accountId?.trim()?.ifEmpty { "guest" } ?: "guest"}"
        if (engineDao != null && neuralEngine.isUsingFallbackModel()) {
            runCatching {
                val savedModelRow = engineDao.getUserProfile(modelStateKey)
                if (savedModelRow != null && savedModelRow.tasteVectorJson.isNotBlank()) {
                    neuralEngine.restoreTrainedModelJson(savedModelRow.tasteVectorJson)
                }
            }
        }

        // 1. Build base behavioural signals & language affinity via Recommender
        fun signal(t: Track) = Recommender.Signal(t.videoId, t.artist, t.title)
        val likedSignals = likedTracks.take(40).map(::signal)
        val recentSignals = recentTracks.take(30).map(::signal)
        val playlistSignals = playlistTracks.take(60).map(::signal)

        val languageProfile = Recommender.languageAffinity(
            liked = likedSignals,
            recent = recentSignals,
            frequent = frequentSignals,
            playlist = playlistSignals,
            nowMs = nowMs,
            languageOverrides = languageOverrides
        )
        val (weights, meta, artistAffinity) = Recommender.seedWeights(
            liked = likedSignals,
            recent = recentSignals,
            frequent = frequentSignals,
            playlist = playlistSignals,
            savedArtists = savedArtists,
            languageAffinity = languageProfile.affinity,
            sessions = sessionSignals,
            nowMs = nowMs,
            languageOverrides = languageOverrides
        )

        // Pre-collect all unique tracks that may need features in this execution pass
        val allUserTracks = (likedTracks.take(25) + recentTracks.take(20) + playlistTracks.take(20))
            .distinctBy { it.videoId }
        val allCandidatesById = relatedBySeed.values.flatten().associateBy { it.videoId }
        val leadFavoriteTrack = likedTracks.firstOrNull() ?: recentTracks.firstOrNull()

        val uniqueTracksForFeatureLookup = LinkedHashMap<String, Track>()
        for (t in allUserTracks) uniqueTracksForFeatureLookup.putIfAbsent(t.videoId, t)
        for ((vid, t) in allCandidatesById) uniqueTracksForFeatureLookup.putIfAbsent(vid, t)
        if (leadFavoriteTrack != null) {
            uniqueTracksForFeatureLookup.putIfAbsent(leadFavoriteTrack.videoId, leadFavoriteTrack)
        }

        // Batch-read cached track features from Room before computing
        val cachedEntitiesById = HashMap<String, TrackFeatureEntity>()
        if (engineDao != null && uniqueTracksForFeatureLookup.isNotEmpty()) {
            val allIds = uniqueTracksForFeatureLookup.keys.toList()
            for (chunk in allIds.chunked(SQLITE_BATCH_CHUNK_SIZE)) {
                currentCoroutineContext().ensureActive()
                val rows = runCatching { engineDao.getTrackFeatures(chunk) }.getOrDefault(emptyList())
                for (row in rows) {
                    cachedEntitiesById[row.videoId] = row
                }
            }
        }

        val resolvedByVideoId = HashMap<String, ResolvedTrackFeatureBundle>(uniqueTracksForFeatureLookup.size)
        val newlyComputedEntities = ArrayList<TrackFeatureEntity>()
        var cacheHits = 0
        var cacheMisses = 0

        suspend fun resolveTrackFeatures(track: Track): ResolvedTrackFeatureBundle {
            currentCoroutineContext().ensureActive()
            resolvedByVideoId[track.videoId]?.let { return it }

            val cachedEntity = cachedEntitiesById[track.videoId]
            if (cachedEntity != null) {
                val validated = validateAndDecodeCachedFeature(track, cachedEntity, nowMs)
                if (validated != null) {
                    cacheHits++
                    resolvedByVideoId[track.videoId] = validated
                    return validated
                }
            }

            // Cache miss or invalidated entry: compute once and record for persistence
            cacheMisses++
            totalComputationCounter.incrementAndGet()
            val cls = classificationService.classify(track.title, track.artist, track.album, track.durationSeconds)
            val audioEmb = audioEmbeddingService.embedTrack(track, classification = cls)
            val lyricsEmb = lyricsSemanticService.extractSemanticVector(track.title, track.artist, track.album)
            val resolvedLang = Recommender.resolveLanguage(track.videoId, track.title, track.artist, languageOverrides)
            val identity = TrackIdentityNormalizer.normalize(track)
            val versionedSource = formatVersionedFeatureSource(audioEmb.source)

            val newEntity = TrackFeatureEntity(
                videoId = track.videoId,
                normalizedIdentity = identity.canonicalKey,
                recordingVariant = identity.variant.name,
                language = resolvedLang,
                languageConfidence = 1.0,
                genresJson = json.encodeToString(cls.genres.mapKeys { it.key.name }),
                moodsJson = json.encodeToString(cls.moods.mapKeys { it.key.name }),
                audioEmbeddingJson = json.encodeToString(audioEmb.vector.toList()),
                lyricsEmbeddingJson = json.encodeToString(lyricsEmb.toList()),
                featureSource = versionedSource,
                tempoBpm = audioEmb.inferredTempoBpm,
                energy = audioEmb.inferredEnergy,
                acousticness = audioEmb.inferredAcousticness,
                valence = 0.55,
                updatedAt = nowMs
            )

            val bundle = ResolvedTrackFeatureBundle(
                videoId = track.videoId,
                classification = cls,
                audioEmbedding = audioEmb,
                lyricsEmbedding = lyricsEmb,
                entity = newEntity,
                fromCache = false
            )
            resolvedByVideoId[track.videoId] = bundle
            newlyComputedEntities.add(newEntity)
            putInMemoryFeatureCache(newEntity, bundle.copy(fromCache = true))
            return bundle
        }

        // 2. Build user content taste vectors (Audio 32-D, Lyrics Semantic 32-D, Genre & Mood distributions)
        val userAudioEmbeddings = mutableListOf<Pair<FloatArray, Double>>()
        val userLyricsEmbeddings = mutableListOf<Pair<FloatArray, Double>>()
        val userGenreWeights = mutableMapOf<MusicGenre, Double>()
        val userMoodWeights = mutableMapOf<MusicMood, Double>()

        for (t in allUserTracks) {
            val w = (weights[t.videoId] ?: 1.0).coerceAtLeast(0.1)
            val resolved = resolveTrackFeatures(t)
            userAudioEmbeddings.add(resolved.audioEmbedding.vector to w)
            userLyricsEmbeddings.add(resolved.lyricsEmbedding to w)
            resolved.classification.genres.forEach { (g, conf) ->
                userGenreWeights[g] = (userGenreWeights[g] ?: 0.0) + w * conf
            }
            resolved.classification.moods.forEach { (m, conf) ->
                userMoodWeights[m] = (userMoodWeights[m] ?: 0.0) + w * conf
            }
        }

        val totalGenreW = userGenreWeights.values.sum().takeIf { it > 0.0 } ?: 1.0
        val normGenreAffinities = userGenreWeights.mapValues { it.value / totalGenreW }
        val totalMoodW = userMoodWeights.values.sum().takeIf { it > 0.0 } ?: 1.0
        val normMoodAffinities = userMoodWeights.mapValues { it.value / totalMoodW }

        val userAudioTasteVector = audioEmbeddingService.buildUserAudioTasteVector(userAudioEmbeddings)
        val userLyricsTasteVector = audioEmbeddingService.buildUserAudioTasteVector(userLyricsEmbeddings)
        val rawUserCompositeVector = combineAudioAndSemanticVectors(userAudioTasteVector, userLyricsTasteVector)

        // 3. Update, persist & query Collaborative Filtering Engine (respecting privacy / pauseListenHistory)
        val anonUserHash = collaborativeEngine.anonymizeUserId(accountId)
        if (!pauseListenHistory && engineDao != null) {
            val deduplicatedCollabMap = LinkedHashMap<String, CollaborativeInteractionEntity>()
            likedTracks.take(15).forEach { t ->
                val entity = CollaborativeInteractionEntity(
                    anonUserHash = anonUserHash,
                    videoId = t.videoId,
                    artistKey = t.artist.lowercase(Locale.ROOT).trim(),
                    interactionType = CollaborativeInteractionType.LIKED.name,
                    weight = CollaborativeInteractionType.LIKED.baseWeight,
                    timestampMs = nowMs
                )
                deduplicatedCollabMap["${entity.videoId}:${entity.interactionType}"] = entity
            }
            sessionSignals.take(25).forEach { s ->
                val type = when {
                    s.completionRate >= 0.75 -> CollaborativeInteractionType.COMPLETED
                    s.skipRate >= 0.65 -> CollaborativeInteractionType.SKIPPED_EARLY
                    else -> CollaborativeInteractionType.MEANINGFUL_PLAY
                }
                val entity = CollaborativeInteractionEntity(
                    anonUserHash = anonUserHash,
                    videoId = s.videoId,
                    artistKey = s.artist.lowercase(Locale.ROOT).trim(),
                    interactionType = type.name,
                    weight = type.baseWeight,
                    timestampMs = s.lastPlayedAtMs.takeIf { it > 0L } ?: nowMs
                )
                deduplicatedCollabMap["${entity.videoId}:${entity.interactionType}"] = entity
            }
            val newCollabRows = deduplicatedCollabMap.values.toList()
            runCatching {
                if (newCollabRows.isNotEmpty()) {
                    engineDao.replaceUserCollaborativeInteractions(anonUserHash, newCollabRows)
                }
                val recentInteractions = engineDao.getRecentCollaborativeInteractions(
                    nowMs - 30L * 24 * 3600_000L,
                    2_000
                )
                val combinedSource = if (recentInteractions.isNotEmpty()) {
                    recentInteractions
                } else {
                    newCollabRows
                }
                val combinedInteractions = collaborativeEngine.fromRoomEntities(combinedSource)
                if (combinedInteractions.isNotEmpty()) {
                    collaborativeEngine.train(combinedInteractions, minCohortListeners = 1)
                }
            }
        }

        // Optional chronological training from real logged user session outcomes when enough real examples exist
        val allKnownTracksById = (likedTracks + recentTracks + playlistTracks + allCandidatesById.values)
            .associateBy { it.videoId }
        if (!pauseListenHistory && sessionSignals.size >= 8 && neuralEngine.isUsingFallbackModel()) {
            val trainingExamples = buildRealChronologicalTrainingExamples(
                sessionSignals = sessionSignals,
                knownTracksById = allKnownTracksById,
                rawUserCompositeVector = rawUserCompositeVector,
                userAudioTasteVector = userAudioTasteVector,
                userLyricsTasteVector = userLyricsTasteVector,
                artistAffinity = artistAffinity,
                languageAffinity = languageProfile.affinity,
                normGenreAffinities = normGenreAffinities,
                normMoodAffinities = normMoodAffinities,
                hourOfDay = hourOfDay,
                languageOverrides = languageOverrides,
                resolveTrackFeatures = ::resolveTrackFeatures
            )
            if (trainingExamples.size >= 6) {
                val promotedMeta = neuralEngine.trainAndValidateChronologically(
                    examples = trainingExamples,
                    newVersionTag = "v2.5.9-neural-trained-${nowMs / 1000L}"
                )
                if (engineDao != null && promotedMeta.isTrained) {
                    val exportedJson = neuralEngine.exportTrainedModelJson()
                    if (!exportedJson.isNullOrBlank()) {
                        runCatching {
                            engineDao.upsertUserProfile(
                                UserPreferenceProfileEntity(
                                    accountId = modelStateKey,
                                    tasteVectorJson = exportedJson,
                                    artistAffinitiesJson = "{}",
                                    genreAffinitiesJson = "{}",
                                    moodAffinitiesJson = "{}",
                                    languageAffinitiesJson = "{}",
                                    contextAffinitiesJson = "{}",
                                    negativeSignalsJson = "{}",
                                    explorationTolerance = 0.25,
                                    modelVersion = promotedMeta.modelVersion,
                                    updatedAt = nowMs
                                )
                            )
                        }
                    }
                }
            }
        }

        val activeModelMetadata = neuralEngine.currentModelMetadata()
        val modelVersion = activeModelMetadata.modelVersion
        val isFallbackModel = activeModelMetadata.isFallback || !activeModelMetadata.isTrained
        val userTowerVector = neuralEngine.computeUserTowerEmbedding(rawUserCompositeVector)

        // 4. Run base Recommender.buildMixes for Control compatibility and base candidate pool
        val discoveryExclude = (likedTracks + recentTracks + playlistTracks).map { it.videoId }.toMutableSet().apply {
            addAll(frequentSignals.map { it.videoId })
        }
        val heavilySkippedIds = sessionSignals
            .filter { it.skipRate >= 0.6 && it.completionRate < 0.35 }
            .map { it.videoId }
            .toSet()
        val strictExclude = recentTracks.take(5).map { it.videoId }.toSet() + heavilySkippedIds

        val (baseMixResults, _) = Recommender.buildMixes(
            liked = likedSignals,
            recent = recentSignals,
            related = relatedBySeed,
            videoId = { it.videoId },
            artist = { it.artist },
            artistDisplay = { it.artist },
            title = { it.title },
            thumbs = { it.thumbnails },
            duration = { it.duration },
            durationSeconds = { it.durationSeconds },
            explicit = { it.explicit },
            type = { it.type },
            frequent = frequentSignals,
            playlist = playlistSignals,
            savedArtists = savedArtists,
            exclude = discoveryExclude,
            shown = shownVideoIds,
            shownCounts = shownCounts,
            album = { it.album },
            seeds = activeSeeds,
            sessions = sessionSignals,
            hourOfDay = hourOfDay,
            preferredContentLanguage = preferredContentLanguage,
            nowMs = nowMs,
            languageOverrides = languageOverrides,
            random = random
        )

        // 5. Stage 3 & Stage 4: Compute multi-modal features and neural/hybrid scores for all candidate tracks
        val sessionByVideoId = sessionSignals.associateBy { it.videoId }
        val totalArtistAff = artistAffinity.values.sum().takeIf { it > 0.0 } ?: 1.0
        val currentContext = contextEngine.inferContextFromHour(hourOfDay)
        val userPositiveIds = (likedTracks.map { it.videoId } + frequentSignals.map { it.videoId }).toSet()
        val userTopArtists = artistAffinity.entries.sortedByDescending { it.value }.take(15).map { it.key }.toSet()

        val diagnostics = mutableMapOf<String, CandidateScoreDiagnostics>()
        val candidateClassifications = mutableMapOf<String, TrackClassification>()
        val candidateAudioEmbs = mutableMapOf<String, AudioEmbeddingResult>()
        val candidateLyricsEmbs = mutableMapOf<String, FloatArray>()

        for ((vid, track) in allCandidatesById) {
            currentCoroutineContext().ensureActive()
            if (vid in discoveryExclude) continue
            val resolved = resolveTrackFeatures(track)
            val cls = resolved.classification
            val audioEmb = resolved.audioEmbedding
            val lyricsEmb = resolved.lyricsEmbedding
            val itemTowerVec = neuralEngine.computeItemTowerEmbedding(
                combineAudioAndSemanticVectors(audioEmb.vector, lyricsEmb)
            )

            candidateClassifications[vid] = cls
            candidateAudioEmbs[vid] = audioEmb
            candidateLyricsEmbs[vid] = lyricsEmb

            val twoTowerSim = neuralEngine.computeTwoTowerSimilarity(userTowerVector, itemTowerVec)
            val audioSim = ((audioEmbeddingService.cosineSimilarity(userAudioTasteVector, audioEmb.vector) + 1.0) / 2.0).coerceIn(0.0, 1.0)
            val lyricsSim = ((lyricsSemanticService.semanticSimilarity(userLyricsTasteVector, lyricsEmb) + 1.0) / 2.0).coerceIn(0.0, 1.0)
            val aKey = track.artist.lowercase(Locale.ROOT).trim()
            val artAff = min(1.0, ((artistAffinity[aKey] ?: 0.0) / totalArtistAff) * 3.0)
            val resolvedLang = Recommender.resolveLanguage(vid, track.title, track.artist, languageOverrides)
            val langAff = resolvedLang?.let { languageProfile.affinity[it] } ?: 0.0
            val genreAff = cls.genres.entries.sumOf { (g, c) -> c * (normGenreAffinities[g] ?: 0.0) }.coerceIn(0.0, 1.0)
            val moodAff = cls.moods.entries.sumOf { (m, c) -> c * (normMoodAffinities[m] ?: 0.0) }.coerceIn(0.0, 1.0)
            val ctxMult = contextEngine.contextAlignmentMultiplier(cls, currentContext, normGenreAffinities)
            val collabScore = collaborativeEngine.scoreCandidate(
                anonUserHash = anonUserHash,
                userPositiveVideoIds = userPositiveIds,
                userTopArtists = userTopArtists,
                candidateVideoId = vid,
                candidateArtistKey = aKey
            )
            val isUnheardArtist = aKey.isNotEmpty() && aKey !in artistAffinity
            val novelty = if (isUnheardArtist) 0.85 else 0.25
            val sess = sessionByVideoId[vid]
            val compRate = sess?.completionRate ?: 0.60
            val skipPen = sess?.skipRate ?: 0.0
            val expCount = shownCounts[vid] ?: if (vid in shownVideoIds) 1 else 0
            val expPenalty = (1.0 - 1.0 / (1.0 + expCount * 0.5)).coerceIn(0.0, 1.0)

            val features = NeuralRankingFeatures(
                twoTowerSimilarity = twoTowerSim,
                collaborativeScore = collabScore,
                artistAffinity = artAff,
                languageAffinity = langAff,
                audioSimilarity = audioSim,
                lyricsSimilarity = lyricsSim,
                genreAffinity = genreAff,
                moodAffinity = moodAff,
                contextAlignment = (ctxMult - 0.90).coerceIn(0.0, 1.0),
                noveltyScore = novelty,
                popularityPrior = 0.65,
                freshnessScore = 0.70,
                candidateSourceConfidence = 0.90,
                historicalCompletionRate = compRate,
                historicalSkipPenalty = skipPen,
                recentExposurePenalty = expPenalty
            )
            val neuralProb = neuralEngine.predictOutcomeProbability(features)

            val finalScore = when (treatmentGroup) {
                ExperimentTreatmentGroup.CONTROL -> {
                    0.50 * (artAff + langAff) + 0.30 * compRate + 0.20 * ctxMult - 0.40 * expPenalty
                }
                ExperimentTreatmentGroup.TREATMENT_A_CONTENT -> {
                    0.35 * audioSim + 0.25 * lyricsSim + 0.20 * (genreAff + moodAff) + 0.20 * langAff - 0.35 * expPenalty
                }
                ExperimentTreatmentGroup.TREATMENT_B_COLLAB -> {
                    0.45 * collabScore + 0.30 * (artAff + langAff) + 0.25 * audioSim - 0.35 * expPenalty
                }
                ExperimentTreatmentGroup.TREATMENT_C_NEURAL -> {
                    0.55 * neuralProb + 0.20 * twoTowerSim + 0.15 * (audioSim + lyricsSim) * 0.5 + 0.10 * collabScore
                }
            }

            diagnostics[vid] = CandidateScoreDiagnostics(
                videoId = vid,
                title = track.title,
                artist = track.artist,
                language = resolvedLang,
                primaryGenre = cls.primaryGenre?.displayName,
                primaryMood = cls.primaryMood?.displayName,
                experimentGroup = treatmentGroup,
                modelVersion = modelVersion,
                finalScore = finalScore,
                neuralProbability = neuralProb,
                features = features,
                isFallbackModel = isFallbackModel,
                audioFeatureSource = audioEmb.source,
                lyricsFeatureSource = lyricsSemanticService.resolveRepresentationSource(null)
            )
        }

        // Persist only newly computed / cache-miss track features
        if (engineDao != null && newlyComputedEntities.isNotEmpty()) {
            runCatching { engineDao.upsertTrackFeatures(newlyComputedEntities.take(120)) }
        }
        if (engineDao != null && !accountId.isNullOrBlank() && !pauseListenHistory) {
            runCatching {
                engineDao.upsertUserProfile(
                    UserPreferenceProfileEntity(
                        accountId = accountId,
                        tasteVectorJson = json.encodeToString(userTowerVector.toList()),
                        artistAffinitiesJson = json.encodeToString(artistAffinity.entries.sortedByDescending { it.value }.take(30).associate { it.key to it.value }),
                        genreAffinitiesJson = json.encodeToString(normGenreAffinities.mapKeys { it.key.name }),
                        moodAffinitiesJson = json.encodeToString(normMoodAffinities.mapKeys { it.key.name }),
                        languageAffinitiesJson = json.encodeToString(languageProfile.affinity),
                        contextAffinitiesJson = json.encodeToString(mapOf(currentContext.name to 1.0)),
                        negativeSignalsJson = json.encodeToString(heavilySkippedIds.associateWith { -1.0 }),
                        explorationTolerance = 0.25,
                        modelVersion = modelVersion,
                        updatedAt = nowMs
                    )
                )
            }
        }

        currentCoroutineContext().ensureActive()

        // 6. Stage 6: Build all 8 distinct recommendation mix products
        val anchorTracksPool = (likedTracks + playlistTracks + recentTracks.drop(5))
            .distinctBy { it.videoId }
            .filter { it.videoId !in strictExclude }
            .sortedByDescending { weights[it.videoId] ?: 0.0 }

        val mixResultSources = baseMixResults.associate { it.id to it.sources }.toMutableMap()
        val mixes = baseMixResults.mapNotNull { mr ->
            val rawDiscovery = mr.videoIds.mapNotNull { allCandidatesById[it] ?: allKnownTracksById[it] }
            // Re-rank discovery tracks using treatment-aware hybrid score when not in pure Control
            val discoveryTracks = if (treatmentGroup == ExperimentTreatmentGroup.CONTROL) {
                rawDiscovery
            } else {
                rawDiscovery.sortedByDescending { t -> diagnostics[t.videoId]?.finalScore ?: 0.0 }
            }

            val tracks = when {
                mr.id == "for-you" -> {
                    Recommender.blendAnchorTracks(
                        discoveryTracks = discoveryTracks,
                        anchorCandidates = anchorTracksPool.take(12),
                        videoId = { it.videoId },
                        artist = { it.artist },
                        targetCount = 30,
                        anchorInterval = 4
                    )
                }
                mr.id.startsWith("daily-mix-") -> {
                    val langTag = mr.title.substringAfter("• ").trim()
                    val langAnchors = anchorTracksPool.filter { t ->
                        Recommender.resolveLanguage(t.videoId, t.title, t.artist, languageOverrides)
                            ?.equals(langTag, ignoreCase = true) == true
                    }.take(10)
                    Recommender.blendAnchorTracks(
                        discoveryTracks = discoveryTracks,
                        anchorCandidates = langAnchors,
                        videoId = { it.videoId },
                        artist = { it.artist },
                        targetCount = 24,
                        anchorInterval = 4
                    )
                }
                else -> discoveryTracks
            }
            if (tracks.isNotEmpty()) {
                Mix(
                    id = mr.id,
                    title = mr.title,
                    subtitle = mr.subtitle,
                    tracks = tracks
                )
            } else null
        }.toMutableList()

        // Product 2b: Genre / Mood Taste-Cluster Daily Mix (e.g., "Daily Mix • Lo-fi & Chill" or "Daily Mix • Romantic & Melody")
        val topGenre = normGenreAffinities.entries
            .filter { it.key != MusicGenre.FILM_MUSIC && it.key != MusicGenre.REGIONAL_TRADITIONAL && it.key != MusicGenre.POP }
            .maxByOrNull { it.value }?.key
        if (topGenre != null) {
            val genreCandidates = allCandidatesById.values
                .filter { t -> t.videoId !in discoveryExclude && (candidateClassifications[t.videoId]?.genres?.get(topGenre) ?: 0.0) >= 0.5 }
                .sortedByDescending { t -> diagnostics[t.videoId]?.finalScore ?: 0.0 }
                .distinctBy { it.videoId }
                .take(20)
            if (genreCandidates.size >= 8) {
                val mixId = "daily-mix-genre-${topGenre.name.lowercase(Locale.ROOT)}"
                mixes.add(
                    Mix(
                        id = mixId,
                        title = "Daily Mix • ${topGenre.displayName}",
                        subtitle = "${topGenre.displayName} tracks matched to your taste",
                        tracks = genreCandidates
                    )
                )
            }
        }

        // Product 4: "More Like <Track>" similarity using resolved cached features (no duplicate recomputation)
        if (leadFavoriteTrack != null && allCandidatesById.size >= 10) {
            val seedResolved = resolveTrackFeatures(leadFavoriteTrack)
            val seedAudio = seedResolved.audioEmbedding
            val seedLyrics = seedResolved.lyricsEmbedding
            val deepMatches = allCandidatesById.values
                .filter { it.videoId !in discoveryExclude && it.videoId != leadFavoriteTrack.videoId }
                .map { cand ->
                    val cResolved = resolvedByVideoId[cand.videoId] ?: resolveTrackFeatures(cand)
                    val cAudio = cResolved.audioEmbedding.vector
                    val cLyrics = cResolved.lyricsEmbedding
                    val sim = 0.60 * audioEmbeddingService.cosineSimilarity(seedAudio.vector, cAudio) +
                        0.40 * lyricsSemanticService.semanticSimilarity(seedLyrics, cLyrics)
                    cand to sim
                }
                .sortedByDescending { it.second }
                .map { it.first }
                .distinctBy { it.videoId }
                .take(20)
            if (deepMatches.size >= 8) {
                val cleanSeedTitle = leadFavoriteTrack.title.take(24).trim()
                mixes.add(
                    Mix(
                        id = "more-like-track-${leadFavoriteTrack.videoId}",
                        title = "More like $cleanSeedTitle",
                        subtitle = "Matched by genre, mood, and thematic style",
                        tracks = deepMatches
                    )
                )
            }
        }

        // Product 6: "On Repeat" (heavy-rotation favorites paired with top sonic matches)
        val onRepeatUsedIds = mutableSetOf<String>()
        val onRepeatFavorites = buildList {
            val heavyIds = sessionSignals
                .filter { it.recentPlays7d >= 2 && it.skipRate < 0.5 }
                .sortedByDescending { it.recentPlays7d * (0.5 + it.completionRate) }
                .map { it.videoId }
            val topStatIds = frequentSignals.sortedByDescending { it.plays }.map { it.videoId }
            (heavyIds + topStatIds + recentTracks.take(10).map { it.videoId } + likedTracks.take(10).map { it.videoId })
                .distinct()
                .mapNotNull { allKnownTracksById[it] }
                .take(12)
                .let(::addAll)
        }
        if (onRepeatFavorites.size >= 4) {
            val companionTracks = mixes.firstOrNull { it.id == "for-you" }?.tracks.orEmpty()
                .filter { it.videoId !in onRepeatFavorites.map { f -> f.videoId }.toSet() }
                .take(12)
            val blendedOnRepeat = buildList {
                val maxIdx = maxOf(onRepeatFavorites.size, companionTracks.size)
                for (i in 0 until maxIdx) {
                    onRepeatFavorites.getOrNull(i)?.let(::add)
                    companionTracks.getOrNull(i)?.let(::add)
                }
            }.distinctBy { it.videoId }.take(24)
            if (blendedOnRepeat.size >= 8) {
                onRepeatUsedIds.addAll(blendedOnRepeat.map { it.videoId })
                mixes.add(
                    Mix(
                        id = "on-repeat",
                        title = "On Repeat",
                        subtitle = "Songs you can't stop playing right now",
                        tracks = blendedOnRepeat
                    )
                )
            }
        }

        // Product 7: "Repeat Rewind" (older favorites not in immediate recent session and not duplicated from On Repeat)
        val immediateRecentIds = recentTracks.take(6).map { it.videoId }.toSet() + onRepeatUsedIds.take(8)
        val rewindFavorites = (likedTracks.drop(3) + playlistTracks + recentTracks.drop(8))
            .filter { it.videoId !in immediateRecentIds }
            .distinctBy { it.videoId }
            .take(14)
        if (rewindFavorites.size >= 5) {
            val freshCompanions = allCandidatesById.values
                .filter { it.videoId !in immediateRecentIds }
                .distinctBy { it.videoId }
                .take(10)
            val rewindTracks = (rewindFavorites + freshCompanions).distinctBy { it.videoId }.take(22)
            if (rewindTracks.size >= 8) {
                mixes.add(
                    Mix(
                        id = "repeat-rewind",
                        title = "Repeat Rewind",
                        subtitle = "Past favorites worth spinning again",
                        tracks = rewindTracks
                    )
                )
            }
        }

        // Product 8: "Exploration" (measured cross-language / high-audio-similarity serendipity from unheard artists)
        val explorationTracks = allCandidatesById.values
            .filter { cand ->
                val aKey = cand.artist.lowercase(Locale.ROOT).trim()
                cand.videoId !in discoveryExclude && aKey.isNotEmpty() && aKey !in artistAffinity
            }
            .sortedByDescending { cand ->
                val d = diagnostics[cand.videoId]
                val audioSim = d?.features?.audioSimilarity ?: 0.5
                val lyricsSim = d?.features?.lyricsSimilarity ?: 0.5
                val neural = d?.neuralProbability ?: 0.5
                0.45 * audioSim + 0.35 * lyricsSim + 0.20 * neural
            }
            .distinctBy { it.artist.lowercase(Locale.ROOT).trim() }
            .take(18)
        if (explorationTracks.size >= 8) {
            mixes.add(
                Mix(
                    id = "exploration",
                    title = "Sound Horizon",
                    subtitle = "New artists and cross-border sounds matched to your vibe",
                    tracks = explorationTracks
                )
            )
        }

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        experimentManager.recordLatency(treatmentGroup, elapsedMs)

        return HybridPipelineOutput(
            mixes = mixes,
            mixSources = mixResultSources,
            diagnostics = diagnostics,
            experimentGroup = treatmentGroup,
            modelVersion = modelVersion,
            latencyMs = elapsedMs,
            modelMetadata = activeModelMetadata,
            isFallbackModel = isFallbackModel,
            featureCacheHits = cacheHits,
            featureCacheMisses = cacheMisses
        )
    }

    private fun memoryCacheKey(entity: TrackFeatureEntity): String =
        "${entity.videoId}|${entity.normalizedIdentity}|${entity.updatedAt}|${entity.featureSource}"

    private fun putInMemoryFeatureCache(entity: TrackFeatureEntity, bundle: ResolvedTrackFeatureBundle) {
        if (parsedFeatureMemoryCache.size >= MAX_MEMORY_CACHE_ENTRIES) {
            val firstKey = parsedFeatureMemoryCache.keys.firstOrNull()
            if (firstKey != null) parsedFeatureMemoryCache.remove(firstKey)
        }
        parsedFeatureMemoryCache[memoryCacheKey(entity)] = bundle
    }

    private fun validateAndDecodeCachedFeature(
        track: Track,
        entity: TrackFeatureEntity,
        nowMs: Long
    ): ResolvedTrackFeatureBundle? {
        if (entity.videoId != track.videoId) return null
        if (entity.updatedAt <= 0L || (nowMs - entity.updatedAt) > FEATURE_CACHE_TTL_MS) return null
        if (!isSupportedFeatureSourceVersion(entity.featureSource)) return null

        val expectedIdentity = TrackIdentityNormalizer.normalize(track).canonicalKey
        if (entity.normalizedIdentity != expectedIdentity) return null

        val memKey = memoryCacheKey(entity)
        parsedFeatureMemoryCache[memKey]?.let { return it }

        return runCatching {
            val audioList = json.decodeFromString<List<Float>>(entity.audioEmbeddingJson)
            val lyricsList = json.decodeFromString<List<Float>>(entity.lyricsEmbeddingJson)
            if (audioList.size != AudioEmbeddingService.EMBEDDING_DIM ||
                lyricsList.size != LyricsSemanticService.SEMANTIC_DIM
            ) {
                return null
            }
            val audioVec = audioList.toFloatArray()
            val lyricsVec = lyricsList.toFloatArray()
            if (audioVec.any { !it.isFinite() } || lyricsVec.any { !it.isFinite() }) return null
            if (audioVec.all { it == 0f } || lyricsVec.all { it == 0f }) return null

            val rawGenres = json.decodeFromString<Map<String, Double>>(entity.genresJson)
            val rawMoods = json.decodeFromString<Map<String, Double>>(entity.moodsJson)
            val genres = rawGenres.mapNotNull { (k, v) ->
                runCatching { MusicGenre.valueOf(k) to v }.getOrNull()
            }.toMap()
            val moods = rawMoods.mapNotNull { (k, v) ->
                runCatching { MusicMood.valueOf(k) to v }.getOrNull()
            }.toMap()
            if (genres.isEmpty() && moods.isEmpty()) return null

            val cls = TrackClassification(
                genres = genres,
                moods = moods,
                primaryGenre = genres.maxByOrNull { it.value }?.key,
                primaryMood = moods.maxByOrNull { it.value }?.key
            )
            val audioEmb = AudioEmbeddingResult(
                videoId = entity.videoId,
                vector = audioVec,
                source = parseEmbeddingFeatureSource(entity.featureSource),
                inferredTempoBpm = entity.tempoBpm,
                inferredEnergy = entity.energy,
                inferredAcousticness = entity.acousticness
            )
            val bundle = ResolvedTrackFeatureBundle(
                videoId = entity.videoId,
                classification = cls,
                audioEmbedding = audioEmb,
                lyricsEmbedding = lyricsVec,
                entity = entity,
                fromCache = true
            )
            putInMemoryFeatureCache(entity, bundle)
            bundle
        }.getOrNull()
    }

    private suspend fun buildRealChronologicalTrainingExamples(
        sessionSignals: List<Recommender.SessionSignal>,
        knownTracksById: Map<String, Track>,
        rawUserCompositeVector: FloatArray,
        userAudioTasteVector: FloatArray,
        userLyricsTasteVector: FloatArray,
        artistAffinity: Map<String, Double>,
        languageAffinity: Map<String, Double>,
        normGenreAffinities: Map<MusicGenre, Double>,
        normMoodAffinities: Map<MusicMood, Double>,
        hourOfDay: Int?,
        languageOverrides: Map<String, String>,
        resolveTrackFeatures: suspend (Track) -> ResolvedTrackFeatureBundle
    ): List<TrainingExample> {
        val totalArtistAff = artistAffinity.values.sum().takeIf { it > 0.0 } ?: 1.0
        val currentContext = contextEngine.inferContextFromHour(hourOfDay)
        val examples = ArrayList<TrainingExample>()

        for ((idx, s) in sessionSignals.withIndex()) {
            currentCoroutineContext().ensureActive()
            val track = knownTracksById[s.videoId] ?: Track(
                videoId = s.videoId,
                title = s.title,
                artist = s.artist
            )
            val resolved = resolveTrackFeatures(track)
            val itemRawVec = combineAudioAndSemanticVectors(
                resolved.audioEmbedding.vector,
                resolved.lyricsEmbedding
            )
            val audioSim = ((audioEmbeddingService.cosineSimilarity(userAudioTasteVector, resolved.audioEmbedding.vector) + 1.0) / 2.0)
                .coerceIn(0.0, 1.0)
            val lyricsSim = ((lyricsSemanticService.semanticSimilarity(userLyricsTasteVector, resolved.lyricsEmbedding) + 1.0) / 2.0)
                .coerceIn(0.0, 1.0)
            val aKey = track.artist.lowercase(Locale.ROOT).trim()
            val artAff = min(1.0, ((artistAffinity[aKey] ?: 0.0) / totalArtistAff) * 3.0)
            val resolvedLang = Recommender.resolveLanguage(track.videoId, track.title, track.artist, languageOverrides)
            val langAff = resolvedLang?.let { languageAffinity[it] } ?: 0.0
            val genreAff = resolved.classification.genres.entries
                .sumOf { (g, c) -> c * (normGenreAffinities[g] ?: 0.0) }
                .coerceIn(0.0, 1.0)
            val moodAff = resolved.classification.moods.entries
                .sumOf { (m, c) -> c * (normMoodAffinities[m] ?: 0.0) }
                .coerceIn(0.0, 1.0)
            val ctxMult = contextEngine.contextAlignmentMultiplier(
                resolved.classification,
                currentContext,
                normGenreAffinities
            )
            val label = when {
                s.completionRate >= 0.70 && s.skipRate < 0.35 -> 1.0
                s.skipRate >= 0.65 && s.completionRate < 0.35 -> 0.0
                else -> s.completionRate.coerceIn(0.0, 1.0)
            }
            val rankingFeatures = NeuralRankingFeatures(
                twoTowerSimilarity = (0.5 * audioSim + 0.5 * lyricsSim).coerceIn(0.0, 1.0),
                collaborativeScore = 0.5,
                artistAffinity = artAff,
                languageAffinity = langAff,
                audioSimilarity = audioSim,
                lyricsSimilarity = lyricsSim,
                genreAffinity = genreAff,
                moodAffinity = moodAff,
                contextAlignment = (ctxMult - 0.90).coerceIn(0.0, 1.0),
                noveltyScore = 0.35,
                popularityPrior = 0.65,
                freshnessScore = 0.70,
                candidateSourceConfidence = 0.90,
                historicalCompletionRate = s.completionRate.coerceIn(0.0, 1.0),
                historicalSkipPenalty = s.skipRate.coerceIn(0.0, 1.0),
                recentExposurePenalty = 0.1
            )
            val ts = s.lastPlayedAtMs.takeIf { it > 0L } ?: (1_000_000L + idx * 1_000L)
            examples.add(
                TrainingExample(
                    userTowerInput = rawUserCompositeVector,
                    itemTowerInput = itemRawVec,
                    rankingFeatures = rankingFeatures,
                    outcomeLabel = label,
                    timestampMs = ts
                )
            )
        }
        return examples
    }

    private fun combineAudioAndSemanticVectors(audio: FloatArray, semantic: FloatArray): FloatArray {
        val out = FloatArray(NeuralRecommendationEngine.TOWER_DIM)
        for (i in 0 until 16) {
            out[i] = audio.getOrElse(i * 2) { 0f } * 0.7f + audio.getOrElse(i * 2 + 1) { 0f } * 0.3f
            out[16 + i] = semantic.getOrElse(i * 2) { 0f } * 0.7f + semantic.getOrElse(i * 2 + 1) { 0f } * 0.3f
        }
        return out
    }
}
