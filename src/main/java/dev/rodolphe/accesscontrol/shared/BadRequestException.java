package dev.rodolphe.accesscontrol.shared;

/** Maps to 400 — for the rules Bean Validation cannot express on a single field. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
