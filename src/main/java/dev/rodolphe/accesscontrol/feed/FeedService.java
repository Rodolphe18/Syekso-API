package dev.rodolphe.accesscontrol.feed;

import dev.rodolphe.accesscontrol.feed.FeedItem;
import dev.rodolphe.accesscontrol.feed.CursorPageResponse;
import dev.rodolphe.accesscontrol.feed.FeedItemDto;
import dev.rodolphe.accesscontrol.feed.OffsetPageResponse;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * The two pagination strategies, side by side so the difference can be demonstrated rather than
 * argued about.
 *
 * <p>Both live on {@code MongoTemplate} rather than the repository, for different reasons. Offset
 * paging could use {@code findAll(Pageable)}, but that runs an extra count query to populate a
 * {@code Page}, and this API returns no total — paying for a count nobody reads. Cursor paging has no
 * choice: its filter is a compound {@code $or} over two fields, which no derived method name and no
 * fixed {@code @Query} can express.
 */
@Service
class FeedService {

    /** The sort both strategies rely on; _id breaks ties so the order is total, never ambiguous. */
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Direction.DESC, "createdAtEpochMs", "_id");

    private final MongoTemplate mongo;

    public FeedService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * Naive offset paging — reproduces the duplicate-rows bug under churn on purpose. Skipping N rows
     * assumes the rows below have not moved; inserting at the top breaks that assumption, and the
     * window slides back over items page 1 already showed.
     */
    public OffsetPageResponse byOffset(int page, int limit) {
        Query query = new Query()
                .with(NEWEST_FIRST)
                .skip((long) (page - 1) * limit)
                .limit(limit);
        List<FeedItemDto> items = mongo.find(query, FeedItem.class).stream().map(FeedService::toDto).toList();
        return new OffsetPageResponse(items, page, page + 1);
    }

    /**
     * Keyset paging — anchored on the last row's {@code (createdAtEpochMs, _id)} instead of a count,
     * so inserts above the window cannot shift it.
     *
     * <p>The {@code $or} is what makes the order total: take everything strictly older, plus — for
     * rows sharing the exact same timestamp — those whose id sorts lower. Without the second branch,
     * two rows created in the same millisecond would either both repeat or both vanish.
     */
    public CursorPageResponse byCursor(String cursor, int limit) {
        Query query = new Query();
        if (cursor != null) {
            CursorCodec.Cursor last = CursorCodec.decode(cursor);
            query.addCriteria(new Criteria().orOperator(
                    Criteria.where("createdAtEpochMs").lt(last.createdAtEpochMs()),
                    Criteria.where("createdAtEpochMs").is(last.createdAtEpochMs())
                            .and("_id").lt(last.id())));
        }
        query.with(NEWEST_FIRST).limit(limit);

        List<FeedItem> rows = mongo.find(query, FeedItem.class);
        // A short page means there is nothing beyond it; a full page might be the last one, in which
        // case the next call simply comes back empty. Cheaper than counting to find out.
        String next = rows.size() < limit
                ? null
                : CursorCodec.encode(rows.getLast().createdAtEpochMs(), rows.getLast().id());
        return new CursorPageResponse(rows.stream().map(FeedService::toDto).toList(), next);
    }

    /** Churn: insert rows dated now, so they land on top of the feed and disturb offset paging. */
    public int simulateInserts(int count) {
        long now = System.currentTimeMillis();
        FeedItem newest = mongo.findOne(
                new Query().with(Sort.by(Sort.Direction.DESC, "seq")).limit(1), FeedItem.class);
        int maxSeq = newest == null ? 0 : newest.seq();

        List<FeedItem> rows = java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> new FeedItem(
                        UUID.randomUUID().toString(), maxSeq + i, "Item #" + (maxSeq + i), now + i))
                .toList();
        mongo.insert(rows, FeedItem.class);
        return count;
    }

    private static FeedItemDto toDto(FeedItem item) {
        return new FeedItemDto(item.id(), item.seq(), item.label(), item.createdAtEpochMs());
    }
}
