package com.geekify.android.lyrics

import android.util.Base64
import com.geekify.android.data.local.LyricsDao
import com.geekify.android.data.local.LyricsEntity
import com.geekify.android.innertube.YouTube
import com.geekify.android.innertube.models.WatchEndpoint
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class LyricsWord(
    val startMs: Long,
    val endMs: Long = 0L,
    val text: String
)

data class LyricsLine(
    val timeMs: Long,
    val endMs: Long = 0L,
    val text: String,
    val words: List<LyricsWord> = emptyList(),
    val embeddedTranslation: String? = null,
    val embeddedRomanization: String? = null
)

enum class LyricsSyncType {
    WORD_SYNCED,
    LINE_SYNCED,
    UNSYNCED
}

data class ParsedLyrics(
    val lines: List<LyricsLine>,
    val isSynced: Boolean,
    val provider: String,
    val offsetMs: Long = 0L,
    val syncType: LyricsSyncType = when {
        !isSynced -> LyricsSyncType.UNSYNCED
        lines.any { it.words.isNotEmpty() } -> LyricsSyncType.WORD_SYNCED
        else -> LyricsSyncType.LINE_SYNCED
    },
    val rawText: String = ""
) {
    val hasWordSync: Boolean
        get() = syncType == LyricsSyncType.WORD_SYNCED || lines.any { it.words.isNotEmpty() }

    fun activeLineIndex(positionMs: Long): Int {
        if (!isSynced || lines.isEmpty()) return -1
        val adjusted = (positionMs + offsetMs).coerceAtLeast(0L)
        var active = -1
        for (i in lines.indices) {
            val t = lines[i].timeMs
            if (t in 0..adjusted) {
                active = i
            } else if (t > adjusted) {
                break
            }
        }
        return active
    }

    fun activeWordIndex(lineIndex: Int, positionMs: Long): Int {
        if (!isSynced || lineIndex !in lines.indices) return -1
        val words = lines[lineIndex].words
        if (words.isEmpty()) return -1
        val adjusted = (positionMs + offsetMs).coerceAtLeast(0L)
        var active = -1
        for (i in words.indices) {
            if (words[i].startMs <= adjusted) {
                active = i
            } else {
                break
            }
        }
        return active
    }
}

sealed interface LyricsLookupOutcome {
    data class Success(
        val lyrics: ParsedLyrics,
        val alternatives: List<ParsedLyrics> = emptyList()
    ) : LyricsLookupOutcome

    data class NoMatch(
        val queryTitle: String,
        val queryArtist: String
    ) : LyricsLookupOutcome

    data class NetworkFailure(
        val message: String
    ) : LyricsLookupOutcome

    data class InvalidResponse(
        val provider: String,
        val message: String
    ) : LyricsLookupOutcome
}

/**
 * Normalizes track metadata, duration units, and candidate matching for multi-provider lyrics lookup.
 */
object LyricsMetadataNormalizer {
    private val YOUTUBE_VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

    private val MEDIA_NOISE_TAG_REGEX = Regex(
        """\s*[\(\[【「『](?:Official\s*(?:Music\s*|Lyric\s*|HD\s*|4K\s*)?(?:Video|Audio|Visualizer)|Music\s*Video|Lyric\s*Video|Lyrics?\s*(?:Video)?|Visualizer|Full\s*(?:Video|Song|Audio)|HD|4K|HQ|Explicit|Clean\s*Version|Audio\s*Only)[\)\]】」』]""",
        RegexOption.IGNORE_CASE
    )

    private val BRACKETED_FEAT_OR_REMASTER_REGEX = Regex(
        """\s*[\(\[【](?:feat\.?|ft\.?|featuring|featured\s+in|with|prod\.?\s*by|remaster(?:ed)?(?:\s*\d{4})?|bonus\s*track|deluxe(?:\s*edition)?|from\s+)[^\)\]】]*[\)\]】]""",
        RegexOption.IGNORE_CASE
    )

    private val PIPE_OR_DASH_VIDEO_SUFFIX_REGEX = Regex(
        """\s+-\s+(?:Official\s*)?(?:Lyric(?:al)?\s*Video|Music\s*Video|Video\s*Song|Full\s*Video|Audio\s*Song|Full\s*Audio)\b.*$|\s+\|\s+.*$""",
        RegexOption.IGNORE_CASE
    )

    private val TRAILING_FEAT_or_REMASTER_REGEX = Regex(
        """(?:\s+-\s+|\s+)(?:feat\.?|ft\.?|featuring)\s+.*$|\s+-\s+(?:\d{4}\s+)?remaster(?:ed)?(?:\s+\d{4})?.*$""",
        RegexOption.IGNORE_CASE
    )

    private val ARTIST_SUFFIX_NOISE_REGEX = Regex(
        """\s*-\s*Topic$|\s*VEVO$|\s*Official$""",
        RegexOption.IGNORE_CASE
    )

    private val ARTIST_SPLIT_REGEX = Regex(
        """\s*(?:,|&|/|;|•|\b(?:featuring|feat\.?|ft\.?|with|vs\.?|x)(?=\s|$))\s*""",
        RegexOption.IGNORE_CASE
    )

