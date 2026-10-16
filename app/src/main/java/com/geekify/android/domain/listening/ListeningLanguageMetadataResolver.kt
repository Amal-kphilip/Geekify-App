package com.geekify.android.domain.listening

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves uncertain track languages from LyricsPlus metadata, then from the lyrics'
 * actual writing system. Network failures are non-fatal and never change playback.
 */
@Singleton
class ListeningLanguageMetadataResolver @Inject constructor(
    private val client: OkHttpClient
) {
    private val successfulResults = ConcurrentHashMap<String, String>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val requests = Semaphore(3)

    suspend fun resolve(title: String, artist: String, durationMs: Long): String? {
        val safeTitle = title.trim()
        val safeArtist = artist.trim()
        if (safeTitle.isEmpty() || safeArtist.isEmpty()) return null

        val key = cacheKey(safeTitle, safeArtist, durationMs)
        successfulResults[key]?.let { return it }
        if ((retryAfter[key] ?: 0L) > System.currentTimeMillis()) return null

        return requests.withPermit {
            successfulResults[key]?.let { return@withPermit it }
            withContext(Dispatchers.IO) {
                val result = runCatching {
                    val durationSeconds = String.format(Locale.US, "%.3f", durationMs.coerceAtLeast(0L) / 1000.0)
                    val url = BASE_URL.toHttpUrl().newBuilder()
                        .addQueryParameter("title", safeTitle)
                        .addQueryParameter("artist", safeArtist)
                        .addQueryParameter("duration", durationSeconds)
                        .build()
                    val request = Request.Builder()
                        .url(url)
                        .header("X-Client-Package", "Geekify <https://github.com/Amal-kphilip/Geekify-App>")
                        .get()
                        .build()

                    client.newBuilder()
                        .connectTimeout(4, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .callTimeout(6, TimeUnit.SECONDS)
                        .build()
                        .newCall(request)
                        .execute()
                        .use { response ->
                            if (!response.isSuccessful) null
                            else response.body?.string()?.let(LyricsLanguageResponseParser::parse)
                        }
                }.getOrNull()

                if (result != null) {
                    successfulResults[key] = result
                    retryAfter.remove(key)
                } else {
                    // Avoid hammering an unavailable provider, but allow later retries.
                    retryAfter[key] = System.currentTimeMillis() + FAILURE_RETRY_MS
                }
                result
            }
        }
    }

    private fun cacheKey(title: String, artist: String, durationMs: Long): String =
        "${title.lowercase(Locale.ROOT)}\u0000${artist.lowercase(Locale.ROOT)}\u0000${durationMs / 5_000L}"

    private companion object {
        const val BASE_URL = "https://lyricsplus.prjktla.my.id/v1/lyrics/get"
        const val FAILURE_RETRY_MS = 30L * 60L * 1000L
    }
}

/** Parser is separate so metadata and script fallback can be unit-tested without networking. */
internal object LyricsLanguageResponseParser {
    private val languageNames = mapOf(
        "ml" to "Malayalam", "mal" to "Malayalam", "malayalam" to "Malayalam",
        "ta" to "Tamil", "tam" to "Tamil", "tamil" to "Tamil",
        "te" to "Telugu", "tel" to "Telugu", "telugu" to "Telugu",
        "kn" to "Kannada", "kan" to "Kannada", "kannada" to "Kannada",
        "hi" to "Hindi", "hin" to "Hindi", "hindi" to "Hindi",
        "pa" to "Punjabi", "pan" to "Punjabi", "punjabi" to "Punjabi",
        "bn" to "Bengali", "ben" to "Bengali", "bengali" to "Bengali", "bangla" to "Bengali",
        "mr" to "Marathi", "mar" to "Marathi", "marathi" to "Marathi",
        "gu" to "Gujarati", "guj" to "Gujarati", "gujarati" to "Gujarati",
        "or" to "Odia", "ori" to "Odia", "odia" to "Odia", "oriya" to "Odia",
        "ur" to "Urdu", "urd" to "Urdu", "urdu" to "Urdu",
        "ar" to "Arabic", "ara" to "Arabic", "arabic" to "Arabic",
        "en" to "English", "eng" to "English", "english" to "English",
        "es" to "Spanish", "spa" to "Spanish", "spanish" to "Spanish",
        "fr" to "French", "fra" to "French", "fre" to "French", "french" to "French",
        "pt" to "Portuguese", "por" to "Portuguese", "portuguese" to "Portuguese",
        "de" to "German", "deu" to "German", "ger" to "German", "german" to "German",
        "ko" to "Korean", "kor" to "Korean", "korean" to "Korean",
        "ja" to "Japanese", "jpn" to "Japanese", "japanese" to "Japanese",
        "zh" to "Chinese", "zho" to "Chinese", "chi" to "Chinese", "chinese" to "Chinese"
    )

    fun parse(jsonText: String): String? {
        val root = runCatching { Json.parseToJsonElement(jsonText) }.getOrNull() ?: return null
        findLanguage(root, 0)?.let { return it }
        val lyrics = StringBuilder()
        collectLyrics(root, false, lyrics, 0)
        if (lyrics.isNotEmpty()) {
            val inferred = ListeningLanguageClassifier.classify(lyrics.toString(), null)
            if (inferred != "Unknown" && !inferred.startsWith("Latin script")) return inferred
        }
        return null
    }

    private fun findLanguage(value: JsonElement, depth: Int): String? {
        if (depth > 12) return null
        return when (value) {
            is JsonObject -> {
                value.entries.firstNotNullOfOrNull { (key, child) ->
                    if (key.lowercase(Locale.ROOT) in LANGUAGE_KEYS &&
                        child is JsonPrimitive && child.isString
                    ) normalizeLanguage(child.content) else null
                } ?: value.values.firstNotNullOfOrNull { findLanguage(it, depth + 1) }
            }
            is JsonArray -> value.firstNotNullOfOrNull { findLanguage(it, depth + 1) }
            else -> null
        }
    }

    private fun normalizeLanguage(raw: String): String? {
        val normalized = raw.trim().lowercase(Locale.ROOT)
            .replace('_', '-')
            .substringBefore('-')
            .substringBefore(';')
            .trim()
        return languageNames[normalized]
    }

    private fun collectLyrics(value: JsonElement, insideLyrics: Boolean, output: StringBuilder, depth: Int) {
        if (depth > 12 || output.length >= MAX_LYRICS_CHARS) return
        when (value) {
            is JsonObject -> value.entries.forEach { (key, child) ->
                if (output.length >= MAX_LYRICS_CHARS) return
                val keyLower = key.lowercase(Locale.ROOT)
                val inLyrics = insideLyrics ||
                    keyLower.contains("lyric") ||
                    keyLower in setOf("lines", "words", "content")
                if (inLyrics && child is JsonPrimitive && child.isString) {
                    output.append(child.content).append('\n')
                } else {
                    collectLyrics(child, inLyrics, output, depth + 1)
                }
            }
            is JsonArray -> value.forEach { child ->
                collectLyrics(child, insideLyrics, output, depth + 1)
            }
            is JsonPrimitive -> if (insideLyrics && value.isString) {
                output.append(value.contentOrNull.orEmpty()).append('\n')
            }
        }
    }

    private val LANGUAGE_KEYS = setOf("language", "lang", "languagecode", "language_code", "language-code")
    private const val MAX_LYRICS_CHARS = 20_000
}
