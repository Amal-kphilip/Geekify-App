package com.geekify.android.domain.recommend.engine

import com.geekify.android.data.local.CollaborativeInteractionEntity
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

enum class CollaborativeInteractionType(val baseWeight: Double) {
    COMPLETED(3.0),
    LIKED(3.5),
    ADDED_TO_PLAYLIST(2.5),
    REPLAYED(2.0),
    MEANINGFUL_PLAY(1.2),
    SKIPPED_EARLY(-2.2),
    DISMISSED_OR_HIDDEN(-4.0),
    IMPRESSION_ONLY(-0.15)
}

data class UserItemInteraction(
    val anonUserHash: String,
    val videoId: String,
    val artistKey: String,
    val type: CollaborativeInteractionType,
    val count: Int = 1,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun calibratedWeight(): Double = when (type) {
        CollaborativeInteractionType.REPLAYED -> type.baseWeight * ln(1.0 + count.coerceIn(1, 20))
        else -> type.baseWeight
    }
}

data class CollaborativeModelSnapshot(
    val itemVectors: Map<String, FloatArray>,
    val userVectors: Map<String, FloatArray>,
    val itemCoOccurrence: Map<String, Map<String, Double>>,
    val artistCoOccurrence: Map<String, Map<String, Double>>,
    val distinctUsersPerItem: Map<String, Int>,
    val trainedAtMs: Long,
    val latentDim: Int = 16
)

/**
 * Multi-user collaborative filtering engine combining:
 * 1. Item-Item Adjusted Cosine Collaborative Filtering (with minimum cohort threshold >= [minCohortListeners]).
 * 2. Implicit Matrix Factorization (BPR/ALS latent factor updates in [latentDim] dimensions).
 * 3. Artist-to-Artist cross-listener co-occurrence (discovering e.g. Malayalam -> Tamil artist bridges).
 * 4. Privacy-preserving salted SHA-256 user identifier hashing and strict account isolation.
 */
@Singleton
class CollaborativeRecommendationEngine @Inject constructor() {
    companion object {
        const val LATENT_DIM = 16
        const val DEFAULT_MIN_COHORT_LISTENERS = 2
    }

    @Volatile
    private var currentSnapshot: CollaborativeModelSnapshot? = null

