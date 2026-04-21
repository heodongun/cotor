package com.cotor.app

/**
 * File overview for AppServer.
 *
 * This file belongs to the app layer for the desktop shell and localhost app-server surface.
 * It groups declarations around app server so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.a2a.A2aRouter
import com.cotor.a2a.installA2aRoutes
import com.cotor.provenance.EvidenceBundle
import com.cotor.runtime.durable.DurableRunSnapshot
import com.cotor.runtime.durable.DurableResumeCoordinator
import com.cotor.runtime.durable.DurableRuntimeService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Lightweight localhost-only API used by the native macOS shell.
 *
 * The desktop client talks to this server over ordinary HTTP so the Kotlin core
 * can stay headless and reusable while the SwiftUI layer focuses on presentation.
 */
class AppServer : KoinComponent {
    private val desktopService: DesktopAppService by inject()
    private val tuiSessionService: DesktopTuiSessionService by inject()
    private val durableRuntimeService: DurableRuntimeService by inject()
    private val durableResumeCoordinator: DurableResumeCoordinator by inject()

    fun start(
        port: Int = 8787,
        host: String = "127.0.0.1",
        wait: Boolean = true,
        token: String? = null
    ) {
        val lockRecord = desktopAppServerInstanceGuard.acquire(host = host, port = port)
        println(
            "[cotor-app-server] acquired desktop app-server instance lock at " +
                "${lockRecord.lockPath} for app home ${lockRecord.appHome}"
        )
        lateinit var server: io.ktor.server.engine.EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        val cleanedUp = AtomicBoolean(false)
        val cleanup = {
            if (cleanedUp.compareAndSet(false, true)) {
                tuiSessionService.shutdown()
                desktopService.shutdown()
                desktopAppServerInstanceGuard.release()
            }
        }
        server = embeddedServer(Netty, port = port, host = host) {
            cotorAppModule(
                token = token,
                desktopService = desktopService,
                tuiSessionService = tuiSessionService,
                durableRuntimeService = durableRuntimeService,
                durableResumeCoordinator = durableResumeCoordinator,
                shutdownHandler = {
                    Thread {
                        server.stop(1000, 5000)
                    }.start()
                }
            )
        }
        server.environment.monitor.subscribe(ApplicationStopped) {
            cleanup()
        }
        Runtime.getRuntime().addShutdownHook(
            Thread {
                cleanup()
            }
        )
        try {
            server.start(wait = wait)
        } catch (error: Throwable) {
            cleanup()
            throw error
        }
    }
}

@Serializable
internal data class DesktopAppServerInstanceMetadata(
    val pid: Long,
    val host: String,
    val port: Int,
    val appHome: String,
    val startedAt: Long
)

internal data class DesktopAppServerLockRecord(
    val appHome: Path,
    val lockPath: Path,
    val metadataPath: Path
)

internal data class DesktopAppServerInstanceStatus(
    val active: Boolean,
    val metadata: DesktopAppServerInstanceMetadata? = null
)

internal class DesktopAppServerInstanceGuard(
    private val appHomeProvider: () -> Path = { defaultDesktopAppHome() },
    private val channelOpener: (Path) -> FileChannel = { lockPath ->
        FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE
        )
    }
) {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var record: DesktopAppServerLockRecord? = null
    private val json = Json {
        encodeDefaults = true
    }

    fun acquire(host: String, port: Int): DesktopAppServerLockRecord {
        if (lock != null) {
            return requireNotNull(record)
        }
        val appHome = appHomeProvider()
        val runtimeDir = appHome.resolve("runtime").resolve("backend")
        runtimeDir.createDirectories()
        val lockPath = runtimeDir.resolve("app-server.instance.lock")
        val metadataPath = runtimeDir.resolve("app-server.instance.json")
        val openedChannel = channelOpener(lockPath)
        val openedLock = try {
            openedChannel.tryLock()
        } catch (_: OverlappingFileLockException) {
            openedChannel.close()
            throw IllegalStateException(
                "Desktop app-server lock is already held in this process for $appHome. " +
                    "Lock=$lockPath"
            )
        } catch (error: IOException) {
            openedChannel.close()
            throw IllegalStateException(
                "Failed to acquire desktop app-server lock at $lockPath for $appHome.",
                error
            )
        }
        if (openedLock == null) {
            val existing = runCatching { Files.readString(metadataPath) }.getOrDefault("unavailable")
            openedChannel.close()
            throw IllegalStateException(
                "Another desktop app-server is already active for $appHome. " +
                    "Lock=$lockPath metadata=$existing"
            )
        }
        val currentRecord = DesktopAppServerLockRecord(
            appHome = appHome,
            lockPath = lockPath,
            metadataPath = metadataPath
        )
        val metadata = DesktopAppServerInstanceMetadata(
            pid = ProcessHandle.current().pid(),
            host = host,
            port = port,
            appHome = appHome.toString(),
            startedAt = System.currentTimeMillis()
        )
        metadataPath.writeText(json.encodeToString(DesktopAppServerInstanceMetadata.serializer(), metadata))
        channel = openedChannel
        lock = openedLock
        record = currentRecord
        return currentRecord
    }

    fun release() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        lock = null
        channel = null
        record = null
    }
}

internal val desktopAppServerInstanceGuard = DesktopAppServerInstanceGuard()

