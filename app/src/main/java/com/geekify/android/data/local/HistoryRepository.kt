package com.geekify.android.data.local

import com.geekify.android.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryRepository @Inject constructor(private val dao: HistoryDao) {
    val recent: Flow<List<Track>> = dao.recent().map { it.map(HistoryTrackEntity::toTrack) }
    suspend fun add(track: Track) {
        dao.delete(track.videoId)
        dao.insert(track.toHistory())
        dao.trim()
    }
    suspend fun clear() = dao.clear()
    suspend fun allOnce(): List<Track> = dao.allOnce().map(HistoryTrackEntity::toTrack)
    suspend fun replaceAll(tracks: List<Track>) {
        dao.clear()
        dao.insertAll(tracks.take(40).map(Track::toHistory))
    }
}
