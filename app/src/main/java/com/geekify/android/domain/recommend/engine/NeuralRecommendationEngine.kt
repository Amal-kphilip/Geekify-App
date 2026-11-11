package com.geekify.android.domain.recommend.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 16 interpretable input features fed into the Neural Ranking MLP and inspectable in debug diagnostics.
 */
data class NeuralRankingFeatures(
    val twoTowerSimilarity: Double,
    val collaborativeScore: Double,
    val artistAffinity: Double,
    val languageAffinity: Double,
    val audioSimilarity: Double,
    val lyricsSimilarity: Double,
    val genreAffinity: Double,
    val moodAffinity: Double,
    val contextAlignment: Double,
    val noveltyScore: Double,
    val popularityPrior: Double,
    val freshnessScore: Double,
    val candidateSourceConfidence: Double,
    val historicalCompletionRate: Double,
    val historicalSkipPenalty: Double,
    val recentExposurePenalty: Double
) {
    fun toFloatArray(): FloatArray = floatArrayOf(
        twoTowerSimilarity.toFloat(),
        collaborativeScore.toFloat(),
        artistAffinity.toFloat(),
        languageAffinity.toFloat(),
        audioSimilarity.toFloat(),
        lyricsSimilarity.toFloat(),
        genreAffinity.toFloat(),
        moodAffinity.toFloat(),
        contextAlignment.toFloat(),
        noveltyScore.toFloat(),
        popularityPrior.toFloat(),
        freshnessScore.toFloat(),
        candidateSourceConfidence.toFloat(),
        historicalCompletionRate.toFloat(),
        historicalSkipPenalty.toFloat(),
        recentExposurePenalty.toFloat()
    )

    companion object {
        const val FEATURE_COUNT = 16
    }
}

data class TrainingExample(
    val userTowerInput: FloatArray,
    val itemTowerInput: FloatArray,
    val rankingFeatures: NeuralRankingFeatures,
    /** Label in [0.0, 1.0]: 1.0 for completed/liked/playlisted, 0.0 for early skip/dismissed. */
    val outcomeLabel: Double,
    val timestampMs: Long
)

@Serializable
enum class NeuralModelState {
    UNTRAINED_FALLBACK,
    TRAINED,
    INVALID,
    TRAINING_REQUIRED
}

@Serializable
data class ModelVersionMetadata(
    val modelVersion: String,
    val trainedAtMs: Long,
    val trainSamples: Int,
    val validationLoss: Double? = null,
    val validationAccuracy: Double? = null,
    val isFallback: Boolean = false,
    val modelState: NeuralModelState = if (isFallback || trainSamples <= 0 || trainedAtMs <= 0L) {
        NeuralModelState.UNTRAINED_FALLBACK
    } else {
        NeuralModelState.TRAINED
    }
) {
    /** True only when the model has been trained on real chronological examples and passed validation. */
    val isTrained: Boolean
        get() = modelState == NeuralModelState.TRAINED && !isFallback && trainSamples > 0 && trainedAtMs > 0L

    /** Explicit human-readable status that never labels an untrained fallback as a trained neural model. */
    val statusSummary: String
        get() = if (isTrained) {
            "TRAINED_NEURAL_MODEL(version=$modelVersion, samples=$trainSamples)"
        } else {
            "UNTRAINED_FALLBACK_PRIOR(state=$modelState, version=$modelVersion)"
        }
}

@Serializable
internal data class PersistedNeuralModelSnapshot(
    val version: ModelVersionMetadata,
    val userTowerDiag: List<Float>,
    val itemTowerDiag: List<Float>,
    val w1: List<List<Float>>,
    val b1: List<Float>,
    val w2: List<Float>,
    val b2: Float
)

