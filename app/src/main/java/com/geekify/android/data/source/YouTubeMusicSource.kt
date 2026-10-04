package com.geekify.android.data.source

import com.geekify.android.data.cache.TtlLruCache
import com.geekify.android.data.model.*
import com.geekify.android.data.source.innertube.InnerTubeClient
import com.geekify.android.data.source.innertube.Parsers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class YouTubeMusicSource @Inject constructor(
    private val innerTube: InnerTubeClient,
    private val cache: TtlLruCache
) : MusicSource {
    override suspend fun search(query: String, type: SearchType?): MusicResult<SearchResponse> = guarded {
        require(query.isNotBlank()) { "Enter a search term." }
        val kind = type?.name?.lowercase(); val key = "search:$query:$kind"
        cache.get<SearchResponse>(key) ?: parseSearch(innerTube.search(query, kind), query, kind).also { cache.put(key, it, FIFTEEN_MINUTES) }
    }
    override suspend fun track(videoId: String): MusicResult<Track> = guarded {
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
        val key = "artist:$channelId"; cache.get<ArtistPage>(key) ?: parseArtist(innerTube.browse(channelId), channelId).also { cache.put(key, it, FIFTEEN_MINUTES) }
    }
    override suspend fun collection(id: String, kind: CollectionKind): MusicResult<CollectionPage> = guarded {
        val key = "${kind.name}:$id"; cache.get<CollectionPage>(key) ?: run {
            val candidates = when { id.startsWith("MPRE") || id.startsWith("UC") || id.startsWith("FE") -> listOf(id); id.startsWith("VL") -> listOf(id, id.drop(2)); else -> listOf("VL$id", id) }
            var last: Throwable? = null; var raw: JsonObject? = null
            for (candidate in candidates) {
                runCatching { innerTube.browse(candidate) }.onSuccess { raw = it }.onFailure { last = it }
                if (raw != null) break
            }
            parseCollection(raw ?: throw (last ?: IllegalStateException("Collection not found")), id, kind).also { if (it.tracks.isEmpty()) error("Collection not found."); cache.put(key, it, FIFTEEN_MINUTES) }
        }
    }
    override suspend fun home(languageHint: String?, forceRefresh: Boolean): MusicResult<HomeResponse> = guarded {
        // YouTube's own shelves keep their original order (it ranks them); we no longer shuffle them.
        val pool = (if (forceRefresh) null else cache.get<List<Shelf>>("home-pool")) ?: coroutineScope {
            val endpoints = listOf("FEmusic_home", "FEmusic_charts", "FEmusic_new_releases", "FEmusic_explore")
            endpoints.map { async { runCatching { parseHome(innerTube.browse(it)).shelves }.getOrDefault(emptyList()) } }
                .awaitAll().flatten()
                .filter { it.items.isNotEmpty() }
                .distinctBy { it.title.trim().lowercase() }
                .also { if (it.isNotEmpty()) cache.put("home-pool", it, FIFTEEN_MINUTES) }
        }

        // Stable for the whole day (so the page doesn't reshuffle on every open); pull-to-refresh rotates it.
        val seed = if (forceRefresh) System.currentTimeMillis() else System.currentTimeMillis() / DAY_MS
        val rnd = Random(seed + (languageHint?.hashCode() ?: 0))
        val ownLanguage = languageHint?.let { languageShelves[it] }.orEmpty().shuffled(rnd).take(2)
        // English music is always part of the feed, whatever else you listen to.
        val english = listOf(englishShelves.first()) + englishShelves.drop(1).shuffled(rnd).take(2)
        val generic = genericShelves.shuffled(rnd).take(if (ownLanguage.isEmpty()) 2 else 1)
        // Unknown taste yet: start with the most popular regional picks instead of random languages.
        val coldStart = if (languageHint == null) listOf(languageShelves.getValue("Malayalam").first(), languageShelves.getValue("Hindi").first()) else emptyList()

        val extra = coroutineScope {
            (ownLanguage + coldStart + english + generic).distinctBy { it.first }.map { (title, query, type) ->
                async {
                    val result = (search(query, type) as? MusicResult.Success)?.value ?: return@async null
                    val items = if (type == SearchType.PLAYLIST) result.playlists.map(::ShelfCard) else result.albums.map(::ShelfCard)
                    items.takeIf { it.isNotEmpty() }?.let { Shelf(title, it.take(20)) }
                }
            }.awaitAll().filterNotNull()
        }
        val languageRows = extra.filter { shelf -> (ownLanguage + coldStart).any { it.first == shelf.title } }
        val englishRows = extra.filter { shelf -> english.any { it.first == shelf.title } }
        val genericRows = extra - languageRows.toSet() - englishRows.toSet()

        // Regional and English rows alternate at the top, then YouTube's own shelves.
        val topRows = buildList {
            val a = languageRows.iterator(); val b = englishRows.iterator()
            while (a.hasNext() || b.hasNext()) { if (a.hasNext()) add(a.next()); if (b.hasNext()) add(b.next()) }
        }
        val shelves = (topRows + pool.take(3) + genericRows + pool.drop(3)).distinctBy { it.title.trim().lowercase() }
        if (shelves.isEmpty()) error("Could not load the YouTube Music home feed. Try again shortly.")
        HomeResponse(shelves.take(18))
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
        const val DAY_MS = 24 * 60 * 60 * 1000L
        val languageShelves: Map<String, List<Triple<String, String, SearchType>>> = mapOf(
            "Malayalam" to listOf(
                Triple("Malayalam hits", "Malayalam hits", SearchType.PLAYLIST),
                Triple("New Malayalam releases", "new Malayalam songs", SearchType.ALBUM),
                Triple("Malayalam romantic", "Malayalam romantic songs", SearchType.PLAYLIST),
                Triple("Malayalam throwback", "Malayalam old hits", SearchType.PLAYLIST)
            ),
            "Hindi" to listOf(
                Triple("Hindi hits", "Hindi hits", SearchType.PLAYLIST),
                Triple("New Hindi releases", "new Hindi songs", SearchType.ALBUM),
                Triple("Hindi romantic", "Hindi romantic songs", SearchType.PLAYLIST),
                Triple("Bollywood throwback", "old Bollywood hits", SearchType.PLAYLIST)
            ),
            "Tamil" to listOf(
                Triple("Tamil hits", "Tamil hits", SearchType.PLAYLIST),
                Triple("New Tamil releases", "new Tamil songs", SearchType.ALBUM),
                Triple("Tamil melodies", "Tamil melody songs", SearchType.PLAYLIST),
                Triple("Tamil throwback", "Tamil old hits", SearchType.PLAYLIST)
            ),
            "Telugu" to listOf(
                Triple("Telugu hits", "Telugu hits", SearchType.PLAYLIST),
                Triple("New Telugu releases", "new Telugu songs", SearchType.ALBUM),
                Triple("Telugu melodies", "Telugu melody songs", SearchType.PLAYLIST)
            ),
            "Punjabi" to listOf(
                Triple("Punjabi hits", "Punjabi hits", SearchType.PLAYLIST),
                Triple("New Punjabi releases", "new Punjabi songs", SearchType.ALBUM)
            ),
            "K-pop" to listOf(
                Triple("K-pop hits", "k-pop hits", SearchType.PLAYLIST),
                Triple("New K-pop albums", "new k-pop albums", SearchType.ALBUM)
            )
        )
        val englishShelves = listOf(
            Triple("English hits", "English hits", SearchType.PLAYLIST),
            Triple("New English releases", "new English songs", SearchType.ALBUM),
            Triple("English love songs", "English love songs", SearchType.PLAYLIST),
            Triple("Chill English", "chill english songs", SearchType.PLAYLIST),
            Triple("English throwback", "English throwback hits", SearchType.PLAYLIST),
            Triple("English workout", "english workout songs", SearchType.PLAYLIST)
        )
        val genericShelves = listOf(
            Triple("Pop hits", "pop hits", SearchType.PLAYLIST),
            Triple("Hip-hop", "hip hop hits", SearchType.PLAYLIST),
            Triple("Lo-fi & chill", "lofi chill beats", SearchType.PLAYLIST),
            Triple("Workout", "workout music", SearchType.PLAYLIST),
            Triple("Throwback", "throwback hits", SearchType.PLAYLIST),
            Triple("Rock classics", "classic rock", SearchType.PLAYLIST),
            Triple("Fresh albums", "new albums", SearchType.ALBUM)
        )
    }
}
