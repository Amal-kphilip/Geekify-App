package com.geekify.android.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.data.model.*
import com.geekify.android.data.source.MusicResult
import com.geekify.android.data.source.MusicSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistUiState(
    val artist: ArtistPage? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

data class CollectionUiState(
    val collection: CollectionPage? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class DetailsViewModel @Inject constructor(
    private val musicSource: MusicSource,
    private val streamResolver: com.geekify.android.player.StreamResolver
) : ViewModel() {

    private val _artistState = MutableStateFlow(ArtistUiState())
    val artistState: StateFlow<ArtistUiState> = _artistState.asStateFlow()

    private val _collectionState = MutableStateFlow(CollectionUiState())
    val collectionState: StateFlow<CollectionUiState> = _collectionState.asStateFlow()

    fun loadArtist(id: String) {
        viewModelScope.launch {
            _artistState.update { it.copy(isLoading = true, error = null) }
            when (val res = musicSource.artist(id)) {
                is MusicResult.Success -> _artistState.update { it.copy(artist = res.value, isLoading = false) }
                is MusicResult.Failure -> _artistState.update { it.copy(error = res.message, isLoading = false) }
            }
        }
    }

    fun loadCollection(id: String, kind: CollectionKind) {
        viewModelScope.launch {
            _collectionState.update { it.copy(isLoading = true, error = null) }
            when (val res = musicSource.collection(id, kind)) {
                is MusicResult.Success -> {
                    _collectionState.update { it.copy(collection = res.value, isLoading = false) }
                    // The Play button starts the first song: have its stream URL ready before the tap.
                    streamResolver.prefetch(res.value.tracks.take(2).map { it.videoId })
                }
                is MusicResult.Failure -> _collectionState.update { it.copy(error = res.message, isLoading = false) }
            }
        }
    }
}
