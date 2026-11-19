package com.geekify.android.player.extraction

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException

enum class PlaybackFailureCategory {
    CDN_FORBIDDEN,
    TOKEN,
    URL_EXPIRED,
    DECODER,
    SABR_PROTOCOL,
    SABR_ATTESTATION,
    TIMEOUT,
    RATE_LIMIT,
    NETWORK,
    RENDERER,
    PLAYABILITY,
    UNAVAILABLE,
    UNKNOWN,
}

fun StreamResolveException.toPlaybackFailureCategory(): PlaybackFailureCategory =
    when (reason) {
        StreamResolveException.Reason.NETWORK -> PlaybackFailureCategory.NETWORK
        StreamResolveException.Reason.UNAVAILABLE,
        StreamResolveException.Reason.EXPLICIT_UNSUPPORTED,
        StreamResolveException.Reason.AGE_RESTRICTED -> PlaybackFailureCategory.UNAVAILABLE
        StreamResolveException.Reason.NO_MUSIC_VIDEO,
        StreamResolveException.Reason.NO_PLAYABLE_STREAM -> PlaybackFailureCategory.PLAYABILITY
        StreamResolveException.Reason.UNKNOWN -> PlaybackFailureCategory.UNKNOWN
    }

fun playbackFailureCategoryFromSabr(kind: SabrFailureKind?): PlaybackFailureCategory =
    when (kind) {
        SabrFailureKind.ATTESTATION_REQUIRED -> PlaybackFailureCategory.SABR_ATTESTATION
        SabrFailureKind.PROTOCOL, SabrFailureKind.RELOAD_PLAYER -> PlaybackFailureCategory.SABR_PROTOCOL
        null -> PlaybackFailureCategory.UNKNOWN
    }

object PlaybackFailureClassifier {
    private val NETWORK_ERROR_NAMES = setOf(
        "ConnectException",
        "SocketException",
        "UnknownHostException",
        "NoRouteToHostException",
        "UnresolvedAddressException"
    )

    fun fromHttpStatus(status: Int): PlaybackFailureCategory =
        when (status) {
            401, 407 -> PlaybackFailureCategory.TOKEN
            403 -> PlaybackFailureCategory.CDN_FORBIDDEN
            404, 410 -> PlaybackFailureCategory.URL_EXPIRED
            408 -> PlaybackFailureCategory.TIMEOUT
            425 -> PlaybackFailureCategory.NETWORK
            429 -> PlaybackFailureCategory.RATE_LIMIT
            else -> if (status in 500..599) PlaybackFailureCategory.NETWORK else PlaybackFailureCategory.UNKNOWN
        }

    @OptIn(UnstableApi::class)
    fun fromThrowable(error: Throwable?): PlaybackFailureCategory {
        if (error == null) return PlaybackFailureCategory.UNKNOWN
        generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.forEach { current ->
            val name = current::class.simpleName.orEmpty()
            when {
                current is StreamResolveException -> return current.toPlaybackFailureCategory()
                current is HttpDataSource.InvalidResponseCodeException -> return fromHttpStatus(current.responseCode)
                current is SabrProtocolException -> {
                    current.httpStatusCode?.let { return fromHttpStatus(it) }
                    return playbackFailureCategoryFromSabr(current.kind)
                }
                current is PlaybackException -> {
                    when (current.errorCode) {
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                            (current.cause as? HttpDataSource.InvalidResponseCodeException)?.let {
                                return fromHttpStatus(it.responseCode)
                            }
                        }
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> return PlaybackFailureCategory.NETWORK
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                        PlaybackException.ERROR_CODE_DECODING_FAILED,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> return PlaybackFailureCategory.DECODER
                        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> return PlaybackFailureCategory.RENDERER
                    }
                }
                name.contains("Sabr", ignoreCase = true) -> {
                    return playbackFailureCategoryFromSabr((current as? SabrProtocolException)?.kind)
                }
                name.contains("Timeout", ignoreCase = true) -> return PlaybackFailureCategory.TIMEOUT
                name.contains("Decoder", ignoreCase = true) -> return PlaybackFailureCategory.DECODER
                name.contains("Renderer", ignoreCase = true) -> return PlaybackFailureCategory.RENDERER
                name.contains("Network", ignoreCase = true) || name in NETWORK_ERROR_NAMES -> return PlaybackFailureCategory.NETWORK
            }
        }
        return PlaybackFailureCategory.UNKNOWN
    }

    fun isRetryable(category: PlaybackFailureCategory): Boolean =
        when (category) {
            PlaybackFailureCategory.CDN_FORBIDDEN,
            PlaybackFailureCategory.TOKEN,
            PlaybackFailureCategory.URL_EXPIRED,
            PlaybackFailureCategory.SABR_PROTOCOL,
            PlaybackFailureCategory.SABR_ATTESTATION,
            PlaybackFailureCategory.TIMEOUT,
            PlaybackFailureCategory.RATE_LIMIT,
            PlaybackFailureCategory.NETWORK,
            PlaybackFailureCategory.UNKNOWN -> true
            PlaybackFailureCategory.DECODER,
            PlaybackFailureCategory.RENDERER,
            PlaybackFailureCategory.PLAYABILITY,
            PlaybackFailureCategory.UNAVAILABLE -> false
        }

    fun requiresFreshExtraction(category: PlaybackFailureCategory): Boolean =
        when (category) {
            PlaybackFailureCategory.CDN_FORBIDDEN,
            PlaybackFailureCategory.TOKEN,
            PlaybackFailureCategory.URL_EXPIRED,
            PlaybackFailureCategory.SABR_PROTOCOL,
            PlaybackFailureCategory.SABR_ATTESTATION -> true
            else -> false
        }
}
