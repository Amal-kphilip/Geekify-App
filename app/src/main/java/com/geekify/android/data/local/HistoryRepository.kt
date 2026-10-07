package com.geekify.android.data.local

import com.geekify.android.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A song and how many times it has been properly listened to. */
data class PlayStat(val videoId: String, val title: String, val artist: String, val plays: Int, val lastPlayedAt: Long)

@Singleton
class HistoryRepository @Inject constructor(
    private val dao: HistoryDao,
    private val stats: PlayStatDao
) {
    val recent: Flow<List<Track>> = dao.recent().map { it.map(HistoryTrackEntity::toTrack) }

    /**
     * Records that [track] just started playing. The primary key is the song, so REPLACE moves a
     * replayed song to the top with a fresh timestamp in a single write (no delete + insert gap that
     * would make the list flicker).
     */
    suspend fun add(track: Track) {
        dao.insert(track.toHistory())
        dao.trim()
    }

    /** Counts one real listen (called after the person has listened for a while, not on a skip). */
    suspend fun countPlay(track: Track) {
        val now = System.currentTimeMillis()
        if (stats.bump(track.videoId, track.title, track.artist, now) == 0) {
            stats.insert(PlayStatEntity(track.videoId, track.title, track.artist, 1, now))
        }
    }

    suspend fun topPlayed(limit: Int = 40): List<PlayStat> =
        stats.top(limit).map { PlayStat(it.videoId, it.title, it.artist, it.playCount, it.lastPlayedAt) }

    /** Live real-play stats so recommendation rebuilds can react when repeated listening changes. */
    fun topPlayedFlow(limit: Int = 40): Flow<List<PlayStat>> =
        stats.topFlow(limit).map { rows ->
            rows.map { PlayStat(it.videoId, it.title, it.artist, it.playCount, it.lastPlayedAt) }
        }

    suspend fun clearStats() = stats.clear()

    suspend fun clear() = dao.clear()
    suspend fun allOnce(): List<Track> = dao.allOnce().map(HistoryTrackEntity::toTrack)

    /** History with the time each song was last played, for merging with the cloud copy. */
    suspend fun allTimed(): List<Pair<Track, Long>> = dao.allOnce().map { it.toTrack() to it.playedAt }

    suspend fun replaceAll(tracks: List<Track>) {
        dao.clear()
        dao.insertAll(tracks.take(40).map(Track::toHistory))
    }

    suspend fun replaceAllTimed(entries: List<Pair<Track, Long>>) {
        dao.clear()
        dao.insertAll(entries.take(40).map { (track, at) -> track.toHistory().copy(playedAt = at) })
    }
}