    private val NON_ALPHANUM_REGEX = Regex("""[^\p{L}\p{N}\s]""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")

    fun isValidYouTubeVideoId(videoId: String?): Boolean {
        if (videoId.isNullOrBlank()) return false
        return YOUTUBE_VIDEO_ID_REGEX.matches(videoId.trim())
    }

    /**
     * Ensures milliseconds and seconds are never mixed.
     * - If [durationSeconds] is accidentally in milliseconds (> 18_000, i.e. > 5 hours), converts to seconds.
     * - If [durationSeconds] is missing or <= 0, falls back to [durationMs] / 1000.
     */
    fun resolveDurationSeconds(durationSeconds: Int?, durationMs: Long? = null): Int? {
        val fromSec = durationSeconds?.takeIf { it > 0 }?.let { raw ->
            if (raw > 18_000) (raw / 1000).takeIf { it > 0 } else raw
        }
        if (fromSec != null) return fromSec
        val fromMs = durationMs?.takeIf { it > 0L }?.let { (it / 1000L).toInt() }?.takeIf { it > 0 }
        return fromMs
    }

    /**
     * Cleans noise labels like "(Official Video)", "[Lyrics]", "(Official Audio)" while keeping
     * meaningful song title parts intact.
     */
    fun cleanSearchTitle(rawTitle: String): String {
        val stripped = rawTitle
            .replace(MEDIA_NOISE_TAG_REGEX, "")
            .replace(PIPE_OR_DASH_VIDEO_SUFFIX_REGEX, "")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
        return stripped.ifBlank { rawTitle.trim() }
    }

    /**
     * Secondary fallback title stripping "(feat. ...)" and "- Remastered 2020" decorators.
     */
    fun cleanCoreTitle(rawTitle: String): String {
        val base = cleanSearchTitle(rawTitle)
            .replace(BRACKETED_FEAT_OR_REMASTER_REGEX, "")
            .replace(TRAILING_FEAT_or_REMASTER_REGEX, "")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
        return base.ifBlank { cleanSearchTitle(rawTitle) }
    }

    fun cleanArtistDisplay(rawArtist: String): String {
        return rawArtist
            .replace(ARTIST_SUFFIX_NOISE_REGEX, "")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    fun extractArtistTokens(rawArtist: String): List<String> {
        val cleaned = cleanArtistDisplay(rawArtist)
        if (cleaned.isBlank()) return emptyList()
        return cleaned
            .split(ARTIST_SPLIT_REGEX)
            .map { it.replace(ARTIST_SUFFIX_NOISE_REGEX, "").trim() }
            .filter { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
            .distinct()
    }

    fun primaryArtist(rawArtist: String): String {
        return extractArtistTokens(rawArtist).firstOrNull() ?: cleanArtistDisplay(rawArtist)
    }

    fun normalizeForComparison(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text
            .lowercase()
            .replace(NON_ALPHANUM_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    /**
     * Validates that a provider candidate actually matches the requested track title, artist, and duration.
     */
    fun matchesCandidate(
        targetTitle: String,
        targetArtist: String,
        targetDurationSec: Int?,
        candidateTitle: String?,
        candidateArtist: String?,
        candidateDurationSec: Double?,
        durationToleranceSec: Double = 12.0
    ): Boolean {
        val candTitleNorm = normalizeForComparison(cleanCoreTitle(candidateTitle.orEmpty()))
        val targetFullNorm = normalizeForComparison(cleanSearchTitle(targetTitle))
        val targetCoreNorm = normalizeForComparison(cleanCoreTitle(targetTitle))
        if (candTitleNorm.isEmpty() || targetCoreNorm.isEmpty()) return false

        val titleMatches = candTitleNorm == targetFullNorm ||
            candTitleNorm == targetCoreNorm ||
            candTitleNorm.contains(targetCoreNorm) ||
            targetCoreNorm.contains(candTitleNorm)
        if (!titleMatches) return false

        // Reject unwanted remix/karaoke/instrumental mismatch if original title didn't ask for it
        val mismatchedVariant = listOf("karaoke", "instrumental", "tribute", "8d audio", "nightcore").any { kw ->
            candTitleNorm.contains(kw) && !targetFullNorm.contains(kw)
        }
        if (mismatchedVariant) return false

        // Verify artist overlap when target artist is known
        val targetArtistTokens = extractArtistTokens(targetArtist).map(::normalizeForComparison).filter { it.isNotEmpty() }
        val candArtistNorm = normalizeForComparison(candidateArtist)
        val candArtistTokens = extractArtistTokens(candidateArtist.orEmpty()).map(::normalizeForComparison).filter { it.isNotEmpty() }

        if (targetArtistTokens.isNotEmpty() && candArtistNorm.isNotEmpty()) {
            val hasArtistOverlap = targetArtistTokens.any { targetToken ->
                candArtistNorm.contains(targetToken) ||
                    candArtistTokens.any { candToken ->
                        candToken == targetToken || candToken.contains(targetToken) || targetToken.contains(candToken)
                    }
            }
            if (!hasArtistOverlap) return false
        }

        // Verify duration when both target and candidate durations are known
        if (targetDurationSec != null && targetDurationSec > 0 && candidateDurationSec != null && candidateDurationSec > 0.0) {
            if (abs(candidateDurationSec - targetDurationSec.toDouble()) > durationToleranceSec) {
                return false
            }
        }

        return true
    }
}

object LrcParser {
    private val lineTimeRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val wordTimeRegex = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>\s*([^<\r\n]+)""")
    private val offsetTagRegex = Regex("""^\[\s*offset\s*:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val metadataTagRegex = Regex("""^\[\s*(?:ti|ar|al|au|by|length|re|ve|tool|id)\s*:.*]$""", RegexOption.IGNORE_CASE)

    fun parse(raw: String, provider: String, offsetMs: Long = 0L): ParsedLyrics {
        val synced = mutableListOf<LyricsLine>()
        val plain = mutableListOf<LyricsLine>()
        var embeddedOffsetMs = 0L

        for (rawLine in raw.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            val offsetMatch = offsetTagRegex.find(trimmed)
            if (offsetMatch != null) {
                embeddedOffsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                continue
            }
            if (metadataTagRegex.matches(trimmed) && !lineTimeRegex.containsMatchIn(trimmed)) {
                continue
            }

            // Collect all leading/inline [mm:ss.xxx] timestamps on this line
            val matches = lineTimeRegex.findAll(trimmed).toList()
            if (matches.isNotEmpty()) {
                val content = trimmed.replace(lineTimeRegex, "").trim()
                val wordMatches = wordTimeRegex.findAll(content).toList()
                val words = if (wordMatches.isNotEmpty()) {
                    val parsedWords = wordMatches.map { wm ->
                        LyricsWord(
                            startMs = parseTimestampMs(wm.groupValues[1], wm.groupValues[2], wm.groupValues[3]),
                            text = wm.groupValues[4].trim()
                        )
                    }.filter { it.text.isNotEmpty() }
                    parsedWords.mapIndexed { idx, w ->
                        val nextStart = parsedWords.getOrNull(idx + 1)?.startMs ?: (w.startMs + 400L)
                        w.copy(endMs = nextStart.coerceAtLeast(w.startMs))
                    }
                } else {
                    emptyList()
                }

                val cleanText = if (words.isNotEmpty()) {
                    words.joinToString(" ") { it.text }.trim()
                } else {
                    content.replace(Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>"""), "").trim()
                }

                if (cleanText.isNotEmpty()) {
                    for (m in matches) {
                        val ts = (parseTimestampMs(m.groupValues[1], m.groupValues[2], m.groupValues[3]) - embeddedOffsetMs)
                            .coerceAtLeast(0L)
                        synced += LyricsLine(timeMs = ts, text = cleanText, words = words)
                    }
                }
            } else if (!trimmed.startsWith("[")) {
                plain += LyricsLine(timeMs = -1L, text = trimmed)
            }
        }

        val sortedUnique = synced
            .sortedBy { it.timeMs }
            .fold(mutableListOf<LyricsLine>()) { acc, line ->
                val normText = normalizeLineForDedupe(line.text)
                val prev = acc.lastOrNull()
                // Deduplicate ONLY genuine duplicates sharing the exact same timestamp AND normalized text.
                // Intentionally repeated chorus lines at different timestamps are always preserved.
                if (prev != null && prev.timeMs == line.timeMs && normalizeLineForDedupe(prev.text) == normText) {
                    if (prev.words.isEmpty() && line.words.isNotEmpty()) {
                        acc[acc.lastIndex] = line
                    }
                } else {
                    acc.add(line)
                }
                acc
            }

        val sortedSynced = sortedUnique.mapIndexed { idx, line ->
            val nextTime = sortedUnique.getOrNull(idx + 1)?.timeMs ?: (line.timeMs + 4_000L)
            if (line.endMs <= line.timeMs) line.copy(endMs = nextTime.coerceAtLeast(line.timeMs)) else line
        }

        // Validate that synced lyrics actually have usable playback timing (not all 00:00.000)
        val hasRealTiming = sortedSynced.size >= 2 && sortedSynced.any { it.timeMs > 0L }
        return if (hasRealTiming) {
            val hasWords = sortedSynced.any { it.words.isNotEmpty() }
            ParsedLyrics(
                lines = sortedSynced,
                isSynced = true,
                provider = provider,
                offsetMs = offsetMs,
                syncType = if (hasWords) LyricsSyncType.WORD_SYNCED else LyricsSyncType.LINE_SYNCED,
                rawText = raw
            )
        } else {
            val fallbackPlain = if (plain.isNotEmpty()) {
                plain
            } else {
                sortedSynced.map { it.copy(timeMs = -1L, endMs = 0L, words = emptyList()) }
            }
            ParsedLyrics(
                lines = fallbackPlain,
                isSynced = false,
                provider = provider,
                offsetMs = offsetMs,
                syncType = LyricsSyncType.UNSYNCED,
                rawText = raw
            )
        }
    }

    internal fun normalizeLineForDedupe(text: String): String =
        text.trim().replace(Regex("""\s+"""), " ").lowercase()

    internal fun parseTimestampMs(minStr: String, secStr: String, fracStr: String): Long {
        val mins = minStr.toLongOrNull() ?: 0L
        val secs = secStr.toLongOrNull() ?: 0L
        val ms = when (fracStr.length) {
            0 -> 0L
            1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
            2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
            else -> fracStr.take(3).toLongOrNull() ?: 0L
        }
        return mins * 60_000L + secs * 1_000L + ms
    }

    fun toLrcString(lyrics: ParsedLyrics): String {
        if (lyrics.rawText.isNotBlank()) return lyrics.rawText
        return lyrics.lines.joinToString("\n") { line ->
            if (!lyrics.isSynced || line.timeMs < 0L) {
                line.text
            } else {
                val mins = line.timeMs / 60_000L
                val secs = (line.timeMs / 1_000L) % 60L
                val ms = line.timeMs % 1_000L
                val prefix = "[%02d:%02d.%03d]".format(mins, secs, ms)
                if (line.words.isNotEmpty()) {
                    val wordBody = line.words.joinToString(" ") { w ->
                        val wm = w.startMs / 60_000L
                        val ws = (w.startMs / 1_000L) % 60L
                        val wms = w.startMs % 1_000L
                        "<%02d:%02d.%03d>%s".format(wm, ws, wms, w.text)
                    }
                    "$prefix$wordBody"
                } else {
                    "$prefix${line.text}"
                }
            }
        }
    }
}

