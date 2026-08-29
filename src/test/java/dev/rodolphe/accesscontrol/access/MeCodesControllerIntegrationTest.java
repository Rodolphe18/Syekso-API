package dev.rodolphe.accesscontrol.access;

import dev.rodolphe.accesscontrol.auth.JwtService;
import dev.rodolphe.accesscontrol.shared.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The access-codes half of the former {@code MeControllerIntegrationTest}.
 *
 * <p>What it covers that a unit test cannot: Bean Validation and the exception advice only exist
 * along a real HTTP request. Only {@code PinCodeService} is replaced — the filter chain, the JSON
 * mapping and {@code ApiExceptionHandler} are the real ones.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeCodesControllerIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;

    @MockitoBean private PinCodeService pinCodes;

    private String bearerFor(String userId) {
        return "Bearer " + jwt.generateToken(userId);
    }

    @Test
    @DisplayName("a blank field is rejected as 400 by validation, not reported as a server fault")
    void validationRejectsBlankDoorId() throws Exception {
        mvc.perform(post("/me/pin-codes")
                        .header("Authorization", bearerFor("user-rodolphe"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doorId\":\"  \"}"))
                .andExpect(status().isBadRequest())
                // The trap this guards: without an explicit handler, the broad catch-all in
                // ApiExceptionHandler would turn a client mistake into a 500.
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("a malformed body is a 400, not a 500")
    void unreadableBodyIsBadRequest() throws Exception {
        mvc.perform(post("/me/pin-codes")
                        .header("Authorization", bearerFor("user-rodolphe"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ pas du json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a domain exception is mapped to its status by the advice")
    void notFoundIsMappedTo404() throws Exception {
        willThrow(new NotFoundException("Porte introuvable"))
                .given(pinCodes).createPinCode("user-rodolphe", "door-du-voisin");

        mvc.perform(post("/me/pin-codes")
                        .header("Authorization", bearerFor("user-rodolphe"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doorId\":\"door-du-voisin\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Porte introuvable"));
    }

    @Test
    @DisplayName("a successful response carries the JSON shape the app parses")
    void responseShape() throws Exception {
        given(pinCodes.createPinCode("user-rodolphe", "door-hall"))
                .willReturn(new PinCodeDto("123456", "Porte d'entrée", 1_700_000_000_000L));

        mvc.perform(post("/me/pin-codes")
                        .header("Authorization", bearerFor("user-rodolphe"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doorId\":\"door-hall\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pin").value("123456"))
                .andExpect(jsonPath("$.doorName").value("Porte d'entrée"))
                .andExpect(jsonPath("$.expiresAtEpochMs").value(1_700_000_000_000L));
    }
}
