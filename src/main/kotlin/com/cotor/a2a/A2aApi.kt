package com.cotor.a2a

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.installA2aRoutes(token: String?, router: A2aRouter) {
    route("/api/a2a/v1") {
        post("/sessions") {
            if (!call.requireA2aToken(token)) return@post
            val request = call.receive<A2aSessionHelloRequest>()
            call.respond(router.openSession(request))
        }

        post("/messages") {
            if (!call.requireA2aToken(token)) return@post
            val request = call.receive<A2aEnvelope>()
            val response = runCatching { router.acceptMessage(request) }
                .getOrElse { error ->
                    return@post call.respond(
                        when (error.message) {
                            "unsupported_version", "unknown_session" -> HttpStatusCode.BadRequest
                            "expired_message" -> HttpStatusCode.Gone
                            else -> HttpStatusCode.InternalServerError
                        },
                        A2aErrorResponse(
                            error = A2aErrorPayload(
                                code = error.message ?: "a2a_error",
                                message = error.message ?: "A2A message handling failed"
                            )
                        )
                    )
                }
            call.respond(response)
        }

        get("/messages/pull") {
            if (!call.requireA2aToken(token)) return@get
            val sessionId = call.request.queryParameters["session_id"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    A2aErrorResponse(error = A2aErrorPayload("missing_session", "session_id is required"))
                )
            val after = call.request.queryParameters["after"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val response = runCatching { router.pullMessages(sessionId, after, limit) }
                .getOrElse { error ->
                    return@get call.respond(
                        HttpStatusCode.BadRequest,
                        A2aErrorResponse(
                            error = A2aErrorPayload(
                                code = error.message ?: "a2a_pull_error",
                                message = error.message ?: "A2A pull failed"
                            )
                        )
                    )
                }
            call.respond(response)
        }

        post("/sync/snapshot") {
            if (!call.requireA2aToken(token)) return@post
            val request = call.receive<A2aSnapshotRequest>()
            call.respond(router.snapshot(request))
        }

        post("/artifacts") {
            if (!call.requireA2aToken(token)) return@post
            val request = call.receive<A2aArtifactRegistrationRequest>()
            call.respond(router.registerArtifact(request))
        }
    }
}

private suspend fun ApplicationCall.requireA2aToken(token: String?): Boolean {
    if (token.isNullOrBlank()) {
        return true
    }
    val header = request.headers["Authorization"]
    val candidate = header
        ?.removePrefix("Bearer")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    return if (candidate == token) {
        true
    } else {
        respond(
            HttpStatusCode.Unauthorized,
            A2aErrorResponse(
                error = A2aErrorPayload(
                    code = "unauthorized",
                    message = "Missing or invalid bearer token"
                )
            )
        )
        false
    }
}
