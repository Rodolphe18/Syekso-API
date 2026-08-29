package dev.rodolphe.accesscontrol.intercom;

import jakarta.validation.constraints.Pattern;

/**
 * The pattern is the most valuable constraint in the whole API: the PIN is the primary key of the
 * pin_codes collection, so anything that is not six digits cannot possibly match and has no business
 * reaching the database.
 */
record IntercomValidateRequest(
        @Pattern(regexp = "\\d{6}", message = "doit contenir exactement 6 chiffres") String pin
) {
}
