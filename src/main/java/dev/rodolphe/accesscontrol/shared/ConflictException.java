package dev.rodolphe.accesscontrol.shared;

/**
 * Maps to 409. Distinct from a 404 on purpose: the resource exists, but its current state refuses the
 * operation — an activation code already redeemed by someone else.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
