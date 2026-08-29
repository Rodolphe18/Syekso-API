package dev.rodolphe.accesscontrol.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads the bearer token off every request and, when it is valid, records who the caller is.
 *
 * <p>Replaces Ktor's {@code authenticate("auth-jwt") { userRoutes(storage) }}, and the change of
 * placement is the whole point: in Ktor, authentication wrapped a group of routes inside the routing
 * tree. Here it happens in a servlet filter, before Spring MVC has even chosen a controller — which
 * is why {@code SecurityConfig}, not the controllers, decides what is protected.
 *
 * <p><strong>This filter never rejects anything.</strong> It answers "who is calling", and nothing
 * else; refusing access is the chain's job, further down, driven by the rules in SecurityConfig. A
 * request with no token passes straight through unauthenticated — exactly what a public endpoint
 * needs. Authentication and authorisation stay two separate steps.
 *
 * <p>Extending {@link OncePerRequestFilter} rather than implementing {@code Filter} directly: the
 * container re-runs an ordinary filter on every internal dispatch (forward, async, error page), which
 * would mean re-decoding and re-verifying the token several times per request. The base class tracks
 * that and guarantees {@code doFilterInternal} runs exactly once — which is also why the method is
 * named that way, {@code doFilter} being already taken by the bookkeeping.
 */
@Component
public class JwtTokenVerifierFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwt;

    public JwtTokenVerifierFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String userId = jwt.userIdFromToken(header.substring(BEARER_PREFIX.length()));
            if (userId != null) {
                // The principal is the user id, so a controller can ask for it with
                // @AuthenticationPrincipal — the counterpart of Ktor's ApplicationCall.userId().
                // Credentials are null: the token has been verified and keeping it around would only
                // widen what a leak exposes. No authorities either — this application has one kind of
                // user, and inventing roles it does not have would be decoration, not security.
                var authentication = new UsernamePasswordAuthenticationToken(userId, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        chain.doFilter(request, response);
    }
}
