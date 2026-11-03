package com.geekify.android.player

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.geekify.android.data.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueManagerTest {

    private class FakePreferencesDataStore(
        initial: Preferences = emptyPreferences(),
        private val readGate: CompletableDeferred<Unit>? = null
    ) : DataStore<Preferences> {
        val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = flow {
            readGate?.await()
            emitAll(state)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(state.value.toMutablePreferences())
            state.value = updated
            return updated
        }
    }

    private fun track(id: String) = Track(
        videoId = id,
        title = "Song $id",
        artist = "Artist $id",
        durationSeconds = 180
    )

    @Test
    fun `volume progress and mode updates never serialize the queue`() = runBlocking {
        val store = FakePreferencesDataStore()
        val manager = QueueManager(store, Dispatchers.Unconfined, debounceMs = 0L)
        manager.awaitLoaded()

        manager.play(track("t1"), listOf(track("t1"), track("t2"), track("t3")))
        manager.flushPersistence()
        assertEquals(1, manager.queueSerializationCount)

        manager.setVolume(0.42f)
        manager.flushPersistence()
        assertEquals(1, manager.queueSerializationCount)
        assertEquals(0.42f, store.state.value[QueueManager.KEY_VOLUME] ?: 0f, 0.0001f)

        manager.setProgress(45_000L, 180_000L)
        manager.persistProgress()
        manager.flushPersistence()
        assertEquals(1, manager.queueSerializationCount)
        assertEquals(45_000L, store.state.value[QueueManager.KEY_PROGRESS])
        assertEquals(45_000L, manager.position.value.positionMs)
        assertEquals(180_000L, manager.position.value.durationMs)

        manager.toggleShuffle()
        manager.cycleRepeat()
        manager.flushPersistence()
        assertEquals(1, manager.queueSerializationCount)
        assertEquals("true", store.state.value[QueueManager.KEY_SHUFFLE])
        assertEquals(RepeatMode.ALL.name, store.state.value[QueueManager.KEY_REPEAT])
    }

    @Test
    fun `mutations before initial load completes are applied in order after restoration`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val json = Json { ignoreUnknownKeys = true }
        val initialPrefs: MutablePreferences = emptyPreferences().toMutablePreferences().apply {
            this[QueueManager.KEY_QUEUE] = json.encodeToString(listOf(track("restored1"), track("restored2")))
            this[QueueManager.KEY_INDEX] = 0
            this[QueueManager.KEY_PROGRESS] = 12_500L
        }
        val store = FakePreferencesDataStore(initial = initialPrefs, readGate = gate)
        val manager = QueueManager(store, Dispatchers.Default, debounceMs = 0L)

        // Mutate before restoration finishes: should queue behind restoration rather than getting wiped by it
        manager.addToQueue(track("addedBeforeLoad"))
        manager.setVolume(0.6f)

        gate.complete(Unit)
        manager.awaitLoaded()
        manager.flushPersistence()

        val state = manager.state.value
        assertEquals(listOf("restored1", "restored2", "addedBeforeLoad"), state.queue.map { it.videoId })
        assertEquals(0, state.index)
        assertFalse(state.isPlaying)
        assertEquals(0.6f, state.volume, 0.0001f)
    }

    @Test
    fun `rapid queue mutations coalesce and never overwrite newer state with older state`() = runBlocking {
        val store = FakePreferencesDataStore()
        val manager = QueueManager(store, Dispatchers.Default, debounceMs = 80L)
        manager.awaitLoaded()

        manager.play(track("a"), listOf(track("a")))
        manager.addToQueue(track("b"))
        manager.addToQueue(track("c"))
        manager.next()
        manager.flushPersistence()

        val json = Json { ignoreUnknownKeys = true }
        val persistedQueue = json.decodeFromString<List<Track>>(store.state.value[QueueManager.KEY_QUEUE]!!)
        assertEquals(listOf("a", "b", "c"), persistedQueue.map { it.videoId })
        assertEquals(1, store.state.value[QueueManager.KEY_INDEX])
        assertTrue(manager.queueSerializationCount <= 2)
    }

    @Test
    fun `syncRemoteTrackAt preserves existing local queue`() = runBlocking {
        val store = FakePreferencesDataStore()
        val manager = QueueManager(store, Dispatchers.Unconfined, debounceMs = 0L)
        manager.awaitLoaded()

        manager.play(track("local1"), listOf(track("local1"), track("local2"), track("local3")))
        manager.syncRemoteTrackAt(track("remoteSong"), startMs = 15_000L, playing = true)

        val state = manager.state.value
        assertEquals(listOf("local1", "remoteSong", "local2", "local3"), state.queue.map { it.videoId })
        assertEquals(1, state.index)
        assertEquals("remoteSong", state.current?.videoId)
        assertEquals(15_000L, manager.takePendingStart())
    }

    @Test
    fun `high frequency setProgress updates position flow without re-emitting queue state`() = runBlocking {
        val store = FakePreferencesDataStore()
        val manager = QueueManager(store, Dispatchers.Unconfined, debounceMs = 0L)
        manager.awaitLoaded()

        manager.play(track("song1"))
        val stateBeforeTicks = manager.state.value

        var stateEmissions = 0
        val collectJob = launch(Dispatchers.Unconfined) {
            manager.state.collect { stateEmissions++ }
        }

        // 100 ticks of position updates (e.g. every 500ms during playback)
        for (i in 1..100) {
            manager.setProgress(i * 500L, 180_000L)
        }

        // stateEmissions should be 1 (only the initial replay emission upon subscription)
        assertEquals(1, stateEmissions)
        assertEquals(stateBeforeTicks, manager.state.value)
        assertEquals(50_000L, manager.position.value.positionMs)
        assertEquals(180_000L, manager.position.value.durationMs)

        collectJob.cancel()
    }

    @Test
    fun `toggleShuffle advances through randomized order without repeating and untoggle preserves queue`() = runBlocking {
        val store = FakePreferencesDataStore()
        val manager = QueueManager(store, Dispatchers.Unconfined, debounceMs = 0L)
        manager.awaitLoaded()

        val originalTracks = listOf(track("s1"), track("s2"), track("s3"), track("s4"), track("s5"))
        manager.play(track("s3"), originalTracks)
        assertEquals("s3", manager.state.value.current?.videoId)

        // Turn shuffle ON
        manager.toggleShuffle()
        assertTrue(manager.state.value.shuffle)
        assertEquals("s3", manager.state.value.current?.videoId)
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), manager.state.value.queue.map { it.videoId })

        // Advance through all remaining songs
        val playedInShuffle = mutableListOf<String>()
        playedInShuffle.add(manager.state.value.current!!.videoId)
        repeat(4) {
            manager.next()
            playedInShuffle.add(manager.state.value.current!!.videoId)
        }

        // All 5 songs played exactly once
        assertEquals(5, playedInShuffle.size)
        assertEquals(5, playedInShuffle.distinct().size)
        assertTrue(playedInShuffle.containsAll(listOf("s1", "s2", "s3", "s4", "s5")))

        // Previous walks backwards through the shuffle sequence
        manager.previous(progressSeconds = 0)
        assertEquals(playedInShuffle[3], manager.state.value.current?.videoId)

        // Turn shuffle OFF -> Queue remains original order
        manager.toggleShuffle()
        assertFalse(manager.state.value.shuffle)
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), manager.state.value.queue.map { it.videoId })
    }

    @Test
    fun `playShuffled initializes queue with random track and shuffle mode true`() = runBlocking {
        val store = FakePreferencesDataStore()
        val manager = QueueManager(store, Dispatchers.Unconfined, debounceMs = 0L)
        manager.awaitLoaded()

        val tracks = listOf(track("a1"), track("a2"), track("a3"), track("a4"))
        manager.playShuffled(tracks)

        val state = manager.state.value
        assertTrue(state.shuffle)
        assertTrue(state.index in tracks.indices)
        assertEquals(4, state.queue.size)
        assertEquals(tracks[state.index], state.current)
    }
    @Test
    fun `account transition clears the previous account queue but preserves device audio preferences`() = runBlocking {
        val store = FakePreferencesDataStore()
        val manager = QueueManager(store, Dispatchers.Unconfined, debounceMs = 0L)
        manager.awaitLoaded()

        manager.play(track("account-a-song"), listOf(track("account-a-song"), track("account-a-next")))
        manager.setProgress(52_000L, 180_000L)
        manager.setVolume(0.37f)
        manager.toggleShuffle()
        manager.cycleRepeat()
        manager.flushPersistence()

        val volumeBefore = manager.state.value.volume
        val shuffleBefore = manager.state.value.shuffle
        val repeatBefore = manager.state.value.repeat

        manager.clearForAccountTransition()

        assertTrue(manager.state.value.queue.isEmpty())
        assertEquals(-1, manager.state.value.index)
        assertFalse(manager.state.value.isPlaying)
        assertEquals(0L, manager.position.value.positionMs)
        assertEquals(volumeBefore, manager.state.value.volume, 0.0001f)
        assertEquals(shuffleBefore, manager.state.value.shuffle)
        assertEquals(repeatBefore, manager.state.value.repeat)
        assertEquals(null, store.state.value[QueueManager.KEY_QUEUE])
        assertEquals(null, store.state.value[QueueManager.KEY_INDEX])
        assertEquals(null, store.state.value[QueueManager.KEY_PROGRESS])
        assertEquals(volumeBefore, store.state.value[QueueManager.KEY_VOLUME] ?: 0f, 0.0001f)
        assertEquals(shuffleBefore.toString(), store.state.value[QueueManager.KEY_SHUFFLE])
        assertEquals(repeatBefore.name, store.state.value[QueueManager.KEY_REPEAT])
    }

}
