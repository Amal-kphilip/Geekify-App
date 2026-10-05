package com.geekify.android.listentogether

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.geekify.android.data.model.Track
import com.geekify.android.di.IoDispatcher
import com.geekify.android.player.QueueManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus
    data object Connecting : ConnectionStatus
    data object Connected : ConnectionStatus
    /** Waiting [inMs] before connection attempt number [attempt]. */
    data class Reconnecting(val attempt: Int, val inMs: Long) : ConnectionStatus
}

data class RoomUiState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val room: String? = null,
    /** People in the room including this device. */
    val peers: Int = 1,
    val error: String? = null,
    val serverUrl: String = ""
)

/**
 * Group listening over a WebSocket relay.
 *
 *  - Outgoing: when this device changes track, plays / pauses or seeks, it sends a `state` message.
 *  - Incoming: a peer's `state` is applied to the local queue (track, play / pause, position).
 *  - Resilience: unexpected drops reconnect with exponential backoff (1 s, 2 s, 4 s ... capped at 30 s, plus
 *    jitter); malformed or oversized frames are dropped without affecting the session.
 *  - Latency: a ping / pong round trip is measured and added to a peer's reported position; the local player
 *    is only re-seeked when it drifts more than [DRIFT_TOLERANCE_MS], so small jitter never causes stutter.
 */
