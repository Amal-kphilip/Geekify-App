package com.geekify.android.data.source

import com.geekify.android.data.model.*

sealed interface MusicResult<out T> {
    data class Success<T>(val value: T) : MusicResult<T>
    data class Failure(val message: String, val retryable: Boolean = false) : MusicResult<Nothing>
}

interface MusicSource {
    suspend fun search(query: String, type: SearchType? = null): MusicResult<SearchResponse>
    suspend fun track(videoId: String): MusicResult<Track>
    suspend fun related(videoId: String): MusicResult<List<Track>>
    suspend fun artist(channelId: String): MusicResult<ArtistPage>
    suspend fun collection(id: String, kind: CollectionKind): MusicResult<CollectionPage>
    /**
     * @param languageHint the listener's dominant language (e.g. "Malayalam"), used to pick relevant shelves.
     * @param forceRefresh bypass the cache and rotate the optional shelves (pull-to-refresh).
     */
    suspend fun home(languageHint: String? = null, forceRefresh: Boolean = false): MusicResult<HomeResponse>
}
