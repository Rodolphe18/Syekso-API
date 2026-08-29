package dev.rodolphe.accesscontrol.shared;

/** Maps to 404. Carries its own message because the four call sites mean four different things. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
