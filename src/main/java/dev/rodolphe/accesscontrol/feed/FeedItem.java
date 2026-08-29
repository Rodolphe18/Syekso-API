package dev.rodolphe.accesscontrol.feed;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A synthetic row for the cursor-pagination exercise.
 *
 * @param seq              a readable 1..N counter, only there to spot duplicates across pages; a real
 *                         feed would not need it.
 * @param createdAtEpochMs the sort key, paired with {@code _id} as the tie-breaker.
 */
@Document(collection = "feed_items")
public record FeedItem(
        @Id String id,
        int seq,
        String label,
        long createdAtEpochMs
) {
}
