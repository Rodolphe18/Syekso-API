package dev.rodolphe.accesscontrol.auth;

import jakarta.validation.constraints.NotBlank;

/** What the app posts to /auth/login. */
record LoginRequest(
        @NotBlank String email,
        @NotBlank String password
) {
}
