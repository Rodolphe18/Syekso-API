package dev.rodolphe.accesscontrol.config;

import dev.rodolphe.accesscontrol.intercom.IntercomKeyAuthenticationFilter;
import dev.rodolphe.accesscontrol.auth.JwtTokenVerifierFilter;
import dev.rodolphe.accesscontrol.shared.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * Where the application says what is protected, and by which scheme.
 *
 * <p>Two callers, two credentials that have nothing in common: a resident carries a JWT, a lobby
 * intercom carries a shared key in a header. Rather than teaching one chain both schemes, each gets
 * its own {@link SecurityFilterChain}, selected by the path it declares.
 *
 * <p>{@code FilterChainProxy} picks the <strong>first chain whose matcher accepts the request</strong>
 * and runs only that one, so {@code @Order} is load-bearing. The narrow chain has to come first; the
 * catch-all chain declares no matcher and therefore accepts everything, which would swallow
 * {@code /intercom/**} if it were consulted first.
 *
 * <p>A consequence worth seeing: a request to {@code /intercom/validate} never meets
 * {@code JwtTokenVerifierFilter} at all. The chains are alternatives, not layers.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** The intercom's own scheme, on its own paths. */
    @Bean
    @Order(1)
    public SecurityFilterChain intercomSecurity(HttpSecurity http,
                                                IntercomKeyAuthenticationFilter keyFilter,
                                                ObjectMapper objectMapper) throws Exception {
        return http
                .securityMatcher("/intercom/**")
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Every path this chain handles requires the key: there is no public intercom route.
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(jsonEntryPoint(objectMapper, "Interphone non autorisé")))
                .addFilterBefore(keyFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * Everything else. Adding spring-boot-starter-security locks every endpoint by default; this is
     * what reopens the public ones, so a route left undeclared stays closed by accident rather than
     * opening by accident.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurity(HttpSecurity http,
                                           JwtTokenVerifierFilter jwtFilter,
                                           ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/me/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(jsonEntryPoint(objectMapper, "Non authentifié")))
                // Before the authorization filter, necessarily: the chain has to know who is calling
                // before it can decide whether they may proceed.
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * What happens when an unauthenticated caller reaches a protected route.
     *
     * <p>Spring Security's default assumes a browser and redirects to a login page — which an Android
     * client would receive as HTML it cannot parse. This returns a plain 401 in the same
     * {@link ErrorResponse} shape as the rest of the API.
     *
     * <p>It cannot be done in {@code ApiExceptionHandler}: the request is refused inside the filter
     * chain, before Spring MVC is reached, so no {@code @ExceptionHandler} is ever consulted. Two
     * different layers, two different mechanisms.
     *
     * <p>Not a bean, but a factory: the two chains want the same behaviour with different wording, and
     * a shared bean could only say one of them.
     *
     * <p>Note the import: Spring Boot 4 moved to Jackson 3, whose root package is {@code tools.jackson}
     * and no longer {@code com.fasterxml.jackson}.
     */
    private static AuthenticationEntryPoint jsonEntryPoint(ObjectMapper objectMapper, String message) {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(), new ErrorResponse(message));
        };
    }
}
