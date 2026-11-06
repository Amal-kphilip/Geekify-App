package com.geekify.android.domain.recommend.engine

import com.geekify.android.data.local.ExperimentAssignmentEntity
import com.geekify.android.data.local.RecommendationEngineDao
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

enum class ExperimentTreatmentGroup {
    CONTROL,
    TREATMENT_A_CONTENT,
    TREATMENT_B_COLLAB,
    TREATMENT_C_NEURAL
}

data class ExperimentGroupMetrics(
    val group: ExperimentTreatmentGroup,
    val impressions: Int,
    val plays: Int,
    val meaningfulPlays: Int,
    val completions: Int,
    val earlySkips: Int,
    val likes: Int,
    val playlistAdditions: Int,
    val avgListenDurationMs: Double,
    val artistDiversityAt10: Double,
    val genreDiversityAt10: Double,
    val languageDiversityAt10: Double,
    val latencyP50Ms: Long,
    val latencyP95Ms: Long,
    val latencyP99Ms: Long
) {
    /** Primary success metric: completed or >= 30s meaningful plays per impression. */
    val meaningfulPlayThroughRate: Double
        get() = if (impressions > 0) meaningfulPlays.toDouble() / impressions else 0.0

    val impressionToPlayRate: Double
        get() = if (impressions > 0) plays.toDouble() / impressions else 0.0

    val completionRate: Double
        get() = if (plays > 0) completions.toDouble() / plays else 0.0

    /** Guardrail metric: early skip rate must not spike significantly above Control. */
    val earlySkipRate: Double
        get() = if (plays > 0) earlySkips.toDouble() / plays else 0.0

    val likesPerExposure: Double
        get() = if (impressions > 0) likes.toDouble() / impressions else 0.0
}

data class StatisticalComparisonResult(
    val treatment: ExperimentTreatmentGroup,
    val primaryMetricLift: Double,
    val confidenceInterval95: Pair<Double, Double>,
    val zScore: Double,
    val isStatisticallySignificant: Boolean,
    val minimumSampleSizeMet: Boolean,
    val guardrailBreached: Boolean,
    val sampleRatioMismatchDetected: Boolean,
    val recommendation: String
)

/**
 * Randomized, reproducible, account-scoped A/B experimentation and statistical evaluation engine.
 */
@Singleton
class RecommendationExperimentManager @Inject constructor() {
    companion object {
        const val DEFAULT_EXPERIMENT_ID = "rec_engine_v2_4_ab"
        const val MIN_IMPRESSIONS_PER_GROUP = 100
        const val Z_CRITICAL_95 = 1.96
    }

    private val assignmentsCache = ConcurrentHashMap<String, ExperimentAssignmentEntity>()
    private val latencySamplesByGroup = ConcurrentHashMap<ExperimentTreatmentGroup, MutableList<Long>>()

    @Volatile
    var globalKillSwitchActive: Boolean = false
        private set

    fun activateKillSwitch() {
        globalKillSwitchActive = true
    }

    fun resetKillSwitch() {
        globalKillSwitchActive = false
    }

