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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class HomeUiState(
    val greeting: String = "",
    val userName: String? = null,
    val selectedFilter: String = "Everything",
    val mixes: List<Mix> = emptyList(),
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

    init {
        updateGreeting()
        observeAuth()
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

    fun loadHome(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // 1. Load recommendations / mixes
            launch { buildMixes() }

            // 2. Load home feed
            when (val result = musicSource.home()) {
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

    private suspend fun buildMixes() {
        val liked = library.likedOnce().take(40).map { Recommender.Signal(it.videoId, it.artist, it.title) }
        val recent = history.allOnce().take(30).map { Recommender.Signal(it.videoId, it.artist, it.title) }
        if (liked.isEmpty() && recent.isEmpty()) return

        // Pick top seeds to fetch related
        val (weights, meta, _) = Recommender.seedWeights(liked, recent)
        val seeds = Recommender.pickSeeds(weights, meta, 4)

        val relatedMap = mutableMapOf<String, List<Track>>()
        for (seed in seeds) {
            when (val res = musicSource.related(seed)) {
                is MusicResult.Success -> relatedMap[seed] = res.value
                else -> Unit
            }
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
        _uiState.update { it.copy(mixes = mixes) }
    }
}