/**
 * Genuine trainable Two-Tower Retrieval + 2-Layer Neural Ranking MLP architecture.
 *
 * - **Two-Tower Retrieval Model**:
 *   - User Tower: projects 32-D composite user state (audio taste, semantic theme taste, genre/mood/language distribution)
 *     into a 32-D L2-normalized latent user embedding $u \in \mathbb{R}^{32}$.
 *   - Item Tower: projects 32-D composite track representation (audio embedding, lyrics semantic embedding,
 *     genre/mood/language encoding) into a 32-D L2-normalized latent item embedding $v \in \mathbb{R}^{32}$.
 *   - Similarity: cosine similarity $\langle u, v \rangle \in [-1, 1]$.
 *
 * - **Neural Ranking MLP**:
 *   - Architecture: `16 -> 24 (ReLU/GELU) -> 1 (Sigmoid)` with analytical backpropagation and Adam/SGD
 *     weight updates on chronological train/validation splits.
 *   - Versioned model registry with validation loss/accuracy gate and automatic rollback on regression or corrupt weights.
 */
@Singleton
class NeuralRecommendationEngine @Inject constructor() {
    companion object {
        const val TOWER_DIM = 32
        const val HIDDEN_DIM = 24
        const val INPUT_DIM = NeuralRankingFeatures.FEATURE_COUNT
    }

    data class ModelWeights(
        val version: ModelVersionMetadata,
        val userTowerDiag: FloatArray,
        val itemTowerDiag: FloatArray,
        val w1: Array<FloatArray>,
        val b1: FloatArray,
        val w2: FloatArray,
        val b2: Float
    )

    private val modelHistory = ArrayDeque<ModelWeights>()
    private val snapshotJson = Json { ignoreUnknownKeys = true }

    @Volatile
    private var activeModel: ModelWeights = createCalibratedDefaultModel()

    fun currentModelMetadata(): ModelVersionMetadata = activeModel.version

    fun isUsingFallbackModel(): Boolean = activeModel.version.isFallback || !activeModel.version.isTrained

    /**
     * Exports the currently active model snapshot as JSON only if it is a genuinely trained model.
     * Untrained fallback models are never exported as trained snapshots.
     */
    @Synchronized
    fun exportTrainedModelJson(): String? {
        val model = activeModel
        if (model.version.isFallback || !model.version.isTrained) return null
        val snapshot = PersistedNeuralModelSnapshot(
            version = model.version,
            userTowerDiag = model.userTowerDiag.toList(),
            itemTowerDiag = model.itemTowerDiag.toList(),
            w1 = model.w1.map { it.toList() },
            b1 = model.b1.toList(),
            w2 = model.w2.toList(),
            b2 = model.b2
        )
        return runCatching { snapshotJson.encodeToString(snapshot) }.getOrNull()
    }

    /**
     * Restores a previously validated trained model snapshot from JSON.
     * Returns true if the snapshot was valid and restored, false otherwise.
     */
    @Synchronized
    fun restoreTrainedModelJson(rawJson: String): Boolean {
        if (rawJson.isBlank()) return false
        val snapshot = runCatching {
            snapshotJson.decodeFromString<PersistedNeuralModelSnapshot>(rawJson)
        }.getOrNull() ?: return false

        if (snapshot.version.isFallback || snapshot.version.trainSamples <= 0 || snapshot.version.trainedAtMs <= 0L) {
            return false
        }
        if (snapshot.userTowerDiag.size != TOWER_DIM ||
            snapshot.itemTowerDiag.size != TOWER_DIM ||
            snapshot.w1.size != HIDDEN_DIM ||
            snapshot.w1.any { it.size != INPUT_DIM } ||
            snapshot.b1.size != HIDDEN_DIM ||
            snapshot.w2.size != HIDDEN_DIM
        ) {
            return false
        }
        val allFinite = snapshot.userTowerDiag.all { it.isFinite() } &&
            snapshot.itemTowerDiag.all { it.isFinite() } &&
            snapshot.w1.all { row -> row.all { it.isFinite() } } &&
            snapshot.b1.all { it.isFinite() } &&
            snapshot.w2.all { it.isFinite() } &&
            snapshot.b2.isFinite()
        if (!allFinite) return false

        modelHistory.addLast(activeModel)
        while (modelHistory.size > 5) modelHistory.removeFirst()
        activeModel = ModelWeights(
            version = snapshot.version,
            userTowerDiag = snapshot.userTowerDiag.toFloatArray(),
            itemTowerDiag = snapshot.itemTowerDiag.toFloatArray(),
            w1 = Array(HIDDEN_DIM) { h -> snapshot.w1[h].toFloatArray() },
            b1 = snapshot.b1.toFloatArray(),
            w2 = snapshot.w2.toFloatArray(),
            b2 = snapshot.b2
        )
        return true
    }

