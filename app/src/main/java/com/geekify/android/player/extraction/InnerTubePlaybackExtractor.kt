package com.geekify.android.player.extraction

import android.content.Context
import android.net.ConnectivityManager
import com.geekify.android.player.potoken.PoTokenGenerator
import com.geekify.android.innertube.YouTube
import com.geekify.android.innertube.models.YouTubeLocale
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality as InnerTubeXAudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter around the InnerTubeX extractor. It owns stream-client fallback,
 * signature deciphering, PO-token generation and player-config refresh while Geekify's
 * existing Media3 service and queue remain the playback owner.
 */
@Singleton
class InnerTubePlaybackExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class PlaybackStream(
        val url: String,
        val mimeType: String,
        val expiresAtMs: Long,
        val loudnessDb: Float?,
        val itag: Int?,
        val clientName: String,
        val requestHeaders: Map<String, String>,
        val requireBoundedRange: Boolean,
        val rangeChunkSizeBytes: Long,
        val useRangeChunks: Boolean
    )

    private companion object {
        const val TAG = "InnerTubePlaybackExtractor"
        const val STREAM_CLIENT_FAILURE_TTL_MS = 5 * 60 * 1000L
        const val DEFAULT_STREAM_TTL_MS = 5 * 60 * 1000L
        const val PLAYER_CONFIG_URL =
            "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
    }

    init {
        // Preserve the English/India locale used by Geekify's existing InnerTube requests.
        YouTube.locale = YouTubeLocale(hl = "en", gl = "IN")
    }

    private val bundleMutex = Mutex()
    @Volatile private var currentBundle: ExtractionBundle? = null
    private val streamClientFailures = ConcurrentHashMap<String, FailedStreamClients>()
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val configRepository: PlayerConfigRepository by lazy { AndroidPlayerConfigRepository(context) }
    private val poTokenGenerator: PoTokenGenerator by lazy { PoTokenGenerator(context) }

    private val tokenProvider = object : TokenProvider {
        override val capabilities = TokenProviderCapabilities(
            providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
            usesWebView = true
        )

        override suspend fun getPoToken(
            videoId: String,
            visitorData: String,
            cookie: String?
        ): PoTokenResult? = poTokenGenerator.getWebClientPoToken(videoId, visitorData)?.let { token ->
            PoTokenResult(
                playerRequestToken = token.playerRequestPoToken,
                streamingDataToken = token.streamingDataPoToken,
                visitorData = visitorData
            )
        }

        override suspend fun close() {
            poTokenGenerator.close()
        }
    }

    private val logger = InnerTubeLogger { event ->
        val details = event.details.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
        val message = event.message + details.takeUnless { event.details.isEmpty() }.orEmpty()
        when (event.level) {
            InnerTubeLogLevel.DEBUG -> Timber.tag(event.tag).d(message)
            InnerTubeLogLevel.INFO -> Timber.tag(event.tag).i(message)
            InnerTubeLogLevel.WARN -> Timber.tag(event.tag).w(message)
            InnerTubeLogLevel.ERROR -> Timber.tag(event.tag).e(message)
        }
    }

    suspend fun prewarm() {
        bundle().extractor.prewarm()
    }

    suspend fun resolve(videoId: String): PlaybackStream {
        val stream = try {
            requireNotNull(
                bundle().extractor.extract(
                    videoId = videoId,
                    hints = ContentHints().withStreamCapabilities(
                        allowHls = false,
                        allowSabr = false,
                        allowBoundedRange = true
                    ),
                    excludedClients = failedStreamClients(videoId),
                    audioQuality = if (connectivityManager.isActiveNetworkMetered) {
                        InnerTubeXAudioQuality.LOW
                    } else {
                        InnerTubeXAudioQuality.AUTO
                    },
                    clientPlaybackNonce = generateClientPlaybackNonce()
                )
            ) { "InnerTubeX returned no playable stream" }
        } catch (error: CancellationException) {
            throw error
        } catch (error: StreamResolveException) {
            val cause = error.cause
            if (error.reason == StreamResolveException.Reason.NETWORK && cause != null) throw cause
            throw error
        }

        check(stream.sabrBootstrap == null) { "The selected stream requires an unsupported SABR session" }
        val nowMs = System.currentTimeMillis()
        val expiresAtMs = stream.expiresAt
            ?.toEpochMilliseconds()
            ?.coerceAtLeast(nowMs + 1_000L)
            ?: (nowMs + DEFAULT_STREAM_TTL_MS)
        val fullMimeType = if (stream.codecs.isNullOrBlank()) {
            stream.mimeType.orEmpty()
        } else {
            "${stream.mimeType.orEmpty()}; codecs=\"${stream.codecs}\""
        }
        return PlaybackStream(
            url = stream.audioUrl,
            mimeType = fullMimeType.substringBefore(';').ifBlank { "audio/mp4" },
            expiresAtMs = expiresAtMs,
            loudnessDb = stream.loudnessDb?.toFloat(),
            itag = stream.itag,
            clientName = stream.clientName,
            requestHeaders = stream.headers.toMap(),
            requireBoundedRange = stream.requireBoundedRange,
            rangeChunkSizeBytes = stream.rangeChunkSizeBytes,
            useRangeChunks = stream.useRangeChunks
        )
    }

    fun markStreamClientFailed(videoId: String, clientName: String) {
        if (clientName.isBlank()) return
        streamClientFailures.compute(videoId) { _, current ->
            FailedStreamClients(current?.clientNames.orEmpty() + clientName, System.currentTimeMillis())
        }
    }

    suspend fun refreshAfterStreamRejection(): Boolean = bundle().cipherService.refreshAfterStreamRejection()

    private fun failedStreamClients(videoId: String, nowMs: Long = System.currentTimeMillis()): Set<String> {
        val failed = streamClientFailures[videoId] ?: return emptySet()
        if ((nowMs - failed.failedAtMs) !in 0 until STREAM_CLIENT_FAILURE_TTL_MS) {
            streamClientFailures.remove(videoId, failed)
            return emptySet()
        }
        return failed.clientNames
    }

    private suspend fun bundle(): ExtractionBundle {
        val transport = YouTube.extractionTransport()
        currentBundle?.takeIf { it.transportGeneration == transport.generation }?.let { return it }
        return bundleMutex.withLock {
            val latestTransport = YouTube.extractionTransport()
            currentBundle?.takeIf { it.transportGeneration == latestTransport.generation }?.let { return@withLock it }
            try {
                currentBundle?.cipherService?.dispose()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.tag(TAG).w("Could not dispose prior cipher service (${error::class.simpleName})")
            }

            val remoteStore = RemotePlayerConfigStore(latestTransport.httpClient, configRepository, logger)
            val cipherService = YouTubeCipherService(latestTransport.httpClient, remoteStore, logger)
            val extractor = InnerTubeExtractor(
                configParser = YtConfigParserImpl(
                    latestTransport.httpClient,
                    latestTransport.innerTube,
                    remoteStore,
                    logger
                ).withEmbeddedConfigFallback(),
                cipherService = cipherService,
                innerTube = latestTransport.innerTube,
                tokenProvider = tokenProvider,
                logger = logger
            )
            ExtractionBundle(latestTransport.generation, cipherService, extractor).also { currentBundle = it }
        }
    }

    private class AndroidPlayerConfigRepository(context: Context) : PlayerConfigRepository {
        private val preferences = context.getSharedPreferences("innertubex_player_config", Context.MODE_PRIVATE)
        private companion object {
            const val PLAYER_CONFIG_URL =
                "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
        }
        override val enabled: Boolean = true
        override val sourceUrl: String = PLAYER_CONFIG_URL
        override val defaultSourceUrl: String = PLAYER_CONFIG_URL
        override var cachedJson: String
            get() = preferences.getString("json", "").orEmpty()
            set(value) { preferences.edit().putString("json", value).apply() }
        override var cachedAtMs: Long
            get() = preferences.getLong("cached_at_ms", 0L)
            set(value) { preferences.edit().putLong("cached_at_ms", value).apply() }
        override var cachedSourceUrl: String
            get() = preferences.getString("source_url", "").orEmpty()
            set(value) { preferences.edit().putString("source_url", value).apply() }
        override var cachedEtag: String
            get() = preferences.getString("etag", "").orEmpty()
            set(value) { preferences.edit().putString("etag", value).apply() }
    }

    private fun YtConfigParser.withEmbeddedConfigFallback(): YtConfigParser = object : YtConfigParser by this {
        override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean) = try {
            this@withEmbeddedConfigFallback.fetchConfig(videoId, useLoginCookies)
        } catch (_: IllegalStateException) {
            this@withEmbeddedConfigFallback.fetchEmbeddedConfig(videoId, useLoginCookies = false)
        }
    }

    private data class ExtractionBundle(
        val transportGeneration: Long,
        val cipherService: YouTubeCipherService,
        val extractor: InnerTubeExtractor
    )

    private data class FailedStreamClients(
        val clientNames: Set<String>,
        val failedAtMs: Long
    )
}
