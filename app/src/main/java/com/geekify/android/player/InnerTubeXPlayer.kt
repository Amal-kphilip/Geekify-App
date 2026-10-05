package com.geekify.android.player

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the player needs from YouTube: "give me a playable stream for this video".
 *
 * The playback service depends only on this interface, so how the URL is obtained (which InnerTube client,
 * whether a signature has to be deciphered, a PO token...) can change without touching player logic.
 * [StreamResolver] is the implementation.
 */
interface InnerTubeXPlayer {
    suspend fun resolve(videoId: String, forceRefresh: Boolean = false): StreamResolver.ResolvedStream
    fun prefetch(videoIds: List<String>)
    fun invalidate(videoId: String)
}

/**
 * Turns an audio format that only carries a `signatureCipher` into a playable URL.
 *
 * This is the plug-in point for zemer-cipher (see integration/zemer-cipher/INTEGRATION.md): implement this
 * interface with it and bind it in [PlayerBindingsModule]. The current VISIONOS player client returns plain
 * URLs, so the default [NoOpStreamCipher] is never needed today; [StreamResolver] only asks a cipher for
 * formats that have no URL. [videoId] is passed because the cipher solver needs it. Return null when the
 * format cannot be deciphered; the resolver then skips it.
 */
interface StreamCipher {
    suspend fun decipher(videoId: String, format: JsonObject): String?
}

@Singleton
class NoOpStreamCipher @Inject constructor() : StreamCipher {
    override suspend fun decipher(videoId: String, format: JsonObject): String? = null
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerBindingsModule {
    @Binds abstract fun bindInnerTubeXPlayer(impl: StreamResolver): InnerTubeXPlayer
    @Binds abstract fun bindStreamCipher(impl: NoOpStreamCipher): StreamCipher
}
