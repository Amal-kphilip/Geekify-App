package com.geekify.android.player

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Test
import java.util.concurrent.TimeUnit

class StreamResolverTest {

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
