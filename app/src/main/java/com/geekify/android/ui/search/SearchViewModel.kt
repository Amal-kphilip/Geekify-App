package com.geekify.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.data.local.*
import com.geekify.android.data.model.*
import com.geekify.android.data.source.MusicResult
import com.geekify.android.data.source.MusicSource
import com.geekify.android.di.DefaultDispatcher
import com.geekify.android.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val selectedTab: String = "All",
    val suggestions: List<String> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val libraryTracks: List<Track> = emptyList(),
    val libraryPlaylists: List<Card> = emptyList(),
    val response: SearchResponse? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val musicSource: MusicSource,
    private val recentSearchDao: RecentSearchDao,
    private val likedDao: LikedDao,
    private val historyDao: HistoryDao,
    private val playlistDao: PlaylistDao,
    private val playStatDao: PlayStatDao,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var suggestionsJob: Job? = null
    private var requestGeneration = 0L

    val genreTags = listOf(
        "Malayalam hits", "Tamil hits", "Hindi hits", "Telugu hits",
        "Punjabi hits", "Hip-hop", "Pop hits", "Lo-fi & chill",
        "Workout", "Rock classics", "Romantic", "K-pop"
    )

    init {
        viewModelScope.launch {
            recentSearchDao.observeRecent().collect { list ->
                _uiState.update { it.copy(recentSearches = list.map { r -> r.query }) }
            }
        }
    }

    fun onQueryChange(newQuery: String) {
        requestGeneration++
        searchJob?.cancel()
        suggestionsJob?.cancel()

        _uiState.update { it.copy(query = newQuery) }

        if (newQuery.isBlank()) {
            _uiState.update {
                it.copy(
                    suggestions = emptyList(),
                    libraryTracks = emptyList(),
                    libraryPlaylists = emptyList(),
                    response = null,
                    isLoading = false,
                    error = null
                )
            }
            return
        }

        // Suggestions with 150ms debounce
        suggestionsJob = viewModelScope.launch {
            delay(150)
            val suggestions = musicSource.searchSuggestions(newQuery)
            _uiState.update { it.copy(suggestions = suggestions) }
        }

        // Full search with 350ms debounce
        startSearch(newQuery, debounce = true)
    }

    fun onSuggestionClick(suggestion: String) {
        _uiState.update { it.copy(query = suggestion, suggestions = emptyList()) }
        recordRecentSearch(suggestion)
        startSearch(suggestion, debounce = false)
    }

    fun onSubmit(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        _uiState.update { it.copy(suggestions = emptyList()) }
        recordRecentSearch(q)
        startSearch(q, debounce = false)
    }

    fun onTabSelected(tab: String) {
        if (_uiState.value.selectedTab == tab) return
        _uiState.update { it.copy(selectedTab = tab) }
        if (_uiState.value.query.isNotBlank()) {
            startSearch(_uiState.value.query, debounce = false)
        }
    }

    fun onTagClick(tag: String) {
        _uiState.update { it.copy(query = tag, suggestions = emptyList()) }
        recordRecentSearch(tag)
        startSearch(tag, debounce = false)
    }

    fun deleteRecentSearch(query: String) {
        viewModelScope.launch(ioDispatcher) {
            recentSearchDao.delete(query)
        }
    }

    fun clearAllRecentSearches() {
        viewModelScope.launch(ioDispatcher) {
            recentSearchDao.clearAll()
        }
    }

    private fun recordRecentSearch(query: String) {
        viewModelScope.launch(ioDispatcher) {
            recentSearchDao.addSearch(query)
        }
    }

    private suspend fun searchLocalLibrary(query: String) {
        withContext(ioDispatcher) {
            val q = query.trim()
            if (q.isBlank()) {
                _uiState.update { it.copy(libraryTracks = emptyList(), libraryPlaylists = emptyList()) }
                return@withContext
            }
            val liked = likedDao.searchLiked(q).map { it.toTrack() }
            val history = historyDao.searchHistory(q).map { it.toTrack() }
            val matchingTracks = (liked + history).distinctBy { it.videoId }.take(10)

            val playlists = playlistDao.searchPlaylists(q).map { p ->
                Card(
                    id = p.id,
                    title = p.name,
                    subtitle = "Local Playlist",
                    thumbnails = emptyList(),
                    type = "playlist",
                    playlistId = p.id
                )
            }

            _uiState.update {
                it.copy(
                    libraryTracks = matchingTracks,
                    libraryPlaylists = playlists
                )
            }
        }
    }

    private fun startSearch(query: String, debounce: Boolean = false) {
        val q = query.trim()
        if (q.isBlank()) return

        val generation = ++requestGeneration
        val requestedTab = _uiState.value.selectedTab
        searchJob?.cancel()

        _uiState.update { it.copy(isLoading = true, error = null) }

        searchJob = viewModelScope.launch {
            if (debounce) delay(350)
            if (generation != requestGeneration || _uiState.value.query.trim() != q || _uiState.value.selectedTab != requestedTab) return@launch

            // Search local library concurrently
            launch { searchLocalLibrary(q) }

            val type = when (requestedTab) {
                "Songs" -> SearchType.SONG
                "Videos" -> SearchType.VIDEO
                "Albums" -> SearchType.ALBUM
                "Artists" -> SearchType.ARTIST
                "Playlists" -> SearchType.PLAYLIST
                else -> null
            }

            try {
                // Fetch favorite artists for re-ranking boost
                val favoriteArtists = withContext(ioDispatcher) {
                    val topStats = playStatDao.top(25).map { it.artist }
                    val topLiked = likedDao.allOnce().map { it.artist }
                    (topStats + topLiked).toSet()
                }

                when (val result = musicSource.search(q, type)) {
                    is MusicResult.Success -> {
                        if (generation == requestGeneration && _uiState.value.query.trim() == q && _uiState.value.selectedTab == requestedTab) {
                            val rankedResponse = withContext(defaultDispatcher) {
                                result.value.copy(
                                    songs = SearchRelevance.rankTracks(q, result.value.songs, favoriteArtists),
                                    albums = SearchRelevance.rankCards(q, result.value.albums, favoriteArtists),
                                    artists = SearchRelevance.rankCards(q, result.value.artists, favoriteArtists),
                                    playlists = SearchRelevance.rankCards(q, result.value.playlists, favoriteArtists)
                                )
                            }
                            _uiState.update {
                                it.copy(
                                    response = rankedResponse,
                                    isLoading = false,
                                    error = null
                                )
                            }
                        }
                    }
                    is MusicResult.Failure -> {
                        if (generation == requestGeneration) {
                            _uiState.update { it.copy(error = result.message, isLoading = false) }
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Cancelled by a newer query
            } catch (error: Exception) {
                if (generation == requestGeneration) {
                    _uiState.update {
                        it.copy(
                            error = error.message ?: "Search failed. Please try again.",
                            isLoading = false
                        )
                    }
                }
            }
        }
    }

    fun loadMore() {
        val currentResponse = _uiState.value.response ?: return
        val continuation = currentResponse.continuation ?: return
        if (_uiState.value.isLoadingMore) return

        _uiState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            try {
                when (val result = musicSource.searchContinuation(continuation)) {
                    is MusicResult.Success -> {
                        val next = result.value
                        val merged = currentResponse.copy(
                            songs = (currentResponse.songs + next.songs).distinctBy { it.videoId },
                            albums = (currentResponse.albums + next.albums).distinctBy { it.id },
                            artists = (currentResponse.artists + next.artists).distinctBy { it.id },
                            playlists = (currentResponse.playlists + next.playlists).distinctBy { it.id },
                            continuation = next.continuation
                        )
                        _uiState.update { it.copy(response = merged, isLoadingMore = false) }
                    }
                    is MusicResult.Failure -> {
                        _uiState.update { it.copy(isLoadingMore = false) }
                    }
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }
}
