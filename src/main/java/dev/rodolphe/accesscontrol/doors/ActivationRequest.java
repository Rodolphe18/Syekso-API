package dev.rodolphe.accesscontrol.doors;

import jakarta.validation.constraints.NotBlank;

record ActivationRequest(@NotBlank String code) {
}
