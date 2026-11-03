package com.geekify.android.domain.recommend

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers recent recommendation impressions separately for each account. A single global set
 * made one person's recommendations suppress tracks for every other signed-in account.
 */
@Singleton
class RecommendationMemory @Inject constructor() {
    private val shownByAccount = LinkedHashMap<String, LinkedHashSet<String>>()

    @Synchronized
    fun snapshot(accountId: String?): Set<String> =
        shownByAccount[key(accountId)]?.toSet().orEmpty()

    @Synchronized
    fun remember(accountId: String?, videoIds: Collection<String>) {
        val accountKey = key(accountId)
        val shown = shownByAccount.getOrPut(accountKey) { LinkedHashSet() }
        shown.addAll(videoIds.filter(String::isNotBlank))
        while (shown.size > MAX_REMEMBERED_PER_ACCOUNT) {
            val oldest = shown.iterator()
            oldest.next()
            oldest.remove()
        }
        while (shownByAccount.size > MAX_ACCOUNTS) {
            val oldestAccount = shownByAccount.keys.iterator()
            oldestAccount.next()
            oldestAccount.remove()
        }
    }

    @Synchronized
    fun clear(accountId: String?) {
        shownByAccount.remove(key(accountId))
    }

    private fun key(accountId: String?): String = accountId?.takeIf { it.isNotBlank() } ?: GUEST_KEY

    private companion object {
        const val GUEST_KEY = "__guest__"
        const val MAX_REMEMBERED_PER_ACCOUNT = 240
        const val MAX_ACCOUNTS = 8
    }
}
