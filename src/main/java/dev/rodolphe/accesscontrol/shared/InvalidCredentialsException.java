package dev.rodolphe.accesscontrol.shared;

/**
 * Thrown for both an unknown email and a wrong password, on purpose and with the same message: an
 * attacker must not be able to probe which addresses exist. The Kotlin route made the same choice
 * with a single shared response.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Email ou mot de passe incorrect");
    }
}
