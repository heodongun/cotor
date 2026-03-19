package com.cotor.app

/**
 * File overview for ExecutionBackendRequest.
 *
 * This file belongs to the app layer for the desktop shell and localhost app-server surface.
 * It groups declarations around execution backends so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import com.cotor.domain.executor.AgentExecutor
import com.cotor.model.AgentConfig
import com.cotor.model.AgentExecutionMetadata
import com.cotor.model.AgentResult
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class ExecutionBackendRequest(
    val agent: AgentConfig,
    val prompt: String,
    val metadata: AgentExecutionMetadata,
    val effectiveConfig: BackendConnectionConfig
)

interface ExecutionBackend {
    val kind: ExecutionBackendKind
    val displayName: String
    val capabilities: ExecutionBackendCapabilities

    suspend fun health(config: BackendConnectionConfig): ExecutionBackendStatus

    suspend fun execute(request: ExecutionBackendRequest): AgentResult
}

class LocalCotorBackend(
    private val agentExecutor: AgentExecutor
) : ExecutionBackend {
    override val kind: ExecutionBackendKind = ExecutionBackendKind.LOCAL_COTOR
    override val displayName: String = "Local Cotor"
    override val capabilities: ExecutionBackendCapabilities = ExecutionBackendCapabilities(
        canStreamEvents = true,
        canResumeRuns = true,
        canSpawnParallelAgents = true,
        canPublishPullRequests = true
    )

    override suspend fun health(config: BackendConnectionConfig): ExecutionBackendStatus =
        ExecutionBackendStatus(
            kind = kind,
            displayName = displayName,
            health = if (config.enabled) "healthy" else "disabled",
            message = if (config.enabled) "Local app-server and process-backed execution are available." else "Disabled in settings.",
            config = config,
            capabilities = capabilities
        )

    override suspend fun execute(request: ExecutionBackendRequest): AgentResult =
        agentExecutor.executeAgent(
            agent = request.agent,
            input = request.prompt,
            metadata = request.metadata
        )
}

class CodexAppServerBackend(
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ExecutionBackend {
    override val kind: ExecutionBackendKind = ExecutionBackendKind.CODEX_APP_SERVER
    override val displayName: String = "Codex App Server"
    override val capabilities: ExecutionBackendCapabilities = ExecutionBackendCapabilities(
        canStreamEvents = true,
        canResumeRuns = true,
        canSpawnParallelAgents = true,
        canPublishPullRequests = true
    )

    private val client = HttpClient.newBuilder().build()

    override suspend fun health(config: BackendConnectionConfig): ExecutionBackendStatus = withContext(Dispatchers.IO) {
        if (!config.enabled) {
            return@withContext ExecutionBackendStatus(
                kind = kind,
                displayName = displayName,
                health = "disabled",
                message = "Disabled in settings.",
                lifecycleState = BackendLifecycleState.STOPPED,
                managed = config.launchMode == BackendLaunchMode.MANAGED,
                config = config,
                capabilities = capabilities
            )
        }
        val baseUrl = config.baseUrl?.trim().orEmpty()
        if (baseUrl.isBlank()) {
            return@withContext ExecutionBackendStatus(
                kind = kind,
                displayName = displayName,
                health = "degraded",
                message = if (config.launchMode == BackendLaunchMode.MANAGED) {
                    "Managed Codex app server is not running."
                } else {
                    "Attached Codex app server URL is not configured."
                },
                lifecycleState = if (config.launchMode == BackendLaunchMode.MANAGED) {
                    BackendLifecycleState.STOPPED
                } else {
                    BackendLifecycleState.FAILED
                },
                managed = config.launchMode == BackendLaunchMode.MANAGED,
                port = config.port,
                config = config,
                capabilities = capabilities
            )
        }
        runCatching {
            val request = requestBuilder(baseUrl + config.healthCheckPath, config)
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val healthy = response.statusCode() in 200..299
            ExecutionBackendStatus(
                kind = kind,
                displayName = displayName,
                health = if (healthy) "healthy" else "degraded",
                message = if (healthy) "Connected to Codex app server." else "Health check returned HTTP ${response.statusCode()}",
                lifecycleState = if (config.launchMode == BackendLaunchMode.MANAGED) BackendLifecycleState.RUNNING else BackendLifecycleState.ATTACHED,
                managed = config.launchMode == BackendLaunchMode.MANAGED,
                port = config.port,
                config = config,
                capabilities = capabilities
            )
        }.getOrElse { error ->
            ExecutionBackendStatus(
                kind = kind,
                displayName = displayName,
                health = "offline",
                message = error.message ?: "Failed to reach Codex app server.",
                lifecycleState = BackendLifecycleState.FAILED,
                managed = config.launchMode == BackendLaunchMode.MANAGED,
                port = config.port,
                lastError = error.message,
                config = config,
                capabilities = capabilities
            )
        }
    }

    override suspend fun execute(request: ExecutionBackendRequest): AgentResult = withContext(Dispatchers.IO) {
        val config = request.effectiveConfig
        val baseUrl = config.baseUrl?.trim().orEmpty()
        if (baseUrl.isBlank()) {
            return@withContext AgentResult(
                agentName = request.agent.name,
                isSuccess = false,
                output = null,
                error = "Codex app server base URL is not configured",
                duration = 0,
                metadata = mapOf("backend" to kind.name)
            )
        }
        runCatching {
            val payload = RemoteExecutionRequest(
                agentName = request.agent.name,
                pluginClass = request.agent.pluginClass,
                prompt = request.prompt,
                parameters = request.agent.parameters,
                environment = request.agent.environment,
                timeoutMs = request.agent.timeout,
                repoRoot = request.metadata.repoRoot?.toString(),
                workspaceId = request.metadata.workspaceId,
                taskId = request.metadata.taskId,
                agentId = request.metadata.agentId,
                baseBranch = request.metadata.baseBranch,
                branchName = request.metadata.branchName,
                workingDirectory = request.metadata.workingDirectory?.toString()
            )
            val body = json.encodeToString(RemoteExecutionRequest.serializer(), payload)
            val response = client.send(
                requestBuilder("$baseUrl/execute", config)
                    .header(HttpHeaders.ContentType, "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            if (response.statusCode() !in 200..299) {
                return@runCatching AgentResult(
                    agentName = request.agent.name,
                    isSuccess = false,
                    output = null,
                    error = "Codex app server returned HTTP ${response.statusCode()}: ${response.body()}",
                    duration = 0,
                    metadata = mapOf("backend" to kind.name)
                )
            }
            val decoded = json.decodeFromString(RemoteExecutionResponse.serializer(), response.body())
            AgentResult(
                agentName = request.agent.name,
                isSuccess = decoded.success,
                output = decoded.output,
                error = decoded.error,
                duration = decoded.durationMs ?: 0,
                metadata = (decoded.metadata ?: emptyMap()) + mapOf("backend" to kind.name),
                processId = decoded.processId
            )
        }.getOrElse { error ->
            AgentResult(
                agentName = request.agent.name,
                isSuccess = false,
                output = null,
                error = error.message ?: "Failed to execute against Codex app server",
                duration = 0,
                metadata = mapOf("backend" to kind.name)
            )
        }
    }

    private fun requestBuilder(url: String, config: BackendConnectionConfig): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(java.time.Duration.ofSeconds(config.timeoutSeconds.toLong()))
        if (config.authMode.equals("bearer", ignoreCase = true) && !config.token.isNullOrBlank()) {
            builder.header(HttpHeaders.Authorization, "Bearer ${config.token}")
        }
        return builder
    }
}

@Serializable
private data class RemoteExecutionRequest(
    val agentName: String,
    val pluginClass: String,
    val prompt: String,
    val parameters: Map<String, String> = emptyMap(),
    val environment: Map<String, String> = emptyMap(),
    val timeoutMs: Long,
    val repoRoot: String? = null,
    val workspaceId: String? = null,
    val taskId: String? = null,
    val agentId: String? = null,
    val baseBranch: String? = null,
    val branchName: String? = null,
    val workingDirectory: String? = null
)

@Serializable
private data class RemoteExecutionResponse(
    val success: Boolean,
    val output: String? = null,
    val error: String? = null,
    val durationMs: Long? = null,
    val processId: Long? = null,
    val metadata: Map<String, String>? = null
)