/**
 * Lightweight TTML (Timed Text Markup Language) parser supporting both line-level `<p>`
 * and word-level `<span>` timings returned by BetterLyrics / Apple Music TTML endpoints.
 */
object TtmlParser {
    private val pTagRegex = Regex("""<p\b([^>]*)>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
    private val spanTagRegex = Regex("""<span\b([^>]*)>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE)
    private val beginAttrRegex = Regex("""\bbegin\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val endAttrRegex = Regex("""\bend\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val roleAttrRegex = Regex("""\b(?:[a-zA-Z0-9_-]+:)?role\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val xmlTagStripRegex = Regex("""<[^>]+>""")
    private val multiSpaceRegex = Regex("""\s+""")

    private fun isTranslationRole(role: String?): Boolean {
        val r = role?.trim()?.lowercase() ?: return false
        return r == "x-translation" || r == "translation" || r.endsWith(":translation") || r.contains("x-translation")
    }

    private fun isRomanizationRole(role: String?): Boolean {
        val r = role?.trim()?.lowercase() ?: return false
        return r == "x-roman" || r == "x-romanization" || r == "romanization" || r.contains("x-roman")
    }

    fun parse(ttml: String, provider: String, offsetMs: Long = 0L): ParsedLyrics? {
        if (ttml.isBlank() || !ttml.contains("<p", ignoreCase = true)) return null
        val lines = mutableListOf<LyricsLine>()
        val translationsByTime = mutableMapOf<Long, String>()
        val romanizationsByTime = mutableMapOf<Long, String>()

        for (pMatch in pTagRegex.findAll(ttml)) {
            val pAttrs = pMatch.groupValues[1]
            val pInner = pMatch.groupValues[2]
            val pRole = roleAttrRegex.find(pAttrs)?.groupValues?.get(1)
            val pBeginMs = beginAttrRegex.find(pAttrs)?.groupValues?.get(1)?.let(::parseTtmlTimeMs)
            val pEndMs = endAttrRegex.find(pAttrs)?.groupValues?.get(1)?.let(::parseTtmlTimeMs) ?: 0L

            // Paragraph-level translation / romanization roles must NEVER become original lyric lines
            if (isTranslationRole(pRole)) {
                val trText = decodeXmlEntities(pInner.replace(xmlTagStripRegex, "")).replace(multiSpaceRegex, " ").trim()
                if (trText.isNotEmpty() && pBeginMs != null) {
                    translationsByTime.putIfAbsent(pBeginMs, trText)
                }
                continue
            }
            if (isRomanizationRole(pRole)) {
                val romText = decodeXmlEntities(pInner.replace(xmlTagStripRegex, "")).replace(multiSpaceRegex, " ").trim()
                if (romText.isNotEmpty() && pBeginMs != null) {
                    romanizationsByTime.putIfAbsent(pBeginMs, romText)
                }
                continue
            }

            val words = mutableListOf<LyricsWord>()
            val spanTranslations = mutableListOf<String>()
            val spanRomanizations = mutableListOf<String>()

            for (spanMatch in spanTagRegex.findAll(pInner)) {
                val sAttrs = spanMatch.groupValues[1]
                val sRole = roleAttrRegex.find(sAttrs)?.groupValues?.get(1)
                val sText = decodeXmlEntities(spanMatch.groupValues[2].replace(xmlTagStripRegex, ""))
                    .replace(multiSpaceRegex, " ")
                    .trim()
                if (isTranslationRole(sRole)) {
                    if (sText.isNotEmpty()) spanTranslations += sText
                    continue
                }
                if (isRomanizationRole(sRole)) {
                    if (sText.isNotEmpty()) spanRomanizations += sText
                    continue
                }
                val sBegin = beginAttrRegex.find(sAttrs)?.groupValues?.get(1)?.let(::parseTtmlTimeMs) ?: continue
                val sEnd = endAttrRegex.find(sAttrs)?.groupValues?.get(1)?.let(::parseTtmlTimeMs) ?: sBegin
                if (sText.isNotEmpty()) {
                    words += LyricsWord(startMs = sBegin, endMs = sEnd, text = sText)
                }
            }

            // Strip translation/romanization spans BEFORE extracting fallback plain paragraph text
            val cleanedInner = spanTagRegex.replace(pInner) { m ->
                val sRole = roleAttrRegex.find(m.groupValues[1])?.groupValues?.get(1)
                if (isTranslationRole(sRole) || isRomanizationRole(sRole)) "" else m.value
            }

            val lineText = if (words.isNotEmpty()) {
                words.joinToString(" ") { it.text }.trim()
            } else {
                decodeXmlEntities(cleanedInner.replace(xmlTagStripRegex, "")).replace(multiSpaceRegex, " ").trim()
            }

            if (lineText.isEmpty()) continue
            val resolvedStartMs = pBeginMs ?: words.firstOrNull()?.startMs ?: -1L
            lines += LyricsLine(
                timeMs = resolvedStartMs,
                endMs = if (pEndMs > resolvedStartMs) pEndMs else (words.lastOrNull()?.endMs ?: 0L),
                text = lineText,
                words = words,
                embeddedTranslation = spanTranslations.joinToString(" ").trim().takeIf { it.isNotEmpty() },
                embeddedRomanization = spanRomanizations.joinToString(" ").trim().takeIf { it.isNotEmpty() }
            )
        }

        if (lines.isEmpty()) return null
        val sortedUnique = lines
            .sortedBy { it.timeMs.coerceAtLeast(0L) }
            .fold(mutableListOf<LyricsLine>()) { acc, line ->
                val normText = LrcParser.normalizeLineForDedupe(line.text)
                val prev = acc.lastOrNull()
                if (prev != null && prev.timeMs == line.timeMs && LrcParser.normalizeLineForDedupe(prev.text) == normText) {
                    if (prev.words.isEmpty() && line.words.isNotEmpty()) {
                        acc[acc.lastIndex] = line
                    }
                } else {
                    acc.add(line)
                }
                acc
            }
            .map { line ->
                line.copy(
                    embeddedTranslation = line.embeddedTranslation ?: translationsByTime[line.timeMs],
                    embeddedRomanization = line.embeddedRomanization ?: romanizationsByTime[line.timeMs]
                )
            }

        val isSynced = sortedUnique.size >= 2 && sortedUnique.any { it.timeMs > 0L }
        val hasWords = isSynced && sortedUnique.any { it.words.isNotEmpty() }
        val syncType = when {
            hasWords -> LyricsSyncType.WORD_SYNCED
            isSynced -> LyricsSyncType.LINE_SYNCED
            else -> LyricsSyncType.UNSYNCED
        }
        val parsed = ParsedLyrics(
            lines = sortedUnique,
            isSynced = isSynced,
            provider = provider,
            offsetMs = offsetMs,
            syncType = syncType
        )
        return parsed.copy(rawText = LrcParser.toLrcString(parsed))
    }

    internal fun parseTtmlTimeMs(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.endsWith("ms", ignoreCase = true)) {
            return s.dropLast(2).trim().toDoubleOrNull()?.toLong()
        }
        if (s.endsWith("s", ignoreCase = true) && !s.contains(':')) {
            return (s.dropLast(1).trim().toDoubleOrNull()?.times(1000.0))?.toLong()
        }
        val parts = s.split(':')
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                val sec = parts[2].toDoubleOrNull() ?: return null
                h * 3_600_000L + m * 60_000L + (sec * 1000.0).toLong()
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: return null
                val sec = parts[1].toDoubleOrNull() ?: return null
                m * 60_000L + (sec * 1000.0).toLong()
            }
            1 -> {
                val sec = parts[0].toDoubleOrNull() ?: return null
                (sec * 1000.0).toLong()
            }
            else -> null
        }
    }

    private fun decodeXmlEntities(text: String): String =
        text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
}

/**
 * Multi-provider lyrics client supporting:
 * 1. BetterLyrics (word-synced & line-synced TTML)
 * 2. SimpMusic (video-ID richSyncLyrics & syncedLyrics)
 * 3. LrcLib (search + exact match with metadata normalization & candidate verification)
 * 4. KuGou (keyword + duration search -> Base64 LRC download)
 * 5. YouTube Music / InnerTube (official lyrics endpoint + timed subtitle transcript fallback)
 */
class LyricsClient(private val http: OkHttpClient) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    data class FetchedLyrics(
        val rawLyrics: String,
        val provider: String,
        val parsed: ParsedLyrics
    )

    suspend fun fetch(
        title: String,
        artist: String,
        durationSeconds: Int?,
        videoId: String? = null,
        album: String? = null
    ): FetchedLyrics? = withContext(Dispatchers.IO) {
        when (val outcome = fetchOutcome(title, artist, durationSeconds, videoId, album)) {
            is LyricsLookupOutcome.Success -> FetchedLyrics(
                rawLyrics = outcome.lyrics.rawText.ifBlank { LrcParser.toLrcString(outcome.lyrics) },
                provider = outcome.lyrics.provider,
                parsed = outcome.lyrics
            )
            else -> null
        }
    }

    suspend fun fetchOutcome(
        title: String,
        artist: String,
        durationSeconds: Int?,
        videoId: String? = null,
        album: String? = null,
        collectAllAlternatives: Boolean = false
    ): LyricsLookupOutcome = withContext(Dispatchers.IO) {
        val cleanTitle = LyricsMetadataNormalizer.cleanSearchTitle(title)
        val coreTitle = LyricsMetadataNormalizer.cleanCoreTitle(title)
        val cleanArtist = LyricsMetadataNormalizer.cleanArtistDisplay(artist)
        val primaryArtist = LyricsMetadataNormalizer.primaryArtist(artist)
        val resolvedDurationSec = LyricsMetadataNormalizer.resolveDurationSeconds(durationSeconds)
        val validVideoId = videoId?.takeIf { LyricsMetadataNormalizer.isValidYouTubeVideoId(it) }

        if (cleanTitle.isBlank()) {
            return@withContext LyricsLookupOutcome.NoMatch(title, artist)
        }

        val candidates = mutableListOf<ParsedLyrics>()
        var hadNetworkFailure = false
        var lastNetworkMessage: String? = null
        var hadInvalidResponse = false
        var lastInvalidProvider: String? = null

        suspend fun runProvider(name: String, block: suspend () -> ParsedLyrics?) {
            try {
                val res = block()
                if (res != null && res.lines.isNotEmpty()) {
                    candidates += res
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (io: IOException) {
                hadNetworkFailure = true
                lastNetworkMessage = io.message ?: "Network error contacting $name"
            } catch (e: Exception) {
                hadInvalidResponse = true
                lastInvalidProvider = name
            }
        }

        fun hasSyncedCandidate(): Boolean = candidates.any { it.isSynced }
        fun hasWordSyncedCandidate(): Boolean = candidates.any { it.hasWordSync }

        // 1. Video-specific SimpMusic provider (returns richSyncLyrics or syncedLyrics by videoId)
        if (validVideoId != null) {
            runProvider("SimpMusic") {
                fetchFromSimpMusic(validVideoId, resolvedDurationSec)
            }
        }

        // 2. BetterLyrics (TTML word-synced / line-synced)
        if (collectAllAlternatives || !hasWordSyncedCandidate()) {
            runProvider("BetterLyrics") {
                fetchFromBetterLyrics(cleanTitle, primaryArtist.ifBlank { cleanArtist }, resolvedDurationSec, album)
                    ?: if (coreTitle != cleanTitle) {
                        fetchFromBetterLyrics(coreTitle, primaryArtist, resolvedDurationSec, null)
                    } else null
            }
        }

        // 3. LrcLib (Search + Exact endpoint with candidate validation)
        if (collectAllAlternatives || !hasSyncedCandidate()) {
            runProvider("LrcLib") {
                fetchFromLrcLib(cleanTitle, cleanArtist, primaryArtist, resolvedDurationSec, album)
            }
        }

        // 4. KuGou (Synchronized LRC search + download)
        if (collectAllAlternatives || !hasSyncedCandidate()) {
            runProvider("KuGou") {
                fetchFromKuGou(coreTitle, primaryArtist.ifBlank { cleanArtist }, resolvedDurationSec)
            }
        }

        // 5. YouTube Music Official Lyrics (ANDROID_MUSIC timedLyricsData + plain fallback) & Timed Transcript
        if (validVideoId != null && (collectAllAlternatives || !hasSyncedCandidate())) {
            runProvider("YouTube Music") {
                fetchFromYouTubeMusicLyrics(validVideoId)
            }
        }
        if (validVideoId != null && (collectAllAlternatives || !hasSyncedCandidate())) {
            runProvider("YouTube Subtitle") {
                fetchFromYouTubeTranscript(validVideoId)
            }
        }

        if (candidates.isNotEmpty()) {
            val ranked = candidates.sortedByDescending { parsed ->
                when (parsed.syncType) {
                    LyricsSyncType.WORD_SYNCED -> 300
                    LyricsSyncType.LINE_SYNCED -> 200
                    LyricsSyncType.UNSYNCED -> 100
                } + parsed.lines.size.coerceAtMost(90)
            }
            val best = ranked.first()
            return@withContext LyricsLookupOutcome.Success(
                lyrics = best,
                alternatives = ranked.distinctBy { "${it.provider}:${it.syncType}" }
            )
        }

        return@withContext when {
            hadNetworkFailure -> LyricsLookupOutcome.NetworkFailure(
                lastNetworkMessage ?: "Unable to reach lyrics providers. Check your connection and retry."
            )
            hadInvalidResponse -> LyricsLookupOutcome.InvalidResponse(
                provider = lastInvalidProvider ?: "Lyrics Provider",
                message = "Received an invalid response from ${lastInvalidProvider ?: "provider"}."
            )
            else -> LyricsLookupOutcome.NoMatch(cleanTitle, cleanArtist)
        }
    }

    // ---- Provider 1: SimpMusic (by YouTube videoId) ----
    internal fun fetchFromSimpMusic(videoId: String, durationSeconds: Int?): ParsedLyrics? {
        val url = "https://api-lyrics.simpmusic.org/v1/$videoId"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Geekify/Android")
            .header("Accept", "application/json")
            .get()
            .build()

        http.newCall(req).execute().use { res ->
            if (res.code == 404) return null
            if (!res.isSuccessful) throw IOException("SimpMusic HTTP ${res.code}")
            val body = res.body.string()
            if (body.isBlank()) return null
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonArray ?: return null
            if (data.isEmpty()) return null

            val matching = if (durationSeconds != null && durationSeconds > 0) {
                val filtered = data.filter { el ->
                    val d = el.jsonObject["durationSeconds"]?.jsonPrimitive?.intOrNull ?: 0
                    d <= 0 || abs(d - durationSeconds) <= 12
                }
                filtered.ifEmpty { data.toList() }
            } else {
                data.toList()
            }

            val bestObj = (if (durationSeconds != null && durationSeconds > 0) {
                matching.minByOrNull { el ->
                    abs((el.jsonObject["durationSeconds"]?.jsonPrimitive?.intOrNull ?: durationSeconds) - durationSeconds)
                }
            } else {
                matching.firstOrNull()
            })?.jsonObject ?: return null

            val richSync = bestObj["richSyncLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val synced = bestObj["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val plain = bestObj["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val chosen = richSync ?: synced ?: plain ?: return null
            return LrcParser.parse(chosen, provider = "SimpMusic")
        }
    }

    // ---- Provider 2: BetterLyrics (TTML word/line synced) ----
    internal fun fetchFromBetterLyrics(
        title: String,
        artist: String,
        durationSeconds: Int?,
        album: String?
    ): ParsedLyrics? {
        val qTrack = URLEncoder.encode(title.trim(), "UTF-8")
        val qArtist = URLEncoder.encode(artist.trim(), "UTF-8")
        val durParam = durationSeconds?.takeIf { it > 0 }?.let { "&d=$it" }.orEmpty()
        val albParam = album?.takeIf { it.isNotBlank() }?.let { "&al=${URLEncoder.encode(it.trim(), "UTF-8")}" }.orEmpty()
        val url = "https://lyrics-api.boidu.dev/getLyrics?s=$qTrack&a=$qArtist$durParam$albParam"

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Geekify/Android")
            .header("Accept", "application/json")
            .get()
            .build()

        http.newCall(req).execute().use { res ->
            if (res.code == 404 || res.code == 400) return null
            if (!res.isSuccessful) throw IOException("BetterLyrics HTTP ${res.code}")
            val body = res.body.string()
            if (body.isBlank()) return null
            val obj = json.parseToJsonElement(body).jsonObject
            val ttml = obj["ttml"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            return TtmlParser.parse(ttml, provider = "BetterLyrics")
        }
    }

    // ---- Provider 3: LrcLib (Search + Exact endpoint with candidate scoring) ----
    internal fun fetchFromLrcLib(
        cleanTitle: String,
        cleanArtist: String,
        primaryArtist: String,
        durationSeconds: Int?,
        album: String?
    ): ParsedLyrics? {
        val coreTitle = LyricsMetadataNormalizer.cleanCoreTitle(cleanTitle)

        // Try /api/search first because it returns multiple candidates and tolerates minor duration/artist variations
        val searchQueries = buildList {
            add(cleanTitle to primaryArtist)
            if (coreTitle != cleanTitle) add(coreTitle to primaryArtist)
            if (cleanArtist != primaryArtist && cleanArtist.isNotBlank()) add(coreTitle to cleanArtist)
            add(coreTitle to "")
        }.distinct()

        var bestPlainFallback: ParsedLyrics? = null

        for ((qTitle, qArtist) in searchQueries) {
            val candidates = queryLrcLibSearch(qTitle, qArtist)
            if (candidates.isEmpty()) continue

            val matched = candidates.mapNotNull { obj ->
                val instrumental = obj["instrumental"]?.jsonPrimitive?.booleanOrNull == true
                if (instrumental) return@mapNotNull null

                val candName = obj["trackName"]?.jsonPrimitive?.contentOrNull
                    ?: obj["name"]?.jsonPrimitive?.contentOrNull
                val candArtist = obj["artistName"]?.jsonPrimitive?.contentOrNull
                val candDur = obj["duration"]?.jsonPrimitive?.doubleOrNull
                val synced = obj["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val plain = obj["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                if (synced == null && plain == null) return@mapNotNull null

                if (!LyricsMetadataNormalizer.matchesCandidate(
                        targetTitle = cleanTitle,
                        targetArtist = cleanArtist,
                        targetDurationSec = durationSeconds,
                        candidateTitle = candName,
                        candidateArtist = candArtist,
                        candidateDurationSec = candDur
                    )
                ) {
                    return@mapNotNull null
                }

                val parsed = LrcParser.parse(synced ?: plain!!, provider = "LrcLib")
                val durPenalty = if (durationSeconds != null && candDur != null) {
                    abs(candDur - durationSeconds.toDouble())
                } else 0.0
                val score = (if (parsed.isSynced) 100.0 else 20.0) - durPenalty
                parsed to score
            }.sortedByDescending { it.second }

            val bestSynced = matched.firstOrNull { it.first.isSynced }?.first
            if (bestSynced != null) return bestSynced
            if (bestPlainFallback == null) {
                bestPlainFallback = matched.firstOrNull()?.first
            }
        }

        // Also try exact /api/get endpoint as a fallback
        val exact = queryLrcLibExact(coreTitle, primaryArtist, durationSeconds)
        if (exact != null && (exact.isSynced || bestPlainFallback == null)) {
            return exact
        }

        return bestPlainFallback
    }

    private fun queryLrcLibSearch(trackName: String, artistName: String): List<JsonObject> {
        val url = if (artistName.isNotBlank()) {
            val qt = URLEncoder.encode(trackName, "UTF-8")
            val qa = URLEncoder.encode(artistName, "UTF-8")
            "https://lrclib.net/api/search?track_name=$qt&artist_name=$qa"
        } else {
            val qq = URLEncoder.encode(trackName, "UTF-8")
            "https://lrclib.net/api/search?q=$qq"
        }
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Geekify/Android (https://github.com/geekify)")
            .header("Accept", "application/json")
            .get()
            .build()

        http.newCall(req).execute().use { res ->
            if (res.code == 404 || res.code == 400) return emptyList()
            if (!res.isSuccessful) throw IOException("LrcLib search HTTP ${res.code}")
            val body = res.body.string()
            if (body.isBlank()) return emptyList()
            val element = json.parseToJsonElement(body)
            return (element as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        }
    }

    private fun queryLrcLibExact(title: String, artist: String, durationSeconds: Int?): ParsedLyrics? {
        if (title.isBlank() || artist.isBlank()) return null
        val qTrack = URLEncoder.encode(title.trim(), "UTF-8")
        val qArtist = URLEncoder.encode(artist.trim(), "UTF-8")
        val durParam = durationSeconds?.takeIf { it > 0 }?.let { "&duration=$it" }.orEmpty()
        val url = "https://lrclib.net/api/get?track_name=$qTrack&artist_name=$qArtist$durParam"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Geekify/Android (https://github.com/geekify)")
            .header("Accept", "application/json")
            .get()
            .build()

        http.newCall(req).execute().use { res ->
            if (res.code == 404 || res.code == 400) return null
            if (!res.isSuccessful) throw IOException("LrcLib get HTTP ${res.code}")
            val body = res.body.string()
            if (body.isBlank()) return null
            val obj = json.parseToJsonElement(body).jsonObject
            val synced = obj["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val plain = obj["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val chosen = synced ?: plain ?: return null
            return LrcParser.parse(chosen, provider = "LrcLib")
        }
    }

    // ---- Provider 4: KuGou (Search + Base64 LRC download) ----
    internal fun fetchFromKuGou(
        title: String,
        artist: String,
        durationSeconds: Int?
    ): ParsedLyrics? {
        val keyword = if (artist.isNotBlank()) "$title - $artist" else title
        val encodedKeyword = URLEncoder.encode(keyword.trim(), "UTF-8")
        val durParam = durationSeconds?.takeIf { it > 0 }?.let { "&duration=${it * 1000L}" }.orEmpty()
        val searchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=$encodedKeyword$durParam"

        val searchReq = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", "Geekify/Android")
            .header("Accept", "application/json")
            .get()
            .build()

        val (id, accessKey) = http.newCall(searchReq).execute().use { res ->
            if (!res.isSuccessful) return null
            val body = res.body.string()
            if (body.isBlank()) return null
            val root = json.parseToJsonElement(body).jsonObject
            val candidates = root["candidates"]?.jsonArray ?: return null
            if (candidates.isEmpty()) return null

            val valid = candidates.mapNotNull { el ->
                val obj = el.jsonObject
                val cid = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val key = obj["accesskey"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val cDurRaw = obj["duration"]?.jsonPrimitive?.longOrNull ?: 0L
                val cDurSec = if (cDurRaw > 1000L) cDurRaw / 1000.0 else cDurRaw.toDouble()
                val candSong = obj["song"]?.jsonPrimitive?.contentOrNull ?: title
                val candSinger = obj["singer"]?.jsonPrimitive?.contentOrNull ?: artist

                if (!LyricsMetadataNormalizer.matchesCandidate(
                        targetTitle = title,
                        targetArtist = artist,
                        targetDurationSec = durationSeconds,
                        candidateTitle = candSong,
                        candidateArtist = candSinger,
                        candidateDurationSec = cDurSec.takeIf { it > 0.0 },
                        durationToleranceSec = 10.0
                    )
                ) {
                    return@mapNotNull null
                }
                Triple(cid, key, cDurSec)
            }

            val chosen = if (durationSeconds != null && durationSeconds > 0) {
                valid.minByOrNull { abs(it.third - durationSeconds.toDouble()) }
            } else {
                valid.firstOrNull()
            } ?: return null

            chosen.first to chosen.second
        }

        val dlUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8"
        val dlReq = Request.Builder()
            .url(dlUrl)
            .header("User-Agent", "Geekify/Android")
            .header("Accept", "application/json")
            .get()
            .build()

        http.newCall(dlReq).execute().use { res ->
            if (!res.isSuccessful) return null
            val body = res.body.string()
            if (body.isBlank()) return null
            val root = json.parseToJsonElement(body).jsonObject
            val base64Content = root["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            val decodedBytes = runCatching { Base64.decode(base64Content, Base64.DEFAULT) }.getOrNull()
                ?: runCatching { java.util.Base64.getDecoder().decode(base64Content) }.getOrNull()
                ?: return null
            val rawLrc = decodedBytes.toString(Charsets.UTF_8)
            val parsed = LrcParser.parse(rawLrc, provider = "KuGou")
            return parsed.takeIf { it.lines.isNotEmpty() }
        }
    }

    // ---- Provider 5A: YouTube Timed Transcript (LRC formatted cues) ----
    private suspend fun fetchFromYouTubeTranscript(videoId: String): ParsedLyrics? {
        val lrcText = YouTube.transcript(videoId).getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val parsed = LrcParser.parse(lrcText, provider = "YouTube Subtitle")
        return parsed.takeIf { it.lines.isNotEmpty() }
    }

    // ---- Provider 5B: Official YouTube Music Lyrics Tab ----
    private suspend fun fetchFromYouTubeMusicLyrics(videoId: String): ParsedLyrics? {
        val nextResult = YouTube.next(WatchEndpoint(videoId = videoId)).getOrNull() ?: return null
        val endpoint = nextResult.lyricsEndpoint ?: return null
        val text = YouTube.lyrics(endpoint).getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val parsed = LrcParser.parse(text, provider = "YouTube Music")
        return parsed.takeIf { it.lines.isNotEmpty() }
    }
}

class LyricsRepository(
    private val lyricsDao: LyricsDao,
    private val lyricsClient: LyricsClient
) {
    private data class NegativeCacheEntry(val timestampMs: Long, val outcome: LyricsLookupOutcome)

    private val negativeCache = ConcurrentHashMap<String, NegativeCacheEntry>()
    private val alternativesCache = ConcurrentHashMap<String, List<ParsedLyrics>>()

    companion object {
        internal const val NEGATIVE_CACHE_TTL_MS = 45_000L
        private const val UNSYNCED_UPGRADE_COOLDOWN_MS = 120_000L
    }

    fun observe(videoId: String): Flow<LyricsEntity?> = lyricsDao.observe(videoId)

    fun clearNegativeCache() {
        negativeCache.clear()
    }

    private fun buildCacheFingerprint(videoId: String, title: String, artist: String): String {
        val normTitle = LyricsMetadataNormalizer.normalizeForComparison(LyricsMetadataNormalizer.cleanCoreTitle(title))
        val normArtist = LyricsMetadataNormalizer.normalizeForComparison(LyricsMetadataNormalizer.primaryArtist(artist))
        return "$videoId|$normTitle|$normArtist"
    }

    suspend fun getOrFetchOutcome(
        videoId: String,
        title: String,
        artist: String,
        durationSeconds: Int?,
        durationMs: Long? = null,
        album: String? = null,
        forceRefresh: Boolean = false,
        collectAlternatives: Boolean = false
    ): LyricsLookupOutcome {
        val resolvedDuration = LyricsMetadataNormalizer.resolveDurationSeconds(durationSeconds, durationMs)
        val fingerprint = buildCacheFingerprint(videoId, title, artist)
        val now = System.currentTimeMillis()

        if (!forceRefresh && !collectAlternatives) {
            val cached = lyricsDao.find(videoId)
            if (cached != null && cached.rawLyrics.isNotBlank()) {
                val parsed = LrcParser.parse(cached.rawLyrics, cached.provider, cached.offsetMs)
                val isUserLocked = cached.provider.startsWith("User:")
                // Return cached lyrics immediately if they are synchronized, user-selected, or recently checked
                if (parsed.isSynced || isUserLocked || (now - cached.updatedAt) < UNSYNCED_UPGRADE_COOLDOWN_MS) {
                    val alts = alternativesCache[videoId].orEmpty().ifEmpty { listOf(parsed) }
                    return LyricsLookupOutcome.Success(parsed, alts)
                }
            }

            // Check bounded negative cache
            val neg = negativeCache[fingerprint]
            if (neg != null && (now - neg.timestampMs) < NEGATIVE_CACHE_TTL_MS) {
                return neg.outcome
            }
        } else {
            negativeCache.remove(fingerprint)
        }

        val outcome = lyricsClient.fetchOutcome(
            title = title,
            artist = artist,
            durationSeconds = resolvedDuration,
            videoId = videoId,
            album = album,
            collectAllAlternatives = collectAlternatives
        )

        when (outcome) {
            is LyricsLookupOutcome.Success -> {
                negativeCache.remove(fingerprint)
                val existingOffset = lyricsDao.find(videoId)?.offsetMs ?: 0L
                val rawToSave = outcome.lyrics.rawText.ifBlank { LrcParser.toLrcString(outcome.lyrics) }
                lyricsDao.upsert(
                    LyricsEntity(
                        videoId = videoId,
                        rawLyrics = rawToSave,
                        provider = outcome.lyrics.provider,
                        offsetMs = existingOffset,
                        updatedAt = now
                    )
                )
                val withOffset = outcome.lyrics.copy(offsetMs = existingOffset)
                val altsWithOffset = outcome.alternatives.map { it.copy(offsetMs = existingOffset) }
                alternativesCache[videoId] = altsWithOffset
                return LyricsLookupOutcome.Success(withOffset, altsWithOffset)
            }
            else -> {
                // If we had an older unsynchronized cached entry, preserve and return it rather than wiping it
                val existing = lyricsDao.find(videoId)
                if (existing != null && existing.rawLyrics.isNotBlank()) {
                    val parsed = LrcParser.parse(existing.rawLyrics, existing.provider, existing.offsetMs)
                    return LyricsLookupOutcome.Success(parsed, listOf(parsed))
                }
                if (outcome is LyricsLookupOutcome.NoMatch) {
                    negativeCache[fingerprint] = NegativeCacheEntry(now, outcome)
                }
                return outcome
            }
        }
    }

    suspend fun getOrFetch(
        videoId: String,
        title: String,
        artist: String,
        durationSeconds: Int?,
        forceRefresh: Boolean = false
    ): ParsedLyrics? {
        return when (val outcome = getOrFetchOutcome(
            videoId = videoId,
            title = title,
            artist = artist,
            durationSeconds = durationSeconds,
            forceRefresh = forceRefresh
        )) {
            is LyricsLookupOutcome.Success -> outcome.lyrics
            else -> null
        }
    }

    suspend fun selectAlternative(videoId: String, selected: ParsedLyrics): ParsedLyrics {
        val existingOffset = lyricsDao.find(videoId)?.offsetMs ?: selected.offsetMs
        val rawToSave = selected.rawText.ifBlank { LrcParser.toLrcString(selected) }
        val lockedProvider = if (selected.provider.startsWith("User:")) selected.provider else "User:${selected.provider}"
        lyricsDao.upsert(
            LyricsEntity(
                videoId = videoId,
                rawLyrics = rawToSave,
                provider = lockedProvider,
                offsetMs = existingOffset,
                updatedAt = System.currentTimeMillis()
            )
        )
        return selected.copy(provider = selected.provider.removePrefix("User:"), offsetMs = existingOffset)
    }

    suspend fun updateOffset(videoId: String, offsetMs: Long) {
        lyricsDao.updateOffset(videoId, offsetMs)
    }
}
