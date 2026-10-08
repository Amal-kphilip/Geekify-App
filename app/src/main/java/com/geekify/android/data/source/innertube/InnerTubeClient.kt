package com.geekify.android.data.source.innertube

import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InnerTubeClient @Inject constructor(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private data class Config(val key: String, val version: String)
    @Volatile private var config: Config? = null

    /** Anonymous visitor id; the player endpoint wants it (taken from the music page, then from any response). */
    @Volatile private var visitorData: String? = null

    suspend fun search(query: String, type: String?): JsonObject = call("search", buildJsonObject {
        put("query", query)
        type?.let { put("params", SEARCH_PARAMS.getValue(it)) }
    })
    suspend fun browse(id: String): JsonObject = call("browse", buildJsonObject { put("browseId", id) })
    suspend fun next(videoId: String, playlistId: String? = null): JsonObject = call("next", buildJsonObject {
        put("videoId", videoId); playlistId?.let { put("playlistId", it) }
    })

    /**
     * Player response used for **audio stream URLs**.
     *
     * Uses the VISIONOS client: as of 2026 it returns plain (cipher-free) audio URLs anonymously,
     * needs no PO token, and its URLs accept normal open-ended range requests (which is what ExoPlayer
     * sends). The older ANDROID_VR / IOS / ANDROID_TESTSUITE clients now need PO tokens for audio-only
     * formats, and ANDROID_VR URLs answer ExoPlayer's first read with HTTP 403.
     *
     * The URLs must be fetched with [PLAYER_USER_AGENT]; googlevideo checks it.
     * If YouTube retires this client, bump [VISIONOS_VERSION] (see yt-dlp's INNERTUBE_CLIENTS).
     */
    suspend fun playerStreams(videoId: String): JsonObject {
        var last: Throwable? = null
        // Primary client first; fall back to YouTube's embedded web player when the
        // primary client is blocked by a bot/login challenge for this video or IP.
        val clients = listOf(::callPlayerVisionOs, ::callPlayerWebEmbedded)
        for ((clientIndex, client) in clients.withIndex()) {
            repeat(2) { attempt ->
                try {
                    val response = client(videoId)
                    val status = response["playabilityStatus"]?.jsonObject
                    val statusCode = status?.get("status")?.jsonPrimitive?.contentOrNull
                    val hasStreams = response["streamingData"]?.jsonObject != null
                    if ((statusCode == null || statusCode == "OK") && hasStreams) {
                        return response
                    }
                    val reason = status?.get("reason")?.jsonPrimitive?.contentOrNull ?: statusCode
                    last = InnerTubeException(reason ?: "YouTube returned no playable stream.", false)
                } catch (t: Throwable) {
                    last = t
                    if (t is InnerTubeException && !t.retryable && clientIndex == 1) break
                }
                if (attempt == 0) delay(350)
            }
        }
        throw (last as? InnerTubeException)
            ?: InnerTubeException("YouTube could not provide a playable stream.", true, last)
    }

    /** Metadata (videoDetails) plus streams when available; falls back to the Music web client. */
    suspend fun player(videoId: String): JsonObject =
        try {
            playerStreams(videoId)
        } catch (_: Exception) {
            call("player", buildJsonObject { put("videoId", videoId) })
        }

    private suspend fun callPlayerWebEmbedded(videoId: String): JsonObject {
        val body = buildJsonObject {
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", "WEB_EMBEDDED_PLAYER")
                    put("clientVersion", WEB_EMBEDDED_VERSION)
                    put("userAgent", WEB_EMBEDDED_USER_AGENT)
                    put("hl", "en")
                    put("gl", "IN")
                    visitorData?.let { put("visitorData", it) }
                })
                put("thirdParty", buildJsonObject {
                    put("embedUrl", "https://www.youtube.com/")
                })
            })
            put("videoId", videoId)
            put("racyCheckOk", true)
            put("contentCheckOk", true)
            put("playbackContext", buildJsonObject {
                put("contentPlaybackContext", buildJsonObject { put("html5Preference", "HTML5_PREF_WANTS") })
            })
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("Origin", "https://www.youtube.com")
            .header("User-Agent", WEB_EMBEDDED_USER_AGENT)
            .header("X-YouTube-Client-Name", "56")
            .header("X-YouTube-Client-Version", WEB_EMBEDDED_VERSION)
            .post(body)
            .build()

        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use {
                val text = it.body.string()
                if (it.isSuccessful) {
                    json.parseToJsonElement(text).jsonObject.also { obj -> rememberVisitor(obj) }
                } else {
                    throw InnerTubeException(
                        "YouTube embedded player refused the stream request (${it.code}).",
                        it.code >= 500 || it.code == 429
                    )
                }
            }
        }
    }

    private suspend fun callPlayerVisionOs(videoId: String): JsonObject {
        // The visitor id is optional. Never make the first tap wait on the (large) Music homepage
        // download for it: give it a short window, otherwise continue without it.
        if (visitorData == null) runCatching { withTimeoutOrNull(1_500) { getConfig() } }
        val body = buildJsonObject {
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", "VISIONOS")
                    put("clientVersion", VISIONOS_VERSION)
                    put("deviceMake", "Apple")
                    put("deviceModel", "RealityDevice17,1")
                    put("osName", "visionOS")
                    put("osVersion", "26.5.23O471")
                    put("userAgent", PLAYER_USER_AGENT)
                    put("hl", "en")
                    put("gl", "IN")
                    visitorData?.let { put("visitorData", it) }
                })
            })
            put("videoId", videoId)
            put("racyCheckOk", true)
            put("contentCheckOk", true)
            put("playbackContext", buildJsonObject {
                put("contentPlaybackContext", buildJsonObject { put("html5Preference", "HTML5_PREF_WANTS") })
            })
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("Origin", "https://www.youtube.com")
            .header("User-Agent", PLAYER_USER_AGENT)
            .post(body).build()

        // Execute, read the whole body AND parse on the IO pool. Callers (the playback service) run on
        // the main thread, and reading/parsing a ~100 KB player response there froze the UI.
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use {
                val text = it.body.string()
                if (it.isSuccessful) {
                    val obj = json.parseToJsonElement(text).jsonObject
                    rememberVisitor(obj)
                    return@use obj
                }
                throw InnerTubeException("YouTube refused the stream request (${it.code}).", it.code >= 500 || it.code == 429)
            }
        }
    }

    /** Loads the Music config + anonymous visitor id ahead of time so the first song starts faster. */
    suspend fun warmUp() {
        runCatching { getConfig() }
    }

    private fun rememberVisitor(obj: JsonObject) {
        if (visitorData != null) return
        visitorData = obj["responseContext"]?.jsonObject?.get("visitorData")?.jsonPrimitive?.contentOrNull
    }

    private suspend fun call(endpoint: String, payload: JsonObject): JsonObject {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                val c = getConfig()
                val body = buildJsonObject {
                    put("context", buildJsonObject {
                        put("client", buildJsonObject {
                            put("clientName", "WEB_REMIX"); put("clientVersion", c.version)
                            put("hl", "en"); put("gl", "IN")
                        })
                    })
                    payload.forEach { (k, v) -> put(k, v) }
                }.toString().toRequestBody(JSON)
                val request = Request.Builder()
                    .url("https://music.youtube.com/youtubei/v1/$endpoint?key=${c.key}&prettyPrint=false")
                    .header("Content-Type", "application/json")
                    .header("X-YouTube-Client-Name", "67")
                    .header("X-YouTube-Client-Version", c.version)
                    .header("Origin", "https://music.youtube.com")
                    .header("User-Agent", USER_AGENT).post(body).build()
                return withContext(Dispatchers.IO) {
                    http.newCall(request).execute().use {
                        val text = it.body.string()
                        if (it.isSuccessful) return@use json.parseToJsonElement(text).jsonObject.also { o -> rememberVisitor(o) }
                        if (it.code in 400..499 && it.code != 429) throw InnerTubeException("YouTube rejected this request.", false)
                        throw InnerTubeException("YouTube is temporarily unavailable (${it.code}).", true)
                    }
                }
            } catch (t: Throwable) {
                last = t
                if (t is InnerTubeException && !t.retryable) throw t
                if (attempt < 2) delay(700L * (attempt + 1))
            }
        }
        throw InnerTubeException("Could not contact YouTube. Check your connection and try again.", true, last)
    }

    private suspend fun getConfig(): Config = config ?: withContext(Dispatchers.IO) {
        config ?: run {
            val request = Request.Builder().url("https://music.youtube.com/").header("User-Agent", USER_AGENT).build()
            val html = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Music configuration request failed")
                response.body.string()
            }
            val key = Regex("\\\"INNERTUBE_API_KEY\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(html)?.groupValues?.get(1)
            val version = Regex("\\\"INNERTUBE_(?:CONTEXT_)?CLIENT_VERSION\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(html)?.groupValues?.get(1)
            if (key.isNullOrBlank() || version.isNullOrBlank()) throw IOException("YouTube Music configuration changed")
            if (visitorData == null) {
                // Plain JSON ("VISITOR_DATA":"...") or the hex-escaped form (\x22VISITOR_DATA\x22:\x22...\x22).
                val plain = Regex("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"")
                val escaped = Regex("\\\\x22VISITOR_DATA\\\\x22\\s*:\\s*\\\\x22(.+?)\\\\x22")
                visitorData = plain.find(html)?.groupValues?.get(1) ?: escaped.find(html)?.groupValues?.get(1)
            }
            Config(key, version).also { config = it }
        }
    }

    class InnerTubeException(message: String, val retryable: Boolean, cause: Throwable? = null) : IOException(message, cause)
    companion object {
        /** Use this User-Agent when fetching the stream URLs resolved by [playerStreams]. */
        const val PLAYER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

        /** The one number to bump if streams stop resolving (compare with yt-dlp's `visionos` client). */
        private const val VISIONOS_VERSION = "1.02"

        private const val WEB_EMBEDDED_VERSION = "2.20260708.00.00"
        private const val WEB_EMBEDDED_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val SEARCH_PARAMS = mapOf(
            "song" to "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D",
            "album" to "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D",
            "artist" to "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D",
            "playlist" to "EgWKAQJQAWoKEAkQChAFEAMQBA%3D%3D"
        )
    }
}
