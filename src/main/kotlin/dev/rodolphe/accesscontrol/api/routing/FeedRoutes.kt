package dev.rodolphe.accesscontrol.api.routing

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import dev.rodolphe.accesscontrol.api.CursorPageResponse
import dev.rodolphe.accesscontrol.api.OffsetPageResponse
import dev.rodolphe.accesscontrol.api.decodeCursor
import dev.rodolphe.accesscontrol.api.encodeCursor
import dev.rodolphe.accesscontrol.api.toFeedItemDto
import dev.rodolphe.accesscontrol.db.FeedItemDoc
import dev.rodolphe.accesscontrol.db.MongoStorage
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import java.util.UUID
import kotlin.text.toIntOrNull


fun Route.feedRoutes(storage: MongoStorage) {
    // Naïve OFFSET pagination by page number — reproduces the duplicate bug under churn.
    get("/feed/offset") {
        // on récupère ce que le user à passer en param pour 'limit' (ex: /feed/offset?limit=5)
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 5
        // on récupère ce que le user à passer en param pour 'page' (ex: /feed/offset?page=2)
        val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        // on transforme le numéro de page en offset pour préparer la requête en BDD
        val offset = (page - 1) * limit
        // On requête les items, en les triant du plus récent au plus ancien, puis par id,
        // puis en skippant un certain nombre d'items, et on limite la sortie à un certain nb.
        val items = storage.feedItems.find()
            .sort(Sorts.descending("createdAtEpochMs", "_id"))
            .skip(offset).limit(limit).toList()
        // Ensuite on renvoie la réponse au client en mappant l'objet métier en objet DTO au préalable
        call.respond(OffsetPageResponse(items.map { it.toFeedItemDto() }, page = page, nextPage = page + 1))
    }

    // Cursor (keyset) pagination — anchored on the last row's (createdAtEpochMs, _id), immune to inserts.
    get("/feed/cursor") {
        // on récupère ce que le user à passer en param pour 'limit' (ex: /feed/offset?limit=5)
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 5
        // on récupère ce que le user à passer en param pour 'cursor' (ex: /feed/offset?cursor="djks45")
        val cursor = call.request.queryParameters["cursor"]
        // On se servira du résultat de 'find' pour la requête en bdd.
        val find = if (cursor == null) {
            // au chargement de la 1ere page, le cursor est null/
            storage.feedItems.find()
        } else {
            // ensuite, quand l'user fera une nouvelle requête, on va décoder le curseur
            // pour connaître le dernier item que l'user a vu.
            // on va ensuite filtrer pour envoyer les items qui sont plus anciens que cet item.
            // si certains items ont le même timestamp, vu qu'on va trier par date puis par id,
            // on renvoie les items qui ont un id inférieur à l'id du curseur.
            val (timestamp, id) = decodeCursor(cursor)
            storage.feedItems.find(
                Filters.or(
                    Filters.lt("createdAtEpochMs", timestamp),
                    Filters.and(Filters.eq("createdAtEpochMs", timestamp), Filters.lt("_id", id)),
                ),
            )
        }
        // on requête en bdd en triant d'abord, avec une limite, et avec le filtre qui est dans l'objet 'find'.
        val items = find.sort(Sorts.descending("createdAtEpochMs", "_id")).limit(limit).toList()
        // on récupère le dernier item du résultat qu'on va renvoyer au client, on l'encode.
        val next = if (items.size < limit) null
        else items.last().let { encodeCursor(it.createdAtEpochMs, it.id) }
        // On renvoie au client les items et le curseur (dernier item encodé).
        call.respond(CursorPageResponse(items.map { it.toFeedItemDto() }, nextCursor = next))
    }

    // Churn: insert N fresh rows (createdAt = now) so they land on top of the feed.
    post("/feed/simulate-inserts") {
        val count = call.request.queryParameters["count"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 5
        val now = System.currentTimeMillis()
        val maxSeq = storage.feedItems.find().sort(Sorts.descending("seq")).limit(1).firstOrNull()?.seq ?: 0
        val docs = (1..count).map { i ->
            FeedItemDoc(UUID.randomUUID().toString(), maxSeq + i, "Item #${maxSeq + i}", now + i)
        }
        storage.feedItems.insertMany(docs)
        call.respond(mapOf("inserted" to count))
    }
}
