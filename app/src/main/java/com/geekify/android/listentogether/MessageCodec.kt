package com.geekify.android.listentogether

import com.geekify.android.data.model.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What peers say to each other through the relay. Every message is a JSON object with a `type` field
 * (join, leave, state, peers, ping, pong, error).
 *
 * Relay contract: connect to `<server>?room=<CODE>&user=<id>`; forward every text frame to the other peers
 * in the same room; answer `ping` with a `pong` carrying the same `t` (optional, used to estimate latency).
 */
@Serializable
sealed class RoomMessage {
    @Serializable @SerialName("join")
    data class Join(val room: String, val user: String) : RoomMessage()

    @Serializable @SerialName("leave")
    data class Leave(val room: String, val user: String) : RoomMessage()

    /** The sender's playback: which track, where in it, playing or paused, and when this was sampled. */
    @Serializable @SerialName("state")
    data class State(
        val room: String,
        val user: String,
        val track: Track? = null,
        val positionMs: Long = 0L,
        val playing: Boolean = false,
        val sentAt: Long = 0L
    ) : RoomMessage()

    @Serializable @SerialName("peers")
    data class Peers(val room: String, val count: Int) : RoomMessage()

    @Serializable @SerialName("ping")
    data class Ping(val t: Long) : RoomMessage()

    @Serializable @SerialName("pong")
    data class Pong(val t: Long) : RoomMessage()

    @Serializable @SerialName("error")
    data class Error(val message: String) : RoomMessage()
}

/** JSON <-> [RoomMessage]. [decode] never throws: garbage, unknown types and oversized frames return null. */
object MessageCodec {
    private const val MAX_FRAME_CHARS = 64 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun encode(message: RoomMessage): String = json.encodeToString(RoomMessage.serializer(), message)

    fun decode(text: String): RoomMessage? {
        if (text.isBlank() || text.length > MAX_FRAME_CHARS) return null
        return try {
            json.decodeFromString(RoomMessage.serializer(), text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
