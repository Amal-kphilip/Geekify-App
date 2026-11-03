package com.geekify.android.data.source

import com.geekify.android.data.model.*

sealed interface MusicResult<out T> {
    data class Success<T>(val value: T) : MusicResult<T>
    data class Failure(val message: String, val retryable: Boolean = false) : MusicResult<Nothing>
}

interface MusicSource {
    suspend fun search(query: String, type: SearchType? = null): MusicResult<SearchResponse>
    suspend fun searchSuggestions(query: String): List<String> = emptyList()
    suspend fun searchContinuation(continuation: String): MusicResult<SearchResponse> = MusicResult.Failure("Continuation not supported")
    suspend fun track(videoId: String): MusicResult<Track>
    suspend fun related(videoId: String): MusicResult<List<Track>>
    suspend fun artist(channelId: String): MusicResult<ArtistPage>
    suspend fun collection(id: String, kind: CollectionKind): MusicResult<CollectionPage>
    suspend fun home(): MusicResult<HomeResponse>
}
