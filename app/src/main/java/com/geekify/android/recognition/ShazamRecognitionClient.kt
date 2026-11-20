package com.geekify.android.recognition

import com.geekify.android.innertube.YouTube
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class NoRecognitionMatchException(message: String) : IllegalStateException(message)
class RecognitionUserException(message: String) : IllegalStateException(message)

/**
 * Rate-limited, retry-capable Shazam discovery client.
 * Note: Uses Shazam's public Android discovery endpoint; availability is subject to upstream Shazam changes.
 */
class ShazamRecognitionClient(
    baseHttp: OkHttpClient,
    private val cache: RecognitionResultCache = RecognitionResultCache()
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val client = baseHttp.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    private val userAgents = listOf(
        "Dalvik/2.1.0 (Linux; U; Android 13; Pixel 7 Build/TQ3A)",
        "Dalvik/2.1.0 (Linux; U; Android 12; SM-G991B Build/SP1A)",
        "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/UQ1A)"
    )
    private val timezones = listOf(
        "Europe/London", "Europe/Paris", "America/New_York", "America/Los_Angeles", "Asia/Tokyo"
    )

    suspend fun recognize(signature: String, sampleDurationMs: Long): Result<RecognitionResult> =
        withContext(Dispatchers.IO) {
            cache.get(signature)?.let { return@withContext Result.success(it) }

            mutex.withLock {
                val elapsed = System.currentTimeMillis() - lastRequestAt
                if (elapsed < MIN_REQUEST_INTERVAL_MS) {
                    delay(MIN_REQUEST_INTERVAL_MS - elapsed)
                }
                lastRequestAt = System.currentTimeMillis()
            }

            repeat(3) { attempt ->
                try {
                    val result = performRecognition(signature, sampleDurationMs)
                    cache.put(signature, result)
                    return@withContext Result.success(result)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    if (attempt == 2 || e is NoRecognitionMatchException || e is RecognitionUserException) {
                        return@withContext Result.failure(e)
                    }
                    delay(1000L * (attempt + 1))
                }
            }
            Result.failure(IllegalStateException("Music recognition failed."))
        }

    private suspend fun performRecognition(signature: String, sampleDurationMs: Long): RecognitionResult {
        val timestamp = System.currentTimeMillis() / 1000
        val body = buildJsonObject {
            put("geolocation", buildJsonObject {
                put("altitude", Random.nextDouble(100.0, 500.0))
                put("latitude", Random.nextDouble(-90.0, 90.0))
                put("longitude", Random.nextDouble(-180.0, 180.0))
            })
            put("signature", buildJsonObject {
                put("samplems", sampleDurationMs)
                put("timestamp", timestamp)
                put("uri", signature)
            })
            put("timestamp", timestamp)
            put("timezone", timezones.random())
        }.toString()

        val hl = YouTube.locale.hl.substringBefore('-').ifBlank { "en" }
        val gl = YouTube.locale.gl.ifBlank { "US" }
        val url = "https://amp.shazam.com/discovery/v5/$hl/$gl/android/-/tag/" +
            "${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID()}" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgents.random())
            .header("Content-Language", "${hl}_$gl")
            .post(body.toRequestBody(mediaType))
            .build()

        val (code, responseText) = client.newCall(request).readCancellable()
        if (code !in 200..299) {
            when (code) {
                404 -> throw NoRecognitionMatchException("No matching song found.")
                429 -> throw RecognitionUserException("Too many recognition requests. Please wait a moment.")
                in 500..599 -> throw RecognitionUserException("Recognition service temporarily unavailable.")
                else -> throw IllegalStateException("Shazam request failed with HTTP $code")
            }
        }
        return parseResult(responseText) ?: throw NoRecognitionMatchException("No matching song found.")
    }

    fun parseResult(responseText: String): RecognitionResult? = runCatching {
        val root = json.parseToJsonElement(responseText).jsonObject
        val track = root["track"]?.jsonObject ?: return null
        val sections = track["sections"]?.jsonArray.orEmpty().mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        val metadata = sections
            .firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "SONG" }
            ?.get("metadata")?.jsonArray.orEmpty()
            .mapNotNull { item ->
                val obj = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                title to obj["text"]?.jsonPrimitive?.contentOrNull
            }.toMap()

        val hub = runCatching { track["hub"]?.jsonObject }.getOrNull()
        val hubOptions = hub?.get("options")?.jsonArray.orEmpty().mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        val hubProviders = hub?.get("providers")?.jsonArray.orEmpty().mapNotNull { runCatching { it.jsonObject }.getOrNull() }

        val youtubeVideoId = hubOptions
            .firstOrNull {
                it["type"]?.jsonPrimitive?.contentOrNull?.contains("video", true) == true
            }?.get("actions")?.jsonArray?.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() }
            ?.get("uri")?.jsonPrimitive?.contentOrNull
            ?.let { extractYouTubeVideoId(it) }

        val spotifyUrl = hubProviders
            .firstOrNull {
                it["caption"]?.jsonPrimitive?.contentOrNull?.contains("spotify", true) == true
            }?.get("actions")?.jsonArray?.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() }
            ?.get("uri")?.jsonPrimitive?.contentOrNull

        val appleUrl = hubOptions
            .firstOrNull {
                it["providername"]?.jsonPrimitive?.contentOrNull?.contains("apple", true) == true
            }?.get("actions")?.jsonArray?.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() }
            ?.get("uri")?.jsonPrimitive?.contentOrNull

        val title = track["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (title.isBlank()) return null

        RecognitionResult(
            trackId = track["key"]?.jsonPrimitive?.contentOrNull
                ?: root["tagid"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            title = title,
            artist = track["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            album = metadata["Album"],
            coverArtUrl = track["images"]?.let { runCatching { it.jsonObject }.getOrNull() }?.get("coverart")?.jsonPrimitive?.contentOrNull,
            coverArtHqUrl = track["images"]?.let { runCatching { it.jsonObject }.getOrNull() }?.get("coverarthq")?.jsonPrimitive?.contentOrNull,
            genre = track["genres"]?.let { runCatching { it.jsonObject }.getOrNull() }?.get("primary")?.jsonPrimitive?.contentOrNull,
            releaseDate = metadata["Released"],
            label = metadata["Label"],
            shazamUrl = track["url"]?.jsonPrimitive?.contentOrNull,
            appleMusicUrl = appleUrl,
            spotifyUrl = spotifyUrl,
            isrc = track["isrc"]?.jsonPrimitive?.contentOrNull,
            youtubeVideoId = youtubeVideoId
        )
    }.getOrNull()

    private fun extractYouTubeVideoId(uri: String): String? {
        val regex = Regex("(?:v=|youtu\\.be/|shorts/)([A-Za-z0-9_-]{11})")
        return regex.find(uri)?.groupValues?.getOrNull(1)
    }

    private suspend fun Call.readCancellable(): Pair<Int, String> =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        val text = runCatching { res.body.string() }.getOrDefault("")
                        if (continuation.isActive) continuation.resume(res.code to text)
                    }
                }
            })
        }

    private companion object {
        const val MIN_REQUEST_INTERVAL_MS = 1200L
    }
}
