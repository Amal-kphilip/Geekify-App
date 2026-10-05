package com.geekify.android.listentogether

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.core.UiState
import com.geekify.android.player.QueueManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.random.Random

@HiltViewModel
class ListenTogetherViewModel @Inject constructor(
    private val manager: ListenTogetherManager,
    queue: QueueManager
) : ViewModel() {

    val state: StateFlow<UiState<RoomUiState>> = manager.state
        .map<RoomUiState, UiState<RoomUiState>> { UiState.Success(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    /** Title and artist of what this device is playing, shown inside the room. */
    val nowPlaying: StateFlow<String?> = queue.state
        .map { s -> s.current?.let { "${it.title} · ${it.artist}" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun join(serverUrl: String, room: String) = manager.join(serverUrl, room)
    fun leave() = manager.leave()
    fun clearError() = manager.clearError()

    /** A fresh code without look-alike characters (no 0/O or 1/I), easy to read out loud. */
    fun newRoomCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return String(CharArray(6) { alphabet[Random.nextInt(alphabet.length)] })
    }
}
