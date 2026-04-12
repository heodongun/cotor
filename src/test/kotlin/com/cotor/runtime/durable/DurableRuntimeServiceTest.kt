package com.cotor.runtime.durable

import com.cotor.checkpoint.CheckpointManager
import com.cotor.checkpoint.StageCheckpoint
import com.cotor.model.Pipeline
import com.cotor.model.PipelineContext
import com.cotor.model.PipelineStage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.time.Instant

class DurableRuntimeServiceTest : FunSpec({
    test("inspectRun imports a legacy checkpoint into the durable graph") {
        val checkpointDir = Files.createTempDirectory("durable-runtime-legacy-checkpoint")
        val runtimeDir = Files.createTempDirectory("durable-runtime-store")
        val checkpointManager = CheckpointManager(checkpointDir.toString())
        checkpointManager.saveCheckpoint(
            pipelineId = "pipeline-1",
            pipelineName = "legacy-pipeline",
            completedStages = listOf(
                StageCheckpoint("stage-1", "codex", "done-1", true, 11, Instant.now().toString()),
                StageCheckpoint("stage-2", "codex", "done-2", true, 12, Instant.now().toString())
            ),
            cotorVersion = "1.0.0",
            gitCommit = "abc123",
            os = "macOS",
            jvm = "17"
        )

        val service = DurableRuntimeService(
            checkpointManager = checkpointManager,
            runtimeStore = DurableRuntimeStore(runtimeDir)
        )

        val snapshot = service.inspectRun("pipeline-1")

        snapshot shouldNotBe null
        snapshot!!.importedLegacyCheckpoint shouldBe true
        snapshot.checkpoints shouldHaveSize 2
        snapshot.latestCompletedCheckpoint?.stageId shouldBe "stage-2"
    }

    test("replay-unsafe side effects require approval before replay can continue") {
        val runtimeDir = Files.createTempDirectory("durable-runtime-replay")
        val service = DurableRuntimeService(runtimeStore = DurableRuntimeStore(runtimeDir))
        val pipeline = Pipeline(
            name = "replay-pipeline",
            stages = listOf(PipelineStage(id = "stage-1"))
        )
        val context = PipelineContext(
            pipelineId = "run-1",
            pipelineName = pipeline.name,
            totalStages = pipeline.stages.size
        )
        DurableRuntimeFlags.enable(context)
        service.beginPipelineRun(pipeline, context)
        service.recordStageCompleted(
            context = context,
            stage = pipeline.stages.first(),
            result = com.cotor.model.AgentResult(
                agentName = "codex",
                isSuccess = true,
                output = "ok",
                error = null,
                duration = 10,
                metadata = emptyMap()
            )
        )

        val approvalError = runCatching {
            runBlocking {
                withContext(
                    DurableRuntimeContext(
                        runId = "run-1",
                        replayMode = ReplayMode.CONTINUE,
                        sourceRunId = "run-1",
                        sourceCheckpointId = service.inspectRun("run-1")!!.latestCompletedCheckpoint!!.id
                    )
                ) {
                    service.recordSideEffect(
                        kind = SideEffectKind.GIT_PUBLISH,
                        label = "git.publish:branch-1",
                        replaySafe = false,
                        approvalRequiredOnReplay = true
                    )
                }
            }
        }.exceptionOrNull()

        (approvalError is ReplayApprovalRequiredException) shouldBe true
        val waiting = service.inspectRun("run-1")!!
        waiting.status shouldBe DurableRunStatus.WAITING_FOR_APPROVAL
        waiting.approvalPauses.single().status shouldBe ApprovalPauseStatus.PENDING

        val approved = service.approve("run-1", waiting.approvalPauses.single().checkpointId)
        approved.status shouldBe DurableRunStatus.RUNNING

        runBlocking {
            withContext(
                DurableRuntimeContext(
                    runId = "run-1",
                    replayMode = ReplayMode.CONTINUE,
                    sourceRunId = "run-1",
                    sourceCheckpointId = approved.latestCompletedCheckpoint!!.id
                )
            ) {
                service.recordSideEffect(
                    kind = SideEffectKind.GIT_PUBLISH,
                    label = "git.publish:branch-1",
                    replaySafe = false,
                    approvalRequiredOnReplay = true
                )
            }
        }

        service.inspectRun("run-1")!!.sideEffects.last().status shouldBe SideEffectStatus.APPROVED
    }
})
