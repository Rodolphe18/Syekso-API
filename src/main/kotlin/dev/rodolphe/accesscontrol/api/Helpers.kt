package dev.rodolphe.accesscontrol.api

import com.mongodb.client.model.Filters
import dev.rodolphe.accesscontrol.db.MongoStorage
import dev.rodolphe.accesscontrol.security.JwtService
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import kotlinx.coroutines.flow.firstOrNull


// génération d'un code pin unique pour accéder à l'immeuble
suspend fun generateUniquePin(storage: MongoStorage): String {
    repeat(10) {
        val pin = (100000..999999).random().toString()
        if (storage.pinCodes.find(Filters.eq("_id", pin)).firstOrNull() == null) return pin
    }
    error("Could not allocate a unique PIN")
}

// extraction de l'userId qui est encodé dans le jwt
fun ApplicationCall.userId(): String =
    principal<JWTPrincipal>()!!.payload.getClaim(JwtService.CLAIM_USER_ID).asString()

