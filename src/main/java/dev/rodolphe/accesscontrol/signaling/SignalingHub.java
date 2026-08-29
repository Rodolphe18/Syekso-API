package dev.rodolphe.accesscontrol.signaling;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-memory relay between intercoms and residents: who is connected, which calls are in flight,
 * and who may say what to whom.
 *
 * <p>Nothing here is persisted. On a restart every client reconnects and the state rebuilds itself —
 * a call that was ringing simply stops ringing, which is the right outcome anyway.
 *
 * <p>A singleton by necessity, not by convenience. Two instances would mean an intercom registered in
 * one and a resident in the other, and nobody ever reaching anybody. Spring's default scope gives
 * that guarantee for free, where the Kotlin server obtained it by creating the object once in
 * {@code module()} and passing the same reference everywhere.
 *
 * <p>Being a shared singleton crossed by every connection at once, it holds only concurrent maps, and
 * every state transition is decided by a compare-and-set rather than by a read followed by a write.
 */
@Component
public class SignalingHub {

    private final Map<String, ClientConnection> residents = new ConcurrentHashMap<>(); // userId -> conn
    private final Map<String, ClientConnection> intercoms = new ConcurrentHashMap<>(); // buildingId -> conn
    private final Map<String, CallState> calls = new ConcurrentHashMap<>();            // callId -> state

    private final TaskScheduler scheduler;
    private final long ringTimeoutMs;

    /**
     * The scheduler replaces the {@code CoroutineScope} the Kotlin hub was handed. Its only job is the
     * "nobody picked up" timer; injecting it rather than creating one keeps the timing controllable
     * from a test, which is how the expiry path gets covered at all.
     */
    public SignalingHub(TaskScheduler scheduler,
                        @Value("${syekso.signaling.ring-timeout-ms:30000}") long ringTimeoutMs) {
        this.scheduler = scheduler;
        this.ringTimeoutMs = ringTimeoutMs;
    }

    public void registerResident(String userId, ClientConnection connection) {
        residents.put(userId, connection);
    }

    public void registerIntercom(String buildingId, ClientConnection connection) {
        intercoms.put(buildingId, connection);
    }

    /** Drops a closed connection and abandons any call that depended on it. */
    public void unregister(ClientConnection connection) {
        residents.values().removeIf(known -> known == connection);
        intercoms.values().removeIf(known -> known == connection);
        calls.entrySet().removeIf(entry -> {
            CallState state = entry.getValue();
            boolean broken = !residents.containsKey(state.residentUserId())
                    || !intercoms.containsKey(state.buildingId());
            if (broken) {
                state.cancelTimeout();
            }
            return broken;
        });
    }

    /** An intercom rings a resident. One call at a time per intercom. */
    public void onRingCall(String buildingId, SignalingMessage.Ring message) {
        String targetUserId = message.targetUserId();
        if (targetUserId == null) {
            return;
        }
        if (calls.values().stream().anyMatch(state -> state.buildingId().equals(buildingId))) {
            sendToIntercom(buildingId, new SignalingMessage.ErrorMsg(message.callId(), "Appel déjà en cours"));
            return;
        }
        ClientConnection resident = residents.get(targetUserId);
        if (resident == null) {
            sendToIntercom(buildingId, new SignalingMessage.ErrorMsg(message.callId(), "Résident indisponible"));
            return;
        }

        CallState state = new CallState(buildingId, targetUserId);
        calls.put(message.callId(), state);
        state.setTimeout(scheduler.schedule(
                () -> expire(message.callId()), Instant.now().plusMillis(ringTimeoutMs)));

        resident.send(new SignalingMessage.Ring(message.callId(), targetUserId, message.doorName()));
    }

    /** Nobody answered in time. Does nothing if the call was accepted in the meantime. */
    private void expire(String callId) {
        CallState state = calls.get(callId);
        if (state == null || !state.markExpiredIfRinging()) {
            return;
        }
        calls.remove(callId);
        sendToIntercom(state.buildingId(), new SignalingMessage.ErrorMsg(callId, "Pas de réponse"));
        sendToResident(state.residentUserId(), new SignalingMessage.ErrorMsg(callId, "TIMED_OUT"));
    }

    /** The resident answered: ringing becomes in-call, and the intercom is told to make its offer. */
    public void onAcceptCall(String residentUserId, SignalingMessage.Accept message) {
        CallState state = calls.get(message.callId());
        if (state == null
                || !state.residentUserId().equals(residentUserId)
                || !state.markInCallIfRinging()) {
            sendToResident(residentUserId, new SignalingMessage.ErrorMsg(message.callId(), "Appel expiré"));
            return;
        }
        state.cancelTimeout();
        sendToIntercom(state.buildingId(), new SignalingMessage.Accept(message.callId()));
    }

    /** Open the door mid-call. Deliberately does not end the call — the conversation continues. */
    public void onOpenCall(String residentUserId, SignalingMessage.Open message) {
        CallState state = calls.get(message.callId());
        if (state == null || !state.residentUserId().equals(residentUserId)) {
            sendToResident(residentUserId, new SignalingMessage.ErrorMsg(message.callId(), "Appel expiré"));
            return;
        }
        ClientConnection intercom = intercoms.get(state.buildingId());
        if (intercom == null) {
            sendToResident(residentUserId, new SignalingMessage.ErrorMsg(message.callId(), "Interphone hors ligne"));
            return;
        }
        intercom.send(new SignalingMessage.Open(message.callId()));
    }

    public void onDeclineCall(String residentUserId, SignalingMessage.Decline message) {
        CallState state = calls.remove(message.callId());
        if (state == null) {
            return;
        }
        state.cancelTimeout();
        sendToIntercom(state.buildingId(), new SignalingMessage.Decline(message.callId()));
    }

    /** The real BLE outcome, relayed back to the resident. The call stays up. */
    public void onOpenResultReported(String buildingId, SignalingMessage.OpenResult message) {
        CallState state = calls.get(message.callId());
        if (state == null || !state.buildingId().equals(buildingId)) {
            return;
        }
        sendToResident(state.residentUserId(), message);
    }

    /** Hangup from either side: end the call and tell the other peer. */
    public void onHangupCall(String callId, boolean fromResident) {
        CallState state = calls.remove(callId);
        if (state == null) {
            return;
        }
        state.cancelTimeout();
        if (fromResident) {
            sendToIntercom(state.buildingId(), new SignalingMessage.Hangup(callId));
        } else {
            sendToResident(state.residentUserId(), new SignalingMessage.Hangup(callId));
        }
    }

    /** Media negotiation from the resident, passed through without being read. */
    public void relayFromResident(String residentUserId, SignalingMessage.WebRtc message) {
        CallState state = calls.get(message.callId());
        if (state == null || !state.residentUserId().equals(residentUserId)) {
            return;
        }
        sendToIntercom(state.buildingId(), message);
    }

    /** Media negotiation from the intercom, passed through without being read. */
    public void relayFromIntercom(String buildingId, SignalingMessage.WebRtc message) {
        CallState state = calls.get(message.callId());
        if (state == null || !state.buildingId().equals(buildingId)) {
            return;
        }
        sendToResident(state.residentUserId(), message);
    }

    private void sendToResident(String userId, SignalingMessage message) {
        ClientConnection connection = residents.get(userId);
        if (connection != null) {
            connection.send(message);
        }
    }

    private void sendToIntercom(String buildingId, SignalingMessage message) {
        ClientConnection connection = intercoms.get(buildingId);
        if (connection != null) {
            connection.send(message);
        }
    }
}
