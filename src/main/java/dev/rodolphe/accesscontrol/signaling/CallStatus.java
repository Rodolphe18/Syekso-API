package dev.rodolphe.accesscontrol.signaling;

/**
 * EXPIRED has no Kotlin counterpart and never reaches the wire. It exists so that the ring timeout
 * and an incoming accept can race for the same call and have exactly one of them win — see
 * {@link CallState#markInCallIfRinging()}.
 */
public enum CallStatus {
    RINGING,
    IN_CALL,
    EXPIRED
}
