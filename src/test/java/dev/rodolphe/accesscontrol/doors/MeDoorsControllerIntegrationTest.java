package dev.rodolphe.accesscontrol.doors;

import dev.rodolphe.accesscontrol.auth.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The doors half of the former {@code MeControllerIntegrationTest}, which was split along the same
 * line as the controller it exercises.
 *
 * <p>Living in the {@code doors} package is not cosmetic: it is what lets the test reference the
 * feature's own types, several of which are package-private now. A test that has to be somewhere
 * else is a hint that the production class is more public than it needs to be.
 *
 * <p>Only the service is replaced. The filter chain, {@code JwtService}, the JSON mapping and the
 * exception advice are the real ones — which is the point, since the bugs this catches live in their
 * interaction rather than inside any one of them. The token is minted with the application's own
 * {@code JwtService} against the secret in the test properties: nothing on the authentication path
 * is stubbed, the request really is verified.
 */
@SpringBootTest
@AutoConfigureMockMvc
// Switches off the one bean that needs a real Tomcat — see WebSocketConfig#createWebSocketContainer.
@ActiveProfiles("test")
class MeDoorsControllerIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;

    @MockitoBean private ResidentService residents;

    @Test
    @DisplayName("a valid token reaches the controller, and the principal is the user id")
    void acceptsValidToken() throws Exception {
        given(residents.doorsOf("user-rodolphe")).willReturn(new DoorsResponse(List.of(
                new DoorDto("door-hall", "Porte d'entrée", "bld-1", "Résidence", "OSKEY-HALL-01"))));

        mvc.perform(get("/me/doors").header("Authorization", "Bearer " + jwt.generateToken("user-rodolphe")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doors[0].id").value("door-hall"))
                .andExpect(jsonPath("$.doors[0].bleLocalName").value("OSKEY-HALL-01"));
        // The stub was keyed on "user-rodolphe": had @AuthenticationPrincipal delivered anything else,
        // the mock would have returned null and the assertions would fail.
    }
}
