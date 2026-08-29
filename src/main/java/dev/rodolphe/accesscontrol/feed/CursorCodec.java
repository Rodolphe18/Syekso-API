package dev.rodolphe.accesscontrol.feed;

import dev.rodolphe.accesscontrol.shared.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The pagination cursor is the sort key of the last row returned — {@code (createdAtEpochMs, _id)} —
 * encoded as an opaque URL-safe token. The client passes it back unchanged and must not parse it.
 *
 * <p>Deliberately not a bean. It holds no state and depends on nothing, so putting it in the Spring
 * context would add an injection and an indirection while buying nothing. Not everything needs to be
 * managed by the framework — only what has dependencies or must be shared.
 */
final class CursorCodec {

    /** Un seul libelle : le client n'a pas a savoir laquelle des trois formes a echoue. */
    private static final String INVALID_CURSOR = "Curseur invalide";

    private CursorCodec() {
    }

    /** The decoded halves of a cursor. */
    public record Cursor(long createdAtEpochMs, String id) {
    }

    public static String encode(long createdAtEpochMs, String id) {
        String raw = createdAtEpochMs + ":" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a cursor the server itself produced, and refuses anything else as a client error.
     *
     * <p>Ids are UUIDs, which contain '-' but never ':', so splitting on the first colon is safe.
     *
     * <p><strong>Why every failure is a {@link BadRequestException}.</strong> This token is opaque:
     * the only thing that can mint a valid one is {@link #encode}. Anything else reaching here is a
     * truncated link, a bad copy-paste or a probe — the caller's mistake, not the server's. Left to
     * fail on its own it landed in the catch-all of {@code ApiExceptionHandler} and came back as a
     * 500, telling the client the server was broken when it was their request that was.
     *
     * <p><strong>Why not a global handler for {@code IllegalArgumentException} instead.</strong> That
     * one-liner was the tempting fix and it is the wrong one, twice over. It would map every
     * programming error that raises the same type — anywhere in the application — to a 400, hiding
     * real server faults behind a client error, which is the present bug inverted and worse. And it
     * would not even have covered this method: a token with no colon failed on {@code parts[1]} with
     * an {@code ArrayIndexOutOfBoundsException}, which is not an {@code IllegalArgumentException} at
     * all. Rejecting at the point where "malformed" is actually defined costs three checks and stays
     * honest.
     */
    public static Cursor decode(String cursor) {
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(INVALID_CURSOR);
        }

        String raw = new String(decoded, StandardCharsets.UTF_8);
        // indexOf rather than split: it removes the out-of-bounds path entirely while keeping the
        // "first colon only" rule, so an id containing a colon would still survive intact.
        int separator = raw.indexOf(':');
        if (separator < 0) {
            throw new BadRequestException(INVALID_CURSOR);
        }

        try {
            return new Cursor(
                    Long.parseLong(raw.substring(0, separator)),
                    raw.substring(separator + 1));
        } catch (NumberFormatException e) {
            throw new BadRequestException(INVALID_CURSOR);
        }
    }
}
