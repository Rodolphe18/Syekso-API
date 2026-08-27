package dev.rodolphe.accesscontrol.api.routing

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import dev.rodolphe.accesscontrol.api.ActivationRequest
import dev.rodolphe.accesscontrol.api.ActivationResponse
import dev.rodolphe.accesscontrol.api.CreateInvitationRequest
import dev.rodolphe.accesscontrol.api.CreatePinRequest
import dev.rodolphe.accesscontrol.api.DoorsResponse
import dev.rodolphe.accesscontrol.api.ErrorResponse
import dev.rodolphe.accesscontrol.api.InvitationDto
import dev.rodolphe.accesscontrol.api.InvitationsResponse
import dev.rodolphe.accesscontrol.api.PinCodeDto
import dev.rodolphe.accesscontrol.api.PinCodesResponse
import dev.rodolphe.accesscontrol.api.generateUniquePin
import dev.rodolphe.accesscontrol.api.toBuildingDto
import dev.rodolphe.accesscontrol.api.toDoorDtos
import dev.rodolphe.accesscontrol.api.userId
import dev.rodolphe.accesscontrol.db.MongoStorage
import dev.rodolphe.accesscontrol.db.PinCodeDoc
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlin.text.trim

fun Route.userRoutes(storage: MongoStorage) {
    // POST /me/activations — redeem an activation code: attach its building to the user and return
    // the building with its doors. This is the pivot of the whole flow.
    post("/me/activations") {
        val userId = call.userId()
        val code = call.receive<ActivationRequest>().code.trim()

        val activation = storage.activationCodes.find(Filters.eq("_id", code)).firstOrNull()
        if (activation == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Code d'activation inconnu"))
            return@post
        }
        if (activation.redeemedByUserId != null && activation.redeemedByUserId != userId) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Code déjà utilisé"))
            return@post
        }

        // Single-use guard even under concurrency: only claim the code if it is still unredeemed.
        if (activation.redeemedByUserId == null) {
            val claimed = storage.activationCodes.updateOne(
                Filters.and(Filters.eq("_id", code), Filters.eq("redeemedByUserId", null)),
                Updates.combine(
                    Updates.set("redeemedByUserId", userId),
                    Updates.set("redeemedAtEpochMs", System.currentTimeMillis()),
                ),
            )
            if (claimed.modifiedCount == 0L) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("Code déjà utilisé"))
                return@post
            }
        }

        storage.users.updateOne(
            Filters.eq("_id", userId),
            Updates.addToSet("buildingIds", activation.buildingId),
        )

        val building = storage.buildings.find(Filters.eq("_id", activation.buildingId)).firstOrNull()
        if (building == null) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Immeuble introuvable"))
            return@post
        }
        call.respond(ActivationResponse(building.toBuildingDto(), building.toDoorDtos()))
    }

    // GET /me/doors — every door the resident can open, across all buildings they've joined.
    get("/me/doors") {
        val userId = call.userId()
        val user = storage.users.find(Filters.eq("_id", userId)).firstOrNull()
        if (user == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Utilisateur introuvable"))
            return@get
        }
        val buildings = storage.buildings
            .find(Filters.`in`("_id", user.buildingIds))
            .toList()
        call.respond(DoorsResponse(doors = buildings.flatMap { it.toDoorDtos() }))
    }

    // POST /me/pin-codes — issue a single-use numeric PIN for one of the user's doors.
    post("/me/pin-codes") {
        val userId = call.userId()
        val doorId = call.receive<CreatePinRequest>().doorId
        val user = storage.users.find(Filters.eq("_id", userId)).firstOrNull()
        if (user == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Utilisateur introuvable"))
            return@post
        }
        val buildings = storage.buildings.find(Filters.`in`("_id", user.buildingIds)).toList()
        val match = buildings.firstNotNullOfOrNull { b -> b.doors.find { it.id == doorId }?.let { b to it } }
        if (match == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Porte introuvable"))
            return@post
        }
        val (building, door) = match
        val pin = generateUniquePin(storage)
        val now = System.currentTimeMillis()
        val expiresAt = now + 15 * 60 * 1000L
        storage.pinCodes.insertOne(
            PinCodeDoc(
                pin = pin,
                issuedByUserId = userId,
                buildingId = building.id,
                doorId = door.id,
                doorName = door.name,
                doorBleLocalName = door.bleLocalName,
                createdAtEpochMs = now,
                validFromEpochMs = now,
                expiresAtEpochMs = expiresAt,
            ),
        )
        call.respond(PinCodeDto(pin = pin, doorName = door.name, expiresAtEpochMs = expiresAt))
    }

    // GET /me/pin-codes — the caller's still-valid PINs.
    get("/me/pin-codes") {
        val userId = call.userId()
        val now = System.currentTimeMillis()
        val codes = storage.pinCodes.find(
            Filters.and(
                Filters.eq("issuedByUserId", userId),
                Filters.eq("redeemedAtEpochMs", null),
                Filters.gt("expiresAtEpochMs", now),
            ),
        ).toList()
        call.respond(PinCodesResponse(codes.map { PinCodeDto(it.pin, it.doorName, it.expiresAtEpochMs) }))
    }

    // POST /me/invitations — a titled, windowed, multi-use code for one door.
    post("/me/invitations") {
        val userId = call.userId()
        val body = call.receive<CreateInvitationRequest>()
        if (body.title.isBlank() || body.validUntilEpochMs <= body.validFromEpochMs) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Titre ou fenêtre invalide"))
            return@post
        }
        val user = storage.users.find(Filters.eq("_id", userId)).firstOrNull()
        if (user == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Utilisateur introuvable"))
            return@post
        }
        val buildings = storage.buildings.find(Filters.`in`("_id", user.buildingIds)).toList()
        val match = buildings.firstNotNullOfOrNull { b -> b.doors.find { it.id == body.doorId }?.let { b to it } }
        if (match == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Porte introuvable"))
            return@post
        }
        val (building, door) = match
        val code = generateUniquePin(storage)
        storage.pinCodes.insertOne(
            PinCodeDoc(
                pin = code,
                issuedByUserId = userId,
                buildingId = building.id,
                doorId = door.id,
                doorName = door.name,
                doorBleLocalName = door.bleLocalName,
                createdAtEpochMs = System.currentTimeMillis(),
                validFromEpochMs = body.validFromEpochMs,
                expiresAtEpochMs = body.validUntilEpochMs,
                singleUse = false,
                title = body.title.trim(),
            ),
        )
        call.respond(
            InvitationDto(
                code = code,
                title = body.title.trim(),
                doorName = door.name,
                validFromEpochMs = body.validFromEpochMs,
                validUntilEpochMs = body.validUntilEpochMs,
            ),
        )
    }

    // GET /me/invitations — the caller's invitations whose window hasn't ended yet.
    get("/me/invitations") {
        val userId = call.userId()
        val now = System.currentTimeMillis()
        val codes = storage.pinCodes.find(
            Filters.and(
                Filters.eq("issuedByUserId", userId),
                Filters.eq("singleUse", false),
                Filters.gt("expiresAtEpochMs", now),
            ),
        ).toList()
        call.respond(
            InvitationsResponse(
                codes.map {
                    InvitationDto(
                        code = it.pin,
                        title = it.title ?: "",
                        doorName = it.doorName,
                        validFromEpochMs = it.validFromEpochMs,
                        validUntilEpochMs = it.expiresAtEpochMs,
                    )
                },
            ),
        )
    }
}
