package dev.rodolphe.accesscontrol.shared;

/**
 * The single error shape of the API. The field is named {@code error} — matching the Kotlin server —
 * even though the Android clients read only the HTTP status and ignore this body entirely.
 */
public record ErrorResponse(String error) {
}
