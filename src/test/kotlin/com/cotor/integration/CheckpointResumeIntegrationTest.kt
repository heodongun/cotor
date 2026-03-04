package com.cotor.integration

import com.cotor.checkpoint.CheckpointManager
import com.cotor.domain.aggregator.ResultAggregator
import com.cotor.domain.executor.AgentExecutor
import com.cotor.domain.orchestrator.DefaultPipelineOrchestrator
import com.cotor.event.EventBus
import com.cotor.model.AgentConfig
import com.cotor.model.AgentReference
import com.cotor.model.AgentResult
import com.cotor.model.AggregatedResult
import com.cotor.model.ExecutionMode
import com.cotor.model.Pipeline
import com.cotor.model.PipelineContext
import com.cotor.model.PipelineStage
import com.cotor.model.ValidationResult
import com.cotor.stats.StatsManager
import com.cotor.validation.PipelineTemplateValidator
import com.cotor.validation.output.OutputValidator
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

class CheckpointResumeIntegrationTest : StringSpec({

    "should resume from checkpoint after forced process termination" {
        val checkpointDir = Files.createTempDirectory("checkpoint-resume-it").toFile()
        val pipelineId = "resume-it-${UUID.randomUUID()}"
        val checkpointManager = CheckpointManager(checkpointDir.absolutePath)

        val javaBin = "${System.getProperty("java.home")}/bin/java"
        val classPath = System.getProperty("java.class.path")
        val process = ProcessBuilder(
            javaBin,
            "-cp",
            classPath,
            "com.cotor.integration.CheckpointFixtureProcess",
            checkpointDir.absolutePath,
            pipelineId
        )
            .redirectErrorStream(true)
            .start()

        try {
            waitForCheckpoint(checkpointManager, pipelineId)
            process.destroyForcibly()
            process.waitFor()

            val checkpoint = checkpointManager.loadCheckpoint(pipelineId)
            checkpoint shouldNotBe null
            checkpoint!!.completedStages shouldHaveSize 1
            checkpoint.completedStages.first().stageId shouldBe "stage-1"

            val restoredContext = PipelineContext(
                pipelineId = pipelineId,
                pipelineName = checkpoint.pipelineName,
                totalStages = 2
            )
            checkpoint.completedStages.forEach { stage ->
                restoredContext.addStageResult(
                    stage.stageId,
                    AgentResult(
                        agentName = stage.agentName,
                        isSuccess = stage.isSuccess,
                        output = stage.output,
                        error = null,
                        duration = stage.duration,
                        metadata = emptyMap()
                    )
                )
            }

            val pipeline = Pipeline(
                name = checkpoint.pipelineName,
                executionMode = ExecutionMode.SEQUENTIAL,
                stages = listOf(
                    PipelineStage(id = "stage-1", agent = AgentReference("echo-agent"), input = "seed"),
                    PipelineStage(id = "stage-2", agent = AgentReference("echo-agent"))
                )
            )

            val agentExecutor = mockk<AgentExecutor>()
            val resultAggregator = mockk<ResultAggregator>()
            val eventBus = mockk<EventBus>(relaxed = true)
            val logger = mockk<Logger>(relaxed = true)
            val agentRegistry = mockk<com.cotor.data.registry.AgentRegistry>()
            val outputValidator = mockk<OutputValidator>(relaxed = true)
            val statsManager = mockk<StatsManager>(relaxed = true)
            val templateValidator = mockk<PipelineTemplateValidator>()

            coEvery { templateValidator.validate(any()) } returns ValidationResult.Success
            coEvery { agentRegistry.getAgent("echo-agent") } returns AgentConfig("echo-agent", "test.EchoAgent")
            coEvery { agentExecutor.executeAgent(any(), any(), any()) } answers {
                AgentResult(
                    agentName = "echo-agent",
                    isSuccess = true,
                    output = secondArg<String?>(),
                    error = null,
                    duration = 5,
                    metadata = emptyMap()
                )
            }
            coEvery { resultAggregator.aggregate(any()) } answers {
                val results = firstArg<List<AgentResult>>()
                AggregatedResult(
                    totalAgents = results.size,
                    successCount = results.count { it.isSuccess },
                    failureCount = results.count { !it.isSuccess },
                    totalDuration = results.sumOf { it.duration },
                    results = results,
                    aggregatedOutput = results.last().output ?: "",
                    timestamp = Instant.now()
                )
            }

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

            val resumedResult = runBlocking {
                orchestrator.executePipeline(
                    pipeline = pipeline,
                    fromStageId = "stage-2",
                    context = restoredContext
                )
            }

            resumedResult.totalAgents shouldBe 2
            resumedResult.successCount shouldBe 2
            resumedResult.aggregatedOutput shouldBe "stage-1-output"

            coVerify(exactly = 1) {
                agentExecutor.executeAgent(
                    any(),
                    "stage-1-output",
                    any()
                )
            }

            val updatedCheckpoint = checkpointManager.loadCheckpoint(pipelineId)
            updatedCheckpoint shouldNotBe null
            updatedCheckpoint!!.completedStages shouldHaveSize 2
            updatedCheckpoint.completedStages.map { it.stageId } shouldBe listOf("stage-1", "stage-2")
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor()
            }
            checkpointDir.deleteRecursively()
        }
    }
})

private fun waitForCheckpoint(checkpointManager: CheckpointManager, pipelineId: String) {
    val deadline = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < deadline) {
        if (checkpointManager.loadCheckpoint(pipelineId) != null) {
            return
        }
        Thread.sleep(100)
    }
    error("Timed out waiting for checkpoint file to be created")
}
