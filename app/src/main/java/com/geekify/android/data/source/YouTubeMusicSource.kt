package com.geekify.android.data.source

import com.geekify.android.data.cache.TtlLruCache
import com.geekify.android.data.local.CatalogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.geekify.android.data.model.*
import com.geekify.android.data.source.innertube.InnerTubeClient
import com.geekify.android.data.source.innertube.Parsers
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class YouTubeMusicSource @Inject constructor(
    private val innerTube: InnerTubeClient,
    private val cache: TtlLruCache,
    private val catalog: CatalogRepository
) : MusicSource {
    /** Write-through to the offline catalog never blocks (or fails) the request that triggered it. */
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun search(query: String, type: SearchType?): MusicResult<SearchResponse> = guarded {
        require(query.isNotBlank()) { "Enter a search term." }
        val kind = type?.name?.lowercase(); val key = "search:$query:$kind"
        cache.get<SearchResponse>(key) ?: parseSearch(innerTube.search(query, kind), query, kind).also {
            cache.put(key, it, FIFTEEN_MINUTES)
            cacheScope.launch { catalog.cacheTracks(it.songs) }
        }
    }
    override suspend fun track(videoId: String): MusicResult<Track> {
        val live = trackLive(videoId)
        // Offline or blocked: fall back to metadata remembered from earlier browsing.
        if (live is MusicResult.Failure) catalog.cachedTrack(videoId)?.let { return MusicResult.Success(it) }
        return live
    }
    private suspend fun trackLive(videoId: String): MusicResult<Track> = guarded {
        val key = "track:$videoId"; cache.get<Track>(key) ?: run {
            val raw = innerTube.player(videoId); val details = raw["videoDetails"] as? JsonObject ?: error("Track metadata is unavailable.")
            val seconds = details["lengthSeconds"]?.toString()?.trim('"')?.toIntOrNull()
            val artist = details["author"]?.toString()?.trim('"').orEmpty().ifBlank { "Unknown" }
            Track(details["videoId"]?.toString()?.trim('"') ?: videoId, details["title"]?.toString()?.trim('"').orEmpty().ifBlank { "Unknown" }, artist, listOf(ArtistRef(artist, details["channelId"]?.toString()?.trim('"'))), thumbnails = Parsers.findThumbnails(raw), durationSeconds = seconds, duration = seconds?.let(::formatDuration)).also { cache.put(key, it, FIFTEEN_MINUTES) }
        }
    }
    override suspend fun related(videoId: String): MusicResult<List<Track>> = guarded {
        val key = "related:$videoId"; cache.get<List<Track>>(key) ?: run {
            val radio = runCatching { innerTube.next(videoId, "RDAMVM$videoId") }.getOrElse { innerTube.next(videoId) }
            Parsers.tracks(radio, 50).filterNot { it.videoId == videoId }.also { if (it.isNotEmpty()) cache.put(key, it, FIFTEEN_MINUTES) }
        }
    }
    override suspend fun artist(channelId: String): MusicResult<ArtistPage> = guarded {
        val key = "artist:$channelId"; cache.get<ArtistPage>(key) ?: parseArtist(innerTube.browse(channelId), channelId).also {
            cache.put(key, it, FIFTEEN_MINUTES)
            cacheScope.launch { catalog.cacheArtist(it) }
        }
    }
    override suspend fun collection(id: String, kind: CollectionKind): MusicResult<CollectionPage> = guarded {
        val key = "${kind.name}:$id"; cache.get<CollectionPage>(key) ?: run {
            val candidates = when { id.startsWith("MPRE") || id.startsWith("UC") || id.startsWith("FE") -> listOf(id); id.startsWith("VL") -> listOf(id, id.drop(2)); else -> listOf("VL$id", id) }
            var last: Throwable? = null; var raw: JsonObject? = null
            for (candidate in candidates) {
                runCatching { innerTube.browse(candidate) }.onSuccess { raw = it }.onFailure { last = it }
                if (raw != null) break
            }
            parseCollection(raw ?: throw (last ?: IllegalStateException("Collection not found")), id, kind).also {
                if (it.tracks.isEmpty()) error("Collection not found.")
                cache.put(key, it, FIFTEEN_MINUTES)
                cacheScope.launch { catalog.cacheCollection(it) }
            }
        }
    }
    override suspend fun home(): MusicResult<HomeResponse> = guarded {
        val pool = cache.get<List<Shelf>>("home-pool") ?: coroutineScope {
            val endpoints = listOf("FEmusic_home", "FEmusic_explore", "FEmusic_charts", "FEmusic_new_releases")
            endpoints.map { async { runCatching { parseHome(innerTube.browse(it)).shelves }.getOrDefault(emptyList()) } }.flatMap { it.await() }
                .filter { it.items.isNotEmpty() }.distinctBy { it.title.trim().lowercase() }.also { if (it.isNotEmpty()) cache.put("home-pool", it, FIFTEEN_MINUTES) }
        }
        val shuffled = pool.shuffled().map { it.copy(items = it.items.shuffled()) }.toMutableList()
        genreShelves.shuffled().take(4).forEach { (title, query, type) ->
            val result = search(query, type).let { (it as? MusicResult.Success)?.value } ?: return@forEach
            val items = if (type == SearchType.PLAYLIST) result.playlists.map(::ShelfCard) else result.albums.map(::ShelfCard)
            if (items.isNotEmpty()) shuffled.add(Random.nextInt(0, minOf(4, shuffled.size) + 1), Shelf(title, items.take(20)))
        }
        if (shuffled.isEmpty()) error("Could not load the YouTube Music home feed. Try again shortly.")
        HomeResponse(shuffled.take(18))
    }
    private fun parseSearch(raw: JsonObject, query: String, type: String?): SearchResponse {
        val shelves = Parsers.shelves(raw).mapNotNull { (title, block) ->
            val tracks = Parsers.tracks(block); val cards = Parsers.cards(block)
            val items = (if (tracks.isNotEmpty() && cards.isEmpty()) tracks.map(::ShelfTrack) else cards.map(::ShelfCard)); items.takeIf { it.isNotEmpty() }?.let { Shelf(title, it) }
        }
        val allTracks = shelves.flatMap { it.items }.filterIsInstance<ShelfTrack>().map { it.value }.distinctBy { it.videoId }.ifEmpty { Parsers.tracks(raw) }
        fun cards(kind: String) = Parsers.cards(raw, kind).distinctBy { it.id }
        return SearchResponse(query, allTracks, if (type == "album") cards("album") else cards("album"), if (type == "artist") cards("artist") else cards("artist"), if (type == "playlist") cards("playlist") else cards("playlist"), shelves)
    }
    private fun parseHome(raw: JsonObject) = HomeResponse(Parsers.shelves(raw).mapNotNull { (title, block) ->
        val tracks = Parsers.tracks(block, 24); val cards = Parsers.cards(block, limit = 24)
        val items = if (tracks.isNotEmpty() && cards.isEmpty()) tracks.map(::ShelfTrack) else cards.map(::ShelfCard)
        items.takeIf { it.isNotEmpty() }?.let { Shelf(title, it) }
    }.take(16))
    private fun parseArtist(raw: JsonObject, id: String): ArtistPage {
        val name = Parsers.shelves(raw).firstOrNull()?.first ?: "Artist"; val shelves = Parsers.shelves(raw)
        fun has(vararg terms: String) = shelves.filter { pair -> terms.any { pair.first.contains(it, true) } }.flatMap { Parsers.cards(it.second, limit = 20) }
        val songs = shelves.filter { it.first.contains("song", true) || it.first.contains("top", true) }.flatMap { Parsers.tracks(it.second, 20) }.ifEmpty { Parsers.tracks(raw, 15) }.distinctBy { it.videoId }.take(25)
        return ArtistPage(id, name, thumbnails = Parsers.findThumbnails(raw), songs = songs, albums = has("album").distinctBy { it.id }, singles = has("single", "ep").distinctBy { it.id }, related = has("similar", "artist", "fan").distinctBy { it.id })
    }
    private fun parseCollection(raw: JsonObject, id: String, kind: CollectionKind): CollectionPage {
        val header = Parsers.collectionHeader(raw)
        val title = header.title ?: Parsers.shelves(raw).firstOrNull()?.first ?: id; val tracks = Parsers.tracks(raw, 250); val thumbs = Parsers.findThumbnails(raw)
        return CollectionPage(id, title, description = header.description, year = header.year, artist = header.creator, type = if (kind == CollectionKind.ALBUM) "album" else "playlist", thumbnails = thumbs, tracks = tracks.map { if (it.thumbnails.isEmpty()) it.copy(thumbnails = thumbs) else it }.distinctBy { it.videoId })
    }
    private suspend fun <T> guarded(block: suspend () -> T): MusicResult<T> = try {
        MusicResult.Success(block())
    } catch (e: CancellationException) {
        // Search and screen changes cancel in-flight calls. Never convert that normal control
        // flow into a user-facing network error.
        throw e
    } catch (e: Throwable) {
        MusicResult.Failure(e.message ?: "Something went wrong. Please try again.", e is InnerTubeClient.InnerTubeException && e.retryable)
    }
    private fun formatDuration(s: Int): String = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    private companion object {
        const val FIFTEEN_MINUTES = 15 * 60 * 1000L
        val genreShelves = listOf(Triple("Malayalam hits", "Malayalam hits", SearchType.PLAYLIST), Triple("Malayalam new releases", "new Malayalam songs", SearchType.ALBUM), Triple("Hindi hits", "Hindi hits", SearchType.PLAYLIST), Triple("Tamil hits", "Tamil hits", SearchType.PLAYLIST), Triple("Telugu hits", "Telugu hits", SearchType.PLAYLIST), Triple("Punjabi hits", "Punjabi hits", SearchType.PLAYLIST), Triple("Hip-hop", "hip hop hits", SearchType.PLAYLIST), Triple("Pop hits", "pop hits", SearchType.PLAYLIST), Triple("Lo-fi & chill", "lofi chill beats", SearchType.PLAYLIST), Triple("Workout", "workout music", SearchType.PLAYLIST), Triple("Throwback", "throwback hits", SearchType.PLAYLIST), Triple("Party", "party songs", SearchType.PLAYLIST), Triple("Rock classics", "classic rock", SearchType.PLAYLIST), Triple("Romantic", "romantic songs", SearchType.PLAYLIST), Triple("K-pop", "k-pop hits", SearchType.PLAYLIST), Triple("Fresh albums", "new albums", SearchType.ALBUM))
    }
}
