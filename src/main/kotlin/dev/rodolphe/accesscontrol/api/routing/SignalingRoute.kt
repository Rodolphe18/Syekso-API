package dev.rodolphe.accesscontrol.api.routing

import dev.rodolphe.accesscontrol.security.JwtService
import dev.rodolphe.accesscontrol.signaling.ClientConnection
import dev.rodolphe.accesscontrol.signaling.SignalingHub
import dev.rodolphe.accesscontrol.signaling.SignalingMessage
import dev.rodolphe.accesscontrol.signaling.signalingJson
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText


fun Route.signalingRoute(jwt: JwtService, intercomKey: String, signalingHub: SignalingHub) {
    webSocket("/ws") {
        // PHASE 1 — Le handshake HELLO
        // .receive() suspend jusqu'à ce que la première frame arrive du client
        val firstFrame = (incoming.receive() as? Frame.Text)?.readText() ?: return@webSocket
        val hello = runCatching {
            signalingJson.decodeFromString(SignalingMessage.serializer(), firstFrame)
        }.getOrNull() as? SignalingMessage.Hello ?: return@webSocket close()

        // 2 variables déclarées mais pas initialisées car remplies différemment selon le rôle

        // l'objet qui représente cette connexion dans le hub
        val clientConnection: ClientConnection

        // que faire quand un message arrive
        val onMessage: suspend (SignalingMessage) -> Unit

        when (hello.role) {
            "resident" -> {
                val userId = hello.jwt?.let(jwt::userIdFromToken) ?: return@webSocket close()
                clientConnection = ClientConnection(userId) {
                    send(Frame.Text(signalingJson.encodeToString(SignalingMessage.serializer(), it)))
                }
                signalingHub.registerResident(userId, clientConnection)
                onMessage = { msg ->
                    when (msg) {
                        is SignalingMessage.Open -> signalingHub.onOpenCall(userId, msg)
                        is SignalingMessage.Decline -> signalingHub.onDeclineCall(userId, msg)
                        is SignalingMessage.Accept -> signalingHub.onAcceptCall(userId, msg)
                        is SignalingMessage.WebRTC -> signalingHub.relayFromResident(userId, msg)
                        is SignalingMessage.Hangup -> signalingHub.onHangupCall(msg.callId, fromResident = true)
                        else -> {}
                    }
                }
            }
            "intercom" -> {
                val buildingId = hello.buildingId
                if (hello.intercomKey != intercomKey || buildingId.isNullOrBlank()) return@webSocket close()
                clientConnection = ClientConnection(buildingId) {
                    send(Frame.Text(signalingJson.encodeToString(SignalingMessage.serializer(), it)))
                }
                signalingHub.registerIntercom(buildingId, clientConnection)
                onMessage = { msg ->
                    when (msg) {
                        is SignalingMessage.Ring -> signalingHub.onRingCall(buildingId, msg)
                        is SignalingMessage.OpenResult -> signalingHub.onOpenResultReported(buildingId, msg)
                        is SignalingMessage.WebRTC -> signalingHub.relayFromIntercom(buildingId, msg)
                        is SignalingMessage.Hangup -> signalingHub.onHangupCall(msg.callId, fromResident = false)
                        else -> {}
                    }
                }
            }
            else -> return@webSocket close()
        }

        try {
            for (frame in incoming) {
                val text = (frame as? Frame.Text)?.readText() ?: continue
                val msg = runCatching {
                    signalingJson.decodeFromString(SignalingMessage.serializer(), text)
                }.getOrNull() ?: continue
                onMessage(msg)
            }
        } finally {
            signalingHub.unregister(clientConnection)
        }
    }
}