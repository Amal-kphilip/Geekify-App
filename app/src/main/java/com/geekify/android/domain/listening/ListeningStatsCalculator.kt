package com.geekify.android.domain.listening

import com.geekify.android.data.local.ListeningSessionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MIN_MEANINGFUL_LISTEN_MS = 10_000L
private const val STREAK_DAY_LISTEN_MS = 10 * 60_000L
private const val DAY_MS = 24 * 60 * 60_000L

/**
 * Estimates a track's language without a language field in the source metadata.
 * Distinct scripts and explicit title tags take priority; romanized titles are only
 * assigned when the title contains high-confidence language clues.
 */
object ListeningLanguageClassifier {
    private data class ScriptRule(val label: String, val range: CharRange)

    private val scriptRules = listOf(
        ScriptRule("Malayalam", '\u0D00'..'\u0D7F'),
        ScriptRule("Tamil", '\u0B80'..'\u0BFF'),
        ScriptRule("Telugu", '\u0C00'..'\u0C7F'),
        ScriptRule("Kannada", '\u0C80'..'\u0CFF'),
        ScriptRule("Bengali", '\u0980'..'\u09FF'),
        ScriptRule("Punjabi (Gurmukhi script)", '\u0A00'..'\u0A7F'),
        ScriptRule("Gujarati", '\u0A80'..'\u0AFF'),
        ScriptRule("Odia", '\u0B00'..'\u0B7F'),
        ScriptRule("Devanagari (Hindi/Marathi etc.)", '\u0900'..'\u097F'),
        ScriptRule("Arabic script (Urdu/Arabic etc.)", '\u0600'..'\u06FF'),
        ScriptRule("Korean", '\uAC00'..'\uD7AF'),
        ScriptRule("Japanese", '\u3040'..'\u30FF'),
        ScriptRule("Chinese/Han script", '\u4E00'..'\u9FFF'),
        ScriptRule("Cyrillic (Russian etc.)", '\u0400'..'\u04FF'),
        ScriptRule("Greek", '\u0370'..'\u03FF')
    )

    private data class RomanizedRule(
        val language: String,
        val anchors: Set<String> = emptySet(),
        val strongWords: Set<String> = emptySet(),
        val supportingWords: Set<String> = emptySet(),
        val anchorPhrases: Set<String> = emptySet()
    )