    /**
     * Deterministically assigns [accountId] to one of the 4 experiment groups using SHA-256 hashing,
     * persisting the assignment so the user never switches treatments across refreshes.
     * When [globalKillSwitchActive] is true, immediately routes all users to [ExperimentTreatmentGroup.CONTROL].
     */
    suspend fun getOrAssignGroup(
        accountId: String?,
        experimentId: String = DEFAULT_EXPERIMENT_ID,
        dao: RecommendationEngineDao? = null
    ): ExperimentTreatmentGroup {
        if (globalKillSwitchActive) return ExperimentTreatmentGroup.CONTROL

        val key = accountId?.trim()?.takeIf { it.isNotEmpty() } ?: "__guest__"
        assignmentsCache[key]?.let { cached ->
            if (cached.killSwitchActive) return ExperimentTreatmentGroup.CONTROL
            return runCatching { ExperimentTreatmentGroup.valueOf(cached.treatmentGroup) }
                .getOrDefault(ExperimentTreatmentGroup.CONTROL)
        }

        if (dao != null) {
            val persisted = runCatching { dao.getExperimentAssignment(key) }.getOrNull()
            if (persisted != null && persisted.experimentId == experimentId) {
                assignmentsCache[key] = persisted
                if (persisted.killSwitchActive) return ExperimentTreatmentGroup.CONTROL
                return runCatching { ExperimentTreatmentGroup.valueOf(persisted.treatmentGroup) }
                    .getOrDefault(ExperimentTreatmentGroup.CONTROL)
            }
        }

        val assigned = computeDeterministicGroup(key, experimentId)
        val entity = ExperimentAssignmentEntity(
            accountId = key,
            experimentId = experimentId,
            treatmentGroup = assigned.name,
            assignedAt = System.currentTimeMillis(),
            killSwitchActive = false
        )
        assignmentsCache[key] = entity
        if (dao != null) {
            runCatching { dao.upsertExperimentAssignment(entity) }
        }
        return assigned
    }

    fun computeDeterministicGroup(
        accountKey: String,
        experimentId: String = DEFAULT_EXPERIMENT_ID
    ): ExperimentTreatmentGroup {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$experimentId:$accountKey".toByteArray(Charsets.UTF_8))
        val hashInt = abs(ByteBuffer.wrap(bytes, 0, 4).int)
        val bucket = hashInt % 100
        return when {
            bucket < 25 -> ExperimentTreatmentGroup.CONTROL
            bucket < 50 -> ExperimentTreatmentGroup.TREATMENT_A_CONTENT
            bucket < 75 -> ExperimentTreatmentGroup.TREATMENT_B_COLLAB
            else -> ExperimentTreatmentGroup.TREATMENT_C_NEURAL
        }
    }

    fun recordLatency(group: ExperimentTreatmentGroup, durationMs: Long) {
        val list = latencySamplesByGroup.getOrPut(group) { mutableListOf() }
        synchronized(list) {
            list.add(durationMs.coerceAtLeast(0L))
            if (list.size > 500) list.removeAt(0)
        }
    }

    fun latencyPercentiles(group: ExperimentTreatmentGroup): Triple<Long, Long, Long> {
        val list = latencySamplesByGroup[group]?.let { synchronized(it) { it.sorted() } }.orEmpty()
        if (list.isEmpty()) return Triple(0L, 0L, 0L)
        fun pct(p: Double): Long = list[((list.size - 1) * p).toInt().coerceIn(0, list.lastIndex)]
        return Triple(pct(0.50), pct(0.95), pct(0.99))
    }

    /**
     * Estimates minimum required impressions per group to detect a relative lift of [relativeMde]
     * over [baselineRate] at $\alpha = 0.05$ (two-sided) and $80\%$ statistical power ($Z_{\beta} = 0.84$).
     */
    fun estimateMinimumSampleSizePerGroup(
        baselineRate: Double,
        relativeMde: Double = 0.10
    ): Int {
        val p1 = baselineRate.coerceIn(0.01, 0.90)
        val p2 = (p1 * (1.0 + relativeMde)).coerceIn(0.01, 0.99)
        val delta = abs(p2 - p1).coerceAtLeast(0.005)
        val pBar = (p1 + p2) / 2.0
        val zAlpha = 1.96
        val zBeta = 0.84
        val n = ((zAlpha + zBeta) * (zAlpha + zBeta) * 2.0 * pBar * (1.0 - pBar)) / (delta * delta)
        return n.toInt().coerceAtLeast(MIN_IMPRESSIONS_PER_GROUP)
    }

