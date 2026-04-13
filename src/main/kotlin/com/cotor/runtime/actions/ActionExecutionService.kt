package com.cotor.runtime.actions

import com.cotor.provenance.ProvenanceService
import com.cotor.runtime.durable.DurableRuntimeContext
import com.cotor.runtime.durable.DurableRuntimeService
import com.cotor.runtime.durable.SideEffectKind
import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface ActionInterceptor {
    suspend fun before(request: ActionRequest): ActionInterceptorDecision = ActionInterceptorDecision.allow()

    suspend fun after(request: ActionRequest, record: ActionExecutionRecord) = Unit
}

data class ActionInterceptorDecision(
    val allow: Boolean,
    val requireApproval: Boolean = false,
    val reason: String? = null,
    val decisionId: String? = null
) {
    companion object {
        fun allow(decisionId: String? = null) = ActionInterceptorDecision(allow = true, decisionId = decisionId)

        fun deny(reason: String, decisionId: String? = null) =
            ActionInterceptorDecision(allow = false, reason = reason, decisionId = decisionId)

        fun requireApproval(reason: String, decisionId: String? = null) =
            ActionInterceptorDecision(allow = true, requireApproval = true, reason = reason, decisionId = decisionId)
    }
}

class ActionExecutionService(
    private val actionStore: ActionStore = ActionStore(),
    private val durableRuntimeService: DurableRuntimeService = DurableRuntimeService(),
    private val provenanceService: ProvenanceService = ProvenanceService(),
    private val interceptors: List<ActionInterceptor> = emptyList(),
    private val logger: Logger = LoggerFactory.getLogger(ActionExecutionService::class.java)
) {
    suspend fun <T> run(
        request: ActionRequest,
        onSuccess: (T) -> ActionEvidence = { request.evidence },
        onFailure: (Throwable) -> ActionEvidence = { request.evidence },
        block: suspend () -> T
    ): T {
        val runtimeContext = currentCoroutineContext()[DurableRuntimeContext]
        val runId = request.subject.runId ?: runtimeContext?.runId ?: "standalone"
        val startedAt = System.currentTimeMillis()
        var decisionId: String? = null

        val startedRecord = ActionExecutionRecord(
            id = request.id,
            runId = runId,
            request = request.copy(
                subject = request.subject.copy(runId = runId)
            ),
            status = ActionStatus.STARTED,
            evidence = request.evidence,
            createdAt = startedAt,
            updatedAt = startedAt
        )
        actionStore.append(runId, startedRecord)

        try {
            interceptors.forEach { interceptor ->
                val decision = interceptor.before(request)
                decisionId = decision.decisionId ?: decisionId
                when {
                    !decision.allow -> {
                        val deniedRecord = startedRecord.copy(
                            status = ActionStatus.DENIED,
                            error = decision.reason,
                            policyDecisionId = decisionId,
                            updatedAt = System.currentTimeMillis()
                        )
                        actionStore.replace(runId, startedRecord.id) { deniedRecord }
                        throw ActionDeniedException(request, decision.reason ?: "Action denied.")
                    }
                    decision.requireApproval -> {
                        val pendingRecord = startedRecord.copy(
                            status = ActionStatus.WAITING_FOR_APPROVAL,
                            error = decision.reason,
                            policyDecisionId = decisionId,
                            updatedAt = System.currentTimeMillis()
                        )
                        actionStore.replace(runId, startedRecord.id) { pendingRecord }
                        throw ActionApprovalRequiredException(request, decision.reason ?: "Action requires approval.")
                    }
                }
            }

            durableRuntimeService.recordSideEffect(
                kind = request.kind.toSideEffectKind(),
                label = request.label,
                replaySafe = request.replaySafe,
                approvalRequiredOnReplay = request.approvalRequiredOnReplay,
                metadata = request.metadata
            )

            val value = block()
            val evidence = onSuccess(value)
            val completedRecord = startedRecord.copy(
                status = ActionStatus.SUCCEEDED,
                outputSummary = summarizeMetadata(request.metadata),
                policyDecisionId = decisionId,
                evidence = evidence,
                updatedAt = System.currentTimeMillis()
            )
            actionStore.replace(runId, startedRecord.id) { completedRecord }
            provenanceService.recordAction(request, completedRecord)
            interceptors.forEach { interceptor ->
                runCatching { interceptor.after(request, completedRecord) }
                    .onFailure { logger.warn("Action interceptor after-hook failed for ${request.label}", it) }
            }
            return value
        } catch (approval: ActionApprovalRequiredException) {
            throw approval
        } catch (denied: ActionDeniedException) {
            throw denied
        } catch (error: Throwable) {
            val failedRecord = startedRecord.copy(
                status = ActionStatus.FAILED,
                error = error.message ?: error::class.simpleName.orEmpty(),
                policyDecisionId = decisionId,
                evidence = onFailure(error),
                updatedAt = System.currentTimeMillis()
            )
            actionStore.replace(runId, startedRecord.id) { failedRecord }
            provenanceService.recordAction(request, failedRecord)
            interceptors.forEach { interceptor ->
                runCatching { interceptor.after(request, failedRecord) }
                    .onFailure { logger.warn("Action interceptor after-hook failed for ${request.label}", it) }
            }
            throw error
        }
    }

    fun listRuns(): List<ActionLogSnapshot> = actionStore.listSnapshots()

    fun inspect(runId: String): ActionLogSnapshot? = actionStore.load(runId)

    private fun ActionKind.toSideEffectKind(): SideEffectKind = when (this) {
        ActionKind.SHELL_EXEC -> SideEffectKind.PROCESS_LAUNCH
        ActionKind.FILE_WRITE -> SideEffectKind.PROCESS_LAUNCH
        ActionKind.HTTP_REQUEST -> SideEffectKind.HTTP_REQUEST
        ActionKind.AGENT_EXEC -> SideEffectKind.AGENT_EXECUTION
        ActionKind.GIT_WORKTREE -> SideEffectKind.GIT_WORKTREE
        ActionKind.GIT_PUBLISH -> SideEffectKind.GIT_PUBLISH
        ActionKind.GITHUB_REVIEW -> SideEffectKind.GITHUB_REVIEW
        ActionKind.GITHUB_COMMENT -> SideEffectKind.GITHUB_COMMENT
        ActionKind.GITHUB_MERGE -> SideEffectKind.GITHUB_MERGE
        ActionKind.SECRET_READ -> SideEffectKind.PROCESS_LAUNCH
    }

    private fun summarizeMetadata(metadata: Map<String, String>): String? =
        metadata.entries.takeIf { it.isNotEmpty() }?.joinToString(", ") { (key, value) -> "$key=$value" }
}
