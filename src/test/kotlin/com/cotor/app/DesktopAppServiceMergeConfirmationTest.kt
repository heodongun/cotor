package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.domain.executor.AgentExecutor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.nio.file.Files

class DesktopAppServiceMergeConfirmationTest : FunSpec({
    test("mergeReviewQueueItem waits for remote merged state before marking local workflow merged") {
        val appHome = Files.createTempDirectory("desktop-app-service-merge-confirmation")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-merge-confirmation-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.commentOnPullRequest(any(), 22, any()) } returns Unit
        coEvery {
            gitWorkspaceService.submitPullRequestReview(any(), 22, PullRequestReviewVerdict.APPROVE, any())
        } returns PublishMetadata(
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            reviewState = "APPROVED",
            mergeability = "CLEAN"
        )
        coEvery { gitWorkspaceService.mergePullRequest(any(), 22) } returns PullRequestMergeResult(
            number = 22,
            url = "https://github.com/heodongun/cotor-test/pull/22",
            state = "OPEN",
            mergeCommitSha = null
        )
        coEvery { gitWorkspaceService.refreshPullRequestMetadata(any(), 22) } returns PublishMetadata(
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            reviewState = "APPROVED",
            mergeability = "CLEAN"
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk<AgentExecutor>(relaxed = true)
        )
        val company = service.createCompany(
            name = "CEO Merge Confirmation Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-ceo-merge-confirmation",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Wait for remote merge confirmation",
            description = "Exercise remote merge confirmation behavior.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-ceo-merge-confirmation",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Ship the pending merge PR",
            description = "Execution branch work.",
            status = IssueStatus.READY_FOR_CEO,
            priority = 2,
            kind = "execution",
            branchName = "codex/cotor/ceo-merge-confirmation",
            worktreePath = repoRoot.resolve(".cotor/worktrees/ceo-merge-confirmation/codex").toString(),
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            qaVerdict = "PASS",
            qaFeedback = "QA approved this PR.",
            createdAt = now,
            updatedAt = now
        )
        val approvalIssue = CompanyIssue(
            id = "issue-approval-ceo-merge-confirmation",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "CEO approve Ship the pending merge PR",
            description = "CEO approval gate.",
            status = IssueStatus.IN_PROGRESS,
            priority = 1,
            kind = "approval",
            dependsOn = listOf(executionIssue.id),
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            qaVerdict = "PASS",
            qaFeedback = "QA approved this PR.",
            sourceSignal = "ceo-approval:${executionIssue.id}",
            createdAt = now,
            updatedAt = now
        )
        val reviewQueueItem = ReviewQueueItem(
            id = "rq-ceo-merge-confirmation",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = executionIssue.id,
            runId = "run-execution-ceo-merge-confirmation",
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            status = ReviewQueueStatus.READY_FOR_CEO,
            mergeability = "CLEAN",
            qaVerdict = "PASS",
            qaFeedback = "QA approved this PR.",
            ceoVerdict = "APPROVE",
            ceoFeedback = "Ship it.",
            ceoReviewedAt = now - 500,
            approvalIssueId = approvalIssue.id,
            createdAt = now - 10_000,
            updatedAt = now - 500
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, approvalIssue),
                reviewQueue = baseState.reviewQueue + reviewQueueItem
            )
        )

        val updated = service.mergeReviewQueueItem(reviewQueueItem.id)

        updated.status shouldBe ReviewQueueStatus.READY_FOR_CEO
        updated.pullRequestState shouldBe "OPEN"
        coVerify(exactly = 0) { gitWorkspaceService.syncBaseBranchAfterMerge(any(), any()) }
        coVerify(exactly = 2) { gitWorkspaceService.refreshPullRequestMetadata(any(), 22) }

        val finalState = stateStore.load()
        finalState.reviewQueue.first { it.id == reviewQueueItem.id }.status shouldBe ReviewQueueStatus.READY_FOR_CEO
        finalState.issues.first { it.id == executionIssue.id }.status shouldBe IssueStatus.READY_FOR_CEO
        finalState.issues.first { it.id == executionIssue.id }.mergeResult shouldBe null
    }
})
