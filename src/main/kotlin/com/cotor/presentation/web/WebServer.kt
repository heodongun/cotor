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
import kotlin.io.path.readText
import kotlin.io.path.writeText

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

    fun start(port: Int = 8080, openBrowser: Boolean = false) {
        ensureEditorDir()

        if (openBrowser) {
            thread(name = "cotor-web-open") {
                Thread.sleep(600)
                openInBrowser("http://localhost:$port/editor")
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
                    call.respondRedirect("/editor")
                }

                get("/editor") {
                    call.respondText(editorHtml, ContentType.Text.Html)
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
                    val request = call.receive<EditorPipelineRequest>()
                    if (request.name.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Pipeline name is required"))
                    }
                    if (request.stages.isEmpty()) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "At least one stage is required"))
                    }

                    val path = savePipeline(request)
                    call.respond(mapOf("ok" to true, "path" to path.toString()))
                }

                post("/api/editor/run") {
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

                        call.respond(
                            mapOf(
                                "name" to pipeline.name,
                                "executionMode" to pipeline.executionMode.name,
                                "totalAgents" to result.totalAgents,
                                "successCount" to result.successCount,
                                "failureCount" to result.failureCount,
                                "totalDuration" to result.totalDuration,
                                "results" to result.results.map {
                                    mapOf(
                                        "agentName" to it.agentName,
                                        "isSuccess" to it.isSuccess,
                                        "duration" to it.duration,
                                        "output" to it.output,
                                        "error" to it.error
                                    )
                                },
                                "timeline" to timeline
                            )
                        )
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
    ): List<Map<String, Any?>> {
        val resultByAgent = result.results.groupBy { it.agentName }
        return pipeline.stages.map { stage ->
            val stageResult = resultByAgent[stage.agent?.name]?.firstOrNull()
            val state = when {
                stageResult == null -> "FAILED"
                stageResult.isSuccess -> "COMPLETED"
                else -> "FAILED"
            }
            val message = stageResult?.error ?: stageResult?.output
            val preview = stageResult?.output?.take(200)

            mapOf(
                "stageId" to stage.id,
                "state" to state,
                "durationMs" to (stageResult?.duration ?: 0),
                "message" to message,
                "outputPreview" to preview
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
      path: null
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

    async function loadPipelines() {
      try {
        const res = await fetch("/api/editor/pipelines");
        saved = await res.json();
        const list = document.getElementById("savedList");
        if (!saved.length) {
          list.innerHTML = '<div class="template-card" style="border-style:dashed;color:var(--muted);">저장된 파이프라인이 없습니다.</div>';
          return;
        }
        list.innerHTML = saved.map(p => `
          <div class="saved-card" onclick="loadPipeline('${'$'}{p.name}')">
            <div style="font-weight:700;">${'$'}{p.name}</div>
            <div style="color:var(--muted);font-size:0.9rem;">${'$'}{p.description}</div>
            <div class="tags">
              <span class="pill">${'$'}{p.executionMode}</span>
              <span class="pill">${'$'}{p.stageCount} stages</span>
            </div>
          </div>
        `).join("");
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

    // Init
    document.getElementById("executionMode").addEventListener("change", e => {
      state.executionMode = e.target.value;
    });
    loadTemplates();
    loadPipelines();
    renderStages();
  </script>
</body>
</html>
""".trimIndent()
