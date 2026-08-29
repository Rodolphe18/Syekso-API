package dev.rodolphe.accesscontrol.feed;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * The feed's own paging query — "the page after this cursor, sorted by (createdAtEpochMs, _id)
 * descending" — is a compound comparison that neither a derived name nor a fixed {@code @Query} can
 * express cleanly, so it waits for {@code MongoTemplate} in iteration 2 along with the endpoint.
 */
public interface FeedItemRepository extends MongoRepository<FeedItem, String> {
}
