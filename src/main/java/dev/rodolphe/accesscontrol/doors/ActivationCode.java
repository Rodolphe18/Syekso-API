package dev.rodolphe.accesscontrol.doors;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A code the building manager mails to a resident, redeemed once to join a building. The code string
 * itself is the primary key.
 *
 * <p>{@code redeemedByUserId} being null is what marks a code as still available, and the server
 * relies on that null inside an atomic conditional update. It carries meaning, so it stays nullable
 * rather than being defaulted away.
 */
@Document(collection = "activation_codes")
public record ActivationCode(
        @Id String code,
        String buildingId,
        String redeemedByUserId,
        Long redeemedAtEpochMs
) {
}
