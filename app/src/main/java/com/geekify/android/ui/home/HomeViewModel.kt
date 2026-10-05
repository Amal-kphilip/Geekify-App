package com.geekify.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.data.auth.AuthRepository
import com.geekify.android.core.UiState
import com.geekify.android.data.local.HistoryRepository
import com.geekify.android.data.local.ShelfCacheRepository
import com.geekify.android.data.local.LibraryRepository
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
    private val auth: AuthRepository,
    private val memory: RecommendationMemory,
    private val shelfCache: ShelfCacheRepository
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
            _uiState.update { it.copy(fromCache = true) }
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
            history.recent.map { it.firstOrNull()?.videoId }.distinctUntilChanged(),
            library.liked.map { it.size }.distinctUntilChanged()
        ) { latest, likes -> latest to likes }
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
                    _uiState.update { it.copy(isLoading = false, fromCache = false) }
                    shelfCache.save(result.value.shelves)
                    // New listeners (or an offline taste profile) get a sensible starter mix instead of nothing.
                    mixes.join()
                    if (_uiState.value.mixes.isEmpty()) buildStarterMix()
                }
                is MusicResult.Failure -> {
                    // Keep whatever is on screen (cached shelves); the screen shows a retry banner.
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
            }
        }
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

        val likedTracks = library.likedOnce()
        val recentTracks = history.allOnce()
        val playlistTracks = library.playlistsOnce().flatMap { it.tracks }.distinctBy { it.videoId }
        val savedArtists = library.savedCollectionsOnce().mapNotNull { it.subtitle?.takeIf { s -> s.isNotBlank() } }
        val frequent = history.topPlayed(30).map { Recommender.PlayedSignal(it.videoId, it.artist, it.title, it.plays) }

        fun signal(t: Track) = Recommender.Signal(t.videoId, t.artist, t.title)
        val liked = likedTracks.take(40).map(::signal)
        val recent = recentTracks.take(30).map(::signal)
        val playlist = playlistTracks.take(60).map(::signal)

        if (liked.isEmpty() && recent.isEmpty() && frequent.isEmpty() && playlist.isEmpty()) return@withContext

        val (weights, meta, _) = Recommender.seedWeights(liked, recent, frequent, playlist, savedArtists)
        val seeds = Recommender.pickSeedsVaried(weights, meta, SEED_COUNT)

        // Ask for every seed's related songs at the same time (results are cached for 15 minutes).
        val relatedMap: Map<String, List<Track>> = coroutineScope {
            seeds.map { seed -> async { seed to musicSource.related(seed) } }.awaitAll()
        }.mapNotNull { (seed, res) -> (res as? MusicResult.Success)?.value?.let { seed to it } }.toMap()
        // Offline or rate limited: keep whatever mixes are already on screen.
        if (relatedMap.isEmpty()) return@withContext

        val exclude = (likedTracks + recentTracks + playlistTracks).map { it.videoId }.toSet()

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
            seeds = seeds
        )

        val tracksById = relatedMap.values.flatten().associateBy { it.videoId }
        val mixes = mixResults.map { mr ->
            Mix(
                id = mr.id,
                title = mr.title,
                subtitle = mr.subtitle,
                tracks = mr.videoIds.mapNotNull { tracksById[it] }
            )
        }
        if (mixes.isEmpty()) return@withContext
        memory.remember(mixes.flatMap { mix -> mix.tracks.take(12).map { it.videoId } })
        _uiState.update { it.copy(mixes = mixes) }
    }

    /** Fallback for people with no listening history yet: popular songs from the feed, or a search. */
    private suspend fun buildStarterMix() {
        var tracks = allShelves.asSequence()
            .flatMap { it.items.asSequence() }
            .filterIsInstance<ShelfTrack>()
            .map { it.value }
            .filter { (it.durationSeconds ?: 0) <= Recommender.MAX_TRACK_SECONDS }
            .distinctBy { it.videoId }
            .take(25)
            .toList()
        if (tracks.size < 8) {
            val found = (musicSource.search("trending songs", SearchType.SONG) as? MusicResult.Success)?.value?.songs.orEmpty()
            tracks = found.filter { (it.durationSeconds ?: 0) <= Recommender.MAX_TRACK_SECONDS }.take(25)
        }
        if (tracks.size >= 5) {
            _uiState.update {
                it.copy(mixes = listOf(Mix("trending", "Trending now", "Popular songs to get you started", tracks)))
            }
        }
    }

    private companion object {
        const val SEED_COUNT = 5
        const val MIN_REBUILD_INTERVAL_MS = 45_000L
    }
}
