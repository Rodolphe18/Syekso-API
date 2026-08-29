package dev.rodolphe.accesscontrol.signaling;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The messages exchanged over {@code /ws}, in the exact shape two Android applications already
 * expect. This is the one file in the migration where a wrong byte breaks production immediately:
 * the resident app and the intercom app both parse this protocol with kotlinx.serialization, and
 * neither will be redeployed for the server's benefit.
 *
 * <p>The Kotlin original relies on {@code Json { classDiscriminator = "type"; encodeDefaults = false }}.
 * Reproducing that here takes two annotations:
 *
 * <ul>
 *   <li>{@link JsonTypeInfo} + {@link JsonSubTypes} put a {@code "type"} property first and map each
 *       value to a record — the eleven names below are the wire contract and must not be renamed.</li>
 *   <li>{@link JsonInclude} with {@code NON_NULL} drops absent optional fields, which is what
 *       {@code encodeDefaults = false} does for nulls.</li>
 * </ul>
 *
 * <p><strong>Why not {@code NON_DEFAULT}</strong>, which would match kotlinx even more closely: it
 * would also drop {@code success} from {@link OpenResult} whenever it is {@code false}, because
 * {@code false} is a boolean's default. That field has no default in Kotlin and is always emitted;
 * losing it would turn every failed door opening into a missing field. The one remaining difference
 * is that {@code sdpMLineIndex} is written as {@code 0} where Kotlin omits it — harmless, since the
 * clients declare that field with a default of 0 and read the two forms identically.
 *
 * <p>Two families under one protocol, as in the original: {@link CallControl} messages the hub
 * interprets, and {@link WebRtc} messages it relays without ever reading them.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SignalingMessage.Hello.class, name = "hello"),
        @JsonSubTypes.Type(value = SignalingMessage.Ring.class, name = "ring"),
        @JsonSubTypes.Type(value = SignalingMessage.Open.class, name = "open"),
        @JsonSubTypes.Type(value = SignalingMessage.Decline.class, name = "decline"),
        @JsonSubTypes.Type(value = SignalingMessage.Accept.class, name = "accept"),
        @JsonSubTypes.Type(value = SignalingMessage.Hangup.class, name = "hangup"),
        @JsonSubTypes.Type(value = SignalingMessage.OpenResult.class, name = "open_result"),
        @JsonSubTypes.Type(value = SignalingMessage.Offer.class, name = "offer"),
        @JsonSubTypes.Type(value = SignalingMessage.Answer.class, name = "answer"),
        @JsonSubTypes.Type(value = SignalingMessage.IceCandidate.class, name = "ice"),
        @JsonSubTypes.Type(value = SignalingMessage.ErrorMsg.class, name = "error"),
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface SignalingMessage {

    /** Interpreted by the hub: call lifecycle and the door-open domain. All carry a call id. */
    sealed interface CallControl extends SignalingMessage {
        String callId();
    }

    /** Media negotiation, opaque to the hub and relayed as-is. Swapping WebRTC would touch only these. */
    sealed interface WebRtc extends SignalingMessage {
        String callId();
    }

    /** The handshake frame. A resident sends its jwt; an intercom sends its key and building. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Hello(String role, String jwt, String intercomKey, String buildingId)
            implements SignalingMessage {

        public static Hello resident(String jwt) {
            return new Hello("resident", jwt, null, null);
        }

        public static Hello intercom(String intercomKey, String buildingId) {
            return new Hello("intercom", null, intercomKey, buildingId);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Ring(String callId, String targetUserId, String doorName) implements CallControl {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Open(String callId) implements CallControl {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Decline(String callId) implements CallControl {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Accept(String callId) implements CallControl {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Hangup(String callId) implements CallControl {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OpenResult(String callId, boolean success, String reason) implements CallControl {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Offer(String callId, String sdp) implements WebRtc {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Answer(String callId, String sdp) implements WebRtc {
    }

    /**
     * {@code sdpMLineIndex} is boxed, and it is not a style choice.
     *
     * <p>Kotlin declares it {@code = 0}, so kotlinx omits it whenever it is 0 — which is the common
     * case, the first media line. Jackson 3 enables {@code FAIL_ON_NULL_FOR_PRIMITIVES} by default
     * (Jackson 2 did not), so an absent field aimed at an {@code int} throws instead of defaulting.
     * Every ordinary ICE candidate the apps send would have failed to parse, and no call would have
     * connected.
     *
     * <p>Boxing plus a compact constructor puts the default back where the Kotlin declaration had it,
     * and does so in the type rather than in a mapper setting — so it holds whatever mapper reads it.
     * Same rule as {@code PinCode}: box what has a semantic default, leave primitive what is
     * required. {@code OpenResult.success} stays an unboxed boolean for exactly that reason.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record IceCandidate(String callId, String sdp, String sdpMid, Integer sdpMLineIndex)
            implements WebRtc {

        public IceCandidate {
            sdpMLineIndex = sdpMLineIndex == null ? 0 : sdpMLineIndex;
        }
    }

    /** Connection handshake failures and call errors alike; {@code callId} is null before a call exists. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ErrorMsg(String callId, String message) implements SignalingMessage {

        public static ErrorMsg of(String message) {
            return new ErrorMsg(null, message);
        }
    }
}
