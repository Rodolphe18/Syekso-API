package dev.rodolphe.accesscontrol.shared;

import dev.rodolphe.accesscontrol.shared.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * One place that turns exceptions into HTTP responses, for every controller. The Kotlin counterpart
 * was {@code install(StatusPages) { exception<Throwable> { ... } }}.
 *
 * <p>What it buys beyond parity: a controller describes only its happy path and throws when the
 * request cannot be honoured. No guard clause has to remember to return.
 *
 * <p>Ordering matters here. Spring picks the handler whose exception type is closest to the one
 * thrown, so the specific handlers below win over the catch-all — which is exactly why the catch-all
 * can stay broad without swallowing client errors.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> onInvalidCredentials(InvalidCredentialsException e) {
        // Not logged as an error: a mistyped password is normal traffic, not a server fault.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(e.getMessage()));
    }

    // There is no handler for the intercom key any more. It is authenticated by its own filter chain,
    // which refuses before Spring MVC is reached — nothing here would ever be consulted for it.

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> onNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> onBadRequest(BadRequestException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> onConflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage()));
    }

    /**
     * Bean Validation rejected a field. Without this handler the catch-all below would report a
     * client mistake as a 500, which is worse than having no validation at all — the caller would be
     * told the server is broken when their request is.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> onInvalidRequest(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + " " + field.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(new ErrorResponse("Requête invalide : " + detail));
    }

    /**
     * A required header or query parameter is missing — the counterpart of the Kotlin server's
     * explicit {@code if (buildingId.isNullOrBlank()) respond(BadRequest, ...)}. Spring detects it
     * before the controller runs; it only needs mapping to the right status.
     */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ErrorResponse> onMissingParameter(ServletRequestBindingException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("Paramètre requis manquant"));
    }

    /**
     * A query parameter could not be converted to the declared type — {@code ?limit=abc} on an
     * {@code int}. Note it extends {@code org.springframework.beans.TypeMismatchException}, not the
     * binding exception above, so the earlier handler does not cover it and the catch-all would
     * report a typo as a server fault.
     *
     * <p>A divergence from the Kotlin server, and an intentional one: {@code toIntOrNull()} silently
     * substituted the default there. Clamping a number that is merely out of range is accommodating;
     * pretending a word is a number hides a client bug.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> onTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("Paramètre invalide : " + e.getName()));
    }

    /** Malformed or absent JSON body. A client error, not a server one. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> onUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("Corps de requête illisible"));
    }

    /** Anything unexpected becomes a JSON 500 rather than a stack trace the client cannot parse. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> onUnexpected(Exception e) {
        log.error("Unhandled failure", e);
        return ResponseEntity.internalServerError().body(new ErrorResponse("Erreur interne"));
    }
}
