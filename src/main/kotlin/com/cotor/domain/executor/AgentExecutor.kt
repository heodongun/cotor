package com.cotor.domain.executor

/**
 * File overview for AgentExecutor.
 *
 * This file belongs to the execution layer that validates and invokes individual agents.
 * It groups declarations around agent executor so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.data.plugin.PluginLoader
import com.cotor.data.process.ProcessManager
import com.cotor.model.*
import com.cotor.monitoring.NoopObservabilityService
import com.cotor.monitoring.ObservabilityService
import com.cotor.runtime.actions.ActionEvidence
import com.cotor.runtime.actions.ActionExecutionService
import com.cotor.runtime.actions.ActionKind
import com.cotor.runtime.actions.ActionRequest
import com.cotor.runtime.actions.ActionScope
import com.cotor.runtime.actions.ActionSubject
import com.cotor.runtime.durable.DurableRuntimeService
import com.cotor.security.SecurityValidator
import kotlinx.coroutines.*
import org.slf4j.Logger
import java.time.Instant

/**
 * Interface for agent execution
 */
interface AgentExecutor {
    /**
     * Execute an agent
     * @param agent AgentConfig to execute
     * @param input Optional input data
     * @return AgentResult containing execution results
     */
    suspend fun executeAgent(
        agent: AgentConfig,
        input: String?,
        metadata: AgentExecutionMetadata = AgentExecutionMetadata()
    ): AgentResult

    /**
     * Execute an agent with retry policy
     * @param agent AgentConfig to execute
     * @param input Optional input data
     * @param retryPolicy Retry policy to apply
     * @return AgentResult containing execution results
     */
    suspend fun executeWithRetry(
        agent: AgentConfig,
        input: String?,
        retryPolicy: RetryPolicy,
        metadata: AgentExecutionMetadata = AgentExecutionMetadata()
    ): AgentResult
}

/**
 * Default implementation of agent executor
 */
