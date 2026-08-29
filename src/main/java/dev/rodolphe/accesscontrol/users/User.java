package dev.rodolphe.accesscontrol.users;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

/**
 * A resident.
 *
 * <p>{@code collection = "users"} is not optional: left out, Spring Data derives the collection name
 * from the class and would read {@code user}. The Kotlin server writes to {@code users}, and both
 * must agree while they run side by side.
 *
 * <p>{@code @Id} maps this component onto Mongo's {@code _id} primary key — the counterpart of the
 * Kotlin document's {@code @SerialName("_id")}.
 *
 * @param buildingIds the buildings this resident has joined; the single source of truth for "which
 *                    doors may I open".
 */
@Document(collection = "users")
public record User(
        @Id String id,
        String email,
        String passwordHash,
        String displayName,
        List<String> buildingIds
) {
    /**
     * A compact constructor runs before the components are assigned, which makes it the place to
     * normalise what comes back from the database. A resident who has joined no building may have no
     * {@code buildingIds} field at all, so the mapper hands us {@code null}; turning it into an empty
     * list here spares every caller a null check.
     */
    public User {
        buildingIds = buildingIds == null ? List.of() : List.copyOf(buildingIds);
    }
}