    /*
     * These are clues, not a translation model. Anchor terms/phrases are uncommon
     * enough to stand on their own; broader words need multiple matching signals.
     */
    private val romanizedRules = listOf(
        RomanizedRule(
            "Malayalam",
            anchors = setOf("mazha", "pavizha", "aaromale", "chembakame", "khalbile", "mizhiyil", "pulari", "nilave"),
            strongWords = setOf("njan", "ente", "ninte", "mazhayil", "manass", "engane", "evide", "cheyyum", "raave", "malare", "ormakal", "azhake", "ninakku", "kiliye"),
            supportingWords = setOf("alle", "aane", "aanu", "nammal", "ningal", "chechi", "chetta", "pinne", "ivide", "enthe", "poove"),
            anchorPhrases = setOf("pavizha mazha", "ente nenjil", "malayalam song")
        ),
        RomanizedRule(
            "Tamil",
            anchors = setOf("kuthu", "kuththu", "thalaiva", "thalapathy", "tamizh", "ennadi", "azhagiya", "nenjukkul", "peidhidum", "vaazhkai"),
            strongWords = setOf("enakku", "unakku", "kadhal", "kaadhal", "uyire", "vizhiyil", "vaadi", "sollu", "machi", "machan", "ponnu", "thamizha", "poda", "podi", "thirudi"),
            supportingWords = setOf("anbe", "naan", "nee", "enna", "kanna", "paattu", "amma", "appa"),
            anchorPhrases = setOf("arabic kuthu", "why this kolaveri", "tamil song")
        ),
        RomanizedRule(
            "Telugu",
            anchors = setOf("nuvvu", "nuvve", "naatu", "naatu", "vellipomaakey", "choopultho", "baagundi", "kannulalo"),
            strongWords = setOf("neeku", "naaku", "ninnu", "vachave", "andham", "manasu", "telugu", "preminchana", "yedalo", "pillaga"),
            supportingWords = setOf("prema", "pilla", "chala", "raave", "nenu", "ammaye", "naa"),
            anchorPhrases = setOf("naatu naatu", "telugu song")
        ),
        RomanizedRule(
            "Kannada",
            anchors = setOf("jotheyali", "belageddu", "kannadathi", "hudugi", "preethi", "maduve"),
            strongWords = setOf("kannada", "ninna", "naanu", "neenu", "banni", "illi", "yenagali", "magale", "hoovu"),
            supportingWords = setOf("nanna", "ondu", "yenu", "nodu", "maatu", "maadu", "jeeva"),
            anchorPhrases = setOf("kannada song")
        ),
        RomanizedRule(
            "Hindi",
            anchors = setOf("pyaar", "pyar", "ishq", "zindagi", "chahiye", "raabta", "dhadkan", "aashiqui", "bekhayali", "kesariya", "humsafar", "bepanah", "sajde", "khuda"),
            strongWords = setOf("tumse", "tumhara", "tumhari", "deewani", "deewana", "mohabbat", "jaane", "jeena", "jeene", "sanam", "sajna", "piya", "khamosh", "duniya"),
            supportingWords = setOf("tum", "tumhe", "mera", "meri", "mere", "tera", "teri", "tere", "hai", "hain", "hoon", "nahi", "nahin", "kya", "kyun", "dil", "jaan", "rang", "saath"),
            anchorPhrases = setOf("tum hi ho", "tere liye", "dil se", "channa mereya", "hindi song")
        ),
        RomanizedRule(
            "Punjabi",
            anchors = setOf("gabru", "kudi", "munde", "mitran", "sohni", "patola", "launglaachi", "bhangra", "gidda", "jatt", "jatta", "vail", "punjabi"),
            strongWords = setOf("sardar", "pind", "jind", "koka", "maahi", "sohnea", "chakde", "naal"),
            supportingWords = setOf("ve", "ni", "balle", "sajna", "tere", "gora"),
            anchorPhrases = setOf("punjabi song", "laung laachi")
        ),
        RomanizedRule(
            "Bengali",
            anchors = setOf("bhalobasha", "bhalobashi", "brishti", "shudhu", "bojhena", "kothay", "bangla", "bengali", "chokh"),
            strongWords = setOf("tumi", "ami", "amar", "tomar", "bhalo", "kemon", "jibon", "moner", "priyo", "shona", "ekla"),
            supportingWords = setOf("mon", "toke", "batashe"),
            anchorPhrases = setOf("bengali song", "bangla gaan")
        ),
        RomanizedRule(
            "Marathi",
            anchors = setOf("majha", "majhi", "majhya", "marathi", "tuza", "tuzi", "manat", "manala"),
            strongWords = setOf("tula", "mala", "aahe", "ahe", "gaani", "sundara", "saajani"),
            supportingWords = setOf("mi", "tu", "kay", "rani"),
            anchorPhrases = setOf("marathi song")
        ),
        RomanizedRule(
            "Gujarati",
            anchors = setOf("gujarati", "kemcho", "dikri", "chokri", "garba", "gujju"),
            strongWords = setOf("tame", "mane", "tari", "maru", "chhe"),
            supportingWords = setOf("tara", "prem"),
            anchorPhrases = setOf("kem cho", "gujarati song")
        ),
        RomanizedRule(
            "Odia",
            anchors = setOf("odia", "odiya", "kemiti", "pakhare"),
            strongWords = setOf("mora", "tora", "tume", "bhala"),
            supportingWords = setOf("mo", "mu"),
            anchorPhrases = setOf("odia song")
        ),
        RomanizedRule(
            "Korean",
            anchors = setOf("saranghae", "annyeong", "gomawo", "eotteoke", "mianhae", "gwiyeowo", "jinjja"),
            strongWords = setOf("neomu", "chingu", "unnie", "oppa", "sarang"),
            supportingWords = setOf("uri", "bogo", "geurae"),
            anchorPhrases = setOf("korean song")
        ),
        RomanizedRule(
            "Japanese",
            anchors = setOf("arigatou", "arigato", "sayonara", "daisuki", "yoroshiku", "watashi"),
            strongWords = setOf("kokoro", "kawaii", "sekai", "yume"),
            supportingWords = setOf("kimi", "boku", "sakura"),
            anchorPhrases = setOf("japanese song")
        ),
        RomanizedRule(
            "Spanish",
            anchors = setOf("despacito", "bailando", "besame", "corazon"),
            strongWords = setOf("cancion", "bailar", "quiero", "amor", "noche", "vida"),
            supportingWords = setOf("para", "con", "mi", "tu", "yo", "te"),
            anchorPhrases = setOf("te quiero", "mi vida", "corazon partio", "spanish song")
        ),
        RomanizedRule(
            "English",
            strongWords = setOf("because", "without", "tonight", "tomorrow", "goodbye", "somebody", "everybody", "nothing", "everything", "nobody", "anywhere", "somewhere", "believe", "dancing", "lonely", "broken", "heaven", "angel", "perfect", "closer", "faded", "attention", "paradise", "memories", "heartbreak", "heartache", "sunshine", "midnight", "moonlight", "beautiful", "forever", "wonderland", "starlight", "yesterday"),
            supportingWords = setOf("the", "you", "your", "with", "from", "are", "we", "me", "my", "what", "where", "when", "why", "how", "never", "can", "not", "dont", "cant", "ill", "im", "its", "in", "on", "of", "to", "for", "and", "but", "if", "like", "this", "that", "all", "one", "more"),
            anchorPhrases = setOf("stand by me", "let it be", "shape of you", "love me like you do", "someone like you", "what makes you beautiful", "take me home", "we are the champions", "english song")
        )
    )

