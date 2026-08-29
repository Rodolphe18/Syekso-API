package dev.rodolphe.accesscontrol.feed;

import dev.rodolphe.accesscontrol.shared.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Port of the Kotlin server's {@code CursorCodecTest}, carried over when the Ktor sources were
 * removed so the migration loses no coverage.
 *
 * <p>What these protect is the opacity of the cursor: clients treat it as a token and hand it back
 * untouched, so the encoding is a contract with every page already in flight. A change here silently
 * breaks pagination for anyone mid-scroll rather than failing loudly.
 */
class CursorCodecTest {

    @Test
    @DisplayName("encoder puis decoder rend la paire d'origine")
    void roundTrips() {
        String cursor = CursorCodec.encode(1_784_400_000_000L, "abc-123");

        CursorCodec.Cursor decoded = CursorCodec.decode(cursor);

        assertEquals(1_784_400_000_000L, decoded.createdAtEpochMs());
        assertEquals("abc-123", decoded.id());
    }

    @Test
    @DisplayName("un jeton base64 connu est lu tel quel")
    void decodesAKnownToken() {
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("1000:xyz".getBytes(StandardCharsets.UTF_8));

        CursorCodec.Cursor decoded = CursorCodec.decode(token);

        assertEquals(1000L, decoded.createdAtEpochMs());
        assertEquals("xyz", decoded.id());
    }

    @Test
    @DisplayName("un identifiant a tirets, sans deux-points, survit au trajet")
    void preservesUuidLikeId() {
        // La separation se fait sur le PREMIER deux-points seulement : un id qui en contiendrait
        // un ne serait pas tronque. Les tirets d'un UUID n'ont jamais pose de probleme, mais le
        // test fige la regle plutot que de la laisser au hasard du split.
        CursorCodec.Cursor decoded = CursorCodec.decode(CursorCodec.encode(42L, "9f8e-7d6c-5b4a"));

        assertEquals(42L, decoded.createdAtEpochMs());
        assertEquals("9f8e-7d6c-5b4a", decoded.id());
    }

    /**
     * Les trois façons dont un curseur peut être malformé, et elles ne levaient pas la même chose.
     *
     * <p>Un curseur est un jeton opaque que le client renvoie tel quel : le seul qui en produise un
     * valide est le serveur. Tout ce qui arrive d'ailleurs est une erreur du client — un lien tronqué,
     * un copier-coller, une sonde — et doit se voir répondre 400. Avant le correctif, les trois
     * remontaient jusqu'à l'attrape-tout de {@code ApiExceptionHandler} et ressortaient en 500,
     * c'est-à-dire en « le serveur est cassé » alors que c'est la requête qui l'est.
     */
    @Test
    @DisplayName("un curseur qui n'est pas du base64 est refuse comme une erreur du client")
    void rejectsNonBase64() {
        assertThrows(BadRequestException.class, () -> CursorCodec.decode("pas-du-base64!!"));
    }

    @Test
    @DisplayName("un base64 valide sans separateur est refuse")
    void rejectsMissingSeparator() {
        // Celui-ci levait un ArrayIndexOutOfBoundsException sur parts[1] : meme pas une
        // IllegalArgumentException, donc aucun handler large ne l'aurait rattrape.
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("sans-separateur".getBytes(StandardCharsets.UTF_8));

        assertThrows(BadRequestException.class, () -> CursorCodec.decode(token));
    }

    @Test
    @DisplayName("un horodatage non numerique est refuse")
    void rejectsNonNumericTimestamp() {
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("pas-un-nombre:abc".getBytes(StandardCharsets.UTF_8));

        assertThrows(BadRequestException.class, () -> CursorCodec.decode(token));
    }
}
