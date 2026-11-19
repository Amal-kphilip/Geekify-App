package com.geekify.android.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.geekify.android.player.extraction.PlaybackFailureCategory

/**
 * Playback-facing API. Geekify keeps Media3, queue, cache and UI ownership; the implementation
 * uses the InnerTubeX extractor for signature deciphering, token-aware client fallback,
 * SABR bootstrap, stream metadata, per-client headers and URL refresh behavior.
 */
interface InnerTubeXPlayer {
    suspend fun resolve(videoId: String, forceRefresh: Boolean = false): StreamResolver.ResolvedStream
    fun prefetch(videoIds: List<String>)
    fun invalidate(videoId: String)
    fun clearCache() {}

    /** The stream last returned for [videoId] was rejected by the server (for example HTTP 403). */
    fun markFailed(videoId: String)

    /** Categorized failure reporting for adaptive client cooldown, PO token invalidation and fallback. */
    fun reportFailure(videoId: String, category: PlaybackFailureCategory) {
        markFailed(videoId)
    }

    /** Apply the resolved stream's required request headers/range limits to a Media3 request. */
    @OptIn(UnstableApi::class)
    fun resolveDataSpec(dataSpec: DataSpec): DataSpec = dataSpec

    /** Best-effort warm-up of the extractor/player-config cache. */
    suspend fun warmUp() {}
}
