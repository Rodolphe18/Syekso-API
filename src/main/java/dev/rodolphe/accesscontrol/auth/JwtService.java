package dev.rodolphe.accesscontrol.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * Issues and verifies the resident's JWT — the direct port of the Kotlin {@code JwtService}.
 *
 * <p>One deliberate change. The Kotlin version reached for its own configuration:
 *
 * <pre>class JwtService(private val secret: String = System.getenv("JWT_SECRET") ?: "dev-secret-change-me")</pre>
 *
 * <p>Here the values are <em>injected</em> instead. The class no longer knows where its secret comes
 * from, which is what makes it testable: a test constructs it with a known secret rather than having
 * to manipulate the environment of the test JVM. The fallback moved to application.properties, where
 * configuration belongs.
 *
 * <p>Verification is only used from iteration 3 onwards, but issuing is needed now for /auth/login.
 */
@Service
public class JwtService {

    public static final String CLAIM_USER_ID = "userId";

    private static final long TOKEN_TTL_MS = 7L * 24 * 60 * 60 * 1000; // 7 days

    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final String issuer;
    private final String audience;

    public JwtService(@Value("${syekso.jwt.secret}") String secret,
                      @Value("${syekso.jwt.issuer}") String issuer,
                      @Value("${syekso.jwt.audience}") String audience) {
        this.issuer = issuer;
        this.audience = audience;
        this.algorithm = Algorithm.HMAC256(secret);
        this.verifier = JWT.require(algorithm).withIssuer(issuer).withAudience(audience).build();
    }

    public String generateToken(String userId) {
        return JWT.create()
                .withIssuer(issuer)
                .withAudience(audience)
                .withClaim(CLAIM_USER_ID, userId)
                .withExpiresAt(new Date(System.currentTimeMillis() + TOKEN_TTL_MS))
                .sign(algorithm);
    }

    /**
     * Verifies a raw token and returns its user-id claim, or {@code null} if it is invalid or expired.
     *
     * <p>Catches {@link JWTVerificationException} specifically rather than the Kotlin version's blanket
     * {@code Exception}: a malformed token must return null, but a programming error should still
     * surface as a failure instead of silently reading as "not authenticated".
     */
    public String userIdFromToken(String token) {
        try {
            return verifier.verify(token).getClaim(CLAIM_USER_ID).asString();
        } catch (JWTVerificationException e) {
            return null;
        }
    }
}
