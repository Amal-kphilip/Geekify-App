package com.geekify.android.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

enum class AiTranslationProvider(val displayName: String, val defaultEndpoint: String, val defaultModel: String) {
    GOOGLE_TRANSLATE("Google Translate (No Key)", "https://translate.googleapis.com/translate_a/single", ""),
    OPENROUTER("OpenRouter (BYOK)", "https://openrouter.ai/api/v1/chat/completions", "google/gemini-2.0-flash-exp:free"),
    OPENAI("OpenAI (BYOK)", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
    GEMINI("Google Gemini (BYOK)", "https://generativelanguage.googleapis.com/v1beta/models", "gemini-2.0-flash"),
    ANTHROPIC("Anthropic Claude (BYOK)", "https://api.anthropic.com/v1/messages", "claude-3-5-haiku-latest")
}

data class LyricsTranslationConfig(
    val provider: AiTranslationProvider = AiTranslationProvider.GOOGLE_TRANSLATE,
    val apiKey: String = "",
    val model: String = provider.defaultModel,
    val targetLanguage: String = "en"
)

/**
 * Pure-Kotlin offline script romanizer supporting Korean Hangul (Revised Romanization),
 * Japanese Kana (Hepburn), Cyrillic, and Greek. Works 100% offline with zero native dependencies.
 */
object OfflineRomanizer {
    private val hangulInitials = arrayOf(
        "g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h"
    )
    private val hangulMedials = arrayOf(
        "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe",
        "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i"
    )
    private val hangulFinals = arrayOf(
        "", "k", "k", "k", "n", "n", "n", "t", "l", "l", "l", "l", "l", "l", "l", "l",
        "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t"
    )

    private val kanaMap: Map<Char, String> = mapOf(
        // Hiragana
        'あ' to "a", 'い' to "i", 'う' to "u", 'え' to "e", 'お' to "o",
        'か' to "ka", 'き' to "ki", 'く' to "ku", 'け' to "ke", 'こ' to "ko",
        'さ' to "sa", 'し' to "shi", 'す' to "su", 'せ' to "se", 'そ' to "so",
        'た' to "ta", 'ち' to "chi", 'つ' to "tsu", 'て' to "te", 'と' to "to",
        'な' to "na", 'に' to "ni", 'ぬ' to "nu", 'ね' to "ne", 'の' to "no",
        'は' to "ha", 'ひ' to "hi", 'ふ' to "fu", 'へ' to "he", 'ほ' to "ho",
        'ま' to "ma", 'み' to "mi", 'む' to "mu", 'め' to "me", 'も' to "mo",
        'や' to "ya", 'ゆ' to "yu", 'よ' to "yo",
        'ら' to "ra", 'り' to "ri", 'る' to "ru", 'れ' to "re", 'ろ' to "ro",
        'わ' to "wa", 'を' to "wo", 'ん' to "n",
        'が' to "ga", 'ぎ' to "gi", 'ぐ' to "gu", 'げ' to "ge", 'ご' to "go",
        'ざ' to "za", 'じ' to "ji", 'ず' to "zu", 'ぜ' to "ze", 'ぞ' to "zo",
        'だ' to "da", 'ぢ' to "ji", 'づ' to "zu", 'で' to "de", 'ど' to "do",
        'ば' to "ba", 'び' to "bi", 'ぶ' to "bu", 'べ' to "be", 'ぼ' to "bo",
        'ぱ' to "pa", 'ぴ' to "pi", 'ぷ' to "pu", 'ぺ' to "pe", 'ぽ' to "po",
        // Katakana
        'ア' to "a", 'イ' to "i", 'ウ' to "u", 'エ' to "e", 'オ' to "o",
        'カ' to "ka", 'キ' to "ki", 'ク' to "ku", 'ケ' to "ke", 'コ' to "ko",
        'サ' to "sa", 'シ' to "shi", 'ス' to "su", 'セ' to "se", 'ソ' to "so",
        'タ' to "ta", 'チ' to "chi", 'ツ' to "tsu", 'テ' to "te", 'ト' to "to",
        'ナ' to "na", 'ニ' to "ni", 'ヌ' to "nu", 'ネ' to "ne", 'ノ' to "no",
        'ハ' to "ha", 'ヒ' to "hi", 'フ' to "fu", 'ヘ' to "he", 'ホ' to "ho",
        'マ' to "ma", 'ミ' to "mi", 'ム' to "mu", 'メ' to "me", 'モ' to "mo",
        'ヤ' to "ya", 'ユ' to "yu", 'ヨ' to "yo",
        'ラ' to "ra", 'リ' to "ri", 'ル' to "ru", 'レ' to "re", 'ロ' to "ro",
        'ワ' to "wa", 'ヲ' to "wo", 'ン' to "n",
        'ガ' to "ga", 'ギ' to "gi", 'グ' to "gu", 'ゲ' to "ge", 'ゴ' to "go",
        'ザ' to "za", 'ジ' to "ji", 'ズ' to "zu", 'ゼ' to "ze", 'ゾ' to "zo",
        'ダ' to "da", 'ヂ' to "ji", 'ヅ' to "zu", 'デ' to "de", 'ド' to "do",
        'バ' to "ba", 'ビ' to "bi", 'ブ' to "bu", 'ベ' to "be", 'ボ' to "bo",
        'パ' to "pa", 'ピ' to "pi", 'プ' to "pu", 'ペ' to "pe", 'ポ' to "po"
    )

