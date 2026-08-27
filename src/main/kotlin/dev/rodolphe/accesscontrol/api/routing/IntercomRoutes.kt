package dev.rodolphe.accesscontrol.api.routing

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import dev.rodolphe.accesscontrol.api.DirectoryEntry
import dev.rodolphe.accesscontrol.api.DirectoryResponse
import dev.rodolphe.accesscontrol.api.ErrorResponse
import dev.rodolphe.accesscontrol.api.IntercomOpenResultRequest
import dev.rodolphe.accesscontrol.api.IntercomOpenResultResponse
import dev.rodolphe.accesscontrol.api.IntercomValidateRequest
import dev.rodolphe.accesscontrol.api.IntercomValidateResponse
import dev.rodolphe.accesscontrol.db.MongoStorage
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlin.text.isNullOrBlank

/**
 * How long after being claimed a single-use PIN can still be handed back. Long enough to cover a
 * BLE scan-connect-write round trip (~10 s of scan timeout plus the connection), short enough that
 * a claim from an earlier visitor can never be resurrected.
 */
private const val CLAIM_RELEASE_WINDOW_MS = 120_000L

fun Route.intercomRoutes(storage: MongoStorage, intercomKey: String) {
    // POST /intercom/validate — the intercom device checks a PIN and consumes it (single-use).
    post("/intercom/validate") {
        if (call.request.headers["X-Intercom-Key"] != intercomKey) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Interphone non autorisé"))
            return@post
        }
        val pin = call.receive<IntercomValidateRequest>().pin.trim()
        val now = System.currentTimeMillis()
        val doc = storage.pinCodes.find(Filters.eq("_id", pin)).firstOrNull()
        when {
            doc == null ->
                call.respond(IntercomValidateResponse(allowed = false, reason = "Code inconnu"))
            now < doc.validFromEpochMs ->
                call.respond(IntercomValidateResponse(allowed = false, reason = "Invitation pas encore active"))
            now > doc.expiresAtEpochMs ->
                call.respond(IntercomValidateResponse(allowed = false, reason = "Code expiré"))
            doc.singleUse && doc.redeemedAtEpochMs != null ->
                call.respond(IntercomValidateResponse(allowed = false, reason = "Code déjà utilisé"))
            doc.singleUse -> {
                val claimed = storage.pinCodes.updateOne(
                    Filters.and(Filters.eq("_id", pin), Filters.eq("redeemedAtEpochMs", null)),
                    Updates.set("redeemedAtEpochMs", now),
                )
                if (claimed.modifiedCount == 0L) {
                    call.respond(IntercomValidateResponse(allowed = false, reason = "Code déjà utilisé"))
                } else {
                    call.respond(
                        IntercomValidateResponse(
                            allowed = true,
                            doorName = doc.doorName,
                            doorBleLocalName = doc.doorBleLocalName,
                        ),
                    )
                }
            }
            else -> // multi-use invitation: allowed, not consumed
                call.respond(
                    IntercomValidateResponse(
                        allowed = true,
                        doorName = doc.doorName,
                        doorBleLocalName = doc.doorBleLocalName,
                    ),
                )
        }
    }

    // POST /intercom/open-result — the intercom reports whether the door actually opened.
    //
    // Validation claims a single-use PIN up front so two intercoms can't race for the same code.
    // The physical open can still fail afterwards (door out of range, controller rebooting, radio
    // interference), and burning the visitor's only code for a radio glitch locks out someone who
    // did nothing wrong. A failure therefore releases the claim.
    //
    // Only a *recent* claim can be released: without that window, anyone holding the intercom key
    // could resurrect any code ever used, which would defeat single-use entirely.
    post("/intercom/open-result") {
        if (call.request.headers["X-Intercom-Key"] != intercomKey) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Interphone non autorisé"))
            return@post
        }
        val body = call.receive<IntercomOpenResultRequest>()
        if (body.success) {
            // The claim stands — nothing to do.
            call.respond(IntercomOpenResultResponse(released = false))
            return@post
        }
        val floor = System.currentTimeMillis() - CLAIM_RELEASE_WINDOW_MS
        val released = storage.pinCodes.updateOne(
            Filters.and(
                Filters.eq("_id", body.pin.trim()),
                Filters.eq("singleUse", true),
                Filters.gte("redeemedAtEpochMs", floor),
            ),
            Updates.set("redeemedAtEpochMs", null),
        )
        call.respond(IntercomOpenResultResponse(released = released.modifiedCount > 0L))
    }

    // GET /intercom/directory?buildingId=… — residents of a building, for the intercom's CONTACT list.
    get("/intercom/directory") {
        if (call.request.headers["X-Intercom-Key"] != intercomKey) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Interphone non autorisé"))
            return@get
        }
        val buildingId = call.request.queryParameters["buildingId"]
        if (buildingId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("buildingId requis"))
            return@get
        }
        val residents = storage.users.find(Filters.`in`("buildingIds", buildingId)).toList()
        call.respond(DirectoryResponse(residents.map { DirectoryEntry(it.id, it.displayName) }))
    }
}