package dev.rodolphe.accesscontrol.access;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The access codes a resident hands out: single-use PINs and windowed invitations.
 *
 * <p>The other half of the former {@code MeController} — see {@code MeDoorsController} for why it
 * was split. Same {@code /me} prefix, mapped independently by Spring, so no URL changed and no
 * client noticed.
 *
 * <p>Package-private, like the service it drives: everything this feature needs to expose to the
 * rest of the application is a URL, not a Java type.
 */
@RestController
@RequestMapping("/me")
class MeCodesController {

    private final PinCodeService pinCodes;

    MeCodesController(PinCodeService pinCodes) {
        this.pinCodes = pinCodes;
    }

    @PostMapping("/pin-codes")
    PinCodeDto createPinCode(@AuthenticationPrincipal String userId,
                             @Valid @RequestBody CreatePinRequest body) {
        return pinCodes.createPinCode(userId, body.doorId());
    }

    @GetMapping("/pin-codes")
    PinCodesResponse pinCodes(@AuthenticationPrincipal String userId) {
        return pinCodes.listPinCodes(userId);
    }

    @PostMapping("/invitations")
    InvitationDto createInvitation(@AuthenticationPrincipal String userId,
                                   @Valid @RequestBody CreateInvitationRequest body) {
        return pinCodes.createInvitation(userId, body);
    }

    @GetMapping("/invitations")
    InvitationsResponse invitations(@AuthenticationPrincipal String userId) {
        return pinCodes.listInvitations(userId);
    }
}
