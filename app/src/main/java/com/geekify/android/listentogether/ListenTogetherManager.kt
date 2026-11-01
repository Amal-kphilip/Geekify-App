package com.geekify.android.listentogether

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.geekify.android.BuildConfig
import com.geekify.android.data.model.Track
import com.geekify.android.di.IoDispatcher
import com.geekify.android.player.QueueManager
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.min
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
 *  - Incoming: a peer's validated `state` is applied to the local queue without wiping existing tracks.
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

    /** Ephemeral per-room peer ID so no persistent device identifier is exposed across rooms. */
    @Volatile private var userId = newEphemeralUserId()
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
        val url = normalizeServerUrl(rawServerUrl, allowInsecureCleartext = BuildConfig.DEBUG)
        val code = sanitizeRoomCode(rawRoom)
        when {
            url == null -> {
                val hint = if (BuildConfig.DEBUG) {
                    "Enter a valid server address, for example wss://example.com/ws."
                } else {
                    "Enter a secure server address starting with wss:// (for example wss://example.com/ws)."
                }
                return fail(hint)
            }
            !isValidRoomCode(code) -> return fail("Room codes need $MIN_ROOM_CHARS–$MAX_ROOM_CHARS letters or numbers.")
        }
        synchronized(lock) {
            closeSocketLocked("switching room")
            userId = newEphemeralUserId()
            serverUrl = url
            room = code
            attempt = 0
            wantConnected = true
            members.clear()
            lastSentAtByUser.clear()
        }
        _state.update { it.copy(room = code, error = null, peers = 1, serverUrl = url, status = ConnectionStatus.Connecting) }
        scope.launch { runCatching { dataStore.edit { it[KEY_SERVER_URL] = url } } }
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
        lastSentAtByUser.clear()
        _state.update { it.copy(status = ConnectionStatus.Disconnected, room = null, peers = 1, error = null) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ---------------------------------------------------------------- connection

    private fun openSocket() {
        val currentRoom: String
        val currentUser: String
        val currentUrl: String
        synchronized(lock) {
            if (!wantConnected) return
            currentRoom = room
            currentUser = userId
            currentUrl = serverUrl
        }
        val request = try {
            val sep = if ('?' in currentUrl) '&' else '?'
            Request.Builder()
                .url("$currentUrl${sep}room=${URLEncoder.encode(currentRoom, "UTF-8")}&user=${URLEncoder.encode(currentUser, "UTF-8")}")
                .header("X-Geekify-Room-Auth", deriveRoomAuthDigest(currentRoom, currentUser))
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
            val wait = backoffMs(attempt, SECURE_RANDOM.nextInt(JITTER_MS.toInt()).toLong())
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
            is RoomMessage.Join -> if (isAuthorizedRoomPeer(message.room, message.user)) {
                members.add(message.user)
                publishPeers()
                broadcastLocalState()          // let the newcomer catch up with what is playing
            }
            is RoomMessage.Leave -> if (isAuthorizedRoomPeer(message.room, message.user)) {
                members.remove(message.user)
                lastSentAtByUser.remove(message.user)
                publishPeers()
            }
            is RoomMessage.State -> if (isAuthorizedRoomPeer(message.room, message.user)) {
                if (members.add(message.user)) publishPeers()
                applyRemote(message)
            }
            is RoomMessage.Peers -> if (message.room == room) {
                _state.update { it.copy(peers = message.count.coerceIn(1, MAX_ROOM_PEERS)) }
            }
            is RoomMessage.Ping -> send(RoomMessage.Pong(message.t))
            is RoomMessage.Pong -> rttMs = (System.currentTimeMillis() - message.t).coerceIn(0L, MAX_LATENCY_MS)
            is RoomMessage.Error -> fail(message.message.take(200))
        }
    }

    private fun isAuthorizedRoomPeer(messageRoom: String, peerUser: String): Boolean {
        val activeRoom = room
        if (activeRoom.isEmpty() || messageRoom != activeRoom) return false
        if (!isValidPeerId(peerUser) || peerUser == userId) return false
        if (members.size >= MAX_ROOM_PEERS && peerUser !in members) return false
        return true
    }

    private fun publishPeers() = _state.update { it.copy(peers = members.size + 1) }

    private fun applyRemote(m: RoomMessage.State) {
        val now = System.currentTimeMillis()
        if (!isValidRemoteState(m, now)) return

        // Ignore anything older than what this peer already told us (frames can arrive out of order).
        val previous = lastSentAtByUser[m.user]
        if (previous != null && m.sentAt < previous) return
        lastSentAtByUser[m.user] = m.sentAt

        val rawTrack = m.track
        val sanitizedTrack = rawTrack?.let(::sanitizeRemoteTrack)
        // If a track was supplied by the peer but failed validation (e.g. invalid videoId), drop the frame.
        if (rawTrack != null && sanitizedTrack == null) return

        // Our own changes caused by this update must not be echoed back to the room.
        suppressBroadcastUntil = now + SUPPRESS_MS

        val local = queue.state.value
        val clampedPosition = m.positionMs.coerceIn(0L, MAX_TRACK_POSITION_MS)
        // The position was sampled a moment ago: add the time the message spent in flight.
        val target = (if (m.playing) clampedPosition + rttMs else clampedPosition).coerceIn(0L, MAX_TRACK_POSITION_MS)

        if (sanitizedTrack != null && sanitizedTrack.videoId != local.current?.videoId) {
            // Synchronize to the peer's track without wiping the user's existing local queue.
            queue.syncRemoteTrackAt(sanitizedTrack, target, m.playing)
            return
        }
        if (local.isPlaying != m.playing) queue.setPlaying(m.playing)
        if (abs(queue.position.value.positionMs - target) > DRIFT_TOLERANCE_MS) queue.seekTo(target)
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
                positionMs = queue.position.value.positionMs.coerceAtLeast(0L),
                playing = s.isPlaying,
                sentAt = System.currentTimeMillis()
            )
        )
    }

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("listen_together_server")
        const val MIN_ROOM_CHARS = 6
        const val MAX_ROOM_CHARS = 16
        const val DEFAULT_ROOM_CODE_LENGTH = 8
        const val DRIFT_TOLERANCE_MS = 1_500L
        private const val SUPPRESS_MS = 1_500L
        private const val PING_EVERY_MS = 10_000L
        private const val MAX_LATENCY_MS = 3_000L
        private const val JITTER_MS = 500L
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val MAX_ROOM_PEERS = 32
        private const val MAX_TRACK_POSITION_MS = 12L * 60L * 60L * 1_000L // 12 hours
        private const val MAX_FUTURE_SKEW_MS = 5L * 60L * 1_000L // 5 minutes
        private const val MAX_TEXT_FIELD_CHARS = 256

        private const val ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val PEER_ID_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
        private val SECURE_RANDOM = SecureRandom()
        private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
        private val PEER_ID_REGEX = Regex("^[A-Za-z0-9_-]{4,36}$")

        private val TRUSTED_THUMBNAIL_HOSTS = setOf(
            "ytimg.com",
            "ggpht.com",
            "googleusercontent.com",
            "youtube.com"
        )

        /** Generates a cryptographically random, unambiguous room code (default 8 chars = 40 bits entropy). */
        fun generateRoomCode(length: Int = DEFAULT_ROOM_CODE_LENGTH): String {
            val safeLen = length.coerceIn(MIN_ROOM_CHARS, MAX_ROOM_CHARS)
            return String(CharArray(safeLen) { ROOM_CODE_ALPHABET[SECURE_RANDOM.nextInt(ROOM_CODE_ALPHABET.length)] })
        }

        private fun newEphemeralUserId(): String =
            String(CharArray(10) { PEER_ID_ALPHABET[SECURE_RANDOM.nextInt(PEER_ID_ALPHABET.length)] })

        fun sanitizeRoomCode(rawRoom: String): String =
            rawRoom.trim().uppercase(Locale.US).filter { it in 'A'..'Z' || it in '0'..'9' }.take(MAX_ROOM_CHARS)

        fun isValidRoomCode(code: String): Boolean =
            code.length in MIN_ROOM_CHARS..MAX_ROOM_CHARS && code.all { it in 'A'..'Z' || it in '0'..'9' }

        fun isValidPeerId(user: String): Boolean = PEER_ID_REGEX.matches(user)

        fun isValidVideoId(videoId: String): Boolean = VIDEO_ID_REGEX.matches(videoId)

        /** Validates that a thumbnail URL uses HTTPS and points to a trusted YouTube/Google image host. */
        fun sanitizeThumbnailUrl(rawUrl: String?): String? {
            if (rawUrl.isNullOrBlank() || rawUrl.length > 1024) return null
            val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
            if (scheme != "https") return null
            val host = uri.host?.lowercase(Locale.US)?.trimEnd('.') ?: return null
            val trusted = TRUSTED_THUMBNAIL_HOSTS.any { domain ->
                host == domain || host.endsWith(".$domain")
            }
            return if (trusted) rawUrl.trim() else null
        }

        /** Validates and sanitizes an incoming [Track] from a remote peer, or returns null if invalid. */
        fun sanitizeRemoteTrack(track: Track): Track? {
            if (!isValidVideoId(track.videoId)) return null
            val safeTitle = track.title.trim().take(MAX_TEXT_FIELD_CHARS)
            if (safeTitle.isEmpty()) return null
            val safeArtist = track.artist.trim().take(MAX_TEXT_FIELD_CHARS)
            val safeAlbum = track.album?.trim()?.take(MAX_TEXT_FIELD_CHARS)
            val safeDuration = track.durationSeconds?.takeIf { it in 0..(12 * 3600) }
            val safeThumbnails = track.thumbnails.take(6).mapNotNull { thumb ->
                val validUrl = sanitizeThumbnailUrl(thumb.url) ?: return@mapNotNull null
                thumb.copy(url = validUrl)
            }
            return track.copy(
                title = safeTitle,
                artist = safeArtist,
                album = safeAlbum,
                thumbnails = safeThumbnails,
                durationSeconds = safeDuration
            )
        }

        /** Validates remote playback state bounds and timestamp sanity before applying. */
        fun isValidRemoteState(state: RoomMessage.State, nowMs: Long = System.currentTimeMillis()): Boolean {
            if (!isValidRoomCode(state.room)) return false
            if (!isValidPeerId(state.user)) return false
            if (state.positionMs < 0L || state.positionMs > MAX_TRACK_POSITION_MS) return false
            if (state.sentAt <= 0L || state.sentAt > nowMs + MAX_FUTURE_SKEW_MS) return false
            return true
        }

        fun deriveRoomAuthDigest(room: String, userId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("geekify-room-v1:$room:$userId".toByteArray(Charsets.UTF_8))
            return digest.take(16).joinToString("") { "%02x".format(it) }
        }

        /** 1 s, 2 s, 4 s, 8 s ... capped at 30 s, plus [jitterMs] so peers don't all reconnect in the same instant. */
        fun backoffMs(attempt: Int, jitterMs: Long = 0L): Long {
            val exponent = (attempt - 1).coerceIn(0, 10)
            return min(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl exponent) + jitterMs
        }

        /**
         * Accepts ws://, wss://, http(s):// or a bare host; returns a ws(s) URL, or null if it is unusable.
         * In release builds ([allowInsecureCleartext] = false), cleartext `ws://` / `http://` is rejected
         * for non-loopback hosts so external traffic always uses TLS (`wss://`).
         */
        fun normalizeServerUrl(raw: String, allowInsecureCleartext: Boolean = true): String? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            val url = when {
                text.startsWith("wss://") || text.startsWith("ws://") -> text
                text.startsWith("https://") -> "wss://" + text.removePrefix("https://")
                text.startsWith("http://") -> "ws://" + text.removePrefix("http://")
                else -> "wss://$text"
            }
            val parsed = runCatching { URI(url) }.getOrNull() ?: return null
            val host = parsed.host?.lowercase(Locale.US)
            if (host.isNullOrBlank()) return null
            if (url.startsWith("ws://") && !allowInsecureCleartext && !isLoopbackHost(host)) {
                return null
            }
            return url
        }

        private fun isLoopbackHost(host: String): Boolean =
            host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
    }
}
