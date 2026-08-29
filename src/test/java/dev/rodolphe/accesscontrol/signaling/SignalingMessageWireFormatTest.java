package dev.rodolphe.accesscontrol.signaling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the wire format against ground truth.
 *
 * <p>The expected strings are not written from a reading of the serializer's documentation: they were
 * produced by running kotlinx.serialization on the Kotlin messages and dumping the result. Two Android
 * applications parse exactly these bytes, and neither will be redeployed because the server changed.
 *
 * <p>A plain mapper is used rather than Spring's: the WebSocket handler serialises these messages
 * itself, exactly as the Kotlin server does with its own {@code signalingJson}, so this is the mapper
 * production will actually use.
 */
class SignalingMessageWireFormatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** @param kotlinJson what kotlinx.serialization emits — captured, not guessed. */
    private record Case(String label, SignalingMessage message, String kotlinJson) {
    }

    private static final List<Case> CASES = List.of(
            new Case("hello-resident",
                    SignalingMessage.Hello.resident("JWT"),
                    "{\"type\":\"hello\",\"role\":\"resident\",\"jwt\":\"JWT\"}"),
            new Case("hello-intercom",
                    SignalingMessage.Hello.intercom("KEY", "bld-1"),
                    "{\"type\":\"hello\",\"role\":\"intercom\",\"intercomKey\":\"KEY\",\"buildingId\":\"bld-1\"}"),
            new Case("ring-minimal",
                    new SignalingMessage.Ring("c1", null, null),
                    "{\"type\":\"ring\",\"callId\":\"c1\"}"),
            new Case("ring-full",
                    new SignalingMessage.Ring("c1", "u1", "Porte d'entrée"),
                    "{\"type\":\"ring\",\"callId\":\"c1\",\"targetUserId\":\"u1\",\"doorName\":\"Porte d'entrée\"}"),
            new Case("open",
                    new SignalingMessage.Open("c1"),
                    "{\"type\":\"open\",\"callId\":\"c1\"}"),
            new Case("decline",
                    new SignalingMessage.Decline("c1"),
                    "{\"type\":\"decline\",\"callId\":\"c1\"}"),
            new Case("accept",
                    new SignalingMessage.Accept("c1"),
                    "{\"type\":\"accept\",\"callId\":\"c1\"}"),
            new Case("hangup",
                    new SignalingMessage.Hangup("c1"),
                    "{\"type\":\"hangup\",\"callId\":\"c1\"}"),
            new Case("open_result-success",
                    new SignalingMessage.OpenResult("c1", true, null),
                    "{\"type\":\"open_result\",\"callId\":\"c1\",\"success\":true}"),
            new Case("open_result-failure",
                    new SignalingMessage.OpenResult("c1", false, "NotFound"),
                    "{\"type\":\"open_result\",\"callId\":\"c1\",\"success\":false,\"reason\":\"NotFound\"}"),
            new Case("offer",
                    new SignalingMessage.Offer("c1", "SDP"),
                    "{\"type\":\"offer\",\"callId\":\"c1\",\"sdp\":\"SDP\"}"),
            new Case("answer",
                    new SignalingMessage.Answer("c1", "SDP"),
                    "{\"type\":\"answer\",\"callId\":\"c1\",\"sdp\":\"SDP\"}"),
            new Case("ice-full",
                    new SignalingMessage.IceCandidate("c1", "cand", "0", 1),
                    "{\"type\":\"ice\",\"callId\":\"c1\",\"sdp\":\"cand\",\"sdpMid\":\"0\",\"sdpMLineIndex\":1}"),
            new Case("error-without-call",
                    SignalingMessage.ErrorMsg.of("Résident indisponible"),
                    "{\"type\":\"error\",\"message\":\"Résident indisponible\"}"),
            new Case("error-with-call",
                    new SignalingMessage.ErrorMsg("c1", "Pas de réponse"),
                    "{\"type\":\"error\",\"callId\":\"c1\",\"message\":\"Pas de réponse\"}"));

    @Test
    @DisplayName("every message serialises to exactly what the Kotlin server puts on the wire")
    void serialisesIdentically() {
        assertAll(CASES.stream().map(c -> () ->
                assertEquals(c.kotlinJson(), mapper.writeValueAsString(c.message()), c.label())));
    }

    @Test
    @DisplayName("every message the clients send is parsed back into the right record")
    void parsesWhatTheClientsSend() {
        assertAll(CASES.stream().map(c -> () ->
                assertEquals(c.message(), mapper.readValue(c.kotlinJson(), SignalingMessage.class), c.label())));
    }

    /**
     * The single documented divergence. kotlinx omits {@code sdpMLineIndex} when it is 0 because that
     * is the declared default; Jackson writes it, since {@code NON_NULL} cannot drop a primitive and
     * {@code NON_DEFAULT} would also drop {@code success:false} from OpenResult, which would be a real
     * bug. Both clients declare the field with a default of 0, so the two forms decode identically —
     * which the second assertion proves rather than assumes.
     */
    @Test
    @DisplayName("ice without sdpMid: writes an explicit 0 where Kotlin omits it, and both parse the same")
    void iceMinimalDivergence() {
        var message = new SignalingMessage.IceCandidate("c1", "cand", null, 0);

        assertEquals("{\"type\":\"ice\",\"callId\":\"c1\",\"sdp\":\"cand\",\"sdpMLineIndex\":0}",
                mapper.writeValueAsString(message));

        String kotlinForm = "{\"type\":\"ice\",\"callId\":\"c1\",\"sdp\":\"cand\"}";
        assertEquals(message, mapper.readValue(kotlinForm, SignalingMessage.class));
    }
}
