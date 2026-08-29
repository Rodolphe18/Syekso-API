package dev.rodolphe.accesscontrol.config;

import dev.rodolphe.accesscontrol.access.PinCode;
import dev.rodolphe.accesscontrol.users.User;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Proves the documents this application writes carry no dependency on its package layout.
 *
 * <p>No database is involved: converting an entity to a {@link Document} is pure in-memory work, so
 * this asserts on exactly what would have been sent, without sending it.
 *
 * <p>The regression it guards is the one the feature-package refactor made real. Spring Data's default
 * mapper writes {@code _class} with a fully qualified class name; every {@code pin_codes} document
 * created between the cutover and that refactor still names {@code …accesscontrol.db.PinCode}, a class
 * that no longer exists. Nobody should have to think about source folders when moving a class again.
 */
@SpringBootTest
@ActiveProfiles("test")
class MongoTypeMappingTest {

    @Autowired private MappingMongoConverter converter;

    @Test
    @DisplayName("un document ecrit ne porte aucun indice de type _class")
    void writesNoClassHint() {
        Document document = new Document();

        converter.write(new User("user-rodolphe", "rodolphe@example.com", "$2a$hash", "Rodolphe",
                List.of("bld-montmartre")), document);

        assertFalse(document.containsKey("_class"),
                "un _class lierait le contenu de la base au nom de package Java");
        // Et le reste est bien ecrit : le test doit echouer si la conversion ne fait plus rien.
        assertEquals("user-rodolphe", document.get("_id"));
        assertEquals("rodolphe@example.com", document.get("email"));
    }

    @Test
    @DisplayName("un document portant un _class perime reste lisible")
    void toleratesAStaleClassHint() {
        // La question que ce test tranche : faut-il nettoyer la base avant la demo ? Les documents
        // pin_codes ecrits par Spring entre la bascule et le passage aux packages par feature portent
        // _class = ...accesscontrol.db.PinCode, une classe qui n'existe plus. Si la lecture levait,
        // le nettoyage serait obligatoire et urgent ; si elle retombe sur le type declare, ce n'est
        // que de l'hygiene.
        Document perime = new Document()
                .append("_class", "dev.rodolphe.accesscontrol.db.PinCode")
                .append("_id", "123456")
                .append("issuedByUserId", "user-rodolphe")
                .append("buildingId", "bld-montmartre")
                .append("doorId", "door-hall")
                .append("doorName", "Porte d'entrée")
                .append("doorBleLocalName", "OSKEY-HALL-01")
                .append("createdAtEpochMs", 1_700_000_000_000L)
                .append("expiresAtEpochMs", 1_700_000_900_000L);

        PinCode relu = converter.read(PinCode.class, perime);

        assertEquals("123456", relu.pin());
        assertEquals("OSKEY-HALL-01", relu.doorBleLocalName());
        // Et le defaut du constructeur compact tient toujours : un champ absent reste a usage unique.
        assertEquals(Boolean.TRUE, relu.singleUse());
    }
}
