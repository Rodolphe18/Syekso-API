package dev.rodolphe.accesscontrol;

import dev.rodolphe.accesscontrol.intercom.IntercomKeyAuthenticationFilter;
import dev.rodolphe.accesscontrol.auth.JwtService;
import dev.rodolphe.accesscontrol.intercom.IntercomService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The part of SecurityConfig that no unit test can reach: which chain answers a given request.
 *
 * <p>{@code FilterChainProxy} runs the <strong>first</strong> chain whose matcher accepts the request
 * and only that one, so the two schemes are alternatives rather than layers. That statement is a
 * claim about {@code @Order} and {@code securityMatcher} — configuration, not code — and the only way
 * to check it is to send a request and look at what comes back.
 *
 * <p>The two crossed cases below are the point of the class. A resident's JWT must not open the
 * intercom routes, and the intercom key must not open the resident routes. Both would still be
 * refused if the chains were misordered, but with the <em>other</em> chain's wording — which is why
 * the assertions read the error message and not only the status.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityChainIntegrationTest {

    private static final String PIN = "123456";

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;

    /** Read from the test properties rather than repeated here: the test cannot drift from the app. */
    @Value("${syekso.intercom-key}") private String intercomKey;

    @MockitoBean private IntercomService intercom;

    private static String validateBody() {
        return "{\"pin\":\"" + PIN + "\"}";
    }

    @Test
    @DisplayName("an intercom route with no key is refused in the intercom chain's own wording")
    void intercomWithoutKeyIsRefused() throws Exception {
        mvc.perform(post("/intercom/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validateBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Interphone non autorisé"));

        then(intercom).should(never()).validate(any());
    }

    @Test
    @DisplayName("a wrong key is refused like no key at all")
    void intercomWithWrongKeyIsRefused() throws Exception {
        mvc.perform(post("/intercom/validate")
                        .header(IntercomKeyAuthenticationFilter.HEADER, "pas-la-bonne-cle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validateBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Interphone non autorisé"));
    }

    @Test
    @DisplayName("with the key, the request reaches the controller")
    void intercomWithTheKeyReachesTheController() throws Exception {
        mvc.perform(post("/intercom/validate")
                        .header(IntercomKeyAuthenticationFilter.HEADER, intercomKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validateBody()))
                .andExpect(status().isOk());

        // Ce que ce test doit prouver est que la chaine a laisse passer, pas ce que le service
        // repond — les neuf tests d'IntercomServiceTest s'en chargent. Verifier l'appel plutot que
        // d'assertionner la charge utile dit la meme chose en plus precis (le PIN est bien arrive)
        // et evite a ce test, qui vit a la racine, d'avoir besoin d'un type interne a intercom.
        then(intercom).should().validate(PIN);
    }

    @Test
    @DisplayName("a perfectly valid resident token does not open the intercom chain")
    void residentTokenDoesNotOpenTheIntercomChain() throws Exception {
        mvc.perform(post("/intercom/validate")
                        .header("Authorization", "Bearer " + jwt.generateToken("user-rodolphe"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validateBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Interphone non autorisé"));
        // The token is genuine — the same one that opens /me/doors two tests down. It fails here
        // because @Order(1) selected the intercom chain, in which JwtTokenVerifierFilter does not
        // even appear. Nothing read the header; there was no filter to read it.
    }

    @Test
    @DisplayName("the intercom key does not open a resident route")
    void intercomKeyDoesNotOpenTheResidentChain() throws Exception {
        mvc.perform(get("/me/doors").header(IntercomKeyAuthenticationFilter.HEADER, intercomKey))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Non authentifié"));
        // The mirror image: /me/doors falls to the catch-all chain, which carries no key filter.
        // Note the wording — reading "Interphone non autorisé" here would mean the intercom chain
        // had widened its matcher and was answering for routes that are not its own.
    }

    @Test
    @DisplayName("an authenticated intercom with a missing parameter gets a 400, not a 500")
    void missingQueryParameterIsBadRequest() throws Exception {
        mvc.perform(get("/intercom/directory")
                        .header(IntercomKeyAuthenticationFilter.HEADER, intercomKey))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Paramètre requis manquant"));
        // Reached only past authentication, which is what makes it an integration test: Spring
        // raises ServletRequestBindingException before the controller method runs, and the advice
        // is what keeps a caller's mistake from being reported as a server fault.
    }

    @Test
    @DisplayName("a route that declares nothing is public")
    void publicRouteNeedsNoCredentials() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    @DisplayName("sans jeton du tout, une route protegee repond 401 dans la forme d'erreur de l'API")
    void refusesWithoutToken() throws Exception {
        // Deplace ici depuis MeControllerIntegrationTest quand les packages sont passes par feature :
        // ce test ne parle pas des portes, il parle de la chaine de securite.
        mvc.perform(get("/me/doors"))
                .andExpect(status().isUnauthorized())
                // Pas le defaut de Spring Security, qui redirigerait un navigateur vers une page de login.
                .andExpect(jsonPath("$.error").value("Non authentifié"));
    }

    @Test
    @DisplayName("un jeton signe avec un autre secret est refuse aussi")
    void refusesForeignToken() throws Exception {
        var attaquant = new JwtService("un-autre-secret", "accesscontrol", "accesscontrol-app");

        mvc.perform(get("/me/doors")
                        .header("Authorization", "Bearer " + attaquant.generateToken("user-rodolphe")))
                .andExpect(status().isUnauthorized());
    }
}
