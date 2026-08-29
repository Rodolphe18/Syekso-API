package dev.rodolphe.accesscontrol.doors;

import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A door, embedded inside its building rather than stored in its own collection — a door belongs to
 * one building and is always read with it. The absence of {@code @Document} here is deliberate and
 * meaningful: this type is never a top-level document.
 *
 * <p><strong>Why the component is called {@code doorId} and not {@code id}.</strong> Spring Data
 * MongoDB treats any property named {@code id} as the entity's identifier and reads it from the BSON
 * field {@code _id} — and it applies that convention to embedded types too, not just to
 * {@code @Document} ones. Stored doors carry a plain {@code id} field, so a component named
 * {@code id} would look for {@code _id}, find nothing, and silently map to null.
 *
 * <p>{@code @Field("id")} alone does not fix it: once a property is detected as the identifier, the
 * field name resolves to {@code _id} whatever the annotation says. The property has to stop being
 * called {@code id}, and {@code @Field} then points it at the real BSON name.
 *
 * <p>Caught by comparing a Spring response against the Kotlin server's byte for byte. It would
 * otherwise have shipped quietly and broken PIN creation, which posts a doorId back to the server.
 *
 * @param bleLocalName what the physical lock advertises over BLE; the app matches a scan result to a
 *                     door by this name.
 */
public record Door(
        @Field("id") String doorId,
        String name,
        String bleLocalName
) {
}
