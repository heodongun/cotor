package com.cotor.domain.orchestrator

import com.cotor.model.AgentResult
import com.cotor.model.Pipeline
import com.cotor.model.PipelineStage
import com.cotor.model.AgentReference
import com.cotor.model.ExecutionMode
import com.cotor.domain.executor.AgentExecutor
import com.cotor.domain.aggregator.ResultAggregator
import com.cotor.event.EventBus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import org.slf4j.Logger
import com.cotor.model.ExecutionContext
import com.cotor.model.PipelineContext
import com.cotor.stats.StatsManager
import com.cotor.validation.output.OutputValidator
import com.cotor.model.AgentConfig
import com.cotor.validation.PipelineTemplateValidator
import com.cotor.checkpoint.CheckpointManager

class PipelineOrchestratorTemplatingTest : StringSpec({

    "should correctly interpolate stage output in a sequential pipeline" {
        val agentExecutor = mockk<AgentExecutor>()
        val resultAggregator = mockk<ResultAggregator>()
        val eventBus = mockk<EventBus>(relaxed = true)
        val logger = mockk<Logger>(relaxed = true)
        val agentRegistry = mockk<com.cotor.data.registry.AgentRegistry>(relaxed = true)
        val outputValidator = mockk<OutputValidator>(relaxed = true)
        val statsManager = mockk<StatsManager>(relaxed = true)
        val checkpointManager = mockk<CheckpointManager>(relaxed = true)
        val templateValidator = mockk<PipelineTemplateValidator>(relaxed = true)

        val orchestrator = DefaultPipelineOrchestrator(
            agentExecutor,
            resultAggregator,
            eventBus,
            logger,
            agentRegistry,
            outputValidator,
            statsManager,
            checkpointManager,
            templateValidator
        )

        val pipeline = Pipeline(
            name = "templating-test",
            executionMode = ExecutionMode.SEQUENTIAL,
            stages = listOf(
                PipelineStage(id = "step1", agent = AgentReference("echo-agent"), input = "Hello"),
                PipelineStage(id = "step2", agent = AgentReference("echo-agent"), input = "\${stages.step1.output}, World!")
            )
        )

        coEvery { agentRegistry.getAgent("echo-agent") } returns AgentConfig("echo-agent", "com.cotor.agents.EchoAgent")
        coEvery { templateValidator.validate(any(), any()) } returns com.cotor.model.ValidationResult.Success

        coEvery { agentExecutor.executeAgent(any(), any(), any()) } coAnswers {
            val input = secondArg<String?>()
            if (input == "Hello") {
                AgentResult("echo-agent", true, "Hello", null, 0, emptyMap())
            } else {
                AgentResult("echo-agent", true, input, null, 0, emptyMap())
            }
        }

        coEvery { resultAggregator.aggregate(any()) } answers {
            val results = firstArg<List<AgentResult>>()
            com.cotor.model.AggregatedResult(
                totalAgents = results.size,
                successCount = results.count { it.isSuccess },
                failureCount = results.count { !it.isSuccess },
                totalDuration = 0,
                results = results,
                aggregatedOutput = results.last().output ?: "",
                timestamp = java.time.Instant.now()
            )
        }

        val result = orchestrator.executePipeline(pipeline)

        result.aggregatedOutput shouldBe "Hello, World!"
    }
})
