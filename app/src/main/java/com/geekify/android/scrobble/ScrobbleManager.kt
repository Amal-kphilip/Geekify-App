package com.geekify.android.scrobble

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.geekify.android.data.model.Track
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

@Serializable
data class PendingScrobble(
    val title: String,
    val artist: String,
    val album: String? = null,
    val timestampSec: Long,
    val provider: String // "listenbrainz" or "lastfm"
)

/**
 * Multi-provider scrobbling manager supporting **ListenBrainz** and **Last.fm** (API 2.0 with MD5
 * `api_sig` signing) plus a persistent offline retry queue stored in DataStore.
 *
 * All API keys, secrets, and user tokens are supplied by the user at runtime — nothing is hardcoded.
 */
class ScrobbleManager(
    private val http: OkHttpClient,
    private val dataStore: DataStore<Preferences>
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun setListenBrainzConfig(enabled: Boolean, userToken: String) {
        dataStore.edit { prefs ->
            prefs[KEY_LB_ENABLED] = enabled
            prefs[KEY_LB_TOKEN] = userToken.trim()
        }
    }

    suspend fun setLastFmConfig(
        enabled: Boolean,
        apiKey: String,
        apiSecret: String,
        sessionKey: String
    ) {
        dataStore.edit { prefs ->
            prefs[KEY_LFM_ENABLED] = enabled
            prefs[KEY_LFM_API_KEY] = apiKey.trim()
            prefs[KEY_LFM_API_SECRET] = apiSecret.trim()
            prefs[KEY_LFM_SESSION_KEY] = sessionKey.trim()
        }
    }

    /**
     * Authenticates a Last.fm user via `auth.getMobileSession` using user-provided API key & secret,
     * persisting the returned session key (`sk`) in DataStore.
     */
    suspend fun authenticateLastFm(
        username: String,
        password: String,
        apiKey: String,
        apiSecret: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val params = sortedMapOf(
                "api_key" to apiKey.trim(),
                "method" to "auth.getMobileSession",
                "password" to password,
                "username" to username.trim()
            )
            val sig = computeLastFmSignature(params, apiSecret.trim())
            val form = FormBody.Builder().apply {
                params.forEach { (k, v) -> add(k, v) }
                add("api_sig", sig)
                add("format", "json")
            }.build()
            val req = Request.Builder().url(LASTFM_API_URL).post(form).build()
            http.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) error("Last.fm auth failed with HTTP ${res.code}")
                val root = json.parseToJsonElement(body).jsonObject
                val sessionKey = root["session"]?.jsonObject?.get("key")?.jsonPrimitive?.contentOrNull
                    ?: error(root["message"]?.jsonPrimitive?.contentOrNull ?: "Invalid Last.fm session response")
                setLastFmConfig(enabled = true, apiKey = apiKey, apiSecret = apiSecret, sessionKey = sessionKey)
                sessionKey
            }
        }
    }

    suspend fun submitNowPlaying(track: Track) {
        submitListenBrainz(track, listenType = "playing_now", queueOnFailure = false)
        submitLastFm(track, method = "track.updateNowPlaying", timestampSec = null, queueOnFailure = false)
    }

    suspend fun submitScrobble(track: Track, listenedAtEpochSec: Long = System.currentTimeMillis() / 1000L) {
        flushPendingScrobbles()
        submitListenBrainz(track, listenType = "single", listenedAt = listenedAtEpochSec, queueOnFailure = true)
        submitLastFm(track, method = "track.scrobble", timestampSec = listenedAtEpochSec, queueOnFailure = true)
    }

    suspend fun flushPendingScrobbles() = withContext(Dispatchers.IO) {
        val prefs = dataStore.data.first()
        val raw = prefs[KEY_PENDING_QUEUE].orEmpty()
        if (raw.isBlank()) return@withContext
        val queue = runCatching { json.decodeFromString<List<PendingScrobble>>(raw) }.getOrDefault(emptyList())
        if (queue.isEmpty()) return@withContext
        val remaining = mutableListOf<PendingScrobble>()
        for (item in queue) {
            val track = Track(videoId = "", title = item.title, artist = item.artist, album = item.album)
            val ok = when (item.provider) {
                "listenbrainz" -> submitListenBrainz(track, "single", item.timestampSec, queueOnFailure = false)
                "lastfm" -> submitLastFm(track, "track.scrobble", item.timestampSec, queueOnFailure = false)
                else -> true
            }
            if (!ok) remaining.add(item)
        }
        dataStore.edit { it[KEY_PENDING_QUEUE] = json.encodeToString(remaining.takeLast(100)) }
    }

    private suspend fun enqueuePending(item: PendingScrobble) {
        dataStore.edit { prefs ->
            val current = runCatching {
                json.decodeFromString<List<PendingScrobble>>(prefs[KEY_PENDING_QUEUE].orEmpty())
            }.getOrDefault(emptyList())
            prefs[KEY_PENDING_QUEUE] = json.encodeToString((current + item).takeLast(100))
        }
    }

    private suspend fun submitListenBrainz(
        track: Track,
        listenType: String,
        listenedAt: Long? = null,
        queueOnFailure: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val prefs = dataStore.data.first()
        if (prefs[KEY_LB_ENABLED] != true) return@withContext true
        val token = prefs[KEY_LB_TOKEN]?.takeIf { it.isNotBlank() } ?: return@withContext true

        val payload = buildJsonObject {
            put("listen_type", listenType)
            put("payload", buildJsonArray {
                add(buildJsonObject {
                    if (listenedAt != null) put("listened_at", listenedAt)
                    put("track_metadata", buildJsonObject {
                        put("artist_name", track.artist)
                        put("track_name", track.title)
                        track.album?.takeIf { it.isNotBlank() }?.let { put("release_name", it) }
                    })
                })
            })
        }.toString()

        val req = Request.Builder()
            .url("https://api.listenbrainz.org/1/submit-listens")
            .header("Authorization", "Token $token")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        try {
            http.newCall(req).execute().use { res ->
                if (res.isSuccessful) return@withContext true
                Timber.w("ListenBrainz $listenType failed with HTTP ${res.code}")
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "ListenBrainz $listenType request failed")
        }
        if (queueOnFailure && listenedAt != null) {
            enqueuePending(PendingScrobble(track.title, track.artist, track.album, listenedAt, "listenbrainz"))
        }
        false
    }

    private suspend fun submitLastFm(
        track: Track,
        method: String,
        timestampSec: Long?,
        queueOnFailure: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val prefs = dataStore.data.first()
        if (prefs[KEY_LFM_ENABLED] != true) return@withContext true
        val apiKey = prefs[KEY_LFM_API_KEY]?.takeIf { it.isNotBlank() } ?: return@withContext true
        val apiSecret = prefs[KEY_LFM_API_SECRET]?.takeIf { it.isNotBlank() } ?: return@withContext true
        val sessionKey = prefs[KEY_LFM_SESSION_KEY]?.takeIf { it.isNotBlank() } ?: return@withContext true

        val params = sortedMapOf(
            "api_key" to apiKey,
            "artist" to track.artist,
            "method" to method,
            "sk" to sessionKey,
            "track" to track.title
        )
        track.album?.takeIf { it.isNotBlank() }?.let { params["album"] = it }
        if (timestampSec != null) params["timestamp"] = timestampSec.toString()

        val sig = computeLastFmSignature(params, apiSecret)
        val form = FormBody.Builder().apply {
            params.forEach { (k, v) -> add(k, v) }
            add("api_sig", sig)
            add("format", "json")
        }.build()

        val req = Request.Builder().url(LASTFM_API_URL).post(form).build()
        try {
            http.newCall(req).execute().use { res ->
                if (res.isSuccessful) return@withContext true
                Timber.w("Last.fm $method failed with HTTP ${res.code}")
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "Last.fm $method request failed")
        }
        if (queueOnFailure && timestampSec != null) {
            enqueuePending(PendingScrobble(track.title, track.artist, track.album, timestampSec, "lastfm"))
        }
        false
    }

    companion object {
        private const val LASTFM_API_URL = "https://ws.audioscrobbler.com/2.0/"
        val KEY_LB_ENABLED = booleanPreferencesKey("scrobble_listenbrainz_enabled")
        val KEY_LB_TOKEN = stringPreferencesKey("scrobble_listenbrainz_token")
        val KEY_LFM_ENABLED = booleanPreferencesKey("scrobble_lastfm_enabled")
        val KEY_LFM_API_KEY = stringPreferencesKey("scrobble_lastfm_api_key")
        val KEY_LFM_API_SECRET = stringPreferencesKey("scrobble_lastfm_api_secret")
        val KEY_LFM_SESSION_KEY = stringPreferencesKey("scrobble_lastfm_session_key")
        val KEY_PENDING_QUEUE = stringPreferencesKey("scrobble_pending_queue_v1")

        /** Standard Last.fm API 2.0 MD5 signature: `MD5(sorted(k+v) + secret)`. */
        fun computeLastFmSignature(params: Map<String, String>, secret: String): String {
            val base = buildString {
                params.entries.sortedBy { it.key }.forEach { (k, v) ->
                    append(k)
                    append(v)
                }
                append(secret)
            }
            val digest = MessageDigest.getInstance("MD5").digest(base.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
