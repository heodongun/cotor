package com.cotor.recovery

import com.cotor.data.registry.InMemoryAgentRegistry
import com.cotor.domain.executor.AgentExecutor
import com.cotor.model.*
import com.cotor.validation.output.DefaultOutputValidator
import com.cotor.validation.output.OutputValidator
import com.cotor.validation.output.StageValidationOutcome
import com.cotor.validation.output.SyntaxValidator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.slf4j.Logger

class RecoveryExecutorTest : FunSpec({

    val registry = InMemoryAgentRegistry().apply {
        registerAgent(
            AgentConfig(
                name = "primary",
                pluginClass = "com.cotor.data.plugin.EchoPlugin"
            )
        )
        registerAgent(
            AgentConfig(
                name = "fallback",
                pluginClass = "com.cotor.data.plugin.EchoPlugin"
            )
        )
    }

    val logger = mockk<Logger>(relaxed = true)

    test("fallback agent runs when primary fails") {
        val agentExecutor = mockk<AgentExecutor>()
        val outputValidator = object : OutputValidator {
            override fun validate(result: AgentResult, config: StageValidationConfig): StageValidationOutcome {
                return StageValidationOutcome(true, 1.0, emptyList(), emptyList())
            }
        }
        val recoveryExecutor = RecoveryExecutor(agentExecutor, registry, outputValidator, logger)

        coEvery {
            agentExecutor.executeAgent(match { it.name == "primary" }, any(), any())
        } returns AgentResult("primary", false, null, "boom", 0, emptyMap())

        coEvery {
            agentExecutor.executeAgent(match { it.name == "fallback" }, any(), any())
        } returns AgentResult("fallback", true, "ok", null, 10, emptyMap())

        val stage = PipelineStage(
            id = "stage1",
            agent = AgentReference("primary"),
            recovery = RecoveryConfig(
                strategy = RecoveryStrategy.FALLBACK,
                fallbackAgents = listOf("fallback")
            )
        )

        val result = recoveryExecutor.executeWithRecovery(stage, "input", null)
        result.agentName shouldBe "fallback"
        result.isSuccess shouldBe true
    }

    test("validation failure marks result unsuccessful") {
        val agentExecutor = mockk<AgentExecutor>()
        val recoveryExecutor = RecoveryExecutor(
            agentExecutor = agentExecutor,
            agentRegistry = registry,
            outputValidator = DefaultOutputValidator(SyntaxValidator()),
            logger = logger
        )

        coEvery { agentExecutor.executeAgent(any(), any(), any()) } returns AgentResult(
            agentName = "primary",
            isSuccess = true,
            output = "short",
            error = null,
            duration = 5,
            metadata = emptyMap()
        )

        val stage = PipelineStage(
            id = "stage2",
            agent = AgentReference("primary"),
            validation = StageValidationConfig(
                minLength = 20,
                requiredKeywords = listOf("SUCCESS")
            )
        )

        val result = recoveryExecutor.executeWithRecovery(stage, null, null)
        result.isSuccess.shouldBeFalse()
        result.error?.contains("Validation failed") shouldBe true
    }
})
