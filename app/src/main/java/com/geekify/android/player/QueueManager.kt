package com.geekify.android.player

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.geekify.android.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

enum class RepeatMode { OFF, ALL, ONE }

data class QueueState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val progressMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val volume: Float = 0.85f,
    /** Short, user-readable reason the current track could not be played (null when fine). */
    val error: String? = null
) {
    val current: Track? get() = queue.getOrNull(index)
    val hasNext: Boolean get() = when {
        queue.isEmpty() -> false
        repeat == RepeatMode.ALL -> true
        shuffle -> queue.size > 1
        else -> index < queue.size - 1
    }
    val hasPrev: Boolean get() = queue.isNotEmpty()
}

@Singleton
class QueueManager @Inject constructor(private val dataStore: DataStore<Preferences>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private val _seekEvents = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val seekEvents: SharedFlow<Long> = _seekEvents.asSharedFlow()

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val KEY_QUEUE = stringPreferencesKey("player_queue")
        private val KEY_INDEX = intPreferencesKey("player_index")
        private val KEY_SHUFFLE = stringPreferencesKey("player_shuffle")
        private val KEY_REPEAT = stringPreferencesKey("player_repeat")
        private val KEY_VOLUME = floatPreferencesKey("player_volume")
        private val KEY_POSITION = longPreferencesKey("player_position")
    }

    init {
        load()
    }

    private fun load() {
        scope.launch {
            val prefs = dataStore.data.first()
            val queueJson = prefs[KEY_QUEUE] ?: return@launch
            try {
                val queue = json.decodeFromString<List<Track>>(queueJson)
                val index = prefs[KEY_INDEX] ?: -1
                val shuffle = prefs[KEY_SHUFFLE] == "true"
                val repeat = prefs[KEY_REPEAT]?.let { runCatching { RepeatMode.valueOf(it) }.getOrNull() } ?: RepeatMode.OFF
                val volume = prefs[KEY_VOLUME] ?: 0.85f
                val position = prefs[KEY_POSITION] ?: 0L
                _state.value = QueueState(queue, index.coerceIn(-1, queue.size - 1), false, false, position, 0L, shuffle, repeat, volume)
            } catch (_: Exception) {}
        }
    }

    fun play(track: Track, queue: List<Track> = emptyList()) {
        val q = queue.ifEmpty { listOf(track) }
        val idx = maxOf(0, q.indexOfFirst { it.videoId == track.videoId })
        update(_state.value.copy(queue = q, index = idx, isPlaying = true, isBuffering = true, progressMs = 0L, error = null))
    }

    /** Starts [tracks] from a random song with shuffle switched on, like Spotify's shuffle-play. */
    fun playShuffled(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val idx = tracks.indices.random()
        update(_state.value.copy(queue = tracks, index = idx, isPlaying = true, isBuffering = true, progressMs = 0L, shuffle = true, error = null))
    }

    fun togglePlay() {
        if (_state.value.current == null) return
        update(_state.value.copy(isPlaying = !_state.value.isPlaying))
    }

    fun setPlaying(playing: Boolean) {
        if (_state.value.isPlaying != playing) {
            _state.value = _state.value.copy(isPlaying = playing)
            if (!playing) persist(_state.value)   // remember where we paused
        }
    }

    fun setError(message: String?) {
        _state.value = _state.value.copy(error = message, isBuffering = false, isPlaying = if (message != null) false else _state.value.isPlaying)
    }

    fun setBuffering(buffering: Boolean) {
        if (_state.value.isBuffering != buffering) {
            _state.value = _state.value.copy(isBuffering = buffering)
        }
    }

    fun setProgress(posMs: Long, durMs: Long) {
        _state.value = _state.value.copy(progressMs = posMs, durationMs = durMs)
    }

    fun seekTo(positionMs: Long) {
        _state.value = _state.value.copy(progressMs = positionMs)
        _seekEvents.tryEmit(positionMs)
    }

    fun next() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        if (s.repeat == RepeatMode.ONE) {
            seekTo(0)
            update(s.copy(isPlaying = true, progressMs = 0L))
            return
        }
        val next = when {
            s.shuffle && s.queue.size > 1 -> {
                var n: Int
                do { n = (s.queue.indices).random() } while (n == s.index)
                n
            }
            s.index + 1 < s.queue.size -> s.index + 1
            s.repeat == RepeatMode.ALL -> 0
            else -> { update(s.copy(isPlaying = false)); return }
        }
        if (next == s.index) {
            // Repeat-all with a single song: same handling as repeat-one.
            seekTo(0)
            update(s.copy(isPlaying = true, progressMs = 0L))
            return
        }
        update(s.copy(index = next, isPlaying = true, isBuffering = true, progressMs = 0L, error = null))
    }

    fun previous(progressSeconds: Long): Boolean {
        val s = _state.value
        if (s.queue.isEmpty()) return false
        if (progressSeconds > 3 && s.current != null) {
            seekTo(0)
            return false
        }
        if (s.index <= 0) {
            // Already on the first song: restart it in place (a same-index "load" would never run
            // and would leave the buffering spinner stuck).
            seekTo(0)
            update(s.copy(isPlaying = true, progressMs = 0L, error = null))
            return false
        }
        update(s.copy(index = s.index - 1, isPlaying = true, isBuffering = true, progressMs = 0L, error = null))
        return true
    }

    fun toggleShuffle() = update(_state.value.copy(shuffle = !_state.value.shuffle))
    fun cycleRepeat() {
        val order = RepeatMode.values()
        val next = order[(order.indexOf(_state.value.repeat) + 1) % order.size]
        update(_state.value.copy(repeat = next))
    }
    fun setVolume(v: Float) = update(_state.value.copy(volume = v.coerceIn(0f, 1f)))
    fun addToQueue(track: Track) = update(_state.value.copy(queue = _state.value.queue + track))
    fun removeFromQueue(index: Int) {
        val s = _state.value
        if (index !in s.queue.indices) return
        val q = s.queue.toMutableList().also { it.removeAt(index) }
        val newIdx = when {
            index < s.index -> s.index - 1
            index == s.index -> if (q.isEmpty()) -1 else s.index.coerceAtMost(q.size - 1)
            else -> s.index
        }
        update(s.copy(queue = q, index = newIdx))
    }
    fun reorder(from: Int, to: Int) {
        val s = _state.value
        val q = s.queue.toMutableList()
        if (from < 0 || to < 0 || from >= q.size || to >= q.size) return
        val item = q.removeAt(from); q.add(to, item)
        val current = s.current
        val idx = if (current != null) q.indexOfFirst { it.videoId == current.videoId }.coerceAtLeast(0) else s.index
        update(s.copy(queue = q, index = idx))
    }
    fun clearQueue() {
        val s = _state.value
        val kept = if (s.current != null) listOf(s.current!!) else emptyList()
        update(s.copy(queue = kept, index = if (kept.isEmpty()) -1 else 0))
    }
    fun appendRelated(tracks: List<Track>) {
        val s = _state.value
        val existing = s.queue.map { it.videoId }.toSet()
        val fresh = tracks.filter { it.videoId !in existing }
        if (fresh.isNotEmpty()) update(s.copy(queue = s.queue + fresh))
    }

    private fun update(newState: QueueState) {
        _state.value = newState
        persist(newState)
    }

    private fun persist(s: QueueState) {
        scope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_QUEUE] = json.encodeToString(s.queue)
                prefs[KEY_INDEX] = s.index
                prefs[KEY_SHUFFLE] = s.shuffle.toString()
                prefs[KEY_REPEAT] = s.repeat.name
                prefs[KEY_VOLUME] = s.volume
                prefs[KEY_POSITION] = s.progressMs
            }
        }
    }
}
