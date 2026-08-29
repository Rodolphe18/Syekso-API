package dev.rodolphe.accesscontrol.intercom;

import jakarta.validation.constraints.NotBlank;

/** What actually happened at the door after /intercom/validate said yes. */
record IntercomOpenResultRequest(@NotBlank String pin, boolean success) {
}
