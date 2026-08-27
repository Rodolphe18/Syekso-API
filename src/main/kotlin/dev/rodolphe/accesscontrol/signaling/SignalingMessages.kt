package dev.rodolphe.accesscontrol.signaling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire messages exchanged over /ws. `type` is the JSON discriminator.
   Kept identical on the app side (core:network).
   Fields are nullable where a role/direction doesn't use them.

   Two families under the single wire protocol:
   - [CallControl] — messages the SignalingHub INTERPRETS (call lifecycle + the door-open domain).
   - [WebRTC]      — media-negotiation messages the hub RELAYS blindly to the other peer; it never
                     reads their content. Swapping the media tech would touch only these.
   [Hello] and [ErrorMsg] belong to neither: connection handshake and a generic error response. */

@Serializable
sealed interface SignalingMessage {

    /** Interpreted by the hub (state machine, routing, door open). All carry a [callId]. */
    sealed interface CallControl : SignalingMessage {
        val callId: String
    }

    /** WebRTC media negotiation. Opaque to the hub — relayed as-is. All carry a [callId]. */
    sealed interface WebRTC : SignalingMessage {
        val callId: String
    }

    @Serializable
    @SerialName("hello")
    data class Hello(
        val role: String,                 // "resident" | "intercom"
        val jwt: String? = null,          // resident
        val intercomKey: String? = null,  // intercom
        val buildingId: String? = null,   // intercom
    ) : SignalingMessage

    @Serializable
    @SerialName("ring")
    data class Ring(
        override val callId: String,
        val targetUserId: String? = null, // set by intercom
        val doorName: String? = null,     // set by intercom, forwarded to resident
    ) : CallControl

    @Serializable
    @SerialName("open")
    data class Open(override val callId: String) : CallControl

    @Serializable
    @SerialName("decline")
    data class Decline(override val callId: String) : CallControl

    @Serializable
    @SerialName("open_result")
    data class OpenResult(override val callId: String, val success: Boolean, val reason: String? = null) : CallControl

    @Serializable
    @SerialName("accept")
    data class Accept(override val callId: String) : CallControl

    @Serializable
    @SerialName("hangup")
    data class Hangup(override val callId: String) : CallControl

    @Serializable
    @SerialName("offer")
    data class Offer(override val callId: String, val sdp: String) : WebRTC

    @Serializable
    @SerialName("answer")
    data class Answer(override val callId: String, val sdp: String) : WebRTC

    @Serializable
    @SerialName("ice")
    data class IceCandidate(
        override val callId: String,
        val sdp: String,
        val sdpMid: String? = null,
        val sdpMLineIndex: Int = 0,
    ) : WebRTC

    @Serializable
    @SerialName("error")
    data class ErrorMsg(val callId: String? = null, val message: String) : SignalingMessage
}

val signalingJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = false
}
