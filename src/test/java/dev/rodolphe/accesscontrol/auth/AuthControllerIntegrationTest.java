package dev.rodolphe.accesscontrol.auth;

import com.jayway.jsonpath.JsonPath;
import dev.rodolphe.accesscontrol.users.User;
import dev.rodolphe.accesscontrol.users.UserRepository;
import dev.rodolphe.accesscontrol.doors.ResidentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The one route that has to stay open, and the token it hands out.
 *
 * <p>Two things here exist nowhere else in the suite. First, {@code /auth/login} is the only endpoint
 * whose correctness depends on a security rule <em>reopening</em> it: the starter closes everything,
 * and a mistake in {@code authorizeHttpRequests} would leave the application unable to issue a single
 * token — while every unit test kept passing.
 *
 * <p>Second, the round trip. {@code JwtServiceTest} proves that what {@code generateToken} signs,
 * {@code userIdFromToken} verifies; it cannot prove that the token travels through JSON, comes back
 * in an {@code Authorization} header, crosses the filter chain and arrives as a principal. Only a
 * request can.
 *
 * <p>{@code PasswordEncoder} is deliberately <em>not</em> mocked: the stored hash is produced by the
 * real BCrypt, so the comparison the controller performs is the real one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

    private static final String EMAIL = "rodolphe@example.com";
    private static final String PASSWORD = "password";

    @Autowired private MockMvc mvc;
    @Autowired private PasswordEncoder encoder;

    @MockitoBean private UserRepository users;
    @MockitoBean private ResidentService residents;

    /** The seed resident, with a hash BCrypt really produced. */
    private User storedUser() {
        return new User("user-rodolphe", EMAIL, encoder.encode(PASSWORD), "Rodolphe", List.of("bld-1"));
    }

    private String login(String email, String password) {
        return """
                {"email":"%s","password":"%s"}""".formatted(email, password);
    }

    @Test
    @DisplayName("/auth/login is reachable with no credentials at all — it is what hands them out")
    void loginIsPublic() throws Exception {
        given(users.findByEmail(EMAIL)).willReturn(Optional.of(storedUser()));

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.id").value("user-rodolphe"))
                .andExpect(jsonPath("$.user.displayName").value("Rodolphe"));
        // Had anyRequest() been left at .authenticated(), this would be 401 and the whole API would
        // be unusable — no route could ever issue the token the other routes require.
    }

    @Test
    @DisplayName("the issued token opens a protected route — the whole loop, over HTTP")
    void theIssuedTokenOpensAProtectedRoute() throws Exception {
        given(users.findByEmail(EMAIL)).willReturn(Optional.of(storedUser()));

        String body = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String token = JsonPath.read(body, "$.token");

        mvc.perform(get("/me/doors").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // The 200 alone would not prove much: an unstubbed mock returns null and the controller
        // answers 200 anyway. This is the real assertion — the service was reached with the id the
        // token carried, so the claim survived signing, JSON, the header, the verifier and
        // @AuthenticationPrincipal without being altered once.
        then(residents).should().doorsOf("user-rodolphe");
    }

    @Test
    @DisplayName("a wrong password is a 401 from the advice, not from the entry point")
    void wrongPasswordIsRejected() throws Exception {
        given(users.findByEmail(EMAIL)).willReturn(Optional.of(storedUser()));

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login(EMAIL, "mauvais")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Email ou mot de passe incorrect"));
        // Same status as an unauthenticated /me/** call, produced by a different layer: this one
        // travels as an exception through ApiExceptionHandler, having reached Spring MVC. The other
        // never leaves the filter chain and is written by jsonEntryPoint. Only the wording tells
        // them apart, which is why both wordings are asserted somewhere.
    }

    @Test
    @DisplayName("an unknown email answers exactly like a wrong password — no address probing")
    void unknownEmailIsIndistinguishable() throws Exception {
        given(users.findByEmail("inconnu@example.com")).willReturn(Optional.empty());

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login("inconnu@example.com", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Email ou mot de passe incorrect"));
        // The property under test is that this response is byte-identical to the one above. A future
        // "utilisateur introuvable" would be friendlier and would turn the endpoint into an
        // enumeration oracle.
    }

    @Test
    @DisplayName("a blank field is refused before the database is ever consulted")
    void validationRunsBeforeTheController() throws Exception {
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login("", "")))
                .andExpect(status().isBadRequest());

        then(users).should(never()).findByEmail(any());
        // Not a detail: @Valid is enforced by the argument resolver, so the controller body never
        // runs. Without the MethodArgumentNotValidException handler this would be a 500 instead.
    }

    @Test
    @DisplayName("the response carries no password hash")
    void responseNeverLeaksTheHash() throws Exception {
        User stored = storedUser();
        given(users.findByEmail(EMAIL)).willReturn(Optional.of(stored));

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(stored.passwordHash()))));
        // What this really guards is the decision to keep UserDto apart from the User document.
        // Returning the document directly would compile, pass every unit test, and ship the hash.
        assertThat(stored.passwordHash()).startsWith("$2a$");
    }
}
