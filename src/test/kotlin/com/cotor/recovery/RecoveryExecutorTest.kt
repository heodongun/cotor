package com.cotor.recovery

import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.executor.AgentExecutor
import com.cotor.model.*
import com.cotor.validation.output.OutputValidator
import com.cotor.validation.output.StageValidationOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.slf4j.Logger

class RecoveryExecutorTest : FunSpec({

    val agentExecutor: AgentExecutor = mockk()
    val agentRegistry: AgentRegistry = mockk()
    val outputValidator: OutputValidator = mockk()
    val logger: Logger = mockk(relaxed = true)

    lateinit var recoveryExecutor: RecoveryExecutor

    beforeEach {
        clearAllMocks()
        recoveryExecutor = RecoveryExecutor(agentExecutor, agentRegistry, outputValidator, logger)
        every { outputValidator.validate(any(), any()) } returns StageValidationOutcome(
            isValid = true,
            score = 1.0,
            violations = emptyList(),
            suggestions = emptyList()
        )
    }

    context("Retry Logic") {
        test("should make correct number of attempts for fixed backoff") {
            runTest {
                val agentConfig = AgentConfig("test-agent", "plugin")
                val stage = PipelineStage("test-stage", agent = AgentReference("test-agent"), recovery = RecoveryConfig(
                    maxRetries = 2,
                    retryDelayMs = 100,
                    backoffStrategy = BackoffStrategy.FIXED,
                    retryOn = listOf("timeout")
                ))

                coEvery { agentExecutor.executeAgent(any(), any(), any()) } returns AgentResult(
                    "test-agent", false, null, "timeout", 100, emptyMap()
                )
                every { agentRegistry.getAgent("test-agent") } returns agentConfig

                val result = recoveryExecutor.executeWithRecovery(stage, null, null)

                result.isSuccess shouldBe false
                result.metadata["retries"] shouldBe "2"
                coVerify(exactly = 3) { agentExecutor.executeAgent(any(), any(), any()) }
            }
        }

        test("should not retry if error is not in retryOn list") {
            runTest {
                val agentConfig = AgentConfig("test-agent", "plugin")
                val stage = PipelineStage("test-stage", agent = AgentReference("test-agent"), recovery = RecoveryConfig(
                    maxRetries = 2,
                    retryOn = listOf("5xx")
                ))

                coEvery { agentExecutor.executeAgent(any(), any(), any()) } returns AgentResult(
                    "test-agent", false, null, "timeout", 100, emptyMap()
                )
                every { agentRegistry.getAgent("test-agent") } returns agentConfig

                val result = recoveryExecutor.executeWithRecovery(stage, null, null)

                result.isSuccess shouldBe false
                result.metadata["retries"] shouldBe "0"
                coVerify(exactly = 1) { agentExecutor.executeAgent(any(), any(), any()) }
            }
        }

        test("should succeed on retry attempt") {
            runTest {
                val agentConfig = AgentConfig("test-agent", "plugin")
                val stage = PipelineStage("test-stage", agent = AgentReference("test-agent"), recovery = RecoveryConfig(
                    maxRetries = 2,
                    retryOn = listOf("timeout")
                ))
                val successResult = AgentResult("test-agent", true, "output", null, 100, emptyMap())
                val failureResult = AgentResult("test-agent", false, null, "timeout", 100, emptyMap())

                coEvery { agentExecutor.executeAgent(any(), any(), any()) } returns failureResult andThen successResult
                every { agentRegistry.getAgent("test-agent") } returns agentConfig

                val result = recoveryExecutor.executeWithRecovery(stage, null, null)

                result.isSuccess shouldBe true
                result.metadata["retries"] shouldBe "1"
                coVerify(exactly = 2) { agentExecutor.executeAgent(any(), any(), any()) }
            }
        }
    }

    context("Fallback Logic") {
        test("should use fallback agent when primary fails") {
            runTest {
                val primaryAgent = AgentConfig("primary", "plugin")
                val fallbackAgent = AgentConfig("fallback", "plugin")
                val stage = PipelineStage("test-stage", agent = AgentReference("primary"), recovery = RecoveryConfig(
                    strategy = RecoveryStrategy.FALLBACK,
                    fallbackAgents = listOf("fallback")
                ))

                every { agentRegistry.getAgent("primary") } returns primaryAgent
                every { agentRegistry.getAgent("fallback") } returns fallbackAgent
                coEvery { agentExecutor.executeAgent(primaryAgent, any(), any()) } returns AgentResult("primary", false, null, "error", 100, emptyMap())
                coEvery { agentExecutor.executeAgent(fallbackAgent, any(), any()) } returns AgentResult("fallback", true, "output", null, 100, emptyMap())

                val result = recoveryExecutor.executeWithRecovery(stage, null, null)

                result.isSuccess shouldBe true
                result.agentName shouldBe "fallback"
            }
        }
    }

    context("Validation") {
        test("should fail stage if output validation fails") {
            runTest {
                val agentConfig = AgentConfig("test-agent", "plugin")
                val stage = PipelineStage(
                    id = "test-stage",
                    agent = AgentReference("test-agent"),
                    validation = StageValidationConfig(requiredKeywords = listOf("required"))
                )
                val agentResult = AgentResult("test-agent", true, "output", null, 100, emptyMap())
                val failedValidation = StageValidationOutcome(
                    isValid = false,
                    score = 0.0,
                    violations = listOf("required keyword missing"),
                    suggestions = emptyList()
                )

                every { agentRegistry.getAgent("test-agent") } returns agentConfig
                coEvery { agentExecutor.executeAgent(any(), any(), any()) } returns agentResult
                every { outputValidator.validate(agentResult, stage.validation!!) } returns failedValidation

                val result = recoveryExecutor.executeWithRecovery(stage, null, null)

                result.isSuccess shouldBe false
                result.error shouldBe "Validation failed: required keyword missing"
            }
        }
    }
})