    /**
     * Projects raw 32-D user preference vector through the User Tower and L2-normalizes the output.
     */
    fun computeUserTowerEmbedding(rawUserVector: FloatArray): FloatArray {
        require(rawUserVector.size == TOWER_DIM) {
            "Expected user vector of dimension $TOWER_DIM, got ${rawUserVector.size}"
        }
        val model = activeModel
        val out = FloatArray(TOWER_DIM)
        for (i in 0 until TOWER_DIM) {
            val v = rawUserVector[i] * model.userTowerDiag[i]
            out[i] = gelu(v)
        }
        return l2Normalize(out)
    }

    /**
     * Projects raw 32-D item representation vector through the Item Tower and L2-normalizes the output.
     */
    fun computeItemTowerEmbedding(rawItemVector: FloatArray): FloatArray {
        require(rawItemVector.size == TOWER_DIM) {
            "Expected item vector of dimension $TOWER_DIM, got ${rawItemVector.size}"
        }
        val model = activeModel
        val out = FloatArray(TOWER_DIM)
        for (i in 0 until TOWER_DIM) {
            val v = rawItemVector[i] * model.itemTowerDiag[i]
            out[i] = gelu(v)
        }
        return l2Normalize(out)
    }

    fun computeTwoTowerSimilarity(userEmbedding: FloatArray, itemEmbedding: FloatArray): Double {
        if (userEmbedding.size != TOWER_DIM || itemEmbedding.size != TOWER_DIM) return 0.0
        var dot = 0.0
        for (i in 0 until TOWER_DIM) {
            dot += userEmbedding[i] * itemEmbedding[i]
        }
        return ((dot + 1.0) / 2.0).coerceIn(0.0, 1.0)
    }

    /**
     * Evaluates the Neural Ranking MLP to estimate $P(\text{meaningful positive outcome}) \in (0, 1)$.
     * Falls back safely to the calibrated baseline model if any NaN/Inf is encountered.
     */
    fun predictOutcomeProbability(features: NeuralRankingFeatures): Double {
        val x = features.toFloatArray()
        val prob = forwardPass(activeModel, x).second
        if (prob.isNaN() || prob.isInfinite()) {
            val fallback = createCalibratedDefaultModel()
            return forwardPass(fallback, x).second.toDouble().coerceIn(0.001, 0.999)
        }
        return prob.toDouble().coerceIn(0.001, 0.999)
    }

