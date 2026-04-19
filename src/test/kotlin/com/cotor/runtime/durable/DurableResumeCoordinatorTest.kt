package com.cotor.runtime.durable

import com.cotor.checkpoint.CheckpointManager
import com.cotor.checkpoint.StageCheckpoint
import com.cotor.data.config.ConfigRepository
import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.orchestrator.PipelineOrchestrator
import com.cotor.model.AgentConfig
import com.cotor.model.AggregatedResult
import com.cotor.model.ExecutionMode
import com.cotor.model.Pipeline
import com.cotor.model.PipelineContext
import com.cotor.model.PipelineStage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.nio.file.Files
import java.time.Instant

class DurableResumeCoordinatorTest : FunSpec({
    test("continueRun restores completed stages and resumes from the next stage") {
        val checkpointDir = Files.createTempDirectory("durable-resume-checkpoints")
        val runtimeDir = Files.createTempDirectory("durable-resume-runtime")
        val checkpointManager = CheckpointManager(checkpointDir.toString())
        checkpointManager.saveCheckpoint(
            pipelineId = "pipeline-continue",
            pipelineName = "resume-pipeline",
            completedStages = listOf(
                StageCheckpoint("stage-1", "codex", "seed-output", true, 15, Instant.now().toString())
            ),
            cotorVersion = "1.0.0",
            gitCommit = "abc",
            os = "macOS",
            jvm = "17"
        )
        val durableRuntimeService = DurableRuntimeService(
            checkpointManager = checkpointManager,
            runtimeStore = DurableRuntimeStore(runtimeDir)
        )
        val configRepository = mockk<ConfigRepository>()
        val agentRegistry = mockk<AgentRegistry>(relaxed = true)
        val orchestrator = mockk<PipelineOrchestrator>()
        val pipeline = Pipeline(
            name = "resume-pipeline",
            executionMode = ExecutionMode.SEQUENTIAL,
            stages = listOf(
                PipelineStage(id = "stage-1"),
                PipelineStage(id = "stage-2")
            )
        )

        coEvery { configRepository.loadConfig(any()) } returns com.cotor.model.CotorConfig(
            agents = listOf(AgentConfig("codex", "plugin.Codex")),
            pipelines = listOf(pipeline)
        )
        coEvery { orchestrator.executePipeline(any(), any(), any()) } returns AggregatedResult(
            totalAgents = 2,
            successCount = 2,
            failureCount = 0,
            totalDuration = 20,
            results = emptyList(),
            aggregatedOutput = "done",
            timestamp = Instant.now()
        )

        val coordinator = DurableResumeCoordinator(
            configRepository = configRepository,
            agentRegistry = agentRegistry,
            orchestrator = orchestrator,
            durableRuntimeService = durableRuntimeService
        )

        val plan = kotlinx.coroutines.runBlocking {
            coordinator.continueRun("pipeline-continue", "cotor.yaml")
        }

        plan.nextStageId shouldBe "stage-2"
        coVerify(exactly = 1) {
            orchestrator.executePipeline(
                pipeline,
                "stage-2",
                withArg<PipelineContext> { restored ->
                    restored.getStageOutput("stage-1") shouldBe "seed-output"
                    restored.metadata["replayMode"] shouldBe ReplayMode.CONTINUE.name
                }
            )
        }
    }

    test("forkRun creates a new run id and links the source checkpoint") {
        val runtimeDir = Files.createTempDirectory("durable-resume-fork")
        val service = DurableRuntimeService(runtimeStore = DurableRuntimeStore(runtimeDir))
        val pipeline = Pipeline(
            name = "fork-pipeline",
            executionMode = ExecutionMode.SEQUENTIAL,
            stages = listOf(PipelineStage(id = "stage-1"), PipelineStage(id = "stage-2"))
        )
        val initialContext = PipelineContext(
            pipelineId = "source-run",
            pipelineName = pipeline.name,
            totalStages = pipeline.stages.size
        )
        DurableRuntimeFlags.enable(initialContext)
        service.beginPipelineRun(pipeline, initialContext)
        service.recordStageCompleted(
            initialContext,
            pipeline.stages.first(),
            com.cotor.model.AgentResult("codex", true, "seed", null, 1, emptyMap())
        )

        val configRepository = mockk<ConfigRepository>()
        val agentRegistry = mockk<AgentRegistry>(relaxed = true)
        val orchestrator = mockk<PipelineOrchestrator>()
        coEvery { configRepository.loadConfig(any()) } returns com.cotor.model.CotorConfig(
            agents = listOf(AgentConfig("codex", "plugin.Codex")),
            pipelines = listOf(pipeline)
        )
        coEvery { orchestrator.executePipeline(any(), any(), any()) } returns AggregatedResult(
            totalAgents = 1,
            successCount = 1,
            failureCount = 0,
            totalDuration = 1,
            results = emptyList(),
            aggregatedOutput = "forked",
            timestamp = Instant.now()
        )
        val coordinator = DurableResumeCoordinator(configRepository, agentRegistry, orchestrator, service)

        val sourceCheckpointId = service.inspectRun("source-run")!!.latestCompletedCheckpoint!!.id
        val forkPlan = kotlinx.coroutines.runBlocking {
            coordinator.forkRun("source-run", sourceCheckpointId, "cotor.yaml")
        }

        forkPlan.replayMode shouldBe ReplayMode.FORK
        forkPlan.restoreCheckpointId shouldBe sourceCheckpointId
        (forkPlan.runId == "source-run") shouldBe false
    }
})
