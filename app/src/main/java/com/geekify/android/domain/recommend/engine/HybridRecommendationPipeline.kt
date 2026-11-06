package com.geekify.android.domain.recommend.engine

import com.geekify.android.data.local.CollaborativeInteractionEntity
import com.geekify.android.data.local.RecommendationEngineDao
import com.geekify.android.data.local.TrackFeatureEntity
import com.geekify.android.data.local.UserPreferenceProfileEntity
import com.geekify.android.data.model.Mix
import com.geekify.android.data.model.Track
import com.geekify.android.domain.recommend.Recommender
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
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
    val features: NeuralRankingFeatures
)

data class HybridPipelineOutput(
    val mixes: List<Mix>,
    val mixSources: Map<String, Map<String, String>>,
    val diagnostics: Map<String, CandidateScoreDiagnostics>,
    val experimentGroup: ExperimentTreatmentGroup,
    val modelVersion: String,
    val latencyMs: Long
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
    private val json = Json { ignoreUnknownKeys = true }

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
        val startNs = System.nanoTime()
        val treatmentGroup = experimentManager.getOrAssignGroup(accountId, dao = engineDao)
        val modelVersion = neuralEngine.currentModelMetadata().modelVersion

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

        // 2. Build user content taste vectors (Audio 32-D, Lyrics Semantic 32-D, Genre & Mood distributions)
        val allUserTracks = (likedTracks.take(25) + recentTracks.take(20) + playlistTracks.take(20))
            .distinctBy { it.videoId }
        val userAudioEmbeddings = mutableListOf<Pair<FloatArray, Double>>()
        val userLyricsEmbeddings = mutableListOf<Pair<FloatArray, Double>>()
        val userGenreWeights = mutableMapOf<MusicGenre, Double>()
        val userMoodWeights = mutableMapOf<MusicMood, Double>()

        for (t in allUserTracks) {
            val w = (weights[t.videoId] ?: 1.0).coerceAtLeast(0.1)
            val cls = classificationService.classify(t.title, t.artist, t.album, t.durationSeconds)
            val audioEmb = audioEmbeddingService.embedTrack(t, classification = cls)
            val lyricsEmb = lyricsSemanticService.extractSemanticVector(t.title, t.artist, t.album)
            userAudioEmbeddings.add(audioEmb.vector to w)
            userLyricsEmbeddings.add(lyricsEmb to w)
            cls.genres.forEach { (g, conf) -> userGenreWeights[g] = (userGenreWeights[g] ?: 0.0) + w * conf }
            cls.moods.forEach { (m, conf) -> userMoodWeights[m] = (userMoodWeights[m] ?: 0.0) + w * conf }
        }

        val totalGenreW = userGenreWeights.values.sum().takeIf { it > 0.0 } ?: 1.0
        val normGenreAffinities = userGenreWeights.mapValues { it.value / totalGenreW }
        val totalMoodW = userMoodWeights.values.sum().takeIf { it > 0.0 } ?: 1.0
        val normMoodAffinities = userMoodWeights.mapValues { it.value / totalMoodW }

        val userAudioTasteVector = audioEmbeddingService.buildUserAudioTasteVector(userAudioEmbeddings)
        val userLyricsTasteVector = audioEmbeddingService.buildUserAudioTasteVector(userLyricsEmbeddings)
        val userTowerVector = neuralEngine.computeUserTowerEmbedding(
            combineAudioAndSemanticVectors(userAudioTasteVector, userLyricsTasteVector)
        )

        // 3. Update & query Collaborative Filtering Engine (respecting privacy / pauseListenHistory)
        val anonUserHash = collaborativeEngine.anonymizeUserId(accountId)
        if (!pauseListenHistory && engineDao != null) {
            val newCollabRows = buildList {
                likedTracks.take(15).forEach { t ->
                    add(
                        CollaborativeInteractionEntity(
                            anonUserHash = anonUserHash,
                            videoId = t.videoId,
                            artistKey = t.artist.lowercase(Locale.ROOT).trim(),
                            interactionType = CollaborativeInteractionType.LIKED.name,
                            weight = CollaborativeInteractionType.LIKED.baseWeight,
                            timestampMs = nowMs
                        )
                    )
                }
                sessionSignals.take(25).forEach { s ->
                    val type = when {
                        s.completionRate >= 0.75 -> CollaborativeInteractionType.COMPLETED
                        s.skipRate >= 0.65 -> CollaborativeInteractionType.SKIPPED_EARLY
                        else -> CollaborativeInteractionType.MEANINGFUL_PLAY
                    }
                    add(
                        CollaborativeInteractionEntity(
                            anonUserHash = anonUserHash,
                            videoId = s.videoId,
                            artistKey = s.artist.lowercase(Locale.ROOT).trim(),
                            interactionType = type.name,
                            weight = type.baseWeight,
                            timestampMs = s.lastPlayedAtMs.takeIf { it > 0L } ?: nowMs
                        )
                    )
                }
            }
            runCatching {
                val recentInteractions = engineDao.getRecentCollaborativeInteractions(nowMs - 30L * 24 * 3600_000L, 2_000)
                val combinedInteractions = collaborativeEngine.fromRoomEntities(recentInteractions + newCollabRows)
                if (combinedInteractions.isNotEmpty()) {
                    collaborativeEngine.train(combinedInteractions, minCohortListeners = 1)
                }
            }
        }

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
        val allCandidatesById = relatedBySeed.values.flatten().associateBy { it.videoId }
        val sessionByVideoId = sessionSignals.associateBy { it.videoId }
        val totalArtistAff = artistAffinity.values.sum().takeIf { it > 0.0 } ?: 1.0
        val currentContext = contextEngine.inferContextFromHour(hourOfDay)
        val userPositiveIds = (likedTracks.map { it.videoId } + frequentSignals.map { it.videoId }).toSet()
        val userTopArtists = artistAffinity.entries.sortedByDescending { it.value }.take(15).map { it.key }.toSet()

        val diagnostics = mutableMapOf<String, CandidateScoreDiagnostics>()
        val candidateClassifications = mutableMapOf<String, TrackClassification>()
        val candidateAudioEmbs = mutableMapOf<String, AudioEmbeddingResult>()
        val candidateLyricsEmbs = mutableMapOf<String, FloatArray>()
        val trackFeaturesToCache = mutableListOf<TrackFeatureEntity>()

        for ((vid, track) in allCandidatesById) {
            if (vid in discoveryExclude) continue
            val cls = classificationService.classify(track.title, track.artist, track.album, track.durationSeconds)
            val audioEmb = audioEmbeddingService.embedTrack(track, classification = cls)
            val lyricsEmb = lyricsSemanticService.extractSemanticVector(track.title, track.artist, track.album)
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
                features = features
            )

            val identity = TrackIdentityNormalizer.normalize(track)
            trackFeaturesToCache.add(
                TrackFeatureEntity(
                    videoId = vid,
                    normalizedIdentity = identity.canonicalKey,
                    recordingVariant = identity.variant.name,
                    language = resolvedLang,
                    languageConfidence = 1.0,
                    genresJson = json.encodeToString(cls.genres.mapKeys { it.key.name }),
                    moodsJson = json.encodeToString(cls.moods.mapKeys { it.key.name }),
                    audioEmbeddingJson = json.encodeToString(audioEmb.vector.toList()),
                    lyricsEmbeddingJson = json.encodeToString(lyricsEmb.toList()),
                    featureSource = audioEmb.source.name,
                    tempoBpm = audioEmb.inferredTempoBpm,
                    energy = audioEmb.inferredEnergy,
                    acousticness = audioEmb.inferredAcousticness,
                    valence = 0.55,
                    updatedAt = nowMs
                )
            )
        }

        if (engineDao != null && trackFeaturesToCache.isNotEmpty()) {
            runCatching { engineDao.upsertTrackFeatures(trackFeaturesToCache.take(80)) }
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

        // 6. Stage 6: Build all 8 distinct recommendation mix products
        val allKnownTracksById = (likedTracks + recentTracks + playlistTracks + allCandidatesById.values)
            .associateBy { it.videoId }
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

        // Product 4: "More Like <Track>" deep-track similarity using Audio + Lyrics Semantic Embeddings
        val leadFavoriteTrack = likedTracks.firstOrNull() ?: recentTracks.firstOrNull()
        if (leadFavoriteTrack != null && allCandidatesById.size >= 10) {
            val seedAudio = audioEmbeddingService.embedTrack(leadFavoriteTrack)
            val seedLyrics = lyricsSemanticService.extractSemanticVector(leadFavoriteTrack.title, leadFavoriteTrack.artist, leadFavoriteTrack.album)
            val deepMatches = allCandidatesById.values
                .filter { it.videoId !in discoveryExclude && it.videoId != leadFavoriteTrack.videoId }
                .map { cand ->
                    val cAudio = candidateAudioEmbs[cand.videoId]?.vector ?: audioEmbeddingService.embedTrack(cand).vector
                    val cLyrics = candidateLyricsEmbs[cand.videoId] ?: lyricsSemanticService.extractSemanticVector(cand.title, cand.artist)
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
                        subtitle = "Matched by musical sound and lyrical mood",
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
            latencyMs = elapsedMs
        )
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