    /**
     * Chi-square goodness-of-fit test for Sample-Ratio Mismatch (SRM).
     * For 4 equal 25% buckets (df = 3), critical $\chi^2_{0.001, 3} = 16.27$.
     */
    fun detectSampleRatioMismatch(
        observedCounts: Map<ExperimentTreatmentGroup, Int>,
        expectedShares: Map<ExperimentTreatmentGroup, Double> = ExperimentTreatmentGroup.entries.associateWith { 0.25 }
    ): Boolean {
        val total = observedCounts.values.sum()
        if (total < 100) return false
        var chiSquare = 0.0
        for ((group, share) in expectedShares) {
            val expected = total * share
            if (expected <= 0.0) continue
            val observed = (observedCounts[group] ?: 0).toDouble()
            val diff = observed - expected
            chiSquare += (diff * diff) / expected
        }
        return chiSquare > 16.27
    }

    /**
     * Evaluates a treatment group against [control] using a two-proportion Z-test on the primary
     * metric (`meaningfulPlayThroughRate`) and checks guardrail metrics (`earlySkipRate` and `latencyP95Ms`).
     */
    fun evaluateTreatmentVsControl(
        control: ExperimentGroupMetrics,
        treatment: ExperimentGroupMetrics,
        allGroupCounts: Map<ExperimentTreatmentGroup, Int> = emptyMap()
    ): StatisticalComparisonResult {
        val nC = control.impressions
        val nT = treatment.impressions
        val minSampleMet = nC >= MIN_IMPRESSIONS_PER_GROUP && nT >= MIN_IMPRESSIONS_PER_GROUP

        val pC = control.meaningfulPlayThroughRate
        val pT = treatment.meaningfulPlayThroughRate
        val diff = pT - pC

        val se = if (nC > 0 && nT > 0) {
            sqrt((pC * (1.0 - pC) / nC.toDouble()) + (pT * (1.0 - pT) / nT.toDouble())).coerceAtLeast(1e-6)
        } else 1.0

        val zScore = if (nC > 0 && nT > 0) diff / se else 0.0
        val ciLow = diff - Z_CRITICAL_95 * se
        val ciHigh = diff + Z_CRITICAL_95 * se
        val significant = minSampleMet && zScore >= Z_CRITICAL_95

        // Guardrail: early skip rate must not increase by more than +5 percentage points, and P95 latency <= 4000ms
        val skipGuardrailBreached = minSampleMet && (treatment.earlySkipRate - control.earlySkipRate) > 0.05
        val latencyGuardrailBreached = treatment.latencyP95Ms > 4_000L && treatment.latencyP95Ms > control.latencyP95Ms * 2
        val guardrailBreached = skipGuardrailBreached || latencyGuardrailBreached
        val srmDetected = if (allGroupCounts.isNotEmpty()) detectSampleRatioMismatch(allGroupCounts) else false

        if (guardrailBreached && treatment.group == ExperimentTreatmentGroup.TREATMENT_C_NEURAL) {
            // Automatic safety rollback if guardrail is breached on enough samples
            activateKillSwitch()
        }

        val decision = when {
            srmDetected -> "HOLD_SRM_DETECTED"
            guardrailBreached -> "ROLLBACK_GUARDRAIL_BREACHED"
            !minSampleMet -> "CONTINUE_COLLECTING_SAMPLES"
            significant && diff > 0.0 -> "PROMOTE_TREATMENT"
            minSampleMet && zScore <= -Z_CRITICAL_95 -> "ROLLBACK_TREATMENT_INFERIOR"
            else -> "INCONCLUSIVE_KEEP_RUNNING"
        }

        return StatisticalComparisonResult(
            treatment = treatment.group,
            primaryMetricLift = diff,
            confidenceInterval95 = ciLow to ciHigh,
            zScore = zScore,
            isStatisticallySignificant = significant,
            minimumSampleSizeMet = minSampleMet,
            guardrailBreached = guardrailBreached,
            sampleRatioMismatchDetected = srmDetected,
            recommendation = decision
        )
    }
}
