package com.geekify.android.domain.recommend

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which songs recent mixes already offered (for this app session), so [Recommender]
 * can demote them next time and the home feed doesn't keep showing the same tracks.
 */
@Singleton
class RecommendationMemory @Inject constructor() {
    private val shown = LinkedHashSet<String>()

    @Synchronized
    fun snapshot(): Set<String> = shown.toSet()

    @Synchronized
    fun remember(videoIds: Collection<String>) {
        shown.addAll(videoIds)
        while (shown.size > MAX_REMEMBERED) {
            val oldest = shown.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    private companion object {
        const val MAX_REMEMBERED = 240
    }
}
