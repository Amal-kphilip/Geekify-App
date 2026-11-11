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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
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
) {
    val isEmpty: Boolean
        get() = !isLoading && error == null && query.isNotBlank() && response != null &&
            libraryTracks.isEmpty() && libraryPlaylists.isEmpty() &&
            response.songs.isEmpty() && response.albums.isEmpty() &&
            response.artists.isEmpty() && response.playlists.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
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

    companion object {
        const val SUGGESTIONS_DEBOUNCE_MS = 150L
        const val SEARCH_DEBOUNCE_MS = 300L
    }

    private data class SearchRequest(
        val query: String,
        val tab: String,
        val debounce: Boolean,
        val generation: Long,
        val force: Boolean = false
    )

    private data class SuggestionRequest(
        val query: String,
        val generation: Long
    )

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val searchRequestFlow = MutableStateFlow<SearchRequest?>(null)
    private val suggestionRequestFlow = MutableStateFlow<SuggestionRequest?>(null)

    private var loadMoreJob: Job? = null
    private var activeContinuationToken: String? = null

    private val requestGeneration = AtomicLong(0L)
    private val suggestionGeneration = AtomicLong(0L)

    @Volatile
    private var cachedFavoriteArtists: Set<String>? = null

    @Volatile
    private var inFlightRequestKey: String? = null

    @Volatile
    private var lastCompletedRequestKey: String? = null

    val genreTags = listOf(
        "Malayalam hits", "Tamil hits", "Hindi hits", "Telugu hits",
        "Punjabi hits", "Hip-hop", "Pop hits", "Lo-fi & chill",
        "Workout", "Rock classics", "Romantic", "K-pop"
    )

    init {
        viewModelScope.launch(ioDispatcher) {
            recentSearchDao.observeRecent().collect { list ->
                _uiState.update { it.copy(recentSearches = list.map { r -> r.query }) }
            }
        }

        // Maintain reactive favorite-artist set using lightweight JSON projection so searches do not re-read full tables
        viewModelScope.launch(ioDispatcher) {
            combine(
                playStatDao.observeTopArtists(25),
                likedDao.observeArtistProjections(100)
            ) { topPlayedArtists, likedProjections ->
                val likedArtists = extractDistinctArtists(likedProjections, 100)
                (topPlayedArtists + likedArtists)
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
            }.collectLatest { artists ->
                cachedFavoriteArtists = artists
            }
        }

        // Cancellation-safe suggestion pipeline using flatMapLatest
        viewModelScope.launch {
            suggestionRequestFlow
                .flatMapLatest { req ->
                    flow {
                        if (req == null || req.query.isBlank()) {
                            emit(null)
                            return@flow
                        }
                        delay(SUGGESTIONS_DEBOUNCE_MS)
                        val items = try {
                            musicSource.searchSuggestions(req.query)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            emptyList()
                        }
                        emit(req to items.distinct())
                    }
                }
                .collect { payload ->
                    if (payload == null) return@collect
                    val (req, suggestions) = payload
                    if (req.generation == suggestionGeneration.get() &&
                        _uiState.value.query.trim() == req.query.trim()
                    ) {
                        _uiState.update { it.copy(suggestions = suggestions) }
                    }
                }
        }

        // Cancellation-safe debounced search pipeline using flatMapLatest
        viewModelScope.launch {
            searchRequestFlow
                .flatMapLatest { req ->
                    flow<Unit> {
                        if (req == null || req.query.isBlank()) {
                            inFlightRequestKey = null
                            return@flow
                        }
                        executeSearchRequest(req)
                    }
                }
                .collect {}
        }
    }

    fun onQueryChange(newQuery: String) {
        val previousTrimmed = _uiState.value.query.trim()
        val trimmed = newQuery.trim()

        cancelPagination()

        if (trimmed.isEmpty()) {
            val gen = requestGeneration.incrementAndGet()
            suggestionGeneration.incrementAndGet()
            inFlightRequestKey = null
            lastCompletedRequestKey = null
            suggestionRequestFlow.value = null
            searchRequestFlow.value = null
            if (_uiState.value.query != newQuery || _uiState.value.response != null || _uiState.value.isLoading) {
                _uiState.update {
                    it.copy(
                        query = newQuery,
                        suggestions = emptyList(),
                        libraryTracks = emptyList(),
                        libraryPlaylists = emptyList(),
                        response = null,
                        isLoading = false,
                        isLoadingMore = false,
                        error = null
                    )
                }
            }
            return
        }

        // If only trailing/leading whitespace changed and query text is identical, just update raw query
        if (trimmed == previousTrimmed && _uiState.value.response != null && !_uiState.value.isLoading) {
            _uiState.update { it.copy(query = newQuery) }
            return
        }

        val gen = requestGeneration.incrementAndGet()
        val sugGen = suggestionGeneration.incrementAndGet()
        inFlightRequestKey = null
        lastCompletedRequestKey = null

        // Release previous response memory immediately when switching queries
        _uiState.update {
            it.copy(
                query = newQuery,
                response = if (trimmed != previousTrimmed) null else it.response,
                libraryTracks = if (trimmed != previousTrimmed) emptyList() else it.libraryTracks,
                libraryPlaylists = if (trimmed != previousTrimmed) emptyList() else it.libraryPlaylists,
                isLoading = true,
                isLoadingMore = false,
                error = null
            )
        }

        suggestionRequestFlow.value = SuggestionRequest(query = trimmed, generation = sugGen)
        searchRequestFlow.value = SearchRequest(
            query = trimmed,
            tab = _uiState.value.selectedTab,
            debounce = true,
            generation = gen
        )
    }

    fun onSuggestionClick(suggestion: String) {
        val clean = suggestion.trim()
        if (clean.isBlank()) return
        cancelSuggestions()
        _uiState.update { it.copy(query = suggestion, suggestions = emptyList()) }
        recordRecentSearch(clean)
        triggerImmediateSearch(clean, _uiState.value.selectedTab, force = false)
    }

    fun onSubmit(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        cancelSuggestions()
        _uiState.update { it.copy(query = query, suggestions = emptyList()) }
        recordRecentSearch(q)
        triggerImmediateSearch(q, _uiState.value.selectedTab, force = false)
    }

    fun onTabSelected(tab: String) {
        if (_uiState.value.selectedTab == tab) return
        cancelSuggestions()
        cancelPagination()
        _uiState.update { it.copy(selectedTab = tab, suggestions = emptyList(), isLoadingMore = false) }
        val q = _uiState.value.query.trim()
        if (q.isNotBlank()) {
            triggerImmediateSearch(q, tab, force = false)
        }
    }

    fun onTagClick(tag: String) {
        val clean = tag.trim()
        if (clean.isBlank()) return
        cancelSuggestions()
        _uiState.update { it.copy(query = tag, suggestions = emptyList()) }
        recordRecentSearch(clean)
        triggerImmediateSearch(clean, _uiState.value.selectedTab, force = false)
    }

    fun retry() {
        val q = _uiState.value.query.trim()
        if (q.isBlank()) return
        cancelSuggestions()
        triggerImmediateSearch(q, _uiState.value.selectedTab, force = true)
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

    private fun cancelSuggestions() {
        suggestionGeneration.incrementAndGet()
        suggestionRequestFlow.value = null
    }

    private fun cancelPagination() {
        loadMoreJob?.cancel()
        loadMoreJob = null
        activeContinuationToken = null
    }

    private fun triggerImmediateSearch(query: String, tab: String, force: Boolean) {
        val q = query.trim()
        if (q.isBlank()) return
        val key = "$q|$tab"

        // Deduplicate: if this exact query + tab already completed with a valid response (or is actively executing post-debounce),
        // avoid firing a redundant network request unless explicitly forced (e.g. retry()).
        if (!force) {
            val state = _uiState.value
            if (lastCompletedRequestKey == key && state.response != null && state.error == null && !state.isLoading) {
                return
            }
            if (inFlightRequestKey == key && state.isLoading) {
                return
            }
        }

        cancelPagination()
        val previousQuery = _uiState.value.query.trim()
        val gen = requestGeneration.incrementAndGet()
        inFlightRequestKey = null
        if (force || previousQuery != q) {
            lastCompletedRequestKey = null
        }

        _uiState.update {
            it.copy(
                response = if (previousQuery != q) null else it.response,
                libraryTracks = if (previousQuery != q) emptyList() else it.libraryTracks,
                libraryPlaylists = if (previousQuery != q) emptyList() else it.libraryPlaylists,
                isLoading = true,
                isLoadingMore = false,
                error = null
            )
        }

        searchRequestFlow.value = SearchRequest(
            query = q,
            tab = tab,
            debounce = false,
            generation = gen,
            force = force
        )
    }

    private suspend fun resolveFavoriteArtists(): Set<String> {
        cachedFavoriteArtists?.let { return it }
        return withContext(ioDispatcher) {
            cachedFavoriteArtists ?: run {
                val topStats = playStatDao.topArtistsOnce(25)
                val topLiked = likedDao.distinctArtistsOnce(100)
                (topStats + topLiked)
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
                    .also { cachedFavoriteArtists = it }
            }
        }
    }

    private suspend fun searchLocalLibrary(query: String, expectedGeneration: Long) {
        withContext(ioDispatcher) {
            val q = query.trim()
            if (q.isBlank()) {
                if (expectedGeneration == requestGeneration.get()) {
                    _uiState.update { it.copy(libraryTracks = emptyList(), libraryPlaylists = emptyList()) }
                }
                return@withContext
            }
            val liked = likedDao.searchLiked(q).map { it.toTrack() }
            val history = historyDao.searchHistory(q).map { it.toTrack() }
            val matchingTracks = (liked + history).distinctBy { it.videoId }.take(10)

            val playlists = playlistDao.searchPlaylists(q).distinctBy { it.id }.map { p ->
                Card(
                    id = p.id,
                    title = p.name,
                    subtitle = "Local Playlist",
                    thumbnails = emptyList(),
                    type = "playlist",
                    playlistId = p.id
                )
            }

            if (expectedGeneration == requestGeneration.get() && _uiState.value.query.trim() == q) {
                _uiState.update {
                    it.copy(
                        libraryTracks = matchingTracks,
                        libraryPlaylists = playlists
                    )
                }
            }
        }
    }

    private suspend fun executeSearchRequest(req: SearchRequest) {
        val q = req.query.trim()
        val requestedTab = req.tab
        val generation = req.generation
        val requestKey = "$q|$requestedTab"

        if (req.debounce) {
            delay(SEARCH_DEBOUNCE_MS)
        }

        if (!isRequestStillActive(generation, q, requestedTab)) return
        inFlightRequestKey = requestKey

        val type = when (requestedTab) {
            "Songs" -> SearchType.SONG
            "Videos" -> SearchType.VIDEO
            "Albums" -> SearchType.ALBUM
            "Artists" -> SearchType.ARTIST
            "Playlists" -> SearchType.PLAYLIST
            else -> null
        }

        try {
            coroutineScope {
                launch { searchLocalLibrary(q, generation) }

                val favoriteArtists = resolveFavoriteArtists()
                val result = withContext(ioDispatcher) {
                    musicSource.search(q, type)
                }

                if (!isRequestStillActive(generation, q, requestedTab)) return@coroutineScope

                when (result) {
                    is MusicResult.Success -> {
                        val rankedResponse = withContext(defaultDispatcher) {
                            result.value.copy(
                                songs = SearchRelevance.rankTracks(q, result.value.songs, favoriteArtists),
                                albums = SearchRelevance.rankCards(q, result.value.albums, favoriteArtists),
                                artists = SearchRelevance.rankCards(q, result.value.artists, favoriteArtists),
                                playlists = SearchRelevance.rankCards(q, result.value.playlists, favoriteArtists)
                            )
                        }
                        if (isRequestStillActive(generation, q, requestedTab)) {
                            lastCompletedRequestKey = requestKey
                            inFlightRequestKey = null
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
                        if (isRequestStillActive(generation, q, requestedTab)) {
                            inFlightRequestKey = null
                            _uiState.update {
                                it.copy(
                                    response = null,
                                    error = result.message,
                                    isLoading = false
                                )
                            }
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            if (inFlightRequestKey == requestKey) {
                inFlightRequestKey = null
            }
            throw ce
        } catch (error: Exception) {
            if (isRequestStillActive(generation, q, requestedTab)) {
                inFlightRequestKey = null
                _uiState.update {
                    it.copy(
                        response = null,
                        error = error.message ?: "Search failed. Please try again.",
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun isRequestStillActive(expectedGeneration: Long, expectedQuery: String, expectedTab: String): Boolean {
        val current = _uiState.value
        return expectedGeneration == requestGeneration.get() &&
            current.query.trim() == expectedQuery &&
            current.selectedTab == expectedTab
    }

    fun loadMore() {
        val snapshot = _uiState.value
        val currentResponse = snapshot.response ?: return
        val continuation = currentResponse.continuation?.takeIf { it.isNotBlank() } ?: return
        if (snapshot.isLoading || snapshot.isLoadingMore) return
        if (activeContinuationToken == continuation) return

        val expectedGeneration = requestGeneration.get()
        val expectedQuery = snapshot.query.trim()
        val expectedTab = snapshot.selectedTab
        if (expectedQuery.isBlank()) return

        activeContinuationToken = continuation
        _uiState.update { it.copy(isLoadingMore = true) }

        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            try {
                val favoriteArtists = resolveFavoriteArtists()
                val result = withContext(ioDispatcher) {
                    musicSource.searchContinuation(continuation)
                }

                // Prevent stale pagination responses from entering new search results
                if (!isRequestStillActive(expectedGeneration, expectedQuery, expectedTab)) {
                    return@launch
                }

                when (result) {
                    is MusicResult.Success -> {
                        val latestResponse = _uiState.value.response
                        if (latestResponse == null || latestResponse.continuation != continuation) {
                            return@launch
                        }
                        val next = result.value
                        val merged = withContext(defaultDispatcher) {
                            latestResponse.copy(
                                songs = SearchRelevance.mergePaginatedTracks(
                                    query = expectedQuery,
                                    existing = latestResponse.songs,
                                    incoming = next.songs,
                                    favoriteArtists = favoriteArtists
                                ),
                                albums = (latestResponse.albums + SearchRelevance.rankCards(expectedQuery, next.albums, favoriteArtists))
                                    .distinctBy { it.id },
                                artists = (latestResponse.artists + SearchRelevance.rankCards(expectedQuery, next.artists, favoriteArtists))
                                    .distinctBy { it.id },
                                playlists = (latestResponse.playlists + SearchRelevance.rankCards(expectedQuery, next.playlists, favoriteArtists))
                                    .distinctBy { it.id },
                                continuation = next.continuation?.takeIf { it != continuation }
                            )
                        }
                        if (isRequestStillActive(expectedGeneration, expectedQuery, expectedTab)) {
                            activeContinuationToken = null
                            _uiState.update { it.copy(response = merged, isLoadingMore = false) }
                        }
                    }
                    is MusicResult.Failure -> {
                        if (isRequestStillActive(expectedGeneration, expectedQuery, expectedTab)) {
                            activeContinuationToken = null
                            _uiState.update { it.copy(isLoadingMore = false) }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                if (isRequestStillActive(expectedGeneration, expectedQuery, expectedTab)) {
                    activeContinuationToken = null
                    _uiState.update { it.copy(isLoadingMore = false) }
                }
            }
        }
    }
}
