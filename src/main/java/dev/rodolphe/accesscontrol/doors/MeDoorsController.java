package dev.rodolphe.accesscontrol.doors;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The doors a signed-in resident holds, and the activation code that grants them.
 *
 * <p>Half of the former {@code MeController}. It was split when the packages moved to a feature
 * layout: one class served {@code /me/doors} and {@code /me/pin-codes}, so it belonged to two
 * features at once and would have forced whichever package it landed in to depend on the other.
 * Two controllers under the same {@code /me} prefix cost nothing — Spring maps them independently —
 * and each now sits with the service it drives.
 *
 * <p><strong>Package-private on purpose.</strong> Nothing outside this package has any business
 * referencing it; Spring instantiates it by scanning, not by import. This is the payoff of the
 * feature layout — under the old layer packages every class had to be {@code public} merely because
 * its caller lived elsewhere, and Java's default visibility, which is a real encapsulation tool,
 * protected nothing.
 *
 * <p>The {@code /me} prefix is what the security rule {@code requestMatchers("/me/**")} keys on:
 * a method added here is protected by construction, without anyone remembering the security config.
 * And note what is <em>absent</em> — any check that the caller is authenticated. The filter chain
 * already refused the request otherwise, so {@code userId} is guaranteed non-null.
 */
@RestController
@RequestMapping("/me")
class MeDoorsController {

    private final ResidentService residents;

    MeDoorsController(ResidentService residents) {
        this.residents = residents;
    }

    /**
     * {@code @AuthenticationPrincipal} pulls the principal out of the SecurityContext that
     * {@code JwtTokenVerifierFilter} populated earlier in the chain. It replaces Ktor's
     * {@code call.userId()}, which dug into the JWT principal by hand — here the plumbing is the
     * framework's and the controller only declares what it needs.
     */
    @GetMapping("/doors")
    DoorsResponse doors(@AuthenticationPrincipal String userId) {
        return residents.doorsOf(userId);
    }

    @PostMapping("/activations")
    ActivationResponse redeemActivation(@AuthenticationPrincipal String userId,
                                        @Valid @RequestBody ActivationRequest body) {
        return residents.redeemActivation(userId, body.code());
    }
}
