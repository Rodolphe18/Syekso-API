package dev.rodolphe.accesscontrol.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a bad cursor produces over HTTP.
 *
 * <p>{@code CursorCodecTest} proves the codec refuses malformed input; only a request proves the
 * refusal comes back as a 400 rather than as a 500. The distinction is the whole point of the fix:
 * the caller must be told their request is wrong, not that the server is broken.
 *
 * <p>Nothing is mocked and no database is reached — {@code FeedService.byCursor} decodes the cursor
 * before it builds any query, so a malformed one never gets as far as Mongo. Which is also why these
 * two cases can be tested at all without a running database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FeedControllerIntegrationTest {

    @Autowired private MockMvc mvc;

    private static String base64Of(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("un curseur illisible est un 400 dans la forme d'erreur de l'API")
    void unreadableCursorIsBadRequest() throws Exception {
        mvc.perform(get("/feed/cursor").param("cursor", "pas-du-base64!!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Curseur invalide"));
    }

    @Test
    @DisplayName("un base64 valide mais sans separateur est un 400, pas un 500")
    void cursorWithoutSeparatorIsBadRequest() throws Exception {
        // Le cas le plus sournois : le decodage base64 reussit, et c'est parts[1] qui explosait —
        // sur une exception qu'aucun handler large n'aurait rattrapee.
        mvc.perform(get("/feed/cursor").param("cursor", base64Of("sans-separateur")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Curseur invalide"));
    }
}
