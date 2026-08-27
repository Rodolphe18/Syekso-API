package dev.rodolphe.accesscontrol.api

import java.util.Base64

/**
 * The pagination cursor is the sort key of the last row returned — (createdAtEpochMs, _id) — encoded as
 * an opaque URL-safe base64 token "ts:id". The client passes it back unchanged; it must not parse it.
 */
fun encodeCursor(createdAtEpochMs: Long, id: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString("$createdAtEpochMs:$id".toByteArray())

fun decodeCursor(cursor: String): Pair<Long, String> {
    val raw = String(Base64.getUrlDecoder().decode(cursor)) // "ts:id"
    val (ts, id) = raw.split(":", limit = 2)                // UUIDs use '-', never ':' → safe
    return ts.toLong() to id
}
