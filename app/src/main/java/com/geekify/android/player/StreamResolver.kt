package com.geekify.android.player

import com.geekify.android.data.source.innertube.InnerTubeClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a videoId to a directly playable audio URL, entirely on the phone.
 *
 * The URL comes from the InnerTube VISIONOS player client (see [InnerTubeClient.playerStreams]).
 * ExoPlayer must fetch it with [InnerTubeClient.PLAYER_USER_AGENT].
 */
@Singleton
class StreamResolver @Inject constructor(
    private val innerTube: InnerTubeClient
) {
    data class ResolvedStream(val url: String, val mimeType: String, val expiresAt: Long)

    private val cache = HashMap<String, ResolvedStream>()
    private val inFlight = HashMap<String, Deferred<ResolvedStream>>()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Returns a playable stream. A lookup that is already running (for example the prefetch of the
     * very song the user just tapped) is shared instead of starting a second request. Cancelling the
     * caller never cancels the shared lookup.
     */
    suspend fun resolve(videoId: String, forceRefresh: Boolean = false): ResolvedStream {
        val job: Deferred<ResolvedStream> = synchronized(lock) {
            if (!forceRefresh) {
                val cached = cache[videoId]
                if (cached != null && cached.expiresAt > System.currentTimeMillis()) return cached
                inFlight[videoId]?.takeIf { it.isActive }?.let { return@synchronized it }
            }
            cache.remove(videoId)
            scope.async {
                try {
                    doResolve(videoId).also { r -> synchronized(lock) { cache[videoId] = r } }
                } finally {
                    synchronized(lock) { inFlight.remove(videoId) }
                }
            }.also { inFlight[videoId] = it }
        }
        return job.await()
    }

    /** Fire-and-forget warm-up so a later tap finds the URL already resolved. Errors are ignored. */
    fun prefetch(videoIds: List<String>) {
        videoIds.forEach { id -> scope.launch { runCatching { resolve(id) } } }
    }

    fun invalidate(videoId: String) {
        synchronized(lock) { cache.remove(videoId) }
    }

    private suspend fun doResolve(videoId: String): ResolvedStream {
        val raw: JsonObject = try {
            innerTube.playerStreams(videoId)
        } catch (e: Exception) {
            throw StreamException(e.message ?: "Could not reach YouTube.", e)
        }

        // "OK" means playable; anything else (LOGIN_REQUIRED, UNPLAYABLE, ERROR...) comes with a reason.
        val status = raw["playabilityStatus"]?.jsonObject
        val statusCode = status?.get("status")?.jsonPrimitive?.contentOrNull
        if (statusCode != null && statusCode != "OK") {
            val reason = status["reason"]?.jsonPrimitive?.contentOrNull ?: statusCode
            throw StreamException("YouTube won't play this track: $reason")
        }

        val streaming = raw["streamingData"]?.jsonObject
            ?: throw StreamException("YouTube returned no audio for this track.")

        val adaptive = streaming["adaptiveFormats"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: emptyList()
        val muxed = streaming["formats"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: emptyList()

        fun bitrate(f: JsonObject): Int =
            f["averageBitrate"]?.jsonPrimitive?.intOrNull ?: f["bitrate"]?.jsonPrimitive?.intOrNull ?: 0

        // Audio-only formats that come with a plain URL (no signatureCipher).
        val audio = adaptive.filter { f ->
            f["url"] != null && (f["mimeType"]?.jsonPrimitive?.contentOrNull ?: "").startsWith("audio/")
        }
        // Dubbed videos list several audio tracks: prefer the original/default one.
        val defaultTrack = audio.filter { f ->
            f["audioTrack"]?.jsonObject?.get("audioIsDefault")?.jsonPrimitive?.booleanOrNull != false
        }
        // A 128–160 kbps stream starts substantially faster on mobile data than the highest
        // bitrate format, while still sounding transparent for music playback.
        val best = (defaultTrack.ifEmpty { audio }).minByOrNull { kotlin.math.abs(bitrate(it) - 160_000) }
            // Last resort: a muxed audio+video file (the player has its video track disabled).
            ?: muxed.filter { it["url"] != null }.maxByOrNull(::bitrate)
            ?: throw StreamException("No playable audio format was returned for this track.")

        val url = best["url"]?.jsonPrimitive?.contentOrNull
            ?: throw StreamException("The audio format has no URL.")
        val mime = (best["mimeType"]?.jsonPrimitive?.contentOrNull ?: "audio/mp4").substringBefore(";")

        return ResolvedStream(url, mime, expiryOf(streaming, url))
    }

    /** URLs expire (about 6 h). Prefer YouTube's own value, then the `expire` URL parameter. */
    private fun expiryOf(streaming: JsonObject, url: String): Long {
        val now = System.currentTimeMillis()
        val margin = 5 * 60 * 1000L
        val fallback = now + 4 * 3600 * 1000L
        val seconds = streaming["expiresInSeconds"]?.jsonPrimitive?.let { it.intOrNull?.toDouble() ?: it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
        if (seconds != null && seconds > 0) return minOf(now + (seconds * 1000).toLong() - margin, fallback)
        val epoch = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("/expire/(\\d+)/").find(url)?.groupValues?.get(1)?.toLongOrNull()
        return if (epoch != null) minOf(epoch * 1000 - margin, fallback) else fallback
    }

    class StreamException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
