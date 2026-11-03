package com.geekify.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.data.model.*
import com.geekify.android.data.source.MusicResult
import com.geekify.android.data.source.MusicSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val selectedTab: String = "All",
    val response: SearchResponse? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val musicSource: MusicSource
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var requestGeneration = 0L

    val genreTags = listOf(
        "Malayalam hits", "Tamil hits", "Hindi hits", "Telugu hits",
        "Punjabi hits", "Hip-hop", "Pop hits", "Lo-fi & chill",
        "Workout", "Rock classics", "Romantic", "K-pop"
    )

    fun onQueryChange(newQuery: String) {
        requestGeneration++
        searchJob?.cancel()
        _uiState.update { it.copy(query = newQuery) }
        if (newQuery.isBlank()) {
            _uiState.update { it.copy(response = null, isLoading = false, error = null) }
            return
        }
        startSearch(newQuery, debounce = true)
    }

    fun onTabSelected(tab: String) {
        if (_uiState.value.selectedTab == tab) return
        _uiState.update { it.copy(selectedTab = tab) }
        if (_uiState.value.query.isNotBlank()) {
            startSearch(_uiState.value.query)
        }
    }

    fun onTagClick(tag: String) {
        _uiState.update { it.copy(query = tag) }
        startSearch(tag)
    }

    private fun startSearch(query: String, debounce: Boolean = false) {
        val q = query.trim()
        if (q.isBlank()) return

        val generation = ++requestGeneration
        val requestedTab = _uiState.value.selectedTab
        searchJob?.cancel()
        _uiState.update { it.copy(response = null, isLoading = true, error = null) }
        searchJob = viewModelScope.launch {
            if (debounce) delay(400)
            // A later keystroke or tab change may have replaced this request.
            if (generation != requestGeneration || _uiState.value.query.trim() != q || _uiState.value.selectedTab != requestedTab) return@launch

            val type = when (requestedTab) {
                "Songs" -> SearchType.SONG
                "Albums" -> SearchType.ALBUM
                "Artists" -> SearchType.ARTIST
                "Playlists" -> SearchType.PLAYLIST
                else -> null
            }

            try {
                when (val result = musicSource.search(q, type)) {
                    is MusicResult.Success -> {
                        if (generation == requestGeneration && _uiState.value.query.trim() == q && _uiState.value.selectedTab == requestedTab) {
                            val response = result.value.copy(
                                songs = SearchRelevance.rankTracks(q, result.value.songs),
                                albums = SearchRelevance.rankCards(q, result.value.albums),
                                artists = SearchRelevance.rankCards(q, result.value.artists),
                                playlists = SearchRelevance.rankCards(q, result.value.playlists)
                            )
                            _uiState.update { it.copy(response = response, isLoading = false, error = null) }
                        }
                    }
                    is MusicResult.Failure -> {
                        if (generation == requestGeneration) {
                            _uiState.update { it.copy(error = result.message, isLoading = false) }
                        }
                    }
                }
            } catch (_: CancellationException) {
                // A newer query superseded this one; it is not an error to show the user.
            } catch (error: Exception) {
                if (generation == requestGeneration) {
                    _uiState.update { it.copy(error = error.message ?: "Search failed. Please try again.", isLoading = false) }
                }
            }
        }
    }
}
