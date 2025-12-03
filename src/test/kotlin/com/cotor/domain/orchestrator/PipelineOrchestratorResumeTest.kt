package com.cotor.domain.orchestrator

import com.cotor.checkpoint.CheckpointManager
import com.cotor.model.AgentConfig
import com.cotor.model.AgentResult
import com.cotor.model.Pipeline
import com.cotor.model.PipelineStage
import com.cotor.model.ExecutionMode
import com.cotor.domain.executor.AgentExecutor
import com.cotor.data.registry.AgentRegistry
import com.cotor.data.registry.InMemoryAgentRegistry
import com.cotor.domain.aggregator.DefaultResultAggregator
import com.cotor.event.EventBus
import com.cotor.stats.StatsManager
import com.cotor.validation.output.OutputValidator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import org.slf4j.LoggerFactory
import java.io.File

class PipelineOrchestratorResumeTest : FunSpec({

    val logger = LoggerFactory.getLogger("TestLogger")
    val eventBus = mockk<EventBus>(relaxed = true)
    val outputValidator = mockk<OutputValidator>(relaxed = true)
    val statsManager = mockk<StatsManager>(relaxed = true)
    val checkpointDir = ".cotor/test-checkpoints"

    beforeTest {
        File(checkpointDir).deleteRecursively()
    }

    afterTest {
        File(checkpointDir).deleteRecursively()
    }

    test("should resume a sequential pipeline from a checkpoint after a failure") {
        // Arrange
        val agentExecutor = mockk<AgentExecutor>()
        val agentRegistry = InMemoryAgentRegistry()
        val checkpointManager = CheckpointManager(checkpointDir)
        val resultAnalyzer = mockk<com.cotor.analysis.ResultAnalyzer>(relaxed = true)
        val orchestrator = DefaultPipelineOrchestrator(
            agentExecutor = agentExecutor,
            resultAggregator = DefaultResultAggregator(resultAnalyzer),
            eventBus = eventBus,
            logger = logger,
            agentRegistry = agentRegistry,
            outputValidator = outputValidator,
            statsManager = statsManager,
            checkpointManager = checkpointManager
        )

        agentRegistry.registerAgent(AgentConfig("agent1", "plugin1"))
        agentRegistry.registerAgent(AgentConfig("agent2", "plugin2"))
        agentRegistry.registerAgent(AgentConfig("agent3", "plugin3"))

        val pipeline = Pipeline(
            name = "resume-test-pipeline",
            executionMode = ExecutionMode.SEQUENTIAL,
            stages = listOf(
                PipelineStage(id = "stage1", agent = com.cotor.model.AgentReference("agent1")),
                PipelineStage(id = "stage2", agent = com.cotor.model.AgentReference("agent2")),
                PipelineStage(id = "stage3", agent = com.cotor.model.AgentReference("agent3"))
            )
        )

        // First run: stage1 succeeds, stage2 fails
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } coAnswers {
            val agentConfig = firstArg<AgentConfig>()
            when (agentConfig.name) {
                "agent1" -> AgentResult("agent1", true, "output1", null, 10, emptyMap())
                "agent2" -> AgentResult("agent2", false, null, "error2", 10, emptyMap())
                else -> throw IllegalStateException("Should not be called in the first run")
            }
        }

        val initialResult = orchestrator.executePipeline(pipeline)
        initialResult.failureCount shouldBe 1

        // Check that a checkpoint was created
        val checkpoints = checkpointManager.listCheckpoints()
        checkpoints.size shouldBe 1
        val checkpoint = checkpointManager.loadCheckpoint(checkpoints.first().pipelineId)!!
        checkpoint.completedStages.size shouldBe 2

        // Second run (resume): stage2 succeeds, stage3 succeeds
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } coAnswers {
            val agentConfig = firstArg<AgentConfig>()
            when (agentConfig.name) {
                "agent2" -> AgentResult("agent2", true, "output2", null, 10, emptyMap())
                "agent3" -> AgentResult("agent3", true, "output3", null, 10, emptyMap())
                else -> throw IllegalStateException("Stage 1 should not be re-executed")
            }
        }

        // Act
        val resumedResult = orchestrator.executePipeline(
            pipeline,
            fromStageId = "stage2",
            context = com.cotor.model.PipelineContext(
                pipelineId = checkpoint.pipelineId,
                pipelineName = pipeline.name,
                totalStages = pipeline.stages.size
            ).apply {
                addStageResult("stage1", AgentResult("agent1", true, "output1", null, 10, emptyMap()))
            }
        )

        // Assert
        resumedResult.failureCount shouldBe 0
        resumedResult.successCount shouldBe 3
        resumedResult.results.size shouldBe 3
        resumedResult.results.find { it.agentName == "agent1" }!!.output shouldBe "output1"
        resumedResult.results.find { it.agentName == "agent2" }!!.output shouldBe "output2"
        resumedResult.results.find { it.agentName == "agent3" }!!.output shouldBe "output3"
    }
})
