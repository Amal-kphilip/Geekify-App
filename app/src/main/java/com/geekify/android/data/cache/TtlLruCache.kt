package com.geekify.android.data.cache

import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TtlLruCache @Inject constructor() {
    private data class Entry(val value: Any, val expiresAt: Long)
    private val entries = object : LinkedHashMap<String, Entry>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 128
    }
    @Synchronized fun <T : Any> get(key: String): T? {
        val entry = entries[key] ?: return null
        if (entry.expiresAt <= System.currentTimeMillis()) { entries.remove(key); return null }
        @Suppress("UNCHECKED_CAST") return entry.value as? T
    }
    @Synchronized fun put(key: String, value: Any, ttlMillis: Long) { entries[key] = Entry(value, System.currentTimeMillis() + ttlMillis) }
    @Synchronized fun remove(key: String) { entries.remove(key) }
}
