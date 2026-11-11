package com.geekify.android.domain.listening

import com.geekify.android.data.local.ListeningSessionEntity
import com.geekify.android.data.local.ListeningStatsRepository
import com.geekify.android.data.model.Track
import com.geekify.android.settings.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class TimeOfDayPart {
    MORNING,    // 05:00 - 11:59
    WORKDAY,    // 12:00 - 16:59
    EVENING,    // 17:00 - 21:59
    NIGHT       // 22:00 - 04:59
}

data class AggregatedListeningSignals(
    val completionRate: Double = 0.0,
    val immediateSkipRate: Double = 0.0,
    val casualSkipRate: Double = 0.0,
    val totalSessionsTracked: Int = 0,
    val totalListeningTimeMs: Long = 0L,
    val topArtistsDwellMs: Map<String, Long> = emptyMap(),
    val timeOfDayAffinity: Map<TimeOfDayPart, Double> = emptyMap(),
    val discoveryTolerance: Double = 0.5,
    val replayCounts: Map<String, Int> = emptyMap()
)

/**
 * Real-time listening behavior model that captures granular playback interactions,
 * computes temporal and skip signals, and respects user privacy preferences.
 */
@Singleton
class ListeningBehaviorManager @Inject constructor(
    private val statsRepository: ListeningStatsRepository,
    private val appSettings: AppSettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _signals = MutableStateFlow(AggregatedListeningSignals())
    val signals: StateFlow<AggregatedListeningSignals> = _signals.asStateFlow()

    private val sessionHistory = ConcurrentHashMap<String, MutableList<Long>>()
    private val artistDwellMap = ConcurrentHashMap<String, Long>()
    private val replayCountMap = ConcurrentHashMap<String, Int>()

    init {
        recomputeSignals()
    }

    fun isTrackingEnabled(): Boolean {
        return !appSettings.state.value.pauseListenHistory
    }

    fun recordPlaybackStart(track: Track, timestamp: Long = System.currentTimeMillis()) {
        if (!isTrackingEnabled()) return
        sessionHistory.computeIfAbsent(track.videoId) { mutableListOf() }.add(timestamp)
        val plays = sessionHistory[track.videoId]?.size ?: 0
        if (plays > 1) {
            replayCountMap[track.videoId] = plays - 1
        }
    }

    fun recordPlaybackEnd(
        track: Track,
        listenedMs: Long,
        durationMs: Long,
        isCompleted: Boolean,
        isSkipped: Boolean
    ) {
        if (!isTrackingEnabled() || listenedMs <= 0L) return

        if (track.artist.isNotBlank()) {
            artistDwellMap.compute(track.artist) { _, existing -> (existing ?: 0L) + listenedMs }
        }

        scope.launch {
            recomputeSignals()
        }
    }

    fun recordArtistPageVisit(artist: String) {
        if (!isTrackingEnabled() || artist.isBlank()) return
        artistDwellMap.compute(artist) { _, existing -> (existing ?: 0L) + 15_000L }
    }

    fun recomputeSignals() {
        if (!isTrackingEnabled()) {
            _signals.value = AggregatedListeningSignals()
            return
        }

        scope.launch {
            val sessions = runCatching { statsRepository.latest(1000) }.getOrDefault(emptyList())
            if (sessions.isEmpty()) return@launch

            var completedCount = 0
            var immediateSkips = 0
            var casualSkips = 0
            var totalTimeMs = 0L
            val timeOfDayCounts = mutableMapOf<TimeOfDayPart, Int>()

            for (session in sessions) {
                totalTimeMs += session.listenedMs

                val ratio = if (session.durationMs > 0L) {
                    session.listenedMs.toDouble() / session.durationMs.toDouble()
                } else 0.0

                if (session.completed || ratio >= 0.85) {
                    completedCount++
                } else if (session.skipped || ratio < 0.85) {
                    if (session.listenedMs < 10_000L) {
                        immediateSkips++
                    } else {
                        casualSkips++
                    }
                }

                val timePart = getTimeOfDayPart(session.startedAt)
                timeOfDayCounts[timePart] = (timeOfDayCounts[timePart] ?: 0) + 1
            }

            val total = sessions.size.toDouble().coerceAtLeast(1.0)
            val timeSum = timeOfDayCounts.values.sum().toDouble().coerceAtLeast(1.0)
            val timeAffinity = TimeOfDayPart.entries.associateWith { part ->
                (timeOfDayCounts[part] ?: 0).toDouble() / timeSum
            }

            // Discovery tolerance: ratio of completions among tracks with low historical play count
            val singlePlaySessions = sessions.filter { s -> (sessionHistory[s.videoId]?.size ?: 1) <= 1 }
            val discoveryCompleted = singlePlaySessions.count { it.completed || (it.durationMs > 0 && it.listenedMs * 1.0 / it.durationMs >= 0.8) }
            val discoveryTolerance = if (singlePlaySessions.isNotEmpty()) {
                (discoveryCompleted.toDouble() / singlePlaySessions.size).coerceIn(0.1, 1.0)
            } else 0.5

            _signals.value = AggregatedListeningSignals(
                completionRate = (completedCount / total).coerceIn(0.0, 1.0),
                immediateSkipRate = (immediateSkips / total).coerceIn(0.0, 1.0),
                casualSkipRate = (casualSkips / total).coerceIn(0.0, 1.0),
                totalSessionsTracked = sessions.size,
                totalListeningTimeMs = totalTimeMs,
                topArtistsDwellMs = artistDwellMap.toMap(),
                timeOfDayAffinity = timeAffinity,
                discoveryTolerance = discoveryTolerance,
                replayCounts = replayCountMap.toMap()
            )
        }
    }

    suspend fun clearHistory() {
        statsRepository.clear()
        sessionHistory.clear()
        artistDwellMap.clear()
        replayCountMap.clear()
        _signals.value = AggregatedListeningSignals()
    }

    companion object {
        fun getTimeOfDayPart(timestampMs: Long): TimeOfDayPart {
            val localTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(timestampMs),
                ZoneId.systemDefault()
            )
            val hour = localTime.hour
            return when (hour) {
                in 5..11 -> TimeOfDayPart.MORNING
                in 12..16 -> TimeOfDayPart.WORKDAY
                in 17..21 -> TimeOfDayPart.EVENING
                else -> TimeOfDayPart.NIGHT
            }
        }
    }
}
