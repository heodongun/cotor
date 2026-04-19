package com.cotor.runtime.durable

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class DurableRunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    WAITING_FOR_APPROVAL
}

@Serializable
enum class ReplayMode {
    LIVE,
    CONTINUE,
    FORK
}

@Serializable
enum class CheckpointNodeState {
    STARTED,
    COMPLETED,
    FAILED,
    IMPORTED_LEGACY
}

@Serializable
enum class SideEffectKind {
    AGENT_EXECUTION,
    PROCESS_LAUNCH,
    GIT_WORKTREE,
    GIT_PUBLISH,
    GITHUB_REVIEW,
    GITHUB_COMMENT,
    GITHUB_CLOSE_PR,
    GITHUB_REFRESH_PR,
    GITHUB_MERGE,
    HTTP_REQUEST
}

@Serializable
enum class SideEffectStatus {
    RECORDED,
    WAITING_FOR_APPROVAL,
    APPROVED,
    QUARANTINED
}

@Serializable
enum class ApprovalPauseStatus {
    PENDING,
    APPROVED,
    REJECTED
}

@Serializable
data class CheckpointNode(
    val id: String = UUID.randomUUID().toString(),
    val ordinal: Int,
    val parentId: String? = null,
    val stageId: String? = null,
    val state: CheckpointNodeState,
    val agentName: String? = null,
    val output: String? = null,
    val error: String? = null,
    val isSuccess: Boolean? = null,
    val durationMs: Long? = null,
    val createdAt: Long,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class SideEffectRecord(
    val id: String = UUID.randomUUID().toString(),
    val checkpointId: String? = null,
    val kind: SideEffectKind,
    val label: String,
    val replaySafe: Boolean,
    val approvalRequiredOnReplay: Boolean,
    val status: SideEffectStatus = SideEffectStatus.RECORDED,
    val createdAt: Long,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class ApprovalPauseState(
    val id: String = UUID.randomUUID().toString(),
    val checkpointId: String? = null,
    val sideEffectId: String,
    val label: String,
    val status: ApprovalPauseStatus = ApprovalPauseStatus.PENDING,
    val reason: String,
    val requestedAt: Long,
    val decidedAt: Long? = null
)

@Serializable
data class DurableRunSnapshot(
    val runId: String,
    val pipelineName: String,
    val configPath: String? = null,
    val replayMode: ReplayMode = ReplayMode.LIVE,
    val sourceRunId: String? = null,
    val sourceCheckpointId: String? = null,
    val status: DurableRunStatus = DurableRunStatus.RUNNING,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
    val importedLegacyCheckpoint: Boolean = false,
    val checkpoints: List<CheckpointNode> = emptyList(),
    val sideEffects: List<SideEffectRecord> = emptyList(),
    val approvalPauses: List<ApprovalPauseState> = emptyList()
) {
    val latestCheckpoint: CheckpointNode?
        get() = checkpoints.maxByOrNull { it.ordinal }

    val latestCompletedCheckpoint: CheckpointNode?
        get() = checkpoints
            .filter { it.state == CheckpointNodeState.COMPLETED || it.state == CheckpointNodeState.IMPORTED_LEGACY }
            .maxByOrNull { it.ordinal }
}

@Serializable
data class DurableExecutionPlan(
    val runId: String,
    val replayMode: ReplayMode,
    val configPath: String?,
    val pipelineName: String,
    val restoreCheckpointId: String?,
    val nextStageId: String?,
    val stageResults: List<CheckpointNode>,
    val pendingApproval: ApprovalPauseState?
)

class ReplayApprovalRequiredException(
    val runId: String,
    val pause: ApprovalPauseState
) : IllegalStateException(
    "Replay of run '$runId' requires approval for side effect '${pause.label}'"
)
