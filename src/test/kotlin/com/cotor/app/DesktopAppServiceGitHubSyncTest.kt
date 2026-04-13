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
})
