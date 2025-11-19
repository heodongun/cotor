package com.cotor.domain.executor

import com.cotor.data.plugin.PluginLoader
import com.cotor.data.process.ProcessManager
import com.cotor.model.*
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
    private val logger: Logger
) : AgentExecutor {

    override suspend fun executeAgent(
        agent: AgentConfig,
        input: String?,
        metadata: AgentExecutionMetadata
    ): AgentResult {
        logger.debug("Executing agent: ${agent.name}")

        return withContext(Dispatchers.IO) {
            try {
                // Security validation
                securityValidator.validate(agent)

                // Load plugin
                val plugin = pluginLoader.loadPlugin(agent.pluginClass)

                // Validate input
                val validationResult = plugin.validateInput(input)
                if (validationResult is ValidationResult.Failure) {
                    throw ValidationException(
                        "Input validation failed for agent ${agent.name}",
                        validationResult.errors
                    )
                }

                // Create execution context
                val context = ExecutionContext(
                    agentName = agent.name,
                    input = input,
                    parameters = agent.parameters,
                    environment = agent.environment,
                    timeout = agent.timeout,
                    pipelineContext = metadata.pipelineContext,
                    currentStageId = metadata.stageId
                )

                // Execute agent
                val startTime = System.currentTimeMillis()
                val output = withTimeout(agent.timeout) {
                    plugin.execute(context, processManager)
                }
                val duration = System.currentTimeMillis() - startTime

                AgentResult(
                    agentName = agent.name,
                    isSuccess = true,
                    output = output,
                    error = null,
                    duration = duration,
                    metadata = mapOf("executedAt" to Instant.now().toString())
                )

            } catch (e: TimeoutCancellationException) {
                logger.error("Agent timeout: ${agent.name}", e)
                AgentResult(
                    agentName = agent.name,
                    isSuccess = false,
                    output = null,
                    error = "Execution timeout after ${agent.timeout}ms",
                    duration = agent.timeout,
                    metadata = emptyMap()
                )
            } catch (e: Exception) {
                logger.error("Agent execution failed: ${agent.name}", e)
                AgentResult(
                    agentName = agent.name,
                    isSuccess = false,
                    output = null,
                    error = e.message ?: "Unknown error",
                    duration = 0,
                    metadata = emptyMap()
                )
            }
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
            lastResult = executeAgent(agent, input, metadata)

            if (lastResult.isSuccess) {
                return lastResult
            }

            attempt++
            if (attempt <= retryPolicy.maxRetries) {
                logger.warn("Retry attempt $attempt for agent: ${agent.name}")
                val delayMs = (retryPolicy.retryDelay * Math.pow(retryPolicy.backoffMultiplier, attempt.toDouble())).toLong()
                delay(delayMs)
            }
        }

        return lastResult ?: throw IllegalStateException("No result after retries")
    }
}
