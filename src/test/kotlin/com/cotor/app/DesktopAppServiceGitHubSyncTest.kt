package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.domain.executor.AgentExecutor
import com.cotor.knowledge.KnowledgeService
import com.cotor.knowledge.KnowledgeStore
import com.cotor.policy.PolicyEngine
import com.cotor.policy.PolicyStore
import com.cotor.providers.github.GitHubControlPlaneService
import com.cotor.providers.github.GitHubControlPlaneStore
import com.cotor.provenance.ProvenanceService
import com.cotor.provenance.ProvenanceStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import java.nio.file.Files

class DesktopAppServiceGitHubSyncTest : FunSpec({
    test("syncGitHubProvider blocks review lanes on failing checks and restores them when checks recover") {
        val appHome = Files.createTempDirectory("desktop-github-sync-home")
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val configRepository = mockk<ConfigRepository>(relaxed = true)
        val agentExecutor = mockk<AgentExecutor>(relaxed = true)
        val githubStore = GitHubControlPlaneStore { appHome }
        val provenanceStore = ProvenanceStore { appHome }
        val knowledgeStore = KnowledgeStore { appHome }
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = configRepository,
            agentExecutor = agentExecutor,
            runtimeBindingService = com.cotor.app.runtime.CompanyRuntimeBindingService(
                gitHubControlPlaneService = GitHubControlPlaneService(store = githubStore)
            ),
            policyEngine = PolicyEngine(PolicyStore { appHome }),
            provenanceService = ProvenanceService(provenanceStore),
            gitHubControlPlaneService = GitHubControlPlaneService(
                store = githubStore,
                provenanceService = ProvenanceService(provenanceStore),
                knowledgeService = KnowledgeService(knowledgeStore)
            ),
            knowledgeService = KnowledgeService(knowledgeStore)
        )

        val company = Company(
            id = "company-1",
            name = "GitHub Sync Co",
            rootPath = ".",
            repositoryId = "repo-1",
            defaultBaseBranch = "main",
            createdAt = 1L,
            updatedAt = 1L
        )
        val issue = CompanyIssue(
            id = "issue-1",
            companyId = company.id,
            goalId = "goal-1",
            workspaceId = "workspace-1",
            title = "Execution issue",
            description = "desc",
            status = IssueStatus.IN_REVIEW,
            pullRequestNumber = 12,
            pullRequestUrl = "https://example.test/pr/12",
            pullRequestState = "OPEN",
            createdAt = 1L,
            updatedAt = 1L
        )
        val queueItem = ReviewQueueItem(
            id = "review-1",
            companyId = company.id,
            issueId = issue.id,
            runId = "run-1",
            worktreePath = Files.createTempDirectory("github-sync-worktree").toString(),
            pullRequestNumber = 12,
            pullRequestUrl = issue.pullRequestUrl,
            pullRequestState = "OPEN",
            status = ReviewQueueStatus.READY_FOR_CEO,
            qaVerdict = "PASS",
            createdAt = 1L,
            updatedAt = 1L
        )
        stateStore.save(
            DesktopAppState(
                companies = listOf(company),
                issues = listOf(issue),
                reviewQueue = listOf(queueItem),
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.STOPPED
                    )
                )
            )
        )

        coEvery {
            gitWorkspaceService.refreshPullRequestMetadata(any(), 12)
        } returnsMany listOf(
            PublishMetadata(
                pullRequestNumber = 12,
                pullRequestUrl = "https://example.test/pr/12",
                pullRequestState = "OPEN",
                reviewState = "APPROVED",
                checksSummary = "ci=COMPLETED/FAILURE",
                mergeability = "CLEAN"
            ),
            PublishMetadata(
                pullRequestNumber = 12,
                pullRequestUrl = "https://example.test/pr/12",
                pullRequestState = "OPEN",
                reviewState = "APPROVED",
                checksSummary = "ci=COMPLETED/SUCCESS",
                mergeability = "CLEAN"
            )
        )

        service.syncGitHubProvider(company.id)
        var afterFail = stateStore.load()
        afterFail.reviewQueue.single().status shouldBe ReviewQueueStatus.FAILED_CHECKS
        afterFail.reviewQueue.single().providerBlockReason shouldBe "ci=COMPLETED/FAILURE"
        afterFail.issues.single().status shouldBe IssueStatus.BLOCKED

        service.syncGitHubProvider(company.id)
        val afterRecovery = stateStore.load()
        afterRecovery.reviewQueue.single().status shouldBe ReviewQueueStatus.READY_FOR_CEO
        afterRecovery.reviewQueue.single().providerBlockReason shouldBe null
        afterRecovery.issues.single().status shouldBe IssueStatus.READY_FOR_CEO
        afterRecovery.issues.single().providerBlockReason shouldBe null

        service.shutdown()
    }

    test("syncGitHubProvider settles merged and closed pull requests into workflow state") {
        val appHome = Files.createTempDirectory("desktop-github-sync-terminal-home")
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val configRepository = mockk<ConfigRepository>(relaxed = true)
        val agentExecutor = mockk<AgentExecutor>(relaxed = true)
        val githubStore = GitHubControlPlaneStore { appHome }
        val provenanceStore = ProvenanceStore { appHome }
        val knowledgeStore = KnowledgeStore { appHome }
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = configRepository,
            agentExecutor = agentExecutor,
            runtimeBindingService = com.cotor.app.runtime.CompanyRuntimeBindingService(
                gitHubControlPlaneService = GitHubControlPlaneService(store = githubStore)
            ),
            policyEngine = PolicyEngine(PolicyStore { appHome }),
            provenanceService = ProvenanceService(provenanceStore),
            gitHubControlPlaneService = GitHubControlPlaneService(
                store = githubStore,
                provenanceService = ProvenanceService(provenanceStore),
                knowledgeService = KnowledgeService(knowledgeStore)
            ),
            knowledgeService = KnowledgeService(knowledgeStore)
        )

        val company = Company(
            id = "company-2",
            name = "GitHub Terminal Co",
            rootPath = ".",
            repositoryId = "repo-2",
            defaultBaseBranch = "main",
            createdAt = 1L,
            updatedAt = 1L
        )
        val mergedIssue = CompanyIssue(
            id = "issue-merged",
            companyId = company.id,
            goalId = "goal-1",
            workspaceId = "workspace-1",
            title = "Merged issue",
            description = "desc",
            status = IssueStatus.READY_FOR_CEO,
            pullRequestNumber = 21,
            pullRequestUrl = "https://example.test/pr/21",
            pullRequestState = "OPEN",
            createdAt = 1L,
            updatedAt = 1L
        )
        val approvalIssue = CompanyIssue(
            id = "approval-merged",
            companyId = company.id,
            goalId = "goal-1",
            workspaceId = "workspace-1",
            title = "CEO approve merged issue",
            description = "desc",
            status = IssueStatus.IN_PROGRESS,
            kind = "approval",
            createdAt = 1L,
            updatedAt = 1L
        )
        val closedIssue = CompanyIssue(
            id = "issue-closed",
            companyId = company.id,
            goalId = "goal-2",
            workspaceId = "workspace-1",
            title = "Closed issue",
            description = "desc",
            status = IssueStatus.IN_REVIEW,
            pullRequestNumber = 22,
            pullRequestUrl = "https://example.test/pr/22",
            pullRequestState = "OPEN",
            createdAt = 1L,
            updatedAt = 1L
        )
        val mergedQueue = ReviewQueueItem(
            id = "review-merged",
            companyId = company.id,
            issueId = mergedIssue.id,
            runId = "run-merged",
            worktreePath = Files.createTempDirectory("github-sync-merged").toString(),
            pullRequestNumber = 21,
            pullRequestUrl = mergedIssue.pullRequestUrl,
            pullRequestState = "OPEN",
            approvalIssueId = approvalIssue.id,
            status = ReviewQueueStatus.READY_TO_MERGE,
            createdAt = 1L,
            updatedAt = 1L
        )
        val closedQueue = ReviewQueueItem(
            id = "review-closed",
            companyId = company.id,
            issueId = closedIssue.id,
            runId = "run-closed",
            worktreePath = Files.createTempDirectory("github-sync-closed").toString(),
            pullRequestNumber = 22,
            pullRequestUrl = closedIssue.pullRequestUrl,
            pullRequestState = "OPEN",
            status = ReviewQueueStatus.READY_FOR_CEO,
            createdAt = 1L,
            updatedAt = 1L
        )
        stateStore.save(
            DesktopAppState(
                companies = listOf(company),
                issues = listOf(mergedIssue, approvalIssue, closedIssue),
                reviewQueue = listOf(mergedQueue, closedQueue),
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(companyId = company.id, status = CompanyRuntimeStatus.STOPPED)
                )
            )
        )

        coEvery { gitWorkspaceService.refreshPullRequestMetadata(any(), 21) } returns PublishMetadata(
            pullRequestNumber = 21,
            pullRequestUrl = "https://example.test/pr/21",
            pullRequestState = "MERGED",
            reviewState = "APPROVED",
            checksSummary = "ci=COMPLETED/SUCCESS",
            mergeability = "CLEAN"
        )
        coEvery { gitWorkspaceService.refreshPullRequestMetadata(any(), 22) } returns PublishMetadata(
            pullRequestNumber = 22,
            pullRequestUrl = "https://example.test/pr/22",
            pullRequestState = "CLOSED",
            reviewState = "CHANGES_REQUESTED",
            checksSummary = "ci=COMPLETED/SUCCESS",
            mergeability = "UNKNOWN"
        )

        service.syncGitHubProvider(company.id)
        val settled = stateStore.load()
        settled.reviewQueue.any { it.id == "review-merged" } shouldBe false
        settled.issues.first { it.id == "issue-merged" }.status shouldBe IssueStatus.DONE
        settled.issues.first { it.id == "approval-merged" }.status shouldBe IssueStatus.DONE
        settled.reviewQueue.first { it.id == "review-closed" }.status shouldBe ReviewQueueStatus.CHANGES_REQUESTED
        settled.issues.first { it.id == "issue-closed" }.status shouldBe IssueStatus.BLOCKED

        service.shutdown()
    }
})
