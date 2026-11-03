package com.geekify.android.domain.recommend

import com.geekify.android.data.model.ShelfItem
import kotlin.random.Random

/**
 * Weighted random sampler for "Top daily picks".
 * Uses Efraimidis-Spirakis weighted reservoir keys: key = u^(1/w) where u ~ U(0,1),
 * sorting by key descending produces an exact weighted random permutation.
 *
 * Guarantees that recently shown top items are not placed in position 0 on subsequent refreshes
 * when alternative candidates are available.
 */
object DailyPicksSampler {

    /**
     * Orders [candidates] using weighted random sampling.
     * @param candidates Items to sample.
     * @param weights Priority/taste weight function (> 0).
     * @param recentlyShownTopIds IDs of items that were recently top picks; disqualified from position 0.
     * @param random Random generator with per-refresh seed.
     * @param limit Max items to return.
     */
    fun sample(
        candidates: List<ShelfItem>,
        weights: (ShelfItem) -> Double,
        recentlyShownTopIds: Set<String>,
        random: Random,
        limit: Int = 20
    ): List<ShelfItem> {
        if (candidates.isEmpty()) return emptyList()
        val distinct = candidates.distinctBy { it.id }
        if (distinct.size <= 1) return distinct

        val scored = distinct.map { item ->
            val w = weights(item).coerceAtLeast(0.1)
            val u = random.nextDouble(0.0001, 1.0)
            val key = Math.pow(u, 1.0 / w)
            ItemKey(item, key)
        }.sortedByDescending { it.key }

        val eligibleForTop = scored.filter { it.item.id !in recentlyShownTopIds }
        val result = if (eligibleForTop.isNotEmpty() && scored.first().item.id in recentlyShownTopIds) {
            val topChoice = eligibleForTop.first()
            val remaining = scored.filter { it.item.id != topChoice.item.id }
            listOf(topChoice) + remaining
        } else {
            scored
        }

        return result.map { it.item }.take(limit)
    }

    private data class ItemKey(val item: ShelfItem, val key: Double)
}
