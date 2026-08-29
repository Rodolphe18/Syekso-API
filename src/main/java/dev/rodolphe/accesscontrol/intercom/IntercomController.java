package dev.rodolphe.accesscontrol.intercom;

import dev.rodolphe.accesscontrol.intercom.IntercomService;
import dev.rodolphe.accesscontrol.intercom.DirectoryResponse;
import dev.rodolphe.accesscontrol.intercom.IntercomOpenResultRequest;
import dev.rodolphe.accesscontrol.intercom.IntercomOpenResultResponse;
import dev.rodolphe.accesscontrol.intercom.IntercomValidateRequest;
import dev.rodolphe.accesscontrol.intercom.IntercomValidateResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three endpoints the lobby intercom calls. Port of {@code intercomRoutes(storage, intercomKey)}.
 *
 * <p>Compare with what the Kotlin routes — and this class, one step ago — had to repeat three times:
 *
 * <pre>
 * if (call.request.headers["X-Intercom-Key"] != intercomKey) {
 *     call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Interphone non autorisé"))
 *     return@post
 * }
 * </pre>
 *
 * <p>Gone entirely. The key is checked by a filter, on a chain whose matcher is {@code /intercom/**},
 * before a controller method is ever chosen. Nothing here can forget the guard, and nothing here can
 * forget the {@code return} that had to follow it.
 */
@RestController
@RequestMapping("/intercom")
class IntercomController {

    private final IntercomService intercom;

    public IntercomController(IntercomService intercom) {
        this.intercom = intercom;
    }

    @PostMapping("/validate")
    public IntercomValidateResponse validate(@Valid @RequestBody IntercomValidateRequest body) {
        return intercom.validate(body.pin());
    }

    @PostMapping("/open-result")
    public IntercomOpenResultResponse openResult(@Valid @RequestBody IntercomOpenResultRequest body) {
        return intercom.reportOpenResult(body.pin(), body.success());
    }

    @GetMapping("/directory")
    public DirectoryResponse directory(@RequestParam String buildingId) {
        return intercom.directory(buildingId);
    }
}
