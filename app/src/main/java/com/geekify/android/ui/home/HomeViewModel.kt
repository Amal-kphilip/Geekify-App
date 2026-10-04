package com.geekify.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.data.auth.AuthRepository
import com.geekify.android.data.local.HistoryRepository
import com.geekify.android.data.local.LibraryRepository
import com.geekify.android.data.model.*
import com.geekify.android.data.source.MusicResult
import com.geekify.android.data.source.MusicSource
import com.geekify.android.domain.recommend.Recommender
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import javax.inject.Inject

data class HomeUiState(
    val greeting: String = "",
    val userName: String? = null,
    val selectedFilter: String = "Everything",
    val recent: List<Track> = emptyList(),
    val mixes: List<Mix> = emptyList(),
    /** "Because you listened to ..." rows built from the songs you actually play. */
    val becauseShelves: List<Shelf> = emptyList(),
    val hasTaste: Boolean = true,
    val shelves: List<Shelf> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val musicSource: MusicSource,
    private val library: LibraryRepository,
    private val history: HistoryRepository,
    private val auth: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var allShelves: List<Shelf> = emptyList()

    private val personalMutex = Mutex()
    private var lastSignature: String? = null

    init {
        updateGreeting()
        observeAuth()
        observeRecent()
        loadHome()
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

    private fun observeRecent() {
        history.recent.onEach { tracks ->
            _uiState.update { it.copy(recent = tracks.distinctBy { t -> t.videoId }.take(12)) }
        }.launchIn(viewModelScope)
    }

    private fun signal(t: Track) = Recommender.Signal(t.videoId, t.artist, t.title)

    fun loadHome(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val liked = library.likedOnce().take(40).map { signal(it) }
            val recent = history.allOnce().take(30).map { signal(it) }
            // Latin-script listening (English) has no detectable regional language; treat it as English
            // so we don't push Malayalam/Hindi shelves at someone who plays English music.
            val language = Recommender.dominantLanguage(liked, recent)
                ?: if (liked.isNotEmpty() || recent.isNotEmpty()) "English" else null

            // 1. Personal mixes (built from what you play and like)
            launch { refreshPersonal(force = forceRefresh) }

            // 2. The general feed, tuned to your language instead of random genres
            when (val result = musicSource.home(language, forceRefresh)) {
                is MusicResult.Success -> {
                    allShelves = result.value.shelves
                    applyFilter(_uiState.value.selectedFilter)
                    _uiState.update { it.copy(isLoading = false) }
                }
                is MusicResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
            }
        }
    }

    /** Called whenever Home becomes visible: rebuilds the personal rows only if your listening changed. */
    fun onScreenShown() {
        viewModelScope.launch { refreshPersonal(force = false) }
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

    private suspend fun refreshPersonal(force: Boolean) = personalMutex.withLock {
        val likedTracks = library.likedOnce().take(40)
        val recentTracks = history.allOnce().take(30)
        val liked = likedTracks.map { signal(it) }
        val recent = recentTracks.map { signal(it) }

        if (liked.isEmpty() && recent.isEmpty()) {
            lastSignature = null
            _uiState.update { it.copy(mixes = emptyList(), becauseShelves = emptyList(), hasTaste = false) }
            return@withLock
        }

        val signature = recent.take(6).joinToString(",") { it.videoId } + "|" + liked.take(10).joinToString(",") { it.videoId }
        if (!force && signature == lastSignature) return@withLock
        lastSignature = signature

        val (weights, meta, _) = Recommender.seedWeights(liked, recent)
        val latest = recent.firstOrNull()?.videoId
        val seeds = (Recommender.pickSeeds(weights, meta, Recommender.MAX_SEEDS) + listOfNotNull(latest)).distinct()

        // Fetch all seeds' radios at the same time (they used to load one after another).
        val relatedMap: Map<String, List<Track>> = coroutineScope {
            seeds.map { seed ->
                async { seed to ((musicSource.related(seed) as? MusicResult.Success)?.value.orEmpty()) }
            }.awaitAll().filter { it.second.isNotEmpty() }.toMap()
        }
        if (relatedMap.isEmpty()) {
            // Offline or rate-limited: keep what is on screen and try again next time.
            lastSignature = null
            _uiState.update { it.copy(hasTaste = true) }
            return@withLock
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
            type = { it.type }
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

        // "Because you listened to X": direct picks from your latest and most-played songs.
        val knownKeys = (liked + recent).map { Recommender.songKey(it.title, it.artist) }.toSet()
        val used = mutableSetOf<String>()
        val because = (listOfNotNull(latest) + seeds).distinct()
            .mapNotNull { seed ->
                val title = meta[seed]?.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val picks = relatedMap[seed].orEmpty()
                    .filter { t ->
                        t.videoId !in used && !Recommender.looksLikeJunk(t.title) &&
                            Recommender.songKey(t.title, t.artist) !in knownKeys &&
                            (t.durationSeconds == null || t.durationSeconds in Recommender.MIN_TRACK_SECONDS..Recommender.MAX_TRACK_SECONDS)
                    }
                    .take(15)
                if (picks.size < 6) return@mapNotNull null
                used += picks.map { it.videoId }
                Shelf("Because you listened to $title", picks.map(::ShelfTrack))
            }
            .take(3)

        _uiState.update { it.copy(mixes = mixes, becauseShelves = because, hasTaste = true) }
    }
}
