package com.geekify.android.recognition

import java.util.LinkedHashMap

data class RecognitionResult(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val coverArtUrl: String? = null,
    val coverArtHqUrl: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val label: String? = null,
    val shazamUrl: String? = null,
    val appleMusicUrl: String? = null,
    val spotifyUrl: String? = null,
    val isrc: String? = null,
    val youtubeVideoId: String? = null
)

sealed interface RecognitionStatus {
    data object Ready : RecognitionStatus
    data object Listening : RecognitionStatus
    data object Processing : RecognitionStatus
    data class Success(val result: RecognitionResult) : RecognitionStatus
    data class NoMatch(val message: String = "No matching song found. Try again closer to the audio source.") : RecognitionStatus
    data class Error(val message: String) : RecognitionStatus
}

class RecognitionResultCache(
    private val maxEntries: Int = 32,
    private val ttlMs: Long = 5 * 60 * 1000L
) {
    private data class Entry(val result: RecognitionResult, val storedAtMs: Long)

    private val map = object : LinkedHashMap<String, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(signature: String, nowMs: Long = System.currentTimeMillis()): RecognitionResult? {
        val entry = map[signature] ?: return null
        if (nowMs - entry.storedAtMs > ttlMs) {
            map.remove(signature)
            return null
        }
        return entry.result
    }

    @Synchronized
    fun put(signature: String, result: RecognitionResult, nowMs: Long = System.currentTimeMillis()) {
        map[signature] = Entry(result, nowMs)
    }

    @Synchronized
    fun clear() {
        map.clear()
    }
}
