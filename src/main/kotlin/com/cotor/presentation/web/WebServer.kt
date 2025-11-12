package com.cotor.presentation.web

import com.cotor.data.config.ConfigRepository
import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.orchestrator.PipelineOrchestrator
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.io.path.Path

/**
 * Web UI Server for Cotor
 */
class WebServer : KoinComponent {
    private val configRepository: ConfigRepository by inject()
    private val agentRegistry: AgentRegistry by inject()
    private val orchestrator: PipelineOrchestrator by inject()

    fun start(port: Int = 8080) {
        embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                json()
            }
            install(CORS) {
                anyHost()
            }

            routing {
                // Home page
                get("/") {
                    call.respondHtml {
                        head {
                            title { +"Cotor - AI Master-Agent" }
                            style {
                                unsafe {
                                    raw("""
                                        * { margin: 0; padding: 0; box-sizing: border-box; }
                                        body { 
                                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                                            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                                            min-height: 100vh;
                                            padding: 20px;
                                        }
                                        .container { 
                                            max-width: 1200px; 
                                            margin: 0 auto; 
                                            background: white;
                                            border-radius: 20px;
                                            padding: 40px;
                                            box-shadow: 0 20px 60px rgba(0,0,0,0.3);
                                        }
                                        h1 { 
                                            color: #667eea; 
                                            margin-bottom: 10px;
                                            font-size: 3em;
                                        }
                                        .subtitle {
                                            color: #666;
                                            margin-bottom: 40px;
                                            font-size: 1.2em;
                                        }
                                        .create-section {
                                            background: #f8f9fa;
                                            padding: 30px;
                                            border-radius: 15px;
                                            margin-bottom: 40px;
                                        }
                                        .create-section h2 {
                                            color: #667eea;
                                            margin-bottom: 20px;
                                        }
                                        .create-section form {
                                            display: flex;
                                            flex-direction: column;
                                            gap: 15px;
                                        }
                                        .create-section input[type="text"],
                                        .create-section textarea {
                                            padding: 12px;
                                            border: 2px solid #ddd;
                                            border-radius: 8px;
                                            font-size: 1em;
                                            font-family: inherit;
                                        }
                                        .create-section textarea {
                                            resize: vertical;
                                        }
                                        .agent-select {
                                            display: flex;
                                            flex-direction: column;
                                            gap: 10px;
                                        }
                                        .agent-select > div {
                                            display: flex;
                                            flex-wrap: wrap;
                                            gap: 15px;
                                        }
                                        .checkbox-label {
                                            display: flex;
                                            align-items: center;
                                            gap: 5px;
                                            cursor: pointer;
                                        }
                                        .checkbox-label input {
                                            cursor: pointer;
                                        }
                                        .agent-tasks {
                                            display: flex;
                                            flex-direction: column;
                                            gap: 15px;
                                        }
                                        .agent-task-item {
                                            display: flex;
                                            gap: 10px;
                                            align-items: flex-start;
                                        }
                                        .agent-select-dropdown {
                                            padding: 12px;
                                            border: 2px solid #ddd;
                                            border-radius: 8px;
                                            font-size: 1em;
                                            min-width: 150px;
                                        }
                                        .agent-task-item textarea {
                                            flex: 1;
                                        }
                                        .add-btn {
                                            background: #28a745;
                                            color: white;
                                            border: none;
                                            padding: 12px 24px;
                                            border-radius: 8px;
                                            cursor: pointer;
                                            font-size: 1em;
                                        }
                                        .add-btn:hover {
                                            background: #218838;
                                        }
                                        .pipeline-grid {
                                            display: grid;
                                            grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
                                            gap: 20px;
                                            margin-top: 30px;
                                        }
                                        .pipeline-card {
                                            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                                            padding: 30px;
                                            border-radius: 15px;
                                            color: white;
                                            cursor: pointer;
                                            transition: transform 0.2s, box-shadow 0.2s;
                                        }
                                        .pipeline-card:hover {
                                            transform: translateY(-5px);
                                            box-shadow: 0 10px 30px rgba(0,0,0,0.3);
                                        }
                                        .pipeline-name {
                                            font-size: 1.5em;
                                            font-weight: bold;
                                            margin-bottom: 10px;
                                        }
                                        .pipeline-desc {
                                            opacity: 0.9;
                                            font-size: 0.9em;
                                        }
                                        .run-btn {
                                            background: white;
                                            color: #667eea;
                                            border: none;
                                            padding: 15px 30px;
                                            border-radius: 10px;
                                            font-size: 1em;
                                            font-weight: bold;
                                            cursor: pointer;
                                            margin-top: 20px;
                                            transition: all 0.2s;
                                        }
                                        .run-btn:hover {
                                            transform: scale(1.05);
                                            box-shadow: 0 5px 15px rgba(0,0,0,0.2);
                                        }
                                        #results {
                                            margin-top: 40px;
                                            padding: 30px;
                                            background: #f8f9fa;
                                            border-radius: 15px;
                                            display: none;
                                        }
                                        .agent-result {
                                            background: white;
                                            padding: 20px;
                                            border-radius: 10px;
                                            margin-bottom: 20px;
                                            border-left: 4px solid #667eea;
                                        }
                                        .agent-name {
                                            font-weight: bold;
                                            color: #667eea;
                                            margin-bottom: 10px;
                                        }
                                        pre {
                                            background: #f8f9fa;
                                            padding: 15px;
                                            border-radius: 5px;
                                            overflow-x: auto;
                                        }
                                    """)
                                }
                            }
                        }
                        body {
                            div(classes = "container") {
                                h1 { +"🤖 Cotor" }
                                div(classes = "subtitle") {
                                    +"AI CLI Master-Agent System"
                                }
                                
                                div(classes = "create-section") {
                                    h2 { +"➕ Create New Pipeline" }
                                    form {
                                        id = "createForm"
                                        input {
                                            type = InputType.text
                                            name = "name"
                                            placeholder = "Pipeline name (e.g., my-task)"
                                            required = true
                                        }
                                        input {
                                            type = InputType.text
                                            name = "description"
                                            placeholder = "Description"
                                        }
                                        div(classes = "agent-tasks") {
                                            id = "agentTasks"
                                            h3 { +"AI Tasks:" }
                                            div(classes = "agent-task-item") {
                                                select {
                                                    name = "agent_0"
                                                    classes = setOf("agent-select-dropdown")
                                                    listOf("claude", "codex", "gemini", "copilot", "cursor", "opencode").forEach { agent ->
                                                        option {
                                                            value = agent
                                                            +agent.replaceFirstChar { it.uppercase() }
                                                        }
                                                    }
                                                }
                                                textArea {
                                                    name = "prompt_0"
                                                    placeholder = "What should this AI do?"
                                                    rows = "2"
                                                    required = true
                                                }
                                            }
                                        }
                                        button(classes = "add-btn") {
                                            type = ButtonType.button
                                            attributes["onclick"] = "addAgentTask()"
                                            +"+ Add Another AI"
                                        }
                                        button(classes = "run-btn") {
                                            type = ButtonType.submit
                                            +"Create & Run"
                                        }
                                    }
                                }
                                
                                div {
                                    id = "pipelines"
                                    +"Loading pipelines..."
                                }
                                
                                div {
                                    id = "results"
                                }
                            }
                            
                            script {
                                unsafe {
                                    raw("""
                                        let taskCount = 1;
                                        
                                        function addAgentTask() {
                                            const container = document.getElementById('agentTasks');
                                            const newTask = document.createElement('div');
                                            newTask.className = 'agent-task-item';
                                            newTask.innerHTML = `
                                                <select name="agent_${'$'}{taskCount}" class="agent-select-dropdown">
                                                    <option value="claude">Claude</option>
                                                    <option value="codex">Codex</option>
                                                    <option value="gemini">Gemini</option>
                                                    <option value="copilot">Copilot</option>
                                                    <option value="cursor">Cursor</option>
                                                    <option value="opencode">OpenCode</option>
                                                </select>
                                                <textarea name="prompt_${'$'}{taskCount}" placeholder="What should this AI do?" rows="2" required></textarea>
                                            `;
                                            container.appendChild(newTask);
                                            taskCount++;
                                        }
                                        
                                        // Handle form submission
                                        document.getElementById('createForm').addEventListener('submit', async (e) => {
                                            e.preventDefault();
                                            const formData = new FormData(e.target);
                                            
                                            // Collect agent tasks
                                            const tasks = [];
                                            for (let i = 0; i < taskCount; i++) {
                                                const agent = formData.get('agent_' + i);
                                                const prompt = formData.get('prompt_' + i);
                                                if (agent && prompt) {
                                                    tasks.push({ agent, prompt });
                                                }
                                            }
                                            
                                            const data = {
                                                name: formData.get('name'),
                                                description: formData.get('description'),
                                                tasks: tasks
                                            };
                                            
                                            const res = await fetch('/api/pipelines/create', {
                                                method: 'POST',
                                                headers: { 'Content-Type': 'application/json' },
                                                body: JSON.stringify(data)
                                            });
                                            
                                            const result = await res.json();
                                            if (result.success) {
                                                alert('Pipeline created: ' + result.file);
                                                loadPipelines();
                                                e.target.reset();
                                                taskCount = 1;
                                                // Auto-run the new pipeline
                                                runPipeline(formData.get('name'));
                                            } else {
                                                alert('Error: ' + result.error);
                                            }
                                        });
                                        
                                        async function loadPipelines() {
                                            const res = await fetch('/api/pipelines');
                                            const pipelines = await res.json();
                                            
                                            if (pipelines.length === 0) {
                                                document.getElementById('pipelines').innerHTML = '<p>No pipelines found. Create one above!</p>';
                                                return;
                                            }
                                            
                                            const html = '<h2>📋 Available Pipelines</h2><div class="pipeline-grid">' + 
                                                pipelines.map(p => `
                                                    <div class="pipeline-card">
                                                        <div class="pipeline-name">${'$'}{p.name}</div>
                                                        <div class="pipeline-desc">${'$'}{p.description}</div>
                                                        <small style="opacity: 0.7">${'$'}{p.configFile}</small>
                                                        <button class="run-btn" onclick="runPipeline('${'$'}{p.name}')">
                                                            ▶ Run Pipeline
                                                        </button>
                                                    </div>
                                                `).join('') +
                                                '</div>';
                                            
                                            document.getElementById('pipelines').innerHTML = html;
                                        }
                                        
                                        async function runPipeline(name) {
                                            const results = document.getElementById('results');
                                            results.style.display = 'block';
                                            results.innerHTML = '<h2>🚀 Running: ' + name + '</h2><p>Please wait...</p>';
                                            
                                            const res = await fetch('/api/run/' + name, { method: 'POST' });
                                            const data = await res.json();
                                            
                                            let html = '<h2>✅ Completed in ' + data.totalDuration + 'ms</h2>';
                                            html += '<p>Success: ' + data.successCount + '/' + data.totalAgents + '</p>';
                                            
                                            data.results.forEach(r => {
                                                html += `
                                                    <div class="agent-result">
                                                        <div class="agent-name">${'$'}{r.agentName} (${'$'}{r.duration}ms)</div>
                                                        <pre>${'$'}{r.output || r.error}</pre>
                                                    </div>
                                                `;
                                            });
                                            
                                            results.innerHTML = html;
                                        }
                                        
                                        loadPipelines();
                                    """)
                                }
                            }
                        }
                    }
                }

                // API endpoints
                get("/api/pipelines") {
                    try {
                        val allPipelines = mutableListOf<Map<String, String>>()
                        
                        // Find all cotor pipeline YAML files
                        // Look for files with "# cotor-pipeline" marker or in test/ directory
                        val yamlFiles = java.io.File(".").walkTopDown()
                            .filter { it.extension in listOf("yaml", "yml") }
                            .filter { !it.path.contains("node_modules") && !it.path.contains(".git") }
                            .filter { file ->
                                // Check if file contains cotor-pipeline marker or is in test/
                                file.path.contains("test/") || 
                                file.readText().contains("# cotor-pipeline") ||
                                file.readText().contains("version: \"1.0\"")
                            }
                            .toList()
                        
                        for (yamlFile in yamlFiles) {
                            try {
                                val config = configRepository.loadConfig(yamlFile.toPath())
                                config.pipelines.forEach { pipeline ->
                                    allPipelines.add(mapOf(
                                        "name" to pipeline.name,
                                        "description" to pipeline.description,
                                        "configFile" to yamlFile.path
                                    ))
                                }
                            } catch (e: Exception) {
                                // Skip invalid files
                            }
                        }
                        
                        call.respond(allPipelines)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                    }
                }

                post("/api/run/{name}") {
                    val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    
                    try {
                        // Find config file with this pipeline
                        val configPaths = listOf(
                            Path("cotor.yaml"),
                            Path("test/multi-compare.yaml"),
                            Path("test/creative-collab.yaml")
                        )
                        
                        var foundConfig: com.cotor.model.CotorConfig? = null
                        var foundPipeline: com.cotor.model.Pipeline? = null
                        
                        for (configPath in configPaths) {
                            try {
                                val config = configRepository.loadConfig(configPath)
                                val pipeline = config.pipelines.find { it.name == name }
                                if (pipeline != null) {
                                    foundConfig = config
                                    foundPipeline = pipeline
                                    break
                                }
                            } catch (e: Exception) {
                                // Skip
                            }
                        }
                        
                        if (foundConfig == null || foundPipeline == null) {
                            return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Pipeline not found"))
                        }
                        
                        foundConfig.agents.forEach { agentRegistry.registerAgent(it) }
                        val result = orchestrator.executePipeline(foundPipeline)
                        
                        call.respond(mapOf(
                            "totalAgents" to result.totalAgents,
                            "successCount" to result.successCount,
                            "totalDuration" to result.totalDuration,
                            "results" to result.results.map {
                                mapOf(
                                    "agentName" to it.agentName,
                                    "duration" to it.duration,
                                    "output" to it.output,
                                    "error" to it.error
                                )
                            }
                        ))
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                    }
                }
                
                // Create new pipeline
                post("/api/pipelines/create") {
                    try {
                        @Serializable
                        data class TaskData(val agent: String, val prompt: String)
                        @Serializable
                        data class CreateRequest(val name: String, val description: String, val tasks: List<TaskData>)
                        
                        val request = call.receive<CreateRequest>()
                        val pipelineName = request.name
                        val description = request.description
                        val tasks = request.tasks
                        
                        if (tasks.isEmpty()) {
                            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "At least one task required"))
                        }
                        
                        val agents = tasks.map { it.agent }.distinct()
                        
                        // Create YAML content
                        val yamlContent = """
# cotor-pipeline
version: "1.0"

agents:
${agents.joinToString("\n") { """  - name: $it
    pluginClass: com.cotor.data.plugin.${it.replaceFirstChar { c -> c.uppercase() }}Plugin
    timeout: 90000""" }}

pipelines:
  - name: $pipelineName
    description: "$description"
    executionMode: PARALLEL
    stages:
${tasks.mapIndexed { i, task -> 
    val enhancedPrompt = "${task.prompt} - 반드시 파일을 생성하고 코드를 작성해주세요. 설명만 하지 말고 실제 파일을 만들어주세요."
    """      - id: ${task.agent}-task-${i+1}
        agent:
          name: ${task.agent}
        input: "$enhancedPrompt""""
}.joinToString("\n")}

security:
  useWhitelist: true
  allowedExecutables: [${agents.joinToString(", ")}]
  allowedDirectories: [/usr/local/bin, /opt/homebrew/bin, /Users/heodongun/Desktop/cotor/test]
                        """.trimIndent()
                        
                        // Save to file
                        val fileName = "test/${pipelineName}.yaml"
                        java.io.File(fileName).writeText(yamlContent)
                        
                        call.respond(mapOf(
                            "success" to true,
                            "message" to "Pipeline created: $fileName",
                            "file" to fileName
                        ))
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
                    }
                }
            }
        }.start(wait = true)
    }
}
