package com.geekify.android.data.source

import com.geekify.android.data.cache.TtlLruCache
import com.geekify.android.data.local.CatalogRepository
import com.geekify.android.di.DefaultDispatcher
import com.geekify.android.di.IoDispatcher
import com.geekify.android.settings.AppSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import java.util.Locale

@Singleton
class YouTubeMusicSource @Inject constructor(
    private val innerTube: InnerTubeClient,
    private val cache: TtlLruCache,
    private val catalog: CatalogRepository,
    private val appSettings: AppSettingsRepository,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MusicSource {
    /** Write-through to the offline catalog never blocks (or fails) the request that triggered it. */
    private val cacheScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val inFlightSearches = ConcurrentHashMap<String, Deferred<SearchResponse>>()

    private fun localeSuffix(): String {
        val s = appSettings.state.value
        return "${s.contentCountry}:${s.contentLanguage}"
    }

    private fun normalizeQuery(q: String): String =
        Normalizer.normalize(q.trim().replace(Regex("\\s+"), " "), Normalizer.Form.NFKC)

    private fun List<Track>.filterExplicitIfNeeded(): List<Track> =
        if (appSettings.state.value.hideExplicit) filterNot { it.explicit } else this

    private fun List<ShelfItem>.filterExplicitItemsIfNeeded(): List<ShelfItem> =
        if (appSettings.state.value.hideExplicit) filterNot { it is ShelfTrack && it.value.explicit } else this

    override suspend fun search(query: String, type: SearchType?): MusicResult<SearchResponse> = guarded {
        require(query.isNotBlank()) { "Enter a search term." }
        val normalizedQuery = normalizeQuery(query)
        val kind = type?.name?.lowercase(Locale.ROOT)
        val key = "search:${normalizedQuery.lowercase(Locale.ROOT)}:$kind:${localeSuffix()}"

        val cached = cache.get<SearchResponse>(key)
        val res = if (cached != null) {
            cached
        } else {
            val deferred = inFlightSearches.computeIfAbsent(key) {
                cacheScope.async(defaultDispatcher) {
                    val result = searchAndParse(normalizedQuery, kind)
                    cache.put(key, result, FIFTEEN_MINUTES)
                    catalog.cacheTracks(result.songs)
                    result
                }
            }
            try {
                deferred.await()
            } finally {
                inFlightSearches.remove(key, deferred)
            }
        }

        res.copy(
            songs = res.songs.filterExplicitIfNeeded(),
            shelves = res.shelves.mapNotNull { shelf ->
                shelf.items.filterExplicitItemsIfNeeded().takeIf { it.isNotEmpty() }?.let { shelf.copy(items = it) }
            }
        )
    }

    override suspend fun searchSuggestions(query: String): List<String> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val normalized = normalizeQuery(q).lowercase(Locale.ROOT)
        val key = "suggestions:$normalized"
        val cached = cache.get<List<String>>(key)
        if (cached != null) return cached
        val fetched = innerTube.searchSuggestions(q)
        if (fetched.isNotEmpty()) {
            cache.put(key, fetched, FIFTEEN_MINUTES)
        }
        return fetched
    }

    override suspend fun searchContinuation(continuation: String): MusicResult<SearchResponse> = guarded {
        val raw = withContext(ioDispatcher) { innerTube.searchContinuation(continuation) }
        val parsed = withContext(defaultDispatcher) { Parsers.parseSearchResponseSinglePass(raw, "", null) }
        parsed.copy(
            songs = parsed.songs.filterExplicitIfNeeded(),
            shelves = parsed.shelves.mapNotNull { shelf ->
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
        val key = "track:$videoId"
        cache.get<Track>(key) ?: run {
            val raw = innerTube.player(videoId)
            withContext(Dispatchers.Default) {
                parseTrackDetails(raw, videoId)
            }.also { cache.put(key, it, FIFTEEN_MINUTES) }
        }
    }

    override suspend fun related(videoId: String): MusicResult<List<Track>> = guarded {
        val key = "related:$videoId:${localeSuffix()}"; (cache.get<List<Track>>(key) ?: run {
            val radio = runCatching { innerTube.next(videoId, "RDAMVM$videoId") }.getOrElse { innerTube.next(videoId) }
            withContext(Dispatchers.Default) {
                Parsers.tracks(radio, 50).filterNot { it.videoId == videoId }
            }.also { if (it.isNotEmpty()) cache.put(key, it, FIFTEEN_MINUTES) }
        }).filterExplicitIfNeeded()
    }

    override suspend fun artist(channelId: String): MusicResult<ArtistPage> = guarded {
        val key = "artist:$channelId:${localeSuffix()}"; cache.get<ArtistPage>(key) ?: run {
            val raw = innerTube.browse(channelId)
            withContext(Dispatchers.Default) {
                parseArtist(raw, channelId)
            }.also {
                cache.put(key, it, FIFTEEN_MINUTES)
                cacheScope.launch { catalog.cacheArtist(it) }
            }
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
            val rawCollection = raw ?: throw (last ?: IllegalStateException("Collection not found"))
            withContext(Dispatchers.Default) {
                parseCollection(rawCollection, id, kind)
            }.also {
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
            val concurrencyLimit = Semaphore(3)
            val endpoints = listOf("FEmusic_home", "FEmusic_explore", "FEmusic_charts", "FEmusic_new_releases")
            val browseDeferred = endpoints.map { endpoint ->
                async(Dispatchers.IO) {
                    concurrencyLimit.withPermit {
                        withTimeoutOrNull(10_000L) {
                            runCatching {
                                val raw = innerTube.browse(endpoint)
                                withContext(Dispatchers.Default) { parseHome(raw).shelves }
                            }.getOrDefault(emptyList())
                        }.orEmpty()
                    }
                }
            }
            val regionalSpecs = buildRegionalShelfSpecs(countryCode, countryName, languageCode, languageName)
            val regionalDeferred = regionalSpecs.map { spec ->
                async(Dispatchers.IO) {
                    concurrencyLimit.withPermit {
                        withTimeoutOrNull(8_000L) {
                            runCatching { fetchCuratedShelf(spec) }.getOrNull()
                        }
                    }
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
            SearchType.VIDEO -> result.songs.take(16).map(::ShelfTrack) +
                result.playlists.take(4).map(::ShelfCard)
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

    /** A filtered tab that comes back empty falls back to the unfiltered response. */
    private suspend fun searchAndParse(query: String, kind: String?): SearchResponse = withContext(ioDispatcher) {
        val raw = innerTube.search(query, kind)
        val parsed = withContext(defaultDispatcher) { parseSearch(raw, query, kind) }
        val empty = when (kind) {
            "song", "video" -> parsed.songs.isEmpty()
            "album" -> parsed.albums.isEmpty()
            "artist" -> parsed.artists.isEmpty()
            "playlist" -> parsed.playlists.isEmpty()
            else -> false
        }
        if (empty) {
            runCatching {
                val fallbackRaw = innerTube.search(query, null)
                withContext(defaultDispatcher) { parseSearch(fallbackRaw, query, null) }
            }.getOrDefault(parsed)
        } else parsed
    }

    internal fun parseSearch(raw: JsonObject, query: String, type: String?): SearchResponse =
        Companion.parseSearch(raw, query, type)

    internal fun parseTrackDetails(raw: JsonObject, fallbackVideoId: String): Track =
        Companion.parseTrackDetails(raw, fallbackVideoId)

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

    internal companion object {
        const val FIFTEEN_MINUTES = 15 * 60 * 1000L

        internal fun formatDuration(s: Int): String =
            if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
            else "%d:%02d".format(s / 60, s % 60)

        internal fun parseSearch(raw: JsonObject, query: String, type: String?): SearchResponse =
            Parsers.parseSearchResponseSinglePass(raw, query, type)

        internal fun parseTrackDetails(raw: JsonObject, fallbackVideoId: String): Track {
            val details = raw["videoDetails"] as? JsonObject ?: error("Track metadata is unavailable.")
            fun JsonObject.primitiveString(key: String): String? =
                (this[key] as? JsonPrimitive)?.contentOrNull

            val seconds = details.primitiveString("lengthSeconds")?.toIntOrNull()
            val artist = details.primitiveString("author")?.trim().orEmpty().ifBlank { "Unknown" }
            val resolvedId = details.primitiveString("videoId")?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackVideoId
            val title = details.primitiveString("title")?.trim().orEmpty().ifBlank { "Unknown" }
            val channelId = details.primitiveString("channelId")?.trim()?.takeIf { it.isNotEmpty() }
            return Track(
                videoId = resolvedId,
                title = title,
                artist = artist,
                artists = listOf(ArtistRef(artist, channelId)),
                thumbnails = Parsers.findThumbnails(raw),
                durationSeconds = seconds,
                duration = seconds?.let(::formatDuration)
            )
        }

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