    private val languageAliases = linkedMapOf(
        "Malayalam" to listOf("malayalam", "malayali"),
        "Tamil" to listOf("tamil", "tamizh"),
        "Telugu" to listOf("telugu"),
        "Kannada" to listOf("kannada"),
        "Hindi" to listOf("hindi"),
        "Punjabi" to listOf("punjabi"),
        "Bengali" to listOf("bengali", "bangla"),
        "Marathi" to listOf("marathi"),
        "Gujarati" to listOf("gujarati"),
        "Odia" to listOf("odia", "odiya", "oriya"),
        "Urdu" to listOf("urdu"),
        "Arabic" to listOf("arabic"),
        "Korean" to listOf("korean", "k-pop"),
        "Japanese" to listOf("japanese", "j-pop"),
        "Chinese" to listOf("chinese", "mandarin"),
        "English" to listOf("english"),
        "Spanish" to listOf("spanish"),
        "French" to listOf("french"),
        "Portuguese" to listOf("portuguese"),
        "German" to listOf("german")
    )

    fun classify(title: String?, artist: String?): String {
        val trackTitle = title.orEmpty().trim()
        val trackArtist = artist.orEmpty().trim()

        explicitLanguageTag(trackTitle)?.let { return it }
        detectScript(trackTitle)?.let { return it }

        // Prefer evidence from the song title over the artist's name: artists often
        // perform in multiple languages, so their name alone must not label a song.
        val normalizedTitle = normalizeLatinText(trackTitle)
        if (normalizedTitle.isNotBlank()) {
            val tokens = Regex("[a-z]+(?:'[a-z]+)?").findAll(normalizedTitle)
                .map { it.value.trim('\'') }
                .filter { it.isNotBlank() }
                .toSet()

            val ranked = romanizedRules.map { rule ->
                var score = 0.0
                score += rule.anchors.count { it in tokens } * 4.0
                score += rule.strongWords.count { it in tokens } * 2.0
                score += rule.supportingWords.count { it in tokens } * 1.0
                score += rule.anchorPhrases.count { phrase ->
                    Regex("(?:^|\\s)${Regex.escape(phrase)}(?:$|\\s)").containsMatchIn(normalizedTitle)
                } * 4.0
                rule.language to score
            }.sortedByDescending { it.second }

            val best = ranked.firstOrNull()
            val runnerUp = ranked.getOrNull(1)?.second ?: 0.0
            if (best != null && best.second >= 3.5 && best.second - runnerUp >= 1.5) {
                return best.first
            }
            // An ambiguous Latin title is not made more certain by the artist's script.
            return "Latin script (language unclear)"
        }

        detectScript(trackArtist)?.let { return it }
        return if (trackArtist.any(Char::isLetter)) "Latin script (language unclear)" else "Unknown"
    }

