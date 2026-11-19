package com.geekify.android.discord

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.geekify.android.data.model.Track
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber

sealed interface DiscordConnectionStatus {
    data object Disabled : DiscordConnectionStatus
    data object NotConfigured : DiscordConnectionStatus
    data object Idle : DiscordConnectionStatus
    data object Connecting : DiscordConnectionStatus
    data class Connected(val username: String? = null) : DiscordConnectionStatus
    data class Error(val message: String) : DiscordConnectionStatus
}

data class DiscordRpcUiState(
    val enabled: Boolean = false,
    val applicationId: String = "",
    val hasToken: Boolean = false,
    val maskedToken: String = "",
    val status: DiscordConnectionStatus = DiscordConnectionStatus.Disabled,
    val lastSyncedTrack: String? = null
)

/**
 * Discord Rich Presence manager using Discord Gateway v10 WebSocket with user-supplied
 * OAuth2 access token and Application ID stored in DataStore.
 * Reports Connected only after Discord Gateway READY (Opcode 0) confirms authentication.
 */
class DiscordRpcManager(
    private val http: OkHttpClient,
    private val dataStore: DataStore<Preferences>
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private val connected = AtomicBoolean(false)
    private val identified = AtomicBoolean(false)
    @Volatile private var lastSeq: Int? = null
    @Volatile private var pendingPresencePayload: String? = null

    private val _uiState = MutableStateFlow(DiscordRpcUiState())
    val uiState: StateFlow<DiscordRpcUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            dataStore.data.collect { prefs ->
                val enabled = prefs[KEY_ENABLED] == true
                val appId = prefs[KEY_APP_ID].orEmpty()
                val token = prefs[KEY_ACCESS_TOKEN].orEmpty()
                val currentStatus = _uiState.value.status
                val nextStatus = when {
                    !enabled -> DiscordConnectionStatus.Disabled
                    appId.isBlank() || token.isBlank() -> DiscordConnectionStatus.NotConfigured
                    currentStatus is DiscordConnectionStatus.Connected ||
                        currentStatus is DiscordConnectionStatus.Connecting ||
                        currentStatus is DiscordConnectionStatus.Error -> currentStatus
                    else -> DiscordConnectionStatus.Idle
                }
                _uiState.value = _uiState.value.copy(
                    enabled = enabled,
                    applicationId = appId,
                    hasToken = token.isNotBlank(),
                    maskedToken = if (token.length > 8) "${token.take(4)}••••••••${token.takeLast(4)}" else if (token.isNotBlank()) "••••••••" else "",
                    status = nextStatus
                )
            }
        }
    }

    suspend fun setConfig(enabled: Boolean, applicationId: String, accessToken: String?) {
        val cleanAppId = applicationId.trim()
        dataStore.edit { prefs ->
            prefs[KEY_ENABLED] = enabled
            prefs[KEY_APP_ID] = cleanAppId
            if (accessToken != null) {
                prefs[KEY_ACCESS_TOKEN] = accessToken.trim()
            }
        }
        val prefs = dataStore.data.first()
        val effectiveToken = prefs[KEY_ACCESS_TOKEN].orEmpty()
        if (!enabled) {
            disconnect()
            _uiState.value = _uiState.value.copy(status = DiscordConnectionStatus.Disabled)
        } else if (cleanAppId.isBlank() || effectiveToken.isBlank()) {
            disconnect()
            _uiState.value = _uiState.value.copy(
                status = DiscordConnectionStatus.Error("Both Discord Application ID and Access Token are required.")
            )
        } else {
            testConnection()
        }
    }

    fun testConnection() {
        scope.launch {
            val prefs = dataStore.data.first()
            val appId = prefs[KEY_APP_ID].orEmpty().trim()
            val token = prefs[KEY_ACCESS_TOKEN].orEmpty().trim()
            if (appId.isBlank() || token.isBlank()) {
                _uiState.value = _uiState.value.copy(
                    status = DiscordConnectionStatus.Error("Enter both Application ID and Access Token first.")
                )
                return@launch
            }
            disconnect()
            _uiState.value = _uiState.value.copy(status = DiscordConnectionStatus.Connecting)
            connectGateway(token)
        }
    }

    /** Builds a standard OAuth2 PKCE authorize URL for a user-registered Discord Application ID. */
    fun buildOAuthPkceUrl(applicationId: String, redirectUri: String = "geekify-auth://discord/callback"): Pair<String, String> {
        val safeAppId = java.net.URLEncoder.encode(applicationId.trim(), "UTF-8")
        val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        val url = "https://discord.com/oauth2/authorize" +
            "?client_id=$safeAppId" +
            "&response_type=code" +
            "&redirect_uri=${java.net.URLEncoder.encode(redirectUri, "UTF-8")}" +
            "&scope=openid%20sdk.social_layer_presence" +
            "&code_challenge=$challenge" +
            "&code_challenge_method=S256"
        return url to verifier
    }

    suspend fun updatePresence(track: Track?, isPlaying: Boolean, positionMs: Long = 0L, durationMs: Long = 0L) {
        val prefs = dataStore.data.first()
        if (prefs[KEY_ENABLED] != true) return
        val token = prefs[KEY_ACCESS_TOKEN]?.takeIf { it.isNotBlank() } ?: return
        val appId = prefs[KEY_APP_ID]?.takeIf { it.isNotBlank() } ?: return

        val now = System.currentTimeMillis()
        val payload = buildJsonObject {
            put("op", 3) // Presence Update
            put("d", buildJsonObject {
                put("since", if (isPlaying) 0 else now)
                put("status", "online")
                put("afk", false)
                put("activities", buildJsonArray {
                    if (track != null && isPlaying) {
                        addJsonObject {
                            put("name", "Geekify")
                            put("type", 2) // LISTENING
                            put("application_id", appId)
                            put("details", track.title)
                            put("state", track.artist)
                            if (durationMs > 0L) {
                                put("timestamps", buildJsonObject {
                                    put("start", now - positionMs)
                                    put("end", now + (durationMs - positionMs).coerceAtLeast(0L))
                                })
                            }
                        }
                    }
                })
            })
        }.toString()

        pendingPresencePayload = payload
        _uiState.value = _uiState.value.copy(
            lastSyncedTrack = if (track != null && isPlaying) "${track.title} — ${track.artist}" else null
        )
        if (!connected.get()) {
            _uiState.value = _uiState.value.copy(status = DiscordConnectionStatus.Connecting)
            connectGateway(token)
            return
        }
        if (identified.get()) {
            socket?.send(payload)
        }
    }

    private fun connectGateway(token: String) {
        if (socket != null) return
        val req = Request.Builder().url("wss://gateway.discord.gg/?v=10&encoding=json").build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = json.parseToJsonElement(text).jsonObject
                    obj["s"]?.jsonPrimitive?.intOrNull?.let { lastSeq = it }
                    val op = obj["op"]?.jsonPrimitive?.intOrNull
                    val eventType = obj["t"]?.jsonPrimitive?.contentOrNull
                    when (op) {
                        10 -> { // HELLO -> send IDENTIFY and start heartbeat
                            val interval = obj["d"]?.jsonObject?.get("heartbeat_interval")?.jsonPrimitive?.longOrNull ?: 41250L
                            val identify = buildJsonObject {
                                put("op", 2)
                                put("d", buildJsonObject {
                                    put("token", token)
                                    put("intents", 0)
                                    put("properties", buildJsonObject {
                                        put("os", "android")
                                        put("browser", "Geekify")
                                        put("device", "Geekify")
                                    })
                                })
                            }.toString()
                            webSocket.send(identify)

                            heartbeatJob?.cancel()
                            heartbeatJob = scope.launch {
                                while (isActive && connected.get()) {
                                    delay(interval)
                                    val hb = buildJsonObject {
                                        put("op", 1)
                                        val seq = lastSeq
                                        if (seq != null) put("d", seq) else put("d", null as String?)
                                    }.toString()
                                    webSocket.send(hb)
                                }
                            }
                        }
                        0 -> { // DISPATCH
                            if (eventType == "READY") {
                                val user = obj["d"]?.jsonObject?.get("user")?.jsonObject
                                    ?.get("username")?.jsonPrimitive?.contentOrNull
                                identified.set(true)
                                _uiState.value = _uiState.value.copy(
                                    status = DiscordConnectionStatus.Connected(user)
                                )
                                pendingPresencePayload?.let { webSocket.send(it) }
                            }
                        }
                        9 -> { // INVALID_SESSION
                            _uiState.value = _uiState.value.copy(
                                status = DiscordConnectionStatus.Error("Discord session rejected (Invalid Session).")
                            )
                            disconnect()
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.d(t, "Discord Gateway connection failed")
                _uiState.value = _uiState.value.copy(
                    status = DiscordConnectionStatus.Error("Network error: ${t.message ?: "Could not reach Discord Gateway"}")
                )
                disconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                if (code == 4004) {
                    _uiState.value = _uiState.value.copy(
                        status = DiscordConnectionStatus.Error("Authentication failed (4004): Invalid Discord token.")
                    )
                } else if (code != 1000) {
                    _uiState.value = _uiState.value.copy(
                        status = DiscordConnectionStatus.Error("Gateway closed ($code): ${reason.ifBlank { "Disconnected" }}")
                    )
                }
                disconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                disconnect()
            }
        })
    }

    fun disconnect() {
        connected.set(false)
        identified.set(false)
        heartbeatJob?.cancel()
        heartbeatJob = null
        runCatching { socket?.close(1000, "disconnect") }
        socket = null
    }

    companion object {
        val KEY_ENABLED = booleanPreferencesKey("discord_rpc_enabled")
        val KEY_APP_ID = stringPreferencesKey("discord_rpc_app_id")
        val KEY_ACCESS_TOKEN = stringPreferencesKey("discord_rpc_access_token")
    }
}
