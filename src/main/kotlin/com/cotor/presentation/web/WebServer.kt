package com.cotor.presentation.web

/**
 * File overview for EditorStagePayload.
 *
 * This file belongs to the web presentation layer for the browser-based editor and runtime surface.
 * It groups declarations around web server so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.app.*
import com.cotor.data.config.ConfigRepository
import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.orchestrator.PipelineOrchestrator
import com.cotor.model.*
import com.cotor.presentation.cli.CliHelpLanguage
import com.cotor.runtime.durable.DurableRuntimeFlags
import com.cotor.runtime.durable.DurableRuntimeService
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.awt.Desktop
import java.net.URI
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

@Serializable
data class EditorStagePayload(
    val id: String,
    val type: String = "EXECUTION",
    val agent: String? = null,
    val input: String? = null,
    val dependencies: List<String> = emptyList(),
    val condition: String? = null,
    val onTrue: String? = null,
    val onFalse: String? = null,
    val loopTarget: String? = null,
    val loopMaxIterations: Int? = null
)

@Serializable
data class EditorPipelineRequest(
    val name: String,
    val description: String = "",
    val executionMode: String = "SEQUENTIAL",
    val stages: List<EditorStagePayload> = emptyList(),
    val agents: List<String> = emptyList(),
    val path: String? = null
)

@Serializable
data class EditorPipelineSummary(
    val name: String,
    val description: String,
    val executionMode: String,
    val stageCount: Int,
    val agents: List<String>,
    val tags: List<String>,
    val path: String,
    val updatedAt: Long
)

@Serializable
data class EditorPipelineDetail(
    val name: String,
    val description: String,
    val executionMode: String,
    val stages: List<EditorStagePayload>,
    val agents: List<String>,
    val path: String
)

@Serializable
data class EditorTemplatePayload(
    val id: String,
    val name: String,
    val description: String,
    val pipeline: EditorPipelineRequest
)

@Serializable
data class SaveResponse(
    val ok: Boolean,
    val path: String
)

@Serializable
data class ConfigResponse(
    val readOnly: Boolean
)

@Serializable
data class RunResponse(
    val name: String,
    val executionMode: String,
    val totalAgents: Int,
    val successCount: Int,
    val failureCount: Int,
    val totalDuration: Long,
    val results: List<AgentResultPayload>,
    val timeline: List<TimelineEntryPayload>
)

@Serializable
data class AgentResultPayload(
    val agentName: String,
    val isSuccess: Boolean,
    val duration: Long,
    val output: String?,
    val error: String?
)

@Serializable
data class TimelineEntryPayload(
    val stageId: String,
    val state: String,
    val durationMs: Long,
    val message: String?,
    val outputPreview: String?
)

class WebServer : KoinComponent {
    private val configRepository: ConfigRepository by inject()
    private val agentRegistry: AgentRegistry by inject()
    private val orchestrator: PipelineOrchestrator by inject()
    private val desktopService: DesktopAppService by inject()
    private val durableRuntimeService: DurableRuntimeService by inject()

    private val editorDir = Path(".cotor/web")
    private val pluginClassMap = mapOf(
        "claude" to "com.cotor.data.plugin.ClaudePlugin",
        "codex" to "com.cotor.data.plugin.CodexPlugin",
        "gemini" to "com.cotor.data.plugin.GeminiPlugin",
        "copilot" to "com.cotor.data.plugin.CopilotPlugin",
        "cursor" to "com.cotor.data.plugin.CursorPlugin",
        "opencode" to "com.cotor.data.plugin.OpenCodePlugin",
        "echo" to "com.cotor.data.plugin.EchoPlugin"
    )

    fun start(port: Int = 8080, openBrowser: Boolean = false, readOnly: Boolean = false, initialPath: String = "/") {
        ensureEditorDir()

        if (openBrowser) {
            thread(name = "cotor-web-open") {
                Thread.sleep(600)
                openInBrowser("http://localhost:$port$initialPath")
            }
        }

        embeddedServer(Netty, port = port) {
            cotorWebModule(
                configRepository = configRepository,
                agentRegistry = agentRegistry,
                orchestrator = orchestrator,
                desktopService = desktopService,
                editorDir = editorDir,
                readOnly = readOnly,
                buildTemplates = ::buildTemplates,
                listSavedPipelines = ::listSavedPipelines,
                loadPipelineDetail = ::loadPipelineDetail,
                savePipeline = ::savePipeline,
                buildTimelinePayload = ::buildTimelinePayload,
                durableRuntimeService = durableRuntimeService
            )
        }.start(wait = true)
    }

    private fun ensureEditorDir() {
        if (!editorDir.exists()) {
            editorDir.createDirectories()
        }
    }

    private suspend fun savePipeline(request: EditorPipelineRequest): java.nio.file.Path {
        val safeName = sanitizeName(request.name)
        val targetPath = request.path?.let { Path(it) } ?: editorDir.resolve("$safeName.yaml")

        val agentNames = if (request.agents.isNotEmpty()) request.agents else request.stages.mapNotNull { it.agent }
        val agentConfigs = agentNames.distinct().map { agentName ->
            AgentConfig(
                name = agentName,
                pluginClass = pluginClassMap[agentName.lowercase()] ?: "com.cotor.data.plugin.EchoPlugin",
                timeout = 60000
            )
        }

        val pipelineStages = request.stages.map { stagePayload ->
            val agentName = stagePayload.agent ?: agentConfigs.firstOrNull()?.name ?: "agent-${stagePayload.id}"
            val stageType = runCatching { StageType.valueOf(stagePayload.type.uppercase()) }.getOrElse { StageType.EXECUTION }

            PipelineStage(
                id = stagePayload.id,
                type = stageType,
                agent = AgentReference(agentName),
                input = stagePayload.input,
                dependencies = stagePayload.dependencies
            )
        }

        val pipeline = Pipeline(
            name = request.name,
            description = request.description,
            executionMode = ExecutionMode.valueOf(request.executionMode.uppercase()),
            stages = pipelineStages
        )

        val config = CotorConfig(
            version = "1.0",
            agents = agentConfigs,
            pipelines = listOf(pipeline),
            security = SecurityConfig(
                useWhitelist = true,
                allowedExecutables = agentConfigs.map { it.name }.toSet(),
                allowedDirectories = listOf(Path("/usr/local/bin"), Path("/opt/homebrew/bin"))
            )
        )

        targetPath.parent?.createDirectories()
        configRepository.saveConfig(config, targetPath)
        return targetPath
    }

    private suspend fun listSavedPipelines(): List<EditorPipelineSummary> {
        ensureEditorDir()
        val files = editorDir.toFile()
            .listFiles { f -> f.extension in listOf("yaml", "yml") }
            ?: return emptyList()

        val summaries = mutableListOf<EditorPipelineSummary>()
        for (file in files) {
            try {
                val config = configRepository.loadConfig(file.toPath())
                val pipeline = config.pipelines.firstOrNull() ?: continue
                summaries += EditorPipelineSummary(
                    name = pipeline.name,
                    description = pipeline.description.ifBlank { "설명 없음" },
                    executionMode = pipeline.executionMode.name,
                    stageCount = pipeline.stages.size,
                    agents = pipeline.stages.mapNotNull { it.agent?.name }.distinct(),
                    tags = config.agents.flatMap { it.tags }.distinct(),
                    path = file.path,
                    updatedAt = file.lastModified()
                )
            } catch (_: Exception) {
                continue
            }
        }
        return summaries
    }

    private suspend fun loadPipelineDetail(name: String): EditorPipelineDetail? {
        val safeName = sanitizeName(name)
        val file = editorDir.resolve("$safeName.yaml")
        if (!file.exists()) return null

        val config = configRepository.loadConfig(file)
        val pipeline = config.pipelines.firstOrNull() ?: return null

        return EditorPipelineDetail(
            name = pipeline.name,
            description = pipeline.description,
            executionMode = pipeline.executionMode.name,
            stages = pipeline.stages.map { stage ->
                EditorStagePayload(
                    id = stage.id,
                    type = stage.type.name,
                    agent = stage.agent?.name,
                    input = stage.input,
                    dependencies = stage.dependencies
                )
            },
            agents = config.agents.map { it.name },
            path = file.toString()
        )
    }

    private fun buildTemplates(): List<EditorTemplatePayload> {
        val templates = listOf(
            EditorTemplatePayload(
                id = "compare",
                name = "다중 에이전트 비교",
                description = "같은 문제를 여러 에이전트가 병렬로 풀고 결과를 비교합니다.",
                pipeline = EditorPipelineRequest(
                    name = "compare-solutions",
                    description = "Compare AI solutions for the same task",
                    executionMode = "PARALLEL",
                    agents = listOf("claude", "gemini"),
                    stages = listOf(
                        EditorStagePayload(id = "claude-solution", agent = "claude", input = "YOUR_PROMPT_HERE"),
                        EditorStagePayload(id = "gemini-solution", agent = "gemini", input = "YOUR_PROMPT_HERE")
                    )
                )
            ),
            EditorTemplatePayload(
                id = "sequential-review",
                name = "생성 → 리뷰 체인",
                description = "생성 후 다른 에이전트가 리뷰/개선을 수행합니다.",
                pipeline = EditorPipelineRequest(
                    name = "review-chain",
                    description = "Generator and reviewer chain",
                    executionMode = "SEQUENTIAL",
                    agents = listOf("claude", "gemini"),
                    stages = listOf(
                        EditorStagePayload(id = "generate", agent = "claude", input = "기능 구현 초안을 작성해줘"),
                        EditorStagePayload(id = "review", agent = "gemini", input = "이전 출력을 개선/리뷰해줘")
                    )
                )
            ),
            EditorTemplatePayload(
                id = "decision-gate",
                name = "결정 게이트",
                description = "특정 조건을 만족하면 바로 성공, 아니면 수정 단계로 이동.",
                pipeline = EditorPipelineRequest(
                    name = "decision-gate",
                    description = "Decide go/stop and reroute",
                    executionMode = "SEQUENTIAL",
                    agents = listOf("claude"),
                    stages = listOf(
                        EditorStagePayload(id = "draft", agent = "claude", input = "요구사항 초안을 작성"),
                        EditorStagePayload(id = "decide", type = "EXECUTION", agent = "claude", input = "조건을 만족하는지 판단 후 메시지 작성")
                    )
                )
            ),
            EditorTemplatePayload(
                id = "loop-self-heal",
                name = "루프 자기치유",
                description = "문제가 해결될 때까지 최대 N회 재시도/보강합니다.",
                pipeline = EditorPipelineRequest(
                    name = "self-heal-loop",
                    description = "Loop until condition met",
                    executionMode = "SEQUENTIAL",
                    agents = listOf("claude"),
                    stages = listOf(
                        EditorStagePayload(id = "attempt-1", agent = "claude", input = "문제를 해결하고 결과를 보고해줘"),
                        EditorStagePayload(id = "attempt-2", agent = "claude", input = "이전 결과를 개선하거나 오류를 고쳐줘")
                    )
                )
            ),
            EditorTemplatePayload(
                id = "fanout-dag",
                name = "DAG 팬아웃/머지",
                description = "분기 실행 후 머지 작업을 수행하는 DAG 예제.",
                pipeline = EditorPipelineRequest(
                    name = "fanout-merge",
                    description = "Fan-out fan-in DAG",
                    executionMode = "DAG",
                    agents = listOf("claude", "gemini"),
                    stages = listOf(
                        EditorStagePayload(id = "seed", agent = "claude", input = "공통 입력을 분석"),
                        EditorStagePayload(id = "branch-a", agent = "claude", input = "접근 A 제안", dependencies = listOf("seed")),
                        EditorStagePayload(id = "branch-b", agent = "gemini", input = "접근 B 제안", dependencies = listOf("seed")),
                        EditorStagePayload(id = "merge", agent = "claude", input = "두 접근을 합쳐 최종안을 작성", dependencies = listOf("branch-a", "branch-b"))
                    )
                )
            )
        )
        return templates
    }

    private fun sanitizeName(name: String): String =
        name.trim().lowercase().replace("[^a-z0-9-_]".toRegex(), "-")

    private fun buildTimelinePayload(
        pipeline: Pipeline,
        result: AggregatedResult
    ): List<TimelineEntryPayload> {
        return pipeline.stages.mapIndexed { index, stage ->
            // Prefer positional mapping (execution order) and fall back to agent name matching.
            val stageResult = result.results.getOrNull(index)
                ?: result.results.lastOrNull { it.agentName == stage.agent?.name }
            val state = when {
                stageResult == null -> "FAILED"
                stageResult.isSuccess -> "COMPLETED"
                else -> "FAILED"
            }
            val message = stageResult?.error ?: stageResult?.output
            val preview = stageResult?.output?.take(200)

            TimelineEntryPayload(
                stageId = stage.id,
                state = state,
                durationMs = (stageResult?.duration ?: 0),
                message = message,
                outputPreview = preview
            )
        }
    }
}

internal fun Application.cotorWebModule(
    configRepository: ConfigRepository,
    agentRegistry: AgentRegistry,
    orchestrator: PipelineOrchestrator,
    desktopService: DesktopAppService,
    editorDir: java.nio.file.Path,
    readOnly: Boolean,
    buildTemplates: () -> List<EditorTemplatePayload>,
    listSavedPipelines: suspend () -> List<EditorPipelineSummary>,
    loadPipelineDetail: suspend (String) -> EditorPipelineDetail?,
    savePipeline: suspend (EditorPipelineRequest) -> java.nio.file.Path,
    buildTimelinePayload: (Pipeline, AggregatedResult) -> List<TimelineEntryPayload>,
    durableRuntimeService: DurableRuntimeService? = null
) {
    install(ContentNegotiation) {
        json()
    }
    install(CORS) {
        anyHost()
    }

    routing {
        get("/") {
            call.respondText(landingHtml, ContentType.Text.Html)
        }

        get("/editor") {
            call.respondText(editorHtml, ContentType.Text.Html)
        }

        get("/company") {
            call.respondText(companyHtml, ContentType.Text.Html)
        }

        get("/help") {
            val language = CliHelpLanguage.resolve(call.request.queryParameters["lang"])
            call.respondText(helpHtml(language), ContentType.Text.Html)
        }

        get("/api/help-guide") {
            val language = CliHelpLanguage.resolve(call.request.queryParameters["lang"])
            call.respond(HelpGuideContent.guide(language))
        }

        get("/api/runtime/runs") {
            call.respond(durableRuntimeService?.listRuns().orEmpty())
        }

        get("/api/runtime/runs/{runId}") {
            val runId = call.parameters["runId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "runId is required"))
            val snapshot = durableRuntimeService?.inspectRun(runId)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Durable run not found: $runId"))
            call.respond(snapshot)
        }

        get("/api/runtime/policy/decisions") {
            val runId = call.request.queryParameters["runId"]
            val issueId = call.request.queryParameters["issueId"]
            call.respond(desktopService.policyDecisions(runId = runId, issueId = issueId))
        }

        get("/api/runtime/evidence/runs/{runId}") {
            val runId = call.parameters["runId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "runId is required"))
            call.respond(desktopService.evidenceForRun(runId))
        }

        get("/api/runtime/evidence/files") {
            val path = call.request.queryParameters["path"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "path is required"))
            call.respond(desktopService.evidenceForFile(path))
        }

        get("/api/runtime/github/pull-requests") {
            val companyId = call.request.queryParameters["companyId"]
            call.respond(desktopService.listGitHubPullRequests(companyId))
        }

        get("/api/runtime/github/events") {
            val companyId = call.request.queryParameters["companyId"]
            call.respond(desktopService.listGitHubEvents(companyId))
        }

        get("/api/runtime/github/pull-requests/{pullRequestNumber}") {
            val pullRequestNumber = call.parameters["pullRequestNumber"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "pullRequestNumber is required"))
            val snapshot = desktopService.inspectGitHubPullRequest(pullRequestNumber)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Pull request not found: $pullRequestNumber"))
            call.respond(snapshot)
        }

        get("/api/runtime/knowledge/issues/{issueId}") {
            val issueId = call.parameters["issueId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
            call.respond(desktopService.issueKnowledge(issueId))
        }

        get("/api/runtime/verification/issues/{issueId}") {
            val issueId = call.parameters["issueId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
            call.respond(desktopService.verificationBundle(issueId))
        }

        get("/api/runtime/issues/{issueId}/projection") {
            val issueId = call.parameters["issueId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "issueId is required"))
            call.respond(desktopService.issueRuntimeProjection(issueId))
        }

        get("/api/editor/config") {
            call.respond(ConfigResponse(readOnly = readOnly))
        }

        get("/api/editor/templates") {
            call.respond(buildTemplates())
        }

        get("/api/editor/pipelines") {
            call.respond(listSavedPipelines())
        }

        get("/api/editor/pipelines/{name}") {
            val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val detail = loadPipelineDetail(name)
            if (detail == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Pipeline not found"))
            } else {
                call.respond(detail)
            }
        }

        post("/api/editor/save") {
            if (readOnly) {
                return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Read-only mode"))
            }
            val request = call.receive<EditorPipelineRequest>()
            if (request.name.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Pipeline name is required"))
            }
            if (request.stages.isEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "At least one stage is required"))
            }

            val path = savePipeline(request)
            call.respond(SaveResponse(ok = true, path = path.toString()))
        }

        post("/api/editor/run") {
            if (readOnly) {
                return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Read-only mode"))
            }
            val request = call.receive<EditorPipelineRequest>()
            val configPath = request.path?.let { Path(it) }
                ?: editorDir.resolve("${request.name.trim().lowercase().replace("[^a-z0-9-_]".toRegex(), "-")}.yaml")
            if (!configPath.exists()) {
                return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Pipeline not saved yet"))
            }

            try {
                val config = configRepository.loadConfig(configPath)
                val pipeline = config.pipelines.firstOrNull { it.name == request.name }
                    ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Pipeline not found in config"))

                config.agents.forEach { agentRegistry.registerAgent(it) }
                val pipelineContext = PipelineContext(
                    pipelineId = UUID.randomUUID().toString(),
                    pipelineName = pipeline.name,
                    totalStages = pipeline.stages.size
                ).also { context ->
                    context.metadata["configPath"] = configPath.toString()
                    if (DurableRuntimeFlags.isEnabled()) {
                        DurableRuntimeFlags.enable(context)
                    }
                }
                val result = orchestrator.executePipeline(pipeline, context = pipelineContext)
                val timeline = buildTimelinePayload(pipeline, result)
                val agentResults = result.results.map {
                    AgentResultPayload(
                        agentName = it.agentName,
                        isSuccess = it.isSuccess,
                        duration = it.duration,
                        output = it.output,
                        error = it.error
                    )
                }

                val response = RunResponse(
                    name = pipeline.name,
                    executionMode = pipeline.executionMode.name,
                    totalAgents = result.totalAgents,
                    successCount = result.successCount,
                    failureCount = result.failureCount,
                    totalDuration = result.totalDuration,
                    results = agentResults,
                    timeline = timeline
                )
                call.respond(response)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            }
        }

        route("/api/company") {
            get("/dashboard") {
                call.respond(desktopService.companyDashboardReadOnly())
            }

            get("/issues/{issueId}/execution-details") {
                val issueId = call.parameters["issueId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                call.respond(desktopService.issueExecutionDetails(issueId))
            }

            route("/companies") {
                get {
                    call.respond(desktopService.listCompanies())
                }

                post {
                    val request = call.receive<CreateCompanyRequest>()
                    call.respond(
                        desktopService.createCompany(
                            name = request.name,
                            rootPath = request.rootPath,
                            defaultBaseBranch = request.defaultBaseBranch,
                            autonomyEnabled = request.autonomyEnabled,
                            dailyBudgetCents = request.dailyBudgetCents,
                            monthlyBudgetCents = request.monthlyBudgetCents
                        )
                    )
                }

                get("/{companyId}") {
                    val companyId = call.parameters["companyId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val company = desktopService.getCompany(companyId)
                        ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Company not found"))
                    call.respond(company)
                }

                get("/{companyId}/dashboard") {
                    val companyId = call.parameters["companyId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    call.respond(desktopService.companyDashboardReadOnly(companyId))
                }

                patch("/{companyId}") {
                    val companyId = call.parameters["companyId"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
                    val request = call.receive<UpdateCompanyRequest>()
                    call.respond(
                        desktopService.updateCompany(
                            companyId = companyId,
                            name = request.name,
                            defaultBaseBranch = request.defaultBaseBranch,
                            autonomyEnabled = request.autonomyEnabled,
                            backendKind = request.backendKind,
                            dailyBudgetCents = request.dailyBudgetCents,
                            monthlyBudgetCents = request.monthlyBudgetCents
                        )
                    )
                }

                delete("/{companyId}") {
                    val companyId = call.parameters["companyId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    call.respond(desktopService.deleteCompany(companyId))
                }

                get("/{companyId}/agents") {
                    val companyId = call.parameters["companyId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    call.respond(desktopService.listCompanyAgentDefinitions(companyId))
                }

                patch("/{companyId}/agents/batch") {
                    val companyId = call.parameters["companyId"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
                    val request = call.receive<BatchUpdateCompanyAgentDefinitionsRequest>()
                    call.respond(
                        desktopService.batchUpdateCompanyAgentDefinitions(
                            companyId = companyId,
                            agentIds = request.agentIds,
                            agentCli = request.agentCli,
                            model = request.model,
                            specialties = request.specialties,
                            enabled = request.enabled
                        )
                    )
                }

                get("/{companyId}/goals") {
                    val companyId = call.parameters["companyId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    call.respond(desktopService.listGoals().filter { it.companyId == companyId })
                }

                post("/{companyId}/goals") {
                    val companyId = call.parameters["companyId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val request = call.receive<CreateGoalRequest>()
                    call.respond(
                        desktopService.createGoal(
                            companyId = companyId,
                            title = request.title,
                            description = request.description,
                            successMetrics = request.successMetrics,
                            autonomyEnabled = request.autonomyEnabled
                        )
                    )
                }

                get("/{companyId}/issues") {
                    val companyId = call.parameters["companyId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val goalId = call.request.queryParameters["goalId"]
                    call.respond(desktopService.listIssues(goalId, companyId))
                }

                get("/{companyId}/issues/{issueId}") {
                    val companyId = call.parameters["companyId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val issueId = call.parameters["issueId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val issue = desktopService.getIssueProjected(issueId)?.takeIf { it.companyId == companyId }
                        ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Issue not found"))
                    call.respond(issue)
                }

                get("/{companyId}/issues/{issueId}/execution-details") {
                    val issueId = call.parameters["issueId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    call.respond(desktopService.issueExecutionDetails(issueId))
                }

                post("/{companyId}/issues") {
                    val companyId = call.parameters["companyId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val request = call.receive<CreateIssueRequest>()
                    call.respond(
                        desktopService.createIssue(
                            companyId = companyId,
                            goalId = request.goalId,
                            title = request.title,
                            description = request.description,
                            priority = request.priority,
                            kind = request.kind
                        )
                    )
                }

                get("/{companyId}/review-queue") {
                    val companyId = call.parameters["companyId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    call.respond(desktopService.listReviewQueue(companyId))
                }

                get("/{companyId}/runtime") {
                    val companyId = call.parameters["companyId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    call.respond(desktopService.runtimeStatus(companyId))
                }

                post("/{companyId}/runtime/start") {
                    val companyId = call.parameters["companyId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    call.respond(desktopService.startCompanyRuntime(companyId))
                }

                post("/{companyId}/runtime/stop") {
                    val companyId = call.parameters["companyId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    call.respond(desktopService.stopCompanyRuntime(companyId))
                }
            }

            post("/issues/{issueId}/delegate") {
                val issueId = call.parameters["issueId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                call.respond(desktopService.delegateIssue(issueId))
            }

            post("/issues/{issueId}/run") {
                val issueId = call.parameters["issueId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                call.respond(desktopService.runIssue(issueId))
            }

            post("/review/{itemId}/merge") {
                val itemId = call.parameters["itemId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                call.respond(desktopService.mergeReviewQueueItem(itemId))
            }
        }
    }
}

internal fun Application.cotorWebModule(
    configRepository: ConfigRepository,
    agentRegistry: AgentRegistry,
    orchestrator: PipelineOrchestrator,
    desktopService: DesktopAppService,
    editorDir: java.nio.file.Path,
    readOnly: Boolean,
    buildTemplates: () -> List<EditorTemplatePayload>,
    listSavedPipelines: suspend () -> List<EditorPipelineSummary>,
    loadPipelineDetail: suspend (String) -> EditorPipelineDetail?,
    savePipeline: suspend (EditorPipelineRequest) -> java.nio.file.Path,
    buildTimelinePayload: (Pipeline, AggregatedResult) -> List<TimelineEntryPayload>
) {
    cotorWebModule(
        configRepository = configRepository,
        agentRegistry = agentRegistry,
        orchestrator = orchestrator,
        desktopService = desktopService,
        editorDir = editorDir,
        readOnly = readOnly,
        buildTemplates = buildTemplates,
        listSavedPipelines = listSavedPipelines,
        loadPipelineDetail = loadPipelineDetail,
        savePipeline = savePipeline,
        buildTimelinePayload = buildTimelinePayload,
        durableRuntimeService = null
    )
}

private fun openInBrowser(url: String) {
    try {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(url))
        } else {
            Runtime.getRuntime().exec(arrayOf("open", url))
        }
    } catch (_: Exception) {
        // Ignore failures
    }
}

private val landingHtml = """
<!doctype html>
<html lang="ko">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width,initial-scale=1.0">
  <title>Cotor | 멀티 에이전트 워크플로 오케스트레이션</title>
  <script src="https://mcp.figma.com/mcp/html-to-design/capture.js" async></script>
  <style>
    :root {
      --bg: #060912;
      --panel: #0f172a;
      --border: #1e293b;
      --text: #e2e8f0;
      --muted: #94a3b8;
      --primary: #8b5cf6;
      --accent: #22d3ee;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      font-family: Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      color: var(--text);
      background: radial-gradient(circle at 20% 0%, rgba(139,92,246,.16), transparent 30%),
                  radial-gradient(circle at 90% 10%, rgba(34,211,238,.18), transparent 30%),
                  var(--bg);
    }
    .wrap { max-width: 1100px; margin: 0 auto; padding: 28px 20px 64px; }
    .hero {
      border: 1px solid var(--border);
      border-radius: 24px;
      padding: 28px;
      background: linear-gradient(140deg, rgba(15,23,42,.95), rgba(8,47,73,.75));
      box-shadow: 0 22px 70px rgba(2,6,23,.45);
    }
    h1 { margin: 0; font-size: clamp(2rem, 5vw, 3rem); line-height: 1.1; letter-spacing: -0.03em; }
    .subtitle { margin: 12px 0 0; color: var(--muted); max-width: 680px; line-height: 1.6; }
    .actions { display: flex; gap: 12px; margin-top: 20px; flex-wrap: wrap; }
    .btn { display: inline-flex; align-items: center; justify-content: center; border-radius: 12px; padding: 11px 16px; text-decoration: none; font-weight: 700; border: 1px solid var(--border); color: var(--text); }
    .btn.primary { background: linear-gradient(135deg, #8b5cf6, #6d28d9); border: 0; }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); gap: 12px; margin-top: 22px; }
    .card { border: 1px solid var(--border); border-radius: 16px; padding: 16px; background: rgba(15,23,42,.85); }
    .card h3 { margin: 0 0 8px; font-size: 1rem; }
    .card p { margin: 0; color: var(--muted); line-height: 1.55; font-size: .95rem; }
    .section-title { margin: 28px 0 10px; font-size: 1.2rem; }
    ol { margin: 0; padding-left: 20px; color: var(--muted); line-height: 1.75; }
    .footer-note { margin-top: 20px; color: var(--muted); font-size: .9rem; }
  </style>
</head>
<body>
  <div class="wrap">
    <section class="hero">
      <h1>Cotor 설명 페이지</h1>
      <p class="subtitle">
        Cotor는 멀티 에이전트 워크플로를 설계/저장/실행할 수 있는 오케스트레이션 도구입니다.
        CLI 자동화와 웹 에디터를 함께 제공해, 초안부터 운영까지 한 경로에서 진행할 수 있습니다.
      </p>
      <div class="actions">
        <a class="btn primary" href="/editor">웹 에디터 시작하기</a>
        <a class="btn" href="/company">회사 콘솔</a>
        <a class="btn" href="https://github.com/heodongun/cotor" target="_blank" rel="noreferrer">GitHub 보기</a>
      </div>
    </section>

    <h2 class="section-title">핵심 워크플로</h2>
    <div class="grid">
      <article class="card">
        <h3>1) 설계</h3>
        <p>템플릿 또는 드래그·드롭 빌더로 파이프라인 스테이지를 빠르게 정의합니다.</p>
      </article>
      <article class="card">
        <h3>2) 저장</h3>
        <p>파이프라인 YAML을 생성하고 버전 관리 가능한 형태로 누적합니다.</p>
      </article>
      <article class="card">
        <h3>3) 실행</h3>
        <p>실행 모드(Sequential/Parallel/DAG)와 에이전트 조합으로 자동화를 수행합니다.</p>
      </article>
      <article class="card">
        <h3>4) 점검</h3>
        <p>타임라인과 단계별 결과를 보고 실패 지점을 파악해 개선합니다.</p>
      </article>
    </div>

    <h2 class="section-title">처음 사용하는 경우</h2>
    <ol>
      <li><code>cotor web --open</code> 실행 후 이 페이지에서 에디터로 이동하세요.</li>
      <li>회사 상태/JSON 콘솔은 <code>/company</code> 에서 확인할 수 있습니다.</li>
      <li>파이프라인 이름과 스테이지를 구성하고 저장합니다.</li>
      <li>실행 후 결과 패널에서 성공/실패와 출력 미리보기를 확인하세요.</li>
    </ol>
    <p class="footer-note">로컬 기본 경로: 소개 페이지 <code>/</code>, 에디터 <code>/editor</code>, 회사 콘솔 <code>/company</code></p>
  </div>
</body>
</html>
""".trimIndent()

private val companyHtml = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Cotor Company Console</title>
  <style>
    body { font-family: ui-sans-serif, system-ui, sans-serif; background: #f3f6fb; color: #172033; margin: 0; padding: 24px; }
    .card { background: #fff; border: 1px solid #dbe3ef; border-radius: 14px; padding: 16px; margin-bottom: 16px; }
    button { background: #2563eb; color: white; border: 0; border-radius: 10px; padding: 10px 14px; cursor: pointer; }
    pre { background: #0f172a; color: #dbeafe; padding: 12px; border-radius: 12px; overflow: auto; }
    code { background: #e7eef8; padding: 2px 6px; border-radius: 6px; }
  </style>
</head>
<body>
  <h1>Cotor Company Console</h1>
  <div class="card">
    <p>This page exposes company APIs from the same service layer used by the desktop app and CLI.</p>
    <button onclick="loadDashboard()">Load Dashboard</button>
  </div>
  <div class="card">
    <strong>Available endpoints</strong>
    <pre>/api/company/dashboard
/api/company/companies
/api/company/companies/{companyId}
/api/company/companies/{companyId}/dashboard
/api/company/companies/{companyId}/agents
/api/company/companies/{companyId}/agents/batch
/api/company/companies/{companyId}/goals
/api/company/companies/{companyId}/issues
/api/company/companies/{companyId}/review-queue
/api/company/companies/{companyId}/runtime
/api/company/issues/{issueId}/delegate
/api/company/issues/{issueId}/run
/api/company/review/{itemId}/merge</pre>
  </div>
  <div class="card">
    <pre id="output">Press "Load Dashboard" to fetch /api/company/dashboard</pre>
  </div>
  <script>
    async function loadDashboard() {
      const response = await fetch('/api/company/dashboard');
      const data = await response.json();
      document.getElementById('output').textContent = JSON.stringify(data, null, 2);
    }
  </script>
</body>
</html>
""".trimIndent()

private fun helpHtml(language: CliHelpLanguage): String {
    val guide = HelpGuideContent.guide(language)
    val htmlLang = if (language == CliHelpLanguage.KOREAN) "ko" else "en"
    val quickStart = guide.quickStart.joinToString("\n") { item ->
        "<div class=\"quick-item\"><code>${item.command}</code><p>${item.description}</p></div>"
    }
    val sections = guide.sections.joinToString("\n") { section ->
        val items = section.items.joinToString("\n") { item ->
            "<li><code>${item.command}</code><span>${item.description}</span></li>"
        }
        """
        <section class="help-card">
          <h2>${section.title}</h2>
          <p>${section.summary}</p>
          <ul>$items</ul>
        </section>
        """.trimIndent()
    }
    val topics = guide.topics.joinToString("\n") { topic ->
        """
        <article class="topic-card">
          <div class="topic-command">${topic.command}</div>
          <strong>${topic.title}</strong>
          <p>${topic.description}</p>
        </article>
        """.trimIndent()
    }
    return """
    <!doctype html>
    <html lang="$htmlLang">
    <head>
      <meta charset="utf-8" />
      <meta name="viewport" content="width=device-width, initial-scale=1" />
      <title>${guide.title}</title>
      <style>
        body { font-family: ui-sans-serif, system-ui, sans-serif; background: #f3f6fb; color: #172033; margin: 0; padding: 24px; }
        .shell { max-width: 1120px; margin: 0 auto; display: grid; gap: 20px; }
        .hero, .help-card, .topic-card { background: #fff; border: 1px solid #dbe3ef; border-radius: 16px; padding: 20px; box-shadow: 0 10px 30px rgba(15,23,42,.06); }
        .hero h1 { margin: 0 0 8px; font-size: 2rem; }
        .hero p { margin: 0; color: #52627a; }
        .quick-grid, .topic-grid { display: grid; gap: 12px; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); }
        .quick-item { background: #f8fbff; border: 1px solid #e1e9f4; border-radius: 12px; padding: 14px; }
        .quick-item p, .topic-card p, .help-card p { color: #52627a; }
        code { background: #eef4fb; color: #0f172a; padding: 3px 8px; border-radius: 8px; font-family: ui-monospace, SFMono-Regular, monospace; }
        ul { list-style: none; padding: 0; margin: 14px 0 0; display: grid; gap: 10px; }
        li { display: grid; gap: 6px; padding: 12px 0; border-top: 1px solid #eef2f7; }
        li:first-child { border-top: 0; padding-top: 0; }
        .topic-command { font-size: .82rem; color: #6d28d9; font-weight: 700; margin-bottom: 8px; }
        .footer-note { color: #52627a; font-size: .95rem; }
      </style>
    </head>
    <body>
      <div class="shell">
        <section class="hero">
          <h1>${guide.title}</h1>
          <p>${guide.subtitle}</p>
        </section>
        <section class="help-card">
          <h2>${if (language == CliHelpLanguage.KOREAN) "빠른 시작" else "Quick Start"}</h2>
          <div class="quick-grid">
            $quickStart
          </div>
        </section>
        $sections
        <section class="help-card">
          <h2>${if (language == CliHelpLanguage.KOREAN) "주제별 도움말" else "Topic Guides"}</h2>
          <div class="topic-grid">
            $topics
          </div>
        </section>
        <section class="help-card">
          <h2>${if (language == CliHelpLanguage.KOREAN) "AI 사용 안내" else "AI Usage Guide"}</h2>
          <p>${guide.aiNarrative}</p>
        </section>
        <p class="footer-note">${guide.footer}</p>
      </div>
    </body>
    </html>
    """.trimIndent()
}

// Lightweight HTML (single file) to keep the web UI self contained.
private val editorHtml = """
<!doctype html>
<html lang="ko">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width,initial-scale=1.0">
  <title>Cotor Flow Studio</title>
  <script src="https://mcp.figma.com/mcp/html-to-design/capture.js" async></script>
  <style>
    :root {
      --bg: #0b1224;
      --panel: #0f172a;
      --card: #111827;
      --border: #1f2937;
      --text: #e5e7eb;
      --muted: #94a3b8;
      --primary: #7c3aed;
      --accent: #22d3ee;
      --success: #10b981;
      --error: #ef4444;
      --shadow: 0 20px 70px rgba(0,0,0,0.35);
    }
    * { box-sizing: border-box; }
    body {
      margin: 0; background: radial-gradient(circle at 20% 20%, rgba(124,58,237,0.12), transparent 25%), radial-gradient(circle at 80% 0%, rgba(34,211,238,0.14), transparent 25%), var(--bg);
      color: var(--text); font-family: 'Inter', system-ui, -apple-system, sans-serif;
      min-height: 100vh; padding: 28px 16px 60px;
    }
    .page { max-width: 1280px; margin: 0 auto; }
    .hero { background: linear-gradient(135deg, #111827, #0f172a 60%, rgba(124,58,237,0.2)); border: 1px solid var(--border); border-radius: 22px; padding: 24px; box-shadow: var(--shadow); display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
    .hero h1 { margin: 0; font-size: clamp(1.8rem, 4vw, 2.4rem); letter-spacing: -0.02em; }
    .hero p { margin: 6px 0 0; color: var(--muted); max-width: 720px; }
    .hero-actions { margin-left: auto; display: flex; gap: 10px; flex-wrap: wrap; }
    .btn { border: 1px solid var(--border); color: var(--text); background: #0f172a; border-radius: 10px; padding: 10px 14px; font-weight: 600; cursor: pointer; transition: all 0.15s ease; }
    .btn.primary { background: linear-gradient(135deg, #8b5cf6, #6d28d9); border: none; color: white; box-shadow: 0 10px 25px rgba(124,58,237,0.35); }
    .btn:hover { transform: translateY(-1px); border-color: #334155; }
    .btn.primary:hover { transform: translateY(-1px) scale(1.01); }
    .layout { display: grid; grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr); gap: 18px; margin-top: 18px; }
    .panel { background: var(--panel); border: 1px solid var(--border); border-radius: 18px; padding: 18px; box-shadow: var(--shadow); }
    .panel h3 { margin: 0 0 12px; font-size: 1.05rem; letter-spacing: -0.01em; }
    .grid-2 { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 10px; }
    .field { display: flex; flex-direction: column; gap: 6px; }
    .field label { color: var(--muted); font-size: 0.85rem; }
    .input, textarea, select { width: 100%; border-radius: 10px; border: 1px solid var(--border); background: var(--card); color: var(--text); padding: 10px 12px; font-size: 0.95rem; }
    textarea { resize: vertical; min-height: 60px; }
    select { cursor: pointer; }
    .stage-list { display: flex; flex-direction: column; gap: 10px; margin-top: 10px; }
    .stage-card { border: 1px solid var(--border); border-radius: 14px; padding: 12px; background: var(--card); display: flex; flex-direction: column; gap: 8px; position: relative; }
    .stage-card .grip { cursor: grab; color: var(--muted); user-select: none; }
    .stage-header { display: flex; align-items: center; gap: 8px; justify-content: space-between; }
    .pill { padding: 6px 10px; border-radius: 999px; font-size: 0.8rem; border: 1px solid var(--border); color: var(--muted); }
    .tags { display: flex; gap: 6px; flex-wrap: wrap; }
    .list { display: flex; flex-direction: column; gap: 8px; }
    .template-card, .saved-card { border: 1px solid var(--border); border-radius: 12px; padding: 12px; background: #0f172a; cursor: pointer; transition: border 0.15s ease, transform 0.15s ease; }
    .template-card:hover, .saved-card:hover { border-color: #5b21b6; transform: translateY(-1px); }
    .status { display: flex; gap: 8px; align-items: center; color: var(--muted); font-size: 0.9rem; }
    .result { border: 1px solid var(--border); border-radius: 12px; padding: 12px; background: var(--card); margin-top: 10px; }
    .timeline { display: flex; flex-direction: column; gap: 8px; }
    .timeline-entry { border: 1px solid var(--border); border-radius: 10px; padding: 10px; }
    .timeline-entry.success { border-color: rgba(16,185,129,0.4); }
    .timeline-entry.fail { border-color: rgba(239,68,68,0.4); }
    .yaml-preview { font-family: "SFMono-Regular", ui-monospace, monospace; background: #0d1528; border: 1px solid var(--border); border-radius: 12px; padding: 12px; color: #cbd5e1; min-height: 160px; white-space: pre-wrap; }
    @media (max-width: 1024px) { .layout { grid-template-columns: 1fr; } }
  </style>
</head>
<body>
  <div class="page">
    <div class="hero">
      <div>
        <h1>Cotor Flow Studio</h1>
        <p>드래그·드롭으로 스테이지를 구성하고, 템플릿을 바로 불러와 저장/실행할 수 있는 노코드 에디터입니다.</p>
      </div>
      <div class="hero-actions">
        <button class="btn" onclick="loadPipelines()">저장된 파이프라인 새로고침</button>
        <button class="btn primary" onclick="addStage('EXECUTION')">＋ 스테이지 추가</button>
      </div>
    </div>

    <div class="layout">
      <div class="panel">
        <h3>파이프라인 빌더</h3>
        <div class="grid-2">
          <div class="field">
            <label>이름</label>
            <input class="input" id="pipelineName" placeholder="ex) web-editor-demo" />
          </div>
          <div class="field">
            <label>실행 모드</label>
            <select id="executionMode" class="input">
              <option value="SEQUENTIAL">SEQUENTIAL</option>
              <option value="PARALLEL">PARALLEL</option>
              <option value="DAG">DAG</option>
            </select>
          </div>
        </div>
        <div class="field" style="margin-top:10px;">
          <label>설명</label>
          <input class="input" id="pipelineDesc" placeholder="파이프라인 목적" />
        </div>

        <div class="tags" style="margin-top:12px;">
          <span class="pill" onclick="addStage('EXECUTION')">＋ 실행 단계</span>
          <span class="pill" onclick="addStage('EXECUTION')">＋ 리뷰 단계</span>
          <span class="pill" onclick="addStage('EXECUTION')">＋ 병렬 단계</span>
        </div>

        <div id="stageList" class="stage-list"></div>

        <div class="tags" style="margin-top:12px; gap:10px;">
          <button class="btn" onclick="exportYaml()">YAML 미리보기</button>
          <button class="btn" onclick="savePipeline()">저장</button>
          <button class="btn primary" onclick="runPipeline()">실행</button>
        </div>
      </div>

      <div class="panel">
        <h3>템플릿 & 저장본</h3>
        <div class="list" id="templateList"></div>
        <h3 style="margin-top:12px;">저장된 파이프라인</h3>
        <div class="field" style="margin-bottom:10px;">
          <label>파이프라인/에이전트 검색</label>
          <input class="input" id="pipelineSearch" placeholder="이름, 설명, 실행모드, 에이전트, 태그 검색" oninput="renderSavedPipelines()" />
        </div>
        <div class="tags" id="savedTagFilters" style="margin-bottom:10px;"></div>
        <div class="list" id="savedList"></div>
        <h3 style="margin-top:12px;">결과</h3>
        <div id="resultBox" class="result">실행 결과가 여기에 표시됩니다.</div>
      </div>
    </div>

    <div class="panel" style="margin-top:16px;">
      <h3>YAML 프리뷰</h3>
      <pre id="yamlPreview" class="yaml-preview">스테이지를 추가하면 YAML이 표시됩니다.</pre>
    </div>
  </div>

  <script>
    const state = {
      name: "",
      description: "",
      executionMode: "SEQUENTIAL",
      stages: [],
      path: null,
      readOnly: false
    };
    const pluginMap = {
      claude: "ClaudePlugin",
      codex: "CodexPlugin",
      gemini: "GeminiPlugin",
      copilot: "CopilotPlugin",
      cursor: "CursorPlugin",
      opencode: "OpenCodePlugin",
      echo: "EchoPlugin"
    };

    let templates = [];
    let saved = [];
    let selectedSavedTags = [];
    let dragIndex = null;

    function uuid(prefix = "stage") {
      return prefix + "-" + Math.random().toString(16).slice(2, 6);
    }

    function renderStages() {
      const list = document.getElementById("stageList");
      if (!state.stages.length) {
        list.innerHTML = '<div class="result" style="text-align:center;color:var(--muted);">＋ 스테이지를 추가하세요.</div>';
        updateYaml();
        return;
      }

      list.innerHTML = state.stages.map((s, i) => {
        const deps = (s.dependencies || []).join(", ");
        return `
          <div class="stage-card" draggable="true" ondragstart="onDragStart(${ '$' }{i})" ondragover="onDragOver(event, ${ '$' }{i})" ondrop="onDrop(event, ${ '$' }{i})">
            <div class="stage-header">
              <span class="grip">⋮⋮</span>
              <input class="input" style="max-width:200px;" value="${ '$' }{s.id}" onchange="updateStageField(${ '$' }{i}, 'id', this.value)" />
              <select class="input" style="max-width:140px;" onchange="updateStageField(${ '$' }{i}, 'type', this.value)">
                ${ '$' }{["EXECUTION"].map(t => `<option value="${ '$' }{t}" ${ '$' }{s.type===t?"selected":""}>${ '$' }{t}</option>`).join("")}
              </select>
              <button class="btn" style="padding:6px 10px;" onclick="removeStage(${ '$' }{i})">삭제</button>
            </div>
            <div class="grid-2">
              <div class="field">
                <label>Agent</label>
                <input class="input" value="${ '$' }{s.agent || ""}" onchange="updateStageField(${ '$' }{i}, 'agent', this.value)" placeholder="claude / gemini / ..." />
              </div>
              <div class="field">
                <label>Dependencies</label>
                <input class="input" value="${ '$' }{deps}" onchange="updateStageField(${ '$' }{i}, 'dependencies', this.value)" placeholder="comma 로 구분" />
              </div>
            </div>
            <div class="field">
              <label>Input / Prompt</label>
              <textarea onchange="updateStageField(${ '$' }{i}, 'input', this.value)" placeholder="이 단계가 수행할 내용">${ '$' }{s.input || ""}</textarea>
            </div>
          </div>
        `;
      }).join("");

      updateYaml();
    }

    function updateStageField(index, key, value) {
      const stage = state.stages[index];
      if (key === "dependencies") {
        stage[key] = value.split(",").map(v => v.trim()).filter(Boolean);
      } else if (key === "loopMaxIterations") {
        stage[key] = Number(value) || 1;
      } else {
        stage[key] = value;
      }
      renderStages();
    }

    function addStage(type) {
      state.stages.push({
        id: uuid("stage"),
        type,
        agent: "",
        input: "",
        dependencies: []
      });
      renderStages();
    }

    function removeStage(index) {
      state.stages.splice(index, 1);
      renderStages();
    }

    function onDragStart(index) { dragIndex = index; }
    function onDragOver(e, index) { e.preventDefault(); }
    function onDrop(e, index) {
      e.preventDefault();
      if (dragIndex === null || dragIndex === index) return;
      const [item] = state.stages.splice(dragIndex, 1);
      state.stages.splice(index, 0, item);
      dragIndex = null;
      renderStages();
    }

    async function loadTemplates() {
      try {
        const res = await fetch("/api/editor/templates");
        templates = await res.json();
        const list = document.getElementById("templateList");
        list.innerHTML = templates.map(t => `
          <div class="template-card" onclick="applyTemplate('${'$'}{t.id}')">
            <div style="font-weight:700;">${'$'}{t.name}</div>
            <div style="color:var(--muted); font-size:0.9rem;">${'$'}{t.description}</div>
          </div>
        `).join("");
      } catch (e) {
        console.error(e);
      }
    }

    function toggleSavedTag(tag) {
      if (selectedSavedTags.includes(tag)) {
        selectedSavedTags = selectedSavedTags.filter(t => t !== tag);
      } else {
        selectedSavedTags = [...selectedSavedTags, tag];
      }
      renderSavedTagFilters();
      renderSavedPipelines();
    }

    function renderSavedTagFilters() {
      const box = document.getElementById("savedTagFilters");
      const tags = [...new Set(saved.flatMap(p => p.tags || []))].sort();
      if (!tags.length) {
        box.innerHTML = '<span style="color:var(--muted);font-size:0.85rem;">태그 없음</span>';
        return;
      }
      box.innerHTML = tags.map(tag => {
        const active = selectedSavedTags.includes(tag);
        const style = active
          ? 'background:linear-gradient(135deg,#8b5cf6,#6d28d9);border:none;color:white;'
          : '';
        return `<button class="btn" style="padding:6px 10px;${'$'}{style}" onclick="toggleSavedTag('${'$'}{tag.replace(/'/g, "\\'")}')">#${'$'}{tag}</button>`;
      }).join("");
    }

    function renderSavedPipelines() {
      const query = (document.getElementById("pipelineSearch")?.value || "").trim().toLowerCase();
      const list = document.getElementById("savedList");
      const filtered = saved.filter(p => {
        const tags = p.tags || [];
        const agents = p.agents || [];
        const haystack = [p.name, p.description, p.executionMode, ...agents, ...tags]
          .filter(Boolean)
          .join(" ")
          .toLowerCase();
        const matchesQuery = !query || haystack.includes(query);
        const matchesTags = selectedSavedTags.every(tag => tags.includes(tag));
        return matchesQuery && matchesTags;
      });
      if (!saved.length) {
        list.innerHTML = '<div class="template-card" style="border-style:dashed;color:var(--muted);">저장된 파이프라인이 없습니다.</div>';
        return;
      }
      if (!filtered.length) {
        list.innerHTML = '<div class="template-card" style="border-style:dashed;color:var(--muted);">검색/태그 조건과 일치하는 파이프라인이 없습니다.</div>';
        return;
      }
      list.innerHTML = filtered.map(p => `
        <div class="saved-card" onclick="loadPipeline('${'$'}{p.name}')">
          <div style="font-weight:700;">${'$'}{p.name}</div>
          <div style="color:var(--muted);font-size:0.9rem;">${'$'}{p.description}</div>
          <div class="tags">
            <span class="pill">${'$'}{p.executionMode}</span>
            <span class="pill">${'$'}{p.stageCount} stages</span>
            ${'$'}{(p.agents || []).map(a => `<span class="pill">@${'$'}{a}</span>`).join("")}
            ${'$'}{(p.tags || []).map(tag => `<span class="pill">#${'$'}{tag}</span>`).join("")}
          </div>
        </div>
      `).join("");
    }

    async function loadPipelines() {
      try {
        const res = await fetch("/api/editor/pipelines");
        saved = await res.json();
        renderSavedTagFilters();
        renderSavedPipelines();
      } catch (e) {
        console.error(e);
      }
    }

    async function loadPipeline(name) {
      try {
        const res = await fetch("/api/editor/pipelines/" + encodeURIComponent(name));
        if (!res.ok) throw new Error("불러오기 실패");
        const data = await res.json();
        state.name = data.name;
        state.description = data.description;
        state.executionMode = data.executionMode;
        state.stages = data.stages;
        state.path = data.path;
        document.getElementById("pipelineName").value = data.name;
        document.getElementById("pipelineDesc").value = data.description;
        document.getElementById("executionMode").value = data.executionMode;
        renderStages();
        document.getElementById("resultBox").innerText = "불러옴: " + data.path;
      } catch (e) {
        alert(e.message);
      }
    }

    function applyTemplate(id) {
      const tpl = templates.find(t => t.id === id);
      if (!tpl) return;
      const p = tpl.pipeline;
      state.name = p.name;
      state.description = p.description;
      state.executionMode = p.executionMode;
      state.stages = p.stages;
      state.path = null;
      document.getElementById("pipelineName").value = p.name;
      document.getElementById("pipelineDesc").value = p.description;
      document.getElementById("executionMode").value = p.executionMode;
      renderStages();
      document.getElementById("resultBox").innerText = "템플릿이 적용되었습니다.";
    }

    function collectStateFromForm() {
      state.name = document.getElementById("pipelineName").value.trim();
      state.description = document.getElementById("pipelineDesc").value.trim();
      state.executionMode = document.getElementById("executionMode").value;
    }

    async function savePipeline() {
      collectStateFromForm();
      if (!state.name) return alert("이름을 입력하세요.");
      if (!state.stages.length) return alert("스테이지를 추가하세요.");
      try {
        const res = await fetch("/api/editor/save", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            name: state.name,
            description: state.description,
            executionMode: state.executionMode,
            stages: state.stages,
            path: state.path
          })
        });
        const data = await res.json();
        if (!res.ok || data.error) throw new Error(data.error || "저장 실패");
        state.path = data.path;
        document.getElementById("resultBox").innerText = "저장됨: " + data.path;
        loadPipelines();
      } catch (e) {
        alert(e.message);
      }
    }

    async function runPipeline() {
      collectStateFromForm();
      if (!state.name) return alert("이름을 입력하세요.");
      await savePipeline();
      try {
        const res = await fetch("/api/editor/run", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ name: state.name, path: state.path })
        });
        const data = await res.json();
        if (!res.ok || data.error) throw new Error(data.error || "실행 실패");
        renderResult(data);
      } catch (e) {
        alert(e.message);
      }
    }

    function renderResult(data) {
      const box = document.getElementById("resultBox");
      const timeline = (data.timeline || []).map(t => {
        const cls = t.state === "FAILED" ? "fail" : "success";
        return `<div class="timeline-entry ${'$'}{cls}">
          <div style="font-weight:700;">${'$'}{t.stageId}</div>
          <div style="color:var(--muted);font-size:0.9rem;">${'$'}{t.durationMs || "-"} ms</div>
          <div style="color:var(--muted);font-size:0.9rem;">${'$'}{t.message || t.outputPreview || ""}</div>
        </div>`;
      }).join("");
      box.innerHTML = `
        <div class="status">
          <span class="pill">${'$'}{data.executionMode}</span>
          <span>${'$'}{data.successCount}/${'$'}{data.totalAgents} 성공 · ${'$'}{data.totalDuration}ms</span>
        </div>
        <div class="timeline">${'$'}{timeline || "타임라인 없음"}</div>
      `;
    }

    function exportYaml() {
      collectStateFromForm();
      updateYaml();
      window.scrollTo({ top: document.body.scrollHeight, behavior: "smooth" });
    }

    function updateYaml() {
      const yaml = [
        'version: "1.0"',
        "",
        "agents:",
        ...(collectAgents().map(a => {
          const plugin = pluginMap[a.toLowerCase()] || "EchoPlugin";
          return `  - name: ${'$'}{a}\n    pluginClass: com.cotor.data.plugin.${'$'}{plugin}\n    timeout: 60000`;
        })),
        "",
        "pipelines:",
        `  - name: ${'$'}{state.name || "my-pipeline"}`,
        `    description: "${'$'}{state.description || ""}"`,
        `    executionMode: ${'$'}{state.executionMode}`,
        "    stages:",
        ...state.stages.map(s => {
          const deps = (s.dependencies || []).join(", ");
          return `      - id: ${'$'}{s.id}\n        agent:\n          name: ${'$'}{s.agent || "agent"}\n        input: "${'$'}{(s.input || "").replace(/"/g, '\\"')}"${'$'}{deps ? `\n        dependencies: [${'$'}{deps}]` : ""}`;
        })
      ].join("\n");
      document.getElementById("yamlPreview").textContent = yaml;
    }

    function collectAgents() {
      const names = new Set();
      state.stages.forEach(s => s.agent && names.add(s.agent));
      return Array.from(names);
    }

    function applyReadOnlyMode() {
      if (!state.readOnly) return;

      // 비활성화할 버튼들
      const buttonsToDisable = [
        ...document.querySelectorAll('.hero-actions .btn.primary'),
        ...document.querySelectorAll('.tags .btn'),
        ...document.querySelectorAll('.stage-card .btn'),
        ...document.querySelectorAll('.panel .pill[onclick]'),
      ];
      buttonsToDisable.forEach(btn => {
        btn.style.display = 'none';
      });

      // 모든 입력 필드 읽기 전용으로
      const inputs = document.querySelectorAll('input, textarea, select');
      inputs.forEach(input => {
        input.setAttribute('readonly', true);
        input.style.pointerEvents = 'none';
        input.style.background = '#2a3a52';
      });

      // 템플릿/저장본 클릭 비활성화
      document.getElementById('templateList').style.pointerEvents = 'none';
    }

    async function initApp() {
      try {
        const res = await fetch("/api/editor/config");
        const config = await res.json();
        state.readOnly = config.readOnly;
        if (state.readOnly) {
          document.querySelector('.hero h1').innerText += " (읽기 전용)";
        }
      } catch (e) {
        console.error("Failed to load config", e);
      }

      document.getElementById("executionMode").addEventListener("change", e => {
        state.executionMode = e.target.value;
      });

      await Promise.all([loadTemplates(), loadPipelines()]);
      renderStages();
      applyReadOnlyMode();
    }

    // Init
    initApp();
  </script>
</body>
</html>
""".trimIndent()
