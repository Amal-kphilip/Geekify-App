package com.geekify.android.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.data.local.LibraryRepository
import com.geekify.android.data.local.LocalPlaylist
import com.geekify.android.data.local.SavedCollection
import com.geekify.android.data.model.Track
import com.geekify.android.data.sync.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val sync: SyncRepository
) : ViewModel() {

    val liked: StateFlow<List<Track>> = library.liked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<LocalPlaylist>> = library.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedCollections: StateFlow<List<SavedCollection>> = library.savedCollections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleSavedCollection(collection: SavedCollection) {
        viewModelScope.launch {
            library.toggleSavedCollection(collection)
            sync.schedulePush()
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            library.createPlaylist(name)
            sync.schedulePush()
        }
    }

    fun toggleLike(track: Track) {
        viewModelScope.launch {
            library.toggleLike(track)
            sync.pushNow()
        }
    }

    fun addToPlaylist(playlistId: String, track: Track) {
        viewModelScope.launch {
            library.addToPlaylist(playlistId, track)
            sync.schedulePush()
        }
    }

    fun createPlaylistWithTrack(name: String, track: Track) {
        viewModelScope.launch {
            val playlist = library.createPlaylist(name)
            library.addToPlaylist(playlist.id, track)
            sync.schedulePush()
        }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch {
            library.deletePlaylist(id)
            sync.schedulePush()
        }
    }

    fun playlistTracks(id: String): Flow<List<Track>> = library.playlistTracks(id)

    fun removeFromPlaylist(playlistId: String, videoId: String) {
        viewModelScope.launch {
            library.removeFromPlaylist(playlistId, videoId)
            sync.schedulePush()
        }
    }
}
