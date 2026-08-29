package dev.rodolphe.accesscontrol.users;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * An interface, and no implementation anywhere — Spring generates one at runtime and registers it as
 * a bean. {@code MongoRepository<User, String>} already brings {@code findById}, {@code findAllById},
 * {@code save}, {@code count} and {@code deleteById}, so only the queries specific to this
 * application appear below.
 *
 * <p>Both methods here are <em>derived</em>: Spring parses the method name and builds the query from
 * it. Nothing else is needed for them to work.
 */
public interface UserRepository extends MongoRepository<User, String> {

    /**
     * Replaces {@code users.find(Filters.eq("email", email)).firstOrNull()}.
     *
     * <p>Returning {@code Optional} rather than a nullable reference makes "no such user" part of the
     * signature, which the login flow has to handle explicitly.
     */
    Optional<User> findByEmail(String email);

    /**
     * Replaces {@code users.find(Filters.in("buildingIds", buildingId))} — used to list the residents
     * an intercom may call.
     *
     * <p>{@code Containing} on a collection property means "this array contains the value", not a
     * substring match. Reading it as a string operation is a classic misreading.
     */
    List<User> findByBuildingIdsContaining(String buildingId);
}
