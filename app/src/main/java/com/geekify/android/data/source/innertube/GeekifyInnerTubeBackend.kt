package com.geekify.android.data.source.innertube

import com.geekify.android.innertube.YouTube
import com.geekify.android.innertube.models.YouTubeClient
import com.geekify.android.innertube.models.YouTubeLocale
import com.geekify.android.settings.AppSettingsRepository
import com.metrolist.innertubex.InnerTube as InnerTubeXClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Geekify-facing adapter to the InnerTubeX request backend. It keeps Geekify's existing
 * JSON parsers/domain models, while using the upstream client's request construction, session
 * handling, HTTP status checks and transient-request behavior for discovery endpoints.
 */
@Singleton
class GeekifyInnerTubeBackend @Inject constructor(
    private val appSettings: AppSettingsRepository
) {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var lastLocale: YouTubeLocale? = null

    init {
        val initial = appSettings.state.value
        val locale = YouTubeLocale(hl = initial.contentLanguage, gl = initial.contentCountry)
        YouTube.locale = locale
        lastLocale = locale
        runCatching {
            YouTube.extractionTransport().innerTube.regionOverrideActive = true
        }
    }

    suspend fun search(query: String, type: String?): JsonObject = execute("search") { innerTube ->
        innerTube.search(
            client = YouTubeClient.WEB_REMIX,
            query = query,
            params = type?.let { SEARCH_PARAMS.getValue(it) }
        )
    }

    suspend fun browse(id: String): JsonObject = execute("browse") { innerTube ->
        innerTube.browse(client = YouTubeClient.WEB_REMIX, browseId = id)
    }

    suspend fun next(videoId: String, playlistId: String?): JsonObject = execute("next") { innerTube ->
        innerTube.next(
            client = YouTubeClient.WEB_REMIX,
            videoId = videoId,
            playlistId = playlistId,
            playlistSetVideoId = null,
            index = null,
            params = null
        )
    }

    suspend fun warmUp() {
        try {
            val innerTube = YouTube.extractionTransport().innerTube
            innerTube.regionOverrideActive = true
            innerTube.fetchFreshVisitorData()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Visitor data is opportunistic; requests can still use the library's anonymous fallback.
        }
    }

    private suspend fun syncLocale(innerTube: InnerTubeXClient) {
        val s = appSettings.state.value
        val targetLocale = YouTubeLocale(hl = s.contentLanguage, gl = s.contentCountry)
        innerTube.regionOverrideActive = true
        if (lastLocale != targetLocale || YouTube.locale != targetLocale) {
            lastLocale = targetLocale
            YouTube.locale = targetLocale
            YouTube.visitorData = null
            try {
                innerTube.fetchFreshVisitorData()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun execute(
        endpoint: String,
        request: suspend (InnerTubeXClient) -> HttpResponse
    ): JsonObject {
        var lastError: Throwable? = null
        val innerTube = YouTube.extractionTransport().innerTube
        syncLocale(innerTube)
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val response = request(innerTube)
                val status = response.status.value
                val body = withContext(Dispatchers.IO) { response.bodyAsText() }
                if (status !in 200..299) {
                    throw InnerTubeClient.InnerTubeException(
                        "YouTube $endpoint request failed ($status).",
                        retryable = status == 429 || status >= 500
                    )
                }
                return withContext(Dispatchers.Default) { json.parseToJsonElement(body).jsonObject }
            } catch (error: CancellationException) {
                throw error
            } catch (error: InnerTubeClient.InnerTubeException) {
                if (!error.retryable || attempt == MAX_ATTEMPTS - 1) throw error
                lastError = error
            } catch (error: Exception) {
                val retryable = generateSequence<Throwable>(error) { it.cause }.any {
                    it is IOException || it is java.net.SocketTimeoutException ||
                        it is java.net.UnknownHostException || it is java.net.ConnectException
                }
                if (!retryable || attempt == MAX_ATTEMPTS - 1) {
                    throw InnerTubeClient.InnerTubeException(
                        "Could not contact YouTube for $endpoint. Check your connection and try again.",
                        retryable,
                        error
                    )
                }
                lastError = error
            }
            if (attempt < MAX_ATTEMPTS - 1) delay(500L * (1L shl attempt))
        }
        throw InnerTubeClient.InnerTubeException(
            "YouTube $endpoint request failed after retries.",
            retryable = true,
            cause = lastError
        )
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        val SEARCH_PARAMS = mapOf(
            "song" to "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D",
            "album" to "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D",
            "artist" to "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D",
            "playlist" to "EgWKAQJQAWoKEAkQChAFEAMQBA%3D%3D"
        )
    }
}
