package com.geekify.android.domain.recommend

import com.geekify.android.data.local.ListeningStatsRepository
import com.geekify.android.data.model.ShelfItem
import com.geekify.android.data.model.Track
import com.geekify.android.domain.listening.ListeningBehaviorManager
import com.geekify.android.domain.listening.TimeOfDayPart
import com.geekify.android.domain.recommend.engine.MusicClassificationService
import com.geekify.android.domain.recommend.engine.MusicMood
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class SmartMixType(
    val title: String,
    val description: String,
    val primaryMood: MusicMood?
) {
    DAILY_DISCOVERY("Daily Discovery", "Fresh tracks tailored to your expanding taste", null),
    FOCUS_FLOW("Focus Flow", "Consistent, low-skip music to keep you in the zone", MusicMood.CALM),
    ENERGY_BOOST("Energy Boost", "High-tempo, uplifting selections to power your day", MusicMood.ENERGETIC),
    LATE_NIGHT_CHILL("Late Night Chill", "Ambient, melodic, low-energy evening sounds", MusicMood.RELAXING),
    TIME_CAPSULE("Time Capsule", "Forgotten favorites and past obsessions", MusicMood.NOSTALGIC)
}

data class SmartMix(
    val type: SmartMixType,
    val explanationBadge: String,
    val tracks: List<Track>
)

@Singleton
class SmartMixManager @Inject constructor(
    private val classificationService: MusicClassificationService,
    private val listeningBehaviorManager: ListeningBehaviorManager,
    private val feedbackRepository: RecommendationFeedbackRepository
) {
    /**
     * Generates a curated [SmartMix] given candidate [pool].
     */
    suspend fun generateMix(
        type: SmartMixType,
        pool: List<Track>,
        limit: Int = 25
    ): SmartMix {
        val hiddenIds = feedbackRepository.getHiddenTrackIdsOnce()
        val blockedArtists = feedbackRepository.getBlockedArtistsOnce()

        val filteredPool = pool.filter { track ->
            track.videoId !in hiddenIds &&
                track.artist.lowercase(Locale.ROOT) !in blockedArtists
        }

        val classified = filteredPool.map { track ->
            val classification = classificationService.classify(
                title = track.title,
                artist = track.artist
            )
            track to classification
        }

        val signals = listeningBehaviorManager.signals.value

        val (tracks, badge) = when (type) {
            SmartMixType.DAILY_DISCOVERY -> {
                val sorted = classified
                    .shuffled()
                    .take(limit)
                    .map { it.first }
                val badge = if (signals.discoveryTolerance > 0.6) "High discovery match" else "Fresh tailored picks"
                sorted to badge
            }
            SmartMixType.FOCUS_FLOW -> {
                val focus = classified.filter { (_, c) ->
                    c.moods[MusicMood.CALM] != null ||
                    c.moods[MusicMood.PEACEFUL] != null ||
                    c.moods[MusicMood.DREAMY] != null
                }.map { it.first }
                val fallback = if (focus.size < 5) filteredPool.take(limit) else focus.take(limit)
                fallback to "Consistent, low-skip focus vibe"
            }
            SmartMixType.ENERGY_BOOST -> {
                val energy = classified.filter { (_, c) ->
                    c.moods[MusicMood.ENERGETIC] != null ||
                    c.moods[MusicMood.MOTIVATIONAL] != null ||
                    c.moods[MusicMood.UPLIFTING] != null
                }.map { it.first }
                val fallback = if (energy.size < 5) filteredPool.take(limit) else energy.take(limit)
                fallback to "High energy tempo"
            }
            SmartMixType.LATE_NIGHT_CHILL -> {
                val chill = classified.filter { (_, c) ->
                    c.moods[MusicMood.RELAXING] != null ||
                    c.moods[MusicMood.ATMOSPHERIC] != null ||
                    c.moods[MusicMood.CALM] != null
                }.map { it.first }
                val fallback = if (chill.size < 5) filteredPool.take(limit) else chill.take(limit)
                val badge = if (signals.timeOfDayAffinity[TimeOfDayPart.NIGHT] ?: 0.0 > 0.3) {
                    "Because you listen late at night"
                } else {
                    "Smooth ambient tones"
                }
                fallback to badge
            }
            SmartMixType.TIME_CAPSULE -> {
                val replayed = filteredPool.filter { track ->
                    (signals.replayCounts[track.videoId] ?: 0) > 0
                }
                val result = if (replayed.size >= 5) replayed.take(limit) else filteredPool.reversed().take(limit)
                result to "Your most replayed favorites"
            }
        }

        return SmartMix(
            type = type,
            explanationBadge = badge,
            tracks = tracks
        )
    }
}
