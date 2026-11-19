package com.geekify.android.player.extraction

import android.content.Context
import android.net.ConnectivityManager
import com.geekify.android.innertube.YouTube
import com.geekify.android.innertube.models.YouTubeLocale
import com.geekify.android.player.potoken.PoTokenGenerator
import com.geekify.android.settings.AppSettingsRepository
import com.geekify.android.settings.AudioQualityPreference
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
import com.metrolist.innertubex.extraction.strategy.ClientFailureKind
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.sabr.SabrBootstrap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import timber.log.Timber

/**
 * Adapter around the InnerTubeX extractor. Owns multi-client fallback, client health scoring,
 * signature deciphering, PO-token generation/invalidation, SABR bootstrap extraction, and
 * player-config refresh while Geekify's Media3 service and queue remain the playback owner.
 */
class InnerTubePlaybackExtractor constructor(
    private val context: Context,
    private val appSettings: AppSettingsRepository,
    private val networkMonitor: PlaybackNetworkMonitor = PlaybackNetworkMonitor(context),
    val clientHealthTracker: ClientHealthTracker = ClientHealthTracker(
        networkScopeProvider = { networkMonitor.current.healthScope() }
    )
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
        val useRangeChunks: Boolean,
        val sabrBootstrap: SabrBootstrap? = null,
        val isHls: Boolean = false,
        val contentLength: Long = -1L
    ) {
        val isSabr: Boolean get() = sabrBootstrap != null
    }

    companion object {
        private const val TAG = "InnerTubePlaybackExtractor"
        private const val STREAM_CLIENT_FAILURE_TTL_MS = 5 * 60 * 1000L
        private const val DEFAULT_STREAM_TTL_MS = 5 * 60 * 1000L
        private const val MAX_CONFIG_JSON_BYTES = 512 * 1024
        const val PINNED_PLAYER_CONFIG_COMMIT = "e9d93a1f77c5f4e615a560f0d90d6e622316f180"
        const val PLAYER_CONFIG_URL =
            "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/$PINNED_PLAYER_CONFIG_COMMIT/library/src/main/assets/player_configs.json"

        private val configJsonParser = Json { ignoreUnknownKeys = true }

        /**
         * Validates downloaded decipher configuration before persisting or applying it.
         * Rejects blank, oversized, HTML, or structurally invalid JSON so the extractor
         * safely falls back to the embedded configuration.
         */
        fun isValidPlayerConfigJson(raw: String): Boolean {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_CONFIG_JSON_BYTES) return false
            if (trimmed.startsWith("<")) return false
            val element = runCatching { configJsonParser.parseToJsonElement(trimmed) }.getOrNull() ?: return false
            return when (element) {
                is JsonObject -> element.isNotEmpty()
                is JsonArray -> element.isNotEmpty() && element.all { it is JsonObject }
                else -> false
            }
        }
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

    private val poTokenCache = ConcurrentHashMap<String, CachedPoToken>()
    private val poTokenCooldownUntilMs = AtomicLong(0L)

    private data class CachedPoToken(
        val result: PoTokenResult,
        val visitorData: String,
        val cookie: String?,
        val expiresAtMs: Long
    )

    val tokenProvider = object : TokenProvider {
        override val capabilities = TokenProviderCapabilities(
            providers = setOf(PoTokenProviderKind.WEB_BOTGUARD, PoTokenProviderKind.WEBPAGE_ATTESTATION),
            usesWebView = true
        )

        override suspend fun getPoToken(
            videoId: String,
            visitorData: String,
            cookie: String?
        ): PoTokenResult? {
            val now = System.currentTimeMillis()
            if (poTokenCooldownUntilMs.get() > now) return null
            val cached = poTokenCache[videoId]
            if (cached != null && cached.expiresAtMs > now &&
                cached.visitorData == visitorData && cached.cookie == cookie
            ) {
                return cached.result
            }
            return try {
                poTokenGenerator.getWebClientPoToken(videoId, visitorData)?.let { token ->
                    val res = PoTokenResult(
                        playerRequestToken = token.playerRequestPoToken,
                        streamingDataToken = token.streamingDataPoToken,
                        visitorData = visitorData
                    )
                    poTokenCache[videoId] = CachedPoToken(
                        result = res,
                        visitorData = visitorData,
                        cookie = cookie,
                        expiresAtMs = now + 10 * 60 * 1000L
                    )
                    res
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "PO token generation failed; entering 30s cooldown")
                poTokenCooldownUntilMs.set(System.currentTimeMillis() + 30_000L)
                null
            }
        }

        override suspend fun invalidateAttestation() {
            poTokenCache.clear()
            poTokenCooldownUntilMs.set(0L)
            runCatching { poTokenGenerator.close() }
        }

        override suspend fun close() {
            poTokenCache.clear()
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

    private val localeLock = Any()

    suspend fun prewarm() {
        bundle().extractor.prewarm()
    }

    suspend fun resolve(videoId: String, preferProgressiveForDownload: Boolean = false): PlaybackStream {
        val currentSettings = appSettings.state.value
        synchronized(localeLock) {
            YouTube.locale = YouTubeLocale(hl = currentSettings.contentLanguage, gl = currentSettings.contentCountry)
        }
        val effectiveAudioQuality = when (currentSettings.audioQuality) {
            AudioQualityPreference.HIGH -> InnerTubeXAudioQuality.HIGH
            AudioQualityPreference.LOW -> InnerTubeXAudioQuality.LOW
            AudioQualityPreference.AUTO -> if (networkMonitor.current.metered) InnerTubeXAudioQuality.LOW else InnerTubeXAudioQuality.AUTO
        }
        val stream = try {
            val extracted = if (preferProgressiveForDownload) {
                runCatching {
                    bundle().extractor.extract(
                        videoId = videoId,
                        hints = ContentHints().withStreamCapabilities(
                            allowHls = false,
                            allowSabr = false,
                            allowBoundedRange = true
                        ),
                        excludedClients = failedStreamClients(videoId),
                        audioQuality = effectiveAudioQuality,
                        clientPlaybackNonce = generateClientPlaybackNonce()
                    )
                }.getOrNull() ?: bundle().extractor.extract(
                    videoId = videoId,
                    hints = ContentHints().withStreamCapabilities(
                        allowHls = false,
                        allowSabr = true,
                        allowBoundedRange = true
                    ),
                    excludedClients = failedStreamClients(videoId),
                    audioQuality = effectiveAudioQuality,
                    clientPlaybackNonce = generateClientPlaybackNonce()
                )
            } else {
                bundle().extractor.extract(
                    videoId = videoId,
                    hints = ContentHints().withStreamCapabilities(
                        allowHls = true,
                        allowSabr = true,
                        allowBoundedRange = true
                    ),
                    excludedClients = failedStreamClients(videoId),
                    audioQuality = effectiveAudioQuality,
                    clientPlaybackNonce = generateClientPlaybackNonce()
                )
            }
            requireNotNull(extracted) { "InnerTubeX returned no playable stream" }
        } catch (error: CancellationException) {
            throw error
        } catch (error: StreamResolveException) {
            val cause = error.cause
            if (error.reason == StreamResolveException.Reason.NETWORK && cause != null) throw cause
            throw error
        }

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
        val cleanMime = fullMimeType.substringBefore(';').ifBlank { "audio/mp4" }
        val isHls = cleanMime.contains("mpegurl", ignoreCase = true) ||
            stream.audioUrl.contains(".m3u8", ignoreCase = true)
        if (stream.clientName.isNotBlank() && !stream.clientName.endsWith("__nopo")) {
            WebPoTokenHealth.recordAccepted()
        }
        return PlaybackStream(
            url = stream.audioUrl,
            mimeType = cleanMime,
            expiresAtMs = expiresAtMs,
            loudnessDb = stream.loudnessDb?.toFloat(),
            itag = stream.itag,
            clientName = stream.clientName,
            requestHeaders = stream.headers.toMap(),
            requireBoundedRange = stream.requireBoundedRange,
            rangeChunkSizeBytes = stream.rangeChunkSizeBytes,
            useRangeChunks = stream.useRangeChunks,
            sabrBootstrap = stream.sabrBootstrap,
            isHls = isHls,
            contentLength = -1L
        )
    }

    fun reportPlaybackFailure(
        videoId: String,
        clientName: String,
        category: PlaybackFailureCategory
    ) {
        if (category == PlaybackFailureCategory.SABR_ATTESTATION) {
            WebPoTokenHealth.recordRejection()
        }
        if (category == PlaybackFailureCategory.CDN_FORBIDDEN && !clientName.endsWith("__nopo")) {
            WebPoTokenHealth.recordRejection()
        }
        if (clientName.isNotBlank() && category != PlaybackFailureCategory.URL_EXPIRED) {
            markStreamClientFailed(videoId, clientName)
            val failureKind = when (category) {
                PlaybackFailureCategory.CDN_FORBIDDEN -> ClientFailureKind.MEDIA_FORBIDDEN
                PlaybackFailureCategory.PLAYABILITY, PlaybackFailureCategory.UNAVAILABLE -> ClientFailureKind.PLAYABILITY
                else -> ClientFailureKind.PLAYER_REQUEST
            }
            clientHealthTracker.recordFailure(clientName, failureKind, scope = null)
        }
    }

    suspend fun invalidatePoTokenAttestation() {
        tokenProvider.invalidateAttestation()
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
                    logger,
                    cipherService = cipherService
                ).withEmbeddedConfigFallback(),
                cipherService = cipherService,
                innerTube = latestTransport.innerTube,
                fallbackStrategy = ContentAwareFallbackStrategy(clientHealthTracker),
                tokenProvider = tokenProvider,
                clientHealthMonitor = clientHealthTracker,
                logger = logger
            )
            ExtractionBundle(latestTransport.generation, cipherService, extractor).also { currentBundle = it }
        }
    }

    private class AndroidPlayerConfigRepository(context: Context) : PlayerConfigRepository {
        private val preferences = context.getSharedPreferences("innertubex_player_config", Context.MODE_PRIVATE)
        override val enabled: Boolean = true
        override val sourceUrl: String = PLAYER_CONFIG_URL
        override val defaultSourceUrl: String = PLAYER_CONFIG_URL
        override var cachedJson: String
            get() {
                val stored = preferences.getString("json", "").orEmpty()
                return if (isValidPlayerConfigJson(stored)) stored else ""
            }
            set(value) {
                if (value.isBlank()) {
                    preferences.edit().remove("json").apply()
                } else if (isValidPlayerConfigJson(value)) {
                    preferences.edit().putString("json", value).apply()
                } else {
                    Timber.tag(TAG).w("Rejected invalid remote player_configs.json payload; keeping embedded fallback")
                }
            }
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
