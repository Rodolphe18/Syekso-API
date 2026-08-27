package dev.rodolphe.accesscontrol.api.routing

import com.mongodb.client.model.Filters
import dev.rodolphe.accesscontrol.api.ErrorResponse
import dev.rodolphe.accesscontrol.api.LoginRequest
import dev.rodolphe.accesscontrol.api.LoginResponse
import dev.rodolphe.accesscontrol.api.toFeedItemDto
import dev.rodolphe.accesscontrol.db.MongoStorage
import dev.rodolphe.accesscontrol.security.JwtService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.firstOrNull
import org.mindrot.jbcrypt.BCrypt


fun Route.authRoutes(storage: MongoStorage, jwt: JwtService) {
    post("/auth/login") {
        val body = call.receive<LoginRequest>()
        val user = storage.users.find(Filters.eq("email", body.email)).firstOrNull()

        // Same response whether the email is unknown or the password is wrong: don't let an attacker
        // probe which emails exist.
        if (user == null || !BCrypt.checkpw(body.password, user.passwordHash)) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Email ou mot de passe incorrect"))
            return@post
        }
        call.respond(LoginResponse(token = jwt.generateToken(user.id), user = user.toFeedItemDto()))
    }
}