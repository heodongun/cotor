package com.cotor.app.runtime

import com.cotor.app.CompanyIssue
import com.cotor.app.CompanyRuntimeSnapshot
import com.cotor.app.DesktopAppState
import com.cotor.app.ReviewQueueItem
import com.cotor.policy.PolicyEngine
import com.cotor.providers.github.GitHubControlPlaneService
import com.cotor.runtime.actions.ActionStatus
import com.cotor.runtime.actions.ActionStore
import com.cotor.runtime.durable.DurableRunStatus
import com.cotor.runtime.durable.DurableRuntimeService

data class BoundCompanyRuntime(
    val runtime: CompanyRuntimeSnapshot,
    val issues: List<CompanyIssue>,
    val reviewQueue: List<ReviewQueueItem>
)

class CompanyRuntimeBindingService(
    private val durableRuntimeService: DurableRuntimeService = DurableRuntimeService(),
    private val actionStore: ActionStore = ActionStore(),
    private val policyEngine: PolicyEngine = PolicyEngine(),
    private val gitHubControlPlaneService: GitHubControlPlaneService = GitHubControlPlaneService()
) {
    fun bind(state: DesktopAppState, companyId: String, runtime: CompanyRuntimeSnapshot): BoundCompanyRuntime {
        val boundRunIds = state.issues
            .filter { it.companyId == companyId }
            .mapNotNull { it.durableRunId }
            .toSet() + state.reviewQueue
            .filter { it.companyId == companyId }
            .map { it.runId }
            .filter { it.isNotBlank() }
            .toSet()
        val runs = durableRuntimeService.listRuns().filter { run ->
            run.runId in boundRunIds ||
                run.checkpoints.any { node -> node.metadata["companyId"] == companyId } ||
                run.sideEffects.any { effect -> effect.metadata["companyId"] == companyId }
        }
        val githubPullRequests = gitHubControlPlaneService.listPullRequests(companyId)
        val actionLogs = actionStore.listSnapshots()

        val resumableRunIds = runs
            .filter { it.status != DurableRunStatus.COMPLETED }
            .map { it.runId }
        val pendingApprovalRunIds = runs
            .filter { it.status == DurableRunStatus.WAITING_FOR_APPROVAL }
            .map { it.runId }
        val blockedByPolicy = actionLogs.sumOf { snapshot ->
            snapshot.records.count { record ->
                record.request.subject.companyId == companyId &&
                    (record.status == ActionStatus.DENIED || record.status == ActionStatus.WAITING_FOR_APPROVAL)
            }
        }
        val blockedByCi = githubPullRequests.count { pullRequest ->
            val stateValue = pullRequest.state?.uppercase()
            stateValue == "OPEN" && (
                pullRequest.checks.any { check ->
                    check.status.equals("COMPLETED", ignoreCase = true) &&
                        !check.conclusion.equals("SUCCESS", ignoreCase = true)
                } || pullRequest.checksSummary?.contains("FAILURE", ignoreCase = true) == true
            )
        }

        val providerBlockByPr = githubPullRequests.associateBy { it.number }
        val providerBlockByIssueId = githubPullRequests
            .filter { !it.issueId.isNullOrBlank() }
            .associateBy { it.issueId!! }
        val boundIssues = state.issues.map { issue ->
            if (issue.companyId != companyId) {
                issue
            } else {
                val issuePullRequest = issue.pullRequestNumber?.let(providerBlockByPr::get)
                    ?: providerBlockByIssueId[issue.id]
                val matchingRun = runs.firstOrNull { run ->
                    run.runId == issue.durableRunId || run.pipelineName == issue.pipelineId
                }
                issue.copy(
                    durableRunId = matchingRun?.runId ?: issue.durableRunId,
                    approvalPauseId = matchingRun?.approvalPauses?.firstOrNull { it.status.name == "PENDING" }?.id ?: issue.approvalPauseId,
                    providerBlockReason = issuePullRequest?.takeIf { pr ->
                        pr.checks.any { check ->
                            check.status.equals("COMPLETED", ignoreCase = true) &&
                                !check.conclusion.equals("SUCCESS", ignoreCase = true)
                        } || pr.checksSummary?.contains("FAILURE", ignoreCase = true) == true
                    }?.checksSummary ?: issue.providerBlockReason
                )
            }
        }
        val boundQueue = state.reviewQueue.map { item ->
            if (item.companyId != companyId) {
                item
            } else {
                val snapshot = item.pullRequestNumber?.let(providerBlockByPr::get)
                item.copy(
                    approvalPauseId = boundIssues.firstOrNull { it.id == item.issueId }?.approvalPauseId ?: item.approvalPauseId,
                    providerBlockReason = snapshot?.checksSummary ?: item.providerBlockReason
                )
            }
        }
        return BoundCompanyRuntime(
            runtime = runtime.copy(
                resumableRunCount = resumableRunIds.size,
                waitingApprovalCount = pendingApprovalRunIds.size,
                blockedByPolicyCount = blockedByPolicy,
                blockedByCiCount = blockedByCi,
                resumableRunIds = resumableRunIds,
                pendingApprovalRunIds = pendingApprovalRunIds
            ),
            issues = boundIssues,
            reviewQueue = boundQueue
        )
    }
}
