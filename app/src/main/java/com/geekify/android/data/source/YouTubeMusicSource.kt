package com.geekify.android.data.source

import com.geekify.android.data.cache.TtlLruCache
import com.geekify.android.data.local.CatalogRepository
import com.geekify.android.settings.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val cache: TtlLruCache,
    private val catalog: CatalogRepository,
    private val appSettings: AppSettingsRepository
) : MusicSource {
    /** Write-through to the offline catalog never blocks (or fails) the request that triggered it. */
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun localeSuffix(): String {
        val s = appSettings.state.value
        return "${s.contentCountry}:${s.contentLanguage}"
    }

    private fun List<Track>.filterExplicitIfNeeded(): List<Track> =
        if (appSettings.state.value.hideExplicit) filterNot { it.explicit } else this

    private fun List<ShelfItem>.filterExplicitItemsIfNeeded(): List<ShelfItem> =
        if (appSettings.state.value.hideExplicit) filterNot { it is ShelfTrack && it.value.explicit } else this

    override suspend fun search(query: String, type: SearchType?): MusicResult<SearchResponse> = guarded {
        require(query.isNotBlank()) { "Enter a search term." }
        val kind = type?.name?.lowercase(); val key = "search:$query:$kind:${localeSuffix()}"
        val res = cache.get<SearchResponse>(key) ?: searchAndParse(query, kind).also {
            cache.put(key, it, FIFTEEN_MINUTES)
            cacheScope.launch { catalog.cacheTracks(it.songs) }
        }
        res.copy(
            songs = res.songs.filterExplicitIfNeeded(),
            shelves = res.shelves.mapNotNull { shelf ->
                shelf.items.filterExplicitItemsIfNeeded().takeIf { it.isNotEmpty() }?.let { shelf.copy(items = it) }
            }
        )
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
        val key = "related:$videoId:${localeSuffix()}"; (cache.get<List<Track>>(key) ?: run {
            val radio = runCatching { innerTube.next(videoId, "RDAMVM$videoId") }.getOrElse { innerTube.next(videoId) }
            Parsers.tracks(radio, 50).filterNot { it.videoId == videoId }.also { if (it.isNotEmpty()) cache.put(key, it, FIFTEEN_MINUTES) }
        }).filterExplicitIfNeeded()
    }

    override suspend fun artist(channelId: String): MusicResult<ArtistPage> = guarded {
        val key = "artist:$channelId:${localeSuffix()}"; cache.get<ArtistPage>(key) ?: parseArtist(innerTube.browse(channelId), channelId).also {
            cache.put(key, it, FIFTEEN_MINUTES)
            cacheScope.launch { catalog.cacheArtist(it) }
        }
    }

    override suspend fun collection(id: String, kind: CollectionKind): MusicResult<CollectionPage> = guarded {
        val key = "${kind.name}:$id:${localeSuffix()}"; cache.get<CollectionPage>(key) ?: run {
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
        }.let { page -> page.copy(tracks = page.tracks.filterExplicitIfNeeded()) }
    }

    override suspend fun home(): MusicResult<HomeResponse> = guarded {
        val settings = appSettings.state.value
        val countryCode = settings.contentCountry.uppercase()
        val languageCode = settings.contentLanguage.lowercase()
        val countryName = COUNTRY_NAMES[countryCode] ?: countryCode
        val languageName = LANGUAGE_NAMES[languageCode] ?: "Music"
        val poolKey = "home-pool:$countryCode:$languageCode"

        val pool = cache.get<List<Shelf>>(poolKey) ?: coroutineScope {
            val endpoints = listOf("FEmusic_home", "FEmusic_explore", "FEmusic_charts", "FEmusic_new_releases")
            val browseDeferred = endpoints.map { endpoint ->
                async {
                    runCatching { parseHome(innerTube.browse(endpoint)).shelves }.getOrDefault(emptyList())
                }
            }
            val regionalSpecs = buildRegionalShelfSpecs(countryCode, countryName, languageCode, languageName)
            val regionalDeferred = regionalSpecs.map { spec ->
                async {
                    runCatching { fetchCuratedShelf(spec) }.getOrNull()
                }
            }
            val browsed = browseDeferred.awaitAll().flatten()
            val regional = regionalDeferred.awaitAll().filterNotNull()
            // Regional & language shelves come first so changing Country or Language in Settings
            // immediately updates the top of the Home feed, followed by YouTube Music browse shelves.
            (regional + browsed)
                .filter { it.items.isNotEmpty() }
                .distinctBy { it.title.trim().lowercase() }
                .also { if (it.isNotEmpty()) cache.put(poolKey, it, FIFTEEN_MINUTES) }
        }

        val primaryTitles = primaryRegionalTitles(countryName, languageCode, languageName)
        val (pinnedRegional, restPool) = pool.partition { shelf ->
            primaryTitles.any { shelf.title.equals(it, ignoreCase = true) }
        }

        val mappedPinned = pinnedRegional.mapNotNull { shelf ->
            val filtered = shelf.items.filterExplicitItemsIfNeeded()
            filtered.takeIf { it.isNotEmpty() }?.let { shelf.copy(items = it) }
        }
        val mappedRest = restPool.shuffled().mapNotNull { shelf ->
            val filtered = shelf.items.filterExplicitItemsIfNeeded().shuffled()
            filtered.takeIf { it.isNotEmpty() }?.let { shelf.copy(items = it) }
        }

        val combined = (mappedPinned + mappedRest).toMutableList()
        if (combined.isEmpty()) error("Could not load the YouTube Music home feed. Try again shortly.")
        HomeResponse(combined.take(18))
    }

    private data class CuratedShelfSpec(
        val title: String,
        val query: String,
        val type: SearchType?
    )

    private suspend fun fetchCuratedShelf(spec: CuratedShelfSpec): Shelf? {
        val result = (search(spec.query, spec.type) as? MusicResult.Success)?.value ?: return null
        val items: List<ShelfItem> = when (spec.type) {
            SearchType.SONG -> result.songs.take(16).map(::ShelfTrack) +
                result.playlists.take(4).map(::ShelfCard)
            SearchType.PLAYLIST -> result.playlists.take(16).map(::ShelfCard) +
                result.songs.take(6).map(::ShelfTrack)
            SearchType.ALBUM -> result.albums.take(16).map(::ShelfCard) +
                result.songs.take(6).map(::ShelfTrack)
            SearchType.ARTIST -> result.artists.take(16).map(::ShelfCard)
            null -> result.songs.take(12).map(::ShelfTrack) +
                result.playlists.take(6).map(::ShelfCard) +
                result.albums.take(6).map(::ShelfCard)
        }
        val clean = items.distinctBy { it.id }.take(20)
        return if (clean.isNotEmpty()) Shelf(spec.title, clean) else null
    }

    private fun primaryRegionalTitles(
        countryName: String,
        languageCode: String,
        languageName: String
    ): Set<String> = buildSet {
        add("Top Hits • $countryName")
        add("Trending in $countryName")
        if (languageCode != "en") {
            add("$languageName Hits")
            add("New $languageName Releases")
        } else {
            add("New Releases • $countryName")
        }
    }

    private fun buildRegionalShelfSpecs(
        countryCode: String,
        countryName: String,
        languageCode: String,
        languageName: String
    ): List<CuratedShelfSpec> {
        val primary = buildList {
            add(CuratedShelfSpec("Top Hits • $countryName", "$countryName top hits songs", SearchType.SONG))
            add(CuratedShelfSpec("Trending in $countryName", "trending music $countryName", SearchType.PLAYLIST))
            if (languageCode != "en") {
                add(CuratedShelfSpec("$languageName Hits", "$languageName hits songs", SearchType.SONG))
                add(CuratedShelfSpec("New $languageName Releases", "new $languageName songs", SearchType.ALBUM))
            } else {
                add(CuratedShelfSpec("New Releases • $countryName", "new music releases $countryName", SearchType.ALBUM))
            }
        }

        val regionalPool = when {
            languageCode == "ml" -> listOf(
                CuratedShelfSpec("Malayalam hits", "Malayalam hits", SearchType.PLAYLIST),
                CuratedShelfSpec("Malayalam new releases", "new Malayalam songs", SearchType.ALBUM),
                CuratedShelfSpec("Mollywood melodies", "Malayalam romantic songs", SearchType.SONG)
            )
            languageCode == "hi" -> listOf(
                CuratedShelfSpec("Hindi hits", "Hindi hits", SearchType.PLAYLIST),
                CuratedShelfSpec("Bollywood new releases", "new Hindi songs", SearchType.ALBUM),
                CuratedShelfSpec("Bollywood romantic", "Hindi romantic songs", SearchType.SONG)
            )
            languageCode == "ta" -> listOf(
                CuratedShelfSpec("Tamil hits", "Tamil hits", SearchType.PLAYLIST),
                CuratedShelfSpec("Kollywood new releases", "new Tamil songs", SearchType.ALBUM),
                CuratedShelfSpec("Tamil melodies", "Tamil melody songs", SearchType.SONG)
            )
            languageCode == "te" -> listOf(
                CuratedShelfSpec("Telugu hits", "Telugu hits", SearchType.PLAYLIST),
                CuratedShelfSpec("Tollywood new releases", "new Telugu songs", SearchType.ALBUM),
                CuratedShelfSpec("Telugu chartbusters", "Telugu top songs", SearchType.SONG)
            )
            languageCode == "ko" || countryCode == "KR" -> listOf(
                CuratedShelfSpec("K-Pop Chartbusters", "K-pop top hits", SearchType.PLAYLIST),
                CuratedShelfSpec("K-R&B & Indie", "Korean R&B indie", SearchType.PLAYLIST),
                CuratedShelfSpec("New K-Pop Releases", "new K-pop albums", SearchType.ALBUM)
            )
            languageCode == "ja" || countryCode == "JP" -> listOf(
                CuratedShelfSpec("J-Pop Top Hits", "J-pop hits", SearchType.PLAYLIST),
                CuratedShelfSpec("Anime & Vocaloid Hits", "anime opening hits", SearchType.PLAYLIST),
                CuratedShelfSpec("New J-Pop Releases", "new Japanese music", SearchType.ALBUM)
            )
            languageCode == "es" -> listOf(
                CuratedShelfSpec("Éxitos Latinos", "Latin hits playlist", SearchType.PLAYLIST),
                CuratedShelfSpec("Reggaeton & Urbano", "Reggaeton hits", SearchType.PLAYLIST),
                CuratedShelfSpec("Nuevos Lanzamientos", "new Spanish music", SearchType.ALBUM)
            )
            languageCode == "fr" || countryCode == "FR" -> listOf(
                CuratedShelfSpec("Hits Français", "French pop hits", SearchType.PLAYLIST),
                CuratedShelfSpec("Rap Français", "French rap hits", SearchType.PLAYLIST)
            )
            languageCode == "de" || countryCode == "DE" -> listOf(
                CuratedShelfSpec("Deutschpop & Hits", "German charts hits", SearchType.PLAYLIST),
                CuratedShelfSpec("Deutschrap", "Deutschrap hits", SearchType.PLAYLIST)
            )
            languageCode == "pt" || countryCode == "BR" -> listOf(
                CuratedShelfSpec("Top Brasil", "Brazil top hits", SearchType.PLAYLIST),
                CuratedShelfSpec("Sertanejo & Pagode", "Sertanejo hits", SearchType.PLAYLIST)
            )
            countryCode == "IN" -> listOf(
                CuratedShelfSpec("Malayalam hits", "Malayalam hits", SearchType.PLAYLIST),
                CuratedShelfSpec("Malayalam new releases", "new Malayalam songs", SearchType.ALBUM),
                CuratedShelfSpec("Hindi hits", "Hindi hits", SearchType.PLAYLIST),
                CuratedShelfSpec("Tamil hits", "Tamil hits", SearchType.PLAYLIST),
                CuratedShelfSpec("Telugu hits", "Telugu hits", SearchType.PLAYLIST),
                CuratedShelfSpec("Punjabi hits", "Punjabi hits", SearchType.PLAYLIST)
            ).shuffled().take(3)
            else -> emptyList()
        }

        val globalGenres = globalGenreShelves.shuffled().take(2).map { (title, query, type) ->
            CuratedShelfSpec(title, "$query $countryName", type)
        }

        return primary + regionalPool + globalGenres
    }

    /** A filtered tab (albums / artists / playlists) that comes back empty falls back to the unfiltered results, which list those too. */
    private suspend fun searchAndParse(query: String, kind: String?): SearchResponse {
        val parsed = parseSearch(innerTube.search(query, kind), query, kind)
        val empty = when (kind) {
            "album" -> parsed.albums.isEmpty()
            "artist" -> parsed.artists.isEmpty()
            "playlist" -> parsed.playlists.isEmpty()
            else -> false
        }
        return if (empty) runCatching { parseSearch(innerTube.search(query, null), query, null) }.getOrDefault(parsed) else parsed
    }

    private fun parseSearch(raw: JsonObject, query: String, type: String?): SearchResponse {
        val shelves = Parsers.shelves(raw).mapNotNull { (title, block) ->
            val tracks = Parsers.tracks(block); val cards = Parsers.cards(block)
            val items = (if (tracks.isNotEmpty() && cards.isEmpty()) tracks.map(::ShelfTrack) else cards.map(::ShelfCard)); items.takeIf { it.isNotEmpty() }?.let { Shelf(title, it) }
        }
        val allTracks = shelves.flatMap { it.items }.filterIsInstance<ShelfTrack>().map { it.value }.distinctBy { it.videoId }.ifEmpty { Parsers.tracks(raw) }
        fun cards(kind: String) = (Parsers.searchEntities(raw, kind) + Parsers.cards(raw, kind)).distinctBy { it.id }
        return SearchResponse(query, allTracks, if (type == "album") cards("album") else cards("album"), if (type == "artist") cards("artist") else cards("artist"), if (type == "playlist") cards("playlist") else cards("playlist"), shelves)
    }

    private fun parseHome(raw: JsonObject) = HomeResponse(Parsers.shelves(raw).mapNotNull { (title, block) ->
        val tracks = Parsers.tracks(block, 24)
        val cards = Parsers.cards(block, limit = 24)
        // Home shelves can legitimately contain both song rows and entity cards. Keep both so the
        // Home filters have real songs/albums/artists to work with instead of silently discarding tracks.
        val items = (tracks.map(::ShelfTrack) + cards.map(::ShelfCard)).distinctBy { it.id }
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

        val COUNTRY_NAMES = mapOf(
            "IN" to "India",
            "US" to "United States",
            "GB" to "United Kingdom",
            "CA" to "Canada",
            "AU" to "Australia",
            "DE" to "Germany",
            "FR" to "France",
            "JP" to "Japan",
            "KR" to "South Korea",
            "BR" to "Brazil",
            "SG" to "Singapore",
            "AE" to "UAE"
        )

        val LANGUAGE_NAMES = mapOf(
            "en" to "English",
            "hi" to "Hindi",
            "ml" to "Malayalam",
            "ta" to "Tamil",
            "te" to "Telugu",
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German",
            "ja" to "Japanese",
            "ko" to "Korean",
            "pt" to "Portuguese"
        )

        val globalGenreShelves = listOf(
            Triple("Hip-hop", "hip hop hits", SearchType.PLAYLIST),
            Triple("Pop hits", "pop hits", SearchType.PLAYLIST),
            Triple("Lo-fi & chill", "lofi chill beats", SearchType.PLAYLIST),
            Triple("Workout", "workout music", SearchType.PLAYLIST),
            Triple("Throwback", "throwback hits", SearchType.PLAYLIST),
            Triple("Party", "party songs", SearchType.PLAYLIST),
            Triple("Rock classics", "classic rock", SearchType.PLAYLIST),
            Triple("Romantic", "romantic songs", SearchType.PLAYLIST),
            Triple("Fresh albums", "new albums", SearchType.ALBUM)
        )
    }
}
