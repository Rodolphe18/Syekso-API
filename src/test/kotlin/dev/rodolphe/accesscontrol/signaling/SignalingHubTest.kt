package dev.rodolphe.accesscontrol.signaling

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalingHubTest {
    private fun recordingConn(id: String, sink: MutableList<SignalingMessage>) =
        ClientConnection(id) { sink.add(it) }

    @Test fun `ring to a connected resident is routed`() = runTest {
        val hub = SignalingHub(scope = this)
        val residentSink = mutableListOf<SignalingMessage>()
        val intercomSink = mutableListOf<SignalingMessage>()
        hub.registerResident("u1", recordingConn("u1", residentSink))
        hub.registerIntercom("b1", recordingConn("b1", intercomSink))

        hub.onRingCall("b1", SignalingMessage.Ring("c1", "u1", "Porte d'entrée"))

        assertEquals(listOf<SignalingMessage>(SignalingMessage.Ring("c1", "u1", "Porte d'entrée")), residentSink)
    }

    @Test fun `ring to an absent resident errors back to the intercom`() = runTest {
        val hub = SignalingHub(scope = this)
        val intercomSink = mutableListOf<SignalingMessage>()
        hub.registerIntercom("b1", recordingConn("b1", intercomSink))

        hub.onRingCall("b1", SignalingMessage.Ring("c1", "u1", "Porte"))

        assertEquals(SignalingMessage.ErrorMsg("c1", "Résident indisponible"), intercomSink.single())
    }

    @Test fun `accept moves the call to in-call and relays to the intercom`() = runTest {
        val hub = SignalingHub(scope = this)
        val intercomSink = mutableListOf<SignalingMessage>()
        hub.registerResident("u1", recordingConn("u1", mutableListOf()))
        hub.registerIntercom("b1", recordingConn("b1", intercomSink))
        hub.onRingCall("b1", SignalingMessage.Ring("c1", "u1", "Porte"))
        intercomSink.clear()

        hub.onAcceptCall("u1", SignalingMessage.Accept("c1"))
        assertEquals(SignalingMessage.Accept("c1"), intercomSink.single())
    }

    @Test fun `open during a call relays and keeps the call alive`() = runTest {
        val hub = SignalingHub(scope = this)
        val intercomSink = mutableListOf<SignalingMessage>()
        val residentSink = mutableListOf<SignalingMessage>()
        hub.registerResident("u1", recordingConn("u1", residentSink))
        hub.registerIntercom("b1", recordingConn("b1", intercomSink))
        hub.onRingCall("b1", SignalingMessage.Ring("c1", "u1", "Porte"))
        hub.onAcceptCall("u1", SignalingMessage.Accept("c1"))
        intercomSink.clear(); residentSink.clear()

        hub.onOpenCall("u1", SignalingMessage.Open("c1"))
        assertEquals(SignalingMessage.Open("c1"), intercomSink.single())
        hub.onOpenResultReported("b1", SignalingMessage.OpenResult("c1", true))
        assertEquals(SignalingMessage.OpenResult("c1", true, null), residentSink.single())
        intercomSink.clear()
        hub.onOpenCall("u1", SignalingMessage.Open("c1"))
        assertEquals(SignalingMessage.Open("c1"), intercomSink.single())
    }

    @Test fun `offer relays intercom to resident, answer relays resident to intercom`() = runTest {
        val hub = SignalingHub(scope = this)
        val intercomSink = mutableListOf<SignalingMessage>()
        val residentSink = mutableListOf<SignalingMessage>()
        hub.registerResident("u1", recordingConn("u1", residentSink))
        hub.registerIntercom("b1", recordingConn("b1", intercomSink))
        hub.onRingCall("b1", SignalingMessage.Ring("c1", "u1", "Porte"))
        hub.onAcceptCall("u1", SignalingMessage.Accept("c1"))
        intercomSink.clear(); residentSink.clear()

        hub.relayFromIntercom("b1", SignalingMessage.Offer("c1", "OFFER"))
        assertEquals(SignalingMessage.Offer("c1", "OFFER"), residentSink.single())
        hub.relayFromResident("u1", SignalingMessage.Answer("c1", "ANSWER"))
        assertEquals(SignalingMessage.Answer("c1", "ANSWER"), intercomSink.single())
    }

    @Test fun `hangup from resident ends the call and reaches the intercom`() = runTest {
        val hub = SignalingHub(scope = this)
        val intercomSink = mutableListOf<SignalingMessage>()
        hub.registerResident("u1", recordingConn("u1", mutableListOf()))
        hub.registerIntercom("b1", recordingConn("b1", intercomSink))
        hub.onRingCall("b1", SignalingMessage.Ring("c1", "u1", "Porte"))
        hub.onAcceptCall("u1", SignalingMessage.Accept("c1"))
        intercomSink.clear()

        hub.onHangupCall("c1", fromResident = true)
        assertEquals(SignalingMessage.Hangup("c1"), intercomSink.single())
        val residentSink = mutableListOf<SignalingMessage>()
        hub.registerResident("u1", recordingConn("u1", residentSink))
        hub.onOpenCall("u1", SignalingMessage.Open("c1"))
        assertEquals(SignalingMessage.ErrorMsg("c1", "Appel expiré"), residentSink.single())
    }

    @Test fun `decline forwards to the intercom and drops the call`() = runTest {
        val hub = SignalingHub(scope = this)
        val intercomSink = mutableListOf<SignalingMessage>()
        hub.registerResident("u1", recordingConn("u1", mutableListOf()))
        hub.registerIntercom("b1", recordingConn("b1", intercomSink))
        hub.onRingCall("b1", SignalingMessage.Ring("c1", "u1", "Porte"))
        intercomSink.clear()

        hub.onDeclineCall("u1", SignalingMessage.Decline("c1"))
        assertTrue(intercomSink.contains(SignalingMessage.Decline("c1")))
    }
}
