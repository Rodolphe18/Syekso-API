package dev.rodolphe.accesscontrol.access;

import jakarta.validation.constraints.NotBlank;

/**
 * Note what is NOT annotated: the rule "validUntil must be after validFrom" spans two fields, and
 * Bean Validation expresses cross-field rules only through a custom class-level constraint — more
 * machinery than the rule is worth. It is checked in the service, where it reads as one line.
 */
record CreateInvitationRequest(
        @NotBlank String title,
        @NotBlank String doorId,
        long validFromEpochMs,
        long validUntilEpochMs
) {
}
