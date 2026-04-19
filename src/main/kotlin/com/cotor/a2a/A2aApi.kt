package com.cotor.a2a

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.installA2aRoutes(
    token: String?,
    router: A2aRouter,
    authorize: suspend RoutingContext.(String?) -> Boolean
) {
    route("/api/a2a/v1") {
        post("/sessions") {
            if (!authorize(token)) return@post
            val request = call.receive<A2aHelloRequest>()
            runCatching {
                call.respond(router.openSession(request))
            }.getOrElse { error ->
                respondA2aError(error)
            }
        }

        post("/sessions/{sessionId}/heartbeat") {
            if (!authorize(token)) return@post
            val sessionId = call.parameters["sessionId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, a2aError("missing_session", "sessionId is required"))
            runCatching {
                call.respond(router.heartbeat(sessionId))
            }.getOrElse { error ->
                respondA2aError(error)
            }
        }

        post("/messages") {
            if (!authorize(token)) return@post
            val request = call.receive<A2aEnvelope>()
            runCatching {
                call.respond(router.postMessage(request))
            }.getOrElse { error ->
                respondA2aError(error)
            }
        }

        get("/messages/pull") {
            if (!authorize(token)) return@get
            val sessionId = call.request.queryParameters["session_id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, a2aError("missing_session", "session_id is required"))
            val after = call.request.queryParameters["after"]?.toLongOrNull()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            runCatching {
                call.respond(router.pullMessages(sessionId, after, limit))
            }.getOrElse { error ->
                respondA2aError(error)
            }
        }

        post("/sync/snapshot") {
            if (!authorize(token)) return@post
            val request = call.receive<A2aSnapshotRequest>()
            runCatching {
                call.respond(router.snapshot(request))
            }.getOrElse { error ->
                respondA2aError(error)
            }
        }

        post("/artifacts") {
            if (!authorize(token)) return@post
            val request = call.receive<A2aArtifactRegistrationRequest>()
            runCatching {
                call.respond(router.registerArtifact(request))
            }.getOrElse { error ->
                respondA2aError(error)
            }
        }

        get("/artifacts") {
            if (!authorize(token)) return@get
            val companyId = call.request.queryParameters["company_id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, a2aError("invalid_request", "company_id is required"))
            val issueId = call.request.queryParameters["issue_id"]
            val taskId = call.request.queryParameters["task_id"]
            val runId = call.request.queryParameters["run_id"]
            runCatching {
                call.respond(router.listArtifacts(A2aTenant(companyId = companyId), issueId, taskId, runId))
            }.getOrElse { error ->
                respondA2aError(error)
            }
        }
    }
}

private suspend fun RoutingContext.respondA2aError(error: Throwable) {
    val message = error.message ?: error::class.simpleName.orEmpty()
    val code = when {
        message == "expired_message" -> "expired_message"
        message.startsWith("Unsupported message type") -> "unsupported_type"
        message.startsWith("Unsupported protocol version") -> "unsupported_version"
        message.startsWith("Unknown session") -> "unknown_session"
        error is IllegalArgumentException -> "invalid_request"
        message.contains("required") -> "invalid_request"
        else -> "internal_error"
    }
    val status = when (code) {
        "unknown_session" -> HttpStatusCode.NotFound
        "internal_error" -> HttpStatusCode.InternalServerError
        else -> HttpStatusCode.BadRequest
    }
    call.respond(status, a2aError(code, message))
}
