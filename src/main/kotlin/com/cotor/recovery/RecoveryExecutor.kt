package com.cotor.recovery

import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.executor.AgentExecutor
import com.cotor.model.*
import com.cotor.validation.output.OutputValidator
import kotlinx.coroutines.delay
import org.slf4j.Logger

/**
 * Executes a stage with retry/fallback/skip recovery semantics.
 */
class RecoveryExecutor(
    private val agentExecutor: AgentExecutor,
    private val agentRegistry: AgentRegistry,
    private val outputValidator: OutputValidator,
    private val logger: Logger
) {

    suspend fun executeWithRecovery(
        stage: PipelineStage,
        input: String?,
        pipelineContext: PipelineContext?
    ): AgentResult {
        val recovery = stage.recovery ?: RecoveryConfig()
        val stageAgent = stage.agent ?: throw IllegalArgumentException(
            "Stage ${stage.id} requires an agent for execution"
        )
        val primaryAgent = agentRegistry.getAgent(stageAgent.name)
            ?: throw IllegalArgumentException("Agent not found: ${stageAgent.name}")

        return when (recovery.strategy) {
            RecoveryStrategy.RETRY -> executeWithRetry(stage, primaryAgent, input, pipelineContext, recovery)
            RecoveryStrategy.FALLBACK -> executeWithFallback(stage, primaryAgent, input, pipelineContext, recovery)
            RecoveryStrategy.RETRY_THEN_FALLBACK -> executeRetryThenFallback(stage, primaryAgent, input, pipelineContext, recovery)
            RecoveryStrategy.SKIP -> executeWithSkip(stage, primaryAgent, input, pipelineContext, recovery)
            RecoveryStrategy.ABORT -> executeWithoutRecovery(stage, primaryAgent, input, pipelineContext)
        }
    }

    private suspend fun executeWithoutRecovery(
        stage: PipelineStage,
        agentConfig: AgentConfig,
        input: String?,
        pipelineContext: PipelineContext?
    ): AgentResult {
        return runAgent(agentConfig, stage, input, pipelineContext)
    }

    private suspend fun executeWithRetry(
        stage: PipelineStage,
        agentConfig: AgentConfig,
        input: String?,
        pipelineContext: PipelineContext?,
        recovery: RecoveryConfig
    ): AgentResult {
        val totalAttempts = 1 + recovery.maxRetries
        var lastResult: AgentResult? = null
        var currentDelay = recovery.retryDelayMs.toDouble()

        for (attempt in 1..totalAttempts) {
            val result = runAgent(agentConfig, stage, input, pipelineContext)
            val retries = attempt - 1
            val resultWithAttempt = result.copy(
                metadata = result.metadata + ("retries" to retries.toString())
            )

            if (resultWithAttempt.isSuccess) {
                if (retries > 0) {
                    logger.info("Stage ${stage.id} succeeded after $retries retries.")
                }
                return resultWithAttempt
            }

            lastResult = resultWithAttempt

            if (attempt < totalAttempts) {
                val matchedCondition = getMatchedRetryCondition(resultWithAttempt.error, recovery.retryOn)
                if (matchedCondition != null) {
                    val delayMs = currentDelay.toLong()
                    logger.warn(
                        "Stage ${stage.id} failed on attempt $attempt/$totalAttempts with error: '${result.error}'. " +
                                "Matched retry condition: '$matchedCondition'. Retrying in ${delayMs}ms..."
                    )
                    delay(delayMs)

                    if (recovery.backoffStrategy == BackoffStrategy.EXPONENTIAL) {
                        currentDelay *= recovery.backoffMultiplier
                    }
                } else {
                    logger.warn(
                        "Stage ${stage.id} failed on attempt $attempt/$totalAttempts with non-retryable error: '${result.error}'"
                    )
                    return resultWithAttempt
                }
            }
        }

        logger.error("Stage ${stage.id} failed after exhausting all $totalAttempts attempts.")
        return lastResult ?: failureResult(
            agentConfig.name,
            "Stage ${stage.id} failed after all retry attempts"
        )
    }

    private suspend fun executeWithFallback(
        stage: PipelineStage,
        primaryAgent: AgentConfig,
        input: String?,
        pipelineContext: PipelineContext?,
        recovery: RecoveryConfig
    ): AgentResult {
        val primaryResult = runAgent(primaryAgent, stage, input, pipelineContext)
        if (primaryResult.isSuccess) {
            return primaryResult
        }

        logger.warn("Primary agent ${primaryAgent.name} failed for stage ${stage.id}. Trying fallbacks...")
        for ((index, fallbackName) in recovery.fallbackAgents.withIndex()) {
            val fallback = agentRegistry.getAgent(fallbackName) ?: continue
            logger.info("Attempting fallback agent ${index + 1}/${recovery.fallbackAgents.size}: $fallbackName for stage ${stage.id}")
            val result = runAgent(fallback, stage, input, pipelineContext)
            if (result.isSuccess) {
                return result
            }
        }

        return primaryResult
    }

    private suspend fun executeRetryThenFallback(
        stage: PipelineStage,
        primaryAgent: AgentConfig,
        input: String?,
        pipelineContext: PipelineContext?,
        recovery: RecoveryConfig
    ): AgentResult {
        val retryResult = executeWithRetry(stage, primaryAgent, input, pipelineContext, recovery)
        if (retryResult.isSuccess) {
            return retryResult
        }

        if (recovery.fallbackAgents.isEmpty()) {
            return retryResult
        }

        return executeWithFallback(stage, primaryAgent, input, pipelineContext, recovery)
    }

    private suspend fun executeWithSkip(
        stage: PipelineStage,
        agentConfig: AgentConfig,
        input: String?,
        pipelineContext: PipelineContext?,
        recovery: RecoveryConfig
    ): AgentResult {
        return try {
            runAgent(agentConfig, stage, input, pipelineContext)
        } catch (e: Exception) {
            logger.warn("Optional stage ${stage.id} failed. Strategy=SKIP. Reason=${e.message}")
            failureResult(agentConfig.name, "Stage skipped: ${e.message}")
        }
    }

    private suspend fun runAgent(
        agentConfig: AgentConfig,
        stage: PipelineStage,
        input: String?,
        pipelineContext: PipelineContext?
    ): AgentResult {
        val metadata = AgentExecutionMetadata(
            pipelineContext = pipelineContext,
            stageId = stage.id
        )
        val result = agentExecutor.executeAgent(agentConfig, input, metadata)
        return applyValidation(stage, result)
    }

    private fun applyValidation(stage: PipelineStage, result: AgentResult): AgentResult {
        val validationConfig = stage.validation ?: return result
        if (!result.isSuccess || result.output == null) {
            return result
        }

        val validation = outputValidator.validate(result, validationConfig)
        val metadata = result.metadata + mapOf("validationScore" to validation.score.toString())

        return if (validation.isValid) {
            result.copy(metadata = metadata)
        } else {
            val violationSummary = validation.violations.joinToString("; ")
            result.copy(
                isSuccess = false,
                error = "Validation failed: $violationSummary",
                metadata = metadata + mapOf("validationViolations" to violationSummary)
            )
        }
    }

    private fun getMatchedRetryCondition(error: String?, retryableErrors: List<String>): String? {
        if (error.isNullOrBlank()) return null
        val lower = error.lowercase()
        return retryableErrors.find { lower.contains(it.lowercase()) }
    }

    private fun failureResult(agentName: String, message: String): AgentResult {
        return AgentResult(
            agentName = agentName,
            isSuccess = false,
            output = null,
            error = message,
            duration = 0,
            metadata = emptyMap()
        )
    }
}
