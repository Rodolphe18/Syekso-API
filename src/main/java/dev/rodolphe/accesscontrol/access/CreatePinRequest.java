package dev.rodolphe.accesscontrol.access;

import jakarta.validation.constraints.NotBlank;

record CreatePinRequest(@NotBlank String doorId) {
}
