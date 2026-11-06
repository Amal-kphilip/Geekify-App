package com.geekify.android.domain.recommend.engine

import com.geekify.android.data.model.ArtistRef
import com.geekify.android.data.model.SearchType
import com.geekify.android.data.model.ShelfTrack
import com.geekify.android.data.model.Track
import com.geekify.android.data.source.MusicResult
import com.geekify.android.data.source.MusicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class DiscoveredCandidate(
    val track: Track,
    val identity: NormalizedTrackIdentity,
    val providerId: String,
    val sourceSeedId: String?,
    val sourceRank: Int,
    val providerConfidence: Double,
    val playableOnPrimarySource: Boolean = true
)

data class DiscoveredArtist(
    val name: String,
    val providerArtistId: String?,
    val providerId: String,
    val similarityScore: Double
)

interface CandidateProvider {
    val providerId: String
    suspend fun searchTracks(query: String, limit: Int = 20, forceRefresh: Boolean = false): List<DiscoveredCandidate>
    suspend fun getRelatedTracks(seedTrack: Track, limit: Int = 35, forceRefresh: Boolean = false): List<DiscoveredCandidate>
    suspend fun getRelatedArtists(artistName: String, artistId: String? = null, limit: Int = 10): List<DiscoveredArtist>
    suspend fun getEditorialTracks(regionCode: String, languageCode: String, limit: Int = 25, forceRefresh: Boolean = false): List<DiscoveredCandidate>
    suspend fun getTrackMetadata(videoId: String): Track?
}

/**
 * Primary candidate provider backed by YouTube Music (InnerTube).
 * All tracks returned by this provider have verified playback availability in Geekify.
 */
@Singleton
class YouTubeMusicCandidateProvider @Inject constructor(
    private val musicSource: MusicSource
) : CandidateProvider {
    override val providerId: String = "ytm"

    override suspend fun searchTracks(query: String, limit: Int, forceRefresh: Boolean): List<DiscoveredCandidate> {
        val result = musicSource.search(query, SearchType.SONG, forceRefresh = forceRefresh)
        val songs = (result as? MusicResult.Success)?.value?.songs.orEmpty().take(limit)
        return songs.mapIndexed { idx, track ->
            DiscoveredCandidate(
                track = track,
                identity = TrackIdentityNormalizer.normalize(track, providerId = providerId),
                providerId = providerId,
                sourceSeedId = "search:$query",
                sourceRank = idx,
                providerConfidence = 0.92,
                playableOnPrimarySource = true
            )
        }
    }

    override suspend fun getRelatedTracks(seedTrack: Track, limit: Int, forceRefresh: Boolean): List<DiscoveredCandidate> {
        val result = musicSource.related(seedTrack.videoId, forceRefresh = forceRefresh)
        val related = (result as? MusicResult.Success)?.value.orEmpty().take(limit)
        return related.mapIndexed { idx, track ->
            DiscoveredCandidate(
                track = track,
                identity = TrackIdentityNormalizer.normalize(track, providerId = providerId),
                providerId = providerId,
                sourceSeedId = seedTrack.videoId,
                sourceRank = idx,
                providerConfidence = 1.0,
                playableOnPrimarySource = true
            )
        }
    }

    override suspend fun getRelatedArtists(artistName: String, artistId: String?, limit: Int): List<DiscoveredArtist> {
        if (!artistId.isNullOrBlank()) {
            val page = (musicSource.artist(artistId) as? MusicResult.Success)?.value
            if (page != null && page.related.isNotEmpty()) {
                return page.related.take(limit).mapIndexed { idx, card ->
                    DiscoveredArtist(
                        name = card.title,
                        providerArtistId = card.browseId ?: card.id,
                        providerId = providerId,
                        similarityScore = (1.0 - idx * 0.07).coerceAtLeast(0.3)
                    )
                }
            }
        }
        val searched = (musicSource.search(artistName, SearchType.ARTIST) as? MusicResult.Success)?.value?.artists.orEmpty()
        return searched.drop(1).take(limit).mapIndexed { idx, card ->
            DiscoveredArtist(
                name = card.title,
                providerArtistId = card.browseId ?: card.id,
                providerId = providerId,
                similarityScore = (0.85 - idx * 0.07).coerceAtLeast(0.25)
            )
        }
    }

    override suspend fun getEditorialTracks(
        regionCode: String,
        languageCode: String,
        limit: Int,
        forceRefresh: Boolean
    ): List<DiscoveredCandidate> {
        val home = (musicSource.home(forceRefresh = forceRefresh) as? MusicResult.Success)?.value ?: return emptyList()
        return home.shelves
            .flatMap { it.items }
            .filterIsInstance<ShelfTrack>()
            .map { it.value }
            .distinctBy { it.videoId }
            .take(limit)
            .mapIndexed { idx, track ->
                DiscoveredCandidate(
                    track = track,
                    identity = TrackIdentityNormalizer.normalize(track, providerId = providerId),
                    providerId = providerId,
                    sourceSeedId = "editorial:$regionCode:$languageCode",
                    sourceRank = idx,
                    providerConfidence = 0.85,
                    playableOnPrimarySource = true
                )
            }
    }

    override suspend fun getTrackMetadata(videoId: String): Track? =
        (musicSource.track(videoId) as? MusicResult.Success)?.value
}

