package com.geekify.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.data.auth.AuthRepository
import com.geekify.android.core.UiState
import com.geekify.android.data.local.HistoryRepository
import com.geekify.android.data.local.ShelfCacheRepository
import com.geekify.android.data.local.LibraryRepository
import com.geekify.android.data.local.ListeningStatsRepository
import com.geekify.android.data.model.*
import com.geekify.android.data.source.MusicResult
import com.geekify.android.data.source.MusicSource
import com.geekify.android.domain.recommend.RecommendationMemory
import com.geekify.android.domain.recommend.Recommender
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject

data class HomeUiState(
    val greeting: String = "",
    val userName: String? = null,
    val selectedFilter: String = "Everything",
    val mixes: List<Mix> = emptyList(),
    val shelves: List<Shelf> = emptyList(),
    /** Personalized + regional candidates for the Top daily section, keyed by the active Home filter. */
    val dailyRecommendations: Map<String, List<ShelfItem>> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** The shelves on screen come from the local cache (cold start or offline), not from a fresh response. */
    val fromCache: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val musicSource: MusicSource,
    private val library: LibraryRepository,
    private val history: HistoryRepository,
    private val listeningStats: ListeningStatsRepository,
    private val auth: AuthRepository,
    private val memory: RecommendationMemory,
    private val shelfCache: ShelfCacheRepository,
    private val appSettings: com.geekify.android.settings.AppSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var allShelves: List<Shelf> = emptyList()
    /** The feed as the screen renders it: Loading, Success(shelves) or Error. Cached shelves count as Success. */
    val feedState: StateFlow<UiState<List<Shelf>>> = _uiState
        .map { s ->
            when {
                s.shelves.isNotEmpty() -> UiState.Success(s.shelves)
                s.isLoading -> UiState.Loading
                s.error != null -> UiState.Error(IllegalStateException(s.error))
                else -> UiState.Loading
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    private var mixJob: Job? = null
    private var lastMixBuild = 0L
    private var liveFeedLoaded = false

    init {
        updateGreeting()
        observeAuth()
        observeTaste()
        observeContentSettings()
        showCachedFeed()
        loadHome()
    }

    /** Instant content on a cold start: the last feed is shown from Room while the network request runs. */
    private fun showCachedFeed() {
        viewModelScope.launch {
            val cached = shelfCache.load() ?: return@launch
            // The network may already have won the race; never replace fresh content with old content.
            if (liveFeedLoaded || allShelves.isNotEmpty()) return@launch
            allShelves = cached
            applyFilter(_uiState.value.selectedFilter)
            rebuildDailyRecommendations()
            // Cached shelves are already fully usable. Do not keep the startup loader
            // visible while the fresh network feed refreshes in the background.
            _uiState.update { it.copy(isLoading = false, fromCache = false) }
        }
    }

    /**
     * Rebuilds the mixes when the listening taste changes (a different latest song, a new like), so
     * "Made for you" follows what is being played instead of staying frozen until a manual refresh.
     * Debounced and rate-limited because each build asks the network for related songs.
     */
    @OptIn(FlowPreview::class)
    private fun observeTaste() {
        combine(
            history.recent.map { tracks ->
                tracks.take(10).joinToString("|") { it.videoId }
            }.distinctUntilChanged(),
            library.liked.map { tracks ->
                tracks.take(40).joinToString("|") { it.videoId }
            }.distinctUntilChanged(),
            library.playlists.map { playlists ->
                playlists.joinToString("|") { playlist ->
                    playlist.id + ":" + playlist.tracks.joinToString(",") { it.videoId }
                }
            }.distinctUntilChanged(),
            history.topPlayedFlow(30).map { stats ->
                stats.joinToString("|") { stat -> "${stat.videoId}:${stat.plays}" }
            }.distinctUntilChanged(),
            library.savedCollections.map { collections ->
                collections.take(20).joinToString("|") { collection ->
                    collection.id + ":" + (collection.subtitle ?: "")
                }
            }.distinctUntilChanged()
        ) { _, _, _, _, _ -> Unit }
            .drop(1)
            .debounce(5_000)
            .onEach {
                if (System.currentTimeMillis() - lastMixBuild >= MIN_REBUILD_INTERVAL_MS) startMixBuild()
            }
            .launchIn(viewModelScope)
    }

    private fun startMixBuild(): Job {
        mixJob?.cancel()
        return viewModelScope.launch { runCatching { buildMixes() } }.also { mixJob = it }
    }

    private fun updateGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour in 0..4 -> "Still up"
            hour in 5..11 -> "Good morning"
            hour in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
        _uiState.update { it.copy(greeting = greeting) }
    }

    private fun observeAuth() {
        auth.user.onEach { user ->
            _uiState.update { it.copy(userName = user?.name?.split(" ")?.firstOrNull()) }
        }.launchIn(viewModelScope)
    }

    private fun observeContentSettings() {
        appSettings.state
            .map { Triple(it.contentCountry, it.contentLanguage, it.hideExplicit) }
            .distinctUntilChanged()
            .drop(1)
            .onEach {
                lastMixBuild = 0L
                loadHome(forceRefresh = true)
            }
            .launchIn(viewModelScope)
    }

    fun loadHome(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // 1. Load recommendations / mixes (in parallel with the feed)
            val mixes = startMixBuild()

            // 2. Load home feed
            when (val result = musicSource.home()) {
                is MusicResult.Success -> {
                    liveFeedLoaded = true
                    allShelves = result.value.shelves
                    applyFilter(_uiState.value.selectedFilter)
                    syncRegionalMixAndDailyRecommendations()
                    _uiState.update { it.copy(isLoading = false, fromCache = false) }
                    shelfCache.save(result.value.shelves)
                    // Do not block the Home screen on personalized recommendation network calls.
                    // The feed is already usable; the mixes update when their background job finishes.
                    if (mixes.isCompleted && _uiState.value.mixes.isEmpty()) buildStarterMix()
                    mixes.invokeOnCompletion {
                        if (_uiState.value.mixes.isEmpty()) {
                            viewModelScope.launch { runCatching { buildStarterMix() } }
                        } else {
                            syncRegionalMixAndDailyRecommendations()
                        }
                    }
                }
                is MusicResult.Failure -> {
                    // Keep whatever is on screen (cached shelves); the screen shows a retry banner.
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
            }
        }
    }

    private fun syncRegionalMixAndDailyRecommendations() {
        val currentMixes = _uiState.value.mixes.filterNot { it.id == "regional-pulse" }
        val regionalMix = buildRegionalPulseMix()
        val updatedMixes = if (regionalMix != null) {
            if (currentMixes.isEmpty()) {
                listOf(regionalMix)
            } else {
                // Place Regional Pulse right after "Made for you" so region/language changes
                // are immediately visible in the top Curated & trending carousel too.
                buildList {
                    add(currentMixes.first())
                    add(regionalMix)
                    addAll(currentMixes.drop(1))
                }
            }
        } else {
            currentMixes
        }
        if (updatedMixes != _uiState.value.mixes) {
            _uiState.update { it.copy(mixes = updatedMixes) }
        }
        rebuildDailyRecommendations(updatedMixes)
    }

    private fun buildRegionalPulseMix(): Mix? {
        if (allShelves.isEmpty()) return null
        val settings = appSettings.state.value
        val countryLabel = COUNTRY_LABELS[settings.contentCountry.uppercase()] ?: settings.contentCountry.uppercase()
        val langLabel = LANGUAGE_LABELS[settings.contentLanguage.lowercase()]
        val regionalTracks = allShelves
            .take(5)
            .flatMap { it.items }
            .filterIsInstance<ShelfTrack>()
            .map { it.value }
            .filter { (it.durationSeconds ?: 0) <= Recommender.MAX_TRACK_SECONDS }
            .distinctBy { it.videoId }
            .take(25)
        if (regionalTracks.size < 5) return null
        val title = if (!langLabel.isNullOrBlank() && settings.contentLanguage.lowercase() != "en") {
            "Top in $countryLabel • $langLabel"
        } else {
            "Trending in $countryLabel"
        }
        val subtitle = if (!langLabel.isNullOrBlank()) {
            "Popular $langLabel & $countryLabel hits right now"
        } else {
            "Chart-topping songs in $countryLabel right now"
        }
        return Mix(
            id = "regional-pulse",
            title = title,
            subtitle = subtitle,
            tracks = regionalTracks
        )
    }

    fun setFilter(filter: String) {
        _uiState.update { it.copy(selectedFilter = filter) }
        applyFilter(filter)
    }

    private fun applyFilter(filter: String) {
        val filtered = when (filter) {
            "Songs" -> allShelves.mapNotNull { shelf ->
                val items = shelf.items.filter { it is ShelfTrack || (it is ShelfCard && it.value.type == "song") }
                if (items.isNotEmpty()) shelf.copy(items = items) else null
            }
            "Albums" -> allShelves.mapNotNull { shelf ->
                val items = shelf.items.filter { it is ShelfCard && it.value.type == "album" }
                if (items.isNotEmpty()) shelf.copy(items = items) else null
            }
            "Playlists" -> allShelves.mapNotNull { shelf ->
                val items = shelf.items.filter { it is ShelfCard && it.value.type == "playlist" }
                if (items.isNotEmpty()) shelf.copy(items = items) else null
            }
            "Artists" -> allShelves.mapNotNull { shelf ->
                val items = shelf.items.filter { it is ShelfCard && it.value.type == "artist" }
                if (items.isNotEmpty()) shelf.copy(items = items) else null
            }
            else -> allShelves
        }
        _uiState.update { it.copy(shelves = filtered) }
    }

    /** Does the heavy lifting on a background dispatcher so the UI thread never waits for it. */
    private suspend fun buildMixes() = withContext(Dispatchers.Default) {
        lastMixBuild = System.currentTimeMillis()
        val now = lastMixBuild
        val sevenDaysAgo = now - 7L * 24 * 60 * 60_000L

        val likedTracks = library.likedOnce()
        val recentTracks = history.allOnce()
        val playlistTracks = library.playlistsOnce().flatMap { it.tracks }.distinctBy { it.videoId }
        val savedArtists = library.savedCollectionsOnce().mapNotNull { it.subtitle?.takeIf { s -> s.isNotBlank() } }
        val frequentStats = history.topPlayed(30)
        val frequent = frequentStats.map {
            Recommender.PlayedSignal(it.videoId, it.artist, it.title, it.plays)
        }
        val rawSessions = runCatching { listeningStats.latest(800) }.getOrDefault(emptyList())
        val sessionSignals = rawSessions
            .groupBy { it.videoId }
            .mapNotNull { (vid, rows) ->
                val sample = rows.firstOrNull() ?: return@mapNotNull null
                val meaningful = rows.filter { it.listenedMs >= 10_000L }
                val compRate = if (meaningful.isEmpty()) 0.5 else meaningful.count { it.completed }.toDouble() / meaningful.size
                val skipRate = rows.count { it.skipped }.toDouble() / rows.size
                val recent7d = meaningful.count { it.startedAt >= sevenDaysAgo }
                val lastPlayed = rows.maxOfOrNull { it.startedAt } ?: 0L
                Recommender.SessionSignal(
                    videoId = vid,
                    artist = sample.artist,
                    title = sample.title,
                    completionRate = compRate,
                    skipRate = skipRate,
                    recentPlays7d = recent7d,
                    lastPlayedAtMs = lastPlayed
                )
            }

        fun signal(t: Track) = Recommender.Signal(t.videoId, t.artist, t.title)
        val liked = likedTracks.take(40).map(::signal)
        val recent = recentTracks.take(30).map(::signal)
        val playlist = playlistTracks.take(60).map(::signal)

        if (liked.isEmpty() && recent.isEmpty() && frequent.isEmpty() && playlist.isEmpty()) return@withContext

        val settings = appSettings.state.value
        val preferredContentLanguage = LANGUAGE_TO_RECOMMENDER_TAG[settings.contentLanguage.lowercase()]
        val hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        val languageProfile = Recommender.languageAffinity(liked, recent, frequent, playlist)
        val (weights, meta, _) = Recommender.seedWeights(
            liked,
            recent,
            frequent,
            playlist,
            savedArtists,
            languageAffinity = languageProfile.affinity,
            sessions = sessionSignals
        )
        val seeds = Recommender.pickSeedsVaried(weights, meta, SEED_COUNT)

        // Ask for every seed's related songs at the same time (results are cached for 15 minutes).
        val relatedMap: Map<String, List<Track>> = coroutineScope {
            seeds.map { seed -> async { seed to musicSource.related(seed) } }.awaitAll()
        }.mapNotNull { (seed, res) -> (res as? MusicResult.Success)?.value?.let { seed to it } }.toMap()
        // Offline or rate limited: keep whatever mixes are already on screen.
        if (relatedMap.isEmpty()) return@withContext

        val exclude = (likedTracks + recentTracks + playlistTracks).map { it.videoId }.toMutableSet().apply {
            addAll(frequent.map { it.videoId })
        }

        val (mixResults, _) = Recommender.buildMixes(
            liked = liked,
            recent = recent,
            related = relatedMap,
            videoId = { it.videoId },
            artist = { it.artist },
            artistDisplay = { it.artist },
            title = { it.title },
            thumbs = { it.thumbnails },
            duration = { it.duration },
            durationSeconds = { it.durationSeconds },
            explicit = { it.explicit },
            type = { it.type },
            frequent = frequent,
            playlist = playlist,
            savedArtists = savedArtists,
            exclude = exclude,
            shown = memory.snapshot(),
            seeds = seeds,
            sessions = sessionSignals,
            hourOfDay = hourOfDay,
            preferredContentLanguage = preferredContentLanguage
        )

        val allKnownTracksById = (likedTracks + recentTracks + playlistTracks + relatedMap.values.flatten())
            .associateBy { it.videoId }
        val tracksById = relatedMap.values.flatten().associateBy { it.videoId }

        val mixes = mixResults.mapNotNull { mr ->
            val tracks = mr.videoIds.mapNotNull { tracksById[it] }
            if (tracks.isNotEmpty()) {
                Mix(
                    id = mr.id,
                    title = mr.title,
                    subtitle = mr.subtitle,
                    tracks = tracks
                )
            } else null
        }.toMutableList()

        // "On Repeat": heavy-rotation tracks from recent days paired with top sonic matches
        val onRepeatFavorites = buildList {
            val heavyIds = sessionSignals
                .filter { it.recentPlays7d >= 2 && it.skipRate < 0.5 }
                .sortedByDescending { it.recentPlays7d * (0.5 + it.completionRate) }
                .map { it.videoId }
            val topStatIds = frequentStats.sortedByDescending { it.plays }.map { it.videoId }
            (heavyIds + topStatIds + recentTracks.take(10).map { it.videoId } + likedTracks.take(10).map { it.videoId })
                .distinct()
                .mapNotNull { allKnownTracksById[it] }
                .take(12)
                .let(::addAll)
        }
        if (onRepeatFavorites.size >= 4) {
            val companionTracks = mixes.firstOrNull { it.id == "for-you" }?.tracks.orEmpty().take(12)
            val blendedOnRepeat = buildList {
                val maxIdx = maxOf(onRepeatFavorites.size, companionTracks.size)
                for (i in 0 until maxIdx) {
                    onRepeatFavorites.getOrNull(i)?.let(::add)
                    companionTracks.getOrNull(i)?.let(::add)
                }
            }.distinctBy { it.videoId }.take(24)
            if (blendedOnRepeat.size >= 8) {
                mixes.add(
                    Mix(
                        id = "on-repeat",
                        title = "On Repeat",
                        subtitle = "Songs you can't stop playing right now",
                        tracks = blendedOnRepeat
                    )
                )
            }
        }

        // "Repeat Rewind": beloved older favorites not in the very latest session
        val immediateRecentIds = recentTracks.take(6).map { it.videoId }.toSet()
        val rewindFavorites = (likedTracks.drop(3) + playlistTracks + recentTracks.drop(8))
            .filter { it.videoId !in immediateRecentIds }
            .distinctBy { it.videoId }
            .take(14)
        if (rewindFavorites.size >= 5) {
            val freshCompanions = relatedMap.values.flatten()
                .filter { it.videoId !in immediateRecentIds }
                .distinctBy { it.videoId }
                .take(10)
            val rewindTracks = (rewindFavorites + freshCompanions).distinctBy { it.videoId }.take(22)
            if (rewindTracks.size >= 8) {
                mixes.add(
                    Mix(
                        id = "repeat-rewind",
                        title = "Repeat Rewind",
                        subtitle = "Past favorites worth spinning again",
                        tracks = rewindTracks
                    )
                )
            }
        }

        val regionalMix = buildRegionalPulseMix()
        if (regionalMix != null) {
            val insertIdx = if (mixes.isNotEmpty()) 1 else 0
            mixes.add(insertIdx, regionalMix)
        }

        if (mixes.isEmpty()) return@withContext
        memory.remember(mixes.filter { it.id != "on-repeat" && it.id != "repeat-rewind" }.flatMap { mix -> mix.tracks.take(10).map { it.videoId } })
        _uiState.update { it.copy(mixes = mixes) }
        rebuildDailyRecommendations(mixes)
    }

    /**
     * Combines the personalized "Made for you" recommendations with the active regional/language
     * Home shelves so that "Top daily picks" reflects both personal taste AND any Content Country
     * or Content Language changes in Settings immediately.
     */
    private fun rebuildDailyRecommendations(mixes: List<Mix> = _uiState.value.mixes) {
        val forYou = mixes.firstOrNull { it.id == "for-you" }?.tracks.orEmpty()
        val regionalShelfItems = allShelves.flatMap { it.items }

        val regionalSongs: List<ShelfTrack> = regionalShelfItems
            .filterIsInstance<ShelfTrack>()
            .distinctBy { it.value.videoId }
            .take(15)

        val personalSongs: List<ShelfTrack> = forYou
            .distinctBy { it.videoId }
            .take(15)
            .map(::ShelfTrack)

        // Interleave regional songs and personal recommendations so both are immediately visible
        val songs: List<ShelfItem> = buildList {
            val max = maxOf(regionalSongs.size, personalSongs.size)
            for (i in 0 until max) {
                regionalSongs.getOrNull(i)?.let(::add)
                personalSongs.getOrNull(i)?.let(::add)
            }
        }.distinctBy { it.id }.take(20)

        if (songs.isEmpty() && regionalShelfItems.isEmpty()) return

        val personalAlbums: List<ShelfCard> = forYou
            .asSequence()
            .filter { !it.albumId.isNullOrBlank() && !it.album.isNullOrBlank() }
            .distinctBy { it.albumId }
            .take(12)
            .map { track ->
                ShelfCard(
                    Card(
                        id = track.albumId!!,
                        title = track.album!!,
                        subtitle = track.artist,
                        thumbnails = track.thumbnails,
                        type = "album",
                        browseId = track.albumId
                    )
                )
            }
            .toList()

        val regionalAlbums: List<ShelfCard> = regionalShelfItems
            .filterIsInstance<ShelfCard>()
            .filter { it.value.type == "album" }
            .distinctBy { it.value.id }
            .take(12)

        val albums: List<ShelfItem> = buildList {
            val max = maxOf(regionalAlbums.size, personalAlbums.size)
            for (i in 0 until max) {
                regionalAlbums.getOrNull(i)?.let(::add)
                personalAlbums.getOrNull(i)?.let(::add)
            }
        }.distinctBy { it.id }.take(20)

        val personalArtists: List<ShelfCard> = forYou
            .asSequence()
            .flatMap { track ->
                if (track.artists.isNotEmpty()) track.artists.asSequence()
                else sequenceOf(ArtistRef(track.artist, null))
            }
            .filter { !it.id.isNullOrBlank() && it.name.isNotBlank() }
            .distinctBy { it.id }
            .take(14)
            .map { artist ->
                val artTrack = forYou.firstOrNull { t ->
                    t.artists.any { a -> a.id == artist.id } || t.artist.equals(artist.name, ignoreCase = true)
                }
                ShelfCard(
                    Card(
                        id = artist.id!!,
                        title = artist.name,
                        subtitle = "Artist",
                        thumbnails = artTrack?.thumbnails.orEmpty(),
                        type = "artist",
                        browseId = artist.id
                    )
                )
            }
            .toList()

        val regionalArtists: List<ShelfCard> = regionalShelfItems
            .filterIsInstance<ShelfCard>()
            .filter { it.value.type == "artist" }
            .distinctBy { it.value.id }
            .take(10)

        val artists: List<ShelfItem> = (regionalArtists + personalArtists).distinctBy { it.id }.take(20)

        val preferredArtists = forYou
            .flatMap { t ->
                buildList {
                    add(t.artist)
                    addAll(t.artists.map { it.name })
                }
            }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(20)

        val playlists: List<ShelfItem> = allShelves
            .asSequence()
            .flatMapIndexed { shelfIdx, shelf ->
                shelf.items.asSequence()
                    .filterIsInstance<ShelfCard>()
                    .filter { it.value.type == "playlist" }
                    .map { item -> shelfIdx to item }
            }
            .distinctBy { it.second.value.id }
            .map { (shelfIdx, item) ->
                val text = buildString {
                    append(item.value.title.lowercase())
                    append(' ')
                    append(item.value.subtitle.orEmpty().lowercase())
                }
                val artistMatchScore = preferredArtists.count { artist -> text.contains(artist) } * 2
                // Keep top regional shelves prominent while still rewarding artist affinity
                val regionalPriorityBonus = if (shelfIdx < 3) 3 else 0
                (artistMatchScore + regionalPriorityBonus) to item
            }
            .sortedByDescending { it.first }
            .map { it.second }
            .take(20)
            .toList()

        val everything = buildList {
            val max = maxOf(songs.size, albums.size, artists.size, playlists.size)
            repeat(max) { index ->
                songs.getOrNull(index)?.let(::add)
                playlists.getOrNull(index)?.let(::add)
                albums.getOrNull(index)?.let(::add)
                artists.getOrNull(index)?.let(::add)
            }
        }.distinctBy { it.id }.take(20)

        _uiState.update {
            it.copy(
                dailyRecommendations = mapOf(
                    "Everything" to everything,
                    "Songs" to songs,
                    "Albums" to albums,
                    "Playlists" to playlists,
                    "Artists" to artists
                )
            )
        }
    }

    /** Fallback for people with no listening history yet: popular songs from the feed, or a search. */
    private suspend fun buildStarterMix() {
        val settings = appSettings.state.value
        val countryLabel = COUNTRY_LABELS[settings.contentCountry.uppercase()] ?: settings.contentCountry.uppercase()
        var tracks = allShelves.asSequence()
            .flatMap { it.items.asSequence() }
            .filterIsInstance<ShelfTrack>()
            .map { it.value }
            .filter { (it.durationSeconds ?: 0) <= Recommender.MAX_TRACK_SECONDS }
            .distinctBy { it.videoId }
            .take(25)
            .toList()
        if (tracks.size < 8) {
            val found = (musicSource.search("trending songs $countryLabel", SearchType.SONG) as? MusicResult.Success)?.value?.songs.orEmpty()
            tracks = found.filter { (it.durationSeconds ?: 0) <= Recommender.MAX_TRACK_SECONDS }.take(25)
        }
        if (tracks.size >= 5) {
            _uiState.update {
                it.copy(mixes = listOf(Mix("trending", "Trending in $countryLabel", "Popular songs to get you started", tracks)))
            }
        }
    }

    private companion object {
        const val SEED_COUNT = 5
        const val MIN_REBUILD_INTERVAL_MS = 45_000L

        val COUNTRY_LABELS = mapOf(
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

        val LANGUAGE_LABELS = mapOf(
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

        val LANGUAGE_TO_RECOMMENDER_TAG = mapOf(
            "ml" to "Malayalam",
            "hi" to "Hindi",
            "ta" to "Tamil",
            "te" to "Telugu",
            "ko" to "K-pop",
            "ja" to "Japanese"
        )
    }
}
