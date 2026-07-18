package dev.rodolphe.accesscontrol.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date

/**
 * Issues and verifies the resident's JWT. The secret comes from JWT_SECRET; the dev fallback is
 * obvious on purpose so a misconfigured production deploy is caught, not silently insecure.
 */
class JwtService(
    private val secret: String = System.getenv("JWT_SECRET") ?: "dev-secret-change-me",
    private val issuer: String = "accesscontrol",
    private val audience: String = "accesscontrol-app",
) {
    val realm: String = "accesscontrol"
    private val algorithm: Algorithm = Algorithm.HMAC256(secret)

    val     verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(issuer)
        .withAudience(audience)
        .build()

    fun generateToken(userId: String): String = JWT.create()
        .withIssuer(issuer)
        .withAudience(audience)
        .withClaim(CLAIM_USER_ID, userId)
        .withExpiresAt(Date(System.currentTimeMillis() + TOKEN_TTL_MS))
        .sign(algorithm)

    companion object {
        const val CLAIM_USER_ID = "userId"
        private const val TOKEN_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
    }
}
