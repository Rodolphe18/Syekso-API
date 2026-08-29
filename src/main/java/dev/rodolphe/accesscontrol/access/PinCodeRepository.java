package dev.rodolphe.accesscontrol.access;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;

/**
 * Where deriving a query from the method name stops paying off.
 *
 * <p>The two queries below combine three conditions each. Derived, the first would have to be called
 * {@code findByIssuedByUserIdAndRedeemedAtEpochMsIsNullAndExpiresAtEpochMsGreaterThan} — technically
 * valid, unreadable in practice. {@code @Query} takes the Mongo filter directly, which maps
 * one-to-one onto the Kotlin server's {@code Filters.and(...)} and stays reviewable.
 *
 * <p>The rule of thumb: derive while the name reads like a sentence, switch to {@code @Query} when it
 * stops.
 *
 * <p>Two operations are deliberately absent — claiming and releasing a single-use code. Both are
 * conditional atomic updates ("set this field only if it is still null, and tell me whether you
 * did"), which a repository interface cannot express. They need {@code MongoTemplate} and its
 * {@code findAndModify}, and they arrive with the endpoints that use them in iteration 2.
 */
public interface PinCodeRepository extends MongoRepository<PinCode, String> {

    /** Codes this resident issued that are still unused and not expired. */
    @Query("{ 'issuedByUserId': ?0, 'redeemedAtEpochMs': null, 'expiresAtEpochMs': { $gt: ?1 } }")
    List<PinCode> findUnredeemedByIssuer(String issuedByUserId, long now);

    /** Multi-use invitations this resident issued that are still within their window. */
    @Query("{ 'issuedByUserId': ?0, 'singleUse': false, 'expiresAtEpochMs': { $gt: ?1 } }")
    List<PinCode> findMultiUseByIssuer(String issuedByUserId, long now);
}
