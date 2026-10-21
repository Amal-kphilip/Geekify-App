package com.geekify.android.player

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.geekify.android.data.model.Track
import com.geekify.android.settings.AppSettingsRepository
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
    val error: String? = null,
    /** Local, transparent explanations for tracks added by radio/autoplay, keyed by video ID. */
    val recommendationReasons: Map<String, String> = emptyMap(),
    /** Direction hint for the most recent track transition: +1 next/auto-advance, -1 previous. */
    val skipDirection: Int = 1,
    /** While true, playback waits for the Now Playing tonearm animation to finish. */
    val tonearmTransitionPending: Boolean = false
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

    /** Enabled only while the expanded Now Playing screen is visible. */
    @Volatile private var tonearmSyncEnabled = false

    fun setTonearmSyncEnabled(enabled: Boolean) {
        tonearmSyncEnabled = enabled
        if (!enabled) {
            val current = _state.value
            if (current.tonearmTransitionPending) {
                // Do not leave playback blocked if the screen closes during an animation.
                _state.value = current.copy(tonearmTransitionPending = false)
            }
        }
    }

    /** Called by the tonearm animation after the stylus has settled onto this exact track. */
    fun completeTonearmTransition(trackVideoId: String) {
        val current = _state.value
        if (current.current?.videoId == trackVideoId && current.tonearmTransitionPending) {
            _state.value = current.copy(tonearmTransitionPending = false)
        }
    }

    private val _seekEvents = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val seekEvents: SharedFlow<Long> = _seekEvents.asSharedFlow()

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val KEY_QUEUE = stringPreferencesKey("player_queue")
        private val KEY_INDEX = intPreferencesKey("player_index")
        private val KEY_SHUFFLE = stringPreferencesKey("player_shuffle")
        private val KEY_REPEAT = stringPreferencesKey("player_repeat")
        private val KEY_VOLUME = floatPreferencesKey("player_volume")
        private val KEY_PROGRESS = longPreferencesKey("player_progress_ms")
        private val KEY_RECOMMENDATION_REASONS = stringPreferencesKey("player_recommendation_reasons")
    }

    init {
        load()
    }

    private fun load() {
        scope.launch {
            val prefs = dataStore.data.first()
            val persistentQueue = prefs[AppSettingsRepository.KEY_PERSISTENT_QUEUE] ?: true
            val rememberShuffleRepeat = prefs[AppSettingsRepository.KEY_REMEMBER_SHUFFLE_REPEAT] ?: true
            val vol = prefs[KEY_VOLUME] ?: 0.85f
            val shuf = if (rememberShuffleRepeat) prefs[KEY_SHUFFLE] == "true" else false
            val rep = if (rememberShuffleRepeat) prefs[KEY_REPEAT]?.let { runCatching { RepeatMode.valueOf(it) }.getOrNull() } ?: RepeatMode.OFF else RepeatMode.OFF
            val queueJson = prefs[KEY_QUEUE]
            if (!persistentQueue || queueJson == null) {
                _state.value = QueueState(shuffle = shuf, repeat = rep, volume = vol)
                return@launch
            }
            try {
                val queue = json.decodeFromString<List<Track>>(queueJson)
                val index = prefs[KEY_INDEX] ?: -1
                val progress = (prefs[KEY_PROGRESS] ?: 0L).coerceAtLeast(0L)
                val reasons = prefs[KEY_RECOMMENDATION_REASONS]?.let {
                    runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull()
                }.orEmpty()
                // A restored queue is always PAUSED (isPlaying = false): reopening the app never starts
                // music by itself. The track and the position are kept so Play continues where it stopped.
                _state.value = QueueState(
                    queue = queue,
                    index = index.coerceIn(-1, queue.size - 1),
                    isPlaying = false,
                    isBuffering = false,
                    progressMs = progress,
                    durationMs = 0L,
                    shuffle = shuf,
                    repeat = rep,
                    volume = vol,
                    recommendationReasons = reasons.filterKeys { id -> queue.any { it.videoId == id } }
                )
            } catch (_: Exception) {}
        }
    }

    fun play(track: Track, queue: List<Track> = emptyList()) {
        val old = _state.value
        val q = queue.ifEmpty { listOf(track) }
        val idx = maxOf(0, q.indexOfFirst { it.videoId == track.videoId })
        // Preserve explanations when a user selects a song from the existing queue; a new library/search
        // queue starts clean so old recommendations cannot leak into unrelated tracks.
        val sameQueue = old.queue.map { it.videoId } == q.map { it.videoId }
        update(old.copy(
            queue = q,
            index = idx,
            isPlaying = true,
            isBuffering = true,
            progressMs = 0L,
            error = null,
            recommendationReasons = if (sameQueue) old.recommendationReasons else emptyMap(),
            skipDirection = 1
        ))
    }

    /** Starts [tracks] from a random song with shuffle switched on, like Spotify's shuffle-play. */
    fun playShuffled(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val idx = tracks.indices.random()
        update(_state.value.copy(
            queue = tracks,
            index = idx,
            isPlaying = true,
            isBuffering = true,
            progressMs = 0L,
            shuffle = true,
            error = null,
            recommendationReasons = emptyMap(),
            skipDirection = 1
        ))
    }

    @Volatile private var pendingStartMs = 0L

    /** Starts [track] from [startMs] (used to join a listening room mid-song). */
    fun playAt(track: Track, startMs: Long) {
        pendingStartMs = startMs.coerceAtLeast(0L)
        play(track, listOf(track))
    }

    /** Read once by the player when it loads a track: where that track should start (0 = the beginning). */
    fun takePendingStart(): Long {
        val v = pendingStartMs
        pendingStartMs = 0L
        return v
    }

    fun togglePlay() {
        if (_state.value.current == null) return
        update(_state.value.copy(isPlaying = !_state.value.isPlaying))
    }

    fun setPlaying(playing: Boolean) {
        if (_state.value.isPlaying != playing) {
            _state.value = _state.value.copy(isPlaying = playing)
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
        update(s.copy(
            index = next,
            isPlaying = true,
            isBuffering = true,
            progressMs = 0L,
            error = null,
            skipDirection = 1,
            tonearmTransitionPending = tonearmSyncEnabled
        ))
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
        update(s.copy(
            index = s.index - 1,
            isPlaying = true,
            isBuffering = true,
            progressMs = 0L,
            error = null,
            skipDirection = -1,
            tonearmTransitionPending = tonearmSyncEnabled
        ))
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
        val remainingIds = q.map { it.videoId }.toSet()
        update(s.copy(
            queue = q,
            index = newIdx,
            recommendationReasons = s.recommendationReasons.filterKeys { it in remainingIds },
            skipDirection = if (index == s.index && newIdx >= 0) 1 else s.skipDirection
        ))
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
        val keptIds = kept.map { it.videoId }.toSet()
        update(s.copy(
            queue = kept,
            index = if (kept.isEmpty()) -1 else 0,
            recommendationReasons = s.recommendationReasons.filterKeys { it in keptIds }
        ))
    }
    fun appendRelated(tracks: List<Track>, reasonsByVideoId: Map<String, String> = emptyMap()) {
        val s = _state.value
        val existing = s.queue.map { it.videoId }.toSet()
        val fresh = tracks.filter { it.videoId !in existing }.distinctBy { it.videoId }
        if (fresh.isNotEmpty()) {
            val nextReasons = s.recommendationReasons + fresh.mapNotNull { track ->
                reasonsByVideoId[track.videoId]?.takeIf { it.isNotBlank() }?.let { track.videoId to it }
            }
            update(s.copy(queue = s.queue + fresh, recommendationReasons = nextReasons))
        }
    }

    /** Removes upcoming auto-recommended tracks when Endless Autoplay is switched off. */
    fun removeAutoplayTracks() {
        val s = _state.value
        if (s.recommendationReasons.isEmpty() || s.queue.isEmpty()) return
        val autoIds = s.recommendationReasons.keys
        val filtered = s.queue.filterIndexed { idx, track ->
            idx <= s.index || track.videoId !in autoIds
        }
        if (filtered.size != s.queue.size) {
            val remainingIds = filtered.map { it.videoId }.toSet()
            update(
                s.copy(
                    queue = filtered,
                    recommendationReasons = s.recommendationReasons.filterKeys { it in remainingIds }
                )
            )
        }
    }

    /** Removes upcoming explicit tracks when Hide Explicit Content is enabled. */
    fun removeExplicitUpcomingTracks() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        val filtered = s.queue.filterIndexed { idx, track ->
            idx <= s.index || !track.explicit
        }
        if (filtered.size != s.queue.size) {
            val remainingIds = filtered.map { it.videoId }.toSet()
            update(
                s.copy(
                    queue = filtered,
                    recommendationReasons = s.recommendationReasons.filterKeys { it in remainingIds }
                )
            )
        }
    }

    private fun update(newState: QueueState) {
        _state.value = newState
        persist(newState)
    }

    /** Saves just the playback position (called every few seconds while playing and on pause). */
    fun persistProgress() {
        val progress = _state.value.progressMs
        scope.launch {
            dataStore.edit { prefs ->
                val persistentQueue = prefs[AppSettingsRepository.KEY_PERSISTENT_QUEUE] ?: true
                if (persistentQueue) {
                    prefs[KEY_PROGRESS] = progress
                }
            }
        }
    }

    private fun persist(s: QueueState) {
        scope.launch {
            dataStore.edit { prefs ->
                val persistentQueue = prefs[AppSettingsRepository.KEY_PERSISTENT_QUEUE] ?: true
                val rememberShuffleRepeat = prefs[AppSettingsRepository.KEY_REMEMBER_SHUFFLE_REPEAT] ?: true
                prefs[KEY_VOLUME] = s.volume
                if (persistentQueue) {
                    prefs[KEY_PROGRESS] = s.progressMs
                    prefs[KEY_QUEUE] = json.encodeToString(s.queue)
                    prefs[KEY_INDEX] = s.index
                    prefs[KEY_RECOMMENDATION_REASONS] = json.encodeToString(s.recommendationReasons)
                } else {
                    prefs.remove(KEY_PROGRESS)
                    prefs.remove(KEY_QUEUE)
                    prefs.remove(KEY_INDEX)
                    prefs.remove(KEY_RECOMMENDATION_REASONS)
                }
                if (rememberShuffleRepeat) {
                    prefs[KEY_SHUFFLE] = s.shuffle.toString()
                    prefs[KEY_REPEAT] = s.repeat.name
                } else {
                    prefs.remove(KEY_SHUFFLE)
                    prefs.remove(KEY_REPEAT)
                }
            }
        }
    }
}