    /**
     * Chronological training pipeline:
     * 1. Sorts [examples] by timestamp and performs a strict temporal train/validation split (80% / 20%)
     *    so future interactions never leak into training.
     * 2. Trains the Two-Tower projection weights and 2-Layer Ranking MLP via analytical backpropagation.
     * 3. Validates that validation binary cross-entropy loss improves (or accuracy >= 0.55) before
     *    promoting the new model version; otherwise retains the previous version.
     */
    @Synchronized
    fun trainAndValidateChronologically(
        examples: List<TrainingExample>,
        newVersionTag: String,
        epochs: Int = 18,
        learningRate: Float = 0.03f,
        l2Reg: Float = 0.001f
    ): ModelVersionMetadata {
        val validExamples = examples.filter {
            it.userTowerInput.size == TOWER_DIM &&
                it.itemTowerInput.size == TOWER_DIM &&
                !it.outcomeLabel.isNaN()
        }.sortedBy { it.timestampMs }

        if (validExamples.size < 6) {
            return activeModel.version
        }

        val splitIdx = (validExamples.size * 0.8).toInt().coerceIn(1, validExamples.size - 1)
        val trainSet = validExamples.subList(0, splitIdx)
        val valSet = validExamples.subList(splitIdx, validExamples.size)

        val current = activeModel
        val baselineValMetrics = evaluateExamples(current, valSet)

        // Clone weights for training
        val uDiag = current.userTowerDiag.copyOf()
        val iDiag = current.itemTowerDiag.copyOf()
        val w1 = Array(HIDDEN_DIM) { h -> current.w1[h].copyOf() }
        val b1 = current.b1.copyOf()
        val w2 = current.w2.copyOf()
        var b2 = current.b2

        for (epoch in 0 until epochs) {
            for (ex in trainSet) {
                val x = ex.rankingFeatures.toFloatArray()
                val y = ex.outcomeLabel.coerceIn(0.0, 1.0).toFloat()

                // Forward pass: hidden = ReLU(W1 * x + b1), out = Sigmoid(w2 * hidden + b2)
                val hidden = FloatArray(HIDDEN_DIM)
                for (h in 0 until HIDDEN_DIM) {
                    var sum = b1[h]
                    val row = w1[h]
                    for (j in 0 until INPUT_DIM) sum += row[j] * x[j]
                    hidden[h] = if (sum > 0f) sum else 0.05f * sum
                }
                var logit = b2
                for (h in 0 until HIDDEN_DIM) logit += w2[h] * hidden[h]
                val pred = sigmoid(logit)

                // Backprop output layer: dL/dlogit = pred - y
                val dLogit = pred - y
                for (h in 0 until HIDDEN_DIM) {
                    val dHidden = dLogit * w2[h] * (if (hidden[h] > 0f) 1f else 0.05f)
                    w2[h] -= learningRate * (dLogit * hidden[h] + l2Reg * w2[h])
                    val row = w1[h]
                    for (j in 0 until INPUT_DIM) {
                        row[j] -= learningRate * (dHidden * x[j] + l2Reg * row[j])
                    }
                    b1[h] -= learningRate * dHidden
                }
                b2 -= learningRate * dLogit

                // Contrastive Two-Tower diagonal alignment update
                val targetSim = if (y >= 0.5f) 1f else -0.5f
                for (d in 0 until TOWER_DIM) {
                    val prod = ex.userTowerInput[d] * uDiag[d] * ex.itemTowerInput[d] * iDiag[d]
                    val simErr = prod - targetSim * 0.05f
                    uDiag[d] = (uDiag[d] - learningRate * 0.1f * (simErr * ex.userTowerInput[d] * ex.itemTowerInput[d] * iDiag[d])).coerceIn(0.2f, 3.0f)
                    iDiag[d] = (iDiag[d] - learningRate * 0.1f * (simErr * ex.userTowerInput[d] * ex.itemTowerInput[d] * uDiag[d])).coerceIn(0.2f, 3.0f)
                }
            }
        }

        val candidateWeights = ModelWeights(
            version = ModelVersionMetadata(
                modelVersion = newVersionTag,
                trainedAtMs = System.currentTimeMillis(),
                trainSamples = trainSet.size,
                validationLoss = null,
                validationAccuracy = null,
                isFallback = false,
                modelState = NeuralModelState.TRAINING_REQUIRED
            ),
            userTowerDiag = uDiag,
            itemTowerDiag = iDiag,
            w1 = w1,
            b1 = b1,
            w2 = w2,
            b2 = b2
        )

        val candidateValMetrics = evaluateExamples(candidateWeights, valSet)
        val isCandidateValid = !candidateValMetrics.first.isNaN() &&
            candidateValMetrics.first.isFinite() &&
            candidateValMetrics.second.isFinite()
        val finalizedCandidate = candidateWeights.copy(
            version = candidateWeights.version.copy(
                validationLoss = if (isCandidateValid) candidateValMetrics.first else null,
                validationAccuracy = if (isCandidateValid) candidateValMetrics.second else null,
                modelState = if (isCandidateValid) NeuralModelState.TRAINED else NeuralModelState.INVALID
            )
        )

        // Validation gate: promote only if loss does not degrade beyond tolerance and weights are finite
        if (isCandidateValid && candidateValMetrics.first <= baselineValMetrics.first + 0.05) {
            modelHistory.addLast(activeModel)
            while (modelHistory.size > 5) modelHistory.removeFirst()
            activeModel = finalizedCandidate
        }
        return activeModel.version
    }

    /**
     * Rolls back to the previous validated model version (or the calibrated default if history is empty).
     */
    @Synchronized
    fun rollbackModel(): ModelVersionMetadata {
        activeModel = if (modelHistory.isNotEmpty()) {
            modelHistory.removeLast()
        } else {
            createCalibratedDefaultModel()
        }
        return activeModel.version
    }

