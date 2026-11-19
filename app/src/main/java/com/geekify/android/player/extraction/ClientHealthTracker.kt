package com.geekify.android.player.extraction

import com.metrolist.innertubex.extraction.strategy.ClientFailureKind
import com.metrolist.innertubex.extraction.strategy.ClientHealthMonitor
import com.metrolist.innertubex.extraction.strategy.ClientHealthScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Tracks per-client extraction/playback health and cooldowns for InnerTubeX's
 * [com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy].
 */
class ClientHealthTracker(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val networkScopeProvider: () -> String = { "default" }
) : ClientHealthMonitor {

    data class ClientHealth(
        val successes: Int = 0,
        val failures: Int = 0,
        val consecutiveFailures: Int = 0,
        val lastFailure: ClientFailureKind? = null,
        val lastFailureAtMs: Long? = null,
        val cooldownUntilMs: Long? = null
    )

    private data class HealthKey(
        val clientId: String,
        val scope: ClientHealthScope?,
        val networkScope: String
    )

    private val map = ConcurrentHashMap<HealthKey, ClientHealth>()

    private fun key(clientId: String, scope: ClientHealthScope?): HealthKey =
        HealthKey(clientId, scope, networkScopeProvider())

    fun health(clientId: String, scope: ClientHealthScope? = null): ClientHealth =
        map[key(clientId, scope)] ?: ClientHealth()

    override fun scoreAdjustment(clientId: String, scope: ClientHealthScope?): Int =
        healthAdjustment(health(clientId)) + (scope?.let { healthAdjustment(health(clientId, it)) } ?: 0)

    private fun healthAdjustment(health: ClientHealth): Int {
        val successBonus = min(health.successes, MAX_SUCCESS_BONUS_COUNT) * SUCCESS_BONUS
        val failurePenalty = min(health.consecutiveFailures, MAX_CONSECUTIVE_FAILURES) * FAILURE_PENALTY
        val cooldownPenalty = if ((health.cooldownUntilMs ?: 0L) > nowMs()) COOLDOWN_PENALTY else 0
        return successBonus - failurePenalty - cooldownPenalty
    }

    fun clientsInCooldown(scope: ClientHealthScope): Set<String> {
        val net = networkScopeProvider()
        val now = nowMs()
        return map.entries
            .filter { (k, h) ->
                k.networkScope == net &&
                    (k.scope == null || k.scope == scope) &&
                    (h.cooldownUntilMs ?: 0L) > now &&
                    (h.lastFailure == ClientFailureKind.PLAYABILITY ||
                        h.lastFailure == ClientFailureKind.PLAYER_REQUEST ||
                        h.lastFailure == ClientFailureKind.MEDIA_FORBIDDEN)
            }
            .mapTo(mutableSetOf()) { it.key.clientId }
    }

    override fun recordSuccess(clientId: String, scope: ClientHealthScope?) {
        val k = key(clientId, scope)
        map.compute(k) { _, cur ->
            val current = cur ?: ClientHealth()
            current.copy(
                successes = current.successes + 1,
                consecutiveFailures = 0,
                cooldownUntilMs = null
            )
        }
    }

    override fun recordFailure(clientId: String, kind: ClientFailureKind, scope: ClientHealthScope?) {
        val now = nowMs()
        val k = key(clientId, scope)
        map.compute(k) { _, cur ->
            val current = cur ?: ClientHealth()
            val nextConsecutive = current.consecutiveFailures + 1
            val cooldownMs = when {
                kind == ClientFailureKind.MEDIA_FORBIDDEN -> FORBIDDEN_COOLDOWN_MS
                nextConsecutive >= 2 -> DEFAULT_COOLDOWN_MS
                else -> 0L
            }
            current.copy(
                failures = current.failures + 1,
                consecutiveFailures = nextConsecutive,
                lastFailure = kind,
                lastFailureAtMs = now,
                cooldownUntilMs = if (cooldownMs > 0L) now + cooldownMs else current.cooldownUntilMs
            )
        }
    }

    fun clear() {
        map.clear()
    }

    companion object {
        private const val SUCCESS_BONUS = 2
        private const val MAX_SUCCESS_BONUS_COUNT = 5
        private const val FAILURE_PENALTY = 18
        private const val MAX_CONSECUTIVE_FAILURES = 4
        private const val COOLDOWN_PENALTY = 1000
        private const val DEFAULT_COOLDOWN_MS = 2 * 60 * 1000L
        private const val FORBIDDEN_COOLDOWN_MS = 10 * 60 * 1000L
    }
}

/**
 * Tracks whether web BotGuard PO tokens are currently being rejected by YouTube's SABR/CDN
 * endpoints (e.g., attestation pending or tokenized 403) so extraction can adaptively prefer
 * tokenless clients during the rejection window.
 */
object WebPoTokenHealth {
    private const val REJECTION_TTL_MS = 30 * 60 * 1000L
    private val rejectedUntilMs = AtomicLong(0L)

    fun recordRejection(nowMs: Long = System.currentTimeMillis()) {
        rejectedUntilMs.set(nowMs + REJECTION_TTL_MS)
    }

    fun recordAccepted() {
        rejectedUntilMs.set(0L)
    }

    fun tokensRejected(nowMs: Long = System.currentTimeMillis()): Boolean =
        rejectedUntilMs.get() > nowMs
}
