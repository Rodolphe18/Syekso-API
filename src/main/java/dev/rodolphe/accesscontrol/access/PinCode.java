package dev.rodolphe.accesscontrol.access;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A numeric code a resident issues for one door, typed on the intercom keypad. The PIN itself is the
 * primary key. Two flavours share this document: a single-use PIN, consumed on first successful
 * validation, and a multi-use invitation valid across a time window.
 */import dev.rodolphe.accesscontrol.doors.Door;


@Document(collection = "pin_codes")
public record PinCode(
        @Id String pin,
        String issuedByUserId,
        String buildingId,
        String doorId,
        String doorName,
        String doorBleLocalName,
        long createdAtEpochMs,
        long expiresAtEpochMs,
        Long validFromEpochMs,
        Boolean singleUse,
        String title,
        Long redeemedAtEpochMs
) {
    /**
     * {@code validFromEpochMs} and {@code singleUse} are boxed on purpose, and it is not a style
     * choice.
     *
     * <p>The Kotlin document declares them with defaults ({@code 0L} and {@code true}). A PIN written
     * before those fields existed carries neither, and a Java primitive cannot tell "absent" from
     * "explicitly false" — a missing {@code singleUse} would silently read as {@code false}, turning
     * every legacy one-time PIN into a reusable one. That is a security regression, not a cosmetic
     * one. Boxing lets us detect the absence and restore the original default.
     *
     * <p>{@code redeemedAtEpochMs} stays nullable for a different reason: its null is load-bearing.
     * The atomic claim matches on {@code redeemedAtEpochMs == null} to reserve a code exactly once.
     */
    public PinCode {
        validFromEpochMs = validFromEpochMs == null ? 0L : validFromEpochMs;
        singleUse = singleUse == null ? Boolean.TRUE : singleUse;
    }

    /**
     * A PIN valid from now until {@code expiresAtEpochMs}, spent on first use.
     *
     * <p>These factories exist because the canonical constructor takes twelve positional arguments,
     * six of them strings in a row. Two call sites building that by hand, with a couple of trailing
     * nulls, is exactly where a silent field swap hides.
     */
    public static PinCode singleUse(String pin, String issuedByUserId, String buildingId,
                                    Door door, long now, long expiresAtEpochMs) {
        return new PinCode(pin, issuedByUserId, buildingId,
                door.doorId(), door.name(), door.bleLocalName(),
                now, expiresAtEpochMs, now, true, null, null);
    }

    /** A titled invitation, reusable for as long as its window is open. */
    public static PinCode invitation(String code, String issuedByUserId, String buildingId,
                                     Door door, long now, String title,
                                     long validFromEpochMs, long validUntilEpochMs) {
        return new PinCode(code, issuedByUserId, buildingId,
                door.doorId(), door.name(), door.bleLocalName(),
                now, validUntilEpochMs, validFromEpochMs, false, title, null);
    }
}
