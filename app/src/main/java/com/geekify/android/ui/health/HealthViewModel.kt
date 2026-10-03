package com.geekify.android.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.data.source.MusicResult
import com.geekify.android.data.source.MusicSource
import com.geekify.android.player.StreamResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HealthUiState(
    val isRunning: Boolean = false,
    val innertubeSearchOk: Boolean? = null,
    val streamResolveOk: Boolean? = null,
    val resolvedUrl: String? = null,
    val mimeType: String? = null,
    val error: String? = null
)

@HiltViewModel
class HealthViewModel @Inject constructor(
    private val musicSource: MusicSource,
    private val streamResolver: StreamResolver
) : ViewModel() {

    private val _uiState = MutableStateFlow(HealthUiState())
    val uiState: StateFlow<HealthUiState> = _uiState.asStateFlow()

    fun runDiagnostics() {
        viewModelScope.launch {
            _uiState.update { HealthUiState(isRunning = true) }

            // 1. Test Search
            var searchOk = false
            var testVideoId: String? = null
            when (val res = musicSource.search("A.R. Rahman")) {
                is MusicResult.Success -> {
                    searchOk = res.value.songs.isNotEmpty()
                    testVideoId = res.value.songs.firstOrNull()?.videoId ?: "dQw4w9WgXcQ"
                }
                is MusicResult.Failure -> {
                    _uiState.update { it.copy(innertubeSearchOk = false, isRunning = false, error = res.message) }
                    return@launch
                }
            }

            _uiState.update { it.copy(innertubeSearchOk = searchOk) }

            // 2. Test Stream Resolution
            try {
                val resolved = streamResolver.resolve(testVideoId ?: "dQw4w9WgXcQ", forceRefresh = true)
                _uiState.update {
                    it.copy(
                        streamResolveOk = true,
                        resolvedUrl = resolved.url.take(80) + "…",
                        mimeType = resolved.mimeType,
                        isRunning = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        streamResolveOk = false,
                        isRunning = false,
                        error = e.message ?: "Failed to resolve stream"
                    )
                }
            }
        }
    }
}
