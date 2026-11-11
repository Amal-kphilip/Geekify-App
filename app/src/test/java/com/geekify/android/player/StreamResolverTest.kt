package com.geekify.android.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class StreamResolverTest {

    @Test
    fun `resolve caches valid stream and deduplicates concurrent in-flight and prefetch requests`() = runBlocking {
        val nowMs = AtomicLong(1_000_000L)
        val fetchCount = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val resolver = StreamResolver(
            extractor = null,
            clock = { nowMs.get() },
            customFetcher = { videoId ->
                fetchCount.incrementAndGet()
                gate.await()
                StreamResolver.ResolvedStream(
                    url = "https://stream.example/$videoId.m4a",
                    mimeType = "audio/mp4",
                    expiresAt = nowMs.get() + 30 * 60_000L,
                    clientName = "ANDROID_VR"
                )
            }
        )

        // Two concurrent resolve calls + one prefetch for the same videoId while in-flight
        val first = async { resolver.resolve("vid_1") }
        val second = async { resolver.resolve("vid_1") }
        delay(20)
        resolver.prefetch(listOf("vid_1", "vid_1"))

        gate.complete(Unit)

        val r1 = first.await()
        val r2 = second.await()
        assertNotNull(r1)
        assertEquals(r1.url, r2.url)
        assertEquals("Concurrent requests for the same track must be deduplicated into 1 fetch", 1, fetchCount.get())

        // Subsequent resolve within TTL hits cache without fetching
        val r3 = resolver.resolve("vid_1")
        assertEquals(r1.url, r3.url)
        assertEquals(1, fetchCount.get())

        // Prefetching an already-cached valid track must be skipped
        resolver.prefetch(listOf("vid_1"))
        delay(30)
        assertEquals("Prefetch must skip valid cached tracks", 1, fetchCount.get())

        // Advance clock past expiration window -> cache expires and re-fetches
        nowMs.addAndGet(31 * 60_000L)
        val r4 = resolver.resolve("vid_1")
        assertNotNull(r4)
        assertEquals("Expired cache entry must trigger a fresh fetch", 2, fetchCount.get())
    }

    @Test
    fun `invalidate and markFailed cancel stale in-flight resolution and force fresh fetch`() = runBlocking {
        val nowMs = AtomicLong(5_000_000L)
        val attemptCounter = AtomicInteger(0)
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()

        val resolver = StreamResolver(
            extractor = null,
            clock = { nowMs.get() },
            customFetcher = { videoId ->
                val attempt = attemptCounter.incrementAndGet()
                if (attempt == 1) {
                    firstStarted.complete(Unit)
                    firstGate.await()
                }
                StreamResolver.ResolvedStream(
                    url = "https://stream.example/$videoId/attempt-$attempt.m4a",
                    mimeType = "audio/mp4",
                    expiresAt = nowMs.get() + 30 * 60_000L,
                    clientName = "ANDROID_VR"
                )
            }
        )

        val staleJob = async { runCatching { resolver.resolve("track_stale") }.getOrNull() }
        firstStarted.await()

        // Mark failed while first attempt is still in-flight -> cancels stale job
        resolver.markFailed("track_stale")
        firstGate.complete(Unit)
        val staleResult = staleJob.await()
        assertNull("Cancelled stale resolution should not produce a result", staleResult)

        // Fresh resolution succeeds with attempt 2
        val freshResult = resolver.resolve("track_stale", forceRefresh = true)
        assertNotNull(freshResult)
        assertTrue(freshResult.url.endsWith("attempt-2.m4a"))
    }

    @Test
    fun `prefetch cancels obsolete in-flight prefetches when upcoming queue changes without cancelling active foreground resolve`() = runBlocking {
        val nowMs = AtomicLong(10_000_000L)
        val obsoleteStarted = CompletableDeferred<Unit>()
        val obsoleteGate = CompletableDeferred<Unit>()
        val obsoleteCancelled = CompletableDeferred<Boolean>()
        val activeGate = CompletableDeferred<Unit>()
        val fetchCounts = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

        val resolver = StreamResolver(
            extractor = null,
            clock = { nowMs.get() },
            customFetcher = { videoId ->
                fetchCounts.computeIfAbsent(videoId) { AtomicInteger(0) }.incrementAndGet()
                when (videoId) {
                    "obsolete_next" -> {
                        obsoleteStarted.complete(Unit)
                        try {
                            obsoleteGate.await()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            obsoleteCancelled.complete(true)
                            throw e
                        }
                    }
                    "foreground_track" -> {
                        activeGate.await()
                    }
                }
                StreamResolver.ResolvedStream(
                    url = "https://stream.example/$videoId.m4a",
                    mimeType = "audio/mp4",
                    expiresAt = nowMs.get() + 30 * 60_000L,
                    clientName = "ANDROID_VR"
                )
            }
        )

        // Start foreground resolve + prefetch for upcoming ["obsolete_next", "foreground_track"]
        val foregroundDeferred = async { resolver.resolve("foreground_track") }
        resolver.prefetch(listOf("obsolete_next", "foreground_track"))
        obsoleteStarted.await()

        // Upcoming queue changes to ["replacement_next"] -> "obsolete_next" prefetch is cancelled,
        // while "foreground_track" continues because it is a foreground resolve.
        resolver.prefetch(listOf("replacement_next"))

        assertTrue("Obsolete prefetch must be cancelled when upcoming queue changes", obsoleteCancelled.await())
        activeGate.complete(Unit)
        val foregroundStream = foregroundDeferred.await()
        assertEquals("https://stream.example/foreground_track.m4a", foregroundStream.url)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Test
    fun testAllClientProfiles() = runBlocking {
        val videoId = "5CljtFNJUDw"
        val profiles = listOf(
            Profile("ANDROID_19_26", "ANDROID", "19.26.35", "3", "com.google.android.youtube/19.26.35 (Linux; U; Android 11) gzip", 30),
            Profile("ANDROID_TESTSUITE", "ANDROID_TESTSUITE", "1.9", "3", "com.google.android.youtube/1.9 (Linux; U; Android 11) gzip", 30),
            Profile("IOS_19_29", "IOS", "19.29.1", "5", "com.google.ios.youtube/19.29.1 (iPhone14,3; U; CPU iOS 17_5_1 like Mac OS X; en_US)", deviceModel = "iPhone14,3"),
            Profile("ANDROID_MUSIC", "ANDROID_MUSIC", "6.42.52", "21", "com.google.android.apps.youtube.music/6.42.52 (Linux; U; Android 14) gzip", 34),
            Profile("TVHTML5", "TVHTML5", "7.20240101.00.00", "85", "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/537.36")
        )

        for (p in profiles) {
            println("--- Testing ${p.name} ---")
            try {
                val body = buildJsonObject {
                    put("context", buildJsonObject {
                        put("client", buildJsonObject {
                            put("clientName", p.clientName)
                            put("clientVersion", p.clientVersion)
                            put("hl", "en")
                            put("gl", "US")
                            if (p.sdkVersion != null) put("androidSdkVersion", p.sdkVersion)
                            if (p.deviceModel != null) put("deviceModel", p.deviceModel)
                        })
                    })
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                    put("playbackContext", buildJsonObject {
                        put("contentPlaybackContext", buildJsonObject {
                            put("html5Preference", "HTML5_PREF_WANTS")
                        })
                    })
                }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

                val req = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", p.userAgent)
                    .header("X-YouTube-Client-Name", p.clientId)
                    .header("X-YouTube-Client-Version", p.clientVersion)
                    .post(body)
                    .build()

                val resp = http.newCall(req).execute()
                val respText = resp.body.string()
                println("${p.name} HTTP ${resp.code}")
                if (resp.isSuccessful) {
                    val obj = json.parseToJsonElement(respText).jsonObject
                    val playability = obj["playabilityStatus"]?.jsonObject
                    println("Playability: ${playability?.get("status")}")
                    val streaming = obj["streamingData"]?.jsonObject
                    if (streaming != null) {
                        val adaptive = streaming["adaptiveFormats"]?.jsonArray
                        val withUrl = adaptive?.filter { (it as? JsonObject)?.get("url") != null }
                        println("SUCCESS: streamingData present! Formats: ${adaptive?.size}, with direct URL: ${withUrl?.size}")
                    } else {
                        println("NO streamingData. Keys: ${obj.keys}")
                    }
                } else {
                    println("Error response: $respText")
                }
            } catch (e: Exception) {
                println("Exception: ${e.message}")
            }
        }
    }

    data class Profile(
        val name: String,
        val clientName: String,
        val clientVersion: String,
        val clientId: String,
        val userAgent: String,
        val sdkVersion: Int? = null,
        val deviceModel: String? = null
    )
}
