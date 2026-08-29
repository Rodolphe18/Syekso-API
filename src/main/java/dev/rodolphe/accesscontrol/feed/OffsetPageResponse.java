package dev.rodolphe.accesscontrol.feed;

import java.util.List;

/**
 * Offset paging, kept deliberately naive: it is the exhibit for the bug it causes. Ask for page 2
 * after rows have been inserted at the top, and the window has shifted underneath — items already
 * shown on page 1 come back.
 */
record OffsetPageResponse(List<FeedItemDto> items, int page, int nextPage) {
}
