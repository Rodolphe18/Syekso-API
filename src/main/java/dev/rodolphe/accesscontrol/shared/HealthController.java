package dev.rodolphe.accesscontrol.shared;

import dev.rodolphe.accesscontrol.shared.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Ktor equivalent, for comparison:
 *
 * <pre>get("/health") { call.respond(HealthResponse(status = "ok")) }</pre>
 *
 * <p>Three differences worth noticing. There is no {@code call}: the returned object <em>is</em> the
 * response body, serialized to JSON because the class is a {@code @RestController} rather than a
 * plain {@code @Controller}. The status is 200 by default, so no {@code ResponseEntity} is needed —
 * that one earns its place only when the status varies or headers matter. And nobody registers this
 * route anywhere: there is no equivalent of {@code Routing.kt}. The class is a bean, Spring finds it
 * by scanning, and reads the path off the annotation.
 */
@RestController
class HealthController {

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("ok");
    }
}