internal fun readDesktopAppServerInstanceStatus(appHome: Path): DesktopAppServerInstanceStatus {
    val metadataPath = appHome.resolve("runtime").resolve("backend").resolve("app-server.instance.json")
    if (!Files.exists(metadataPath)) {
        return DesktopAppServerInstanceStatus(active = false)
    }
    val json = Json { ignoreUnknownKeys = true }
    val metadata = runCatching {
        json.decodeFromString(DesktopAppServerInstanceMetadata.serializer(), Files.readString(metadataPath))
    }.getOrNull() ?: return DesktopAppServerInstanceStatus(active = false)
    val active = ProcessHandle.of(metadata.pid).map(ProcessHandle::isAlive).orElse(false)
    return DesktopAppServerInstanceStatus(active = active, metadata = metadata.takeIf { active })
}

private val mcpJson = Json {
    encodeDefaults = true
    prettyPrint = true
    ignoreUnknownKeys = true
}

/**
 * Keep the routing tree flat and explicit for the first desktop iteration.
 * This makes it easier to evolve the contract while the Swift app is still
 * changing quickly.
 */
internal fun Application.cotorAppModule(
    token: String?,
    desktopService: DesktopAppService,
    tuiSessionService: DesktopTuiSessionService,
    a2aRouter: A2aRouter = A2aRouter(desktopService),
    durableRuntimeService: DurableRuntimeService? = null,
    durableResumeCoordinator: DurableResumeCoordinator? = null,
    shutdownHandler: (() -> Unit)? = null
) {
    val ktorJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    val streamJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    install(ContentNegotiation) {
        json(ktorJson)
    }
    install(CORS) {
        allowHost("127.0.0.1", schemes = listOf("http"))
        allowHost("localhost", schemes = listOf("http"))
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
    }

    routing {
        installA2aRoutes(token, a2aRouter) { expectedToken ->
            this.requireToken(expectedToken)
        }

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
            // `/api/app` is the contract consumed by the desktop shell. The routes under this
            // prefix intentionally stay business-oriented so the Swift client can think in terms
            // of dashboards, companies, issues, and sessions rather than raw persistence details.
            get("/health") {
                if (!requireToken(token)) return@get
                call.respond(HealthResponse(ok = true, service = "cotor-app-server"))
            }

            get("/help-guide") {
                if (!requireToken(token)) return@get
                val language = com.cotor.presentation.cli.CliHelpLanguage.resolve(call.request.queryParameters["lang"])
                call.respond(HelpGuideContent.guide(language))
            }

            post("/shutdown") {
                if (!requireToken(token)) return@post
                call.respond(HttpStatusCode.Accepted, HealthResponse(ok = true, service = "cotor-app-server"))
                shutdownHandler?.invoke()
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
                            codePublishMode = request.codePublishMode,
                            codexLaunchMode = request.codexLaunchMode,
                            codexCommand = request.codexCommand,
                            codexArgs = request.codexArgs,
                            codexWorkingDirectory = request.codexWorkingDirectory,
                            codexPort = request.codexPort,
                            codexStartupTimeoutSeconds = request.codexStartupTimeoutSeconds,
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
                            launchMode = request.launchMode,
                            command = request.command,
                            args = request.args,
                            workingDirectory = request.workingDirectory,
                            port = request.port,
                            startupTimeoutSeconds = request.startupTimeoutSeconds,
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
                    call.respond(desktopService.listRuns(taskId).map(::toApiRunRecord))
                }
            }

            route("/durable-runtime") {
                get("/runs") {
                    if (!requireToken(token)) return@get
                    call.respond(durableRuntimeService?.listRuns().orEmpty())
                }

                get("/runs/{runId}") {
                    if (!requireToken(token)) return@get
                    val runId = call.parameters["runId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "runId is required"))
                    val snapshot = durableRuntimeService?.inspectRun(runId)
                        ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Durable run not found: $runId"))
                    call.respond(snapshot)
                }

                post("/runs/{runId}/continue") {
                    if (!requireToken(token)) return@post
                    val runId = call.parameters["runId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "runId is required"))
                    val coordinator = durableResumeCoordinator
                        ?: return@post call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "Durable runtime coordinator not configured"))
                    val request = call.receive<DurableContinueRequest>()
                    respondDesktopRequest {
                        coordinator.continueRun(runId, request.configPath)
                    }
                }

                post("/runs/{runId}/fork") {
                    if (!requireToken(token)) return@post
                    val runId = call.parameters["runId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "runId is required"))
                    val coordinator = durableResumeCoordinator
                        ?: return@post call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "Durable runtime coordinator not configured"))
                    val request = call.receive<DurableForkRequest>()
                    respondDesktopRequest {
                        coordinator.forkRun(runId, request.checkpointId, request.configPath)
                    }
                }

                post("/runs/{runId}/approve") {
                    if (!requireToken(token)) return@post
                    val runId = call.parameters["runId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "runId is required"))
                    val coordinator = durableResumeCoordinator
                        ?: return@post call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "Durable runtime coordinator not configured"))
                    val request = call.receive<DurableApproveRequest>()
                    respondDesktopRequest {
                        coordinator.approve(runId, request.checkpointId)
                    }
                }
            }

            route("/policy") {
                get("/decisions") {
                    if (!requireToken(token)) return@get
                    val runId = call.request.queryParameters["runId"]
                    val issueId = call.request.queryParameters["issueId"]
                    call.respond(desktopService.policyDecisions(runId = runId, issueId = issueId))
                }
            }

            route("/evidence") {
                get("/runs/{runId}") {
                    if (!requireToken(token)) return@get
                    val runId = call.parameters["runId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "runId is required"))
                    call.respond(desktopService.evidenceForRun(runId))
                }

                get("/files") {
                    if (!requireToken(token)) return@get
                    val path = call.request.queryParameters["path"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "path is required"))
                    if (path.isBlank()) {
                        return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "path must not be blank"))
                    }
                    call.respond(desktopService.evidenceForFile(path))
                }
            }

            route("/github") {
                get("/pull-requests") {
                    if (!requireToken(token)) return@get
                    val companyId = call.request.queryParameters["companyId"]
                    call.respond(desktopService.listGitHubPullRequests(companyId))
                }

                get("/events") {
                    if (!requireToken(token)) return@get
                    val companyId = call.request.queryParameters["companyId"]
                    call.respond(desktopService.listGitHubEvents(companyId))
                }

                get("/pull-requests/{pullRequestNumber}") {
                    if (!requireToken(token)) return@get
                    val pullRequestNumber = call.parameters["pullRequestNumber"]?.toIntOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "pullRequestNumber is required"))
                    val snapshot = desktopService.inspectGitHubPullRequest(pullRequestNumber)
                        ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Pull request not found: $pullRequestNumber"))
                    call.respond(snapshot)
                }

                post("/companies/{companyId}/sync") {
                    if (!requireToken(token)) return@post
                    val companyId = call.parameters["companyId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                    respondDesktopRequest {
                        desktopService.syncGitHubProvider(companyId)
                    }
                }
            }

            route("/knowledge") {
                get("/issues/{issueId}") {
                    if (!requireToken(token)) return@get
                    val issueId = call.parameters["issueId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
                    call.respond(desktopService.issueKnowledge(issueId))
                }
            }

            route("/verification") {
                get("/issues/{issueId}") {
                    if (!requireToken(token)) return@get
                    val issueId = call.parameters["issueId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
                    call.respond(desktopService.verificationBundle(issueId))
                }
            }

            route("/runtime") {
                get("/issues/{issueId}/projection") {
                    if (!requireToken(token)) return@get
                    val issueId = call.parameters["issueId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
                    call.respond(desktopService.issueRuntimeProjection(issueId))
                }
            }

            post("/mcp") {
                if (!requireToken(token)) return@post
                val request = call.receive<JsonElement>()
                call.respond(handleReadonlyMcpRequest(request, desktopService, durableRuntimeService))
            }

            route("/issues/{issueId}/runs") {
                get {
                    if (!requireToken(token)) return@get
                    val issueId = call.parameters["issueId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
                    call.respond(desktopService.listIssueRuns(issueId).map(::toApiRunRecord))
                }
            }

            route("/issues/{issueId}/execution-details") {
                get {
                    if (!requireToken(token)) return@get
                    val issueId = call.parameters["issueId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
                    call.respond(desktopService.issueExecutionDetails(issueId))
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
                    call.respond(desktopService.companyDashboardReadOnly())
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
                        val issue = desktopService.getIssueProjected(issueId)
                            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Issue not found: $issueId"))
                        call.respond(issue)
                    }

                    get("/{issueId}/execution-details") {
                        if (!requireToken(token)) return@get
                        val issueId = call.parameters["issueId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
                        call.respond(desktopService.issueExecutionDetails(issueId))
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
                    respondDesktopRequest {
                        val companyId = desktopService.listCompanies().maxByOrNull { it.updatedAt }?.id
                            ?: throw IllegalStateException("Create a company before syncing Linear")
                        desktopService.syncCompanyLinear(companyId)
                    }
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
                // Company routes expose the highest-level operating surface in the product. Most of
                // the desktop experience hangs off this subtree because companies own goals,
                // issues, runtime state, workflow topology, and organization rosters.
                get {
                    if (!requireToken(token)) return@get
                    call.respond(desktopService.listCompanies())
                }

                post {
                    if (!requireToken(token)) return@post
                    val request = call.receive<CreateCompanyRequest>()
                    respondDesktopRequest {
                        val company = desktopService.createCompany(
                            name = request.name,
                            rootPath = request.rootPath,
                            defaultBaseBranch = request.defaultBaseBranch,
                            autonomyEnabled = request.autonomyEnabled,
                            dailyBudgetCents = request.dailyBudgetCents,
                            monthlyBudgetCents = request.monthlyBudgetCents
                        )
                        CreateCompanyResponse(
                            company = company,
                            githubPublishStatus = desktopService.githubPublishStatus(company.id)
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

                get("/{companyId}/dashboard") {
                    if (!requireToken(token)) return@get
                    val companyId = call.parameters["companyId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                    call.respond(desktopService.companyDashboardReadOnly(companyId))
                }

                get("/{companyId}/memory-snapshot") {
                    if (!requireToken(token)) return@get
                    val companyId = call.parameters["companyId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                    val issueId = call.request.queryParameters["issueId"]
                    val agentProfileId = call.request.queryParameters["agentProfileId"]
                    respondDesktopRequest {
                        desktopService.companyMemorySnapshot(companyId, issueId, agentProfileId)
                    }
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
                            backendKind = request.backendKind,
                            dailyBudgetCents = request.dailyBudgetCents,
                            monthlyBudgetCents = request.monthlyBudgetCents
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
                            launchMode = request.launchMode,
                            command = request.command,
                            args = request.args,
                            workingDirectory = request.workingDirectory,
                            port = request.port,
                            startupTimeoutSeconds = request.startupTimeoutSeconds,
                            baseUrl = request.baseUrl,
                            authMode = request.authMode,
                            token = request.token,
                            timeoutSeconds = request.timeoutSeconds,
                            useGlobalDefault = request.useGlobalDefault
                        )
                    }
                }

                get("/{companyId}/backend") {
                    if (!requireToken(token)) return@get
                    val companyId = call.parameters["companyId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                    respondDesktopRequest {
                        desktopService.companyBackendStatus(companyId)
                    }
                }

                post("/{companyId}/backend/start") {
                    if (!requireToken(token)) return@post
                    val companyId = call.parameters["companyId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                    respondDesktopRequest {
                        desktopService.startCompanyBackend(companyId)
                    }
                }

                post("/{companyId}/backend/stop") {
                    if (!requireToken(token)) return@post
                    val companyId = call.parameters["companyId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                    respondDesktopRequest {
                        desktopService.stopCompanyBackend(companyId)
                    }
                }

                post("/{companyId}/backend/restart") {
                    if (!requireToken(token)) return@post
                    val companyId = call.parameters["companyId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                    respondDesktopRequest {
                        desktopService.restartCompanyBackend(companyId)
                    }
                }

                patch("/{companyId}/linear") {
                    if (!requireToken(token)) return@patch
                    val companyId = call.parameters["companyId"]
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                    val request = call.receive<UpdateCompanyLinearRequest>()
                    respondDesktopRequest {
                        desktopService.updateCompanyLinear(
                            companyId = companyId,
                            enabled = request.enabled,
                            endpoint = request.endpoint,
                            apiToken = request.apiToken,
                            teamId = request.teamId,
                            projectId = request.projectId,
                            stateMappings = request.stateMappings,
                            useGlobalDefault = request.useGlobalDefault
                        )
                    }
                }

                post("/{companyId}/linear/resync") {
                    if (!requireToken(token)) return@post
                    val companyId = call.parameters["companyId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                    respondDesktopRequest {
                        desktopService.syncCompanyLinear(companyId)
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
                                model = request.model,
                                roleSummary = request.roleSummary,
                                specialties = request.specialties,
                                collaborationInstructions = request.collaborationInstructions,
                                preferredCollaboratorIds = request.preferredCollaboratorIds,
                                memoryNotes = request.memoryNotes,
                                enabled = request.enabled
                            )
                        }
                    }

                    patch("/batch") {
                        if (!requireToken(token)) return@patch
                        val companyId = call.parameters["companyId"]
                            ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        val request = call.receive<BatchUpdateCompanyAgentDefinitionsRequest>()
                        respondDesktopRequest {
                            desktopService.batchUpdateCompanyAgentDefinitions(
                                companyId = companyId,
                                agentIds = request.agentIds,
                                agentCli = request.agentCli,
                                model = request.model,
                                specialties = request.specialties,
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
                                model = request.model,
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

                    post {
                        if (!requireToken(token)) return@post
                        val companyId = call.parameters["companyId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        val request = call.receive<CreateIssueRequest>()
                        respondDesktopRequest {
                            desktopService.createIssue(
                                companyId = companyId,
                                goalId = request.goalId,
                                title = request.title,
                                description = request.description,
                                priority = request.priority,
                                kind = request.kind
                            )
                        }
                    }

                    get("/{issueId}") {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        val issueId = call.parameters["issueId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
                        val issue = desktopService.getIssueProjected(issueId)?.takeIf { it.companyId == companyId }
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
                        call.respond(desktopService.listWorkflowTopologies(companyId))
                    }
                }

                route("/{companyId}/decisions") {
                    get {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        call.respond(desktopService.listGoalDecisions(companyId))
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

                route("/{companyId}/pipelines") {
                    get {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        call.respond(desktopService.listPipelines(companyId))
                    }

                    post {
                        if (!requireToken(token)) return@post
                        val companyId = call.parameters["companyId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        val request = call.receive<CreatePipelineRequest>()
                        respondDesktopRequest {
                            desktopService.createPipeline(
                                companyId = companyId,
                                name = request.name,
                                stages = request.stages.mapIndexed { idx, s ->
                                    WorkflowStageDefinition(
                                        id = s.id ?: "stage-$idx",
                                        kind = s.kind,
                                        title = s.title,
                                        assigneeRoleName = s.assigneeRoleName,
                                        verdictKey = s.verdictKey,
                                        verdictPassValue = s.verdictPassValue ?: "PASS",
                                        verdictFailValue = s.verdictFailValue ?: "CHANGES_REQUESTED",
                                        skipWhen = s.skipWhen,
                                        order = idx
                                    )
                                }
                            )
                        }
                    }

                    patch("/{pipelineId}") {
                        if (!requireToken(token)) return@patch
                        val pipelineId = call.parameters["pipelineId"]
                            ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "pipelineId is required"))
                        val request = call.receive<UpdatePipelineRequest>()
                        respondDesktopRequest {
                            desktopService.updatePipeline(
                                pipelineId = pipelineId,
                                name = request.name,
                                stages = request.stages?.mapIndexed { idx, s ->
                                    WorkflowStageDefinition(
                                        id = s.id ?: "stage-$idx",
                                        kind = s.kind,
                                        title = s.title,
                                        assigneeRoleName = s.assigneeRoleName,
                                        verdictKey = s.verdictKey,
                                        verdictPassValue = s.verdictPassValue ?: "PASS",
                                        verdictFailValue = s.verdictFailValue ?: "CHANGES_REQUESTED",
                                        skipWhen = s.skipWhen,
                                        order = idx
                                    )
                                }
                            )
                        }
                    }

                    delete("/{pipelineId}") {
                        if (!requireToken(token)) return@delete
                        val pipelineId = call.parameters["pipelineId"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "pipelineId is required"))
                        respondDesktopRequest { desktopService.deletePipeline(pipelineId) }
                    }

                    post("/{pipelineId}/set-default") {
                        if (!requireToken(token)) return@post
                        val companyId = call.parameters["companyId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        val pipelineId = call.parameters["pipelineId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "pipelineId is required"))
                        respondDesktopRequest { desktopService.setDefaultPipeline(companyId, pipelineId) }
                    }
                }

                route("/{companyId}/context-entries") {
                    get {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        val goalId = call.request.queryParameters["goalId"]
                        val issueId = call.request.queryParameters["issueId"]
                        call.respond(desktopService.listContextEntries(companyId, goalId, issueId))
                    }

                    post {
                        if (!requireToken(token)) return@post
                        val companyId = call.parameters["companyId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        val request = call.receive<CreateContextEntryRequest>()
                        respondDesktopRequest {
                            desktopService.addContextEntry(
                                companyId = companyId,
                                agentName = request.agentName,
                                kind = request.kind,
                                title = request.title,
                                content = request.content,
                                issueId = request.issueId,
                                goalId = request.goalId,
                                visibility = request.visibility ?: "company"
                            )
                        }
                    }

                    delete("/{entryId}") {
                        if (!requireToken(token)) return@delete
                        val entryId = call.parameters["entryId"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "entryId is required"))
                        desktopService.deleteContextEntry(entryId)
                        call.respond(HttpStatusCode.OK, mapOf("deleted" to entryId))
                    }
                }

                route("/{companyId}/messages") {
                    get {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        val goalId = call.request.queryParameters["goalId"]
                        val issueId = call.request.queryParameters["issueId"]
                        call.respond(desktopService.listMessages(companyId, goalId, issueId))
                    }

                    post {
                        if (!requireToken(token)) return@post
                        val companyId = call.parameters["companyId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        val request = call.receive<SendMessageRequest>()
                        respondDesktopRequest {
                            desktopService.sendMessage(
                                companyId = companyId,
                                fromAgentName = request.fromAgentName,
                                toAgentName = request.toAgentName,
                                kind = request.kind,
                                subject = request.subject,
                                body = request.body,
                                issueId = request.issueId,
                                goalId = request.goalId
                            )
                        }
                    }
                }

                route("/{companyId}/execution-log") {
                    get {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        val payload = desktopService.executionLog(companyId)
                        call.respondText(
                            text = streamJson.encodeToString(JsonArray.serializer(), payload.toJsonArray()),
                            contentType = ContentType.Application.Json
                        )
                    }
                }

                route("/{companyId}/issue-graph") {
                    get {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        call.respond(desktopService.issueGraph(companyId))
                    }
                }

                route("/{companyId}/budget") {
                    get {
                        if (!requireToken(token)) return@get
                        val companyId = call.parameters["companyId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                        val state = desktopService.dashboard()
                        val company = state.companies.firstOrNull { it.id == companyId }
                            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Company not found"))
                        val runtime = state.companyRuntimes.firstOrNull { it.companyId == companyId }
                        call.respond(
                            BudgetResponse(
                                dailyBudgetCents = company.dailyBudgetCents,
                                monthlyBudgetCents = company.monthlyBudgetCents,
                                todaySpentCents = runtime?.todaySpentCents ?: 0,
                                monthSpentCents = runtime?.monthSpentCents ?: 0,
                                budgetPaused = runtime?.budgetPausedAt != null
                            )
                        )
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

                get("/{issueId}/execution-details") {
                    if (!requireToken(token)) return@get
                    val issueId = call.parameters["issueId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
                    call.respond(desktopService.issueExecutionDetails(issueId))
                }

                post("/{issueId}/delegate") {
                    if (!requireToken(token)) return@post
                    val issueId = call.parameters["issueId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
                    respondDesktopRequest {
                        desktopService.delegateIssue(issueId)
                    }
                }

                patch("/{issueId}/assignee") {
                    if (!requireToken(token)) return@patch
                    val issueId = call.parameters["issueId"]
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
                    val request = call.receive<IssueAssigneeUpdateRequest>()
                    respondDesktopRequest {
                        desktopService.updateIssueAssignee(issueId, request.assigneeProfileId)
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

                post("/{itemId}/qa") {
                    if (!requireToken(token)) return@post
                    val itemId = call.parameters["itemId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "itemId is required"))
                    val request = call.receive<ReviewQueueVerdictRequest>()
                    respondDesktopRequest {
                        desktopService.submitQaReviewVerdict(itemId, request.verdict, request.feedback)
                    }
                }

                post("/{itemId}/ceo") {
                    if (!requireToken(token)) return@post
                    val itemId = call.parameters["itemId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "itemId is required"))
                    val request = call.receive<ReviewQueueVerdictRequest>()
                    respondDesktopRequest {
                        desktopService.submitCeoReviewVerdict(itemId, request.verdict, request.feedback)
                    }
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
                respondDesktopRequest {
                    val companyId = desktopService.listCompanies().maxByOrNull { it.updatedAt }?.id
                        ?: throw IllegalStateException("Create a company before syncing Linear")
                    desktopService.syncCompanyLinear(companyId)
                }
            }

            route("/tui/sessions") {
                // TUI sessions are long-lived interactive processes. The API separates opening,
                // transcript snapshots, incremental deltas, and input forwarding so the desktop UI
                // can rebuild a terminal view without holding a websocket or a native PTY itself.
                get {
                    if (!requireToken(token)) return@get
                    respondDesktopRequest {
                        tuiSessionService.listSessions()
                    }
                }

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

internal fun Application.cotorAppModule(
    token: String?,
    desktopService: DesktopAppService,
    tuiSessionService: DesktopTuiSessionService,
    shutdownHandler: (() -> Unit)?
) {
    cotorAppModule(
        token = token,
        desktopService = desktopService,
        tuiSessionService = tuiSessionService,
        a2aRouter = A2aRouter(desktopService),
        durableRuntimeService = null,
        durableResumeCoordinator = null,
        shutdownHandler = shutdownHandler
    )
}

internal fun Application.cotorAppModule(
    token: String?,
    desktopService: DesktopAppService,
    tuiSessionService: DesktopTuiSessionService,
    a2aRouter: A2aRouter,
    shutdownHandler: (() -> Unit)?
) {
    cotorAppModule(
        token = token,
        desktopService = desktopService,
        tuiSessionService = tuiSessionService,
        a2aRouter = a2aRouter,
        durableRuntimeService = null,
        durableResumeCoordinator = null,
        shutdownHandler = shutdownHandler
    )
}

private const val RUN_OUTPUT_LIMIT = 40_000
private const val RUN_ERROR_LIMIT = 8_000

private suspend fun handleReadonlyMcpRequest(
    request: JsonElement,
    desktopService: DesktopAppService,
    durableRuntimeService: DurableRuntimeService?
): JsonElement {
    val root = request.jsonObject
    val id = root["id"] ?: JsonNull
    val method = root["method"]?.jsonPrimitive?.contentOrNull
        ?: return mcpError(id, code = -32600, message = "Missing JSON-RPC method")
    val params = root["params"]?.jsonObject ?: buildJsonObject { }
    return when (method) {
        "initialize" -> buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", id)
            put(
                "result",
                buildJsonObject {
                    put("protocolVersion", JsonPrimitive("2025-06-18"))
                    put(
                        "serverInfo",
                        buildJsonObject {
                            put("name", JsonPrimitive("cotor-readonly"))
                            put("version", JsonPrimitive("1.0"))
                        }
                    )
                    put(
                        "capabilities",
                        buildJsonObject {
                            put("tools", buildJsonObject {
                                put("listChanged", JsonPrimitive(false))
                            })
                            put("resources", buildJsonObject {
                                put("subscribe", JsonPrimitive(false))
                                put("listChanged", JsonPrimitive(false))
                            })
                            put("annotations", buildJsonObject {
                                put("readOnlySurface", JsonPrimitive(true))
                            })
                        }
                    )
                }
            )
        }
        "tools/list" -> mcpResult(id, buildJsonObject {
            put(
                "tools",
                    buildJsonArray {
                        add(mcpToolDescriptor("company_summary", "Return the company dashboard summary.", readOnly = true))
                        add(mcpToolDescriptor("issue_list", "Return issues for one company.", readOnly = true))
                        add(mcpToolDescriptor("durable_run_inspect", "Inspect one durable runtime run.", readOnly = true))
                        add(mcpToolDescriptor("approval_queue", "Return runs waiting for approval.", readOnly = true))
                        add(mcpToolDescriptor("evidence_summary", "Return evidence bundle for a run or file.", readOnly = true))
                        add(mcpToolDescriptor("verification_bundle", "Return verification bundle for one issue.", readOnly = true))
                        add(mcpToolDescriptor("company_memory_snapshot", "Return the unified company/workflow/agent memory snapshot.", readOnly = true))
                        add(mcpToolDescriptor("github_company_events", "Return GitHub provider events for one company.", readOnly = true))
                        add(mcpToolDescriptor("runtime_projection", "Return projected runtime state for one issue.", readOnly = true))
                        add(mcpToolDescriptor("company_runtime_start", "Start one company runtime.", readOnly = false))
                        add(mcpToolDescriptor("company_runtime_stop", "Stop one company runtime.", readOnly = false))
                        add(mcpToolDescriptor("company_review_qa", "Submit a QA review verdict for one review queue item.", readOnly = false))
                        add(mcpToolDescriptor("company_review_ceo", "Submit a CEO review verdict for one review queue item.", readOnly = false))
                    }
                )
            })
        "tools/call" -> {
            val toolName = params["name"]?.jsonPrimitive?.contentOrNull
                ?: return mcpError(id, -32602, "Missing tool name")
            val arguments = params["arguments"]?.jsonObject ?: buildJsonObject { }
            val content = when (toolName) {
                "company_summary" -> {
                    val companyId = arguments["companyId"]?.jsonPrimitive?.contentOrNull
                    mcpJson.encodeToString(CompanyDashboardResponse.serializer(), desktopService.companyDashboardReadOnly(companyId))
                }
                "issue_list" -> {
                    val companyId = arguments["companyId"]?.jsonPrimitive?.contentOrNull
                    mcpJson.encodeToString(
                        ListSerializer(CompanyIssue.serializer()),
                        desktopService.companyDashboardReadOnly(companyId).issues
                    )
                }
                "durable_run_inspect" -> {
                    val runId = arguments["runId"]?.jsonPrimitive?.contentOrNull
                        ?: return mcpError(id, -32602, "runId is required")
                    durableRuntimeService?.inspectRun(runId)?.let {
                        mcpJson.encodeToString(DurableRunSnapshot.serializer(), it)
                    } ?: mcpJson.encodeToString(EvidenceBundle.serializer(), desktopService.evidenceForRun(runId))
                }
                "approval_queue" -> {
                    val companyId = arguments["companyId"]?.jsonPrimitive?.contentOrNull
                    val dashboard = desktopService.companyDashboardReadOnly(companyId)
                    mcpJson.encodeToString(ListSerializer(String.serializer()), dashboard.runtime.pendingApprovalRunIds)
                }
                "evidence_summary" -> {
                    val runId = arguments["runId"]?.jsonPrimitive?.contentOrNull
                    val filePath = arguments["path"]?.jsonPrimitive?.contentOrNull
                    when {
                        runId != null -> mcpJson.encodeToString(EvidenceBundle.serializer(), desktopService.evidenceForRun(runId))
                        filePath != null -> mcpJson.encodeToString(EvidenceBundle.serializer(), desktopService.evidenceForFile(filePath))
                        else -> return mcpError(id, -32602, "runId or path is required")
                    }
                }
                "verification_bundle" -> {
                    val issueId = arguments["issueId"]?.jsonPrimitive?.contentOrNull
                        ?: return mcpError(id, -32602, "issueId is required")
                    mcpJson.encodeToString(com.cotor.verification.VerificationBundle.serializer(), desktopService.verificationBundle(issueId))
                }
                "company_memory_snapshot" -> {
                    val companyId = arguments["companyId"]?.jsonPrimitive?.contentOrNull
                        ?: return mcpError(id, -32602, "companyId is required")
                    val issueId = arguments["issueId"]?.jsonPrimitive?.contentOrNull
                    val agentProfileId = arguments["agentProfileId"]?.jsonPrimitive?.contentOrNull
                    mcpJson.encodeToString(CompanyMemorySnapshotResponse.serializer(), desktopService.companyMemorySnapshot(companyId, issueId, agentProfileId))
                }
                "github_company_events" -> {
                    val companyId = arguments["companyId"]?.jsonPrimitive?.contentOrNull
                    mcpJson.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(com.cotor.providers.github.GitHubProviderEvent.serializer()),
                        desktopService.listGitHubEvents(companyId)
                    )
                }
                "runtime_projection" -> {
                    val issueId = arguments["issueId"]?.jsonPrimitive?.contentOrNull
                        ?: return mcpError(id, -32602, "issueId is required")
                    mcpJson.encodeToString(IssueRuntimeProjection.serializer(), desktopService.issueRuntimeProjection(issueId))
                }
                "company_runtime_start" -> {
                    val companyId = arguments["companyId"]?.jsonPrimitive?.contentOrNull
                        ?: return mcpError(id, -32602, "companyId is required")
                    mcpJson.encodeToString(CompanyRuntimeSnapshot.serializer(), desktopService.startCompanyRuntime(companyId))
                }
                "company_runtime_stop" -> {
                    val companyId = arguments["companyId"]?.jsonPrimitive?.contentOrNull
                        ?: return mcpError(id, -32602, "companyId is required")
                    mcpJson.encodeToString(CompanyRuntimeSnapshot.serializer(), desktopService.stopCompanyRuntime(companyId))
                }
                "company_review_qa" -> {
                    val queueItemId = arguments["queueItemId"]?.jsonPrimitive?.contentOrNull
                        ?: return mcpError(id, -32602, "queueItemId is required")
                    val verdict = arguments["verdict"]?.jsonPrimitive?.contentOrNull
                        ?: return mcpError(id, -32602, "verdict is required")
                    val feedback = arguments["feedback"]?.jsonPrimitive?.contentOrNull
                    mcpJson.encodeToString(ReviewQueueItem.serializer(), desktopService.submitQaReviewVerdict(queueItemId, verdict, feedback))
                }
                "company_review_ceo" -> {
                    val queueItemId = arguments["queueItemId"]?.jsonPrimitive?.contentOrNull
                        ?: return mcpError(id, -32602, "queueItemId is required")
                    val verdict = arguments["verdict"]?.jsonPrimitive?.contentOrNull
                        ?: return mcpError(id, -32602, "verdict is required")
                    val feedback = arguments["feedback"]?.jsonPrimitive?.contentOrNull
                    mcpJson.encodeToString(ReviewQueueItem.serializer(), desktopService.submitCeoReviewVerdict(queueItemId, verdict, feedback))
                }
                else -> return mcpError(id, -32601, "Unknown MCP tool: $toolName")
            }
            mcpResult(id, buildJsonObject {
                put("content", buildJsonArray { add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive(content)) }) })
            })
        }
        "resources/list" -> mcpResult(id, buildJsonObject {
            put(
                "resources",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("uri", JsonPrimitive("cotor://companies"))
                            put("name", JsonPrimitive("Companies"))
                            put("annotations", buildJsonObject { put("readOnlyHint", JsonPrimitive(true)) })
                        }
                    )
                    add(
                        buildJsonObject {
                            put("uri", JsonPrimitive("cotor://durable-runs"))
                            put("name", JsonPrimitive("Durable Runs"))
                            put("annotations", buildJsonObject { put("readOnlyHint", JsonPrimitive(true)) })
                        }
                    )
                }
            )
        })
        "resources/read" -> {
            val uri = params["uri"]?.jsonPrimitive?.contentOrNull
                ?: return mcpError(id, -32602, "uri is required")
            if (uri.isBlank()) {
                return mcpError(id, -32602, "uri must not be blank")
            }
            val content = when (uri) {
                "cotor://companies" -> mcpJson.encodeToString(
                    ListSerializer(Company.serializer()),
                    desktopService.companyDashboardReadOnly().companies
                )
                "cotor://durable-runs" -> mcpJson.encodeToString(
                    ListSerializer(DurableRunSnapshot.serializer()),
                    durableRuntimeService?.listRuns() ?: emptyList()
                )
                else -> return mcpError(id, -32602, "Unsupported resource: $uri")
            }
            mcpResult(id, buildJsonObject {
                put(
                    "contents",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("uri", JsonPrimitive(uri))
                                put("mimeType", JsonPrimitive("application/json"))
                                put("text", JsonPrimitive(content))
                            }
                        )
                    }
                )
            })
        }
        else -> mcpError(id, -32601, "Unsupported MCP method: $method")
    }
}

private fun mcpToolDescriptor(name: String, description: String, readOnly: Boolean): JsonElement = buildJsonObject {
    put("name", JsonPrimitive(name))
    put("description", JsonPrimitive(description))
    put("annotations", buildJsonObject { put("readOnlyHint", JsonPrimitive(readOnly)) })
    put("inputSchema", buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject { })
    })
}

private fun mcpResult(id: JsonElement, result: JsonElement): JsonElement = buildJsonObject {
    put("jsonrpc", JsonPrimitive("2.0"))
    put("id", id)
    put("result", result)
}

private fun mcpError(id: JsonElement, code: Int, message: String): JsonElement = buildJsonObject {
    put("jsonrpc", JsonPrimitive("2.0"))
    put("id", id)
    put(
        "error",
        buildJsonObject {
            put("code", JsonPrimitive(code))
            put("message", JsonPrimitive(message))
        }
    )
}

private fun toApiRunRecord(run: AgentRun): AgentRun =
    run.copy(
        output = run.output?.let { truncateForApi(it, RUN_OUTPUT_LIMIT) },
        error = run.error?.let { truncateForApi(it, RUN_ERROR_LIMIT) }
    )

private fun truncateForApi(value: String, maxChars: Int): String {
    if (value.length <= maxChars) return value
    val head = value.take(maxChars)
    val omitted = value.length - maxChars
    return buildString(head.length + 64) {
        append(head)
        append("\n\n... [truncated ")
        append(omitted)
        append(" chars]")
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
        call.application.environment.log.warn(
            "Desktop request {} {} failed with IllegalArgumentException: {}",
            call.request.local.method,
            call.request.path(),
            message
        )
        val status = if (message.contains("not found", ignoreCase = true)) {
            HttpStatusCode.NotFound
        } else {
            HttpStatusCode.BadRequest
        }
        call.respond(status, mapOf("error" to message))
    } catch (cause: IllegalStateException) {
        call.application.environment.log.warn(
            "Desktop request {} {} failed with IllegalStateException: {}",
            call.request.local.method,
            call.request.path(),
            cause.message ?: "Invalid state"
        )
        call.respond(HttpStatusCode.Conflict, mapOf("error" to (cause.message ?: "Invalid state")))
    } catch (cause: Throwable) {
        call.application.environment.log.error(
            "Desktop request {} {} failed with unhandled exception",
            call.request.local.method,
            call.request.path(),
            cause
        )
        call.respond(
            HttpStatusCode.InternalServerError,
            mapOf("error" to (cause.message ?: cause::class.simpleName ?: "Internal server error"))
        )
    }
}

private fun List<Map<String, Any?>>.toJsonArray(): JsonArray = buildJsonArray {
    forEach { add(it.toJsonElement()) }
}

private fun Map<String, Any?>.toJsonElement(): JsonElement = buildJsonObject {
    forEach { (key, value) -> put(key, value.toJsonElement()) }
}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Enum<*> -> JsonPrimitive(this.name)
    is Map<*, *> -> buildJsonObject {
        this@toJsonElement.forEach { (key, value) ->
            if (key != null) put(key.toString(), value.toJsonElement())
        }
    }
    is Iterable<*> -> buildJsonArray {
        this@toJsonElement.forEach { add(it.toJsonElement()) }
    }
    is Array<*> -> buildJsonArray {
        this@toJsonElement.forEach { add(it.toJsonElement()) }
    }
    else -> JsonPrimitive(toString())
}

@Serializable
private data class ReviewQueueVerdictRequest(
    val verdict: String,
    val feedback: String? = null
)

@Serializable
private data class IssueAssigneeUpdateRequest(
    val assigneeProfileId: String? = null
)
