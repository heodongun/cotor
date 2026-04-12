package com.cotor.runtime.durable

import com.cotor.checkpoint.CheckpointManager
import com.cotor.checkpoint.PipelineCheckpoint
import com.cotor.model.AgentResult
import com.cotor.model.Pipeline
import com.cotor.model.PipelineContext
import com.cotor.model.PipelineStage
import java.time.Instant
import java.util.UUID

class DurableRuntimeService(
    private val checkpointManager: CheckpointManager = CheckpointManager(),
    private val runtimeStore: DurableRuntimeStore = DurableRuntimeStore(),
    private val checkpointGraphStore: CheckpointGraphStore = CheckpointGraphStore(runtimeStore),
    private val sideEffectJournalStore: SideEffectJournalStore = SideEffectJournalStore(runtimeStore)
) {
    fun listRuns(): List<DurableRunSnapshot> = runtimeStore.listRuns()

    fun inspectRun(runId: String): DurableRunSnapshot? =
        runtimeStore.loadRun(runId) ?: importLegacyCheckpoint(runId)

    fun beginPipelineRun(
        pipeline: Pipeline,
        context: PipelineContext,
        fromStageId: String? = null
    ): DurableRunSnapshot? {
        if (!DurableRuntimeFlags.isEnabled(context)) {
            return null
        }
        val runId = context.metadata["durableRunId"]?.toString() ?: context.pipelineId
        val existing = runtimeStore.loadRun(runId)
        if (existing != null) {
            return checkpointGraphStore.updateStatus(runId, DurableRunStatus.RUNNING)
        }
        val replayMode = parseReplayMode(context)
        val snapshot = DurableRunSnapshot(
            runId = runId,
            pipelineName = pipeline.name,
            configPath = context.metadata["configPath"]?.toString(),
            replayMode = replayMode,
            sourceRunId = context.metadata["sourceRunId"]?.toString(),
            sourceCheckpointId = context.metadata["sourceCheckpointId"]?.toString(),
            status = DurableRunStatus.RUNNING,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        context.metadata["durableRunId"] = runId
        context.metadata["replayMode"] = replayMode.name
        if (fromStageId != null) {
            context.metadata["resumeFromStageId"] = fromStageId
        }
        return checkpointGraphStore.upsertRun(snapshot)
    }

    fun recordStageStarted(context: PipelineContext, stage: PipelineStage): DurableRunSnapshot? {
        val runId = context.metadata["durableRunId"]?.toString() ?: return null
        if (!DurableRuntimeFlags.isEnabled(context)) return null
        val parentId = runtimeStore.loadRun(runId)?.latestCheckpoint?.id
        val node = CheckpointNode(
            ordinal = nextOrdinal(runId),
            parentId = parentId,
            stageId = stage.id,
            state = CheckpointNodeState.STARTED,
            createdAt = System.currentTimeMillis(),
            metadata = mapOf("stageType" to stage.type.name)
        )
        return checkpointGraphStore.appendCheckpoint(runId, node)
    }

    fun recordStageCompleted(context: PipelineContext, stage: PipelineStage, result: AgentResult): DurableRunSnapshot? {
        val runId = context.metadata["durableRunId"]?.toString() ?: return null
        if (!DurableRuntimeFlags.isEnabled(context)) return null
        val node = CheckpointNode(
            ordinal = nextOrdinal(runId),
            parentId = runtimeStore.loadRun(runId)?.latestCheckpoint?.id,
            stageId = stage.id,
            state = CheckpointNodeState.COMPLETED,
            agentName = result.agentName,
            output = result.output,
            error = result.error,
            isSuccess = result.isSuccess,
            durationMs = result.duration,
            createdAt = System.currentTimeMillis(),
            metadata = result.metadata
        )
        return checkpointGraphStore.appendCheckpoint(runId, node)
    }

    fun recordStageFailed(context: PipelineContext, stage: PipelineStage, result: AgentResult): DurableRunSnapshot? {
        val runId = context.metadata["durableRunId"]?.toString() ?: return null
        if (!DurableRuntimeFlags.isEnabled(context)) return null
        val node = CheckpointNode(
            ordinal = nextOrdinal(runId),
            parentId = runtimeStore.loadRun(runId)?.latestCheckpoint?.id,
            stageId = stage.id,
            state = CheckpointNodeState.FAILED,
            agentName = result.agentName,
            output = result.output,
            error = result.error,
            isSuccess = false,
            durationMs = result.duration,
            createdAt = System.currentTimeMillis(),
            metadata = result.metadata
        )
        return checkpointGraphStore.appendCheckpoint(runId, node, status = DurableRunStatus.FAILED)
    }

    fun completeRun(context: PipelineContext): DurableRunSnapshot? {
        val runId = context.metadata["durableRunId"]?.toString() ?: return null
        if (!DurableRuntimeFlags.isEnabled(context)) return null
        return checkpointGraphStore.updateStatus(runId, DurableRunStatus.COMPLETED)
    }

    fun failRun(context: PipelineContext): DurableRunSnapshot? {
        val runId = context.metadata["durableRunId"]?.toString() ?: return null
        if (!DurableRuntimeFlags.isEnabled(context)) return null
        return checkpointGraphStore.updateStatus(runId, DurableRunStatus.FAILED)
    }

    suspend fun recordSideEffect(
        kind: SideEffectKind,
        label: String,
        replaySafe: Boolean,
        approvalRequiredOnReplay: Boolean,
        metadata: Map<String, String> = emptyMap()
    ): SideEffectRecord? {
        val runtimeContext = currentDurableRuntimeContext() ?: return null
        val run = inspectRun(runtimeContext.runId) ?: return null
        val checkpointId = run.latestCompletedCheckpoint?.id ?: runtimeContext.sourceCheckpointId
        val existingPendingPause = run.approvalPauses.firstOrNull { pause ->
            pause.status == ApprovalPauseStatus.PENDING && pause.label == label && pause.checkpointId == checkpointId
        }
        if (runtimeContext.replayMode != ReplayMode.LIVE && approvalRequiredOnReplay) {
            if (existingPendingPause != null) {
                throw ReplayApprovalRequiredException(runtimeContext.runId, existingPendingPause)
            }
            val approvedPause = run.approvalPauses.firstOrNull { pause ->
                pause.status == ApprovalPauseStatus.APPROVED && pause.label == label && pause.checkpointId == checkpointId
            }
            if (approvedPause == null) {
                val sideEffect = SideEffectRecord(
                    checkpointId = checkpointId,
                    kind = kind,
                    label = label,
                    replaySafe = replaySafe,
                    approvalRequiredOnReplay = approvalRequiredOnReplay,
                    status = SideEffectStatus.WAITING_FOR_APPROVAL,
                    createdAt = System.currentTimeMillis(),
                    metadata = metadata + mapOf("replayMode" to runtimeContext.replayMode.name)
                )
                sideEffectJournalStore.appendSideEffect(runtimeContext.runId, sideEffect)
                val pause = ApprovalPauseState(
                    checkpointId = checkpointId,
                    sideEffectId = sideEffect.id,
                    label = label,
                    reason = "Replay-safe continuation requires explicit approval before rerunning '$label'.",
                    requestedAt = sideEffect.createdAt
                )
                sideEffectJournalStore.addApprovalPause(runtimeContext.runId, pause)
                throw ReplayApprovalRequiredException(runtimeContext.runId, pause)
            }
        }

        val sideEffect = SideEffectRecord(
            checkpointId = checkpointId,
            kind = kind,
            label = label,
            replaySafe = replaySafe,
            approvalRequiredOnReplay = approvalRequiredOnReplay,
            status = if (runtimeContext.replayMode != ReplayMode.LIVE && approvalRequiredOnReplay) SideEffectStatus.APPROVED else SideEffectStatus.RECORDED,
            createdAt = System.currentTimeMillis(),
            metadata = metadata + mapOf("replayMode" to runtimeContext.replayMode.name)
        )
        sideEffectJournalStore.appendSideEffect(runtimeContext.runId, sideEffect)
        return sideEffect
    }

    fun approve(runId: String, checkpointId: String? = null): DurableRunSnapshot =
        sideEffectJournalStore.approve(runId, checkpointId)

    fun buildExecutionPlan(
        runId: String,
        pipeline: Pipeline,
        checkpointId: String? = null,
        replayMode: ReplayMode = ReplayMode.CONTINUE
    ): DurableExecutionPlan {
        val snapshot = inspectRun(runId) ?: error("No durable run or legacy checkpoint found for $runId")
        val restoreCheckpoint = checkpointId?.let { id ->
            snapshot.checkpoints.firstOrNull { it.id == id } ?: error("Checkpoint not found: $id")
        } ?: snapshot.latestCompletedCheckpoint
        val restoredStages = snapshot.checkpoints
            .filter { node ->
                (node.state == CheckpointNodeState.COMPLETED || node.state == CheckpointNodeState.IMPORTED_LEGACY) &&
                    (restoreCheckpoint == null || node.ordinal <= restoreCheckpoint.ordinal)
            }
            .sortedBy { it.ordinal }
        val completedStageIds = restoredStages.mapNotNull { it.stageId }.toSet()
        val nextStageId = pipeline.stages.firstOrNull { it.id !in completedStageIds }?.id
        val pendingApproval = snapshot.approvalPauses.firstOrNull { it.status == ApprovalPauseStatus.PENDING }
        return DurableExecutionPlan(
            runId = snapshot.runId,
            replayMode = replayMode,
            configPath = snapshot.configPath,
            pipelineName = snapshot.pipelineName,
            restoreCheckpointId = restoreCheckpoint?.id,
            nextStageId = nextStageId,
            stageResults = restoredStages,
            pendingApproval = pendingApproval
        )
    }

    fun importLegacyCheckpoint(runId: String, configPath: String? = null): DurableRunSnapshot? {
        val checkpoint = checkpointManager.loadCheckpoint(runId) ?: return null
        val nodes = checkpoint.completedStages.mapIndexed { index, stage ->
            CheckpointNode(
                ordinal = index + 1,
                stageId = stage.stageId,
                state = CheckpointNodeState.IMPORTED_LEGACY,
                agentName = stage.agentName,
                output = stage.output,
                isSuccess = stage.isSuccess,
                durationMs = stage.duration,
                createdAt = runCatching { Instant.parse(stage.timestamp).toEpochMilli() }.getOrDefault(System.currentTimeMillis()),
                metadata = mapOf("importedFromLegacyCheckpoint" to "true")
            )
        }
        val snapshot = DurableRunSnapshot(
            runId = checkpoint.pipelineId,
            pipelineName = checkpoint.pipelineName,
            configPath = configPath,
            replayMode = ReplayMode.LIVE,
            status = DurableRunStatus.COMPLETED,
            createdAt = runCatching { Instant.parse(checkpoint.createdAt).toEpochMilli() }.getOrDefault(System.currentTimeMillis()),
            updatedAt = System.currentTimeMillis(),
            completedAt = System.currentTimeMillis(),
            importedLegacyCheckpoint = true,
            checkpoints = nodes
        )
        runtimeStore.saveRun(snapshot)
        return snapshot
    }

    fun createFork(
        sourceRunId: String,
        forkRunId: String,
        checkpointId: String,
        configPath: String? = null
    ): DurableRunSnapshot {
        val source = inspectRun(sourceRunId) ?: error("No durable run or legacy checkpoint found for $sourceRunId")
        val restoreCheckpoint = source.checkpoints.firstOrNull { it.id == checkpointId }
            ?: error("Checkpoint not found: $checkpointId")
        val copiedCheckpoints = source.checkpoints
            .filter { it.ordinal <= restoreCheckpoint.ordinal }
            .sortedBy { it.ordinal }
        val snapshot = DurableRunSnapshot(
            runId = forkRunId,
            pipelineName = source.pipelineName,
            configPath = configPath ?: source.configPath,
            replayMode = ReplayMode.FORK,
            sourceRunId = sourceRunId,
            sourceCheckpointId = checkpointId,
            status = DurableRunStatus.RUNNING,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            checkpoints = copiedCheckpoints
        )
        runtimeStore.saveRun(snapshot)
        return snapshot
    }

    private fun nextOrdinal(runId: String): Int =
        (runtimeStore.loadRun(runId)?.checkpoints?.maxOfOrNull { it.ordinal } ?: 0) + 1

    private fun parseReplayMode(context: PipelineContext): ReplayMode =
        context.metadata["replayMode"]?.toString()?.let {
            runCatching { ReplayMode.valueOf(it) }.getOrDefault(ReplayMode.LIVE)
        } ?: ReplayMode.LIVE
}
