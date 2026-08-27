package dev.rodolphe.accesscontrol.signaling

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** One connected client (resident or intercom). [rawSend] serializes+sends one message; the mutex
 *  serializes concurrent sends to the same socket so frames don't interleave. */
class ClientConnection(
    val id: String,
    private val rawSend: suspend (SignalingMessage) -> Unit,
) {
    private val sendMutex = Mutex()
    suspend fun send(msg: SignalingMessage) = sendMutex.withLock { rawSend(msg) }
}

enum class CallStatus { RINGING, IN_CALL }

class CallState(
    val buildingId: String,
    val residentUserId: String,
    var status: CallStatus,
    var timeoutJob: Job? = null,
)

/** In-memory relay. Not persisted: on backend restart, clients reconnect. */
class SignalingHub(
    private val scope: CoroutineScope,
    private val ringTimeoutMs: Long = 30_000,
) {
    private val residents = ConcurrentHashMap<String, ClientConnection>()   // userId -> conn
    private val intercoms = ConcurrentHashMap<String, ClientConnection>()   // buildingId -> conn
    private val calls = ConcurrentHashMap<String, CallState>()              // callId -> state

    fun registerResident(userId: String, conn: ClientConnection) { residents[userId] = conn }
    fun registerIntercom(buildingId: String, conn: ClientConnection) { intercoms[buildingId] = conn }

    /** Remove a dropped connection and cancel any call that depended on it. */
    fun unregister(conn: ClientConnection) {
        residents.entries.removeIf { it.value === conn }
        intercoms.entries.removeIf { it.value === conn }
        calls.entries.removeIf { (_, state) ->
            val broken = residents[state.residentUserId] == null || intercoms[state.buildingId] == null
            if (broken) state.timeoutJob?.cancel()
            broken
        }
    }

    suspend fun onRingCall(buildingId: String, msg: SignalingMessage.Ring) {
        val targetUserId = msg.targetUserId ?: return
        // One call at a time per intercom.
        if (calls.values.any { it.buildingId == buildingId }) {
            intercoms[buildingId]?.send(SignalingMessage.ErrorMsg(msg.callId, "Appel déjà en cours"))
            return
        }
        val resident = residents[targetUserId]
        if (resident == null) {
            intercoms[buildingId]?.send(SignalingMessage.ErrorMsg(msg.callId, "Résident indisponible"))
            return
        }
        val state = CallState(buildingId, targetUserId, CallStatus.RINGING)
        calls[msg.callId] = state
        state.timeoutJob = scope.launch {
            delay(ringTimeoutMs)
            if (calls[msg.callId]?.status == CallStatus.RINGING) {
                calls.remove(msg.callId)
                intercoms[buildingId]?.send(SignalingMessage.ErrorMsg(msg.callId, "Pas de réponse"))
                residents[targetUserId]?.send(SignalingMessage.ErrorMsg(msg.callId, "TIMED_OUT"))
            }
        }
        resident.send(SignalingMessage.Ring(msg.callId, targetUserId, msg.doorName))
    }

    /** Resident answered: RINGING -> IN_CALL, relay Accept to the intercom (which then creates the offer). */
    suspend fun onAcceptCall(residentUserId: String, msg: SignalingMessage.Accept) {
        val state = calls[msg.callId]
        if (state == null || state.residentUserId != residentUserId || state.status != CallStatus.RINGING) {
            residents[residentUserId]?.send(SignalingMessage.ErrorMsg(msg.callId, "Appel expiré"))
            return
        }
        state.status = CallStatus.IN_CALL
        state.timeoutJob?.cancel()
        intercoms[state.buildingId]?.send(SignalingMessage.Accept(msg.callId))
    }

    /** Open the door during a call: relay OPEN to the intercom. Does NOT end the call. */
    suspend fun onOpenCall(residentUserId: String, msg: SignalingMessage.Open) {
        val state = calls[msg.callId]
        if (state == null || state.residentUserId != residentUserId) {
            residents[residentUserId]?.send(SignalingMessage.ErrorMsg(msg.callId, "Appel expiré"))
            return
        }
        val intercom = intercoms[state.buildingId]
        if (intercom == null) {
            residents[residentUserId]?.send(SignalingMessage.ErrorMsg(msg.callId, "Interphone hors ligne"))
            return
        }
        intercom.send(SignalingMessage.Open(msg.callId))
    }

    suspend fun onDeclineCall(residentUserId: String, msg: SignalingMessage.Decline) {
        val state = calls.remove(msg.callId) ?: return
        state.timeoutJob?.cancel()
        intercoms[state.buildingId]?.send(SignalingMessage.Decline(msg.callId))
    }

    /** Real BLE result relayed back to the resident. Call stays alive (talk continues). */
    suspend fun onOpenResultReported(buildingId: String, msg: SignalingMessage.OpenResult) {
        val state = calls[msg.callId] ?: return
        if (state.buildingId != buildingId) return
        residents[state.residentUserId]?.send(msg)
    }

    /** Hangup from either side: end the call, relay to the other peer. */
    suspend fun onHangupCall(callId: String, fromResident: Boolean) {
        val state = calls.remove(callId) ?: return
        state.timeoutJob?.cancel()
        val target = if (fromResident) intercoms[state.buildingId] else residents[state.residentUserId]
        target?.send(SignalingMessage.Hangup(callId))
    }

    /** Pure media pass-through from the resident to the intercom (Answer, IceCandidate). */
    suspend fun relayFromResident(residentUserId: String, msg: SignalingMessage.WebRTC) {
        val state = calls[msg.callId] ?: return
        if (state.residentUserId != residentUserId) return
        intercoms[state.buildingId]?.send(msg)
    }

    /** Pure media pass-through from the intercom to the resident (Offer, IceCandidate). */
    suspend fun relayFromIntercom(buildingId: String, msg: SignalingMessage.WebRTC) {
        val state = calls[msg.callId] ?: return
        if (state.buildingId != buildingId) return
        residents[state.residentUserId]?.send(msg)
    }
}
