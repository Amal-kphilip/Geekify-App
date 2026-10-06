package com.geekify.android.player

import com.geekify.android.data.source.innertube.InnerTubeClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    private val innerTube: InnerTubeClient,
    private val cipher: StreamCipher
) : InnerTubeXPlayer {
    /** [loudnessDb] is YouTube's measured loudness for the track (dB relative to its target), when it reports one. */
    data class ResolvedStream(val url: String, val mimeType: String, val expiresAt: Long, val loudnessDb: Float? = null, val itag: Int? = null)

    private val cache = HashMap<String, ResolvedStream>()
    private val inFlight = HashMap<String, Deferred<ResolvedStream>>()
    /** Formats (itags) the server rejected for a video; skipped on the next lookup so a retry does not hit the same 403 again. */
    private val failedItags = HashMap<String, MutableSet<Int>>()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Returns a playable stream. A lookup that is already running (for example the prefetch of the
     * very song the user just tapped) is shared instead of starting a second request. Cancelling the
     * caller never cancels the shared lookup.
     */
    override suspend fun resolve(videoId: String, forceRefresh: Boolean): ResolvedStream {
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
    override fun prefetch(videoIds: List<String>) {
        videoIds.forEach { id -> scope.launch { runCatching { resolve(id) } } }
    }

    override fun invalidate(videoId: String) {
        synchronized(lock) { cache.remove(videoId) }
    }

    override fun markFailed(videoId: String) {
        synchronized(lock) {
            cache.remove(videoId)?.itag?.let { failedItags.getOrPut(videoId) { HashSet() }.add(it) }
        }
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

        // Audio-only formats. Most carry a plain URL; one that only has a signatureCipher is handed to the
        // installed [StreamCipher] (a no-op unless a cipher library was plugged in) and skipped if it fails.
        val audioFormats = adaptive.filter { f ->
            (f["mimeType"]?.jsonPrimitive?.contentOrNull ?: "").startsWith("audio/")
        }
        val urlByFormat = HashMap<JsonObject, String>()
        for (f in audioFormats) {
            val u = f["url"]?.jsonPrimitive?.contentOrNull ?: runCatching { cipher.decipher(videoId, f) }.getOrNull()
            if (!u.isNullOrBlank()) urlByFormat[f] = u
        }
        val usable = audioFormats.filter { it in urlByFormat }
        // Skip formats the server already refused for this video; if that leaves nothing, start over with all of them.
        val failed = synchronized(lock) { failedItags[videoId]?.toSet().orEmpty() }
        val audio = usable.filter { itagOf(it) !in failed }.ifEmpty {
            synchronized(lock) { failedItags.remove(videoId) }
            usable
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

        val url = urlByFormat[best] ?: best["url"]?.jsonPrimitive?.contentOrNull
            ?: throw StreamException("The audio format has no URL.")
        val mime = (best["mimeType"]?.jsonPrimitive?.contentOrNull ?: "audio/mp4").substringBefore(";")

        return ResolvedStream(url, mime, expiryOf(streaming, url), loudnessOf(raw), itagOf(best))
    }

    private fun itagOf(format: JsonObject): Int? = format["itag"]?.jsonPrimitive?.intOrNull

    /** `playerConfig.audioConfig.loudnessDb`: used by volume normalisation. Any missing / odd value is just null. */
    private fun loudnessOf(raw: JsonObject): Float? {
        val config = (raw["playerConfig"] as? JsonObject)?.get("audioConfig") as? JsonObject
        val value = config?.get("loudnessDb") as? JsonPrimitive ?: return null
        return (value.doubleOrNull ?: value.contentOrNull?.toDoubleOrNull())?.toFloat()?.takeIf { it.isFinite() }
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
