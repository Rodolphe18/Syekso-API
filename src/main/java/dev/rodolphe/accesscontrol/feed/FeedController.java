package dev.rodolphe.accesscontrol.feed;

import dev.rodolphe.accesscontrol.feed.FeedService;
import dev.rodolphe.accesscontrol.feed.CursorPageResponse;
import dev.rodolphe.accesscontrol.feed.InsertedResponse;
import dev.rodolphe.accesscontrol.feed.OffsetPageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pagination playground. Port of {@code feedRoutes(storage)}.
 *
 * <p>Note what is <em>not</em> used here: Bean Validation. The Kotlin routes clamp out-of-range values
 * with {@code coerceIn} rather than rejecting them, and that behaviour is kept. {@code @Min(1) @Max(100)}
 * would turn {@code ?limit=500} into a 400, changing the contract of an endpoint whose whole purpose
 * is to be poked at by hand. Request bodies are validated because a malformed field is a client bug;
 * a pagination bound that is merely too large is a reasonable thing to accommodate.
 */
@RestController
class FeedController {

    private final FeedService feed;

    public FeedController(FeedService feed) {
        this.feed = feed;
    }

    @GetMapping("/feed/offset")
    public OffsetPageResponse offset(
            @RequestParam(defaultValue = "5") int limit,
            @RequestParam(defaultValue = "1") int page) {
        // Math.clamp is Java 21; it says what coerceIn said, without an if.
        return feed.byOffset(Math.max(page, 1), Math.clamp(limit, 1, 100));
    }

    @GetMapping("/feed/cursor")
    public CursorPageResponse cursor(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "5") int limit) {
        return feed.byCursor(cursor, Math.clamp(limit, 1, 100));
    }

    @PostMapping("/feed/simulate-inserts")
    public InsertedResponse simulateInserts(@RequestParam(defaultValue = "5") int count) {
        return new InsertedResponse(feed.simulateInserts(Math.clamp(count, 1, 1000)));
    }
}
