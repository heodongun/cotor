package com.cotor.app

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
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
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
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
    private val tuiSessionService: DesktopTuiSessionService by inject()

    fun start(
        port: Int = 8787,
        host: String = "127.0.0.1",
        wait: Boolean = true,
        token: String? = null
    ) {
        embeddedServer(Netty, port = port, host = host) {
            cotorAppModule(token, desktopService, tuiSessionService)
        }.start(wait = wait)
    }
}

/**
 * Keep the routing tree flat and explicit for the first desktop iteration.
 * This makes it easier to evolve the contract while the Swift app is still
 * changing quickly.
 */
internal fun Application.cotorAppModule(
    token: String?,
    desktopService: DesktopAppService,
    tuiSessionService: DesktopTuiSessionService
) {
    val streamJson = Json { ignoreUnknownKeys = true }
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

        // Ready is the probe that deployment platforms should use to keep the
        // desktop API on the load balancer only once the HTTP stack is available.
        get("/ready") {
            call.respond(HealthResponse(ok = true, service = "cotor-app-server"))
        }

        route("/api/app") {
            get("/health") {
                if (!requireToken(token)) return@get
                call.respond(HealthResponse(ok = true, service = "cotor-app-server"))
            }

            get("/dashboard") {
                if (!requireToken(token)) return@get
                call.respond(desktopService.dashboard())
            }

            route("/settings/backends") {
                get {
                    if (!requireToken(token)) return@get
                    call.respond(desktopService.backendStatuses())
                }

                patch("/default") {
                    if (!requireToken(token)) return@patch
                    val request = call.receive<UpdateBackendSettingsRequest>()
                    respondDesktopRequest {
                        desktopService.updateBackendSettings(
                            defaultBackendKind = request.defaultBackendKind,
                            codexAppServerBaseUrl = request.codexAppServerBaseUrl,
                            codexAuthMode = request.codexAuthMode,
                            codexToken = request.codexToken,
                            codexTimeoutSeconds = request.codexTimeoutSeconds
                        )
                    }
                }

                post("/test") {
                    if (!requireToken(token)) return@post
                    val request = call.receive<TestBackendRequest>()
                    respondDesktopRequest {
                        desktopService.testBackend(
                            kind = request.kind,
                            baseUrl = request.baseUrl,
                            authMode = request.authMode,
                            token = request.token,
                            timeoutSeconds = request.timeoutSeconds
                        )
                    }
                }
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
                            agents = request.agents,
                            issueId = request.issueId
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
                    val task = desktopService.getTask(taskId)
                        ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Task not found: $taskId"))
                    val workspace = desktopService.listWorkspaces().firstOrNull { it.id == task.workspaceId }
                    val run = desktopService.listRuns(taskId)
                        .firstOrNull { it.agentName.equals(agentName, ignoreCase = true) }
                    if (run == null) {
                        call.respond(
                            ChangeSummary(
                                runId = "",
                                branchName = "",
                                baseBranch = workspace?.baseBranch ?: "",
                                patch = "",
                                changedFiles = emptyList()
                            )
                        )
                    } else {
                        call.respond(desktopService.getChanges(run.id))
                    }
                }

                get("/{taskId}/files/{agentName}") {
                    if (!requireToken(token)) return@get
                    val taskId = call.parameters["taskId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "taskId is required"))
                    val agentName = call.parameters["agentName"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "agentName is required"))
                    val run = desktopService.listRuns(taskId)
                        .firstOrNull { it.agentName.equals(agentName, ignoreCase = true) }
                    val relativePath = call.request.queryParameters["path"]
                    if (run == null) {
                        call.respond(emptyList<FileTreeNode>())
                    } else {
                        call.respond(desktopService.listFiles(run.id, relativePath))
                    }
                }

                get("/{taskId}/ports/{agentName}") {
                    if (!requireToken(token)) return@get
                    val taskId = call.parameters["taskId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "taskId is required"))
                    val agentName = call.parameters["agentName"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "agentName is required"))
                    val run = desktopService.listRuns(taskId)
                        .firstOrNull { it.agentName.equals(agentName, ignoreCase = true) }
                    if (run == null) {
                        call.respond(emptyList<PortEntry>())
                    } else {
                        call.respond(desktopService.listPorts(run.id))
                    }
                }
            }

            route("/changes") {
                get {
                    if (!requireToken(token)) return@get
                    val runId = call.request.queryParameters["runId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "runId is required"))
                    respondDesktopRequest {
                        desktopService.getChanges(runId)
                    }
                }
            }

            route("/files") {
                get {
                    if (!requireToken(token)) return@get
                    val runId = call.request.queryParameters["runId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "runId is required"))
                    val relativePath = call.request.queryParameters["path"]
                    respondDesktopRequest {
                        desktopService.listFiles(runId, relativePath)
                    }
                }
            }

            route("/ports") {
                get {
                    if (!requireToken(token)) return@get
                    val runId = call.request.queryParameters["runId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "runId is required"))
                    respondDesktopRequest {
                        desktopService.listPorts(runId)
                    }
                }
            }

            route("/runs") {
                get {
                    if (!requireToken(token)) return@get
                    val taskId = call.request.queryParameters["taskId"]
                    call.respond(desktopService.listRuns(taskId))
                }
            }

            route("/goals") {
                get {
                    if (!requireToken(token)) return@get
                    call.respond(desktopService.listGoals())
                }

                post {
                    if (!requireToken(token)) return@post
                    val request = call.receive<CreateGoalRequest>()
                    respondDesktopRequest {
                        desktopService.createGoal(
                            companyId = null,
                            title = request.title,
                            description = request.description,
                            successMetrics = request.successMetrics,
                            autonomyEnabled = request.autonomyEnabled
                        )
                    }
                }

                get("/{goalId}") {
                    if (!requireToken(token)) return@get
                    val goalId = call.parameters["goalId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "goalId is required"))
                    val goal = desktopService.getGoal(goalId)
                        ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Goal not found: $goalId"))
                    call.respond(goal)
                }

                post("/{goalId}/decompose") {
                    if (!requireToken(token)) return@post
                    val goalId = call.parameters["goalId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "goalId is required"))
                    respondDesktopRequest {
                        desktopService.decomposeGoal(goalId)
                    }
                }
            }

            route("/company") {
                get {
                    if (!requireToken(token)) return@get
                    call.respond(desktopService.companyDashboard())
                }

                route("/goals") {
                    get {
                        if (!requireToken(token)) return@get
                        call.respond(desktopService.listGoals())
                    }

                    post {
                        if (!requireToken(token)) return@post
                        val request = call.receive<CreateGoalRequest>()
                        respondDesktopRequest {
                            desktopService.createGoal(
                                companyId = null,
                                title = request.title,
                                description = request.description,
                                successMetrics = request.successMetrics,
                                autonomyEnabled = request.autonomyEnabled
                            )
                        }
                    }

                    get("/{goalId}") {
                        if (!requireToken(token)) return@get
                        val goalId = call.parameters["goalId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "goalId is required"))
                        val goal = desktopService.getGoal(goalId)
                            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Goal not found: $goalId"))
                        call.respond(goal)
                    }

                    post("/{goalId}/enable-autonomy") {
                        if (!requireToken(token)) return@post
                        val goalId = call.parameters["goalId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "goalId is required"))
                        respondDesktopRequest {
                            desktopService.updateGoalAutonomy(goalId, enabled = true)
                        }
                    }

                    post("/{goalId}/disable-autonomy") {
                        if (!requireToken(token)) return@post
                        val goalId = call.parameters["goalId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "goalId is required"))
                        respondDesktopRequest {
                            desktopService.updateGoalAutonomy(goalId, enabled = false)
                        }
                    }

                    post("/{goalId}/decompose") {
                        if (!requireToken(token)) return@post
                        val goalId = call.parameters["goalId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "goalId is required"))
                        respondDesktopRequest {
                            desktopService.decomposeGoal(goalId)
                        }
                    }
                }

                route("/issues") {
                    get {
                        if (!requireToken(token)) return@get
                        val goalId = call.request.queryParameters["goalId"]
                        call.respond(desktopService.listIssues(goalId))
                    }

                    get("/{issueId}") {
                        if (!requireToken(token)) return@get
                        val issueId = call.parameters["issueId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
                        val issue = desktopService.getIssue(issueId)
                            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Issue not found: $issueId"))
                        call.respond(issue)
                    }

                    post("/{issueId}/delegate") {
                        if (!requireToken(token)) return@post
                        val issueId = call.parameters["issueId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
                        respondDesktopRequest {
                            desktopService.delegateIssue(issueId)
                        }
                    }

                    post("/{issueId}/run") {
                        if (!requireToken(token)) return@post
                        val issueId = call.parameters["issueId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
                        respondDesktopRequest {
                            desktopService.runIssue(issueId)
                        }
                    }
                }

                route("/review-queue") {
                    get {
                        if (!requireToken(token)) return@get
                        call.respond(desktopService.listReviewQueue())
                    }

                    post("/{itemId}/merge") {
                        if (!requireToken(token)) return@post
                        val itemId = call.parameters["itemId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "itemId is required"))
                        respondDesktopRequest {
                            desktopService.mergeReviewQueueItem(itemId)
                        }
                    }
                }

                get("/signals") {
                    if (!requireToken(token)) return@get
                    call.respond(desktopService.listSignals())
                }

                get("/metrics") {
                    if (!requireToken(token)) return@get
                    call.respond(desktopService.dashboard().opsMetrics)
                }

                post("/linear/sync") {
                    if (!requireToken(token)) return@post
                    call.respond(
                        LinearSyncResponse(
                            ok = false,
                            message = "Linear sync adapter is not configured yet in this build"
                        )
                    )
                }

                route("/runtime") {
                    get {
                        if (!requireToken(token)) return@get
                        call.respond(desktopService.runtimeStatus())
                    }

                    post("/start") {
                        if (!requireToken(token)) return@post
                        respondDesktopRequest {
                            desktopService.startCompanyRuntime()
                        }
                    }

                    post("/stop") {
                        if (!requireToken(token)) return@post
                        respondDesktopRequest {
                            desktopService.stopCompanyRuntime()
                        }
                    }
                }
            }

            route("/companies") {
                get {
                    if (!requireToken(token)) return@get
                    call.respond(desktopService.listCompanies())
                }

                post {
                    if (!requireToken(token)) return@post
                    val request = call.receive<CreateCompanyRequest>()
                    respondDesktopRequest {
                        desktopService.createCompany(
                            name = request.name,
                            rootPath = request.rootPath,
                            defaultBaseBranch = request.defaultBaseBranch,
                            autonomyEnabled = request.autonomyEnabled
                        )
                    }
                }

                get("/{companyId}") {
                    if (!requireToken(token)) return@get
                    val companyId = call.parameters["companyId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                    val company = desktopService.getCompany(companyId)
                        ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Company not found: $companyId"))
                    call.respond(company)
                }

                patch("/{companyId}") {
                    if (!requireToken(token)) return@patch
                    val companyId = call.parameters["companyId"]
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                    val request = call.receive<UpdateCompanyRequest>()
                    respondDesktopRequest {
                        desktopService.updateCompany(
                            companyId = companyId,
                            name = request.name,
                            defaultBaseBranch = request.defaultBaseBranch,
                            autonomyEnabled = request.autonomyEnabled,
                            backendKind = request.backendKind
                        )
                    }
                }

                patch("/{companyId}/backend") {
                    if (!requireToken(token)) return@patch
                    val companyId = call.parameters["companyId"]
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                    val request = call.receive<UpdateCompanyBackendRequest>()
                    respondDesktopRequest {
                        desktopService.updateCompanyBackend(
                            companyId = companyId,
                            backendKind = request.backendKind,
                            baseUrl = request.baseUrl,
                            authMode = request.authMode,
                            token = request.token,
                            timeoutSeconds = request.timeoutSeconds,
                            useGlobalDefault = request.useGlobalDefault
                        )
                    }
                }

                delete("/{companyId}") {
                    if (!requireToken(token)) return@delete
                    val companyId = call.parameters["companyId"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                    respondDesktopRequest {
                        desktopService.deleteCompany(companyId)
                    }
                }

                route("/{companyId}/agents") {
                    get {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        call.respond(desktopService.listCompanyAgentDefinitions(companyId))
                    }

                    post {
                        if (!requireToken(token)) return@post
                        val companyId = call.parameters["companyId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        val request = call.receive<CreateCompanyAgentDefinitionRequest>()
                        respondDesktopRequest {
                            desktopService.createCompanyAgentDefinition(
                                companyId = companyId,
                                title = request.title,
                                agentCli = request.agentCli,
                                roleSummary = request.roleSummary,
                                specialties = request.specialties,
                                collaborationInstructions = request.collaborationInstructions,
                                preferredCollaboratorIds = request.preferredCollaboratorIds,
                                memoryNotes = request.memoryNotes,
                                enabled = request.enabled
                            )
                        }
                    }

                    patch("/{agentId}") {
                        if (!requireToken(token)) return@patch
                        val companyId = call.parameters["companyId"]
                            ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        val agentId = call.parameters["agentId"]
                            ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "agentId is required"))
                        val request = call.receive<UpdateCompanyAgentDefinitionRequest>()
                        respondDesktopRequest {
                            desktopService.updateCompanyAgentDefinition(
                                companyId = companyId,
                                agentId = agentId,
                                title = request.title,
                                agentCli = request.agentCli,
                                roleSummary = request.roleSummary,
                                specialties = request.specialties,
                                collaborationInstructions = request.collaborationInstructions,
                                preferredCollaboratorIds = request.preferredCollaboratorIds,
                                memoryNotes = request.memoryNotes,
                                enabled = request.enabled,
                                displayOrder = request.displayOrder
                            )
                        }
                    }
                }

                route("/{companyId}/projects") {
                    get {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        call.respond(desktopService.listProjectContexts(companyId))
                    }
                }

                route("/{companyId}/goals") {
                    get {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        call.respond(desktopService.listGoals().filter { it.companyId == companyId })
                    }

                    post {
                        if (!requireToken(token)) return@post
                        val companyId = call.parameters["companyId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        val request = call.receive<CreateGoalRequest>()
                        respondDesktopRequest {
                            desktopService.createGoal(
                                companyId = companyId,
                                title = request.title,
                                description = request.description,
                                successMetrics = request.successMetrics,
                                autonomyEnabled = request.autonomyEnabled
                            )
                        }
                    }

                    patch("/{goalId}") {
                        if (!requireToken(token)) return@patch
                        val companyId = call.parameters["companyId"]
                            ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        val goalId = call.parameters["goalId"]
                            ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "goalId is required"))
                        val goal = desktopService.listGoals().firstOrNull { it.id == goalId && it.companyId == companyId }
                            ?: return@patch call.respond(HttpStatusCode.NotFound, mapOf("error" to "Goal not found: $goalId"))
                        val request = call.receive<UpdateGoalRequest>()
                        respondDesktopRequest {
                            desktopService.updateGoal(
                                goalId = goal.id,
                                title = request.title,
                                description = request.description,
                                successMetrics = request.successMetrics,
                                autonomyEnabled = request.autonomyEnabled
                            )
                        }
                    }

                    delete("/{goalId}") {
                        if (!requireToken(token)) return@delete
                        val companyId = call.parameters["companyId"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        val goalId = call.parameters["goalId"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "goalId is required"))
                        val goal = desktopService.listGoals().firstOrNull { it.id == goalId && it.companyId == companyId }
                            ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "Goal not found: $goalId"))
                        respondDesktopRequest {
                            desktopService.deleteGoal(goal.id)
                        }
                    }
                }

                route("/{companyId}/issues") {
                    get {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        val goalId = call.request.queryParameters["goalId"]
                        call.respond(desktopService.listIssues(goalId, companyId))
                    }

                    get("/{issueId}") {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        val issueId = call.parameters["issueId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
                        val issue = desktopService.listIssues(companyId = companyId).firstOrNull { it.id == issueId }
                            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Issue not found: $issueId"))
                        call.respond(issue)
                    }

                    delete("/{issueId}") {
                        if (!requireToken(token)) return@delete
                        val companyId = call.parameters["companyId"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        val issueId = call.parameters["issueId"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
                        val issue = desktopService.listIssues(companyId = companyId).firstOrNull { it.id == issueId }
                            ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "Issue not found: $issueId"))
                        respondDesktopRequest {
                            desktopService.deleteIssue(issue.id)
                        }
                    }
                }

                route("/{companyId}/review-queue") {
                    get {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        call.respond(desktopService.listReviewQueue(companyId))
                    }
                }

                route("/{companyId}/activity") {
                    get {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        call.respond(desktopService.listCompanyActivity(companyId))
                    }
                }

                route("/{companyId}/events") {
                    get {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        call.respondTextWriter(ContentType.parse("application/x-ndjson")) {
                            desktopService.companyEvents(companyId).collect { envelope ->
                                write(streamJson.encodeToString(CompanyEventEnvelope.serializer(), envelope))
                                write("\n")
                                flush()
                            }
                        }
                    }
                }

                route("/{companyId}/topology") {
                    get {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        call.respond(desktopService.companyDashboard(companyId).workflowTopologies)
                    }
                }

                route("/{companyId}/decisions") {
                    get {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        call.respond(desktopService.companyDashboard(companyId).goalDecisions)
                    }
                }

                route("/{companyId}/contexts") {
                    get {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        call.respond(desktopService.listProjectContexts(companyId))
                    }
                }

                route("/{companyId}/runtime") {
                    get {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        call.respond(desktopService.runtimeStatus(companyId))
                    }

                    post("/start") {
                        if (!requireToken(token)) return@post
                        val companyId = call.parameters["companyId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        respondDesktopRequest {
                            desktopService.startCompanyRuntime(companyId)
                        }
                    }

                    post("/stop") {
                        if (!requireToken(token)) return@post
                        val companyId = call.parameters["companyId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        respondDesktopRequest {
                            desktopService.stopCompanyRuntime(companyId)
                        }
                    }
                }
            }

            route("/issues") {
                get {
                    if (!requireToken(token)) return@get
                    val goalId = call.request.queryParameters["goalId"]
                    call.respond(desktopService.listIssues(goalId))
                }

                post("/{issueId}/delegate") {
                    if (!requireToken(token)) return@post
                    val issueId = call.parameters["issueId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
                    respondDesktopRequest {
                        desktopService.delegateIssue(issueId)
                    }
                }

                post("/{issueId}/run") {
                    if (!requireToken(token)) return@post
                    val issueId = call.parameters["issueId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
                    respondDesktopRequest {
                        desktopService.runIssue(issueId)
                    }
                }
            }

            route("/review-queue") {
                get {
                    if (!requireToken(token)) return@get
                    call.respond(desktopService.listReviewQueue())
                }

                post("/{itemId}/merge") {
                    if (!requireToken(token)) return@post
                    val itemId = call.parameters["itemId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "itemId is required"))
                    respondDesktopRequest {
                        desktopService.mergeReviewQueueItem(itemId)
                    }
                }
            }

            patch("/workspaces/{workspaceId}/base-branch") {
                if (!requireToken(token)) return@patch
                val workspaceId = call.parameters["workspaceId"]
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "workspaceId is required"))
                val request = call.receive<UpdateWorkspaceBaseBranchRequest>()
                respondDesktopRequest {
                    desktopService.updateWorkspaceBaseBranch(workspaceId, request.baseBranch)
                }
            }

            post("/linear/sync") {
                if (!requireToken(token)) return@post
                call.respond(
                    LinearSyncResponse(
                        ok = false,
                        message = "Linear sync adapter is not configured yet in this build"
                    )
                )
            }

            route("/tui/sessions") {
                post {
                    if (!requireToken(token)) return@post
                    val request = call.receive<OpenTuiSessionRequest>()
                    respondDesktopRequest {
                        tuiSessionService.openSession(request.workspaceId, request.preferredAgent)
                    }
                }

                get("/{sessionId}") {
                    if (!requireToken(token)) return@get
                    val sessionId = call.parameters["sessionId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sessionId is required"))
                    respondDesktopRequest {
                        tuiSessionService.getSession(sessionId)
                    }
                }

                get("/{sessionId}/delta") {
                    if (!requireToken(token)) return@get
                    val sessionId = call.parameters["sessionId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sessionId is required"))
                    val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                    respondDesktopRequest {
                        tuiSessionService.getDelta(sessionId, offset)
                    }
                }

                post("/{sessionId}/input") {
                    if (!requireToken(token)) return@post
                    val sessionId = call.parameters["sessionId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sessionId is required"))
                    val request = call.receive<TuiInputRequest>()
                    respondDesktopRequest {
                        tuiSessionService.sendInput(sessionId, request.input)
                    }
                }

                post("/{sessionId}/terminate") {
                    if (!requireToken(token)) return@post
                    val sessionId = call.parameters["sessionId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sessionId is required"))
                    respondDesktopRequest {
                        tuiSessionService.terminateSession(sessionId)
                    }
                }
            }

            get("/settings") {
                if (!requireToken(token)) return@get
                call.respond(desktopService.settings())
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

/**
 * Desktop terminal routes need predictable JSON errors because the Swift shell
 * uses them to decide whether it should recover by opening a new PTY session.
 */
private suspend fun RoutingContext.respondDesktopRequest(block: suspend () -> Any) {
    try {
        call.respond(block())
    } catch (cause: IllegalArgumentException) {
        val message = cause.message ?: "Invalid request"
        val status = if (message.contains("not found", ignoreCase = true)) {
            HttpStatusCode.NotFound
        } else {
            HttpStatusCode.BadRequest
        }
        call.respond(status, mapOf("error" to message))
    } catch (cause: IllegalStateException) {
        call.respond(HttpStatusCode.Conflict, mapOf("error" to (cause.message ?: "Invalid state")))
    } catch (cause: Throwable) {
        call.respond(
            HttpStatusCode.InternalServerError,
            mapOf("error" to (cause.message ?: cause::class.simpleName ?: "Internal server error"))
        )
    }
}
