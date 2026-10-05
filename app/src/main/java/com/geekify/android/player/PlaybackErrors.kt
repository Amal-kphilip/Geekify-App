package com.geekify.android.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Turns player and network failures into short messages a listener can act on (shown as banners). */
@OptIn(UnstableApi::class)
object PlaybackErrors {

    fun message(error: PlaybackException): String {
        val http = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode
        return when {
            http == 429 -> "YouTube is limiting requests right now. Wait a moment and try again."
            http == 403 -> "This stream was blocked. Trying a fresh link may help."
            http == 404 || http == 410 -> "This stream link expired. Tap play to try again."
            http != null -> "The server couldn't deliver this track (error $http)."
            else -> when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "No connection. Check your internet and try again."
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "The connection timed out. Try again in a moment."
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                    "This audio couldn't be played on your device."
                else -> "Playback stopped unexpectedly. Try again."
            }
        }
    }

    /** For failures before playback starts (looking up the stream). */
    fun message(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.toList()
        return when {
            chain.any { it is UnknownHostException || it is ConnectException } -> "No connection. Check your internet and try again."
            chain.any { it is SocketTimeoutException } -> "The connection timed out. Try again in a moment."
            chain.any { it is StreamResolver.StreamException } ->
                chain.filterIsInstance<StreamResolver.StreamException>().first().message ?: GENERIC
            else -> error.message?.takeIf { it.isNotBlank() } ?: GENERIC
        }
    }

    private const val GENERIC = "This track couldn't be played. Try again."
}
