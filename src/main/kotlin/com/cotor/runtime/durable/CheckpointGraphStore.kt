package com.cotor.runtime.durable

class CheckpointGraphStore(
    private val store: DurableRuntimeStore
) {
    fun upsertRun(snapshot: DurableRunSnapshot): DurableRunSnapshot {
        store.saveRun(snapshot)
        return snapshot
    }

    fun appendCheckpoint(
        runId: String,
        checkpoint: CheckpointNode,
        status: DurableRunStatus? = null,
        sourceCheckpointId: String? = null
    ): DurableRunSnapshot {
        val current = store.loadRun(runId) ?: error("Unknown durable run: $runId")
        val updated = current.copy(
            status = status ?: current.status,
            sourceCheckpointId = sourceCheckpointId ?: current.sourceCheckpointId,
            updatedAt = checkpoint.createdAt,
            checkpoints = current.checkpoints + checkpoint
        )
        store.saveRun(updated)
        return updated
    }

    fun updateStatus(
        runId: String,
        status: DurableRunStatus,
        timestamp: Long = System.currentTimeMillis()
    ): DurableRunSnapshot {
        val current = store.loadRun(runId) ?: error("Unknown durable run: $runId")
        val updated = current.copy(
            status = status,
            updatedAt = timestamp,
            completedAt = if (status == DurableRunStatus.COMPLETED || status == DurableRunStatus.FAILED) timestamp else current.completedAt
        )
        store.saveRun(updated)
        return updated
    }
}