    fun anonymizeUserId(accountId: String?, salt: String = "geekify-cf-v1"): String {
        val raw = "${salt}:${accountId?.trim()?.takeIf { it.isNotEmpty() } ?: "guest"}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /**
     * Deduplicates raw interactions within 5-second windows and filters out events after [cutoffTimestampMs]
     * to prevent temporal leakage during evaluation or training.
     */
    fun buildCleanInteractionMatrix(
        interactions: List<UserItemInteraction>,
        cutoffTimestampMs: Long = Long.MAX_VALUE
    ): Map<String, Map<String, Double>> {
        val seenEventKeys = HashSet<String>()
        val matrix = mutableMapOf<String, MutableMap<String, Double>>()

        for (event in interactions) {
            if (event.timestampMs > cutoffTimestampMs) continue
            if (event.anonUserHash.isBlank() || event.videoId.isBlank()) continue
            val timeBucket = event.timestampMs / 5_000L
            val dedupeKey = "${event.anonUserHash}:${event.videoId}:${event.type.name}:$timeBucket"
            if (!seenEventKeys.add(dedupeKey)) continue

            val userRow = matrix.getOrPut(event.anonUserHash) { mutableMapOf() }
            val updated = (userRow[event.videoId] ?: 0.0) + event.calibratedWeight()
            userRow[event.videoId] = updated.coerceIn(-6.0, 12.0)
        }
        return matrix
    }

    /**
     * Trains both the Item-Item Adjusted Cosine similarity graph and Implicit Matrix Factorization
     * latent factors from multi-user interaction records.
     */
    fun train(
        interactions: List<UserItemInteraction>,
        cutoffTimestampMs: Long = Long.MAX_VALUE,
        minCohortListeners: Int = DEFAULT_MIN_COHORT_LISTENERS,
        epochs: Int = 8,
        learningRate: Double = 0.035,
        regularization: Double = 0.01,
        random: Random = Random(42)
    ): CollaborativeModelSnapshot {
        val matrix = buildCleanInteractionMatrix(interactions, cutoffTimestampMs)
        val itemUsers = mutableMapOf<String, MutableMap<String, Double>>()
        val artistByVideo = mutableMapOf<String, String>()

        interactions.forEach { ev ->
            if (ev.timestampMs <= cutoffTimestampMs && ev.artistKey.isNotBlank()) {
                artistByVideo[ev.videoId] = ev.artistKey.lowercase(Locale.ROOT).trim()
            }
        }

        for ((user, items) in matrix) {
            for ((item, score) in items) {
                if (score > 0.0) {
                    itemUsers.getOrPut(item) { mutableMapOf() }[user] = score
                }
            }
        }

        val distinctUsersPerItem = itemUsers.mapValues { it.value.size }

        // 1. Item-Item Adjusted Cosine Similarity with minimum cohort threshold
        val userMeans = matrix.mapValues { (_, items) ->
            val pos = items.values.filter { it > 0.0 }
            if (pos.isEmpty()) 0.0 else pos.average()
        }

        val itemCoOccurrence = mutableMapOf<String, MutableMap<String, Double>>()
        val eligibleItems = itemUsers.keys.filter { (distinctUsersPerItem[it] ?: 0) >= minCohortListeners }

        for (i in eligibleItems.indices) {
            val itemA = eligibleItems[i]
            val usersA = itemUsers[itemA] ?: continue
            for (j in (i + 1) until eligibleItems.size) {
                val itemB = eligibleItems[j]
                val usersB = itemUsers[itemB] ?: continue
                val sharedUsers = usersA.keys.intersect(usersB.keys)
                if (sharedUsers.size < minCohortListeners) continue

                var num = 0.0
                var denA = 0.0
                var denB = 0.0
                for (u in sharedUsers) {
                    val mean = userMeans[u] ?: 0.0
                    val a = (usersA[u] ?: 0.0) - mean * 0.25
                    val b = (usersB[u] ?: 0.0) - mean * 0.25
                    num += a * b
                }
                for ((_, v) in usersA) denA += v * v
                for ((_, v) in usersB) denB += v * v
                val denom = sqrt(denA) * sqrt(denB)
                if (denom > 1e-6) {
                    val sim = (num / denom).coerceIn(0.0, 1.0)
                    if (sim > 0.05) {
                        itemCoOccurrence.getOrPut(itemA) { mutableMapOf() }[itemB] = sim
                        itemCoOccurrence.getOrPut(itemB) { mutableMapOf() }[itemA] = sim
                    }
                }
            }
        }

        // 2. Artist-to-Artist Cross-User Co-occurrence (e.g., Malayalam <-> Tamil artist bridges)
        val userArtists = mutableMapOf<String, MutableMap<String, Double>>()
        for ((user, items) in matrix) {
            for ((vid, score) in items) {
                if (score <= 0.0) continue
                val artist = artistByVideo[vid] ?: continue
                val row = userArtists.getOrPut(user) { mutableMapOf() }
                row[artist] = (row[artist] ?: 0.0) + score
            }
        }
        val artistUsers = mutableMapOf<String, MutableMap<String, Double>>()
        for ((u, artists) in userArtists) {
            for ((a, s) in artists) {
                artistUsers.getOrPut(a) { mutableMapOf() }[u] = s
            }
        }
        val artistCoOccurrence = mutableMapOf<String, MutableMap<String, Double>>()
        val eligibleArtists = artistUsers.keys.filter { (artistUsers[it]?.size ?: 0) >= minCohortListeners }
        for (i in eligibleArtists.indices) {
            val artA = eligibleArtists[i]
            val uA = artistUsers[artA] ?: continue
            for (j in (i + 1) until eligibleArtists.size) {
                val artB = eligibleArtists[j]
                val uB = artistUsers[artB] ?: continue
                val shared = uA.keys.intersect(uB.keys)
                if (shared.size < minCohortListeners) continue
                var num = 0.0
                var dA = 0.0
                var dB = 0.0
                for (u in shared) num += (uA[u] ?: 0.0) * (uB[u] ?: 0.0)
                for (v in uA.values) dA += v * v
                for (v in uB.values) dB += v * v
                val denom = sqrt(dA) * sqrt(dB)
                if (denom > 1e-6) {
                    val sim = (num / denom).coerceIn(0.0, 1.0)
                    if (sim > 0.05) {
                        artistCoOccurrence.getOrPut(artA) { mutableMapOf() }[artB] = sim
                        artistCoOccurrence.getOrPut(artB) { mutableMapOf() }[artA] = sim
                    }
                }
            }
        }

        // 3. Implicit Matrix Factorization (BPR / Regularized Weighted SGD)
        val userVectors = matrix.keys.associateWith {
            FloatArray(LATENT_DIM) { (random.nextDouble(-0.15, 0.15)).toFloat() }
        }
        val allItemIds = matrix.values.flatMap { it.keys }.toSet()
        val itemVectors = allItemIds.associateWith {
            FloatArray(LATENT_DIM) { (random.nextDouble(-0.15, 0.15)).toFloat() }
        }

        for (epoch in 0 until epochs) {
            for ((user, items) in matrix) {
                val uVec = userVectors[user] ?: continue
                for ((item, rating) in items) {
                    val iVec = itemVectors[item] ?: continue
                    val target = (rating / 5.0).coerceIn(-1.0, 1.0)
                    var pred = 0.0
                    for (k in 0 until LATENT_DIM) pred += uVec[k] * iVec[k]
                    val err = target - pred
                    for (k in 0 until LATENT_DIM) {
                        val uOld = uVec[k]
                        val iOld = iVec[k]
                        uVec[k] = (uOld + learningRate * (err * iOld - regularization * uOld)).toFloat()
                        iVec[k] = (iOld + learningRate * (err * uOld - regularization * iOld)).toFloat()
                    }
                }
            }
        }

        return CollaborativeModelSnapshot(
            itemVectors = itemVectors,
            userVectors = userVectors,
            itemCoOccurrence = itemCoOccurrence,
            artistCoOccurrence = artistCoOccurrence,
            distinctUsersPerItem = distinctUsersPerItem,
            trainedAtMs = System.currentTimeMillis()
        ).also { currentSnapshot = it }
    }

    /**
     * Computes a collaborative affinity score in `[0.0, 1.0]` for [candidateVideoId] and [candidateArtistKey]
     * given the active listener's positive seed tracks and artist affinities.
     * Gracefully falls back to `0.0` for cold-start users or cold-start tracks with no collaborative data.
     */
    fun scoreCandidate(
        anonUserHash: String,
        userPositiveVideoIds: Set<String>,
        userTopArtists: Set<String>,
        candidateVideoId: String,
        candidateArtistKey: String,
        snapshot: CollaborativeModelSnapshot? = currentSnapshot
    ): Double {
        val model = snapshot ?: return 0.0
        var itemSimScore = 0.0
        val itemNeighbors = model.itemCoOccurrence[candidateVideoId]
        if (!itemNeighbors.isNullOrEmpty() && userPositiveVideoIds.isNotEmpty()) {
            var sum = 0.0
            var hits = 0
            for (seedId in userPositiveVideoIds) {
                val s = itemNeighbors[seedId] ?: continue
                sum += s
                hits++
            }
            if (hits > 0) {
                itemSimScore = (sum / sqrt(hits.toDouble())).coerceIn(0.0, 1.0)
            }
        }

        var mfScore = 0.0
        val uVec = model.userVectors[anonUserHash]
        val iVec = model.itemVectors[candidateVideoId]
        if (uVec != null && iVec != null) {
            var dot = 0.0
            for (k in uVec.indices) dot += uVec[k] * iVec[k]
            mfScore = ((dot + 1.0) / 2.0).coerceIn(0.0, 1.0)
        }

        var artistBridgeScore = 0.0
        val normCandidateArtist = candidateArtistKey.lowercase(Locale.ROOT).trim()
        val artistNeighbors = model.artistCoOccurrence[normCandidateArtist]
        if (!artistNeighbors.isNullOrEmpty() && userTopArtists.isNotEmpty()) {
            var maxBridge = 0.0
            for (userArtist in userTopArtists) {
                val b = artistNeighbors[userArtist.lowercase(Locale.ROOT).trim()] ?: 0.0
                if (b > maxBridge) maxBridge = b
            }
            artistBridgeScore = maxBridge
        }

        return (0.45 * itemSimScore + 0.30 * mfScore + 0.25 * artistBridgeScore).coerceIn(0.0, 1.0)
    }

    fun fromRoomEntities(entities: List<CollaborativeInteractionEntity>): List<UserItemInteraction> =
        entities.mapNotNull { e ->
            val type = runCatching { CollaborativeInteractionType.valueOf(e.interactionType) }.getOrNull()
                ?: return@mapNotNull null
            UserItemInteraction(
                anonUserHash = e.anonUserHash,
                videoId = e.videoId,
                artistKey = e.artistKey,
                type = type,
                count = 1,
                timestampMs = e.timestampMs
            )
        }
}
