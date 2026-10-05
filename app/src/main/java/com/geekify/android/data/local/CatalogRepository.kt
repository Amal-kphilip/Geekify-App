package com.geekify.android.data.local

import com.geekify.android.data.model.ArtistPage
import com.geekify.android.data.model.CollectionPage
import com.geekify.android.data.model.Track
import com.geekify.android.data.source.MusicResult
import com.geekify.android.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-first catalog: everything the app loads from the network (search results, albums, artists) is
 * written here, so metadata keeps working offline. Reads are Flows, so any screen collecting them updates
 * by itself whenever the cache changes. Writes return [MusicResult] instead of throwing.
 */
@Singleton
class CatalogRepository @Inject constructor(
    private val dao: CatalogDao,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    fun observeTrack(videoId: String): Flow<Track?> = dao.observeTrack(videoId).map { it?.toTrack() }
    fun observeAlbums(): Flow<List<AlbumEntity>> = dao.observeAlbums()
    fun observeArtists(): Flow<List<ArtistEntity>> = dao.observeArtists()

    suspend fun cachedTrack(videoId: String): Track? =
        (safeDbCall(io) { dao.trackOnce(videoId)?.toTrack() } as? MusicResult.Success)?.value

    suspend fun cacheTracks(tracks: List<Track>): MusicResult<Unit> = safeDbCall(io) {
        if (tracks.isNotEmpty()) {
            val now = System.currentTimeMillis()
            dao.upsertTracks(tracks.filter { it.videoId.isNotEmpty() }.map { it.toEntity(now) })
        }
    }

    suspend fun cacheCollection(page: CollectionPage): MusicResult<Unit> = safeDbCall(io) {
        val now = System.currentTimeMillis()
        dao.upsertTracks(page.tracks.filter { it.videoId.isNotEmpty() }.map { it.toEntity(now) })
        if (page.type == "album") {
            dao.upsertAlbum(AlbumEntity(page.id, page.title, page.artist, page.year, page.thumbnails.firstOrNull()?.url, now))
        }
    }

    suspend fun cacheArtist(page: ArtistPage): MusicResult<Unit> = safeDbCall(io) {
        val now = System.currentTimeMillis()
        dao.upsertArtist(ArtistEntity(page.id, page.name, page.thumbnails.firstOrNull()?.url, now))
        dao.upsertTracks(page.songs.filter { it.videoId.isNotEmpty() }.map { it.toEntity(now) })
    }

    /** Keeps the cache from growing forever: drops entries not refreshed for [maxAgeMs]. */
    suspend fun prune(maxAgeMs: Long = 30L * 24 * 3600 * 1000): MusicResult<Unit> = safeDbCall(io) {
        val before = System.currentTimeMillis() - maxAgeMs
        dao.pruneTracks(before); dao.pruneAlbums(before); dao.pruneArtists(before)
    }
}
