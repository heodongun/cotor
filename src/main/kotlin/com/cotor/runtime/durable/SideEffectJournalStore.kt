package com.cotor.runtime.durable

class SideEffectJournalStore(
    private val store: DurableRuntimeStore
) {
    fun appendSideEffect(runId: String, sideEffect: SideEffectRecord): DurableRunSnapshot {
        val current = store.loadRun(runId) ?: error("Unknown durable run: $runId")
        val updated = current.copy(
            status = if (sideEffect.status == SideEffectStatus.WAITING_FOR_APPROVAL) {
                DurableRunStatus.WAITING_FOR_APPROVAL
            } else {
                current.status
            },
            updatedAt = sideEffect.createdAt,
            sideEffects = current.sideEffects + sideEffect
        )
        store.saveRun(updated)
        return updated
    }

    fun addApprovalPause(runId: String, pause: ApprovalPauseState): DurableRunSnapshot {
        val current = store.loadRun(runId) ?: error("Unknown durable run: $runId")
        val updated = current.copy(
            status = DurableRunStatus.WAITING_FOR_APPROVAL,
            updatedAt = pause.requestedAt,
            approvalPauses = current.approvalPauses + pause
        )
        store.saveRun(updated)
        return updated
    }

    fun approve(runId: String, checkpointId: String? = null, now: Long = System.currentTimeMillis()): DurableRunSnapshot {
        val current = store.loadRun(runId) ?: error("Unknown durable run: $runId")
        val approvedPauses = current.approvalPauses.map { pause ->
            if (pause.status != ApprovalPauseStatus.PENDING) {
                pause
            } else if (checkpointId == null || pause.checkpointId == checkpointId) {
                pause.copy(status = ApprovalPauseStatus.APPROVED, decidedAt = now)
            } else {
                pause
            }
        }
        val approvedIds = approvedPauses
            .filter { it.status == ApprovalPauseStatus.APPROVED }
            .map { it.sideEffectId }
            .toSet()
        val updatedSideEffects = current.sideEffects.map { effect ->
            if (effect.id in approvedIds && effect.status == SideEffectStatus.WAITING_FOR_APPROVAL) {
                effect.copy(status = SideEffectStatus.APPROVED)
            } else {
                effect
            }
        }
        val updated = current.copy(
            status = DurableRunStatus.RUNNING,
            updatedAt = now,
            approvalPauses = approvedPauses,
            sideEffects = updatedSideEffects
        )
        store.saveRun(updated)
        return updated
    }
}
