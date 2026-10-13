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

/** Conservative script-based inference; Latin-script tracks are deliberately not guessed as English. */
object ListeningLanguageClassifier {
    fun classify(title: String?, artist: String?): String {
        val text = "${title.orEmpty()} ${artist.orEmpty()}"
        return when {
            text.any { it in '\u0D00'..'\u0D7F' } -> "Malayalam"
            text.any { it in '\u0B80'..'\u0BFF' } -> "Tamil"
            text.any { it in '\u0C00'..'\u0C7F' } -> "Telugu"
            text.any { it in '\u0C80'..'\u0CFF' } -> "Kannada"
            text.any { it in '\u0980'..'\u09FF' } -> "Bengali"
            text.any { it in '\u0A00'..'\u0A7F' } -> "Punjabi (Gurmukhi script)"
            text.any { it in '\u0A80'..'\u0AFF' } -> "Gujarati"
            text.any { it in '\u0B00'..'\u0B7F' } -> "Odia"
            text.any { it in '\u0900'..'\u097F' } -> "Devanagari (Hindi/Marathi etc.)"
            text.any { it in '\u0600'..'\u06FF' } -> "Arabic script (Urdu/Arabic etc.)"
            text.any { it in '\uAC00'..'\uD7AF' } -> "Korean"
            text.any { it in '\u3040'..'\u30FF' } -> "Japanese"
            text.any { it in '\u4E00'..'\u9FFF' } -> "Chinese/Han script"
            text.any { it in '\u0400'..'\u04FF' } -> "Cyrillic (Russian etc.)"
            text.any { it in '\u0370'..'\u03FF' } -> "Greek"
            text.any { it.isLetter() } -> "Latin script (language unclear)"
            else -> "Unknown"
        }
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
        zone: ZoneId = ZoneId.systemDefault()
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

        val languageTotals = monthly.groupBy { ListeningLanguageClassifier.classify(it.title, it.artist) }
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
