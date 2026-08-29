package dev.rodolphe.accesscontrol.feed;

import java.util.List;

/**
 * Keyset paging. The cursor names the last row seen, so newly inserted rows land above the window
 * instead of shifting it. {@code nextCursor} is null when the last page has been reached.
 */
record CursorPageResponse(List<FeedItemDto> items, String nextCursor) {
}