/**
 * Secondary metadata & discovery provider using documented MusicBrainz / ListenBrainz open APIs.
 * Discovers recordings and related artists outside YouTube Music's radio bubble, then resolves
 * playable matches through [YouTubeMusicSource] so playback availability is guaranteed.
 */
@Singleton
class MusicBrainzCandidateProvider @Inject constructor(
    private val httpClient: OkHttpClient,
    private val musicSource: MusicSource
) : CandidateProvider {
    override val providerId: String = "musicbrainz"
    private val json = Json { ignoreUnknownKeys = true }
    private val rateLimiter = Semaphore(1)

    override suspend fun searchTracks(query: String, limit: Int, forceRefresh: Boolean): List<DiscoveredCandidate> =
        withContext(Dispatchers.IO) {
            rateLimiter.withPermit {
                runCatching {
                    val url = "https://musicbrainz.org/ws/2/recording".toHttpUrl().newBuilder()
                        .addQueryParameter("query", query)
                        .addQueryParameter("fmt", "json")
                        .addQueryParameter("limit", limit.coerceIn(1, 10).toString())
                        .build()
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "GeekifyAndroid/2.4 (https://github.com/Amal-kphilip/Geekify-App)")
                        .get()
                        .build()
                    val body = httpClient.newBuilder()
                        .connectTimeout(4, TimeUnit.SECONDS)
                        .readTimeout(4, TimeUnit.SECONDS)
                        .build()
                        .newCall(request)
                        .execute()
                        .use { resp -> if (resp.isSuccessful) resp.body.string() else null }
                        ?: return@runCatching emptyList()

                    parseMusicBrainzRecordings(body, limit)
                }.getOrDefault(emptyList())
            }
        }

    internal suspend fun parseMusicBrainzRecordings(jsonBody: String, limit: Int): List<DiscoveredCandidate> {
        val root = runCatching { json.parseToJsonElement(jsonBody) as? JsonObject }.getOrNull() ?: return emptyList()
        val recordings = root["recordings"] as? JsonArray ?: return emptyList()
        val results = mutableListOf<DiscoveredCandidate>()
        for ((idx, item) in recordings.take(limit).withIndex()) {
            val obj = item as? JsonObject ?: continue
            val mbid = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: continue
            val title = (obj["title"] as? JsonPrimitive)?.contentOrNull ?: continue
            val artistCredit = (obj["artist-credit"] as? JsonArray)?.firstOrNull() as? JsonObject
            val artistName = (artistCredit?.get("name") as? JsonPrimitive)?.contentOrNull ?: continue
            val isrcs = (obj["isrcs"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()

            // Resolve playable counterpart on YouTube Music
            val resolved = (musicSource.search("$title $artistName", SearchType.SONG) as? MusicResult.Success)
                ?.value?.songs?.firstOrNull() ?: continue
            val identity = TrackIdentityNormalizer.normalize(
                track = resolved,
                isrc = isrcs.firstOrNull(),
                mbRecordingId = mbid,
                providerId = providerId
            )
            results.add(
                DiscoveredCandidate(
                    track = resolved,
                    identity = identity,
                    providerId = providerId,
                    sourceSeedId = "mbid:$mbid",
                    sourceRank = idx,
                    providerConfidence = 0.88,
                    playableOnPrimarySource = true
                )
            )
        }
        return results
    }

    override suspend fun getRelatedTracks(seedTrack: Track, limit: Int, forceRefresh: Boolean): List<DiscoveredCandidate> {
        val artistQuery = seedTrack.artist.substringBefore(",").trim()
        if (artistQuery.isEmpty()) return emptyList()
        return searchTracks("artist:\"$artistQuery\" AND NOT recording:\"${seedTrack.title}\"", limit = limit.coerceAtMost(5))
    }

    override suspend fun getRelatedArtists(artistName: String, artistId: String?, limit: Int): List<DiscoveredArtist> =
        emptyList()

    override suspend fun getEditorialTracks(
        regionCode: String,
        languageCode: String,
        limit: Int,
        forceRefresh: Boolean
    ): List<DiscoveredCandidate> = emptyList()

    override suspend fun getTrackMetadata(videoId: String): Track? = null
}

/**
 * Orchestrates multi-source candidate retrieval with bounded concurrency and canonical deduplication.
 */
@Singleton
class CandidateDiscoveryEngine @Inject constructor(
    private val primaryProvider: YouTubeMusicCandidateProvider
) {
    private val concurrencySemaphore = Semaphore(4)

    suspend fun retrieveCandidates(
        seedTracks: List<Track>,
        extraProviders: List<CandidateProvider> = emptyList(),
        perSeedLimit: Int = 35,
        forceRefresh: Boolean = false
    ): List<DiscoveredCandidate> = coroutineScope {
        val primaryDeferred = seedTracks.map { seed ->
            async(Dispatchers.IO) {
                concurrencySemaphore.withPermit {
                    withTimeoutOrNull(8_000L) {
                        runCatching {
                            primaryProvider.getRelatedTracks(seed, limit = perSeedLimit, forceRefresh = forceRefresh)
                        }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }
        }

        val secondaryDeferred = extraProviders.flatMap { provider ->
            seedTracks.take(2).map { seed ->
                async(Dispatchers.IO) {
                    concurrencySemaphore.withPermit {
                        withTimeoutOrNull(4_500L) {
                            runCatching {
                                provider.getRelatedTracks(seed, limit = 6, forceRefresh = forceRefresh)
                            }.getOrDefault(emptyList())
                        }.orEmpty()
                    }
                }
            }
        }

        val allRaw = (primaryDeferred.awaitAll().flatten() + secondaryDeferred.awaitAll().flatten())
            .filter { it.playableOnPrimarySource && it.track.videoId.isNotBlank() }

        deduplicateCandidates(allRaw)
    }

    fun deduplicateCandidates(candidates: List<DiscoveredCandidate>): List<DiscoveredCandidate> {
        val unique = mutableListOf<DiscoveredCandidate>()
        for (cand in candidates) {
            val duplicateIdx = unique.indexOfFirst { existing ->
                existing.track.videoId == cand.track.videoId ||
                    TrackIdentityNormalizer.isSameRecording(existing.identity, cand.identity)
            }
            if (duplicateIdx < 0) {
                unique.add(cand)
            } else {
                val existing = unique[duplicateIdx]
                if (cand.providerConfidence > existing.providerConfidence || cand.sourceRank < existing.sourceRank) {
                    unique[duplicateIdx] = cand
                }
            }
        }
        return unique
    }
}
