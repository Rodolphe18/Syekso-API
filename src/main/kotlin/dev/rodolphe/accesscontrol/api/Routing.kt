package dev.rodolphe.accesscontrol.api

import dev.rodolphe.accesscontrol.api.routing.*
import dev.rodolphe.accesscontrol.db.MongoStorage
import dev.rodolphe.accesscontrol.security.JwtService
import dev.rodolphe.accesscontrol.signaling.SignalingHub
import io.ktor.server.auth.*
import io.ktor.server.routing.*

fun Route.apiRoutes(storage: MongoStorage, jwt: JwtService, intercomKey: String, hub: SignalingHub) {
    authRoutes(storage, jwt)
    authenticate("auth-jwt") {
        userRoutes(storage)
    }
    intercomRoutes(storage, intercomKey)
    signalingRoute(jwt, intercomKey, hub)
    feedRoutes(storage)
}

