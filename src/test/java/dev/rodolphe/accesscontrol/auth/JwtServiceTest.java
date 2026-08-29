package dev.rodolphe.accesscontrol.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * No Spring context here, and that is the point of having moved the secret into the constructor: the
 * Kotlin version read {@code System.getenv("JWT_SECRET")} itself, so testing it with a known secret
 * meant manipulating the environment of the test JVM.
 */
class JwtServiceTest {

    private final JwtService jwt = new JwtService("un-secret-de-test", "accesscontrol", "accesscontrol-app");

    @Test
    @DisplayName("a token it issued is accepted and yields the user id back")
    void roundTrip() {
        String token = jwt.generateToken("user-rodolphe");

        assertEquals("user-rodolphe", jwt.userIdFromToken(token));
    }

    @Test
    @DisplayName("a token signed with another secret is rejected")
    void rejectsForeignSignature() {
        var attacker = new JwtService("un-autre-secret", "accesscontrol", "accesscontrol-app");
        String forged = attacker.generateToken("user-rodolphe");

        // The claims are right and the structure is valid — only the signature does not match. This is
        // the whole point of signing: knowing the format is not enough to mint a token.
        assertNull(jwt.userIdFromToken(forged));
    }

    @Test
    @DisplayName("a token issued for another audience is rejected")
    void rejectsForeignAudience() {
        var other = new JwtService("un-secret-de-test", "accesscontrol", "une-autre-app");

        assertNull(jwt.userIdFromToken(other.generateToken("user-rodolphe")));
    }

    @Test
    @DisplayName("a tampered token is rejected")
    void rejectsTampering() {
        String token = jwt.generateToken("user-rodolphe");
        // Flip one character of the payload: the signature no longer covers what is being read.
        String tampered = token.substring(0, 20) + "X" + token.substring(21);

        assertNull(jwt.userIdFromToken(tampered));
    }

    @Test
    @DisplayName("garbage is rejected without throwing")
    void rejectsGarbage() {
        // The WebSocket handshake feeds whatever the first frame contains straight into this method,
        // so it has to answer null rather than blow up the connection handler.
        assertNull(jwt.userIdFromToken("pas-du-tout-un-jeton"));
        assertNull(jwt.userIdFromToken(""));
        assertNotNull(jwt.generateToken("user-rodolphe"));
    }
}
