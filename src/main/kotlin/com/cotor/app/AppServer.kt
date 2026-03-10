package com.cotor.app

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.routing.RoutingContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Lightweight localhost-only API used by the native macOS shell.
 *
 * The desktop client talks to this server over ordinary HTTP so the Kotlin core
 * can stay headless and reusable while the SwiftUI layer focuses on presentation.
 */
class AppServer : KoinComponent {
    private val desktopService: DesktopAppService by inject()

    fun start(
        port: Int = 8787,
        host: String = "127.0.0.1",
        wait: Boolean = true,
        token: String? = null
    ) {
        embeddedServer(Netty, port = port, host = host) {
            appModule(token)
        }.start(wait = wait)
    }

    /**
     * Keep the routing tree flat and explicit for the first desktop iteration.
     * This makes it easier to evolve the contract while the Swift app is still
     * changing quickly.
     */
    private fun Application.appModule(token: String?) {
        install(ContentNegotiation) {
            json()
        }
        install(CORS) {
            anyHost()
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
        }

        routing {
            // Health stays unauthenticated so the app can distinguish "server down"
            // from "server up but auth misconfigured".
            get("/health") {
                call.respond(HealthResponse(ok = true, service = "cotor-app-server"))
            }

            route("/api/app") {
                get("/dashboard") {
                    if (!requireToken(token)) return@get
                    call.respond(
                        DashboardResponse(
                            repositories = desktopService.listRepositories(),
                            workspaces = desktopService.listWorkspaces(),
                            tasks = desktopService.listTasks(),
                            settings = desktopService.settings()
                        )
                    )
                }

                get("/agents") {
                    if (!requireToken(token)) return@get
                    call.respond(BuiltinAgentCatalog.names())
                }

                route("/repositories") {
                    get {
                        if (!requireToken(token)) return@get
                        call.respond(desktopService.listRepositories())
                    }

                    get("/{repositoryId}/branches") {
                        if (!requireToken(token)) return@get
                        val repositoryId = call.parameters["repositoryId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "repositoryId is required"))
                        call.respond(desktopService.listBranches(repositoryId))
                    }

                    post("/open") {
                        if (!requireToken(token)) return@post
                        val request = call.receive<OpenRepositoryRequest>()
                        call.respond(desktopService.openLocalRepository(request.path))
                    }

                    post("/clone") {
                        if (!requireToken(token)) return@post
                        val request = call.receive<CloneRepositoryRequest>()
                        call.respond(desktopService.cloneRepository(request.url))
                    }
                }

                route("/workspaces") {
                    get {
                        if (!requireToken(token)) return@get
                        val repositoryId = call.request.queryParameters["repositoryId"]
                        call.respond(desktopService.listWorkspaces(repositoryId))
                    }

                    post {
                        if (!requireToken(token)) return@post
                        val request = call.receive<CreateWorkspaceRequest>()
                        call.respond(
                            desktopService.createWorkspace(
                                repositoryId = request.repositoryId,
                                name = request.name,
                                baseBranch = request.baseBranch
                            )
                        )
                    }
                }

                route("/tasks") {
                    get {
                        if (!requireToken(token)) return@get
                        val workspaceId = call.request.queryParameters["workspaceId"]
                        call.respond(desktopService.listTasks(workspaceId))
                    }

                    post {
                        if (!requireToken(token)) return@post
                        val request = call.receive<CreateTaskRequest>()
                        call.respond(
                            desktopService.createTask(
                                workspaceId = request.workspaceId,
                                title = request.title,
                                prompt = request.prompt,
                                agents = request.agents
                            )
                        )
                    }

                    post("/{taskId}/run") {
                        if (!requireToken(token)) return@post
                        val taskId = call.parameters["taskId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "taskId is required"))
                        call.respond(desktopService.runTask(taskId))
                    }

                    get("/{taskId}/changes/{agentName}") {
                        if (!requireToken(token)) return@get
                        val taskId = call.parameters["taskId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "taskId is required"))
                        val agentName = call.parameters["agentName"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "agentName is required"))
                        val run = desktopService.listRuns(taskId)
                            .firstOrNull { it.agentName.equals(agentName, ignoreCase = true) }
                            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "No run found for $agentName"))
                        call.respond(desktopService.getChanges(run.id))
                    }

                    get("/{taskId}/files/{agentName}") {
                        if (!requireToken(token)) return@get
                        val taskId = call.parameters["taskId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "taskId is required"))
                        val agentName = call.parameters["agentName"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "agentName is required"))
                        val run = desktopService.listRuns(taskId)
                            .firstOrNull { it.agentName.equals(agentName, ignoreCase = true) }
                            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "No run found for $agentName"))
                        val relativePath = call.request.queryParameters["path"]
                        call.respond(desktopService.listFiles(run.id, relativePath))
                    }

                    get("/{taskId}/ports/{agentName}") {
                        if (!requireToken(token)) return@get
                        val taskId = call.parameters["taskId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "taskId is required"))
                        val agentName = call.parameters["agentName"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "agentName is required"))
                        val run = desktopService.listRuns(taskId)
                            .firstOrNull { it.agentName.equals(agentName, ignoreCase = true) }
                            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "No run found for $agentName"))
                        call.respond(desktopService.listPorts(run.id))
                    }
                }

                route("/changes") {
                    get {
                        if (!requireToken(token)) return@get
                        val runId = call.request.queryParameters["runId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "runId is required"))
                        call.respond(desktopService.getChanges(runId))
                    }
                }

                route("/files") {
                    get {
                        if (!requireToken(token)) return@get
                        val runId = call.request.queryParameters["runId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "runId is required"))
                        val relativePath = call.request.queryParameters["path"]
                        call.respond(desktopService.listFiles(runId, relativePath))
                    }
                }

                route("/ports") {
                    get {
                        if (!requireToken(token)) return@get
                        val runId = call.request.queryParameters["runId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "runId is required"))
                        call.respond(desktopService.listPorts(runId))
                    }
                }

                route("/runs") {
                    get {
                        if (!requireToken(token)) return@get
                        val taskId = call.request.queryParameters["taskId"]
                        call.respond(desktopService.listRuns(taskId))
                    }
                }

                get("/settings") {
                    if (!requireToken(token)) return@get
                    call.respond(desktopService.settings())
                }
            }
        }
    }
}

/**
 * Token auth is intentionally minimal because the server only binds to localhost.
 * The token mainly protects against accidental cross-process access on the same machine.
 */
private suspend fun RoutingContext.requireToken(token: String?): Boolean {
    val expected = token?.takeIf { it.isNotBlank() } ?: return true
    val actual = call.request.header(HttpHeaders.Authorization)
    if (actual == "Bearer $expected") {
        return true
    }
    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
    return false
}
