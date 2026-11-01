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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

enum class RepeatMode { OFF, ALL, ONE }

/**
 * High-frequency playback position, exposed separately from [QueueState] so periodic progress
 * ticks never re-emit the queue state (and never re-run queue observers or recompose
 * queue-dependent UI).
 */
data class PlaybackPosition(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

data class QueueState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
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
class QueueManager(
    private val dataStore: DataStore<Preferences>,
    dispatcher: CoroutineDispatcher,
    private val debounceMs: Long
) {
    @Inject
    constructor(dataStore: DataStore<Preferences>) : this(
        dataStore = dataStore,
        dispatcher = Dispatchers.IO,
        debounceMs = DEFAULT_PERSIST_DEBOUNCE_MS
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val stateLock = Any()
    private val persistMutex = Mutex()
    private val loadCompleted = CompletableDeferred<Unit>()

    @Volatile private var isLoaded = false
    private val preLoadMutations = mutableListOf<() -> Unit>()
    private var shuffleOrder: List<Int>? = null
    private var shufflePointer: Int = 0

    @Volatile private var dirtyQueue = false
    @Volatile private var dirtyModes = false
    @Volatile private var dirtyVolume = false
    @Volatile private var dirtyProgress = false
    @Volatile private var persistJob: Job? = null

    /** Counts how many full-queue JSON serializations occurred (inspected by unit tests). */
    @Volatile var queueSerializationCount: Int = 0
        private set

    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private val _position = MutableStateFlow(PlaybackPosition())
    /** Current playback position/duration. Updated by the player only while actively playing, and on seeks/track changes. */
    val position: StateFlow<PlaybackPosition> = _position.asStateFlow()

    /** Resets the position to [positionMs] for a track change or seek; keeps the last known duration until the player reports a new one. */
    private fun resetPosition(positionMs: Long) {
        val cur = _position.value
        if (cur.positionMs != positionMs) _position.value = cur.copy(positionMs = positionMs)
    }

    /** Enabled only while the expanded Now Playing screen is visible. */
    @Volatile private var tonearmSyncEnabled = false

    fun setTonearmSyncEnabled(enabled: Boolean) {
        tonearmSyncEnabled = enabled
        if (!enabled) {
            synchronized(stateLock) {
                val current = _state.value
                if (current.tonearmTransitionPending) {
                    // Do not leave playback blocked if the screen closes during an animation.
                    _state.value = current.copy(tonearmTransitionPending = false)
                }
            }
        }
    }

    /** Called by the tonearm animation after the stylus has settled onto this exact track. */
    fun completeTonearmTransition(trackVideoId: String) {
        synchronized(stateLock) {
            val current = _state.value
            if (current.current?.videoId == trackVideoId && current.tonearmTransitionPending) {
                _state.value = current.copy(tonearmTransitionPending = false)
            }
        }
    }

    private val _seekEvents = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val seekEvents: SharedFlow<Long> = _seekEvents.asSharedFlow()

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val DEFAULT_PERSIST_DEBOUNCE_MS = 150L
        val KEY_QUEUE = stringPreferencesKey("player_queue")
        val KEY_INDEX = intPreferencesKey("player_index")
        val KEY_SHUFFLE = stringPreferencesKey("player_shuffle")
        val KEY_REPEAT = stringPreferencesKey("player_repeat")
        val KEY_VOLUME = floatPreferencesKey("player_volume")
        val KEY_PROGRESS = longPreferencesKey("player_progress_ms")
        val KEY_RECOMMENDATION_REASONS = stringPreferencesKey("player_recommendation_reasons")
    }

    init {
        load()
    }

    suspend fun awaitLoaded() {
        loadCompleted.await()
    }

    private fun load() {
        scope.launch {
            try {
                val prefs = dataStore.data.first()
                val persistentQueue = prefs[AppSettingsRepository.KEY_PERSISTENT_QUEUE] ?: true
                val rememberShuffleRepeat = prefs[AppSettingsRepository.KEY_REMEMBER_SHUFFLE_REPEAT] ?: true
                val vol = prefs[KEY_VOLUME] ?: 0.85f
                val shuf = if (rememberShuffleRepeat) prefs[KEY_SHUFFLE] == "true" else false
                val rep = if (rememberShuffleRepeat) {
                    prefs[KEY_REPEAT]?.let { runCatching { RepeatMode.valueOf(it) }.getOrNull() } ?: RepeatMode.OFF
                } else {
                    RepeatMode.OFF
                }
                val queueJson = prefs[KEY_QUEUE]
                var restoredProgress = 0L

                val restoredState = if (!persistentQueue || queueJson == null) {
                    QueueState(shuffle = shuf, repeat = rep, volume = vol)
                } else {
                    try {
                        val queue = json.decodeFromString<List<Track>>(queueJson)
                        val index = prefs[KEY_INDEX] ?: -1
                        restoredProgress = (prefs[KEY_PROGRESS] ?: 0L).coerceAtLeast(0L)
                        val reasons = prefs[KEY_RECOMMENDATION_REASONS]?.let {
                            runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull()
                        }.orEmpty()
                        // A restored queue is always PAUSED (isPlaying = false): reopening the app never starts
                        // music by itself. The track and the position are kept so Play continues where it stopped.
                        QueueState(
                            queue = queue,
                            index = index.coerceIn(-1, queue.size - 1),
                            isPlaying = false,
                            isBuffering = false,
                            shuffle = shuf,
                            repeat = rep,
                            volume = vol,
                            recommendationReasons = reasons.filterKeys { id -> queue.any { it.videoId == id } }
                        )
                    } catch (_: Exception) {
                        restoredProgress = 0L
                        QueueState(shuffle = shuf, repeat = rep, volume = vol)
                    }
                }

                synchronized(stateLock) {
                    _position.value = PlaybackPosition(positionMs = restoredProgress, durationMs = 0L)
                    _state.value = restoredState
                    isLoaded = true
                    val queued = preLoadMutations.toList()
                    preLoadMutations.clear()
                    queued.forEach { it() }
                }
            } finally {
                loadCompleted.complete(Unit)
            }
        }
    }

    private fun runWhenLoaded(mutation: () -> Unit) {
        synchronized(stateLock) {
            if (!isLoaded) {
                preLoadMutations += mutation
                return
            }
            mutation()
        }
    }

    fun play(track: Track, queue: List<Track> = emptyList()) = runWhenLoaded {
        shuffleOrder = null
        shufflePointer = 0
        val old = _state.value
        val q = queue.ifEmpty { listOf(track) }
        val idx = maxOf(0, q.indexOfFirst { it.videoId == track.videoId })
        if (old.shuffle && q.size > 1) {
            val indices = q.indices.toMutableList()
            indices.remove(idx)
            indices.shuffle()
            shuffleOrder = listOf(idx) + indices
            shufflePointer = 0
        }
        // Preserve explanations when a user selects a song from the existing queue; a new library/search
        // queue starts clean so old recommendations cannot leak into unrelated tracks.
        val sameQueue = old.queue.map { it.videoId } == q.map { it.videoId }
        resetPosition(0L)
        update(
            old.copy(
                queue = q,
                index = idx,
                isPlaying = true,
                isBuffering = true,
                error = null,
                recommendationReasons = if (sameQueue) old.recommendationReasons else emptyMap(),
                skipDirection = 1
            ),
            queueChanged = true,
            progressChanged = true
        )
    }

    /** Starts [tracks] from a random song with shuffle switched on and true randomized permutation. */
    fun playShuffled(tracks: List<Track>) = runWhenLoaded {
        if (tracks.isEmpty()) return@runWhenLoaded
        val firstIndex = tracks.indices.random()
        val indices = tracks.indices.toMutableList()
        indices.remove(firstIndex)
        indices.shuffle()
        shuffleOrder = listOf(firstIndex) + indices
        shufflePointer = 0
        resetPosition(0L)
        update(
            _state.value.copy(
                queue = tracks,
                index = firstIndex,
                isPlaying = true,
                isBuffering = true,
                shuffle = true,
                error = null,
                recommendationReasons = emptyMap(),
                skipDirection = 1
            ),
            queueChanged = true,
            modesChanged = true,
            progressChanged = true
        )
    }

    @Volatile private var pendingStartMs = 0L

    /** Starts [track] from [startMs] (used to join a listening room mid-song). */
    fun playAt(track: Track, startMs: Long) {
        pendingStartMs = startMs.coerceAtLeast(0L)
        play(track, listOf(track))
    }

    /**
     * Synchronizes a remote track in Listen Together without wiping the user's existing local queue:
     * if [track] is already in the queue, jumps to it; otherwise inserts it right after the current item
     * (or as the first item if the queue was empty) and selects it.
     */
    fun syncRemoteTrackAt(track: Track, startMs: Long, playing: Boolean) = runWhenLoaded {
        pendingStartMs = startMs.coerceAtLeast(0L)
        val old = _state.value
        val existingIdx = old.queue.indexOfFirst { it.videoId == track.videoId }
        val (newQueue, targetIdx) = if (existingIdx >= 0) {
            old.queue to existingIdx
        } else if (old.queue.isEmpty()) {
            listOf(track) to 0
        } else {
            val insertAt = (old.index + 1).coerceIn(0, old.queue.size)
            val mutable = old.queue.toMutableList().apply { add(insertAt, track) }
            mutable to insertAt
        }
        resetPosition(startMs.coerceAtLeast(0L))
        update(
            old.copy(
                queue = newQueue,
                index = targetIdx,
                isPlaying = playing,
                isBuffering = playing,
                error = null,
                skipDirection = 1
            ),
            queueChanged = true,
            progressChanged = true
        )
    }

    /** Read once by the player when it loads a track: where that track should start (0 = the beginning). */
    fun takePendingStart(): Long {
        val v = pendingStartMs
        pendingStartMs = 0L
        return v
    }

    fun togglePlay() = runWhenLoaded {
        if (_state.value.current == null) return@runWhenLoaded
        _state.value = _state.value.copy(isPlaying = !_state.value.isPlaying)
    }

    fun setPlaying(playing: Boolean) = runWhenLoaded {
        if (_state.value.isPlaying != playing) {
            _state.value = _state.value.copy(isPlaying = playing)
        }
    }

    fun setError(message: String?) = runWhenLoaded {
        _state.value = _state.value.copy(
            error = message,
            isBuffering = false,
            isPlaying = if (message != null) false else _state.value.isPlaying
        )
    }

    fun setBuffering(buffering: Boolean) = runWhenLoaded {
        if (_state.value.isBuffering != buffering) {
            _state.value = _state.value.copy(isBuffering = buffering)
        }
    }

    /** Publishes the player position. Touches only [position], never [state], so ticks do not re-emit the queue. */
    fun setProgress(posMs: Long, durMs: Long) {
        val cur = _position.value
        if (cur.positionMs != posMs || cur.durationMs != durMs) {
            _position.value = PlaybackPosition(positionMs = posMs, durationMs = durMs)
        }
    }

    fun seekTo(positionMs: Long) = runWhenLoaded {
        resetPosition(positionMs)
        _seekEvents.tryEmit(positionMs)
        dirtyProgress = true
        schedulePersist()
    }

    fun next() = runWhenLoaded {
        val s = _state.value
        if (s.queue.isEmpty()) return@runWhenLoaded
        if (s.repeat == RepeatMode.ONE) {
            seekTo(0)
            _state.value = _state.value.copy(isPlaying = true)
            return@runWhenLoaded
        }
        val next = when {
            s.shuffle && s.queue.size > 1 -> {
                val order = shuffleOrder ?: run {
                    val ind = s.queue.indices.toMutableList()
                    ind.remove(s.index)
                    ind.shuffle()
                    val gen = listOf(s.index) + ind
                    shuffleOrder = gen
                    shufflePointer = 0
                    gen
                }
                if (shufflePointer + 1 < order.size) {
                    shufflePointer++
                    order[shufflePointer]
                } else if (s.repeat == RepeatMode.ALL) {
                    val ind = s.queue.indices.toMutableList()
                    ind.shuffle()
                    shuffleOrder = ind
                    shufflePointer = 0
                    ind[0]
                } else {
                    _state.value = s.copy(isPlaying = false)
                    return@runWhenLoaded
                }
            }
            s.index + 1 < s.queue.size -> s.index + 1
            s.repeat == RepeatMode.ALL -> 0
            else -> {
                _state.value = s.copy(isPlaying = false)
                return@runWhenLoaded
            }
        }
        if (next == s.index) {
            // Repeat-all with a single song: same handling as repeat-one.
            seekTo(0)
            _state.value = _state.value.copy(isPlaying = true)
            return@runWhenLoaded
        }
        resetPosition(0L)
        update(
            s.copy(
                index = next,
                isPlaying = true,
                isBuffering = true,
                error = null,
                skipDirection = 1,
                tonearmTransitionPending = tonearmSyncEnabled
            ),
            queueChanged = true,
            progressChanged = true
        )
    }

    fun previous(progressSeconds: Long): Boolean {
        var moved = false
        runWhenLoaded {
            val s = _state.value
            if (s.queue.isEmpty()) return@runWhenLoaded
            if (progressSeconds > 3 && s.current != null) {
                seekTo(0)
                return@runWhenLoaded
            }
            if (s.shuffle && s.queue.size > 1 && shuffleOrder != null && shufflePointer > 0) {
                shufflePointer--
                val prev = shuffleOrder!![shufflePointer]
                resetPosition(0L)
                update(
                    s.copy(
                        index = prev,
                        isPlaying = true,
                        isBuffering = true,
                        error = null,
                        skipDirection = -1,
                        tonearmTransitionPending = tonearmSyncEnabled
                    ),
                    queueChanged = true,
                    progressChanged = true
                )
                moved = true
                return@runWhenLoaded
            }
            if (s.index <= 0) {
                // Already on the first song: restart it in place (a same-index "load" would never run
                // and would leave the buffering spinner stuck).
                seekTo(0)
                _state.value = _state.value.copy(isPlaying = true, error = null)
                return@runWhenLoaded
            }
            resetPosition(0L)
            update(
                s.copy(
                    index = s.index - 1,
                    isPlaying = true,
                    isBuffering = true,
                    error = null,
                    skipDirection = -1,
                    tonearmTransitionPending = tonearmSyncEnabled
                ),
                queueChanged = true,
                progressChanged = true
            )
            moved = true
        }
        return moved
    }

    fun toggleShuffle() = runWhenLoaded {
        val s = _state.value
        val willBeShuffle = !s.shuffle
        if (willBeShuffle && s.queue.size > 1) {
            val indices = s.queue.indices.toMutableList()
            indices.remove(s.index)
            indices.shuffle()
            shuffleOrder = listOf(s.index) + indices
            shufflePointer = 0
        } else {
            shuffleOrder = null
            shufflePointer = 0
        }
        update(
            s.copy(shuffle = willBeShuffle),
            modesChanged = true
        )
    }

    fun cycleRepeat() = runWhenLoaded {
        val order = RepeatMode.values()
        val next = order[(order.indexOf(_state.value.repeat) + 1) % order.size]
        update(
            _state.value.copy(repeat = next),
            modesChanged = true
        )
    }

    fun setVolume(v: Float) = runWhenLoaded {
        val coerced = v.coerceIn(0f, 1f)
        if (_state.value.volume == coerced) return@runWhenLoaded
        update(
            _state.value.copy(volume = coerced),
            volumeChanged = true
        )
    }

    fun addToQueue(track: Track) = runWhenLoaded {
        update(
            _state.value.copy(queue = _state.value.queue + track),
            queueChanged = true
        )
    }

    fun removeFromQueue(index: Int) = runWhenLoaded {
        val s = _state.value
        if (index !in s.queue.indices) return@runWhenLoaded
        val q = s.queue.toMutableList().also { it.removeAt(index) }
        val newIdx = when {
            index < s.index -> s.index - 1
            index == s.index -> if (q.isEmpty()) -1 else s.index.coerceAtMost(q.size - 1)
            else -> s.index
        }
        val remainingIds = q.map { it.videoId }.toSet()
        update(
            s.copy(
                queue = q,
                index = newIdx,
                recommendationReasons = s.recommendationReasons.filterKeys { it in remainingIds },
                skipDirection = if (index == s.index && newIdx >= 0) 1 else s.skipDirection
            ),
            queueChanged = true
        )
    }

    fun reorder(from: Int, to: Int) = runWhenLoaded {
        val s = _state.value
        val q = s.queue.toMutableList()
        if (from < 0 || to < 0 || from >= q.size || to >= q.size) return@runWhenLoaded
        val item = q.removeAt(from); q.add(to, item)
        val current = s.current
        val idx = if (current != null) q.indexOfFirst { it.videoId == current.videoId }.coerceAtLeast(0) else s.index
        update(
            s.copy(queue = q, index = idx),
            queueChanged = true
        )
    }

    fun clearQueue() = runWhenLoaded {
        val s = _state.value
        val kept = if (s.current != null) listOf(s.current!!) else emptyList()
        val keptIds = kept.map { it.videoId }.toSet()
        update(
            s.copy(
                queue = kept,
                index = if (kept.isEmpty()) -1 else 0,
                recommendationReasons = s.recommendationReasons.filterKeys { it in keptIds }
            ),
            queueChanged = true
        )
    }

    fun appendRelated(tracks: List<Track>, reasonsByVideoId: Map<String, String> = emptyMap()) = runWhenLoaded {
        val s = _state.value
        val existing = s.queue.map { it.videoId }.toSet()
        val fresh = tracks.filter { it.videoId !in existing }.distinctBy { it.videoId }
        if (fresh.isNotEmpty()) {
            val nextReasons = s.recommendationReasons + fresh.mapNotNull { track ->
                reasonsByVideoId[track.videoId]?.takeIf { it.isNotBlank() }?.let { track.videoId to it }
            }
            update(
                s.copy(queue = s.queue + fresh, recommendationReasons = nextReasons),
                queueChanged = true
            )
        }
    }

    /** Removes upcoming auto-recommended tracks when Endless Autoplay is switched off. */
    fun removeAutoplayTracks() = runWhenLoaded {
        val s = _state.value
        if (s.recommendationReasons.isEmpty() || s.queue.isEmpty()) return@runWhenLoaded
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
                ),
                queueChanged = true
            )
        }
    }

    /** Removes upcoming explicit tracks when Hide Explicit Content is enabled. */
    fun removeExplicitUpcomingTracks() = runWhenLoaded {
        val s = _state.value
        if (s.queue.isEmpty()) return@runWhenLoaded
        val filtered = s.queue.filterIndexed { idx, track ->
            idx <= s.index || !track.explicit
        }
        if (filtered.size != s.queue.size) {
            val remainingIds = filtered.map { it.videoId }.toSet()
            update(
                s.copy(
                    queue = filtered,
                    recommendationReasons = s.recommendationReasons.filterKeys { it in remainingIds }
                ),
                queueChanged = true
            )
        }
    }

    private fun update(
        newState: QueueState,
        queueChanged: Boolean = false,
        modesChanged: Boolean = false,
        volumeChanged: Boolean = false,
        progressChanged: Boolean = false
    ) {
        _state.value = newState
        if (queueChanged) dirtyQueue = true
        if (modesChanged) dirtyModes = true
        if (volumeChanged) dirtyVolume = true
        if (progressChanged) dirtyProgress = true
        if (queueChanged || modesChanged || volumeChanged || progressChanged) {
            schedulePersist()
        }
    }

    /** Saves just the playback position (called every few seconds while playing and on pause). */
    fun persistProgress() {
        dirtyProgress = true
        schedulePersist(immediate = false)
    }

    private fun schedulePersist(immediate: Boolean = false) {
        synchronized(stateLock) {
            persistJob?.cancel()
            persistJob = scope.launch {
                if (!immediate && debounceMs > 0L) {
                    delay(debounceMs)
                }
                flushPersistence()
            }
        }
    }

    /**
     * Single ordered persistence writer. Reads a coherent snapshot of [_state] and dirty flags
     * under lock so older states can never overwrite newer states and volume/progress updates
     * never serialize the entire queue.
     */
    suspend fun flushPersistence() {
        persistMutex.withLock {
            val snapshot: QueueState
            val snapshotProgressMs: Long
            val writeQueue: Boolean
            val writeModes: Boolean
            val writeVolume: Boolean
            val writeProgress: Boolean

            synchronized(stateLock) {
                if (!dirtyQueue && !dirtyModes && !dirtyVolume && !dirtyProgress) return
                snapshot = _state.value
                snapshotProgressMs = _position.value.positionMs
                writeQueue = dirtyQueue
                writeModes = dirtyModes
                writeVolume = dirtyVolume
                writeProgress = dirtyProgress
                dirtyQueue = false
                dirtyModes = false
                dirtyVolume = false
                dirtyProgress = false
            }

            val encodedQueue = if (writeQueue) {
                queueSerializationCount++
                json.encodeToString(snapshot.queue)
            } else null
            val encodedReasons = if (writeQueue) {
                json.encodeToString(snapshot.recommendationReasons)
            } else null

            dataStore.edit { prefs ->
                val persistentQueue = prefs[AppSettingsRepository.KEY_PERSISTENT_QUEUE] ?: true
                val rememberShuffleRepeat = prefs[AppSettingsRepository.KEY_REMEMBER_SHUFFLE_REPEAT] ?: true

                if (writeVolume) {
                    prefs[KEY_VOLUME] = snapshot.volume
                }
                if (writeQueue) {
                    if (persistentQueue) {
                        prefs[KEY_QUEUE] = encodedQueue!!
                        prefs[KEY_INDEX] = snapshot.index
                        prefs[KEY_RECOMMENDATION_REASONS] = encodedReasons!!
                    } else {
                        prefs.remove(KEY_PROGRESS)
                        prefs.remove(KEY_QUEUE)
                        prefs.remove(KEY_INDEX)
                        prefs.remove(KEY_RECOMMENDATION_REASONS)
                    }
                }
                if (writeProgress) {
                    if (persistentQueue) {
                        prefs[KEY_PROGRESS] = snapshotProgressMs
                    } else {
                        prefs.remove(KEY_PROGRESS)
                    }
                }
                if (writeModes) {
                    if (rememberShuffleRepeat) {
                        prefs[KEY_SHUFFLE] = snapshot.shuffle.toString()
                        prefs[KEY_REPEAT] = snapshot.repeat.name
                    } else {
                        prefs.remove(KEY_SHUFFLE)
                        prefs.remove(KEY_REPEAT)
                    }
                }
            }
        }
    }
}
