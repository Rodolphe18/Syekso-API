package dev.rodolphe.accesscontrol

import dev.rodolphe.accesscontrol.api.ErrorResponse
import dev.rodolphe.accesscontrol.api.apiRoutes
import dev.rodolphe.accesscontrol.db.connectMongo
import dev.rodolphe.accesscontrol.security.JwtService
import dev.rodolphe.accesscontrol.signaling.SignalingHub
import io.ktor.server.websocket.WebSockets
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    val storage = connectMongo()
    runBlocking {
        storage.seedIfEmpty()
        storage.setupFeedItems()
    }

    val jwt = JwtService()
    val signalingHub = SignalingHub(scope = this)

    install(WebSockets)
    install(ContentNegotiation) { json() }

    install(Authentication) {
        jwt("auth-jwt") {
            realm = jwt.realm
            verifier(jwt.verifier)
            validate { credential ->
                if (credential.payload.getClaim(JwtService.CLAIM_USER_ID).asString() != null) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
        }
    }

    // Turn uncaught failures into JSON instead of a stack-trace HTML page the app can't parse.
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled failure", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Erreur interne"))
        }
    }

    routing {
        get("/health") { call.respond(HealthResponse(status = "ok")) }
        apiRoutes(storage, jwt, System.getenv("INTERCOM_KEY") ?: "syekso-demo-intercom-key", signalingHub)
    }
}

@Serializable
data class HealthResponse(val status: String)