@Singleton
class ListenTogetherManager @Inject constructor(
    private val http: OkHttpClient,
    private val queue: QueueManager,
    private val dataStore: DataStore<Preferences>,
    @IoDispatcher io: CoroutineDispatcher
) {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val _state = MutableStateFlow(RoomUiState())
    val state: StateFlow<RoomUiState> = _state.asStateFlow()

    private val userId = UUID.randomUUID().toString().take(8)
    private val lock = Any()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var wantConnected = false
    @Volatile private var rttMs = 0L
    @Volatile private var suppressBroadcastUntil = 0L
    private var serverUrl = ""
    private var room = ""
    private var attempt = 0
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private val members = ConcurrentHashMap.newKeySet<String>()
    private val lastSentAtByUser = ConcurrentHashMap<String, Long>()

    private val wsClient: OkHttpClient by lazy { http.newBuilder().pingInterval(20, TimeUnit.SECONDS).build() }

    private data class PlaybackSnapshot(val videoId: String?, val playing: Boolean)

    init {
        scope.launch {
            runCatching { dataStore.data.first()[KEY_SERVER_URL] }.getOrNull()?.let { saved ->
                _state.update { it.copy(serverUrl = saved) }
            }
        }
        // Local changes -> peers.
        queue.state
            .map { PlaybackSnapshot(it.current?.videoId, it.isPlaying) }
            .distinctUntilChanged()
            .onEach { broadcastLocalState() }
            .launchIn(scope)
        queue.seekEvents.onEach { broadcastLocalState() }.launchIn(scope)
    }

    // ---------------------------------------------------------------- public API

    fun join(rawServerUrl: String, rawRoom: String) {
        val url = normalizeServerUrl(rawServerUrl)
        val code = rawRoom.trim().uppercase().filter { it.isLetterOrDigit() }.take(MAX_ROOM_CHARS)
        when {
            url == null -> return fail("Enter a valid server address, for example wss://example.com/ws.")
            code.length < MIN_ROOM_CHARS -> return fail("Room codes need at least $MIN_ROOM_CHARS letters or numbers.")
        }
        synchronized(lock) {
            closeSocketLocked("switching room")
            serverUrl = url!!
            room = code
            attempt = 0
            wantConnected = true
            members.clear()
            lastSentAtByUser.clear()
        }
        _state.update { it.copy(room = code, error = null, peers = 1, serverUrl = url!!, status = ConnectionStatus.Connecting) }
        scope.launch { runCatching { dataStore.edit { it[KEY_SERVER_URL] = url!! } } }
        openSocket()
    }

    fun leave() {
        synchronized(lock) {
            wantConnected = false
            reconnectJob?.cancel()
            pingJob?.cancel()
            send(RoomMessage.Leave(room, userId))
            closeSocketLocked("left room")
        }
        members.clear()
        _state.update { it.copy(status = ConnectionStatus.Disconnected, room = null, peers = 1, error = null) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ---------------------------------------------------------------- connection

    private fun openSocket() {
        val request = try {
            val sep = if ('?' in serverUrl) '&' else '?'
            Request.Builder()
                .url("$serverUrl${sep}room=${URLEncoder.encode(room, "UTF-8")}&user=$userId")
                .build()
        } catch (e: IllegalArgumentException) {
            fail("That server address can't be used.")
            leave()
            return
        }
        synchronized(lock) {
            if (!wantConnected) return
            socket = wsClient.newWebSocket(request, SocketListener())
        }
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            synchronized(lock) { attempt = 0 }
            _state.update { it.copy(status = ConnectionStatus.Connected, error = null) }
            send(RoomMessage.Join(room, userId))
            startPinging()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            // A bad frame is dropped; it must never take the session (or the app) down.
            val message = MessageCodec.decode(text) ?: return
            runCatching { handle(message) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = connectionLost(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = connectionLost(webSocket)
    }

    private fun connectionLost(dead: WebSocket) {
        synchronized(lock) {
            if (dead !== socket) return   // an old socket we already replaced or closed
            socket = null
            pingJob?.cancel()
            if (!wantConnected) return
            attempt++
            val wait = backoffMs(attempt, Random.nextLong(0, JITTER_MS))
            _state.update { it.copy(status = ConnectionStatus.Reconnecting(attempt, wait)) }
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(wait)
                if (wantConnected) openSocket()
            }
        }
    }

    private fun closeSocketLocked(reason: String) {
        val old = socket
        socket = null
        old?.close(1000, reason)
    }

    private fun startPinging() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && wantConnected) {
                send(RoomMessage.Ping(System.currentTimeMillis()))
                delay(PING_EVERY_MS)
            }
        }
    }

    private fun fail(message: String) = _state.update { it.copy(error = message) }

    private fun send(message: RoomMessage): Boolean =
        runCatching { socket?.send(MessageCodec.encode(message)) ?: false }.getOrDefault(false)

    // ---------------------------------------------------------------- incoming

    private fun handle(message: RoomMessage) {
        when (message) {
            is RoomMessage.Join -> if (message.user != userId) {
                members.add(message.user)
                publishPeers()
                broadcastLocalState()          // let the newcomer catch up with what is playing
            }
            is RoomMessage.Leave -> {
                members.remove(message.user)
                publishPeers()
            }
            is RoomMessage.State -> if (message.user != userId && message.room == room) {
                if (members.add(message.user)) publishPeers()
                applyRemote(message)
            }
            is RoomMessage.Peers -> _state.update { it.copy(peers = message.count.coerceAtLeast(1)) }
            is RoomMessage.Ping -> send(RoomMessage.Pong(message.t))
            is RoomMessage.Pong -> rttMs = (System.currentTimeMillis() - message.t).coerceIn(0L, MAX_LATENCY_MS)
            is RoomMessage.Error -> fail(message.message)
        }
    }

    private fun publishPeers() = _state.update { it.copy(peers = members.size + 1) }

    private fun applyRemote(m: RoomMessage.State) {
        // Ignore anything older than what this peer already told us (frames can arrive out of order).
        val previous = lastSentAtByUser.put(m.user, m.sentAt)
        if (previous != null && m.sentAt < previous) return

        // Our own changes caused by this update must not be echoed back to the room.
        suppressBroadcastUntil = System.currentTimeMillis() + SUPPRESS_MS

        val local = queue.state.value
        // The position was sampled a moment ago: add the time the message spent in flight.
        val target = if (m.playing) m.positionMs + rttMs else m.positionMs
        val track: Track? = m.track

        if (track != null && track.videoId != local.current?.videoId) {
            queue.playAt(track, target)
            if (!m.playing) queue.setPlaying(false)
            return
        }
        if (local.isPlaying != m.playing) queue.setPlaying(m.playing)
        if (abs(local.progressMs - target) > DRIFT_TOLERANCE_MS) queue.seekTo(target)
    }

    // ---------------------------------------------------------------- outgoing

    private fun broadcastLocalState() {
        if (_state.value.status != ConnectionStatus.Connected) return
        if (System.currentTimeMillis() < suppressBroadcastUntil) return
        val s = queue.state.value
        send(
            RoomMessage.State(
                room = room,
                user = userId,
                track = s.current,
                positionMs = s.progressMs,
                playing = s.isPlaying,
                sentAt = System.currentTimeMillis()
            )
        )
    }

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("listen_together_server")
        const val MIN_ROOM_CHARS = 4
        const val MAX_ROOM_CHARS = 12
        const val DRIFT_TOLERANCE_MS = 1_500L
        private const val SUPPRESS_MS = 1_500L
        private const val PING_EVERY_MS = 10_000L
        private const val MAX_LATENCY_MS = 3_000L
        private const val JITTER_MS = 500L
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L

        /** 1 s, 2 s, 4 s, 8 s ... capped at 30 s, plus [jitterMs] so peers don't all reconnect in the same instant. */
        fun backoffMs(attempt: Int, jitterMs: Long = 0L): Long {
            val exponent = (attempt - 1).coerceIn(0, 10)
            return min(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl exponent) + jitterMs
        }

        /** Accepts ws://, wss://, http(s):// or a bare host; returns a ws(s) URL, or null if it is unusable. */
        fun normalizeServerUrl(raw: String): String? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            val url = when {
                text.startsWith("wss://") || text.startsWith("ws://") -> text
                text.startsWith("https://") -> "wss://" + text.removePrefix("https://")
                text.startsWith("http://") -> "ws://" + text.removePrefix("http://")
                else -> "wss://$text"
            }
            val host = runCatching { URI(url).host }.getOrNull()
            return if (host.isNullOrBlank()) null else url
        }
    }
}