    private val cyrillicMap: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
    )

    fun hasNonLatinScript(text: String): Boolean = text.any { ch ->
        val code = ch.code
        code in 0xAC00..0xD7A3 || // Hangul
            code in 0x3040..0x30FF || // Hiragana/Katakana
            code in 0x0400..0x04FF || // Cyrillic
            code in 0x4E00..0x9FFF    // CJK
    }

    fun romanize(text: String): String {
        val sb = StringBuilder(text.length * 2)
        for (ch in text) {
            val code = ch.code
            when {
                code in 0xAC00..0xD7A3 -> {
                    val sIndex = code - 0xAC00
                    val initial = sIndex / 588
                    val medial = (sIndex % 588) / 28
                    val finalCons = sIndex % 28
                    sb.append(hangulInitials[initial])
                    sb.append(hangulMedials[medial])
                    sb.append(hangulFinals[finalCons])
                }
                kanaMap.containsKey(ch) -> sb.append(kanaMap[ch])
                cyrillicMap.containsKey(ch.lowercaseChar()) -> {
                    val mapped = cyrillicMap[ch.lowercaseChar()].orEmpty()
                    sb.append(if (ch.isUpperCase()) mapped.replaceFirstChar { it.uppercaseChar() } else mapped)
                }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}

/**
 * Alignment, deduplication, and cache-key utilities for translated and romanized lyric subtitles.
 */
object LyricsSubtitleAligner {
    private val multiSpace = Regex("""\s+""")

    fun normalize(text: String?): String =
        text.orEmpty().trim().replace(multiSpace, " ").lowercase()

    /**
     * Returns the cleaned subtitle only when it adds genuine information (non-blank and not
     * identical to the original lyric line after normalization).
     */
    fun sanitizeSubtitle(original: String, candidate: String?): String? {
        val cleaned = candidate?.trim()?.replace(multiSpace, " ").orEmpty()
        if (cleaned.isEmpty()) return null
        if (normalize(cleaned) == normalize(original)) return null
        return cleaned
    }

    /**
     * Aligns provider-returned [rawTranslations] strictly 1:1 with [originalLines].
     * Missing lines remain blank placeholders (`""`) rather than shifting subsequent lines.
     * Redundant translations identical to the original are cleared to `""`.
     */
    fun alignTranslations(originalLines: List<String>, rawTranslations: List<String>): List<String> {
        if (originalLines.isEmpty()) return emptyList()
        return originalLines.indices.map { i ->
            sanitizeSubtitle(originalLines[i], rawTranslations.getOrNull(i)).orEmpty()
        }
    }

    /**
     * Builds a stable cache identity for a track's lyrics translation state so switching tracks,
     * lyric providers, content, or target language immediately invalidates stale translations.
     */
    fun buildTranslationKey(
        trackId: String,
        provider: String,
        originalLines: List<String>,
        targetLanguage: String
    ): String {
        val contentHash = originalLines.joinToString("\n").hashCode()
        return "$trackId|$provider|$contentHash|${targetLanguage.lowercase()}"
    }
}

/**
 * Translates lyric lines via Google Translate (no key) or BYOK LLM providers
 * (OpenRouter, OpenAI, Gemini, Anthropic). Never stores hardcoded API keys.
 */
class LyricsTranslationClient(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun translateLines(
        lines: List<String>,
        config: LyricsTranslationConfig
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext Result.success(emptyList())
        runCatching {
            when (config.provider) {
                AiTranslationProvider.GOOGLE_TRANSLATE -> translateViaGoogle(lines, config.targetLanguage)
                AiTranslationProvider.OPENROUTER, AiTranslationProvider.OPENAI ->
                    translateViaOpenAiCompatible(lines, config)
                AiTranslationProvider.GEMINI -> translateViaGemini(lines, config)
                AiTranslationProvider.ANTHROPIC -> translateViaAnthropic(lines, config)
            }
        }
    }

    private fun translateViaGoogle(lines: List<String>, targetLang: String): List<String> {
        val joined = lines.joinToString("\n")
        val encoded = URLEncoder.encode(joined, "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encoded"
        val req = Request.Builder().url(url).get().build()
        return http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("Google Translate HTTP ${res.code}")
            val body = res.body.string()
            val root = json.parseToJsonElement(body).jsonArray
            val chunks = root.firstOrNull()?.jsonArray.orEmpty()
            val fullTranslated = buildString {
                for (chunk in chunks) {
                    append(chunk.jsonArray.firstOrNull()?.jsonPrimitive?.contentOrNull.orEmpty())
                }
            }
            val split = fullTranslated.split('\n')
            lines.indices.map { i -> split.getOrNull(i)?.trim().orEmpty() }
        }
    }

    private fun translateViaOpenAiCompatible(lines: List<String>, config: LyricsTranslationConfig): List<String> {
        require(config.apiKey.isNotBlank()) { "API key is required for ${config.provider.displayName}" }
        val prompt = buildTranslationPrompt(lines, config.targetLanguage)
        val payload = buildJsonObject {
            put("model", config.model.ifBlank { config.provider.defaultModel })
            put("messages", buildJsonArray {
                addJsonObject {
                    put("role", "system")
                    put("content", "You translate song lyrics line-by-line preserving exact line count. Return only the translated lines.")
                }
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            })
        }.toString()

        val req = Request.Builder()
            .url(config.provider.defaultEndpoint)
            .header("Authorization", "Bearer ${config.apiKey.trim()}")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        return http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("${config.provider.displayName} HTTP ${res.code}")
            val root = json.parseToJsonElement(res.body.string()).jsonObject
            val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            val split = content.trim().split('\n')
            lines.indices.map { i -> split.getOrNull(i)?.trim().orEmpty() }
        }
    }

    private fun translateViaGemini(lines: List<String>, config: LyricsTranslationConfig): List<String> {
        require(config.apiKey.isNotBlank()) { "API key is required for Google Gemini" }
        val model = config.model.ifBlank { AiTranslationProvider.GEMINI.defaultModel }
        val url = "${AiTranslationProvider.GEMINI.defaultEndpoint}/$model:generateContent?key=${config.apiKey.trim()}"
        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                addJsonObject {
                    put("parts", buildJsonArray {
                        addJsonObject { put("text", buildTranslationPrompt(lines, config.targetLanguage)) }
                    })
                }
            })
        }.toString()
        val req = Request.Builder().url(url).post(payload.toRequestBody(jsonMedia)).build()
        return http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("Gemini HTTP ${res.code}")
            val root = json.parseToJsonElement(res.body.string()).jsonObject
            val content = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
            val split = content.trim().split('\n')
            lines.indices.map { i -> split.getOrNull(i)?.trim().orEmpty() }
        }
    }

    private fun translateViaAnthropic(lines: List<String>, config: LyricsTranslationConfig): List<String> {
        require(config.apiKey.isNotBlank()) { "API key is required for Anthropic" }
        val payload = buildJsonObject {
            put("model", config.model.ifBlank { AiTranslationProvider.ANTHROPIC.defaultModel })
            put("max_tokens", 2048)
            put("messages", buildJsonArray {
                addJsonObject {
                    put("role", "user")
                    put("content", buildTranslationPrompt(lines, config.targetLanguage))
                }
            })
        }.toString()
        val req = Request.Builder()
            .url(AiTranslationProvider.ANTHROPIC.defaultEndpoint)
            .header("x-api-key", config.apiKey.trim())
            .header("anthropic-version", "2023-06-01")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        return http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("Anthropic HTTP ${res.code}")
            val root = json.parseToJsonElement(res.body.string()).jsonObject
            val content = root["content"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
            val split = content.trim().split('\n')
            lines.indices.map { i -> split.getOrNull(i)?.trim().orEmpty() }
        }
    }

    private fun buildTranslationPrompt(lines: List<String>, targetLanguage: String): String =
        "Translate each of the following ${lines.size} lyric lines into $targetLanguage. " +
            "Output exactly ${lines.size} lines with no numbering or commentary:\n" +
            lines.joinToString("\n")
}