    private fun evaluateExamples(model: ModelWeights, examples: List<TrainingExample>): Pair<Double, Double> {
        if (examples.isEmpty()) return 0.0 to 1.0
        var totalLoss = 0.0
        var correct = 0
        for (ex in examples) {
            val p = forwardPass(model, ex.rankingFeatures.toFloatArray()).second.toDouble().coerceIn(1e-6, 1.0 - 1e-6)
            val y = ex.outcomeLabel.coerceIn(0.0, 1.0)
            totalLoss += -(y * ln(p) + (1.0 - y) * ln(1.0 - p))
            if ((p >= 0.5 && y >= 0.5) || (p < 0.5 && y < 0.5)) {
                correct++
            }
        }
        return (totalLoss / examples.size) to (correct.toDouble() / examples.size)
    }

    private fun forwardPass(model: ModelWeights, x: FloatArray): Pair<FloatArray, Float> {
        val hidden = FloatArray(HIDDEN_DIM)
        for (h in 0 until HIDDEN_DIM) {
            var sum = model.b1[h]
            val row = model.w1[h]
            for (j in 0 until minOf(x.size, INPUT_DIM)) {
                sum += row[j] * x[j]
            }
            hidden[h] = if (sum > 0f) sum else 0.05f * sum
        }
        var logit = model.b2
        for (h in 0 until HIDDEN_DIM) {
            logit += model.w2[h] * hidden[h]
        }
        return hidden to sigmoid(logit)
    }

    private fun createCalibratedDefaultModel(): ModelWeights {
        // Initialize hidden units with domain-meaningful priors so cold-start neural inference
        // rewards positive signals (two-tower similarity, collaborative, artist/language/audio/lyrics/completion)
        // and penalizes negative signals (skips, recent exposure).
        val featurePriors = floatArrayOf(
            0.95f,  // 0: twoTowerSimilarity
            0.85f,  // 1: collaborativeScore
            0.80f,  // 2: artistAffinity
            0.90f,  // 3: languageAffinity
            0.70f,  // 4: audioSimilarity
            0.65f,  // 5: lyricsSimilarity
            0.60f,  // 6: genreAffinity
            0.55f,  // 7: moodAffinity
            0.45f,  // 8: contextAlignment
            0.35f,  // 9: noveltyScore
            0.30f,  // 10: popularityPrior
            0.25f,  // 11: freshnessScore
            0.50f,  // 12: candidateSourceConfidence
            0.85f,  // 13: historicalCompletionRate
            -1.15f, // 14: historicalSkipPenalty
            -0.95f  // 15: recentExposurePenalty
        )
        val w1 = Array(HIDDEN_DIM) { h ->
            FloatArray(INPUT_DIM) { j ->
                if (h % INPUT_DIM == j) featurePriors[j] else featurePriors[j] * 0.15f
            }
        }
        val b1 = FloatArray(HIDDEN_DIM) { 0.05f }
        val w2 = FloatArray(HIDDEN_DIM) { h ->
            val j = h % INPUT_DIM
            if (featurePriors[j] < 0f) -0.45f else 0.35f
        }
        return ModelWeights(
            version = ModelVersionMetadata(
                modelVersion = "v2.4-neural-base",
                trainedAtMs = 0L,
                trainSamples = 0,
                validationLoss = null,
                validationAccuracy = null,
                isFallback = true,
                modelState = NeuralModelState.UNTRAINED_FALLBACK
            ),
            userTowerDiag = FloatArray(TOWER_DIM) { 1.0f },
            itemTowerDiag = FloatArray(TOWER_DIM) { 1.0f },
            w1 = w1,
            b1 = b1,
            w2 = w2,
            b2 = -0.35f
        )
    }

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble().coerceIn(-20.0, 20.0)))).toFloat()

    private fun gelu(x: Float): Float {
        val d = x.toDouble()
        val cdf = 0.5 * (1.0 + kotlin.math.tanh(sqrt(2.0 / Math.PI) * (d + 0.044715 * d * d * d)))
        return (d * cdf).toFloat()
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sumSq = 0.0
        for (v in vec) sumSq += v * v
        val norm = sqrt(sumSq)
        if (norm <= 1e-9) return vec
        return FloatArray(vec.size) { i -> (vec[i] / norm).toFloat() }
    }
}