class DefaultAgentExecutor(
    private val processManager: ProcessManager,
    private val pluginLoader: PluginLoader,
    private val securityValidator: SecurityValidator,
    private val logger: Logger,
    private val observability: ObservabilityService = NoopObservabilityService,
    private val durableRuntimeService: DurableRuntimeService = DurableRuntimeService(),
    private val actionExecutionService: ActionExecutionService = ActionExecutionService(durableRuntimeService = durableRuntimeService, logger = logger)
) : AgentExecutor {
    private data class RuntimeServices(
        val durableRuntimeService: DurableRuntimeService,
        val actionExecutionService: ActionExecutionService
    )

    private constructor(
        processManager: ProcessManager,
        pluginLoader: PluginLoader,
        securityValidator: SecurityValidator,
        logger: Logger,
        observability: ObservabilityService,
        runtimeServices: RuntimeServices
    ) : this(
        processManager = processManager,
        pluginLoader = pluginLoader,
        securityValidator = securityValidator,
        logger = logger,
        observability = observability,
        durableRuntimeService = runtimeServices.durableRuntimeService,
        actionExecutionService = runtimeServices.actionExecutionService
    )

    constructor(
        processManager: ProcessManager,
        pluginLoader: PluginLoader,
        securityValidator: SecurityValidator,
        logger: Logger
    ) : this(
        processManager = processManager,
        pluginLoader = pluginLoader,
        securityValidator = securityValidator,
        logger = logger,
        observability = NoopObservabilityService,
        runtimeServices = defaultRuntimeServices(logger)
    )

    constructor(
        processManager: ProcessManager,
        pluginLoader: PluginLoader,
        securityValidator: SecurityValidator,
        logger: Logger,
        observability: ObservabilityService
    ) : this(
        processManager = processManager,
        pluginLoader = pluginLoader,
        securityValidator = securityValidator,
        logger = logger,
        observability = observability,
        runtimeServices = defaultRuntimeServices(logger)
    )

    companion object {
        private fun defaultRuntimeServices(logger: Logger): RuntimeServices {
            val durableRuntimeService = DurableRuntimeService()
            return RuntimeServices(
                durableRuntimeService = durableRuntimeService,
                actionExecutionService = ActionExecutionService(
                    durableRuntimeService = durableRuntimeService,
                    logger = logger
                )
            )
        }
    }

    private val isDesktopTui = System.getenv("COTOR_DESKTOP_TUI") == "1"

    override suspend fun executeAgent(
        agent: AgentConfig,
        input: String?,
        metadata: AgentExecutionMetadata
    ): AgentResult {
        logger.debug("Executing agent: ${agent.name}")
        val observation = observability.startAgent(agent.name, metadata)
        val startedAtMs = System.currentTimeMillis()

        return withContext(Dispatchers.IO) {
            try {
                // Validate the static agent configuration before any plugin code or
                // child process is given a chance to run.
                securityValidator.validate(agent)

                // Resolve the plugin implementation for this agent name.
                val plugin = pluginLoader.loadPlugin(agent.pluginClass)

                // Let the plugin reject malformed input early with a clear validation error
                // instead of failing later inside a CLI or network call.
                val validationResult = plugin.validateInput(input)
                if (validationResult is ValidationResult.Failure) {
                    throw ValidationException(
                        "Input validation failed for agent ${agent.name}",
                        validationResult.errors
                    )
                }

                // Collapse the agent config plus desktop/pipeline metadata into one
                // immutable execution snapshot that the plugin can rely on.
                val context = ExecutionContext(
                    agentName = agent.name,
                    input = input,
                    parameters = agent.parameters,
                    environment = agent.environment,
                    timeout = agent.timeout,
                    repoRoot = metadata.repoRoot,
                    workspaceId = metadata.workspaceId,
                    taskId = metadata.taskId,
                    agentId = metadata.agentId,
                    baseBranch = metadata.baseBranch,
                    branchName = metadata.branchName,
                    workingDirectory = metadata.workingDirectory,
                    pipelineContext = metadata.pipelineContext,
                    currentStageId = metadata.stageId,
                    onProcessStarted = metadata.onProcessStarted,
                    validateCommand = securityValidator::validateCommand
                )

                // Time only the plugin body itself so reported duration reflects the
                // actual execution window seen by the child process or API call.
                val startTime = System.currentTimeMillis()
                val actionRequest = ActionRequest(
                    kind = ActionKind.AGENT_EXEC,
                    label = "agent.execute:${agent.name}",
                    scope = if (metadata.pipelineContext != null) ActionScope.RUN else ActionScope.GLOBAL,
                    subject = ActionSubject(
                        runId = metadata.pipelineContext?.metadata?.get("durableRunId")?.toString(),
                        taskId = metadata.taskId,
                        agentName = agent.name
                    ),
                    replaySafe = true,
                    approvalRequiredOnReplay = false,
                    metadata = buildMap {
                        put("agentName", agent.name)
                        metadata.stageId?.let { put("stageId", it) }
                        metadata.taskId?.let { put("taskId", it) }
                        metadata.pipelineContext?.metadata?.get("configPath")?.toString()?.let { put("configPath", it) }
                    }
                )
                val pluginOutput = actionExecutionService.run(
                    request = actionRequest,
                    onSuccess = { output ->
                        ActionEvidence(
                            branchName = metadata.branchName
                        ).copy(
                            filePaths = emptyList(),
                            pullRequestUrl = null
                        )
                    }
                ) {
                    withTimeout(agent.timeout) {
                        plugin.execute(context, processManager)
                    }
                }
                val duration = System.currentTimeMillis() - startTime

                val result = AgentResult(
                    agentName = agent.name,
                    isSuccess = true,
                    output = pluginOutput.output,
                    error = null,
                    duration = duration,
                    metadata = buildMap {
                        put("executedAt", Instant.now().toString())
                        // Mirror the pid into the string metadata map because some
                        // existing consumers still only inspect this generic field bag.
                        pluginOutput.processId?.let { put("processId", it.toString()) }
                    },
                    processId = pluginOutput.processId
                )
                result.copy(metadata = result.metadata + observability.completeAgent(observation, metadata, result))
            } catch (e: TimeoutCancellationException) {
                logAgentFailure("Agent timeout: ${agent.name}", e)
                AgentResult(
                    agentName = agent.name,
                    isSuccess = false,
                    output = null,
                    error = "Execution timeout after ${agent.timeout}ms",
                    duration = agent.timeout,
                    metadata = observability.failAgent(observation, metadata, System.currentTimeMillis() - startedAtMs, e)
                )
            } catch (e: ProcessExecutionException) {
                logAgentFailure("Agent process execution failed: ${agent.name}", e)
                val stderr = e.stderr.trim().ifEmpty { e.stdout.trim() }.ifEmpty { "(no stderr)" }
                val message = e.message?.trim().orEmpty().ifEmpty { "Process failed" }
                AgentResult(
                    agentName = agent.name,
                    isSuccess = false,
                    output = e.stdout.takeIf { it.isNotBlank() },
                    error = "$message (exit=${e.exitCode}): $stderr",
                    duration = 0,
                    metadata = observability.failAgent(observation, metadata, System.currentTimeMillis() - startedAtMs, e)
                )
            } catch (e: Exception) {
                logAgentFailure("Agent execution failed: ${agent.name}", e)
                AgentResult(
                    agentName = agent.name,
                    isSuccess = false,
                    output = null,
                    error = e.message ?: "Unknown error",
                    duration = 0,
                    metadata = observability.failAgent(observation, metadata, System.currentTimeMillis() - startedAtMs, e)
                )
            }
        }
    }

    private fun logAgentFailure(message: String, error: Throwable) {
        if (isDesktopTui) {
            // The interactive desktop terminal already echoes a compact user-facing
            // error line, so duplicating the full stack trace there only makes the
            // PTY look frozen or broken.
            logger.warn("$message: ${error.message ?: "unknown error"}")
        } else {
            logger.error(message, error)
        }
    }

    override suspend fun executeWithRetry(
        agent: AgentConfig,
        input: String?,
        retryPolicy: RetryPolicy,
        metadata: AgentExecutionMetadata
    ): AgentResult {
        var lastResult: AgentResult? = null
        var attempt = 0

        while (attempt <= retryPolicy.maxRetries) {
            // Re-run the full execution path so each retry gets the same validation,
            // plugin resolution, and metadata setup as the initial attempt.
            lastResult = executeAgent(agent, input, metadata)

            if (lastResult.isSuccess) {
                return lastResult
            }

            attempt++
            if (attempt <= retryPolicy.maxRetries) {
                logger.warn("Retry attempt $attempt for agent: ${agent.name}")
                // Exponential backoff is computed here rather than in the plugin layer
                // so retry policy stays consistent regardless of which agent is running.
                val delayMs = (retryPolicy.retryDelay * Math.pow(retryPolicy.backoffMultiplier, attempt.toDouble())).toLong()
                delay(delayMs)
            }
        }

        return lastResult ?: throw IllegalStateException("No result after retries")
    }
}