    private fun explicitLanguageTag(title: String): String? {
        if (title.isBlank()) return null
        languageAliases.forEach { (language, aliases) ->
            aliases.forEach { alias ->
                val escaped = Regex.escape(alias)
                val tagged = Regex(
                    "(?i)(?:\\[\\s*$escaped\\s*]|\\(\\s*$escaped\\s*\\)|" +
                        "\\b(?:language|lyrics?|audio|version|song)\\s*(?:in|[:=-])?\\s+$escaped\\b|" +
                        "\\b$escaped\\s+(?:song|version|audio|lyrics?|language)\\b|" +
                        "(?:^|[-–—|•])\\s*$escaped\\s*$)"
                )
                if (tagged.containsMatchIn(title) || normalizeLatinText(title) == normalizeLatinText(alias)) {
                    return language
                }
            }
        }
        return null
    }

    private fun detectScript(text: String): String? {
        if (text.isBlank()) return null
        val counts = scriptRules.mapNotNull { rule ->
            val count = text.count { it in rule.range && it.isLetter() }
            if (count > 0) rule to count else null
        }
        if (counts.isEmpty()) return null
        // Prefer Japanese when kana appears; Han-only text remains ambiguous.
        if (text.any { it in '\u3040'..'\u30FF' }) return "Japanese"
        return counts.maxByOrNull { it.second }?.first?.label
    }

    private fun normalizeLatinText(text: String): String {
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        return normalized
            .filter { java.lang.Character.getType(it) != java.lang.Character.NON_SPACING_MARK.toInt() }
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9']+"), " ")
            .trim()
    }
}
data class TrackListeningTotal(
    val videoId: String,
    val title: String,
    val artist: String,
    val listenedMs: Long,
    val sessions: Int
)

data class ArtistListeningTotal(
    val id: String,
    val name: String,
    val listenedMs: Long,
    val sessions: Int
)

data class LanguageListeningTotal(val name: String, val listenedMs: Long)
data class DailyListeningTotal(val label: String, val listenedMs: Long)
data class DiscoveredArtist(val id: String, val name: String, val firstHeardAt: Long)

data class ListeningStatsSnapshot(
    val last7DaysMs: Long = 0L,
    val last30DaysMs: Long = 0L,
    val dailyListening: List<DailyListeningTotal> = emptyList(),
    val topTracks: List<TrackListeningTotal> = emptyList(),
    val topArtists: List<ArtistListeningTotal> = emptyList(),
    val languages: List<LanguageListeningTotal> = emptyList(),
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val recentlyDiscoveredArtists: List<DiscoveredArtist> = emptyList()
) {
    val hasData: Boolean get() = last30DaysMs > 0L || topTracks.isNotEmpty() || longestStreakDays > 0
}

