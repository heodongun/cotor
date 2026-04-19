package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.domain.executor.AgentExecutor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.nio.file.Files

class DesktopAppServiceReviewVerdictControlTest : FunSpec({
    test("submitQaReviewVerdict moves a review queue item into CEO approval") {
        val appHome = Files.createTempDirectory("desktop-review-verdict-qa-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-review-verdict-qa-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns "https://github.com/example/cotor.git"
        coEvery { gitWorkspaceService.commentOnPullRequest(any(), any(), any()) } returns Unit
        coEvery {
            gitWorkspaceService.submitPullRequestReview(any(), any(), PullRequestReviewVerdict.APPROVE, any())
        } returns PublishMetadata(
            pullRequestNumber = 42,
            pullRequestUrl = "https://github.com/example/cotor/pull/42",
            pullRequestState = "OPEN"
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk<AgentExecutor>(relaxed = true)
        )

        val company = service.createCompany(
            name = "Review Verdict Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-review-verdict",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Ship reviewed work",
            description = "Move reviewed work through QA and CEO lanes.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-review-verdict",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Implement feature",
            description = "Execution issue under review.",
            status = IssueStatus.IN_REVIEW,
            priority = 1,
            kind = "execution",
            branchName = "codex/cotor/review-verdict/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/review-verdict/codex").toString(),
            pullRequestNumber = 42,
            pullRequestUrl = "https://github.com/example/cotor/pull/42",
            pullRequestState = "OPEN",
            createdAt = now,
            updatedAt = now
        )
        val reviewIssue = CompanyIssue(
            id = "issue-qa-review-verdict",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "QA review Implement feature",
            description = "Review the pull request.",
            status = IssueStatus.PLANNED,
            priority = 2,
            kind = "review",
            dependsOn = listOf(executionIssue.id),
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            sourceSignal = "qa-review:${executionIssue.id}",
            createdAt = now,
            updatedAt = now
        )
        val queueItem = ReviewQueueItem(
            id = "queue-review-verdict",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = executionIssue.id,
            runId = "run-review-verdict",
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            status = ReviewQueueStatus.AWAITING_QA,
            qaIssueId = reviewIssue.id,
            createdAt = now,
            updatedAt = now
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, reviewIssue),
                reviewQueue = baseState.reviewQueue + queueItem
            )
        )

        val updated = service.submitQaReviewVerdict(queueItem.id, "PASS", "Looks good")
        val refreshed = stateStore.load()
        val refreshedExecution = refreshed.issues.first { it.id == executionIssue.id }
        val refreshedReview = refreshed.issues.first { it.id == reviewIssue.id }
        val approvalIssue = refreshed.issues.first { it.kind == "approval" && it.sourceSignal == "ceo-approval:${executionIssue.id}" }

        updated.status shouldBe ReviewQueueStatus.READY_FOR_CEO
        updated.qaVerdict shouldBe "PASS"
        updated.approvalIssueId shouldBe approvalIssue.id
        refreshedReview.status shouldBe IssueStatus.DONE
        refreshedReview.qaVerdict shouldBe "PASS"
        refreshedExecution.status shouldBe IssueStatus.READY_FOR_CEO
        refreshedExecution.qaVerdict shouldBe "PASS"
        approvalIssue.status shouldBe IssueStatus.PLANNED
        approvalIssue.qaVerdict shouldBe "PASS"
        coVerify(exactly = 1) {
            gitWorkspaceService.submitPullRequestReview(any(), 42, PullRequestReviewVerdict.APPROVE, any())
        }
    }

    test("submitQaReviewVerdict records requested changes and re-plans execution") {
        val appHome = Files.createTempDirectory("desktop-review-verdict-qa-changes-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-review-verdict-qa-changes-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns "https://github.com/example/cotor.git"
        coEvery { gitWorkspaceService.commentOnPullRequest(any(), any(), any()) } returns Unit
        coEvery {
            gitWorkspaceService.submitPullRequestReview(any(), any(), PullRequestReviewVerdict.REQUEST_CHANGES, any())
        } returns PublishMetadata(
            pullRequestNumber = 43,
            pullRequestUrl = "https://github.com/example/cotor/pull/43",
            pullRequestState = "OPEN"
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk<AgentExecutor>(relaxed = true)
        )

        val company = service.createCompany(
            name = "Review Verdict Changes Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-review-verdict-changes",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Re-plan reviewed work",
            description = "Move reviewed work back to execution when QA requests changes.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-review-verdict-changes",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Implement feature changes",
            description = "Execution issue under review.",
            status = IssueStatus.IN_REVIEW,
            priority = 1,
            kind = "execution",
            branchName = "codex/cotor/review-verdict-changes/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/review-verdict-changes/codex").toString(),
            pullRequestNumber = 43,
            pullRequestUrl = "https://github.com/example/cotor/pull/43",
            pullRequestState = "OPEN",
            createdAt = now,
            updatedAt = now
        )
        val reviewIssue = CompanyIssue(
            id = "issue-qa-review-verdict-changes",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "QA review Implement feature changes",
            description = "Review the pull request.",
            status = IssueStatus.PLANNED,
            priority = 2,
            kind = "review",
            dependsOn = listOf(executionIssue.id),
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            sourceSignal = "qa-review:${executionIssue.id}",
            createdAt = now,
            updatedAt = now
        )
        val queueItem = ReviewQueueItem(
            id = "queue-review-verdict-changes",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = executionIssue.id,
            runId = "run-review-verdict-changes",
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            status = ReviewQueueStatus.AWAITING_QA,
            qaIssueId = reviewIssue.id,
            createdAt = now,
            updatedAt = now
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, reviewIssue),
                reviewQueue = baseState.reviewQueue + queueItem
            )
        )

        val updated = service.submitQaReviewVerdict(queueItem.id, "CHANGES_REQUESTED", "Please revise")
        val refreshed = stateStore.load()
        val refreshedExecution = refreshed.issues.first { it.id == executionIssue.id }
        val refreshedReview = refreshed.issues.first { it.id == reviewIssue.id }

        updated.status shouldBe ReviewQueueStatus.CHANGES_REQUESTED
        updated.qaVerdict shouldBe "CHANGES_REQUESTED"
        refreshedReview.status shouldBe IssueStatus.BLOCKED
        refreshedReview.qaVerdict shouldBe "CHANGES_REQUESTED"
        refreshedExecution.status shouldBe IssueStatus.PLANNED
        refreshedExecution.qaVerdict shouldBe "CHANGES_REQUESTED"
        coVerify(exactly = 1) {
            gitWorkspaceService.submitPullRequestReview(any(), 43, PullRequestReviewVerdict.REQUEST_CHANGES, any())
        }
    }

    test("submitCeoReviewVerdict records changes requested without merging") {
        val appHome = Files.createTempDirectory("desktop-review-verdict-ceo-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-review-verdict-ceo-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns "https://github.com/example/cotor.git"
        coEvery { gitWorkspaceService.commentOnPullRequest(any(), any(), any()) } returns Unit
        coEvery {
            gitWorkspaceService.submitPullRequestReview(any(), any(), PullRequestReviewVerdict.REQUEST_CHANGES, any())
        } returns PublishMetadata(
            pullRequestNumber = 77,
            pullRequestUrl = "https://github.com/example/cotor/pull/77",
            pullRequestState = "OPEN"
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk<AgentExecutor>(relaxed = true)
        )

        val company = service.createCompany(
            name = "CEO Verdict Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-ceo-verdict",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Approve reviewed work",
            description = "Move reviewed work through CEO lane.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-ceo-verdict",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Implement approved feature",
            description = "Execution issue awaiting CEO approval.",
            status = IssueStatus.READY_FOR_CEO,
            priority = 1,
            kind = "execution",
            branchName = "codex/cotor/ceo-verdict/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/ceo-verdict/codex").toString(),
            pullRequestNumber = 77,
            pullRequestUrl = "https://github.com/example/cotor/pull/77",
            pullRequestState = "OPEN",
            qaVerdict = "PASS",
            qaFeedback = "Looks good",
            createdAt = now,
            updatedAt = now
        )
        val approvalIssue = CompanyIssue(
            id = "issue-approval-ceo-verdict",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "CEO approve Implement approved feature",
            description = "Approve or request changes.",
            status = IssueStatus.PLANNED,
            priority = 3,
            kind = "approval",
            dependsOn = listOf("issue-qa-review-ceo-verdict"),
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            qaVerdict = "PASS",
            qaFeedback = "Looks good",
            sourceSignal = "ceo-approval:${executionIssue.id}",
            createdAt = now,
            updatedAt = now
        )
        val queueItem = ReviewQueueItem(
            id = "queue-ceo-verdict",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = executionIssue.id,
            runId = "run-ceo-verdict",
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            status = ReviewQueueStatus.READY_FOR_CEO,
            qaVerdict = "PASS",
            qaFeedback = "Looks good",
            approvalIssueId = approvalIssue.id,
            createdAt = now,
            updatedAt = now
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, approvalIssue),
                reviewQueue = baseState.reviewQueue + queueItem
            )
        )

        val updated = service.submitCeoReviewVerdict(queueItem.id, "CHANGES_REQUESTED", "Needs another pass")
        val refreshed = stateStore.load()
        val refreshedExecution = refreshed.issues.first { it.id == executionIssue.id }
        val refreshedApproval = refreshed.issues.first { it.id == approvalIssue.id }

        updated.status shouldBe ReviewQueueStatus.CHANGES_REQUESTED
        updated.ceoVerdict shouldBe "CHANGES_REQUESTED"
        refreshedApproval.status shouldBe IssueStatus.BLOCKED
        refreshedApproval.ceoVerdict shouldBe "CHANGES_REQUESTED"
        refreshedExecution.status shouldBe IssueStatus.PLANNED
        refreshedExecution.ceoVerdict shouldBe "CHANGES_REQUESTED"
        coVerify(exactly = 1) {
            gitWorkspaceService.submitPullRequestReview(any(), 77, PullRequestReviewVerdict.REQUEST_CHANGES, any())
        }
    }

    test("submitCeoReviewVerdict approves and merges a ready review queue item") {
        val appHome = Files.createTempDirectory("desktop-review-verdict-ceo-approve-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-review-verdict-ceo-approve-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns "https://github.com/example/cotor.git"
        coEvery { gitWorkspaceService.commentOnPullRequest(any(), any(), any()) } returns Unit
        coEvery {
            gitWorkspaceService.submitPullRequestReview(any(), any(), PullRequestReviewVerdict.APPROVE, any())
        } returns PublishMetadata(
            pullRequestNumber = 88,
            pullRequestUrl = "https://github.com/example/cotor/pull/88",
            pullRequestState = "OPEN",
            mergeability = "CLEAN"
        )
        coEvery { gitWorkspaceService.mergePullRequest(any(), 88, true) } returns PullRequestMergeResult(
            number = 88,
            url = "https://github.com/example/cotor/pull/88",
            state = "MERGED",
            mergeCommitSha = "merge-commit-88"
        )
        coEvery { gitWorkspaceService.syncBaseBranchAfterMerge(any(), any()) } returns BaseBranchSyncResult(synced = false)
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk<AgentExecutor>(relaxed = true)
        )

        val company = service.createCompany(
            name = "CEO Approve Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-ceo-approve",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Merge approved work",
            description = "Move approved work through CEO merge.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-ceo-approve",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Implement mergeable feature",
            description = "Execution issue awaiting CEO approval.",
            status = IssueStatus.READY_FOR_CEO,
            priority = 1,
            kind = "execution",
            branchName = "codex/cotor/ceo-approve/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/ceo-approve/codex").toString(),
            pullRequestNumber = 88,
            pullRequestUrl = "https://github.com/example/cotor/pull/88",
            pullRequestState = "OPEN",
            qaVerdict = "PASS",
            qaFeedback = "Looks good",
            createdAt = now,
            updatedAt = now
        )
        val approvalIssue = CompanyIssue(
            id = "issue-approval-ceo-approve",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "CEO approve Implement mergeable feature",
            description = "Approve or request changes.",
            status = IssueStatus.PLANNED,
            priority = 3,
            kind = "approval",
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            qaVerdict = "PASS",
            qaFeedback = "Looks good",
            sourceSignal = "ceo-approval:${executionIssue.id}",
            createdAt = now,
            updatedAt = now
        )
        val queueItem = ReviewQueueItem(
            id = "queue-ceo-approve",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = executionIssue.id,
            runId = "run-ceo-approve",
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            status = ReviewQueueStatus.READY_FOR_CEO,
            mergeability = "CLEAN",
            qaVerdict = "PASS",
            qaFeedback = "Looks good",
            approvalIssueId = approvalIssue.id,
            createdAt = now,
            updatedAt = now
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, approvalIssue),
                reviewQueue = baseState.reviewQueue + queueItem
            )
        )

        val updated = service.submitCeoReviewVerdict(queueItem.id, "APPROVE", "Ship it")
        val refreshed = stateStore.load()
        val refreshedExecution = refreshed.issues.first { it.id == executionIssue.id }
        val refreshedApproval = refreshed.issues.first { it.id == approvalIssue.id }

        updated.status shouldBe ReviewQueueStatus.MERGED
        updated.ceoVerdict shouldBe "APPROVE"
        refreshedExecution.status shouldBe IssueStatus.DONE
        refreshedExecution.ceoVerdict shouldBe "APPROVE"
        refreshedExecution.verificationStatus shouldBe null
        refreshedExecution.verificationSummary shouldBe null
        refreshedApproval.status shouldBe IssueStatus.DONE
        refreshedApproval.ceoVerdict shouldBe "APPROVE"
        coVerify(exactly = 1) {
            gitWorkspaceService.submitPullRequestReview(any(), 88, PullRequestReviewVerdict.APPROVE, any())
        }
        coVerify(exactly = 1) { gitWorkspaceService.mergePullRequest(any(), 88, true) }
    }

    test("submitQaReviewVerdict rejects a missing review queue item") {
        val appHome = Files.createTempDirectory("desktop-review-verdict-missing-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-review-verdict-missing-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk<AgentExecutor>(relaxed = true)
        )

        val error = runCatching {
            service.submitQaReviewVerdict("missing-item", "PASS", "ok")
        }.exceptionOrNull()

        error.shouldNotBeNull()
        (error is IllegalArgumentException) shouldBe true
    }
})
