package dev.rodolphe.accesscontrol.intercom;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates the lobby intercom by the shared key it presents in {@code X-Intercom-Key}.
 *
 * <p>Replaces the guard clause that three controller methods repeated, and that the Kotlin server
 * repeated in all three routes. Like {@link JwtTokenVerifierFilter}, it never rejects: it records
 * whether the caller proved itself, and the chain decides.
 *
 * <p>A shared secret in a header is a weak scheme — anyone who reads the key becomes the intercom.
 * It is what the Kotlin server does and what the device firmware sends, so it is kept as-is; the
 * honest upgrade is a per-device credential, which is out of scope for this migration.
 */
@Component
public class IntercomKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Intercom-Key";

    private final String intercomKey;

    public IntercomKeyAuthenticationFilter(@Value("${syekso.intercom-key}") String intercomKey) {
        this.intercomKey = intercomKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (intercomKey.equals(request.getHeader(HEADER))) {
            var authentication =
                    new UsernamePasswordAuthenticationToken("intercom", null, List.of());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }
}
