package dev.rodolphe.accesscontrol.auth;

import dev.rodolphe.accesscontrol.users.User;
import dev.rodolphe.accesscontrol.users.UserRepository;
import dev.rodolphe.accesscontrol.auth.JwtService;
import dev.rodolphe.accesscontrol.auth.LoginRequest;
import dev.rodolphe.accesscontrol.auth.LoginResponse;
import dev.rodolphe.accesscontrol.users.UserDto;
import dev.rodolphe.accesscontrol.shared.InvalidCredentialsException;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The port of {@code authRoutes(storage, jwt)}.
 *
 * <p>Worth comparing against the original. The Kotlin route received {@code storage} and {@code jwt}
 * as parameters, handed down from {@code Routing.kt}, itself handed them from {@code module()}. Here
 * the three collaborators arrive through the constructor and nothing upstream has to know about them.
 *
 * <p>The method returns {@link LoginResponse} rather than {@code ResponseEntity}: the success path is
 * always 200, and the 401 travels as an exception that {@code ApiExceptionHandler} converts. The
 * signature therefore states what the endpoint produces, instead of hiding it behind a wildcard.
 */
@RestController
class AuthController {

    private final UserRepository users;
    private final JwtService jwt;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserRepository users, JwtService jwt, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.jwt = jwt;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest body) {
        User user = users.findByEmail(body.email())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(body.password(), user.passwordHash())) {
            throw new InvalidCredentialsException();
        }

        return new LoginResponse(
                jwt.generateToken(user.id()),
                new UserDto(user.id(), user.email(), user.displayName()));
    }
}
