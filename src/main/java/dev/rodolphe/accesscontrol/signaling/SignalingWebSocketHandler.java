package dev.rodolphe.accesscontrol.signaling;

import dev.rodolphe.accesscontrol.auth.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * The {@code /ws} endpoint. Port of {@code signalingRoute(jwt, intercomKey, hub)}.
 *
 * <p><strong>The shape of the code changes here, not the behaviour.</strong> Ktor suspended on a loop
 * over incoming frames, so the handshake was simply the first iteration and the per-connection state
 * lived in local variables. Spring calls back per event, so there is no loop and no locals: what a
 * connection has established about itself is kept in {@code session.getAttributes()} and read again
 * on the next message.
 *
 * <p>This is also the one place Spring Security cannot help. Identity does not arrive in a header but
 * in the first frame — a resident sends its JWT, an intercom its shared key — so the handshake is
 * authenticated by hand, here, exactly as the Kotlin route did.
 */
@Component
public class SignalingWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SignalingWebSocketHandler.class);

    private static final String CONNECTION = "signaling.connection";
    private static final String ROLE = "signaling.role";
    private static final String PEER_ID = "signaling.peerId";

    private static final String ROLE_RESIDENT = "resident";
    private static final String ROLE_INTERCOM = "intercom";

    /**
     * A mapper of its own, not the one Spring configures for REST — the counterpart of the Kotlin
     * server's dedicated {@code signalingJson}. The wire format of this protocol is a contract with
     * two deployed applications; it must not shift because someone tunes a global Jackson property.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    private final SignalingHub hub;
    private final JwtService jwt;
    private final String intercomKey;

    public SignalingWebSocketHandler(SignalingHub hub,
                                     JwtService jwt,
                                     @Value("${syekso.intercom-key}") String intercomKey) {
        this.hub = hub;
        this.jwt = jwt;
        this.intercomKey = intercomKey;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        SignalingMessage parsed = decode(message.getPayload());
        if (parsed == null) {
            // Undecodable frames are ignored rather than fatal, as in the Kotlin loop: a peer sending
            // one bad message should not lose a call in progress.
            return;
        }
        if (session.getAttributes().get(CONNECTION) == null) {
            handshake(session, parsed);
        } else {
            route(session, parsed);
        }
    }

    /** The first frame must be a HELLO that proves who is calling. Anything else closes the socket. */
    private void handshake(WebSocketSession session, SignalingMessage first) throws IOException {
        if (!(first instanceof SignalingMessage.Hello hello)) {
            session.close(CloseStatus.PROTOCOL_ERROR);
            return;
        }

        switch (hello.role() == null ? "" : hello.role()) {
            case ROLE_RESIDENT -> {
                String userId = hello.jwt() == null ? null : jwt.userIdFromToken(hello.jwt());
                if (userId == null) {
                    session.close(CloseStatus.POLICY_VIOLATION);
                    return;
                }
                hub.registerResident(userId, remember(session, ROLE_RESIDENT, userId));
                log.info("Resident {} connected", userId);
            }
            case ROLE_INTERCOM -> {
                String buildingId = hello.buildingId();
                if (!intercomKey.equals(hello.intercomKey()) || buildingId == null || buildingId.isBlank()) {
                    session.close(CloseStatus.POLICY_VIOLATION);
                    return;
                }
                hub.registerIntercom(buildingId, remember(session, ROLE_INTERCOM, buildingId));
                log.info("Intercom for building {} connected", buildingId);
            }
            default -> session.close(CloseStatus.PROTOCOL_ERROR);
        }
    }

    /**
     * What a peer is allowed to say depends on which side it is. A resident cannot ring itself, and an
     * intercom cannot accept a call on someone's behalf — the two branches are the whole authorisation
     * model of this protocol.
     *
     * <p>Pattern matching over the sealed hierarchy is the direct counterpart of Kotlin's
     * {@code when (msg) { is Open -> ... }}.
     *
     * <p><strong>Neither switch has a {@code default}, and that is the point.</strong> A {@code
     * default} makes any switch exhaustive, so the compiler stops checking: a twelfth message type
     * added to {@link SignalingMessage} would have compiled cleanly and then been dropped on the
     * floor at runtime, silently, on both sides. Listing the ignored types instead costs four lines
     * and turns the compiler into the reviewer — a new type cannot be introduced without someone
     * deciding, here, what each side may do with it.
     */
    private void route(WebSocketSession session, SignalingMessage message) {
        String role = (String) session.getAttributes().get(ROLE);
        String peerId = (String) session.getAttributes().get(PEER_ID);

        if (ROLE_RESIDENT.equals(role)) {
            switch (message) {
                case SignalingMessage.Accept accept -> hub.onAcceptCall(peerId, accept);
                case SignalingMessage.Open open -> hub.onOpenCall(peerId, open);
                case SignalingMessage.Decline decline -> hub.onDeclineCall(peerId, decline);
                case SignalingMessage.Hangup hangup -> hub.onHangupCall(hangup.callId(), true);
                case SignalingMessage.WebRtc media -> hub.relayFromResident(peerId, media);
                // Not a resident's to send: the handshake is over, ringing and reporting an opening
                // are the intercom's, and errors travel server to client only.
                case SignalingMessage.Hello ignored -> { }
                case SignalingMessage.Ring ignored -> { }
                case SignalingMessage.OpenResult ignored -> { }
                case SignalingMessage.ErrorMsg ignored -> { }
            }
        } else {
            switch (message) {
                case SignalingMessage.Ring ring -> hub.onRingCall(peerId, ring);
                case SignalingMessage.OpenResult result -> hub.onOpenResultReported(peerId, result);
                case SignalingMessage.Hangup hangup -> hub.onHangupCall(hangup.callId(), false);
                case SignalingMessage.WebRtc media -> hub.relayFromIntercom(peerId, media);
                // An intercom must never answer, open or decline on a resident's behalf. This is the
                // authorisation model of the protocol, and it is now enforced by the compiler.
                case SignalingMessage.Hello ignored -> { }
                case SignalingMessage.Accept ignored -> { }
                case SignalingMessage.Open ignored -> { }
                case SignalingMessage.Decline ignored -> { }
                case SignalingMessage.ErrorMsg ignored -> { }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ClientConnection connection = (ClientConnection) session.getAttributes().get(CONNECTION);
        if (connection != null) {
            // Drops the registration and abandons any call that depended on this peer.
            hub.unregister(connection);
            log.info("{} {} disconnected ({})", session.getAttributes().get(ROLE), connection.id(), status);
        }
    }

    /**
     * Binds a {@link ClientConnection} to this socket and stores it on the session.
     *
     * <p>The lambda is what keeps the hub free of any WebSocket type — and note that nothing here
     * guards against concurrent writes: {@code ClientConnection} already serialises sends behind its
     * own lock, which matters because a session is not safe for two threads to write at once.
     */
    private ClientConnection remember(WebSocketSession session, String role, String peerId) {
        ClientConnection connection = new ClientConnection(peerId,
                message -> session.sendMessage(new TextMessage(mapper.writeValueAsString(message))));
        session.getAttributes().put(CONNECTION, connection);
        session.getAttributes().put(ROLE, role);
        session.getAttributes().put(PEER_ID, peerId);
        return connection;
    }

    private SignalingMessage decode(String payload) {
        try {
            return mapper.readValue(payload, SignalingMessage.class);
        } catch (JacksonException e) {
            log.debug("Ignoring undecodable frame", e);
            return null;
        }
    }
}
