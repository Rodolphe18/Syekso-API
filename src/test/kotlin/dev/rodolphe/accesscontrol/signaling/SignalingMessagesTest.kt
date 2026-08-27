package dev.rodolphe.accesscontrol.signaling

import kotlin.test.Test
import kotlin.test.assertEquals

class SignalingMessagesTest {
    private fun roundTrip(msg: SignalingMessage) {
        val json = signalingJson.encodeToString(SignalingMessage.serializer(), msg)
        val back = signalingJson.decodeFromString(SignalingMessage.serializer(), json)
        assertEquals(msg, back)
    }

    @Test fun `ring round-trips with type discriminator`() {
        val json = signalingJson.encodeToString(
            SignalingMessage.serializer(),
            SignalingMessage.Ring("c1", targetUserId = "u1", doorName = "Porte d'entrée"),
        )
        assertEquals(true, json.contains("\"type\":\"ring\""))
        roundTrip(SignalingMessage.Ring("c1", "u1", "Porte d'entrée"))
    }

    @Test fun `all subtypes round-trip`() {
        roundTrip(SignalingMessage.Hello(role = "intercom", intercomKey = "k", buildingId = "b"))
        roundTrip(SignalingMessage.Open("c1"))
        roundTrip(SignalingMessage.Decline("c1"))
        roundTrip(SignalingMessage.OpenResult("c1", success = false, reason = "NotFound"))
        roundTrip(SignalingMessage.ErrorMsg(message = "boom"))
        roundTrip(SignalingMessage.Accept("c1"))
        roundTrip(SignalingMessage.Offer("c1", "v=0..."))
        roundTrip(SignalingMessage.Answer("c1", "v=0..."))
        roundTrip(SignalingMessage.IceCandidate("c1", "candidate:...", "0", 0))
        roundTrip(SignalingMessage.Hangup("c1"))
    }
}
