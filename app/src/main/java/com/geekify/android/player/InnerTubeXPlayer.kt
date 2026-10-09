package com.geekify.android.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Playback-facing API. Geekify keeps Media3, queue, cache and UI ownership; the implementation
 * uses the InnerTubeX extractor for signature deciphering, token-aware client fallback,
 * stream metadata, per-client headers and URL refresh behavior.
 */
interface InnerTubeXPlayer {
    suspend fun resolve(videoId: String, forceRefresh: Boolean = false): StreamResolver.ResolvedStream
    fun prefetch(videoIds: List<String>)
    fun invalidate(videoId: String)

    /** The stream last returned for [videoId] was rejected by the server (for example HTTP 403). */
    fun markFailed(videoId: String)

    /** Apply the resolved stream's required request headers/range limits to a Media3 request. */
    @OptIn(UnstableApi::class)
    fun resolveDataSpec(dataSpec: DataSpec): DataSpec = dataSpec

    /** Best-effort warm-up of the extractor/player-config cache. */
    suspend fun warmUp() {}
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerBindingsModule {
    @Binds abstract fun bindInnerTubeXPlayer(impl: StreamResolver): InnerTubeXPlayer
}