/** Aggregations are pure and deterministic so time-window, streak and ranking behavior can be unit-tested. */
object ListeningStatsCalculator {
    fun calculate(
        sessions: List<ListeningSessionEntity>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        languageOverrides: Map<String, String> = emptyMap()
    ): ListeningStatsSnapshot {
        val meaningful = sessions.filter { it.listenedMs >= MIN_MEANINGFUL_LISTEN_MS }
        val monthSince = now - 30L * DAY_MS
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val weekStartDate = today.minusDays(6)
        val weekStartMs = weekStartDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val weekly = meaningful.filter { it.startedAt in weekStartMs..now }
        val monthly = meaningful.filter { it.startedAt in monthSince..now }
        val weeklyByDate = weekly.groupBy { localDate(it.startedAt, zone) }
        val dailyListening = (0L..6L).map { offset ->
            val date = weekStartDate.plusDays(offset)
            DailyListeningTotal(
                label = date.format(DateTimeFormatter.ofPattern("EE", Locale.getDefault())),
                listenedMs = weeklyByDate[date].orEmpty().sumOf { it.listenedMs }
            )
        }

        val topTracks = monthly.groupBy { it.videoId }.map { (id, rows) ->
            val newest = rows.maxByOrNull { it.startedAt } ?: return@map null
            TrackListeningTotal(
                videoId = id,
                title = newest.title,
                artist = newest.artist,
                listenedMs = rows.sumOf { it.listenedMs },
                sessions = rows.size
            )
        }.filterNotNull().sortedWith(compareByDescending<TrackListeningTotal> { it.listenedMs }.thenBy { it.title.lowercase(Locale.ROOT) }).take(5)

        data class MutableArtist(var name: String, var listenedMs: Long = 0L, var sessions: Int = 0)
        val artistTotals = linkedMapOf<String, MutableArtist>()
        monthly.forEach { session ->
            artistEntries(session.artist, session.artists.map { it.id to it.name }).forEach { (id, name) ->
                val total = artistTotals.getOrPut(id) { MutableArtist(name) }
                total.name = name
                total.listenedMs += session.listenedMs
                total.sessions += 1
            }
        }
        val topArtists = artistTotals.map { (id, v) -> ArtistListeningTotal(id, v.name, v.listenedMs, v.sessions) }
            .sortedWith(compareByDescending<ArtistListeningTotal> { it.listenedMs }.thenBy { it.name.lowercase(Locale.ROOT) })
            .take(5)

        // A source-provided language (or language inferred from lyrics script) is more
        // reliable than a title-only estimate. Keep the existing classifier as fallback.
        val languageTotals = monthly.groupBy { session ->
            languageOverrides[session.videoId]
                ?: ListeningLanguageClassifier.classify(session.title, session.artist)
        }
            .map { (language, rows) -> LanguageListeningTotal(language, rows.sumOf { it.listenedMs }) }
            .sortedByDescending { it.listenedMs }
            .take(6)

        val dailyMs = meaningful.groupBy { localDate(it.startedAt, zone) }
            .mapValues { (_, rows) -> rows.sumOf { it.listenedMs } }
        val qualifyingDays = dailyMs.filterValues { it >= STREAK_DAY_LISTEN_MS }.keys
        val currentStart = when {
            today in qualifyingDays -> today
            today.minusDays(1) in qualifyingDays -> today.minusDays(1)
            else -> null
        }
        var currentStreak = 0
        if (currentStart != null) {
            var date: LocalDate = currentStart
            while (date in qualifyingDays) {
                currentStreak++
                date = date.minusDays(1)
            }
        }
        val sortedDays = qualifyingDays.sorted()
        var longestStreak = 0
        var runLength = 0
        var previous: LocalDate? = null
        sortedDays.forEach { date ->
            runLength = if (previous != null && previous!!.plusDays(1) == date) runLength + 1 else 1
            longestStreak = maxOf(longestStreak, runLength)
            previous = date
        }

        // An artist is “discovered” only if the first meaningful local listen is within the last 30 days.
        data class FirstArtist(val name: String, val at: Long)
        val firstByArtist = linkedMapOf<String, FirstArtist>()
        meaningful.forEach { session ->
            artistEntries(session.artist, session.artists.map { it.id to it.name }).forEach { (id, name) ->
                val current = firstByArtist[id]
                if (current == null || session.startedAt < current.at) {
                    firstByArtist[id] = FirstArtist(name, session.startedAt)
                }
            }
        }
        val discovered = firstByArtist.mapNotNull { (id, first) ->
            if (first.at in monthSince..now) DiscoveredArtist(id, first.name, first.at) else null
        }.sortedByDescending { it.firstHeardAt }.take(5)

        return ListeningStatsSnapshot(
            last7DaysMs = weekly.sumOf { it.listenedMs },
            last30DaysMs = monthly.sumOf { it.listenedMs },
            dailyListening = dailyListening,
            topTracks = topTracks,
            topArtists = topArtists,
            languages = languageTotals,
            currentStreakDays = currentStreak,
            longestStreakDays = longestStreak,
            recentlyDiscoveredArtists = discovered
        )
    }

    private fun localDate(timestamp: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

    private fun artistEntries(
        fallbackArtist: String,
        artists: List<Pair<String?, String>>
    ): List<Pair<String, String>> {
        val valid = artists.mapNotNull { (id, name) ->
            name.trim().takeIf { it.isNotEmpty() }?.let { (id?.takeIf(String::isNotBlank)?.let { key -> "id:$key" } ?: "name:${it.lowercase(Locale.ROOT)}") to it }
        }
        if (valid.isNotEmpty()) return valid.distinctBy { it.first }
        return fallbackArtist.trim().takeIf { it.isNotEmpty() }
            ?.let { listOf(("name:" + it.lowercase(Locale.ROOT)) to it) }.orEmpty()
    }

    fun formatDuration(durationMs: Long): String {
        val minutes = (durationMs.coerceAtLeast(0L) / 60_000L).toInt()
        val hours = minutes / 60
        val remainder = minutes % 60
        return when {
            hours > 0 && remainder > 0 -> "${hours}h ${remainder}m"
            hours > 0 -> "${hours}h"
            minutes > 0 -> "${minutes}m"
            durationMs <= 0L -> "0m"
            else -> "<1m"
        }
    }

    fun formatDate(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(timestamp).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
}
