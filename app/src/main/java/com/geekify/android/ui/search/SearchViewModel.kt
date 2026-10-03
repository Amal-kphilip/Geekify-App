package com.geekify.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.data.model.*
import com.geekify.android.data.source.MusicResult
import com.geekify.android.data.source.MusicSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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

    val genreTags = listOf(
        "Malayalam hits", "Tamil hits", "Hindi hits", "Telugu hits",
        "Punjabi hits", "Hip-hop", "Pop hits", "Lo-fi & chill",
        "Workout", "Rock classics", "Romantic", "K-pop"
    )

    fun onQueryChange(newQuery: String) {
        _uiState.update { it.copy(query = newQuery) }
        searchJob?.cancel()
        if (newQuery.isBlank()) {
            _uiState.update { it.copy(response = null, isLoading = false, error = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(400) // Debounce
            executeSearch()
        }
    }

    fun onTabSelected(tab: String) {
        _uiState.update { it.copy(selectedTab = tab) }
        if (_uiState.value.query.isNotBlank()) {
            executeSearch()
        }
    }

    fun onTagClick(tag: String) {
        _uiState.update { it.copy(query = tag) }
        executeSearch()
    }

    private fun executeSearch() {
        val q = _uiState.value.query.trim()
        if (q.isBlank()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val type = when (_uiState.value.selectedTab) {
                "Songs" -> SearchType.SONG
                "Albums" -> SearchType.ALBUM
                "Artists" -> SearchType.ARTIST
                "Playlists" -> SearchType.PLAYLIST
                else -> null
            }

            when (val result = musicSource.search(q, type)) {
                is MusicResult.Success -> {
                    _uiState.update { it.copy(response = result.value, isLoading = false) }
                }
                is MusicResult.Failure -> {
                    _uiState.update { it.copy(error = result.message, isLoading = false) }
                }
            }
        }
    }
}
