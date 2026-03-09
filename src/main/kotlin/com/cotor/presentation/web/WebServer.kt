package com.cotor.presentation.web

import com.cotor.data.config.ConfigRepository
import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.orchestrator.PipelineOrchestrator
import com.cotor.model.*
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

    fun start(port: Int = 8080, openBrowser: Boolean = false, readOnly: Boolean = false) {
        ensureEditorDir()

        if (openBrowser) {
            thread(name = "cotor-web-open") {
                Thread.sleep(600)
                openInBrowser("http://localhost:$port/")
            }
        }

        embeddedServer(Netty, port = port) {
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
                    val configPath = request.path?.let { Path(it) } ?: editorDir.resolve("${sanitizeName(request.name)}.yaml")
                    if (!configPath.exists()) {
                        return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Pipeline not saved yet"))
                    }

                    try {
                        val config = configRepository.loadConfig(configPath)
                        val pipeline = config.pipelines.firstOrNull { it.name == request.name }
                            ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Pipeline not found in config"))

                        config.agents.forEach { agentRegistry.registerAgent(it) }
                        val result = orchestrator.executePipeline(pipeline)
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
            }
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
  <title>Cotor | Explain, Design, Run</title>
  <style>
    :root {
      --page-bg: #f4efe5;
      --surface: rgba(255, 252, 246, 0.82);
      --surface-strong: #fffaf2;
      --surface-dark: #11231f;
      --ink: #10201c;
      --muted: #55645d;
      --line: rgba(16, 32, 28, 0.12);
      --accent: #0f766e;
      --accent-strong: #115e59;
      --accent-soft: rgba(15, 118, 110, 0.14);
      --warm: #dd6b20;
      --warm-soft: rgba(221, 107, 32, 0.14);
      --success: #2f855a;
      --shadow: 0 26px 80px rgba(17, 35, 31, 0.12);
      --radius-lg: 28px;
      --radius-md: 20px;
      --radius-sm: 14px;
      --max-width: 1180px;
      --space-1: 4px;
      --space-2: 8px;
      --space-3: 12px;
      --space-4: 16px;
      --space-5: 20px;
      --space-6: 24px;
      --space-8: 32px;
      --space-10: 40px;
      --space-12: 48px;
    }
    * { box-sizing: border-box; }
    html { scroll-behavior: smooth; }
    body {
      margin: 0;
      color: var(--ink);
      background:
        radial-gradient(circle at top left, rgba(15, 118, 110, 0.22), transparent 32%),
        radial-gradient(circle at top right, rgba(221, 107, 32, 0.18), transparent 28%),
        linear-gradient(180deg, #fbf8f1 0%, #f4efe5 54%, #ede6d9 100%);
      font-family: "Space Grotesk", "Pretendard Variable", "Avenir Next", sans-serif;
      min-height: 100vh;
    }
    a { color: inherit; text-decoration: none; }
    img { max-width: 100%; }
    .shell {
      width: min(calc(100% - 32px), var(--max-width));
      margin: 0 auto;
    }
    .nav {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-4);
      padding: 20px 0 14px;
    }
    .brand {
      display: inline-flex;
      align-items: center;
      gap: var(--space-3);
      font-weight: 700;
      letter-spacing: -0.03em;
    }
    .brand-mark {
      width: 40px;
      height: 40px;
      border-radius: 12px;
      display: grid;
      place-items: center;
      background: linear-gradient(135deg, var(--accent), #17a399);
      color: white;
      box-shadow: 0 14px 28px rgba(15, 118, 110, 0.22);
    }
    .nav-links {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      flex-wrap: wrap;
      justify-content: flex-end;
    }
    .nav-link {
      min-height: 44px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0 14px;
      border-radius: 999px;
      color: var(--muted);
      transition: background 160ms ease, color 160ms ease, transform 160ms ease;
    }
    .nav-link:hover {
      background: rgba(255, 255, 255, 0.58);
      color: var(--ink);
      transform: translateY(-1px);
    }
    .nav-link:focus-visible,
    .button:focus-visible {
      outline: 3px solid rgba(17, 94, 89, 0.26);
      outline-offset: 3px;
    }
    .button {
      min-height: 44px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      padding: 0 18px;
      border-radius: 999px;
      border: 1px solid transparent;
      font-weight: 700;
      letter-spacing: -0.02em;
      transition: transform 160ms ease, box-shadow 160ms ease, background 160ms ease, border-color 160ms ease;
    }
    .button:hover { transform: translateY(-1px); }
    .button-primary {
      background: linear-gradient(135deg, var(--accent), var(--accent-strong));
      color: white;
      box-shadow: 0 18px 30px rgba(17, 94, 89, 0.24);
    }
    .button-secondary {
      background: rgba(255, 255, 255, 0.7);
      border-color: rgba(16, 32, 28, 0.1);
      color: var(--ink);
    }
    .hero {
      display: grid;
      grid-template-columns: minmax(0, 1.18fr) minmax(320px, 0.82fr);
      gap: var(--space-8);
      padding: 22px 0 64px;
      align-items: center;
    }
    .eyebrow {
      display: inline-flex;
      align-items: center;
      gap: var(--space-2);
      padding: 8px 14px;
      border-radius: 999px;
      background: rgba(255, 255, 255, 0.7);
      border: 1px solid rgba(16, 32, 28, 0.08);
      color: var(--muted);
      font-size: 0.92rem;
      font-weight: 600;
    }
    .hero h1 {
      margin: 18px 0 16px;
      font-size: clamp(2.8rem, 6vw, 5.4rem);
      line-height: 0.94;
      letter-spacing: -0.05em;
      max-width: 11ch;
    }
    .hero p {
      margin: 0;
      font-size: 1.05rem;
      line-height: 1.72;
      color: var(--muted);
      max-width: 62ch;
    }
    .hero-actions {
      display: flex;
      gap: var(--space-3);
      flex-wrap: wrap;
      margin-top: var(--space-6);
    }
    .hero-meta {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--space-4);
      margin-top: var(--space-8);
    }
    .hero-stat {
      padding: 18px;
      border-radius: var(--radius-sm);
      background: rgba(255, 255, 255, 0.62);
      border: 1px solid rgba(16, 32, 28, 0.08);
      box-shadow: 0 14px 34px rgba(16, 32, 28, 0.06);
    }
    .hero-stat strong {
      display: block;
      font-size: 1.45rem;
      letter-spacing: -0.04em;
      margin-bottom: 6px;
    }
    .hero-card {
      position: relative;
      overflow: hidden;
      padding: var(--space-6);
      border-radius: var(--radius-lg);
      background: linear-gradient(160deg, rgba(17, 35, 31, 0.96), rgba(17, 35, 31, 0.9));
      color: white;
      box-shadow: var(--shadow);
      border: 1px solid rgba(255, 255, 255, 0.06);
    }
    .hero-card::before,
    .hero-card::after {
      content: "";
      position: absolute;
      inset: auto;
      border-radius: 999px;
      filter: blur(2px);
      opacity: 0.72;
    }
    .hero-card::before {
      width: 180px;
      height: 180px;
      top: -30px;
      right: -46px;
      background: rgba(15, 118, 110, 0.26);
    }
    .hero-card::after {
      width: 160px;
      height: 160px;
      bottom: -36px;
      left: -24px;
      background: rgba(221, 107, 32, 0.2);
    }
    .hero-card > * { position: relative; z-index: 1; }
    .card-label {
      display: inline-flex;
      padding: 8px 12px;
      border-radius: 999px;
      background: rgba(255, 255, 255, 0.1);
      font-size: 0.82rem;
      color: rgba(255, 255, 255, 0.8);
    }
    .card-list {
      display: grid;
      gap: 12px;
      margin-top: 18px;
    }
    .card-item {
      padding: 16px;
      border-radius: var(--radius-sm);
      background: rgba(255, 255, 255, 0.06);
      border: 1px solid rgba(255, 255, 255, 0.08);
    }
    .card-item strong {
      display: block;
      margin-bottom: 6px;
      font-size: 1rem;
    }
    .card-item span {
      color: rgba(255, 255, 255, 0.72);
      line-height: 1.6;
      font-size: 0.95rem;
    }
    .page-section {
      padding: 18px 0 22px;
    }
    .section-header {
      display: flex;
      align-items: flex-end;
      justify-content: space-between;
      gap: var(--space-4);
      margin-bottom: var(--space-6);
      flex-wrap: wrap;
    }
    .section-header h2 {
      margin: 0;
      font-size: clamp(1.8rem, 3vw, 2.8rem);
      line-height: 1;
      letter-spacing: -0.05em;
    }
    .section-header p {
      margin: 0;
      max-width: 48ch;
      color: var(--muted);
      line-height: 1.7;
    }
    .feature-grid,
    .step-grid,
    .proof-grid {
      display: grid;
      gap: var(--space-4);
    }
    .feature-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    .step-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
    .proof-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    .panel {
      padding: var(--space-6);
      border-radius: var(--radius-md);
      background: var(--surface);
      border: 1px solid var(--line);
      box-shadow: var(--shadow);
      backdrop-filter: blur(14px);
    }
    .panel h3 {
      margin: 0 0 10px;
      font-size: 1.16rem;
      letter-spacing: -0.03em;
    }
    .panel p {
      margin: 0;
      color: var(--muted);
      line-height: 1.7;
    }
    .panel-accent {
      background:
        linear-gradient(180deg, rgba(255, 255, 255, 0.78), rgba(255, 255, 255, 0.66)),
        linear-gradient(135deg, rgba(15, 118, 110, 0.08), rgba(221, 107, 32, 0.05));
    }
    .label {
      display: inline-flex;
      align-items: center;
      min-height: 32px;
      padding: 0 10px;
      border-radius: 999px;
      background: var(--accent-soft);
      color: var(--accent-strong);
      font-size: 0.82rem;
      font-weight: 700;
      margin-bottom: 12px;
    }
    .label-warm {
      background: var(--warm-soft);
      color: var(--warm);
    }
    .flow-line {
      display: flex;
      align-items: center;
      gap: 12px;
      color: var(--muted);
      font-size: 0.95rem;
      margin-top: 16px;
      flex-wrap: wrap;
    }
    .flow-dot {
      width: 8px;
      height: 8px;
      border-radius: 999px;
      background: var(--accent);
      box-shadow: 18px 0 0 rgba(15, 118, 110, 0.45), 36px 0 0 rgba(221, 107, 32, 0.65);
      margin-right: 40px;
    }
    .code-block {
      margin: 0;
      border-radius: var(--radius-md);
      padding: var(--space-5);
      background: var(--surface-dark);
      color: #f9fafb;
      font-family: "SFMono-Regular", "JetBrains Mono", monospace;
      font-size: 0.92rem;
      line-height: 1.75;
      overflow: auto;
      box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.05);
    }
    .cta {
      margin: 28px 0 56px;
      padding: var(--space-8);
      border-radius: var(--radius-lg);
      background: linear-gradient(135deg, rgba(17, 35, 31, 0.96), rgba(15, 118, 110, 0.92));
      color: white;
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: var(--space-6);
      align-items: center;
      box-shadow: var(--shadow);
    }
    .cta p {
      margin: 10px 0 0;
      color: rgba(255, 255, 255, 0.78);
      line-height: 1.7;
      max-width: 58ch;
    }
    .footer {
      padding: 0 0 44px;
      color: var(--muted);
      font-size: 0.94rem;
    }
    @media (max-width: 1080px) {
      .hero,
      .cta {
        grid-template-columns: 1fr;
      }
      .feature-grid,
      .proof-grid {
        grid-template-columns: 1fr;
      }
    }
    @media (max-width: 840px) {
      .hero-meta,
      .step-grid {
        grid-template-columns: 1fr;
      }
    }
    @media (max-width: 720px) {
      .nav {
        padding-top: 16px;
        align-items: flex-start;
        flex-direction: column;
      }
      .nav-links { width: 100%; justify-content: flex-start; }
      .hero { padding-bottom: 44px; }
      .hero h1 { max-width: 13ch; }
      .shell { width: min(calc(100% - 24px), var(--max-width)); }
      .panel,
      .hero-card,
      .cta {
        padding: var(--space-5);
      }
      .section-header h2 {
        line-height: 1.06;
      }
    }
  </style>
</head>
<body>
  <div class="shell">
    <header class="nav" aria-label="Cotor intro navigation">
      <div class="brand">
        <div class="brand-mark" aria-hidden="true">C</div>
        <div>
          <div>Cotor</div>
          <div style="font-size:0.85rem;color:var(--muted);font-weight:500;">Master-agent orchestration</div>
        </div>
      </div>
      <nav class="nav-links">
        <a class="nav-link" href="#why">왜 Cotor인가</a>
        <a class="nav-link" href="#workflow">어떻게 쓰나</a>
        <a class="nav-link" href="#proof">무엇이 포함되나</a>
        <a class="button button-secondary" href="/editor">Flow Studio 열기</a>
      </nav>
    </header>

    <main>
      <section class="hero" aria-labelledby="hero-title">
        <div>
          <div class="eyebrow">CLI의 제어력 + 웹의 가시성</div>
          <h1 id="hero-title">설명하고, 설계하고, 검증하고, 바로 실행하는 AI workflow.</h1>
          <p>
            Cotor는 여러 에이전트를 한 흐름으로 묶어 실행하는 Kotlin 기반 오케스트레이터입니다.
            소개 페이지에서 핵심 개념을 빠르게 이해한 뒤, 곧바로 `/editor`에서 노코드 스튜디오와 템플릿 워크플로우로 이어질 수 있습니다.
          </p>
          <div class="hero-actions">
            <a class="button button-primary" href="/editor">Flow Studio 시작</a>
            <a class="button button-secondary" href="#workflow">핵심 흐름 보기</a>
          </div>
          <div class="hero-meta" aria-label="product highlights">
            <div class="hero-stat">
              <strong>Sequential / Parallel / DAG</strong>
              <span>복잡한 협업 구조를 한 도구에서 설계하고 실행합니다.</span>
            </div>
            <div class="hero-stat">
              <strong>Validation + Recovery</strong>
              <span>사전 검증, 체크포인트, 재개 경로를 기본 제공해 실패 비용을 줄입니다.</span>
            </div>
            <div class="hero-stat">
              <strong>CLI + Web Studio</strong>
              <span>터미널과 브라우저를 오가며 같은 워크플로우를 다룹니다.</span>
            </div>
          </div>
        </div>

        <aside class="hero-card" aria-label="launch snapshot">
          <span class="card-label">Quick launch</span>
          <h2 style="margin:14px 0 10px;font-size:2rem;line-height:1;letter-spacing:-0.05em;">가장 빠른 진입 경로</h2>
          <p style="margin:0;color:rgba(255,255,255,0.74);line-height:1.7;">
            `cotor web`를 실행하면 이제 루트(`/`)에서 소개 랜딩을 보고, 이어서 `/editor`에서 파이프라인을 설계할 수 있습니다.
          </p>
          <div class="card-list">
            <div class="card-item">
              <strong>1. 개념을 빠르게 파악</strong>
              <span>무엇을 해결하는 도구인지, 어떤 사용 시나리오가 맞는지 몇 화면 안에 정리합니다.</span>
            </div>
            <div class="card-item">
              <strong>2. 템플릿으로 시작</strong>
              <span>비교, 리뷰 체인, 팬아웃/머지 같은 패턴을 웹 스튜디오에서 즉시 불러옵니다.</span>
            </div>
            <div class="card-item">
              <strong>3. 저장 후 실행</strong>
              <span>생성된 YAML은 `.cotor/web/`에 저장되고, CLI와 같은 결과 경로로 이어집니다.</span>
            </div>
          </div>
          <div class="flow-line">
            <span class="flow-dot" aria-hidden="true"></span>
            <span>Explain</span>
            <span>Design</span>
            <span>Validate</span>
            <span>Run</span>
          </div>
        </aside>
      </section>

      <section id="why" class="page-section" aria-labelledby="why-title">
        <div class="section-header">
          <div>
            <h2 id="why-title">왜 Cotor인가</h2>
          </div>
          <p>단순한 실행기보다 한 단계 위의 문제를 겨냥합니다. 설계, 검증, 실행, 모니터링을 같은 흐름 안에서 다루는 것이 핵심입니다.</p>
        </div>
        <div class="feature-grid">
          <article class="panel panel-accent">
            <span class="label">Orchestration</span>
            <h3>여러 에이전트를 한 파이프라인으로</h3>
            <p>순차, 병렬, DAG 모드를 지원해 생성, 리뷰, 비교, 병합 흐름을 깔끔하게 정의할 수 있습니다.</p>
          </article>
          <article class="panel">
            <span class="label label-warm">Guardrails</span>
            <h3>검증과 보안을 실행 전에</h3>
            <p>파이프라인 검증, 보안 화이트리스트, 체크포인트/재개 기능으로 실패했을 때의 복구 경로를 남깁니다.</p>
          </article>
          <article class="panel">
            <span class="label">Visibility</span>
            <h3>CLI와 웹이 같은 모델을 공유</h3>
            <p>CLI에서 관리하던 YAML과 실행 결과를 웹 스튜디오에서도 다뤄, 도입과 운영의 문턱을 낮춥니다.</p>
          </article>
          <article class="panel panel-accent">
            <span class="label label-warm">Templates</span>
            <h3>반복 패턴을 빠르게 재사용</h3>
            <p>비교, 생성→리뷰, 결정 게이트, 자기치유 루프, 팬아웃/머지 같은 템플릿을 기반으로 시작할 수 있습니다.</p>
          </article>
        </div>
      </section>

      <section id="workflow" class="page-section" aria-labelledby="workflow-title">
        <div class="section-header">
          <div>
            <h2 id="workflow-title">어떻게 사용하는가</h2>
          </div>
          <p>첫 방문자도 바로 따라갈 수 있도록, 소개 페이지에서 스튜디오로 이어지는 경로를 짧게 정리했습니다.</p>
        </div>
        <div class="step-grid">
          <article class="panel">
            <span class="label">Step 1</span>
            <h3>개념과 흐름 이해</h3>
            <p>루트 랜딩에서 제품 가치와 실행 모드를 훑고, 자신이 필요한 패턴이 무엇인지 확인합니다.</p>
          </article>
          <article class="panel panel-accent">
            <span class="label label-warm">Step 2</span>
            <h3>Flow Studio로 이동</h3>
            <p>`/editor`에서 템플릿을 불러오거나 스테이지를 드래그·드롭으로 구성해 YAML을 설계합니다.</p>
          </article>
          <article class="panel">
            <span class="label">Step 3</span>
            <h3>저장, 실행, 공유</h3>
            <p>웹에서 저장된 파이프라인은 CLI와 같은 설정 자산으로 남기 때문에 이후 자동화/버전 관리 흐름으로 자연스럽게 넘어갑니다.</p>
          </article>
        </div>
      </section>

      <section id="proof" class="page-section" aria-labelledby="proof-title">
        <div class="section-header">
          <div>
            <h2 id="proof-title">무엇이 포함되는가</h2>
          </div>
          <p>소개 페이지는 마케팅 문구만 두지 않고, 실제 진입 명령과 핵심 기능 지도를 함께 보여줍니다.</p>
        </div>
        <div class="proof-grid">
          <article class="panel">
            <h3>Launch commands</h3>
            <pre class="code-block">./gradlew shadowJar
java -jar build/libs/cotor-1.0.0.jar web --open

# 소개 랜딩
http://localhost:8080/

# 웹 스튜디오
http://localhost:8080/editor</pre>
          </article>
          <article class="panel panel-accent">
            <h3>Included entry points</h3>
            <p>랜딩 페이지에서 핵심 가치, 템플릿 패턴, 실행 모드, 다음 행동을 먼저 설명하고, 마지막 CTA를 통해 웹 스튜디오로 이동시킵니다.</p>
            <div style="display:grid;gap:10px;margin-top:14px;">
              <div class="hero-stat" style="padding:14px;background:rgba(255,255,255,0.56);">
                <strong style="font-size:1rem;">설명</strong>
                <span>첫 방문자에게 제품 맥락과 사용 시나리오를 제공</span>
              </div>
              <div class="hero-stat" style="padding:14px;background:rgba(255,255,255,0.56);">
                <strong style="font-size:1rem;">전환</strong>
                <span>`/editor` CTA로 실제 작업 흐름에 즉시 연결</span>
              </div>
              <div class="hero-stat" style="padding:14px;background:rgba(255,255,255,0.56);">
                <strong style="font-size:1rem;">증명</strong>
                <span>로컬 명령과 URL을 그대로 적어 누구나 재현 가능</span>
              </div>
            </div>
          </article>
        </div>
      </section>

      <section class="cta" aria-labelledby="cta-title">
        <div>
          <h2 id="cta-title" style="margin:0;font-size:clamp(1.8rem,3vw,3rem);line-height:1;letter-spacing:-0.05em;">개념 확인이 끝났다면, 이제 바로 설계로 이동합니다.</h2>
          <p>소개 페이지는 진입점을 설명하고, 실제 파이프라인 편집은 기존 Flow Studio에 맡깁니다. 이 분리를 통해 처음 보는 사용자도 맥락을 잃지 않고 시작할 수 있습니다.</p>
        </div>
        <div style="display:flex;gap:12px;flex-wrap:wrap;">
          <a class="button button-primary" href="/editor">Flow Studio 열기</a>
          <a class="button button-secondary" href="#hero-title">위로 돌아가기</a>
        </div>
      </section>
    </main>

    <footer class="footer">
      Cotor intro landing lives inside the existing Ktor web server, so the explanation page and the editor ship from the same local command.
    </footer>
  </div>
</body>
</html>
"""

// Lightweight HTML (single file) to keep the web UI self contained.
private val editorHtml = """
<!doctype html>
<html lang="ko">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width,initial-scale=1.0">
  <title>Cotor Flow Studio</title>
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
