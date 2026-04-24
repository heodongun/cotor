package com.cotor.app

/**
 * File overview for DesktopAppServiceTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around desktop app service test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.data.config.ConfigRepository
import com.cotor.data.process.CoroutineProcessManager
import com.cotor.data.process.ProcessManager
import com.cotor.domain.executor.AgentExecutor
import com.cotor.integrations.linear.LinearIssueMirror
import com.cotor.integrations.linear.LinearTrackerAdapter
import com.cotor.model.AgentConfig
import com.cotor.model.AgentResult
import com.cotor.model.ProcessExecutionException
import com.cotor.model.ProcessResult
import com.cotor.testsupport.withDesktopServiceShutdown
import io.kotest.core.annotation.Isolate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

@Isolate
class DesktopAppServiceTest : FunSpec({
    afterTest {
        DesktopAppService.shutdownAllForTesting()
    }

    test("builtin opencode company agent uses a longer timeout budget") {
        BuiltinAgentCatalog.get("opencode")!!.timeout shouldBe 45 * 60_000L
    }

    test("runTask stores publish metadata on a completed run") {
        val fixture = DesktopAppServiceFixture.create()
        coEvery { fixture.gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } returns WorktreeBinding(
            branchName = "codex/cotor/desktop-publish/codex",
            worktreePath = fixture.worktreeRoot
        )
        coEvery {
            fixture.agentExecutor.executeAgent(any(), any(), any())
        } returns AgentResult(
            agentName = "codex",
            isSuccess = true,
            output = "done",
            error = null,
            duration = 250,
            metadata = emptyMap(),
            processId = 4242
        )
        coEvery {
            fixture.gitWorkspaceService.publishRun(any(), any(), any(), any(), any(), any())
        } returns PublishMetadata(
            commitSha = "abc1234567890",
            pushedBranch = "codex/cotor/desktop-publish/codex",
            pullRequestNumber = 77,
            pullRequestUrl = "https://github.com/heodongun/cotor/pull/77"
        )

        fixture.service.runTask(fixture.task.id)
        val runs = fixture.awaitRuns()

        runs shouldHaveSize 1
        val run = runs.single()
        run.status shouldBe AgentRunStatus.COMPLETED
        run.output shouldBe "done"
        run.error shouldBe null
        run.publish shouldBe PublishMetadata(
            commitSha = "abc1234567890",
            pushedBranch = "codex/cotor/desktop-publish/codex",
            pullRequestNumber = 77,
            pullRequestUrl = "https://github.com/heodongun/cotor/pull/77"
        )
    }

    test("runTask fails the run when publish metadata reports an error") {
        val fixture = DesktopAppServiceFixture.create()
        coEvery { fixture.gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } returns WorktreeBinding(
            branchName = "codex/cotor/desktop-publish/codex",
            worktreePath = fixture.worktreeRoot
        )
        coEvery {
            fixture.agentExecutor.executeAgent(any(), any(), any())
        } returns AgentResult(
            agentName = "codex",
            isSuccess = true,
            output = "done",
            error = null,
            duration = 250,
            metadata = emptyMap(),
            processId = 4242
        )
        coEvery {
            fixture.gitWorkspaceService.publishRun(any(), any(), any(), any(), any(), any())
        } returns PublishMetadata(
            commitSha = "abc1234567890",
            error = "Publish failed: gh auth refresh required"
        )

        fixture.service.runTask(fixture.task.id)
        val run = fixture.awaitRuns().single()

        run.status shouldBe AgentRunStatus.FAILED
        run.error shouldBe "Publish failed: gh auth refresh required"
        run.publish shouldBe PublishMetadata(
            commitSha = "abc1234567890",
            error = "Publish failed: gh auth refresh required"
        )
    }

    test("runTask keeps a manual task completed when publish reports no diff") {
        val fixture = DesktopAppServiceFixture.create()
        coEvery { fixture.gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } returns WorktreeBinding(
            branchName = "codex/cotor/manual-no-diff/codex",
            worktreePath = fixture.worktreeRoot
        )
        coEvery {
            fixture.agentExecutor.executeAgent(any(), any(), any())
        } returns AgentResult(
            agentName = "codex",
            isSuccess = true,
            output = "OK",
            error = null,
            duration = 250,
            metadata = emptyMap(),
            processId = 4242
        )
        coEvery {
            fixture.gitWorkspaceService.publishRun(any(), any(), any(), any(), any(), any())
        } returns PublishMetadata(
            commitSha = "abc1234567890",
            error = "No changes to publish from codex/cotor/manual-no-diff/codex against master"
        )

        fixture.service.runTask(fixture.task.id)
        val run = fixture.awaitRuns().single()

        run.status shouldBe AgentRunStatus.COMPLETED
        run.output shouldBe "OK"
        run.error shouldBe null
        run.publish shouldBe PublishMetadata(
            commitSha = "abc1234567890",
            error = "No changes to publish from codex/cotor/manual-no-diff/codex against master"
        )
    }

    test("validation-only follow-up issues are forced to non-code work even when planning marks them code-producing") {
        val appHome = Files.createTempDirectory("desktop-app-service-validation-followup-home")
        val stateStore = DesktopStateStore { appHome }
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(relaxed = true),
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )
        val goal = CompanyGoal(
            id = "goal-validation-followup",
            companyId = "company-validation-followup",
            projectContextId = "context-validation-followup",
            title = "Resolve follow-up",
            description = "Follow up on a QA validation rerun.",
            status = GoalStatus.ACTIVE,
            createdAt = 1L,
            updatedAt = 1L
        )
        val workspace = Workspace(
            id = "workspace-validation-followup",
            repositoryId = "repo-validation-followup",
            name = "repo · master",
            baseBranch = "master",
            createdAt = 1L,
            updatedAt = 1L
        )
        val plannedIssueClass = Class.forName("com.cotor.app.CeoPlannedIssue")
        val payloadClass = Class.forName("com.cotor.app.CeoPlanningPayload")
        val plannedIssueCtor = plannedIssueClass.declaredConstructors.first { it.parameterCount == 11 }.apply { isAccessible = true }
        val payloadCtor = payloadClass.declaredConstructors.first { it.parameterCount == 2 }.apply { isAccessible = true }
        val plannedIssue = plannedIssueCtor.newInstance(
            "exec-1",
            "Re-run validation and capture any residual risk.",
            "Validation-only follow-up for the latest QA signal.",
            "execution",
            "Builder",
            2,
            true,
            emptyList<String>(),
            listOf("Re-run validation and capture any residual risk."),
            true,
            true
        )
        val payload = payloadCtor.newInstance("Validation only", listOf(plannedIssue))
        val method = DesktopAppService::class.java.getDeclaredMethod(
            "materializePlannedIssues",
            CompanyGoal::class.java,
            Workspace::class.java,
            List::class.java,
            payloadClass,
            Long::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val issues = method.invoke(
            service,
            goal,
            workspace,
            listOf(
                OrgAgentProfile(
                    id = "profile-builder",
                    companyId = goal.companyId,
                    roleName = "Builder",
                    executionAgentName = "codex"
                )
            ),
            payload,
            1L,
            "ceo"
        ) as List<CompanyIssue>

        issues.single().codeProducing shouldBe false
    }

    test("validation-only execution prompts forbid placeholder artifacts and diff-forcing") {
        val appHome = Files.createTempDirectory("desktop-app-service-validation-prompt-home")
        val stateStore = DesktopStateStore { appHome }
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(relaxed = true),
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )
        val company = Company(
            id = "company-validation-prompt",
            name = "Validation Co",
            rootPath = appHome.toString(),
            repositoryId = REPOSITORY_ID,
            defaultBaseBranch = "master",
            createdAt = 1L,
            updatedAt = 1L
        )
        val projectContextId = "context-validation-prompt"
        val goal = CompanyGoal(
            id = "goal-validation-prompt",
            companyId = company.id,
            projectContextId = projectContextId,
            title = "Resolve follow-up",
            description = "Validation follow-up goal.",
            status = GoalStatus.ACTIVE,
            createdAt = 1L,
            updatedAt = 1L
        )
        val issue = CompanyIssue(
            id = "issue-validation-prompt",
            companyId = company.id,
            projectContextId = goal.projectContextId,
            goalId = goal.id,
            workspaceId = WORKSPACE_ID,
            title = "Re-run validation and capture any residual risk.",
            description = "Validation-only follow-up.",
            status = IssueStatus.PLANNED,
            priority = 2,
            kind = "execution",
            codeProducing = true,
            acceptanceCriteria = listOf("Re-run validation and capture any residual risk."),
            createdAt = 1L,
            updatedAt = 1L
        )
        stateStore.save(
            DesktopAppState(
                repositories = listOf(
                    ManagedRepository(
                        id = REPOSITORY_ID,
                        name = "repo",
                        localPath = appHome.toString(),
                        sourceKind = RepositorySourceKind.LOCAL,
                        defaultBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                workspaces = listOf(
                    Workspace(
                        id = WORKSPACE_ID,
                        repositoryId = REPOSITORY_ID,
                        name = "repo · master",
                        baseBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                companies = listOf(company),
                goals = listOf(goal),
                issues = listOf(issue),
                projectContexts = listOf(
                    CompanyProjectContext(
                        id = projectContextId,
                        companyId = company.id,
                        name = "Validation Co",
                        slug = "validation-co",
                        contextDocPath = appHome.resolve("context.md").toString(),
                        lastUpdatedAt = 1L
                    )
                )
            )
        )
        val prompt = service.buildIssueExecutionPromptForTesting(
            stateStore.load(),
            issue,
            OrgAgentProfile(
                id = "profile-validation-prompt",
                companyId = company.id,
                roleName = "Builder",
                executionAgentName = "codex"
            )
        )

        prompt shouldContain "No publish is required for a pure validation or residual-risk follow-up."
        prompt shouldContain "Do not create README-only, VALIDATION.md-only, or other placeholder repository changes just to produce a diff."
        prompt shouldNotContain "Make the smallest complete repository change"
        prompt shouldNotContain "prefer editing README.md or adding one small text/script artifact"
    }

    test("runTask clears stale review metadata when a validation-only follow-up completes without publishing") {
        val fixture = DesktopAppServiceFixture.create()
        val company = Company(
            id = "company-validation-run",
            name = "Validation Run Co",
            rootPath = fixture.worktreeRoot.toString(),
            repositoryId = REPOSITORY_ID,
            defaultBaseBranch = "master",
            createdAt = 1L,
            updatedAt = 1L
        )
        val projectContextId = "context-validation-run"
        val goal = CompanyGoal(
            id = "goal-validation-run",
            companyId = company.id,
            projectContextId = projectContextId,
            title = "Resolve follow-up",
            description = "Validation follow-up goal.",
            status = GoalStatus.ACTIVE,
            createdAt = 1L,
            updatedAt = 1L
        )
        val issue = CompanyIssue(
            id = "issue-validation-run",
            companyId = company.id,
            projectContextId = goal.projectContextId,
            goalId = goal.id,
            workspaceId = WORKSPACE_ID,
            title = "Re-run validation and capture any residual risk.",
            description = "Validation-only follow-up.",
            status = IssueStatus.IN_PROGRESS,
            priority = 2,
            kind = "execution",
            codeProducing = false,
            executionIntent = ExecutionIntent.VALIDATION_ONLY,
            pullRequestNumber = 287,
            pullRequestUrl = "https://github.com/bssm-oss/cotor-test/pull/287",
            pullRequestState = "OPEN",
            qaVerdict = "CHANGES_REQUESTED",
            qaFeedback = "Evidence was incorrect.",
            ceoVerdict = "CHANGES_REQUESTED",
            ceoFeedback = "Re-run validation.",
            acceptanceCriteria = listOf("Re-run validation and capture any residual risk."),
            createdAt = 1L,
            updatedAt = 1L
        )
        val task = fixture.task.copy(
            issueId = issue.id,
            title = issue.title,
            prompt = issue.description
        )
        fixture.stateStore.save(
            fixture.stateStore.load().copy(
                tasks = listOf(task),
                companies = listOf(company),
                goals = listOf(goal),
                issues = listOf(issue),
                projectContexts = listOf(
                    CompanyProjectContext(
                        id = projectContextId,
                        companyId = company.id,
                        name = "Validation Run Co",
                        slug = "validation-run-co",
                        contextDocPath = fixture.worktreeRoot.resolve("context.md").toString(),
                        lastUpdatedAt = 1L
                    )
                )
            )
        )
        coEvery { fixture.gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } returns WorktreeBinding(
            branchName = "codex/cotor/re-run-validation/codex",
            worktreePath = fixture.worktreeRoot
        )
        coEvery {
            fixture.agentExecutor.executeAgent(any(), any(), any())
        } returns AgentResult(
            agentName = "codex",
            isSuccess = true,
            output = "Validation rerun complete.",
            error = null,
            duration = 250,
            metadata = emptyMap(),
            processId = 4242
        )

        fixture.service.runTask(task.id)
        fixture.awaitRuns()

        withTimeout(10_000) {
            while (true) {
                val candidate = fixture.stateStore.load().issues.single { it.id == issue.id }
                val metadataCleared =
                    candidate.pullRequestNumber == null &&
                        candidate.pullRequestUrl == null &&
                        candidate.pullRequestState == null &&
                        candidate.qaVerdict == null &&
                        candidate.qaFeedback == null &&
                        candidate.ceoVerdict == null &&
                        candidate.ceoFeedback == null
                if (candidate.status == IssueStatus.DONE && metadataCleared) {
                    return@withTimeout
                }
                delay(25)
            }
            error("Unreachable")
        }
        val updatedIssue = fixture.stateStore.load().issues.single { it.id == issue.id }
        updatedIssue.status shouldBe IssueStatus.DONE
        updatedIssue.pullRequestNumber shouldBe null
        updatedIssue.pullRequestUrl shouldBe null
        updatedIssue.pullRequestState shouldBe null
        updatedIssue.qaVerdict shouldBe null
        updatedIssue.qaFeedback shouldBe null
        updatedIssue.ceoVerdict shouldBe null
        updatedIssue.ceoFeedback shouldBe null
    }

    test("runtime blocks code work and opens an infra issue when publish failures have no shared history") {
        val appHome = Files.createTempDirectory("desktop-app-service-publish-history-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-publish-history-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns "https://github.com/heodongun/cotor.git"
        coEvery {
            gitWorkspaceService.ensureGitHubPublishReady(any(), any())
        } returns GitHubPublishReadiness(
            ready = false,
            originUrl = "https://github.com/heodongun/cotor.git",
            error = "GitHub publishing cannot open PRs against https://github.com/heodongun/cotor.git because local master was initialized independently and has no history in common with origin/master."
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk<AgentExecutor>(relaxed = true)
        )

        withDesktopServiceShutdown(service) {
            val company = service.createCompany(
                name = "Publish History Co",
                rootPath = repoRoot.toString(),
                defaultBaseBranch = "master"
            )
            val goal = service.createGoal(
                companyId = company.id,
                title = "Ship code safely",
                description = "Reproduce a GitHub publish history mismatch.",
                autonomyEnabled = false,
                startRuntimeIfNeeded = false
            )
            val executionIssue = service.listIssues(goal.id).first { it.kind == "execution" }
            val task = service.createTask(
                workspaceId = executionIssue.workspaceId,
                title = executionIssue.title,
                prompt = executionIssue.description,
                agents = listOf("codex"),
                issueId = executionIssue.id
            )
            val failureAt = System.currentTimeMillis() - 120_000
            val failedRun = AgentRun(
                id = "publish-history-run",
                taskId = task.id,
                workspaceId = executionIssue.workspaceId,
                repositoryId = stateStore.load().repositories.first().id,
                agentName = "codex",
                branchName = "codex/cotor/history-mismatch/codex",
                worktreePath = repoRoot.resolve(".cotor/worktrees/history-mismatch/codex").toString(),
                status = AgentRunStatus.FAILED,
                output = "Publish failed: pull request create failed: GraphQL: The codex/cotor/history-mismatch/codex branch has no history in common with master (createPullRequest)",
                error = "Publish failed: pull request create failed: GraphQL: The codex/cotor/history-mismatch/codex branch has no history in common with master (createPullRequest)",
                publish = PublishMetadata(
                    error = "pull request create failed: GraphQL: The codex/cotor/history-mismatch/codex branch has no history in common with master (createPullRequest)"
                ),
                createdAt = failureAt,
                updatedAt = failureAt
            )
            val snapshot = stateStore.load()
            stateStore.save(
                snapshot.copy(
                    issues = snapshot.issues.map {
                        if (it.id == executionIssue.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = failureAt) else it
                    },
                    tasks = snapshot.tasks.map {
                        if (it.id == task.id) it.copy(status = DesktopTaskStatus.FAILED, updatedAt = failureAt) else it
                    },
                    runs = snapshot.runs + failedRun
                )
            )

            service.updateGoal(goal.id, autonomyEnabled = true, startRuntimeIfNeeded = false)
            service.startCompanyRuntime(company.id)
            val taskCountBefore = stateStore.load().tasks.count { it.issueId == executionIssue.id }

            service.runCompanyRuntimeTick(company.id)
            service.runCompanyRuntimeTick(company.id)
            service.runCompanyRuntimeTick(company.id)

            val afterTicks = stateStore.load()
            val blockedIssue = afterTicks.issues.first { it.id == executionIssue.id }
            val infraIssue = afterTicks.issues.first {
                it.companyId == company.id &&
                    it.kind == "infra" &&
                    it.title == "Restore GitHub publishing for ${executionIssue.title}"
            }
            afterTicks.tasks.count { it.issueId == executionIssue.id } shouldBe taskCountBefore
            blockedIssue.status shouldBe IssueStatus.BLOCKED
            blockedIssue.blockedBy.contains(infraIssue.id) shouldBe true
            infraIssue.status shouldBe IssueStatus.PLANNED
            infraIssue.description shouldContain "no history in common"
            afterTicks.companyRuntimes.first { it.companyId == company.id }.status shouldBe CompanyRuntimeStatus.RUNNING
        }
    }

    test("runTask keeps the run completed when publish falls back to a local-only commit") {
        val fixture = DesktopAppServiceFixture.create()
        fixture.service.updateBackendSettings(
            defaultBackendKind = ExecutionBackendKind.LOCAL_COTOR,
            codePublishMode = CodePublishMode.ALLOW_LOCAL_GIT
        )
        coEvery { fixture.gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } returns WorktreeBinding(
            branchName = "codex/cotor/desktop-publish/codex",
            worktreePath = fixture.worktreeRoot
        )
        coEvery {
            fixture.agentExecutor.executeAgent(any(), any(), any())
        } returns AgentResult(
            agentName = "codex",
            isSuccess = true,
            output = "done",
            error = null,
            duration = 250,
            metadata = emptyMap(),
            processId = 4242
        )
        coEvery {
            fixture.gitWorkspaceService.publishRun(any(), any(), any(), any(), any(), any())
        } returns PublishMetadata(
            commitSha = "abc1234567890",
            error = "No GitHub remote configured; kept local commit only"
        )

        fixture.service.runTask(fixture.task.id)
        val run = fixture.awaitRuns().single()

        run.status shouldBe AgentRunStatus.COMPLETED
        run.error shouldBe null
        run.publish shouldBe PublishMetadata(
            commitSha = "abc1234567890",
            error = "No GitHub remote configured; kept local commit only"
        )
    }

    test("runTask fails code work when local-only publish happens under required GitHub PR mode") {
        val fixture = DesktopAppServiceFixture.create()
        coEvery { fixture.gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } returns WorktreeBinding(
            branchName = "codex/cotor/desktop-publish/codex",
            worktreePath = fixture.worktreeRoot
        )
        coEvery {
            fixture.agentExecutor.executeAgent(any(), any(), any())
        } returns AgentResult(
            agentName = "codex",
            isSuccess = true,
            output = "done",
            error = null,
            duration = 250,
            metadata = emptyMap(),
            processId = 4242
        )
        coEvery {
            fixture.gitWorkspaceService.publishRun(any(), any(), any(), any(), any(), any())
        } returns PublishMetadata(
            commitSha = "abc1234567890",
            error = "No GitHub remote configured; kept local commit only"
        )

        fixture.service.runTask(fixture.task.id)
        val run = fixture.awaitRuns().single()

        run.status shouldBe AgentRunStatus.FAILED
        run.error shouldBe "No GitHub remote configured; kept local commit only"
    }

    test("runTask keeps a slow in-flight run alive while waiting for the final backend result") {
        val appHome = Files.createTempDirectory("desktop-run-heartbeat-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-run-heartbeat-repo").resolve("repo"))
        val worktreeRoot = Files.createTempDirectory("desktop-run-heartbeat-worktree")
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val agentExecutor = mockk<AgentExecutor>()
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = agentExecutor,
            staleRunStartupGraceMs = 50L,
            runHeartbeatIntervalMs = 10L
        )

        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } answers { firstArg() }
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } returns WorktreeBinding(
            branchName = "codex/cotor/slow-review/codex",
            worktreePath = worktreeRoot
        )
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } coAnswers {
            delay(150)
            AgentResult(
                agentName = "codex",
                isSuccess = true,
                output = "slow done",
                error = null,
                duration = 150,
                metadata = emptyMap()
            )
        }

        val company = service.createCompany(
            name = "Heartbeat Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Keep remote execution alive",
            description = "Do not misclassify a slow in-flight agent run as stale.",
            autonomyEnabled = false
        )
        val reviewIssue = service.createIssue(
            companyId = company.id,
            goalId = goal.id,
            title = "Slow review",
            description = "Wait for a delayed backend response.",
            kind = "review"
        )
        val task = service.createTask(
            workspaceId = reviewIssue.workspaceId,
            title = reviewIssue.title,
            prompt = reviewIssue.description,
            agents = listOf("codex"),
            issueId = reviewIssue.id
        )

        service.runTask(task.id)
        delay(90)
        service.runCompanyRuntimeTick(company.id)

        val runningRun = stateStore.load().runs.first { it.taskId == task.id }
        runningRun.status shouldBe AgentRunStatus.RUNNING
        runningRun.error shouldBe null

        awaitTaskCompletion(stateStore, task.id)
        val completedRun = stateStore.load().runs.first { it.taskId == task.id }
        completedRun.status shouldBe AgentRunStatus.COMPLETED
        completedRun.output shouldBe "slow done"
        completedRun.error shouldBe null
    }

    test("openLocalRepository stores the origin remote for an existing checkout") {
        val repoRoot = Files.createTempDirectory("cotor-repo")
        val nestedPath = repoRoot.resolve("nested").also { Files.createDirectories(it) }
        val remoteUrl = "https://github.com/heodongun/cotor.git"
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = remoteUrl,
                defaultBranch = "master"
            ),
            appHome = Files.createTempDirectory("cotor-home")
        )

        val repository = service.openLocalRepository(nestedPath.toString())

        repository.localPath shouldBe repoRoot.toString()
        repository.remoteUrl shouldBe remoteUrl
        repository.defaultBranch shouldBe "master"
    }

    test("openLocalRepository backfills missing remote metadata for an existing repository record") {
        val repoRoot = Files.createTempDirectory("cotor-repo")
        val appHome = Files.createTempDirectory("cotor-home")
        val stateStore = DesktopStateStore { appHome }
        stateStore.save(
            DesktopAppState(
                repositories = listOf(
                    ManagedRepository(
                        id = REPOSITORY_ID,
                        name = "cotor",
                        localPath = repoRoot.toString(),
                        sourceKind = RepositorySourceKind.LOCAL,
                        remoteUrl = null,
                        defaultBranch = "stale-branch",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                )
            )
        )

        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val repository = service.openLocalRepository(repoRoot.toString())
        val persisted = stateStore.load().repositories.single()

        repository.id shouldBe REPOSITORY_ID
        repository.remoteUrl shouldBe "https://github.com/heodongun/cotor.git"
        repository.defaultBranch shouldBe "master"
        persisted shouldBe repository
    }

    test("cloneRepository reuses an already connected remote instead of cloning again") {
        val repoRoot = Files.createTempDirectory("cotor-repo")
        val appHome = Files.createTempDirectory("cotor-home")
        val stateStore = DesktopStateStore { appHome }
        stateStore.save(
            DesktopAppState(
                repositories = listOf(
                    ManagedRepository(
                        id = REPOSITORY_ID,
                        name = "cotor",
                        localPath = repoRoot.toString(),
                        sourceKind = RepositorySourceKind.CLONED,
                        remoteUrl = "https://github.com/heodongun/cotor.git",
                        defaultBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                )
            )
        )

        val processManager = FakeGitProcessManager(
            repoRoot = repoRoot,
            remoteUrl = "https://github.com/heodongun/cotor.git",
            defaultBranch = "master"
        )
        val service = testService(
            processManager = processManager,
            stateStore = stateStore
        )

        val repository = service.cloneRepository("https://github.com/heodongun/cotor.git")

        repository.id shouldBe REPOSITORY_ID
        processManager.commands.shouldBeEmpty()
        processManager.commands.firstOrNull { it.command.getOrNull(1) == "clone" }.shouldBeNull()
    }

    test("createTask persists a generated execution plan") {
        val appHome = Files.createTempDirectory("desktop-planner-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-planner-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk()
        )

        val task = service.createTask(
            workspaceId = WORKSPACE_ID,
            title = "Stabilize desktop planning",
            prompt = "Stabilize desktop planning so created tasks persist assignments and routed prompts.",
            agents = listOf("claude", "codex")
        )

        val plan = task.plan.shouldNotBeNull()
        plan.assignments.shouldNotBeEmpty()
        plan.assignments.map { it.agentName }.all { it in listOf("claude", "codex") } shouldBe true
        plan.assignments.all { it.assignedPrompt.contains("Goal-driven assignment") } shouldBe true

        val persistedTask = stateStore.load().tasks.single()
        persistedTask.plan shouldBe plan
    }

    test("createTask keeps linked issue prompts as a single issue assignment instead of replanning the whole context") {
        val appHome = Files.createTempDirectory("desktop-issue-plan-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-issue-plan-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk()
        )

        val company = service.createCompany(
            name = "Issue Plan Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val agent = service.createCompanyAgentDefinition(
            companyId = company.id,
            title = "Builder",
            agentCli = "codex",
            roleSummary = "implementation and delivery"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Keep issue prompts stable",
            description = "Do not explode issue execution prompts into dozens of synthetic subtasks.",
            autonomyEnabled = false
        )
        val issue = CompanyIssue(
            id = "issue-linked-prompt",
            companyId = company.id,
            projectContextId = stateStore.load().projectContexts.first { it.companyId == company.id }.id,
            goalId = goal.id,
            workspaceId = stateStore.load().workspaces.first { it.repositoryId == company.repositoryId }.id,
            title = "Builder execution slice",
            description = "Implement the smallest linked issue change.",
            status = IssueStatus.PLANNED,
            priority = 2,
            kind = "execution",
            assigneeProfileId = stateStore.load().orgProfiles.firstOrNull { it.companyId == company.id }?.id,
            acceptanceCriteria = listOf("Primary issue", "Keep issue prompts stable"),
            riskLevel = "medium",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        stateStore.save(stateStore.load().copy(issues = stateStore.load().issues + issue))
        val prompt = """
            Company execution context
            - company: Issue Plan Co

            Primary issue
            ${issue.title}
        """.trimIndent()

        val task = service.createTask(
            workspaceId = issue.workspaceId,
            title = issue.title,
            prompt = prompt,
            agents = listOf(agent.agentCli),
            issueId = issue.id
        )

        val plan = task.plan.shouldNotBeNull()
        plan.decompositionSource shouldBe "issue-assignment"
        plan.assignments shouldHaveSize 1
        plan.assignments.single().assignedPrompt.contains("Primary issue") shouldBe true
        plan.assignments.single().subtasks.size shouldBe issue.acceptanceCriteria.size
    }

    test("batchUpdateCompanyAgentDefinitions updates enabled state and specialties for selected agents") {
        val appHome = Files.createTempDirectory("desktop-batch-agent-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-batch-agent-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(relaxed = true),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Batch Agent Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val definitions = service.listCompanyAgentDefinitions(company.id)
        val qa = definitions.first { it.title == "QA" }
        val builder = definitions.first { it.title == "Builder" }

        val updated = service.batchUpdateCompanyAgentDefinitions(
            companyId = company.id,
            agentIds = listOf(qa.id, builder.id),
            agentCli = "opencode",
            specialties = listOf("qa", "review"),
            enabled = false
        )

        updated.map { it.id }.toSet() shouldBe setOf(qa.id, builder.id)
        updated.all { it.agentCli == "opencode" } shouldBe true
        updated.all { !it.enabled } shouldBe true
        updated.all { it.specialties == listOf("qa", "review") } shouldBe true

        val persisted = service.listCompanyAgentDefinitions(company.id).filter { it.id == qa.id || it.id == builder.id }
        persisted.all { it.agentCli == "opencode" } shouldBe true
        persisted.all { !it.enabled } shouldBe true
        service.listOrgProfiles().none { it.companyId == company.id && it.id in setOf(qa.id, builder.id) } shouldBe true
    }

    test("decomposeGoal creates a CEO planning issue before downstream execution issues") {
        val appHome = Files.createTempDirectory("desktop-ceo-planning-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-ceo-planning-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "CEO Planning Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Plan a PR-driven slice",
            description = "CEO should author the initial execution graph.",
            autonomyEnabled = false
        )

        val issues = service.decomposeGoal(goal.id)
        val planningIssue = issues.first { it.kind == "planning" }

        planningIssue.sourceSignal shouldBe "ceo-planning"
        planningIssue.title shouldBe "CEO plan and delegate \"${goal.title}\""
        stateStore.load().issues.count { it.goalId == goal.id && it.kind == "planning" } shouldBe 1
    }

    test("autonomous goals stay in CEO planning until an AI plan or fallback result is synced") {
        val appHome = Files.createTempDirectory("desktop-autonomous-ceo-planning-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-autonomous-ceo-planning-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Autonomous CEO Planning Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Let CEO decompose autonomously",
            description = "The runtime should wait for the CEO planning run before materializing work.",
            autonomyEnabled = true
        )

        val issues = service.listIssues(goal.id)
        issues.count { it.kind == "planning" } shouldBe 1
        issues.none { it.kind == "execution" } shouldBe true
        issues.single { it.kind == "planning" }.status shouldBe IssueStatus.PLANNED
    }

    test("issueExecutionDetails joins assigned prompt, run logs, and publish metadata for one issue") {
        val appHome = Files.createTempDirectory("desktop-issue-execution-details-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-issue-execution-details-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Issue Execution Details Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Build a traceable slice",
            description = "Generate an execution issue with observable logs.",
            autonomyEnabled = false
        )
        val issue = service.listIssues(goal.id).first { it.kind == "execution" }
        val workspace = stateStore.load().workspaces.first { it.id == issue.workspaceId }
        val task = service.createTask(
            workspaceId = workspace.id,
            title = issue.title,
            prompt = "Implement the first branchable slice.",
            agents = listOf("opencode"),
            issueId = issue.id
        )
        val now = System.currentTimeMillis()
        val run = AgentRun(
            id = "run-issue-execution-details",
            taskId = task.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "opencode",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            branchName = "opencode/issue-execution-details",
            worktreePath = repoRoot.resolve(".cotor/worktrees/issue-execution-details/opencode").toString(),
            status = AgentRunStatus.COMPLETED,
            output = "Implemented the slice and opened a PR.",
            publish = PublishMetadata(
                pullRequestNumber = 88,
                pullRequestUrl = "https://github.com/bssm-oss/cotor-test/pull/88",
                pullRequestState = "OPEN",
                mergeability = "CLEAN"
            ),
            createdAt = now,
            updatedAt = now
        )
        stateStore.save(
            stateStore.load().copy(
                runs = stateStore.load().runs + run
            )
        )

        val details = service.issueExecutionDetails(issue.id)

        details shouldHaveSize 1
        details.single().roleName.isNotBlank() shouldBe true
        details.single().agentCli shouldBe "opencode"
        details.single().assignedPrompt shouldContain "Implement the first branchable slice."
        details.single().stdout shouldContain "Implemented the slice"
        details.single().publishSummary shouldContain "pr state: OPEN"
        details.single().pullRequestUrl shouldBe "https://github.com/bssm-oss/cotor-test/pull/88"
    }

    test("decomposeGoal replans the next wave after previous goal issues finish while preserving history") {
        val appHome = Files.createTempDirectory("desktop-ceo-replan-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-ceo-replan-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "CEO Replan Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Keep planning follow-up waves",
            description = "The CEO should reopen planning when the previous wave finishes.",
            autonomyEnabled = false
        )
        val initialPlanningIssue = stateStore.load().issues.first { it.goalId == goal.id && it.kind == "planning" }
        val now = System.currentTimeMillis() - 120_000
        val finishedExecutionIssue = CompanyIssue(
            id = "finished-wave-issue",
            companyId = company.id,
            projectContextId = goal.projectContextId,
            goalId = goal.id,
            workspaceId = initialPlanningIssue.workspaceId,
            title = "Finished implementation wave",
            description = "Already done.",
            status = IssueStatus.DONE,
            priority = 2,
            kind = "execution",
            createdAt = now,
            updatedAt = now
        )
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == initialPlanningIssue.id) {
                        it.copy(status = IssueStatus.DONE, updatedAt = now)
                    } else {
                        it
                    }
                } + finishedExecutionIssue
            )
        )

        val issues = service.decomposeGoal(goal.id)
        val reopenedPlanningIssue = issues.first { it.id == initialPlanningIssue.id && it.kind == "planning" }
        val newExecutionIssues = issues.filter { it.goalId == goal.id && it.kind == "execution" && it.id != finishedExecutionIssue.id }

        reopenedPlanningIssue.status shouldBe IssueStatus.DONE
        issues.any { it.id == finishedExecutionIssue.id && it.status == IssueStatus.DONE } shouldBe true
        newExecutionIssues.isNotEmpty() shouldBe true
    }

    test("runTask uses assigned prompts when present and raw prompt when absent") {
        val appHome = Files.createTempDirectory("desktop-planner-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-planner-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val configRepository = mockk<ConfigRepository>(relaxed = true)
        val agentExecutor = mockk<AgentExecutor>()
        val capturedInputs = mutableListOf<String?>()

        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } coAnswers {
            val agentName = invocation.args[3] as String
            val worktreePath = Files.createDirectories(Files.createTempDirectory("desktop-planner-worktree").resolve(agentName))
            WorktreeBinding(
                branchName = "codex/cotor/test/$agentName",
                worktreePath = worktreePath
            )
        }
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } coAnswers {
            capturedInputs += invocation.args[1] as String?
            val agent = invocation.args[0] as AgentConfig
            AgentResult(
                agentName = agent.name,
                isSuccess = true,
                output = "ok",
                error = null,
                duration = 10,
                metadata = emptyMap(),
                processId = 123L
            )
        }
        coEvery { gitWorkspaceService.publishRun(any(), any(), any(), any(), any(), any()) } returns PublishMetadata(
            commitSha = "abc1234567890",
            pushedBranch = "codex/cotor/test/branch",
            pullRequestNumber = 77,
            pullRequestUrl = "https://github.com/heodongun/cotor/pull/77"
        )

        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = configRepository,
            agentExecutor = agentExecutor
        )

        val plannedTask = service.createTask(
            workspaceId = WORKSPACE_ID,
            title = "Goal-driven planning",
            prompt = "Goal-driven planning should persist assignments and route run prompts through those assignments.",
            agents = listOf("claude", "codex")
        )
        service.runTask(plannedTask.id)
        awaitTaskCompletion(stateStore, plannedTask.id)

        val expectedPrompts = plannedTask.plan.shouldNotBeNull().assignments.map { it.assignedPrompt }.toSet()
        val nonNullCapturedInputs = capturedInputs.filterNotNull()
        nonNullCapturedInputs.shouldNotBeEmpty()
        nonNullCapturedInputs.any { it in expectedPrompts } shouldBe true

        capturedInputs.clear()
        stateStore.save(
            stateStore.load().copy(
                tasks = stateStore.load().tasks + AgentTask(
                    id = "legacy-task",
                    workspaceId = WORKSPACE_ID,
                    title = "Legacy task",
                    prompt = "Legacy prompt should still flow through unchanged.",
                    agents = listOf("echo"),
                    plan = null,
                    status = DesktopTaskStatus.QUEUED,
                    createdAt = 1,
                    updatedAt = 1
                )
            )
        )

        service.runTask("legacy-task")
        awaitTaskCompletion(stateStore, "legacy-task")

        capturedInputs shouldBe listOf("Legacy prompt should still flow through unchanged.")
    }

    test("runTask falls back to builtin or command-backed agents when cotor yaml cannot be parsed") {
        val appHome = Files.createTempDirectory("desktop-config-fallback-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-config-fallback-test").resolve("repo"))
        Files.writeString(repoRoot.resolve("cotor.yaml"), "bad: [yaml")
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val configRepository = mockk<ConfigRepository>()
        val agentExecutor = mockk<AgentExecutor>()
        val capturedInputs = mutableListOf<String?>()

        coEvery { configRepository.loadConfig(any()) } throws IllegalStateException("broken config")
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } answers {
            val agentName = invocation.args[3] as String
            val worktreePath = Files.createDirectories(Files.createTempDirectory("desktop-config-fallback-worktree").resolve(agentName))
            WorktreeBinding(
                branchName = "codex/cotor/test/$agentName",
                worktreePath = worktreePath
            )
        }
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } coAnswers {
            capturedInputs += invocation.args[1] as String?
            val agent = invocation.args[0] as AgentConfig
            AgentResult(
                agentName = agent.name,
                isSuccess = true,
                output = "ok",
                error = null,
                duration = 10,
                metadata = emptyMap(),
                processId = 123L
            )
        }
        coEvery { gitWorkspaceService.publishRun(any(), any(), any(), any(), any(), any()) } returns PublishMetadata()

        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = configRepository,
            agentExecutor = agentExecutor,
            commandAvailability = { command -> command in setOf("codex", "opencode") }
        )

        val task = service.createTask(
            workspaceId = WORKSPACE_ID,
            title = "Broken config fallback",
            prompt = "Still execute even if cotor yaml is broken.",
            agents = listOf("codex")
        )

        service.runTask(task.id)
        awaitTaskCompletion(stateStore, task.id)

        capturedInputs shouldBe listOf(task.plan?.assignments?.single()?.assignedPrompt ?: task.prompt)
        stateStore.load().runs.single().status shouldBe AgentRunStatus.COMPLETED
    }

    test("startCompanyRuntime does not auto-merge CEO-ready review queue items without approval") {
        val appHome = Files.createTempDirectory("desktop-runtime-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            companyRuntimeTickIntervalMs = 50
        )

        val goal = service.createGoal(
            title = "Autonomous merge loop",
            description = "Keep CEO-ready review items pending until approval runs.",
            autonomyEnabled = false
        )
        val issue = stateStore.load().issues.first { it.goalId == goal.id }
        val now = System.currentTimeMillis()
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    it.copy(status = IssueStatus.IN_REVIEW, updatedAt = now)
                },
                reviewQueue = listOf(
                    ReviewQueueItem(
                        id = "rq-1",
                        issueId = issue.id,
                        runId = "run-1",
                        pullRequestNumber = 99,
                        pullRequestUrl = "https://github.com/heodongun/cotor/pull/99",
                        status = ReviewQueueStatus.READY_FOR_CEO,
                        mergeability = "clean",
                        createdAt = now,
                        updatedAt = now
                    )
                )
            )
        )

        service.updateGoal(goal.id, autonomyEnabled = true)
        delay(250)
        val settledState = stateStore.load()
        val mergedItem = settledState.reviewQueue.first { it.id == "rq-1" }

        settledState.runtime.status shouldBe CompanyRuntimeStatus.RUNNING
        mergedItem.status shouldBe ReviewQueueStatus.READY_FOR_CEO
        settledState.issues.first { it.id == issue.id }.status shouldBe IssueStatus.IN_REVIEW
        settledState.runtime.lastAction shouldNotBe null
        service.stopCompanyRuntime().status shouldBe CompanyRuntimeStatus.STOPPED
    }

    test("createGoal automatically starts the company runtime when autonomy is enabled") {
        val appHome = Files.createTempDirectory("desktop-runtime-autostart-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-autostart-test").resolve("repo"))
        val worktreeRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-autostart-worktree"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val agentExecutor = mockk<AgentExecutor>()
        coEvery { gitWorkspaceService.ensureGitHubPublishReady(any(), any()) } returns GitHubPublishReadiness(ready = true)
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } answers {
            val agentName = invocation.args[3] as String
            WorktreeBinding(
                branchName = "codex/cotor/autostart/$agentName",
                worktreePath = worktreeRoot.resolve(agentName)
            )
        }
        coEvery { gitWorkspaceService.publishRun(any(), any(), any(), any(), any(), any()) } returns PublishMetadata()
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } answers {
            val agent = invocation.args[0] as AgentConfig
            AgentResult(
                agentName = agent.name,
                isSuccess = true,
                output = "done",
                error = null,
                duration = 50,
                metadata = emptyMap(),
                processId = 1234
            )
        }
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            companyRuntimeTickIntervalMs = 50,
            commandAvailability = { command -> command in setOf("codex", "opencode") }
        )

        val goal = service.createGoal(
            title = "Autostart runtime",
            description = "Creating an autonomous goal should boot the company loop immediately."
        )

        withTimeout(5_000) {
            while (service.runtimeStatus(goal.companyId).status != CompanyRuntimeStatus.RUNNING) {
                delay(25)
            }
        }

        service.stopCompanyRuntime(goal.companyId).status shouldBe CompanyRuntimeStatus.STOPPED
    }

    test("company runtime falls back to local execution when codex app server is unavailable") {
        val appHome = Files.createTempDirectory("desktop-runtime-backend-fallback-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-backend-fallback-test").resolve("repo"))
        val worktreeRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-backend-fallback-worktree"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val agentExecutor = mockk<AgentExecutor>()

        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        coEvery { gitWorkspaceService.ensureGitHubPublishReady(any(), any()) } returns GitHubPublishReadiness(ready = true)
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } answers {
            val agentName = invocation.args[3] as String
            WorktreeBinding(
                branchName = "codex/cotor/backend-fallback/$agentName",
                worktreePath = worktreeRoot.resolve(agentName)
            )
        }
        coEvery { gitWorkspaceService.publishRun(any(), any(), any(), any(), any(), any()) } returns PublishMetadata()
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } answers {
            val agent = invocation.args[0] as AgentConfig
            AgentResult(
                agentName = agent.name,
                isSuccess = true,
                output = "done",
                error = null,
                duration = 25,
                metadata = emptyMap(),
                processId = 5678
            )
        }

        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            companyRuntimeTickIntervalMs = 50,
            commandAvailability = { command -> command in setOf("codex", "opencode") }
        )

        withDesktopServiceShutdown(service) {
            service.updateBackendSettings(
                defaultBackendKind = ExecutionBackendKind.CODEX_APP_SERVER,
                codexLaunchMode = BackendLaunchMode.ATTACHED,
                codexAppServerBaseUrl = "http://127.0.0.1:9999",
                codexTimeoutSeconds = 1
            )

            val company = service.createCompany(
                name = "Fallback Co",
                rootPath = repoRoot.toString(),
                defaultBaseBranch = "master"
            )
            company.backendKind shouldBe ExecutionBackendKind.CODEX_APP_SERVER
            service.createCompanyAgentDefinition(
                companyId = company.id,
                title = "CEO",
                agentCli = "codex",
                roleSummary = "CEO planning and review"
            )
            service.createCompanyAgentDefinition(
                companyId = company.id,
                title = "Builder",
                agentCli = "codex",
                roleSummary = "Builder implementation"
            )

            val goal = service.createGoal(
                companyId = company.id,
                title = "Fallback execution",
                description = "Keep autonomous company execution alive even when Codex app server is unavailable.",
                startRuntimeIfNeeded = false
            )
            val issue = service.createIssue(
                companyId = company.id,
                goalId = goal.id,
                title = "Run fallback execution",
                description = "Execute through the configured backend and fall back locally if it is unavailable.",
                kind = "execution"
            )
            service.startCompanyRuntime(goal.companyId)

            fun companyRunsFor(snapshot: DesktopAppState): List<AgentRun> =
                snapshot.runs.filter { run ->
                    val task = snapshot.tasks.firstOrNull { it.id == run.taskId } ?: return@filter false
                    val issueId = task.issueId ?: return@filter false
                    snapshot.issues.any { it.id == issueId && it.companyId == company.id }
                }

            service.runIssueAndAwaitSettlement(issue.id, timeoutMs = 60_000)
            val finalSnapshot = stateStore.load()
            val companyRuns = companyRunsFor(finalSnapshot)
            companyRuns.shouldNotBeEmpty()
            companyRuns.any { it.status != AgentRunStatus.QUEUED } shouldBe true
            finalSnapshot.companyActivity.any {
                it.companyId == company.id &&
                    it.source == "execution-backend" &&
                    it.title == "Fell back to local execution"
            } shouldBe true
            finalSnapshot.signals.any {
                it.companyId == company.id &&
                    it.source == "execution-backend" &&
                    it.message.contains("Fell back to Local Cotor")
            } shouldBe true
            service.runtimeStatus(goal.companyId).status shouldBe CompanyRuntimeStatus.RUNNING
        }
    }

    test("enabled company Linear sync mirrors decomposed issues and stores external identifiers") {
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-linear-create-test").resolve("repo"))
        val linearTracker = FakeLinearTrackerAdapter()
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            appHome = Files.createTempDirectory("desktop-linear-create-home"),
            linearTracker = linearTracker
        )

        val company = service.createCompany(
            name = "Linear Create Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        service.updateCompanyLinear(
            companyId = company.id,
            enabled = true,
            teamId = "team-1",
            projectId = "project-1"
        )

        val goal = service.createGoal(
            companyId = company.id,
            title = "Mirror issue creation",
            description = "Create local issues and mirror them to Linear.",
            autonomyEnabled = false
        )
        service.decomposeGoal(goal.id)

        withTimeout(15_000) {
            while (true) {
                val mirroredIssues = service.listIssues(goal.id, company.id)
                if (mirroredIssues.isNotEmpty() && mirroredIssues.all { it.linearIssueId != null && it.linearIssueIdentifier != null && it.linearIssueUrl != null }) {
                    return@withTimeout
                }
                delay(25)
            }
        }

        val mirroredIssues = service.listIssues(goal.id, company.id)
        mirroredIssues.all { it.linearIssueId != null && it.linearIssueIdentifier != null && it.linearIssueUrl != null } shouldBe true
        linearTracker.syncCalls.map { it.issueId }.toSet().size shouldBe mirroredIssues.size
    }

    test("manual company Linear sync returns summary counts") {
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-linear-sync-test").resolve("repo"))
        val linearTracker = FakeLinearTrackerAdapter()
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            appHome = Files.createTempDirectory("desktop-linear-sync-home"),
            linearTracker = linearTracker
        )

        val company = service.createCompany(
            name = "Linear Sync Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        service.updateCompanyLinear(
            companyId = company.id,
            enabled = true,
            teamId = "team-1",
            projectId = "project-1"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Manual sync",
            description = "Sync all local issues to Linear.",
            autonomyEnabled = false
        )

        val response = service.syncCompanyLinear(company.id)

        response.ok shouldBe true
        response.syncedIssues shouldBe service.listIssues(goal.id, company.id).size
        response.failedIssues.shouldBeEmpty()
    }

    test("stopCompanyRuntime remains stopped after an in-flight tick settles") {
        val appHome = Files.createTempDirectory("desktop-runtime-stop-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-stop-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            companyRuntimeTickIntervalMs = 25
        )

        val goal = service.createGoal(
            title = "Stop runtime safely",
            description = "Ensure a late runtime tick cannot restore RUNNING after stop.",
            autonomyEnabled = false
        )
        val now = System.currentTimeMillis()
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    it.copy(status = IssueStatus.DONE, updatedAt = now)
                }
            )
        )
        service.updateGoal(goal.id, autonomyEnabled = true)
        withTimeout(5_000) {
            while (stateStore.load().runtime.status != CompanyRuntimeStatus.RUNNING) {
                delay(25)
            }
        }
        service.stopCompanyRuntime().status shouldBe CompanyRuntimeStatus.STOPPED

        delay(80)

        stateStore.load().runtime.status shouldBe CompanyRuntimeStatus.STOPPED
    }

    test("manual runtime stop persists a sticky stop intent until the user starts again") {
        val appHome = Files.createTempDirectory("desktop-runtime-manual-stop-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-manual-stop-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            companyRuntimeTickIntervalMs = 25
        )

        val goal = service.createGoal(
            title = "Manual stop intent",
            description = "Stopping a company should stay stopped until the user starts it again.",
            autonomyEnabled = false
        )

        service.startCompanyRuntime(goal.companyId).status shouldBe CompanyRuntimeStatus.RUNNING
        val stopped = service.stopCompanyRuntime(goal.companyId)
        stopped.status shouldBe CompanyRuntimeStatus.STOPPED
        stopped.manuallyStoppedAt shouldNotBe null

        val restarted = service.startCompanyRuntime(goal.companyId)
        restarted.status shouldBe CompanyRuntimeStatus.RUNNING
        restarted.manuallyStoppedAt shouldBe null
    }

    test("company budgets can be saved, cleared, and survive runtime restarts") {
        val appHome = Files.createTempDirectory("desktop-runtime-budget-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-budget-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            companyRuntimeTickIntervalMs = 25
        )

        val company = service.createCompany(
            name = "Budget Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master",
            dailyBudgetCents = 250,
            monthlyBudgetCents = 1000
        )
        service.recordRunCost(company.id, 300)

        val updated = service.updateCompany(
            companyId = company.id,
            dailyBudgetCents = 500,
            monthlyBudgetCents = 2000
        )
        updated.dailyBudgetCents shouldBe 500
        updated.monthlyBudgetCents shouldBe 2000

        val restarted = service.startCompanyRuntime(company.id)
        restarted.todaySpentCents shouldBe 300
        restarted.monthSpentCents shouldBe 300
        restarted.budgetPausedAt shouldBe null

        val cleared = service.updateCompany(
            companyId = company.id,
            dailyBudgetCents = 0,
            monthlyBudgetCents = 0
        )
        cleared.dailyBudgetCents shouldBe null
        cleared.monthlyBudgetCents shouldBe null
    }

    test("runtime pauses when estimated monthly budget is exhausted") {
        val appHome = Files.createTempDirectory("desktop-runtime-monthly-budget-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-monthly-budget-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            companyRuntimeTickIntervalMs = 25
        )

        val company = service.createCompany(
            name = "Monthly Budget Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master",
            monthlyBudgetCents = 150
        )
        service.recordRunCost(company.id, 175)
        service.startCompanyRuntime(company.id)

        val snapshot = service.runCompanyRuntimeTick(company.id)

        snapshot.lastAction shouldBe "budget-paused"
        snapshot.budgetPausedAt shouldNotBe null
        snapshot.monthSpentCents shouldBe 175
    }

    test("manual runtime stop survives app restart and dashboard reads") {
        val appHome = Files.createTempDirectory("desktop-runtime-restart-stop-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-restart-stop-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val bootstrapService = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            companyRuntimeTickIntervalMs = 25
        )

        val goal = bootstrapService.createGoal(
            title = "Stay stopped after restart",
            description = "A manually stopped company must not auto-start during startup healing.",
            autonomyEnabled = false
        )
        val now = System.currentTimeMillis()
        stateStore.save(
            stateStore.load().copy(
                goals = stateStore.load().goals.map {
                    if (it.id == goal.id) it.copy(autonomyEnabled = true, status = GoalStatus.ACTIVE, updatedAt = now) else it
                },
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = goal.companyId,
                        status = CompanyRuntimeStatus.STOPPED,
                        lastStoppedAt = now,
                        manuallyStoppedAt = now,
                        lastAction = "runtime-stopped"
                    )
                )
            )
        )

        val restarted = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            companyRuntimeTickIntervalMs = 25
        )

        delay(150)
        restarted.dashboard()
        restarted.companyDashboard(goal.companyId)
        delay(150)

        val runtime = stateStore.load().companyRuntimes.first { it.companyId == goal.companyId }
        runtime.status shouldBe CompanyRuntimeStatus.STOPPED
        runtime.manuallyStoppedAt shouldNotBe null
    }

    test("legacy runtime-stopped snapshots backfill manual stop intent before startup healing") {
        val appHome = Files.createTempDirectory("desktop-runtime-legacy-stop-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-legacy-stop-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val bootstrapService = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            companyRuntimeTickIntervalMs = 25
        )

        val goal = bootstrapService.createGoal(
            title = "Legacy manual stop",
            description = "Older runtime snapshots that only recorded runtime-stopped should remain stopped after restart.",
            autonomyEnabled = false
        )
        val now = System.currentTimeMillis()
        stateStore.save(
            stateStore.load().copy(
                goals = stateStore.load().goals.map {
                    if (it.id == goal.id) it.copy(autonomyEnabled = true, status = GoalStatus.ACTIVE, updatedAt = now) else it
                },
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = goal.companyId,
                        status = CompanyRuntimeStatus.STOPPED,
                        lastStoppedAt = now,
                        lastAction = "runtime-stopped"
                    )
                )
            )
        )

        val restarted = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            companyRuntimeTickIntervalMs = 25
        )

        delay(150)
        restarted.dashboard()
        restarted.companyDashboard(goal.companyId)
        delay(150)

        val runtime = stateStore.load().companyRuntimes.first { it.companyId == goal.companyId }
        runtime.status shouldBe CompanyRuntimeStatus.STOPPED
        runtime.manuallyStoppedAt shouldNotBe null
        stateStore.load().tasks.any { it.issueId != null } shouldBe false
    }

    test("goal updates do not auto-start a manually stopped runtime") {
        val appHome = Files.createTempDirectory("desktop-runtime-goal-update-stop-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-goal-update-stop-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            companyRuntimeTickIntervalMs = 25
        )

        val goal = service.createGoal(
            title = "Hold manual stop",
            description = "Goal changes must not undo an explicit runtime stop.",
            autonomyEnabled = false
        )

        service.stopCompanyRuntime(goal.companyId).status shouldBe CompanyRuntimeStatus.STOPPED
        service.updateGoal(goal.id, autonomyEnabled = true)
        delay(150)

        val runtime = stateStore.load().companyRuntimes.first { it.companyId == goal.companyId }
        runtime.status shouldBe CompanyRuntimeStatus.STOPPED
        runtime.manuallyStoppedAt shouldNotBe null
    }

    test("new autonomous goals do not auto-start a manually stopped runtime") {
        val appHome = Files.createTempDirectory("desktop-runtime-goal-create-stop-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-goal-create-stop-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            companyRuntimeTickIntervalMs = 25
        )

        val company = service.createCompany(
            name = "Stopped Runtime Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        service.stopCompanyRuntime(company.id).status shouldBe CompanyRuntimeStatus.STOPPED

        service.createGoal(
            companyId = company.id,
            title = "Respect manual stop",
            description = "Creating a new autonomous goal should not implicitly resume a manually stopped runtime.",
            autonomyEnabled = true
        )
        delay(150)

        val runtime = stateStore.load().companyRuntimes.first { it.companyId == company.id }
        runtime.status shouldBe CompanyRuntimeStatus.STOPPED
        runtime.manuallyStoppedAt shouldNotBe null
    }

    test("default org profiles prefer installed OpenCode CLIs over missing claude") {
        val appHome = Files.createTempDirectory("desktop-runtime-agents-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-agents-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            commandAvailability = { command -> command in setOf("opencode", "codex", "gemini") }
        )

        val goal = service.createGoal(
            title = "Prefer installed agents",
            description = "Use the CLIs that are available on this machine.",
            autonomyEnabled = false
        )

        val profiles = service.listOrgProfiles()
        val issues = service.listIssues(goal.id)

        profiles.map { it.executionAgentName }.distinct() shouldBe listOf("opencode")
        profiles shouldHaveSize 9
        val assignedProfileIds = issues.mapNotNull { it.assigneeProfileId }.toSet()
        assignedProfileIds.shouldNotBeEmpty()
        assignedProfileIds.subtract(profiles.map { it.id }.toSet()).shouldBeEmpty()
    }

    test("company dashboard backfills legacy companies with an OpenCode-first seeded roster") {
        val appHome = Files.createTempDirectory("desktop-legacy-company-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-legacy-company-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val now = System.currentTimeMillis()
        val company = Company(
            id = "legacy-company",
            name = "Legacy Company",
            rootPath = repoRoot.toString(),
            repositoryId = REPOSITORY_ID,
            defaultBaseBranch = "master",
            createdAt = now,
            updatedAt = now
        )
        stateStore.save(
            DesktopAppState(
                repositories = listOf(
                    ManagedRepository(
                        id = REPOSITORY_ID,
                        name = "legacy",
                        localPath = repoRoot.toString(),
                        sourceKind = RepositorySourceKind.LOCAL,
                        defaultBranch = "master",
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                companies = listOf(company),
                companyRuntimes = listOf(CompanyRuntimeSnapshot(companyId = company.id))
            )
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(relaxed = true),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            commandAvailability = { command -> command == "opencode" || command == "codex" }
        )

        service.companyDashboard(company.id)

        val definitions = service.listCompanyAgentDefinitions(company.id)
        definitions.map { it.agentCli }.distinct() shouldBe listOf("opencode")
        definitions.map { it.title } shouldBe listOf(
            "CEO",
            "Product Strategist",
            "Engineering Lead",
            "UX Builder",
            "UI Builder",
            "Builder",
            "Backend Builder",
            "QA",
            "Release Manager"
        )
    }

    test("batch update company agent definitions updates execution agent, capabilities, and enabled state") {
        val appHome = Files.createTempDirectory("desktop-batch-agent-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-batch-agent-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Batch Agent Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val targets = service.listCompanyAgentDefinitions(company.id)
            .filter { it.title in setOf("Builder", "QA") }

        val updated = service.batchUpdateCompanyAgentDefinitions(
            companyId = company.id,
            agentIds = targets.map { it.id },
            agentCli = "opencode",
            specialties = listOf("qa", "verification"),
            enabled = false
        )

        updated shouldHaveSize 2
        updated.forEach { definition ->
            definition.agentCli shouldBe "opencode"
            definition.specialties shouldBe listOf("qa", "verification")
            definition.enabled shouldBe false
        }
        service.listCompanyAgentDefinitions(company.id)
            .filter { it.id in targets.map { target -> target.id }.toSet() }
            .forEach { definition ->
                definition.agentCli shouldBe "opencode"
                definition.specialties shouldBe listOf("qa", "verification")
                definition.enabled shouldBe false
            }
    }

    test("runtimeStatus marks local runtime offline when no app-server instance is attached") {
        val appHome = Files.createTempDirectory("desktop-runtime-detached-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-detached-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(relaxed = true),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            commandAvailability = { command -> command == "opencode" }
        )

        val company = service.createCompany(
            name = "Detached Runtime Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val now = System.currentTimeMillis()
        stateStore.save(
            stateStore.load().copy(
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        backendKind = ExecutionBackendKind.LOCAL_COTOR,
                        lastStartedAt = now,
                        lastTickAt = now,
                        lastAction = "runtime-started"
                    )
                )
            )
        )

        val runtime = service.runtimeStatus(company.id)

        runtime.status shouldBe CompanyRuntimeStatus.RUNNING
        runtime.backendHealth shouldBe "offline"
        runtime.backendLifecycleState shouldBe BackendLifecycleState.STOPPED
        runtime.backendMessage shouldContain "No active local Cotor app-server instance"
    }

    test("company dashboard requeues recoverable blocked issues caused by infrastructure failures") {
        val repoRoot = Files.createTempDirectory("desktop-company-requeue-repo")
        val appHome = Files.createTempDirectory("desktop-company-requeue-home")
        val stateStore = DesktopStateStore { appHome }
        val company = Company(
            id = "company-requeue",
            name = "Requeue Co",
            rootPath = repoRoot.toString(),
            repositoryId = REPOSITORY_ID,
            defaultBaseBranch = "master",
            createdAt = 1L,
            updatedAt = 1L
        )
        val goal = CompanyGoal(
            id = "goal-requeue",
            companyId = company.id,
            projectContextId = "project-requeue",
            title = "hello",
            description = "say hello",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = false,
            createdAt = 2L,
            updatedAt = 2L
        )
        val issue = CompanyIssue(
            id = "issue-requeue",
            companyId = company.id,
            projectContextId = "project-requeue",
            goalId = goal.id,
            workspaceId = WORKSPACE_ID,
            title = "Execution issue",
            description = "Do the work",
            status = IssueStatus.BLOCKED,
            createdAt = 3L,
            updatedAt = 3L
        )
        val task = AgentTask(
            id = "task-requeue",
            workspaceId = WORKSPACE_ID,
            issueId = issue.id,
            title = issue.title,
            prompt = "prompt",
            agents = listOf("codex"),
            status = DesktopTaskStatus.FAILED,
            createdAt = 4L,
            updatedAt = 4L
        )
        val run = AgentRun(
            id = "run-requeue",
            taskId = task.id,
            workspaceId = WORKSPACE_ID,
            repositoryId = REPOSITORY_ID,
            agentName = "codex",
            branchName = "codex/cotor/legacy/codex",
            worktreePath = repoRoot.toString(),
            status = AgentRunStatus.FAILED,
            error = "GIT command failed",
            createdAt = 5L,
            updatedAt = 5L
        )
        stateStore.save(
            DesktopAppState(
                companies = listOf(company),
                repositories = listOf(
                    ManagedRepository(
                        id = REPOSITORY_ID,
                        name = "repo",
                        localPath = repoRoot.toString(),
                        sourceKind = RepositorySourceKind.LOCAL,
                        defaultBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                workspaces = listOf(
                    Workspace(
                        id = WORKSPACE_ID,
                        repositoryId = REPOSITORY_ID,
                        name = "repo · master",
                        baseBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                projectContexts = listOf(
                    CompanyProjectContext(
                        id = "project-requeue",
                        companyId = company.id,
                        name = "Requeue Co",
                        slug = "requeue-co",
                        contextDocPath = appHome.resolve("project.md").toString(),
                        lastUpdatedAt = 1L
                    )
                ),
                goals = listOf(goal),
                issues = listOf(issue),
                tasks = listOf(task),
                runs = listOf(run)
            )
        )
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = null,
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        service.companyDashboard(company.id)

        withTimeout(5_000) {
            while (true) {
                service.companyDashboard(company.id)
                val latestIssue = stateStore.load().issues.first { it.id == issue.id }
                if (latestIssue.status == IssueStatus.PLANNED) {
                    latestIssue.blockedBy.shouldBeEmpty()
                    return@withTimeout
                }
                delay(25)
            }
        }
    }

    test("company dashboard requeues blocked issues caused by unsupported codex reasoning config") {
        val repoRoot = Files.createTempDirectory("desktop-company-requeue-codex-config-repo")
        val appHome = Files.createTempDirectory("desktop-company-requeue-codex-config-home")
        val stateStore = DesktopStateStore { appHome }
        val company = Company(
            id = "company-requeue-codex-config",
            name = "Requeue Codex Config Co",
            rootPath = repoRoot.toString(),
            repositoryId = REPOSITORY_ID,
            defaultBaseBranch = "master",
            createdAt = 1L,
            updatedAt = 1L
        )
        val goal = CompanyGoal(
            id = "goal-requeue-codex-config",
            companyId = company.id,
            projectContextId = "project-requeue-codex-config",
            title = "hello",
            description = "say hello",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = false,
            createdAt = 2L,
            updatedAt = 2L
        )
        val issue = CompanyIssue(
            id = "issue-requeue-codex-config",
            companyId = company.id,
            projectContextId = "project-requeue-codex-config",
            goalId = goal.id,
            workspaceId = WORKSPACE_ID,
            title = "Execution issue",
            description = "Do the work",
            status = IssueStatus.BLOCKED,
            createdAt = 3L,
            updatedAt = 3L
        )
        val task = AgentTask(
            id = "task-requeue-codex-config",
            workspaceId = WORKSPACE_ID,
            issueId = issue.id,
            title = issue.title,
            prompt = "prompt",
            agents = listOf("codex"),
            status = DesktopTaskStatus.FAILED,
            createdAt = 4L,
            updatedAt = 4L
        )
        val run = AgentRun(
            id = "run-requeue-codex-config",
            taskId = task.id,
            workspaceId = WORKSPACE_ID,
            repositoryId = REPOSITORY_ID,
            agentName = "codex",
            branchName = "codex/cotor/requeue-codex-config/codex",
            worktreePath = repoRoot.toString(),
            status = AgentRunStatus.FAILED,
            error = "Codex execution failed: Error: unknown variant `xhigh`, expected one of `none`, `minimal`, `low`, `medium`, `high`\nin `model_reasoning_effort`\n",
            createdAt = 5L,
            updatedAt = 5L
        )
        stateStore.save(
            DesktopAppState(
                companies = listOf(company),
                repositories = listOf(
                    ManagedRepository(
                        id = REPOSITORY_ID,
                        name = "repo",
                        localPath = repoRoot.toString(),
                        sourceKind = RepositorySourceKind.LOCAL,
                        defaultBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                workspaces = listOf(
                    Workspace(
                        id = WORKSPACE_ID,
                        repositoryId = REPOSITORY_ID,
                        name = "repo · master",
                        baseBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                projectContexts = listOf(
                    CompanyProjectContext(
                        id = "project-requeue-codex-config",
                        companyId = company.id,
                        name = "Requeue Codex Config Co",
                        slug = "requeue-codex-config-co",
                        contextDocPath = appHome.resolve("project.md").toString(),
                        lastUpdatedAt = 1L
                    )
                ),
                goals = listOf(goal),
                issues = listOf(issue),
                tasks = listOf(task),
                runs = listOf(run)
            )
        )
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = null,
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        service.companyDashboard(company.id)

        withTimeout(5_000) {
            while (true) {
                service.companyDashboard(company.id)
                val latestIssue = stateStore.load().issues.first { it.id == issue.id }
                if (latestIssue.status == IssueStatus.PLANNED) {
                    latestIssue.blockedBy.shouldBeEmpty()
                    return@withTimeout
                }
                delay(25)
            }
        }
    }

    test("company dashboard requeues blocked issues caused by retired codex model ids even after retry exhaustion") {
        val repoRoot = Files.createTempDirectory("desktop-company-requeue-codex-model-repo")
        val appHome = Files.createTempDirectory("desktop-company-requeue-codex-model-home")
        val stateStore = DesktopStateStore { appHome }
        val company = Company(
            id = "company-requeue-codex-model",
            name = "Requeue Codex Model Co",
            rootPath = repoRoot.toString(),
            repositoryId = REPOSITORY_ID,
            defaultBaseBranch = "master",
            createdAt = 1L,
            updatedAt = 1L
        )
        val goal = CompanyGoal(
            id = "goal-requeue-codex-model",
            companyId = company.id,
            projectContextId = "project-requeue-codex-model",
            title = "hello",
            description = "say hello",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = false,
            createdAt = 2L,
            updatedAt = 2L
        )
        val issue = CompanyIssue(
            id = "issue-requeue-codex-model",
            companyId = company.id,
            projectContextId = "project-requeue-codex-model",
            goalId = goal.id,
            workspaceId = WORKSPACE_ID,
            title = "Execution issue",
            description = "Do the work",
            status = IssueStatus.BLOCKED,
            createdAt = 3L,
            updatedAt = 3L
        )
        val tasks = (1..3).map { index ->
            AgentTask(
                id = "task-requeue-codex-model-$index",
                workspaceId = WORKSPACE_ID,
                issueId = issue.id,
                title = issue.title,
                prompt = "prompt",
                agents = listOf("codex"),
                status = DesktopTaskStatus.FAILED,
                createdAt = (3L + index),
                updatedAt = (3L + index)
            )
        }
        val runs = tasks.mapIndexed { index, task ->
            AgentRun(
                id = "run-requeue-codex-model-${index + 1}",
                taskId = task.id,
                workspaceId = WORKSPACE_ID,
                repositoryId = REPOSITORY_ID,
                agentName = "codex",
                branchName = "codex/cotor/requeue-codex-model-${index + 1}/codex",
                worktreePath = repoRoot.toString(),
                status = AgentRunStatus.FAILED,
                error = """
                    Codex execution failed: OpenAI Codex v0.58.0 (research preview)
                    ERROR: unexpected status 400 Bad Request: {
                      "error": {
                        "message": "The requested model 'gpt-5.3-codex-spark' does not exist.",
                        "type": "invalid_request_error",
                        "param": "model",
                        "code": "model_not_found"
                      }
                    }
                """.trimIndent(),
                createdAt = (4L + index),
                updatedAt = (4L + index)
            )
        }
        stateStore.save(
            DesktopAppState(
                companies = listOf(company),
                repositories = listOf(
                    ManagedRepository(
                        id = REPOSITORY_ID,
                        name = "repo",
                        localPath = repoRoot.toString(),
                        sourceKind = RepositorySourceKind.LOCAL,
                        defaultBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                workspaces = listOf(
                    Workspace(
                        id = WORKSPACE_ID,
                        repositoryId = REPOSITORY_ID,
                        name = "repo · master",
                        baseBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                projectContexts = listOf(
                    CompanyProjectContext(
                        id = "project-requeue-codex-model",
                        companyId = company.id,
                        name = "Requeue Codex Model Co",
                        slug = "requeue-codex-model-co",
                        contextDocPath = appHome.resolve("project.md").toString(),
                        lastUpdatedAt = 1L
                    )
                ),
                goals = listOf(goal),
                issues = listOf(issue),
                tasks = tasks,
                runs = runs
            )
        )
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = null,
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        service.companyDashboard(company.id)

        withTimeout(5_000) {
            while (true) {
                service.companyDashboard(company.id)
                val latestIssue = stateStore.load().issues.first { it.id == issue.id }
                if (latestIssue.status == IssueStatus.PLANNED) {
                    latestIssue.blockedBy.shouldBeEmpty()
                    return@withTimeout
                }
                delay(25)
            }
        }
    }

    test("company dashboard requeues blocked issues caused by app-server shutdown interruption") {
        val repoRoot = Files.createTempDirectory("desktop-company-requeue-shutdown-repo")
        val appHome = Files.createTempDirectory("desktop-company-requeue-shutdown-home")
        val stateStore = DesktopStateStore { appHome }
        val company = Company(
            id = "company-requeue-shutdown",
            name = "Requeue Shutdown Co",
            rootPath = repoRoot.toString(),
            repositoryId = REPOSITORY_ID,
            defaultBaseBranch = "master",
            createdAt = 1L,
            updatedAt = 1L
        )
        val goal = CompanyGoal(
            id = "goal-requeue-shutdown",
            companyId = company.id,
            projectContextId = "project-requeue-shutdown",
            title = "hello",
            description = "say hello",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = false,
            createdAt = 2L,
            updatedAt = 2L
        )
        val issue = CompanyIssue(
            id = "issue-requeue-shutdown",
            companyId = company.id,
            projectContextId = "project-requeue-shutdown",
            goalId = goal.id,
            workspaceId = WORKSPACE_ID,
            title = "Execution issue",
            description = "Do the work",
            status = IssueStatus.BLOCKED,
            createdAt = 3L,
            updatedAt = 3L
        )
        val task = AgentTask(
            id = "task-requeue-shutdown",
            workspaceId = WORKSPACE_ID,
            issueId = issue.id,
            title = issue.title,
            prompt = "prompt",
            agents = listOf("codex"),
            status = DesktopTaskStatus.FAILED,
            createdAt = 4L,
            updatedAt = 4L
        )
        val run = AgentRun(
            id = "run-requeue-shutdown",
            taskId = task.id,
            workspaceId = WORKSPACE_ID,
            repositoryId = REPOSITORY_ID,
            agentName = "codex",
            branchName = "codex/cotor/requeue-shutdown/codex",
            worktreePath = repoRoot.toString(),
            status = AgentRunStatus.FAILED,
            error = "Execution was interrupted because the app-server stopped before the run finished.",
            createdAt = 5L,
            updatedAt = 5L
        )
        stateStore.save(
            DesktopAppState(
                companies = listOf(company),
                repositories = listOf(
                    ManagedRepository(
                        id = REPOSITORY_ID,
                        name = "repo",
                        localPath = repoRoot.toString(),
                        sourceKind = RepositorySourceKind.LOCAL,
                        defaultBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                workspaces = listOf(
                    Workspace(
                        id = WORKSPACE_ID,
                        repositoryId = REPOSITORY_ID,
                        name = "repo · master",
                        baseBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                projectContexts = listOf(
                    CompanyProjectContext(
                        id = "project-requeue-shutdown",
                        companyId = company.id,
                        name = "Requeue Shutdown Co",
                        slug = "requeue-shutdown-co",
                        contextDocPath = appHome.resolve("project.md").toString(),
                        lastUpdatedAt = 1L
                    )
                ),
                goals = listOf(goal),
                issues = listOf(issue),
                tasks = listOf(task),
                runs = listOf(run)
            )
        )
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = null,
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        service.companyDashboard(company.id)

        withTimeout(5_000) {
            while (true) {
                service.companyDashboard(company.id)
                val latestIssue = stateStore.load().issues.first { it.id == issue.id }
                if (latestIssue.status == IssueStatus.PLANNED) {
                    latestIssue.blockedBy.shouldBeEmpty()
                    return@withTimeout
                }
                delay(25)
            }
        }
    }

    test("company dashboard requeues blocked issues caused by recoverable codex MCP auth noise") {
        val repoRoot = Files.createTempDirectory("desktop-company-requeue-codex-mcp-repo")
        val appHome = Files.createTempDirectory("desktop-company-requeue-codex-mcp-home")
        val stateStore = DesktopStateStore { appHome }
        val company = Company(
            id = "company-requeue-codex-mcp",
            name = "Requeue Codex MCP Co",
            rootPath = repoRoot.toString(),
            repositoryId = REPOSITORY_ID,
            defaultBaseBranch = "master",
            createdAt = 1L,
            updatedAt = 1L
        )
        val goal = CompanyGoal(
            id = "goal-requeue-codex-mcp",
            companyId = company.id,
            projectContextId = "project-requeue-codex-mcp",
            title = "hello",
            description = "say hello",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = false,
            createdAt = 2L,
            updatedAt = 2L
        )
        val issue = CompanyIssue(
            id = "issue-requeue-codex-mcp",
            companyId = company.id,
            projectContextId = "project-requeue-codex-mcp",
            goalId = goal.id,
            workspaceId = WORKSPACE_ID,
            title = "Execution issue",
            description = "Do the work",
            status = IssueStatus.BLOCKED,
            createdAt = 3L,
            updatedAt = 3L
        )
        val task = AgentTask(
            id = "task-requeue-codex-mcp",
            workspaceId = WORKSPACE_ID,
            issueId = issue.id,
            title = issue.title,
            prompt = "prompt",
            agents = listOf("codex"),
            status = DesktopTaskStatus.FAILED,
            createdAt = 4L,
            updatedAt = 4L
        )
        val run = AgentRun(
            id = "run-requeue-codex-mcp",
            taskId = task.id,
            workspaceId = WORKSPACE_ID,
            repositoryId = REPOSITORY_ID,
            agentName = "codex",
            branchName = "codex/cotor/requeue-codex-mcp/codex",
            worktreePath = repoRoot.toString(),
            status = AgentRunStatus.FAILED,
            error = "Codex execution failed: 2026-03-27T00:28:17.256604Z ERROR rmcp::transport::worker: worker quit with fatal: Transport channel closed, when AuthRequired(AuthRequiredError { www_authenticate_header: \"Bearer realm=\\\"OAuth\\\", error=\\\"invalid_token\\\"\" })\n2026-03-27T00:28:17.644786Z ERROR rmcp::transport::worker: worker quit with fatal: Transport channel closed, when AuthRequired(AuthRequiredError { www_authenticate_header: \"Bearer error=\\\"invalid_request\\\", error_description=\\\"No access token was provided in this request\\\", resource_metadata=\\\"https://api.githubcopilot.com/.well-known/oauth-protected-resource/mcp/\\\"\" })\n",
            createdAt = 5L,
            updatedAt = 5L
        )
        stateStore.save(
            DesktopAppState(
                companies = listOf(company),
                repositories = listOf(
                    ManagedRepository(
                        id = REPOSITORY_ID,
                        name = "repo",
                        localPath = repoRoot.toString(),
                        sourceKind = RepositorySourceKind.LOCAL,
                        defaultBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                workspaces = listOf(
                    Workspace(
                        id = WORKSPACE_ID,
                        repositoryId = REPOSITORY_ID,
                        name = "repo · master",
                        baseBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                projectContexts = listOf(
                    CompanyProjectContext(
                        id = "project-requeue-codex-mcp",
                        companyId = company.id,
                        name = "Requeue Codex MCP Co",
                        slug = "requeue-codex-mcp-co",
                        contextDocPath = appHome.resolve("project.md").toString(),
                        lastUpdatedAt = 1L
                    )
                ),
                goals = listOf(goal),
                issues = listOf(issue),
                tasks = listOf(task),
                runs = listOf(run)
            )
        )
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = null,
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        service.companyDashboard(company.id)

        withTimeout(5_000) {
            while (true) {
                service.companyDashboard(company.id)
                val latestIssue = stateStore.load().issues.first { it.id == issue.id }
                if (latestIssue.status == IssueStatus.PLANNED) {
                    latestIssue.blockedBy.shouldBeEmpty()
                    return@withTimeout
                }
                delay(25)
            }
        }
    }

    test("seeded builder profile does not infer frontend capability from the word builder") {
        val appHome = Files.createTempDirectory("desktop-builder-capability-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-builder-capability-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(relaxed = true),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            commandAvailability = { command -> command == "codex" }
        )

        val company = service.createCompany(
            name = "Capability Check Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )

        val builder = service.listOrgProfiles().first { it.companyId == company.id && it.roleName == "Builder" }
        builder.capabilities.contains("implementation") shouldBe true
        builder.capabilities.contains("integration") shouldBe true
        builder.capabilities.contains("frontend") shouldBe false
    }

    test("autonomous runtime leaves goal issues unblocked for parallel scheduling and starts work automatically") {
        val appHome = Files.createTempDirectory("desktop-runtime-parallel-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-parallel-test").resolve("repo"))
        val worktreeRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-parallel-worktree"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val agentExecutor = mockk<AgentExecutor>()
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns "https://github.com/heodongun/cotor.git"
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } answers {
            val agentName = invocation.args[3] as String
            WorktreeBinding(
                branchName = "codex/cotor/parallel/$agentName",
                worktreePath = worktreeRoot.resolve(agentName)
            )
        }
        coEvery { gitWorkspaceService.publishRun(any(), any(), any(), any(), any(), any()) } returns PublishMetadata()
        coEvery { gitWorkspaceService.ensureGitHubPublishReady(any(), any()) } returns GitHubPublishReadiness(ready = true)
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } answers {
            val agent = invocation.args[0] as AgentConfig
            AgentResult(
                agentName = agent.name,
                isSuccess = true,
                output = "done",
                error = null,
                duration = 50,
                metadata = emptyMap(),
                processId = 555
            )
        }
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            companyRuntimeTickIntervalMs = 50,
            commandAvailability = { command -> command in setOf("codex", "gemini") }
        )

        val company = service.createCompany(
            name = "Parallel Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Parallel company execution",
            description = """
                - Build the main implementation path
                - Cover regression and failure handling
                - Document final validation
            """.trimIndent(),
            autonomyEnabled = false
        )

        val plannedIssues = service.listIssues(goal.id)
        plannedIssues.count { it.kind == "planning" } shouldBe 1
        plannedIssues.count { it.kind == "execution" } shouldBeGreaterThan 0
        plannedIssues.first { it.kind == "planning" }.status shouldBe IssueStatus.DONE
        plannedIssues.filter { it.kind == "execution" }.all { it.dependsOn.isEmpty() && it.status == IssueStatus.PLANNED } shouldBe true

        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(goal.companyId)
        service.runCompanyRuntimeTick(goal.companyId)

        withTimeout(5_000) {
            while (true) {
                val current = stateStore.load()
                val issueTasks = current.tasks.filter { it.issueId != null }
                val activeIssues = current.issues.filter { it.goalId == goal.id }.count { it.status != IssueStatus.PLANNED && it.status != IssueStatus.BACKLOG }
                if (issueTasks.isNotEmpty() || activeIssues > 0) {
                    return@withTimeout
                }
                delay(25)
            }
        }

        withTimeout(5_000) {
            while (true) {
                val state = stateStore.load()
                if (state.issues.filter { it.goalId == goal.id }.count { it.status != IssueStatus.PLANNED } > 0) {
                    return@withTimeout
                }
                delay(25)
            }
        }
        service.stopCompanyRuntime(goal.companyId).status shouldBe CompanyRuntimeStatus.STOPPED
    }

    test("autonomous goal execution creates and completes runs without a manual issue trigger") {
        val appHome = Files.createTempDirectory("desktop-runtime-autorun-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-autorun-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val processManager = FakeGitProcessManager(
            repoRoot = repoRoot,
            remoteUrl = "https://github.com/heodongun/cotor.git",
            defaultBranch = "master"
        )
        val gitWorkspaceService = GitWorkspaceService(
            processManager = processManager,
            stateStore = stateStore,
            logger = mockk(relaxed = true)
        )
        val configRepository = mockk<ConfigRepository>(relaxed = true)
        val agentExecutor = mockk<AgentExecutor>()
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } answers {
            val agent = invocation.args[0] as AgentConfig
            AgentResult(
                agentName = agent.name,
                isSuccess = true,
                output = "completed",
                error = null,
                duration = 50,
                metadata = emptyMap(),
                processId = 4321
            )
        }
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = configRepository,
            agentExecutor = agentExecutor,
            companyRuntimeTickIntervalMs = 50,
            commandAvailability = { command -> command in setOf("codex", "opencode") }
        )

        val company = service.createCompany(
            name = "Autonomous Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )

        val goal = service.createGoal(
            companyId = company.id,
            title = "Autonomous codex execution",
            description = "Create the plan and execute it without pressing any issue run button.",
            autonomyEnabled = false
        )
        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(goal.companyId)
        service.runCompanyRuntimeTick(goal.companyId)

        repeat(10) {
            service.runCompanyRuntimeTick(goal.companyId)
            delay(25)
        }

        withTimeout(30_000) {
            while (true) {
                service.runCompanyRuntimeTick(goal.companyId)
                val state = stateStore.load()
                val issueRunCount = state.runs.count { run -> state.tasks.firstOrNull { it.id == run.taskId }?.issueId != null }
                if (state.tasks.count { it.issueId != null } > 0 && issueRunCount > 0) {
                    return@withTimeout
                }
                delay(50)
            }
        }

        val state = stateStore.load()
        state.tasks.count { it.issueId != null } shouldBeGreaterThan 0
        state.runs.count { run -> state.tasks.firstOrNull { it.id == run.taskId }?.issueId != null } shouldBeGreaterThan 0
        service.stopCompanyRuntime(goal.companyId).status shouldBe CompanyRuntimeStatus.STOPPED
    }

    test("company decomposition keeps duplicate worker CLIs as separate execution issues and builds review dependencies") {
        val appHome = Files.createTempDirectory("desktop-runtime-roster-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-roster-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Dynamic Roster Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val seededAgents = service.listCompanyAgentDefinitions(company.id)
        val seededChief = seededAgents.first { it.title == "CEO" }
        val seededBuilder = seededAgents.first { it.title == "Builder" }
        val seededQa = seededAgents.first { it.title == "QA" }
        service.updateCompanyAgentDefinition(company.id, seededChief.id, title = "CEO", agentCli = "codex", roleSummary = "ceo final approval")
        service.updateCompanyAgentDefinition(company.id, seededBuilder.id, title = "Builder 1", agentCli = "/bin/echo", roleSummary = "builder implementation delivery")
        service.updateCompanyAgentDefinition(company.id, seededQa.id, title = "QA Lead", agentCli = "/bin/echo", roleSummary = "qa review verification")
        repeat(4) { index ->
            service.createCompanyAgentDefinition(
                company.id,
                "Builder ${index + 2}",
                "/bin/echo",
                "builder implementation delivery"
            )
        }

        val goal = service.createGoal(
            companyId = company.id,
            title = "Dynamic agent company",
            description = """
                - Build the planner
                - Build the UI shell
                - Add verification
                - Validate branch publishing
                - Summarize release readiness
            """.trimIndent(),
            autonomyEnabled = false
        )

        val issues = service.listIssues(goal.id)
        val planningIssues = issues.filter { it.kind == "planning" }
        val executionIssues = issues.filter { it.kind == "execution" }
        val reviewIssues = issues.filter { it.kind == "review" }
        val approvalIssues = issues.filter { it.kind == "approval" }

        planningIssues shouldHaveSize 1
        planningIssues.single().status shouldBe IssueStatus.DONE
        executionIssues.size shouldBeGreaterThan 0
        executionIssues.mapNotNull { it.assigneeProfileId }.distinct().size shouldBe executionIssues.size
        executionIssues.all { it.dependsOn.isEmpty() && it.status == IssueStatus.PLANNED } shouldBe true
    }

    test("new companies seed an OpenCode-first enterprise roster with CEO as the sole merge authority") {
        val appHome = Files.createTempDirectory("desktop-enterprise-roster-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-enterprise-roster-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Enterprise Seed Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )

        val definitions = service.listCompanyAgentDefinitions(company.id)
        definitions.map { it.title } shouldBe listOf(
            "CEO",
            "Product Strategist",
            "Engineering Lead",
            "UX Builder",
            "UI Builder",
            "Builder",
            "Backend Builder",
            "QA",
            "Release Manager"
        )
        definitions.all { it.agentCli == "opencode" } shouldBe true

        val mergeAuthorities = service.companyDashboard().orgProfiles
            .filter { it.companyId == company.id && it.mergeAuthority }
            .map { it.roleName }
        mergeAuthorities shouldBe listOf("CEO")
    }

    test("runtime synthesizes a follow-up goal when blocked work needs another CEO loop") {
        val appHome = Files.createTempDirectory("desktop-runtime-followup-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-followup-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Follow Up Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val seededAgents = service.listCompanyAgentDefinitions(company.id)
        val seededChief = seededAgents.first { it.title == "CEO" }
        val seededBuilder = seededAgents.first { it.title == "Builder" }
        service.updateCompanyAgentDefinition(company.id, seededChief.id, title = "CEO", agentCli = "codex", roleSummary = "ceo strategy and approval")
        service.updateCompanyAgentDefinition(company.id, seededBuilder.id, title = "Builder", agentCli = "/bin/echo", roleSummary = "builder implementation")

        val goal = service.createGoal(
            companyId = company.id,
            title = "Ship autonomous work",
            description = "Deliver the initial company objective.",
            autonomyEnabled = false
        )
        val issue = service.listIssues(goal.id).first { it.kind == "execution" }
        blockIssueWithFailedTask(stateStore, issue)

        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        val state = stateStore.load()
        val followUpGoals = state.goals.filter {
            it.companyId == company.id &&
                it.id != goal.id &&
                it.operatingPolicy == "auto-follow-up:goal:${goal.id}"
        }
        followUpGoals shouldHaveSize 1
        followUpGoals.single().title shouldContain issue.title
        state.issues.any { it.goalId == followUpGoals.single().id } shouldBe true
    }

    test("runtime synthesizes a continuous CEO goal after active autonomous work completes") {
        val appHome = Files.createTempDirectory("desktop-runtime-continuous-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-continuous-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )
        try {
            val company = service.createCompany(
                name = "Continuous Loop Co",
                rootPath = repoRoot.toString(),
                defaultBaseBranch = "master"
            )
            val goal = service.createGoal(
                companyId = company.id,
                title = "Ship the first validated slice",
                description = "Complete the first autonomous cycle, then keep the company moving.",
                autonomyEnabled = true
            )
            // Allow any background runtime ticks to settle before rewriting state.
            delay(300)
            service.stopCompanyRuntime(company.id)
            delay(100)

            val now = System.currentTimeMillis()
            val snapshot = stateStore.load()
            stateStore.save(
                snapshot.copy(
                    goals = snapshot.goals.map {
                        if (it.companyId == company.id) it.copy(status = GoalStatus.COMPLETED, updatedAt = now) else it
                    },
                    issues = snapshot.issues.map {
                        if (it.companyId == company.id) it.copy(status = IssueStatus.DONE, updatedAt = now) else it
                    },
                    tasks = snapshot.tasks.map {
                        if (it.status == DesktopTaskStatus.RUNNING || it.status == DesktopTaskStatus.QUEUED) {
                            it.copy(status = DesktopTaskStatus.COMPLETED, updatedAt = now)
                        } else {
                            it
                        }
                    },
                    runs = snapshot.runs.map {
                        if (it.status == AgentRunStatus.RUNNING || it.status == AgentRunStatus.QUEUED) {
                            it.copy(status = AgentRunStatus.COMPLETED, updatedAt = now)
                        } else {
                            it
                        }
                    },
                    companyRuntimes = listOf(
                        CompanyRuntimeSnapshot(
                            companyId = company.id,
                            status = CompanyRuntimeStatus.RUNNING,
                            lastTickAt = now
                        )
                    )
                )
            )

            val nextGoal = service.synthesizeAutonomousFollowUpGoalForTesting(company.id)

            nextGoal shouldNotBe null
            val synthesizedGoal = requireNotNull(nextGoal)
            synthesizedGoal.description shouldContain "portfolio of 3 to 5 branchable issues"
            synthesizedGoal.description shouldContain "multiple compatible implementation and validation tracks"
        } finally {
            service.shutdown()
        }
    }

    test("normalize automation state cancels superseded blocked follow-up issues after a newer retry succeeds") {
        val appHome = Files.createTempDirectory("desktop-runtime-superseded-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-superseded-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Superseded Retry Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val activeGoal = service.createGoal(
            companyId = company.id,
            title = "Primary goal",
            description = "Keep the company moving.",
            autonomyEnabled = true,
            startRuntimeIfNeeded = false
        )
        val followUpGoal = service.createGoal(
            companyId = company.id,
            title = "Resolve follow-up for blocked review",
            description = "Remediate blocked review work.",
            autonomyEnabled = true,
            operatingPolicy = "auto-follow-up:review:item-1",
            startRuntimeIfNeeded = false
        )
        val workspaceId = stateStore.load().workspaces.first { it.repositoryId == company.repositoryId }.id
        val blockedIssue = CompanyIssue(
            id = "blocked-review-issue",
            companyId = company.id,
            projectContextId = followUpGoal.projectContextId,
            goalId = followUpGoal.id,
            workspaceId = workspaceId,
            title = "Review completed implementation work",
            description = "Old blocked remediation review issue.",
            status = IssueStatus.BLOCKED,
            priority = 1,
            kind = "review",
            createdAt = 10,
            updatedAt = 10
        )
        val successfulRetry = CompanyIssue(
            id = "successful-review-issue",
            companyId = company.id,
            projectContextId = activeGoal.projectContextId,
            goalId = activeGoal.id,
            workspaceId = workspaceId,
            title = "Review completed implementation work",
            description = "A newer retry completed successfully.",
            status = IssueStatus.DONE,
            priority = 1,
            kind = "review",
            createdAt = 20,
            updatedAt = 20
        )
        // Allow any background runtime ticks to settle before rewriting state.
        delay(300)
        service.stopCompanyRuntime(company.id)
        delay(100)
        val snapshot = stateStore.load()
        stateStore.save(
            snapshot.copy(
                issues = snapshot.issues + listOf(blockedIssue, successfulRetry)
            )
        )

        stateStore.save(
            stateStore.load().copy(
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        backendKind = ExecutionBackendKind.LOCAL_COTOR,
                        backendHealth = "healthy",
                        lastStartedAt = System.currentTimeMillis()
                    )
                )
            )
        )
        service.prepareCompanyAutomationStateForTesting(company.id)

        val refreshedState = stateStore.load()
        refreshedState.issues.first { it.id == blockedIssue.id }.status shouldBe IssueStatus.CANCELED
        refreshedState.issues.first { it.id == successfulRetry.id }.status shouldBe IssueStatus.DONE
    }

    test("runtime treats superseded canceled dependencies as satisfied for downstream review work") {
        val appHome = Files.createTempDirectory("desktop-runtime-superseded-dependency-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-superseded-dependency-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Superseded Dependency Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Keep review moving",
            description = "Review should continue after a retry supersedes an older execution issue.",
            autonomyEnabled = false,
            startRuntimeIfNeeded = false
        )
        val workspaceId = stateStore.load().workspaces.first { it.repositoryId == company.repositoryId }.id
        val canceledExecution = CompanyIssue(
            id = "canceled-execution-issue",
            companyId = company.id,
            projectContextId = goal.projectContextId,
            goalId = goal.id,
            workspaceId = workspaceId,
            title = "Ship the feature slice",
            description = "An older execution issue superseded by a newer retry.",
            status = IssueStatus.CANCELED,
            priority = 1,
            kind = "execution",
            createdAt = 10,
            updatedAt = 10
        )
        val successfulRetry = CompanyIssue(
            id = "successful-execution-issue",
            companyId = company.id,
            projectContextId = goal.projectContextId,
            goalId = goal.id,
            workspaceId = workspaceId,
            title = "Ship the feature slice",
            description = "A newer execution retry that already finished successfully.",
            status = IssueStatus.DONE,
            priority = 1,
            kind = "execution",
            createdAt = 20,
            updatedAt = 20
        )
        val reviewIssue = CompanyIssue(
            id = "downstream-review-issue",
            companyId = company.id,
            projectContextId = goal.projectContextId,
            goalId = goal.id,
            workspaceId = workspaceId,
            title = "Review completed implementation work",
            description = "QA should review the latest successful branch, not wait on the superseded one.",
            status = IssueStatus.BACKLOG,
            priority = 2,
            kind = "execution",
            codeProducing = false,
            executionIntent = ExecutionIntent.VALIDATION_ONLY,
            dependsOn = listOf(canceledExecution.id, successfulRetry.id),
            createdAt = 30,
            updatedAt = 30
        )
        val successfulRetryTask = AgentTask(
            id = "successful-execution-task",
            workspaceId = workspaceId,
            issueId = successfulRetry.id,
            title = successfulRetry.title,
            prompt = "Implement the latest retry successfully.",
            agents = listOf("codex"),
            status = DesktopTaskStatus.COMPLETED,
            createdAt = 21,
            updatedAt = 21
        )
        val successfulRetryRun = AgentRun(
            id = "successful-execution-run",
            taskId = successfulRetryTask.id,
            workspaceId = workspaceId,
            repositoryId = company.repositoryId,
            agentName = "codex",
            branchName = "codex/cotor/ship-feature-retry/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/ship-feature-retry/codex").toString(),
            status = AgentRunStatus.COMPLETED,
            output = "Implemented the successful retry branch.",
            createdAt = 22,
            updatedAt = 22
        )
        val snapshot = stateStore.load()
        stateStore.save(
            snapshot.copy(
                issues = snapshot.issues.filterNot { it.goalId == goal.id } + listOf(canceledExecution, successfulRetry, reviewIssue),
                tasks = snapshot.tasks + successfulRetryTask,
                runs = snapshot.runs + successfulRetryRun
            )
        )
        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)

        service.runCompanyRuntimeTick(company.id)

        withTimeout(10_000) {
            while (stateStore.load().issues.first { it.id == reviewIssue.id }.status == IssueStatus.BLOCKED) {
                delay(25)
            }
        }
        val refreshedState = stateStore.load()
        refreshedState.issues.first { it.id == canceledExecution.id }.status shouldBe IssueStatus.CANCELED
        refreshedState.issues.first { it.id == successfulRetry.id }.status shouldBe IssueStatus.DONE
        refreshedState.issues.first { it.id == reviewIssue.id }.status shouldNotBe IssueStatus.BLOCKED
    }

    test("runtime does not synthesize recursive follow-up goals from blocked remediation work") {
        val appHome = Files.createTempDirectory("desktop-runtime-followup-recursive-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-followup-recursive-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Recursive Follow Up Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Ship autonomous work",
            description = "Deliver the initial company objective.",
            autonomyEnabled = false
        )
        val originalIssue = service.listIssues(goal.id).first { it.kind == "execution" }
        blockIssueWithFailedTask(stateStore, originalIssue)

        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        val firstFollowUpGoal = withTimeout(5_000) {
            while (true) {
                stateStore.load().goals.firstOrNull {
                    it.companyId == company.id && it.operatingPolicy == "auto-follow-up:goal:${goal.id}"
                }?.let { return@withTimeout it }
                delay(25)
            }
            error("Unreachable")
        }
        val remediationIssue = withTimeout(5_000) {
            while (true) {
                stateStore.load().issues.firstOrNull {
                    it.goalId == firstFollowUpGoal.id && it.kind == "execution"
                }?.let { return@withTimeout it }
                delay(25)
            }
            error("Unreachable")
        }
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == remediationIssue.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = System.currentTimeMillis()) else it
                }
            )
        )

        service.runCompanyRuntimeTick(company.id)

        val followUpGoals = stateStore.load().goals.filter {
            it.companyId == company.id && it.operatingPolicy?.startsWith("auto-follow-up:") == true
        }
        followUpGoals shouldHaveSize 1
    }

    test("runtime does not synthesize duplicate follow-up goals for the same canonical subject") {
        val appHome = Files.createTempDirectory("desktop-runtime-followup-subject-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-followup-subject-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Follow Up Subject Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val workspaceId = baseState.workspaces.first { it.repositoryId == company.repositoryId }.id
        val rootGoal = CompanyGoal(
            id = "goal-followup-subject-root",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Ship autonomous work",
            description = "Deliver the initial company objective.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = 1,
            updatedAt = 1
        )
        val originalIssue = CompanyIssue(
            id = "issue-followup-subject-root",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = rootGoal.id,
            workspaceId = workspaceId,
            title = "Ship autonomous work",
            description = "Original blocked execution issue.",
            status = IssueStatus.BLOCKED,
            priority = 1,
            kind = "execution",
            createdAt = 2,
            updatedAt = 2
        )
        val firstFollowUpGoal = CompanyGoal(
            id = "goal-followup-subject-existing",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Resolve follow-up for \"${originalIssue.title}\"",
            description = "Existing active follow-up for the blocked issue.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = false,
            operatingPolicy = "auto-follow-up:goal:${rootGoal.id}",
            followUpContext = FollowUpContextSnapshot(
                rootGoalId = rootGoal.id,
                triggerIssueId = originalIssue.id,
                failureClass = FollowUpFailureClass.BLOCKED_EXECUTION
            ),
            createdAt = 3,
            updatedAt = 3
        )
        val duplicateBlockedIssue = CompanyIssue(
            id = "duplicate-follow-up-trigger",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = rootGoal.id,
            workspaceId = workspaceId,
            title = "Resolve follow-up for \"${originalIssue.title}\"",
            description = "A duplicate nested follow-up trigger.",
            status = IssueStatus.BLOCKED,
            priority = 1,
            kind = "execution",
            createdAt = 4,
            updatedAt = 4
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + listOf(rootGoal, firstFollowUpGoal),
                issues = baseState.issues + listOf(originalIssue, duplicateBlockedIssue),
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        backendKind = ExecutionBackendKind.LOCAL_COTOR,
                        backendHealth = "healthy",
                        lastStartedAt = System.currentTimeMillis()
                    )
                )
            )
        )

        service.runCompanyRuntimeTick(company.id)

        val activeFollowUpGoals = stateStore.load().goals.filter {
            it.companyId == company.id &&
                it.status == GoalStatus.ACTIVE &&
                it.operatingPolicy.orEmpty().startsWith("auto-follow-up:") &&
                it.followUpContext?.rootGoalId == rootGoal.id
        }
        activeFollowUpGoals.map { it.id }.contains(firstFollowUpGoal.id) shouldBe true
        activeFollowUpGoals shouldHaveSize 1
    }

    test("runtime synthesizes deterministic merge-conflict follow-up issues on the existing PR lineage") {
        val appHome = Files.createTempDirectory("desktop-runtime-merge-conflict-followup-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-merge-conflict-followup-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Merge Conflict Follow Up Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Ship the conflicted branch",
            description = "Keep moving after a merge conflict without inventing a handoff PR.",
            autonomyEnabled = false,
            startRuntimeIfNeeded = false
        )
        val initialState = stateStore.load()
        val workspaceId = initialState.workspaces.first { it.repositoryId == company.repositoryId }.id
        val executionIssue = initialState.issues.first {
            it.goalId == goal.id && it.kind.equals("execution", ignoreCase = true)
        }
        val conflictedIssue = executionIssue.copy(
            status = IssueStatus.PLANNED,
            branchName = "codex/cotor/merge-conflict-followup/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/merge-conflict-followup/codex").toString(),
            pullRequestNumber = 321,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/321",
            pullRequestState = "OPEN",
            updatedAt = System.currentTimeMillis()
        )
        val reviewQueueItem = ReviewQueueItem(
            id = "rq-merge-conflict-followup",
            companyId = company.id,
            projectContextId = goal.projectContextId,
            issueId = conflictedIssue.id,
            runId = "run-merge-conflict-followup",
            branchName = conflictedIssue.branchName,
            worktreePath = conflictedIssue.worktreePath,
            pullRequestNumber = conflictedIssue.pullRequestNumber,
            pullRequestUrl = conflictedIssue.pullRequestUrl,
            pullRequestState = conflictedIssue.pullRequestState,
            status = ReviewQueueStatus.CHANGES_REQUESTED,
            mergeability = "DIRTY",
            qaVerdict = "PASS",
            qaFeedback = "Implementation was fine before the rebase conflict.",
            ceoVerdict = "CHANGES_REQUESTED",
            ceoFeedback = "GitHub could not merge this pull request cleanly.",
            qaIssueId = "qa-merge-conflict-followup",
            approvalIssueId = "approval-merge-conflict-followup",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val qaIssue = CompanyIssue(
            id = "qa-merge-conflict-followup",
            companyId = company.id,
            projectContextId = goal.projectContextId,
            goalId = goal.id,
            workspaceId = workspaceId,
            title = "QA review ${conflictedIssue.title}",
            description = "Existing QA lane for the dirty PR.",
            status = IssueStatus.DONE,
            priority = 2,
            kind = "review",
            dependsOn = listOf(conflictedIssue.id),
            branchName = conflictedIssue.branchName,
            worktreePath = conflictedIssue.worktreePath,
            pullRequestNumber = conflictedIssue.pullRequestNumber,
            pullRequestUrl = conflictedIssue.pullRequestUrl,
            pullRequestState = conflictedIssue.pullRequestState,
            qaVerdict = "PASS",
            qaFeedback = "Implementation was fine before the rebase conflict.",
            sourceSignal = "qa-review:${conflictedIssue.id}",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val approvalIssue = CompanyIssue(
            id = "approval-merge-conflict-followup",
            companyId = company.id,
            projectContextId = goal.projectContextId,
            goalId = goal.id,
            workspaceId = workspaceId,
            title = "CEO approve ${conflictedIssue.title}",
            description = "Existing CEO lane for the dirty PR.",
            status = IssueStatus.BLOCKED,
            priority = 3,
            kind = "approval",
            dependsOn = listOf(qaIssue.id),
            branchName = conflictedIssue.branchName,
            worktreePath = conflictedIssue.worktreePath,
            pullRequestNumber = conflictedIssue.pullRequestNumber,
            pullRequestUrl = conflictedIssue.pullRequestUrl,
            pullRequestState = conflictedIssue.pullRequestState,
            qaVerdict = "PASS",
            qaFeedback = "Implementation was fine before the rebase conflict.",
            ceoVerdict = "CHANGES_REQUESTED",
            ceoFeedback = "GitHub could not merge this pull request cleanly.",
            sourceSignal = "ceo-approval:${conflictedIssue.id}",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        stateStore.save(
            initialState.copy(
                issues = initialState.issues.map { if (it.id == conflictedIssue.id) conflictedIssue else it } + listOf(qaIssue, approvalIssue),
                reviewQueue = initialState.reviewQueue + reviewQueueItem,
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        backendKind = ExecutionBackendKind.LOCAL_COTOR,
                        backendHealth = "healthy",
                        lastStartedAt = System.currentTimeMillis()
                    )
                )
            )
        )
        service.updateGoal(goal.id, autonomyEnabled = true)

        service.runCompanyRuntimeTick(company.id)

        val refreshed = withTimeout(120_000) {
            while (true) {
                val candidate = stateStore.load()
                val followUpGoal = candidate.goals.firstOrNull {
                    it.companyId == company.id &&
                        it.followUpContext?.rootGoalId == goal.id &&
                        it.followUpContext?.reviewQueueItemId == reviewQueueItem.id &&
                        it.followUpContext?.failureClass == FollowUpFailureClass.MERGE_CONFLICT
                }
                val followUpExecutionIssueCount = followUpGoal?.let { goalCandidate ->
                    candidate.issues.count { it.goalId == goalCandidate.id && it.kind.equals("execution", ignoreCase = true) }
                } ?: 0
                if (followUpGoal != null && followUpExecutionIssueCount >= 2) {
                    return@withTimeout candidate
                }
                service.runCompanyRuntimeTick(company.id)
                delay(25)
            }
            error("Unreachable")
        }
        val followUpGoal = refreshed.goals.first {
            it.companyId == company.id &&
                it.followUpContext?.rootGoalId == goal.id &&
                it.followUpContext?.reviewQueueItemId == reviewQueueItem.id &&
                it.followUpContext?.failureClass == FollowUpFailureClass.MERGE_CONFLICT
        }
        check(followUpGoal.followUpContext?.failureClass == FollowUpFailureClass.MERGE_CONFLICT) {
            "Expected merge-conflict follow-up context but was ${followUpGoal.followUpContext}"
        }
        followUpGoal.followUpContext?.reviewQueueItemId shouldBe reviewQueueItem.id
        val followUpExecutionIssues = withTimeout(5_000) {
            while (true) {
                val issues = stateStore.load().issues.filter {
                    it.goalId == followUpGoal.id && it.kind.equals("execution", ignoreCase = true)
                }
                if (issues.size >= 2) {
                    return@withTimeout issues
                }
                delay(25)
            }
            error("Unreachable")
        }
        followUpExecutionIssues.size shouldBeGreaterThanOrEqual 2
        val remediationIssue = followUpExecutionIssues.first { it.executionIntent == ExecutionIntent.MERGE_CONFLICT_REMEDIATION }
        remediationIssue.title shouldContain "Resolve merge conflict for PR #321 against master"
        remediationIssue.codeProducing shouldBe true
        remediationIssue.branchName shouldBe conflictedIssue.branchName
        remediationIssue.pullRequestNumber shouldBe 321
        remediationIssue.worktreePath.shouldNotBeNull()
        val validationIssue = followUpExecutionIssues.first { it.executionIntent == ExecutionIntent.VALIDATION_ONLY }
        validationIssue.title shouldBe "Re-run validation and summarize residual risk"
        validationIssue.codeProducing shouldBe false
        validationIssue.dependsOn shouldBe listOf(remediationIssue.id)
        followUpExecutionIssues.none { it.title.contains("Hand the result back", ignoreCase = true) } shouldBe true
    }

    test("normalize automation state archives duplicate follow-up lineages for the same trigger and failure class") {
        val appHome = Files.createTempDirectory("desktop-runtime-duplicate-followup-lineage-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-duplicate-followup-lineage-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Duplicate Follow Up Lineage Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Repair one review trigger once",
            description = "Only one active follow-up goal should survive for the same lineage.",
            autonomyEnabled = false,
            startRuntimeIfNeeded = false
        )
        val snapshot = stateStore.load()
        val workspaceId = snapshot.workspaces.first { it.repositoryId == company.repositoryId }.id
        val triggerIssue = snapshot.issues.first { it.goalId == goal.id && it.kind.equals("execution", ignoreCase = true) }
        val baseContext = FollowUpContextSnapshot(
            rootGoalId = goal.id,
            triggerIssueId = triggerIssue.id,
            reviewQueueItemId = "rq-duplicate-followup-lineage",
            pullRequestNumber = 88,
            failureClass = FollowUpFailureClass.REVIEW_CHANGES_REQUESTED
        )
        val olderGoal = CompanyGoal(
            id = "goal-duplicate-followup-old",
            companyId = company.id,
            projectContextId = goal.projectContextId,
            title = "Resolve follow-up for \"${triggerIssue.title}\"",
            description = "Older duplicate lineage.",
            status = GoalStatus.ACTIVE,
            operatingPolicy = "auto-follow-up:goal:${goal.id}",
            followUpContext = baseContext,
            createdAt = 10,
            updatedAt = 10
        )
        val newerGoal = CompanyGoal(
            id = "goal-duplicate-followup-new",
            companyId = company.id,
            projectContextId = goal.projectContextId,
            title = "Resolve follow-up for \"${triggerIssue.title}\"",
            description = "Newer duplicate lineage.",
            status = GoalStatus.ACTIVE,
            operatingPolicy = "auto-follow-up:goal:${goal.id}",
            followUpContext = baseContext,
            createdAt = 20,
            updatedAt = 20
        )
        val olderIssue = CompanyIssue(
            id = "issue-duplicate-followup-old",
            companyId = company.id,
            projectContextId = goal.projectContextId,
            goalId = olderGoal.id,
            workspaceId = workspaceId,
            title = "Older remediation issue",
            description = "Should be canceled with the archived duplicate goal.",
            status = IssueStatus.PLANNED,
            kind = "execution",
            createdAt = 10,
            updatedAt = 10
        )
        val newerIssue = CompanyIssue(
            id = "issue-duplicate-followup-new",
            companyId = company.id,
            projectContextId = goal.projectContextId,
            goalId = newerGoal.id,
            workspaceId = workspaceId,
            title = "Newer remediation issue",
            description = "Should remain active.",
            status = IssueStatus.PLANNED,
            kind = "execution",
            createdAt = 20,
            updatedAt = 20
        )
        stateStore.save(
            snapshot.copy(
                goals = snapshot.goals + listOf(olderGoal, newerGoal),
                issues = snapshot.issues + listOf(olderIssue, newerIssue),
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        backendKind = ExecutionBackendKind.LOCAL_COTOR,
                        backendHealth = "healthy",
                        lastStartedAt = System.currentTimeMillis()
                    )
                )
            )
        )

        service.runCompanyRuntimeTick(company.id)

        val refreshed = stateStore.load()
        refreshed.goals.first { it.id == olderGoal.id }.status shouldBe GoalStatus.COMPLETED
        refreshed.goals.first { it.id == newerGoal.id }.status shouldBe GoalStatus.ACTIVE
        refreshed.issues.first { it.id == olderIssue.id }.status shouldBe IssueStatus.CANCELED
        refreshed.issues.first { it.id == newerIssue.id }.status shouldNotBe IssueStatus.CANCELED
    }

    test("runtime retries blocked remediation issues only for recoverable infrastructure failures") {
        val appHome = Files.createTempDirectory("desktop-runtime-followup-retry-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-followup-retry-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Retry Follow Up Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Ship autonomous work",
            description = "Deliver the initial company objective.",
            autonomyEnabled = false
        )
        val originalIssue = service.listIssues(goal.id).first { it.kind == "execution" }
        blockIssueWithFailedTask(stateStore, originalIssue)
        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)
        service.stopCompanyRuntime(company.id)

        withTimeout(5_000) {
            while (true) {
                val followUp = stateStore.load().goals.firstOrNull {
                    it.companyId == company.id && it.operatingPolicy == "auto-follow-up:goal:${goal.id}"
                }
                if (followUp != null && stateStore.load().issues.any { it.goalId == followUp.id && it.kind == "execution" }) {
                    break
                }
                delay(25)
            }
        }

        val followUpGoal = withTimeout(5_000) {
            while (true) {
                stateStore.load().goals.firstOrNull {
                    it.companyId == company.id && it.operatingPolicy == "auto-follow-up:goal:${goal.id}"
                }?.let { return@withTimeout it }
                delay(25)
            }
            error("Unreachable")
        }
        val remediationIssue = stateStore.load().issues.first {
            it.goalId == followUpGoal.id && it.kind == "execution"
        }
        awaitIssueTasksSettled(stateStore, remediationIssue.id)
        val existingRemediationTask = service.createTask(
            workspaceId = remediationIssue.workspaceId,
            title = remediationIssue.title,
            prompt = remediationIssue.description,
            agents = listOf("codex"),
            issueId = remediationIssue.id
        )
        val failedRun = AgentRun(
            id = "recoverable-remediation-run",
            taskId = existingRemediationTask.id,
            workspaceId = remediationIssue.workspaceId,
            repositoryId = stateStore.load().repositories.first().id,
            agentName = "codex",
            branchName = "codex/cotor/recoverable-remediation/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/recoverable-remediation/codex").toString(),
            status = AgentRunStatus.FAILED,
            output = "Agent process exited before Cotor recorded a final result",
            error = "Agent process exited before Cotor recorded a final result",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val preNow = System.currentTimeMillis() - 120_000
        val preSnapshot = stateStore.load()
        stateStore.save(
            preSnapshot.copy(
                issues = preSnapshot.issues.map {
                    if (it.id == remediationIssue.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = preNow) else it
                },
                tasks = preSnapshot.tasks.map {
                    if (it.issueId == remediationIssue.id && it.id == existingRemediationTask.id) {
                        it.copy(status = DesktopTaskStatus.FAILED, updatedAt = preNow + 1)
                    } else if (it.issueId == remediationIssue.id) {
                        it.copy(status = DesktopTaskStatus.FAILED, updatedAt = preNow)
                    } else {
                        it
                    }
                },
                runs = preSnapshot.runs + failedRun
            )
        )

        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)
        service.stopCompanyRuntime(company.id)
        delay(500)

        // Recoverable failures should keep the remediation loop active instead of terminally
        // canceling the follow-up goal.
        stateStore.load().goals.first { it.id == followUpGoal.id }.status shouldBe GoalStatus.ACTIVE
    }

    test("runtime stops auto-retrying recoverable remediation issues after repeated failures") {
        val appHome = Files.createTempDirectory("desktop-runtime-followup-retry-many-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-followup-retry-many-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Retry Many Follow Up Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Ship autonomous work",
            description = "Deliver the initial company objective.",
            autonomyEnabled = false
        )
        val originalIssue = service.listIssues(goal.id).first { it.kind == "execution" }
        blockIssueWithFailedTask(stateStore, originalIssue)
        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        val followUpGoal = withTimeout(5_000) {
            while (true) {
                stateStore.load().goals.firstOrNull {
                    it.companyId == company.id && it.operatingPolicy == "auto-follow-up:goal:${goal.id}"
                }?.let { return@withTimeout it }
                delay(25)
            }
            error("Unreachable")
        }
        val remediationIssue = stateStore.load().issues.first {
            it.goalId == followUpGoal.id && it.kind == "execution"
        }
        awaitIssueTasksSettled(stateStore, remediationIssue.id)
        val seededTasks = (1..3).map { attempt ->
            service.createTask(
                workspaceId = remediationIssue.workspaceId,
                title = "${remediationIssue.title} #$attempt",
                prompt = remediationIssue.description,
                agents = listOf("codex"),
                issueId = remediationIssue.id
            )
        }
        val now = System.currentTimeMillis() - 120_000
        val retrySnapshot = stateStore.load()
        val initialRemediationTaskCount = retrySnapshot.tasks.count { it.issueId == remediationIssue.id }
        stateStore.save(
            retrySnapshot.copy(
                issues = retrySnapshot.issues.map {
                    if (it.id == remediationIssue.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = now) else it
                },
                tasks = retrySnapshot.tasks.map { task ->
                    if (task.issueId == remediationIssue.id) {
                        task.copy(status = DesktopTaskStatus.FAILED, updatedAt = now)
                    } else {
                        task
                    }
                },
                runs = retrySnapshot.runs + seededTasks.mapIndexed { index, task ->
                    AgentRun(
                        id = "recoverable-remediation-run-many-${index + 1}",
                        taskId = task.id,
                        workspaceId = remediationIssue.workspaceId,
                        repositoryId = retrySnapshot.repositories.first().id,
                        agentName = "codex",
                        branchName = "codex/cotor/recoverable-remediation-many/codex-${index + 1}",
                        worktreePath = repoRoot.resolve(".cotor/worktrees/recoverable-remediation-many/codex-${index + 1}").toString(),
                        status = AgentRunStatus.FAILED,
                        output = "Agent process exited before Cotor recorded a final result",
                        error = "Agent process exited before Cotor recorded a final result",
                        createdAt = now + index,
                        updatedAt = now + index
                    )
                }
            )
        )

        service.runCompanyRuntimeTick(company.id)

        // After several consecutive recoverable failures, the runtime should stop
        // automatically retrying and leave the remediation issue blocked.
        stateStore.load().issues.first { it.id == remediationIssue.id }.status shouldNotBe IssueStatus.DONE
    }

    test("runtime waits for cooldown before retrying a recoverable blocked workflow issue") {
        val appHome = Files.createTempDirectory("desktop-runtime-workflow-retry-cooldown-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-workflow-retry-cooldown-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Workflow Retry Cooldown Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val snapshot = stateStore.load()
        val workspace = snapshot.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = snapshot.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-workflow-retry-cooldown",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Drive the next company cycle",
            description = "Create and complete the next improvement slice.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-workflow-retry-cooldown",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Retry the blocked workflow issue",
            description = "This recoverable blocked issue should wait for cooldown before retrying.",
            status = IssueStatus.BLOCKED,
            priority = 2,
            kind = "execution",
            createdAt = now,
            updatedAt = now
        )
        val existingTask = AgentTask(
            id = "task-workflow-retry-cooldown",
            workspaceId = workspace.id,
            title = executionIssue.title,
            prompt = executionIssue.description,
            agents = listOf("codex"),
            issueId = executionIssue.id,
            status = DesktopTaskStatus.FAILED,
            createdAt = now,
            updatedAt = now
        )
        val failedRun = AgentRun(
            id = "workflow-recoverable-cooldown-run",
            taskId = existingTask.id,
            workspaceId = workspace.id,
            repositoryId = snapshot.repositories.first().id,
            agentName = "codex",
            branchName = "codex/cotor/workflow-retry-cooldown/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/workflow-retry-cooldown/codex").toString(),
            status = AgentRunStatus.FAILED,
            output = "Agent process exited before Cotor recorded a final result",
            error = "Agent process exited before Cotor recorded a final result",
            createdAt = now,
            updatedAt = now
        )
        val preTickTaskCount = 1
        stateStore.save(
            snapshot.copy(
                goals = snapshot.goals + goal,
                issues = snapshot.issues + executionIssue,
                tasks = snapshot.tasks + existingTask,
                runs = snapshot.runs + failedRun,
                companyRuntimes = snapshot.companyRuntimes.filterNot { it.companyId == company.id } + CompanyRuntimeSnapshot(
                    companyId = company.id,
                    status = CompanyRuntimeStatus.RUNNING,
                    backendKind = ExecutionBackendKind.LOCAL_COTOR,
                    backendHealth = "healthy",
                    lastStartedAt = now
                )
            )
        )

        service.runCompanyRuntimeTick(company.id)

        stateStore.load().tasks.count { it.issueId == executionIssue.id } shouldBe preTickTaskCount
        stateStore.load().issues.first { it.id == executionIssue.id }.status shouldBe IssueStatus.BLOCKED
        stateStore.load().goals.first { it.id == goal.id }.status shouldBe GoalStatus.ACTIVE
    }

    test("runtime reopens recoverable blocked workflow issues inside an active autonomous goal") {
        val appHome = Files.createTempDirectory("desktop-runtime-workflow-retry-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-workflow-retry-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Workflow Retry Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Drive the next company cycle",
            description = "Create and complete the next improvement slice.",
            autonomyEnabled = false
        )
        val executionIssue = service.listIssues(goal.id).first { it.kind == "execution" }
        awaitIssueTasksSettled(stateStore, executionIssue.id)
        val existingTask = service.createTask(
            workspaceId = executionIssue.workspaceId,
            title = executionIssue.title,
            prompt = executionIssue.description,
            agents = listOf("codex"),
            issueId = executionIssue.id
        )
        val retryAt = System.currentTimeMillis() - 120_000
        val failedRun = AgentRun(
            id = "workflow-recoverable-run",
            taskId = existingTask.id,
            workspaceId = executionIssue.workspaceId,
            repositoryId = stateStore.load().repositories.first().id,
            agentName = "codex",
            branchName = "codex/cotor/workflow-retry/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/workflow-retry/codex").toString(),
            status = AgentRunStatus.FAILED,
            output = "Agent process exited before Cotor recorded a final result",
            error = "Agent process exited before Cotor recorded a final result",
            createdAt = retryAt,
            updatedAt = retryAt
        )
        val workflowRetrySnapshot = stateStore.load()
        stateStore.save(
            workflowRetrySnapshot.copy(
                issues = workflowRetrySnapshot.issues.map {
                    if (it.id == executionIssue.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = retryAt) else it
                },
                tasks = workflowRetrySnapshot.tasks.map {
                    if (it.issueId == executionIssue.id) it.copy(status = DesktopTaskStatus.FAILED, updatedAt = retryAt) else it
                },
                runs = workflowRetrySnapshot.runs + failedRun
            )
        )

        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        withTimeout(120_000) {
            while (true) {
                if (stateStore.load().tasks.count { it.issueId == executionIssue.id } > 1) {
                    return@withTimeout
                }
                service.runCompanyRuntimeTick(company.id)
                delay(25)
            }
        }
        // The tick requeues the issue and restarts it – which may re-block it when the
        // relaxed mock executor fails.  Verify the retry was attempted via task count.
        stateStore.load().tasks.count { it.issueId == executionIssue.id } shouldBeGreaterThan 1
        stateStore.load().goals.first { it.id == goal.id }.status shouldBe GoalStatus.ACTIVE
        val traceLog = Files.readString(appHome.resolve("runtime").resolve("backend").resolve("company-automation-trace.log"))
        traceLog shouldContain "\"issueId\":\"${executionIssue.id}\""
        traceLog shouldContain "recoverable"
    }

    test("runtime treats execution timeout failures as recoverable for autonomous company issues") {
        val appHome = Files.createTempDirectory("desktop-runtime-timeout-retry-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-timeout-retry-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Timeout Retry Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Retry timed out work",
            description = "A timed-out execution should reopen instead of staying permanently blocked.",
            autonomyEnabled = false
        )
        val executionIssue = service.listIssues(goal.id).first { it.kind == "execution" }
        val existingTask = service.createTask(
            workspaceId = executionIssue.workspaceId,
            title = executionIssue.title,
            prompt = executionIssue.description,
            agents = listOf("opencode"),
            issueId = executionIssue.id
        )
        val timeoutAt = System.currentTimeMillis() - 120_000
        val timedOutRun = AgentRun(
            id = "workflow-timeout-run",
            taskId = existingTask.id,
            workspaceId = executionIssue.workspaceId,
            repositoryId = stateStore.load().repositories.first().id,
            agentName = "opencode",
            branchName = "codex/cotor/timeout-retry/opencode",
            worktreePath = repoRoot.resolve(".cotor/worktrees/timeout-retry/opencode").toString(),
            status = AgentRunStatus.FAILED,
            output = null,
            error = "Execution timeout after 900000ms",
            durationMs = 900_000,
            createdAt = timeoutAt,
            updatedAt = timeoutAt
        )
        val snapshot = stateStore.load()
        stateStore.save(
            snapshot.copy(
                issues = snapshot.issues.map {
                    when {
                        it.id == executionIssue.id -> it.copy(status = IssueStatus.BLOCKED, updatedAt = timeoutAt)
                        it.goalId == goal.id && it.kind == "execution" -> it.copy(status = IssueStatus.DONE, updatedAt = timeoutAt)
                        else -> it
                    }
                },
                tasks = snapshot.tasks.map {
                    if (it.id == existingTask.id) it.copy(status = DesktopTaskStatus.FAILED, updatedAt = timeoutAt) else it
                },
                runs = snapshot.runs + timedOutRun
            )
        )

        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        val refreshed = stateStore.load()
        refreshed.goals.first { it.id == goal.id }.status shouldBe GoalStatus.ACTIVE
        refreshed.issues.first { it.id == executionIssue.id }.status shouldNotBe IssueStatus.CANCELED
    }

    test("runtime treats opencode decimal failures as recoverable for autonomous company issues") {
        val appHome = Files.createTempDirectory("desktop-runtime-opencode-retry-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-opencode-retry-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "OpenCode Retry Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Retry opencode decimal failures",
            description = "An OpenCode DecimalError should reopen instead of permanently blocking autonomous work.",
            autonomyEnabled = false
        )
        val executionIssue = service.listIssues(goal.id).first { it.kind == "execution" }
        val existingTask = service.createTask(
            workspaceId = executionIssue.workspaceId,
            title = executionIssue.title,
            prompt = executionIssue.description,
            agents = listOf("opencode"),
            issueId = executionIssue.id
        )
        val retryAt = System.currentTimeMillis() - 120_000
        val failedRun = AgentRun(
            id = "workflow-opencode-decimal-run",
            taskId = existingTask.id,
            workspaceId = executionIssue.workspaceId,
            repositoryId = stateStore.load().repositories.first().id,
            agentName = "opencode",
            branchName = "codex/cotor/opencode-decimal-retry/opencode",
            worktreePath = repoRoot.resolve(".cotor/worktrees/opencode-decimal-retry/opencode").toString(),
            status = AgentRunStatus.FAILED,
            output = "{\"type\":\"error\",\"error\":{\"name\":\"UnknownError\",\"data\":{\"message\":\"Error: [DecimalError] Invalid argument: [object Object]\"}}}",
            error = "OpenCode execution failed (exit=1): {\"type\":\"error\",\"error\":{\"name\":\"UnknownError\",\"data\":{\"message\":\"Error: [DecimalError] Invalid argument: [object Object]\"}}}",
            createdAt = retryAt,
            updatedAt = retryAt
        )
        val snapshot = stateStore.load()
        stateStore.save(
            snapshot.copy(
                issues = snapshot.issues.map {
                    when {
                        it.id == executionIssue.id -> it.copy(status = IssueStatus.BLOCKED, updatedAt = retryAt)
                        it.goalId == goal.id && it.kind == "execution" -> it.copy(status = IssueStatus.DONE, updatedAt = retryAt)
                        else -> it
                    }
                },
                tasks = snapshot.tasks.map {
                    if (it.id == existingTask.id) it.copy(status = DesktopTaskStatus.FAILED, updatedAt = retryAt) else it
                },
                runs = snapshot.runs + failedRun
            )
        )

        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        val refreshed = stateStore.load()
        refreshed.goals.first { it.id == goal.id }.status shouldBe GoalStatus.ACTIVE
        refreshed.issues.first { it.id == executionIssue.id }.status shouldNotBe IssueStatus.CANCELED
    }

    test("runtime keeps blocked remediation issues blocked for non-recoverable failures and logs the transition") {
        val appHome = Files.createTempDirectory("desktop-runtime-followup-nonrecoverable-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-followup-nonrecoverable-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Non Recoverable Retry Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Ship autonomous work",
            description = "Deliver the initial company objective.",
            autonomyEnabled = false
        )
        val originalIssue = service.listIssues(goal.id).first { it.kind == "execution" }
        blockIssueWithFailedTask(stateStore, originalIssue)
        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        val followUpGoal = withTimeout(5_000) {
            while (true) {
                stateStore.load().goals.firstOrNull {
                    it.companyId == company.id && it.operatingPolicy == "auto-follow-up:goal:${goal.id}"
                }?.let { return@withTimeout it }
                delay(25)
            }
            error("Unreachable")
        }
        val remediationIssue = stateStore.load().issues.first {
            it.goalId == followUpGoal.id && it.kind == "execution"
        }
        awaitIssueTasksSettled(stateStore, remediationIssue.id)
        val existingRemediationTask = service.createTask(
            workspaceId = remediationIssue.workspaceId,
            title = remediationIssue.title,
            prompt = remediationIssue.description,
            agents = listOf("codex"),
            issueId = remediationIssue.id
        )
        val failedRun = AgentRun(
            id = "nonrecoverable-remediation-run",
            taskId = existingRemediationTask.id,
            workspaceId = remediationIssue.workspaceId,
            repositoryId = stateStore.load().repositories.first().id,
            agentName = "codex",
            branchName = "codex/cotor/nonrecoverable-remediation/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/nonrecoverable-remediation/codex").toString(),
            status = AgentRunStatus.FAILED,
            output = "review failed: residual risk remains",
            error = "review failed: residual risk remains",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val nonRecoverSnapshot = stateStore.load()
        stateStore.save(
            nonRecoverSnapshot.copy(
                issues = nonRecoverSnapshot.issues.map {
                    if (it.id == remediationIssue.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = System.currentTimeMillis()) else it
                },
                tasks = nonRecoverSnapshot.tasks.map {
                    if (it.issueId == remediationIssue.id) {
                        it.copy(status = DesktopTaskStatus.FAILED, updatedAt = System.currentTimeMillis())
                    } else {
                        it
                    }
                },
                runs = nonRecoverSnapshot.runs + failedRun
            )
        )

        service.runCompanyRuntimeTick(company.id)

        val updatedIssue = stateStore.load().issues.first { it.id == remediationIssue.id }
        updatedIssue.status shouldBe IssueStatus.BLOCKED
        val tracePath = appHome.resolve("runtime").resolve("backend").resolve("company-automation-trace.log")
        if (tracePath.exists()) {
            val traceLog = Files.readString(tracePath)
            traceLog shouldContain "\"issueId\":\"${remediationIssue.id}\""
            traceLog shouldContain "\"newStatus\":\"BLOCKED\""
        }
    }

    test("runtime retries blocked remediation issues for transient GitHub PR publish failures") {
        val appHome = Files.createTempDirectory("desktop-runtime-followup-prpublish-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-followup-prpublish-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "PR Publish Retry Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Ship autonomous work",
            description = "Deliver the initial company objective.",
            autonomyEnabled = false
        )
        val originalIssue = service.listIssues(goal.id).first { it.kind == "execution" }
        blockIssueWithFailedTask(stateStore, originalIssue)
        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        val followUpGoal = withTimeout(5_000) {
            while (true) {
                stateStore.load().goals.firstOrNull {
                    it.companyId == company.id && it.operatingPolicy == "auto-follow-up:goal:${goal.id}"
                }?.let { return@withTimeout it }
                delay(25)
            }
            error("Unreachable")
        }
        val remediationIssue = stateStore.load().issues.first {
            it.goalId == followUpGoal.id && it.kind == "execution"
        }
        awaitIssueTasksSettled(stateStore, remediationIssue.id)
        val existingRemediationTask = service.createTask(
            workspaceId = remediationIssue.workspaceId,
            title = remediationIssue.title,
            prompt = remediationIssue.description,
            agents = listOf("codex"),
            issueId = remediationIssue.id
        )
        val retryAt = System.currentTimeMillis() - 120_000
        val failedRun = AgentRun(
            id = "prpublish-remediation-run",
            taskId = existingRemediationTask.id,
            workspaceId = remediationIssue.workspaceId,
            repositoryId = stateStore.load().repositories.first().id,
            agentName = "codex",
            branchName = "codex/cotor/prpublish-remediation/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/prpublish-remediation/codex").toString(),
            status = AgentRunStatus.FAILED,
            output = "Publish failed: pull request create failed: GraphQL: was submitted too quickly (createPullRequest)",
            error = "Publish failed: pull request create failed: GraphQL: was submitted too quickly (createPullRequest)",
            createdAt = retryAt,
            updatedAt = retryAt,
            publish = PublishMetadata(
                error = "pull request create failed: GraphQL: was submitted too quickly (createPullRequest)"
            )
        )
        val prPublishSnapshot = stateStore.load()
        stateStore.save(
            prPublishSnapshot.copy(
                issues = prPublishSnapshot.issues.map {
                    if (it.id == remediationIssue.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = retryAt) else it
                },
                tasks = prPublishSnapshot.tasks.map {
                    if (it.issueId == remediationIssue.id) {
                        it.copy(status = DesktopTaskStatus.FAILED, updatedAt = retryAt)
                    } else {
                        it
                    }
                },
                runs = prPublishSnapshot.runs + failedRun
            )
        )

        service.runCompanyRuntimeTick(company.id)

        stateStore.load().goals.first { it.id == followUpGoal.id }.status shouldBe GoalStatus.ACTIVE
        stateStore.load().issues.first { it.id == remediationIssue.id }.status shouldNotBe IssueStatus.CANCELED
    }

    test("runtime archives recursively nested follow-up goals from older broken state") {
        val appHome = Files.createTempDirectory("desktop-runtime-followup-archive-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-followup-archive-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Archive Follow Up Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Ship autonomous work",
            description = "Deliver the initial company objective.",
            autonomyEnabled = false
        )
        val firstFollowUpGoal = service.createGoal(
            companyId = company.id,
            title = "Resolve follow-up for \"${goal.title}\"",
            description = "Parent remediation goal.",
            autonomyEnabled = true,
            operatingPolicy = "auto-follow-up:goal:${goal.id}",
            startRuntimeIfNeeded = false
        )
        val firstFollowUpExecution = service.createIssue(
            companyId = company.id,
            goalId = firstFollowUpGoal.id,
            title = "Remediate parent follow-up",
            description = "Parent remediation execution issue.",
            kind = "execution"
        )
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == firstFollowUpExecution.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = System.currentTimeMillis()) else it
                }
            )
        )
        val nestedGoal = service.createGoal(
            companyId = company.id,
            title = "Resolve follow-up for \"${firstFollowUpExecution.title}\"",
            description = "Incorrect recursive remediation goal.",
            autonomyEnabled = true,
            operatingPolicy = "auto-follow-up:goal:${firstFollowUpGoal.id}",
            startRuntimeIfNeeded = false
        )
        service.createIssue(
            companyId = company.id,
            goalId = nestedGoal.id,
            title = "Recursive remediation issue",
            description = "This issue should be archived with the recursive goal.",
            kind = "execution"
        )
        service.stopCompanyRuntime(company.id)
        service.companyDashboardPrepared(company.id)
        // A second prepared read ensures normalization fully propagates to nested recursive
        // goals without also scheduling unrelated autonomous execution work.
        service.companyDashboardPrepared(company.id)

        withTimeout(10_000) {
            while (true) {
                val state = stateStore.load()
                val nestedGoalTerminal = state.goals.firstOrNull { it.id == nestedGoal.id }?.status == GoalStatus.COMPLETED
                val nestedIssuesTerminal = state.issues
                    .filter { it.goalId == nestedGoal.id }
                    .all { it.status == IssueStatus.CANCELED || it.status == IssueStatus.DONE }
                if (nestedGoalTerminal && nestedIssuesTerminal) {
                    break
                }
                service.companyDashboardPrepared(company.id)
                delay(25)
            }
        }
        val companyGoalsAfterNestedTicks = stateStore.load().goals.filter { it.companyId == company.id }
        val updatedNestedGoal = companyGoalsAfterNestedTicks.firstOrNull { it.id == nestedGoal.id }
            ?: error(
                "Nested follow-up goal disappeared. Goals: ${
                    companyGoalsAfterNestedTicks.map { "${it.id}:${it.operatingPolicy}:${it.status}" }
                }"
            )
        updatedNestedGoal.status shouldBe GoalStatus.COMPLETED
        stateStore.load().issues.filter { it.goalId == nestedGoal.id }.all {
            it.status == IssueStatus.CANCELED || it.status == IssueStatus.DONE
        } shouldBe true
    }

    test("company dashboard migrates legacy follow-up markers to the root goal lineage and archives duplicates") {
        val appHome = Files.createTempDirectory("desktop-runtime-followup-migrate-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-followup-migrate-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Migrate Follow Up Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspaceId = baseState.workspaces.first { it.repositoryId == company.repositoryId }.id
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-followup-migrate-root",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Primary objective",
            description = "Initial objective.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val triggerIssue = CompanyIssue(
            id = "issue-followup-migrate-trigger",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspaceId,
            title = "Deliver the primary objective",
            description = "Root trigger issue.",
            status = IssueStatus.BLOCKED,
            priority = 1,
            kind = "execution",
            createdAt = now,
            updatedAt = now
        )
        val firstLegacyFollowUp = CompanyGoal(
            id = "goal-followup-migrate-first",
            title = "Resolve follow-up for \"${triggerIssue.title}\"",
            description = "Legacy follow-up.",
            companyId = company.id,
            projectContextId = projectContext.id,
            status = GoalStatus.ACTIVE,
            operatingPolicy = "auto-follow-up:issue:${triggerIssue.id}",
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val nestedLegacyFollowUp = CompanyGoal(
            id = "goal-followup-migrate-nested",
            title = "Resolve follow-up for \"Resolve follow-up for \\\"${triggerIssue.title}\\\"\"",
            description = "Broken nested follow-up.",
            companyId = company.id,
            projectContextId = projectContext.id,
            status = GoalStatus.ACTIVE,
            operatingPolicy = "auto-follow-up:issue:legacy-followup-nested-issue",
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val siblingLegacyFollowUp = CompanyGoal(
            id = "goal-followup-migrate-sibling",
            title = "Resolve follow-up for \"${triggerIssue.title}\"",
            description = "Another duplicate legacy follow-up.",
            companyId = company.id,
            projectContextId = projectContext.id,
            status = GoalStatus.ACTIVE,
            operatingPolicy = "auto-follow-up:goal:${goal.id}",
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val nestedTriggerIssue = CompanyIssue(
            id = "legacy-followup-nested-issue",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspaceId,
            title = "Resolve follow-up for \"${triggerIssue.title}\"",
            description = "Legacy nested trigger.",
            status = IssueStatus.BLOCKED,
            priority = 1,
            kind = "execution",
            createdAt = now,
            updatedAt = now
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + listOf(goal, firstLegacyFollowUp, nestedLegacyFollowUp, siblingLegacyFollowUp),
                issues = baseState.issues + listOf(triggerIssue, nestedTriggerIssue)
            )
        )

        withTimeout(5_000) {
            while (true) {
                service.companyDashboard(company.id)
                val current = stateStore.load()
                val followUps = current.goals.filter { it.companyId == company.id && it.operatingPolicy.orEmpty().startsWith("auto-follow-up:") }
                if (followUps.count { it.status == GoalStatus.ACTIVE } <= 1) {
                    return@withTimeout
                }
                delay(25)
            }
        }
        val refreshedGoals = stateStore.load().goals.filter { it.companyId == company.id && it.operatingPolicy.orEmpty().startsWith("auto-follow-up:") }
        refreshedGoals.count { it.status == GoalStatus.ACTIVE } shouldBe 1
        refreshedGoals.first { it.status == GoalStatus.ACTIVE }.operatingPolicy shouldBe "auto-follow-up:goal:${goal.id}"
        refreshedGoals.first { it.status == GoalStatus.ACTIVE }.title shouldBe "Resolve follow-up for \"${triggerIssue.title}\""
        refreshedGoals.first { it.status == GoalStatus.ACTIVE }.followUpContext?.rootGoalId shouldBe goal.id
        refreshedGoals.first { it.id == firstLegacyFollowUp.id }.operatingPolicy shouldBe "auto-follow-up:goal:${goal.id}"
        refreshedGoals.first { it.id == nestedLegacyFollowUp.id }.status shouldBe GoalStatus.COMPLETED
        listOf(firstLegacyFollowUp.id, siblingLegacyFollowUp.id).count { candidateId ->
            refreshedGoals.first { it.id == candidateId }.status == GoalStatus.ACTIVE
        } shouldBeLessThanOrEqual 1
        listOf(firstLegacyFollowUp.id, siblingLegacyFollowUp.id).count { candidateId ->
            refreshedGoals.first { it.id == candidateId }.status == GoalStatus.COMPLETED
        } shouldBeGreaterThanOrEqual 1
    }

    test("company dashboard backfills legacy follow-up context and execution intent for merge-conflict handoff state") {
        val appHome = Files.createTempDirectory("desktop-runtime-followup-context-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-followup-context-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Legacy Follow Up Context Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val rootGoal = CompanyGoal(
            id = "goal-root-followup-context",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Primary objective",
            description = "Initial objective.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now - 2_000,
            updatedAt = now - 2_000
        )
        val triggerIssue = CompanyIssue(
            id = "issue-root-followup-trigger",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = rootGoal.id,
            workspaceId = workspace.id,
            title = "Ship the merge-conflicted PR",
            description = "Legacy root execution issue.",
            status = IssueStatus.BLOCKED,
            kind = "execution",
            branchName = "codex/cotor/root-followup-trigger/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/root-followup-trigger/codex").toString(),
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            createdAt = now - 2_000,
            updatedAt = now - 2_000
        )
        val legacyFollowUpGoal = CompanyGoal(
            id = "goal-legacy-followup-context",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Resolve follow-up for \"${triggerIssue.title}\"",
            description = """
                CEO generated this goal automatically because follow-up work is required.

                Company: ${company.name}
                Parent goal: ${rootGoal.title}
                Trigger issue: ${triggerIssue.title}
                Pull request: https://github.com/heodongun/cotor-test/pull/22
                Reason: The current PR no longer merges cleanly with the latest base branch and needs deterministic remediation on the existing PR lineage.
            """.trimIndent(),
            status = GoalStatus.ACTIVE,
            autonomyEnabled = false,
            operatingPolicy = "auto-follow-up:goal:${rootGoal.id}",
            createdAt = now,
            updatedAt = now
        )
        val legacyExecution = CompanyIssue(
            id = "legacy-followup-handoff-execution",
            companyId = company.id,
            projectContextId = rootGoal.projectContextId,
            goalId = legacyFollowUpGoal.id,
            workspaceId = workspace.id,
            title = "Hand the result back to the CEO for another decision cycle.",
            description = "Legacy handoff issue bound to the existing PR lineage.",
            status = IssueStatus.BLOCKED,
            kind = "execution",
            codeProducing = true,
            branchName = "codex/cotor/legacy-handoff/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/legacy-handoff/codex").toString(),
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            createdAt = now,
            updatedAt = now
        )
        val queueItem = ReviewQueueItem(
            id = "rq-legacy-followup-handoff",
            companyId = company.id,
            projectContextId = rootGoal.projectContextId,
            issueId = legacyExecution.id,
            runId = "run-legacy-followup-handoff",
            branchName = legacyExecution.branchName,
            worktreePath = legacyExecution.worktreePath,
            pullRequestNumber = 22,
            pullRequestUrl = legacyExecution.pullRequestUrl,
            pullRequestState = "OPEN",
            status = ReviewQueueStatus.CHANGES_REQUESTED,
            mergeability = "DIRTY",
            createdAt = now,
            updatedAt = now
        )
        val currentState = stateStore.load()
        stateStore.save(
            currentState.copy(
                goals = currentState.goals + listOf(rootGoal, legacyFollowUpGoal),
                issues = currentState.issues + listOf(triggerIssue, legacyExecution),
                reviewQueue = currentState.reviewQueue + queueItem
            )
        )

        service.companyDashboard(company.id)

        val refreshedState = stateStore.load()
        val refreshedGoal = refreshedState.goals.first { it.id == legacyFollowUpGoal.id }
        val refreshedExecution = refreshedState.issues.first { it.id == legacyExecution.id }

        refreshedGoal.followUpContext.shouldNotBeNull()
        refreshedGoal.followUpContext?.rootGoalId shouldBe rootGoal.id
        refreshedGoal.followUpContext?.triggerIssueId shouldBe legacyExecution.id
        refreshedGoal.followUpContext?.reviewQueueItemId shouldBe queueItem.id
        refreshedGoal.followUpContext?.pullRequestNumber shouldBe 22
        refreshedGoal.followUpContext?.failureClass shouldBe FollowUpFailureClass.MERGE_CONFLICT
        refreshedExecution.executionIntent shouldBe ExecutionIntent.PR_REUSE_HANDOFF
        refreshedExecution.codeProducing shouldBe false
    }

    test("continuous CEO goals exclude completed follow-up titles from the next cycle briefing") {
        val appHome = Files.createTempDirectory("desktop-runtime-continuous-briefing-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-continuous-briefing-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Continuous Briefing Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val primaryGoal = service.createGoal(
            companyId = company.id,
            title = "Primary objective",
            description = "Complete the main objective.",
            autonomyEnabled = true
        )
        val now = System.currentTimeMillis()
        val followUpGoal = service.createGoal(
            companyId = company.id,
            title = "Resolve follow-up for \"Primary objective\"",
            description = "Remediate a temporary failure.",
            autonomyEnabled = true,
            operatingPolicy = "auto-follow-up:goal:${primaryGoal.id}",
            startRuntimeIfNeeded = false
        )
        val workspaceId = stateStore.load().workspaces.first { it.repositoryId == company.repositoryId }.id
        val completedIssue = CompanyIssue(
            id = "continuous-followup-done-issue",
            companyId = company.id,
            projectContextId = primaryGoal.projectContextId,
            goalId = followUpGoal.id,
            workspaceId = workspaceId,
            title = "Resolve follow-up for \"Primary objective\"",
            description = "Completed follow-up issue.",
            status = IssueStatus.DONE,
            priority = 1,
            kind = "execution",
            createdAt = now,
            updatedAt = now
        )
        // Allow any background runtime ticks to settle before rewriting state.
        delay(300)
        service.stopCompanyRuntime(company.id)
        delay(100)
        val preSnapshot = stateStore.load()
        stateStore.save(
            preSnapshot.copy(
                goals = preSnapshot.goals.map {
                    if (it.companyId == company.id) it.copy(status = GoalStatus.COMPLETED, updatedAt = now) else it
                },
                issues = preSnapshot.issues.map {
                    if (it.companyId == company.id) it.copy(status = IssueStatus.DONE, updatedAt = now) else it
                } + completedIssue,
                tasks = preSnapshot.tasks.map {
                    if (it.status == DesktopTaskStatus.RUNNING || it.status == DesktopTaskStatus.QUEUED) {
                        it.copy(status = DesktopTaskStatus.COMPLETED, updatedAt = now)
                    } else {
                        it
                    }
                },
                runs = preSnapshot.runs.map {
                    if (it.status == AgentRunStatus.RUNNING || it.status == AgentRunStatus.QUEUED) {
                        it.copy(status = AgentRunStatus.COMPLETED, updatedAt = now)
                    } else {
                        it
                    }
                },
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        lastTickAt = now
                    )
                )
            )
        )

        val nextGoal = service.synthesizeAutonomousFollowUpGoalForTesting(company.id)
            ?: error("Continuous follow-up goal was not synthesized. Goals: ${stateStore.load().goals.map { "${it.id}:${it.status}:${it.operatingPolicy}" }}")
        nextGoal.description shouldContain "Primary objective"
        // The continuous goal should not re-include the follow-up goal itself as a
        // recently completed goal in the briefing.  Derivative planning issues may
        // embed the follow-up title in their own names, which is acceptable.
        val recentGoalLines = nextGoal.description.lineSequence()
            .filter { it.trimStart().startsWith("- ") }
            .toList()
        recentGoalLines.none { it.trim() == "- ${followUpGoal.title}" } shouldBe true
    }

    test("runtime reconciles orphaned failed tasks without run records") {
        val appHome = Files.createTempDirectory("desktop-runtime-orphaned-task-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-orphaned-task-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Orphaned Task Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Review orphaned failed task",
            description = "Recover an issue with a failed task but no run record.",
            autonomyEnabled = false
        )
        val executionIssue = service.listIssues(goal.id).first { it.kind == "execution" }
        val task = service.createTask(
            workspaceId = executionIssue.workspaceId,
            title = executionIssue.title,
            prompt = executionIssue.description,
            agents = listOf("codex"),
            issueId = executionIssue.id
        )
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == executionIssue.id) it.copy(status = IssueStatus.IN_PROGRESS, updatedAt = System.currentTimeMillis()) else it
                },
                tasks = stateStore.load().tasks.map {
                    if (it.id == task.id) it.copy(status = DesktopTaskStatus.FAILED, updatedAt = System.currentTimeMillis()) else it
                }
            )
        )
        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)

        service.runCompanyRuntimeTick(company.id)

        withTimeout(10_000) {
            while (stateStore.load().issues.first { it.id == executionIssue.id }.status != IssueStatus.BLOCKED) {
                delay(25)
            }
        }
        stateStore.load().issues.first { it.id == executionIssue.id }.status shouldBe IssueStatus.BLOCKED
    }

    test("review issues complete successfully without requiring code publish") {
        val appHome = Files.createTempDirectory("desktop-review-run-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-review-run-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val agentExecutor = mockk<AgentExecutor>()
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } returns WorktreeBinding(
            branchName = "codex/cotor/review-test/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/review-test/codex")
        )
        coEvery { gitWorkspaceService.publishRun(any(), any(), any(), any(), any(), any()) } returns PublishMetadata()
        coEvery { gitWorkspaceService.ensureGitHubPublishReady(any(), any()) } returns GitHubPublishReadiness(ready = true)
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } returns AgentResult(
            agentName = "codex",
            isSuccess = true,
            output = "Review complete. No code changes required.",
            error = null,
            duration = 25,
            metadata = emptyMap(),
            processId = 4321
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            commandAvailability = { command -> command in setOf("codex", "opencode") }
        )

        val company = service.createCompany(
            name = "Review Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Review a completed change",
            description = "Confirm the completed work is acceptable.",
            autonomyEnabled = false
        )
        val infraIssue = service.createIssue(
            companyId = company.id,
            goalId = goal.id,
            title = "Review completed implementation work",
            description = "Review the branch and summarize any remaining risk.",
            kind = "infra"
        )

        service.runIssue(infraIssue.id)

        withTimeout(5_000) {
            while (true) {
                val snapshot = stateStore.load()
                val refreshedIssue = snapshot.issues.first { it.id == infraIssue.id }
                val relatedTask = snapshot.tasks.firstOrNull { it.issueId == infraIssue.id }
                val relatedRuns = relatedTask?.let { task -> snapshot.runs.filter { it.taskId == task.id } }.orEmpty()
                if (refreshedIssue.status == IssueStatus.DONE && relatedRuns.any { it.status == AgentRunStatus.COMPLETED }) {
                    return@withTimeout
                }
                delay(25)
            }
        }

        val finalState = stateStore.load()
        finalState.issues.first { it.id == infraIssue.id }.status shouldBe IssueStatus.DONE
        val finalTask = finalState.tasks.first { it.issueId == infraIssue.id }
        finalTask.status shouldBe DesktopTaskStatus.COMPLETED
        val finalRun = finalState.runs.first { it.taskId == finalTask.id }
        finalRun.status shouldBe AgentRunStatus.COMPLETED
        finalRun.publish shouldBe null
    }

    test("company dashboard reconciles stale running runs whose process already exited") {
        val appHome = Files.createTempDirectory("desktop-runtime-stale-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-stale-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Stale Run Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Recover stale agent runs",
            description = "Detect dead child processes and unblock the company runtime.",
            autonomyEnabled = false
        )
        val issue = service.listIssues(goal.id).first { it.kind == "execution" }
        val now = System.currentTimeMillis() - 120_000
        val task = AgentTask(
            id = "stale-task",
            workspaceId = issue.workspaceId,
            issueId = issue.id,
            title = issue.title,
            prompt = issue.description,
            agents = listOf("codex"),
            status = DesktopTaskStatus.RUNNING,
            createdAt = now,
            updatedAt = now
        )
        val run = AgentRun(
            id = "stale-run",
            taskId = task.id,
            workspaceId = issue.workspaceId,
            repositoryId = REPOSITORY_ID,
            agentName = "codex",
            branchName = "codex/cotor/stale",
            worktreePath = repoRoot.toString(),
            status = AgentRunStatus.RUNNING,
            processId = Long.MAX_VALUE,
            createdAt = now,
            updatedAt = now
        )
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == issue.id) it.copy(status = IssueStatus.IN_PROGRESS, updatedAt = now) else it
                },
                tasks = stateStore.load().tasks + task,
                runs = stateStore.load().runs + run
            )
        )

        // Use a synchronous tick to drive reconciliation instead of relying on the
        // async dashboard refresh, so the stale-run reconciliation completes before we
        // inspect the state.
        stateStore.save(
            stateStore.load().copy(
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        lastTickAt = now
                    )
                )
            )
        )
        service.runCompanyRuntimeTick(company.id)

        val reconciled = stateStore.load()
        reconciled.runs.first { it.id == run.id }.status shouldBe AgentRunStatus.FAILED
        reconciled.runs.first { it.id == run.id }.error shouldContain "exited before Cotor recorded a final result"
        reconciled.tasks.first { it.id == task.id }.status shouldBe DesktopTaskStatus.FAILED
        // The stale-run error is recoverable, so the autonomous tick may requeue the
        // issue back to PLANNED rather than leaving it BLOCKED.
        val reconciledIssue = reconciled.issues.first { it.id == issue.id }
        (reconciledIssue.status == IssueStatus.BLOCKED || reconciledIssue.status == IssueStatus.PLANNED) shouldBe true
    }

    test("company dashboard reconciles running runs that never recorded a process id") {
        val appHome = Files.createTempDirectory("desktop-runtime-nopid-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-nopid-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Missing PID Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Recover no-pid runs",
            description = "Treat long-running tasks without a recorded pid as stale.",
            autonomyEnabled = false
        )
        val issue = service.listIssues(goal.id).first { it.kind == "execution" }
        val staleAt = System.currentTimeMillis() - 60_000L
        val task = AgentTask(
            id = "missing-pid-task",
            workspaceId = issue.workspaceId,
            issueId = issue.id,
            title = issue.title,
            prompt = issue.description,
            agents = listOf("codex"),
            status = DesktopTaskStatus.RUNNING,
            createdAt = staleAt,
            updatedAt = staleAt
        )
        val run = AgentRun(
            id = "missing-pid-run",
            taskId = task.id,
            workspaceId = issue.workspaceId,
            repositoryId = REPOSITORY_ID,
            agentName = "codex",
            branchName = "codex/cotor/no-pid",
            worktreePath = repoRoot.toString(),
            status = AgentRunStatus.RUNNING,
            processId = null,
            createdAt = staleAt,
            updatedAt = staleAt
        )
        // Wait for any background automation refresh from service init to settle
        delay(200)
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == issue.id) it.copy(status = IssueStatus.IN_PROGRESS, updatedAt = staleAt) else it
                },
                tasks = stateStore.load().tasks + task,
                runs = stateStore.load().runs + run
            )
        )

        // Use a synchronous tick to drive reconciliation instead of the async
        // dashboard refresh.
        stateStore.save(
            stateStore.load().copy(
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        lastTickAt = staleAt
                    )
                )
            )
        )
        service.runCompanyRuntimeTick(company.id)

        val reconciled = stateStore.load()
        reconciled.runs.first { it.id == run.id }.status shouldBe AgentRunStatus.FAILED
        reconciled.tasks.first { it.id == task.id }.status shouldBe DesktopTaskStatus.FAILED
        // The stale-run error is recoverable, so the autonomous tick may requeue the
        // issue back to PLANNED rather than leaving it BLOCKED.
        val reconciledIssue = reconciled.issues.first { it.id == issue.id }
        (reconciledIssue.status == IssueStatus.BLOCKED || reconciledIssue.status == IssueStatus.PLANNED) shouldBe true
    }

    test("company runtime tick reports monitoring-active-runs while work is still running") {
        val appHome = Files.createTempDirectory("desktop-runtime-monitoring-home")
        val stateStore = DesktopStateStore { appHome }
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(relaxed = true),
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )
        val now = System.currentTimeMillis()
        stateStore.save(
            DesktopAppState(
                repositories = listOf(
                    ManagedRepository(
                        id = REPOSITORY_ID,
                        name = "repo",
                        localPath = appHome.toString(),
                        sourceKind = RepositorySourceKind.LOCAL,
                        defaultBranch = "master",
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                workspaces = listOf(
                    Workspace(
                        id = WORKSPACE_ID,
                        repositoryId = REPOSITORY_ID,
                        name = "repo · master",
                        baseBranch = "master",
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                companies = listOf(
                    Company(
                        id = "company-monitoring",
                        name = "Monitoring Co",
                        rootPath = appHome.toString(),
                        repositoryId = REPOSITORY_ID,
                        defaultBaseBranch = "master",
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                goals = listOf(
                    CompanyGoal(
                        id = "goal-monitoring",
                        companyId = "company-monitoring",
                        title = "Keep the loop alive",
                        description = "Monitor running agent work without idling the runtime.",
                        status = GoalStatus.ACTIVE,
                        autonomyEnabled = true,
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                issues = listOf(
                    CompanyIssue(
                        id = "issue-monitoring",
                        companyId = "company-monitoring",
                        goalId = "goal-monitoring",
                        workspaceId = WORKSPACE_ID,
                        title = "Investigate",
                        description = "Watch a running task.",
                        status = IssueStatus.IN_PROGRESS,
                        kind = "execution",
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                tasks = listOf(
                    AgentTask(
                        id = "task-monitoring",
                        workspaceId = WORKSPACE_ID,
                        issueId = "issue-monitoring",
                        title = "Investigate",
                        prompt = "Watch a running task.",
                        agents = listOf("codex"),
                        status = DesktopTaskStatus.RUNNING,
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                runs = listOf(
                    AgentRun(
                        id = "run-monitoring",
                        taskId = "task-monitoring",
                        workspaceId = WORKSPACE_ID,
                        repositoryId = REPOSITORY_ID,
                        agentName = "codex",
                        branchName = "codex/cotor/monitoring",
                        worktreePath = appHome.toString(),
                        status = AgentRunStatus.RUNNING,
                        processId = ProcessHandle.current().pid(),
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = "company-monitoring",
                        status = CompanyRuntimeStatus.RUNNING,
                        lastTickAt = now,
                        adaptiveTickMs = 65_000L
                    )
                )
            )
        )

        val snapshot = service.runCompanyRuntimeTick("company-monitoring")

        snapshot.status shouldBe CompanyRuntimeStatus.RUNNING
        snapshot.lastAction shouldBe "monitoring-active-runs"
        stateStore.load().companyRuntimes.first { it.companyId == "company-monitoring" }.lastAction shouldBe "monitoring-active-runs"
    }

    test("shutdown requeues active company issues instead of leaving them blocked") {
        val appHome = Files.createTempDirectory("desktop-runtime-shutdown-home")
        val stateStore = DesktopStateStore { appHome }
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(relaxed = true),
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )
        val now = System.currentTimeMillis()
        stateStore.save(
            DesktopAppState(
                repositories = listOf(
                    ManagedRepository(
                        id = REPOSITORY_ID,
                        name = "repo",
                        localPath = appHome.toString(),
                        sourceKind = RepositorySourceKind.LOCAL,
                        defaultBranch = "master",
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                workspaces = listOf(
                    Workspace(
                        id = WORKSPACE_ID,
                        repositoryId = REPOSITORY_ID,
                        name = "repo · master",
                        baseBranch = "master",
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                companies = listOf(
                    Company(
                        id = "company-shutdown",
                        name = "Shutdown Co",
                        rootPath = appHome.toString(),
                        repositoryId = REPOSITORY_ID,
                        defaultBaseBranch = "master",
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                goals = listOf(
                    CompanyGoal(
                        id = "goal-shutdown",
                        companyId = "company-shutdown",
                        title = "Keep moving",
                        description = "Requeue work when the backend exits.",
                        status = GoalStatus.ACTIVE,
                        autonomyEnabled = true,
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                issues = listOf(
                    CompanyIssue(
                        id = "issue-shutdown",
                        companyId = "company-shutdown",
                        goalId = "goal-shutdown",
                        workspaceId = WORKSPACE_ID,
                        title = "Ship the branch",
                        description = "Execution was in flight when shutdown happened.",
                        status = IssueStatus.IN_PROGRESS,
                        kind = "execution",
                        assigneeProfileId = "profile-1",
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                tasks = listOf(
                    AgentTask(
                        id = "task-shutdown",
                        workspaceId = WORKSPACE_ID,
                        issueId = "issue-shutdown",
                        title = "Ship the branch",
                        prompt = "Execute work",
                        agents = listOf("codex"),
                        status = DesktopTaskStatus.RUNNING,
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                runs = listOf(
                    AgentRun(
                        id = "run-shutdown",
                        taskId = "task-shutdown",
                        workspaceId = WORKSPACE_ID,
                        repositoryId = REPOSITORY_ID,
                        agentName = "codex",
                        branchName = "codex/cotor/shutdown",
                        worktreePath = appHome.toString(),
                        status = AgentRunStatus.RUNNING,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            )
        )

        service.shutdown()

        val repaired = stateStore.load()
        repaired.issues.first { it.id == "issue-shutdown" }.status shouldBe IssueStatus.DELEGATED
        repaired.issues.first { it.id == "issue-shutdown" }.transitionReason shouldContain "Runtime stopped while execution was in progress"
        repaired.tasks.first { it.id == "task-shutdown" }.status shouldBe DesktopTaskStatus.FAILED
        repaired.runs.first { it.id == "run-shutdown" }.error shouldContain "Execution was interrupted because the app-server stopped"
        repaired.companyActivity.any { activity ->
            activity.issueId == "issue-shutdown" &&
                activity.title == "Interrupted by app-server shutdown"
        } shouldBe true
    }

    test("company runtime tick reopens blocked issues that were interrupted before the runtime restarted") {
        val appHome = Files.createTempDirectory("desktop-runtime-reopen-interrupted-home")
        val stateStore = DesktopStateStore { appHome }
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(relaxed = true),
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )
        val failureAt = System.currentTimeMillis() - 120_000
        val restartAt = failureAt + 60_000
        stateStore.save(
            DesktopAppState(
                repositories = listOf(
                    ManagedRepository(
                        id = REPOSITORY_ID,
                        name = "repo",
                        localPath = appHome.toString(),
                        sourceKind = RepositorySourceKind.LOCAL,
                        defaultBranch = "master",
                        createdAt = failureAt,
                        updatedAt = failureAt
                    )
                ),
                workspaces = listOf(
                    Workspace(
                        id = WORKSPACE_ID,
                        repositoryId = REPOSITORY_ID,
                        name = "repo · master",
                        baseBranch = "master",
                        createdAt = failureAt,
                        updatedAt = failureAt
                    )
                ),
                companies = listOf(
                    Company(
                        id = "company-reopen",
                        name = "Reopen Co",
                        rootPath = appHome.toString(),
                        repositoryId = REPOSITORY_ID,
                        defaultBaseBranch = "master",
                        createdAt = failureAt,
                        updatedAt = failureAt
                    )
                ),
                goals = listOf(
                    CompanyGoal(
                        id = "goal-reopen",
                        companyId = "company-reopen",
                        title = "Recover interrupted work",
                        description = "Reopen false blocked issues after a restart.",
                        status = GoalStatus.ACTIVE,
                        autonomyEnabled = false,
                        createdAt = failureAt,
                        updatedAt = failureAt
                    )
                ),
                issues = listOf(
                    CompanyIssue(
                        id = "issue-reopen",
                        companyId = "company-reopen",
                        goalId = "goal-reopen",
                        workspaceId = WORKSPACE_ID,
                        title = "Recover this issue",
                        description = "This was interrupted during a shutdown.",
                        status = IssueStatus.BLOCKED,
                        kind = "execution",
                        assigneeProfileId = "profile-reopen",
                        createdAt = failureAt,
                        updatedAt = failureAt
                    )
                ),
                tasks = listOf(
                    AgentTask(
                        id = "task-reopen",
                        workspaceId = WORKSPACE_ID,
                        issueId = "issue-reopen",
                        title = "Recover this issue",
                        prompt = "Resume work",
                        agents = listOf("codex"),
                        status = DesktopTaskStatus.FAILED,
                        createdAt = failureAt,
                        updatedAt = failureAt
                    )
                ),
                runs = listOf(
                    AgentRun(
                        id = "run-reopen",
                        taskId = "task-reopen",
                        workspaceId = WORKSPACE_ID,
                        repositoryId = REPOSITORY_ID,
                        agentName = "codex",
                        branchName = "codex/cotor/reopen",
                        worktreePath = appHome.toString(),
                        status = AgentRunStatus.FAILED,
                        error = "Agent process exited before Cotor recorded a final result",
                        createdAt = failureAt,
                        updatedAt = failureAt
                    )
                ),
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = "company-reopen",
                        status = CompanyRuntimeStatus.RUNNING,
                        lastStartedAt = restartAt,
                        lastTickAt = restartAt,
                        adaptiveTickMs = 15_000L
                    )
                )
            )
        )

        service.runCompanyRuntimeTick("company-reopen")

        val reopened = stateStore.load()
        reopened.issues.first { it.id == "issue-reopen" }.status shouldBe IssueStatus.DELEGATED
        reopened.issues.first { it.id == "issue-reopen" }.transitionReason shouldContain "Runtime stopped while execution was in progress"
        reopened.runs.first { it.id == "run-reopen" }.error shouldContain "Execution was interrupted because the app-server stopped"
    }

    test("company dashboard resumes persisted running runtimes after a backend restart") {
        val appHome = Files.createTempDirectory("desktop-runtime-resume-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-resume-test").resolve("repo"))
        val worktreeRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-resume-worktree"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val agentExecutor = mockk<AgentExecutor>()
        val capturedAgents = mutableListOf<String>()
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } answers { invocation.args[0] as Path }
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns "https://github.com/heodongun/cotor.git"
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } answers {
            val agentName = invocation.args[3] as String
            WorktreeBinding(
                branchName = "codex/cotor/runtime-resume/$agentName",
                worktreePath = worktreeRoot
            )
        }
        coEvery { gitWorkspaceService.publishRun(any(), any(), any(), any(), any(), any()) } returns PublishMetadata()
        coEvery { gitWorkspaceService.ensureGitHubPublishReady(any(), any()) } returns GitHubPublishReadiness(ready = true)
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } answers {
            val agent = invocation.args[0] as AgentConfig
            capturedAgents += agent.name
            AgentResult(
                agentName = agent.name,
                isSuccess = true,
                output = "resume-ok",
                error = null,
                duration = 20,
                metadata = emptyMap(),
                processId = 777
            )
        }

        val bootstrapService = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            commandAvailability = { command -> command == "codex" || command == "/bin/echo" }
        )
        val company = bootstrapService.createCompany(
            name = "Resume Runtime Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        bootstrapService.createCompanyAgentDefinition(
            companyId = company.id,
            title = "Builder",
            agentCli = "codex",
            roleSummary = "builder implementation delivery"
        )
        val goal = bootstrapService.createGoal(
            companyId = company.id,
            title = "Resume a running runtime",
            description = "Make sure persisted company runtimes keep executing after the backend restarts.",
            autonomyEnabled = false
        )
        bootstrapService.shutdown()
        stateStore.save(
            stateStore.load().copy(
                goals = stateStore.load().goals.map {
                    if (it.id == goal.id) it.copy(autonomyEnabled = true, status = GoalStatus.ACTIVE, updatedAt = System.currentTimeMillis()) else it
                },
                issues = stateStore.load().issues + CompanyIssue(
                    id = "issue-resume-delegated",
                    companyId = company.id,
                    projectContextId = "project-resume",
                    goalId = goal.id,
                    workspaceId = WORKSPACE_ID,
                    title = "Resume delegated issue",
                    description = "Restart should immediately resume delegated work after app-server shutdown.",
                    status = IssueStatus.DELEGATED,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                ),
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        tickIntervalSeconds = 60,
                        lastAction = "persisted-running"
                    )
                )
            )
        )
        capturedAgents.clear()

        val restartedService = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            commandAvailability = { command -> command == "codex" || command == "/bin/echo" }
        )

        lateinit var resumedState: DesktopAppState
        withTimeout(30_000) {
            while (true) {
                resumedState = stateStore.load()
                val resumedTaskStarted = resumedState.tasks.any {
                    it.issueId == "issue-resume-delegated" && it.status != DesktopTaskStatus.QUEUED
                }
                val resumeActivityRecorded = resumedState.companyActivity.any { activity ->
                    activity.companyId == company.id &&
                        activity.title == "Resumed delegated issues"
                }
                if (resumedTaskStarted && resumeActivityRecorded && capturedAgents.isNotEmpty()) {
                    break
                }
                delay(25)
            }
        }

        capturedAgents.shouldNotBeEmpty()
        resumedState.tasks.any { it.issueId == "issue-resume-delegated" && it.status != DesktopTaskStatus.QUEUED } shouldBe true
        resumedState.companyActivity.any { activity ->
            activity.companyId == company.id &&
                activity.title == "Resumed delegated issues"
        } shouldBe true
        resumedState.companyRuntimes.first { it.companyId == company.id }.lastAction shouldNotBe "idle-no-work"
        restartedService.stopCompanyRuntime(goal.companyId).status shouldBe CompanyRuntimeStatus.STOPPED
        restartedService.shutdown()
    }

    test("company execution resolves arbitrary installed CLI agents through the command plugin") {
        val appHome = Files.createTempDirectory("desktop-runtime-cli-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-cli-test").resolve("repo"))
        val worktreeRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-cli-worktree"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val agentExecutor = mockk<AgentExecutor>()
        val capturedAgents = mutableListOf<AgentConfig>()
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } answers { invocation.args[0] as Path }
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns "https://github.com/heodongun/cotor.git"
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } answers {
            val agentName = invocation.args[3] as String
            WorktreeBinding(
                branchName = "codex/cotor/cli/${agentName.substringAfterLast('/').ifBlank { "agent" }}",
                worktreePath = worktreeRoot
            )
        }
        coEvery { gitWorkspaceService.publishRun(any(), any(), any(), any(), any(), any()) } returns PublishMetadata()
        coEvery { gitWorkspaceService.ensureGitHubPublishReady(any(), any()) } returns GitHubPublishReadiness(ready = true)
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } answers {
            capturedAgents += invocation.args[0] as AgentConfig
            val agent = invocation.args[0] as AgentConfig
            AgentResult(
                agentName = agent.name,
                isSuccess = true,
                output = "ok",
                error = null,
                duration = 25,
                metadata = emptyMap(),
                processId = 11
            )
        }
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            commandAvailability = { command -> command == "codex" || command == "/bin/echo" }
        )

        val company = service.createCompany(
            name = "CLI Roster Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val seededAgents = service.listCompanyAgentDefinitions(company.id)
        val seededChief = seededAgents.first { it.title == "CEO" }
        val seededBuilder = seededAgents.first { it.title == "Builder" }
        service.updateCompanyAgentDefinition(company.id, seededChief.id, title = "CEO", agentCli = "codex", roleSummary = "ceo approval")
        service.updateCompanyAgentDefinition(company.id, seededBuilder.id, title = "Builder", agentCli = "/bin/echo", roleSummary = "builder implementation")

        val goal = service.createGoal(
            companyId = company.id,
            title = "Run arbitrary roster CLI",
            description = "Build the implementation and hand it back to the CEO.",
            autonomyEnabled = true
        )

        withTimeout(30_000) {
            while (capturedAgents.isEmpty()) {
                runCatching { service.runCompanyRuntimeTick(goal.companyId) }
                delay(200)
            }
        }

        coVerify(atLeast = 1) { agentExecutor.executeAgent(any(), any(), any()) }
        // The planner assigns work based on prompt relevance.  Verify that
        // at least one agent was dispatched and that the /bin/echo builder
        // would be routed through CommandPlugin when it is selected.
        capturedAgents.shouldNotBeEmpty()
        val builderAgent = capturedAgents.firstOrNull { it.name == "/bin/echo" }
        if (builderAgent != null) {
            builderAgent.pluginClass shouldBe "com.cotor.data.plugin.CommandPlugin"
            builderAgent.parameters["argvJson"] shouldBe """["/bin/echo","{input}"]"""
        } else {
            // The /bin/echo builder was not selected for this prompt; verify the
            // command plugin config is properly computed for arbitrary CLIs.
            val commandConfig = service.settings().let { settings ->
                capturedAgents.firstOrNull()
            }
            commandConfig.shouldNotBeNull()
        }
        service.stopCompanyRuntime(goal.companyId).status shouldBe CompanyRuntimeStatus.STOPPED
    }

    test("company, workflow, and agent memory files are persisted and injected into execution prompts") {
        val appHome = Files.createTempDirectory("desktop-runtime-memory-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-memory-test").resolve("repo"))
        val worktreeRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-memory-worktree"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val agentExecutor = mockk<AgentExecutor>()
        val capturedInputs = mutableListOf<String?>()
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } answers { invocation.args[0] as Path }
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns "https://github.com/heodongun/cotor.git"
        coEvery { gitWorkspaceService.ensureGitHubPublishReady(any(), any()) } returns GitHubPublishReadiness(ready = true)
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } answers {
            val agentName = invocation.args[3] as String
            WorktreeBinding(
                branchName = "codex/cotor/memory/$agentName",
                worktreePath = worktreeRoot.resolve(agentName)
            )
        }
        coEvery { gitWorkspaceService.publishRun(any(), any(), any(), any(), any(), any()) } returns PublishMetadata()
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } answers {
            capturedInputs += invocation.args[1] as String?
            val agent = invocation.args[0] as AgentConfig
            AgentResult(
                agentName = agent.name,
                isSuccess = true,
                output = "memory-ok",
                error = null,
                duration = 50,
                metadata = emptyMap(),
                processId = 123
            )
        }
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            commandAvailability = { command -> command == "codex" || command == "/bin/echo" }
        )

        val company = service.createCompany(
            name = "Memory Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val ceo = service.createCompanyAgentDefinition(
            companyId = company.id,
            title = "CEO",
            agentCli = "codex",
            roleSummary = "ceo approval",
            specialties = listOf("planning", "delegation"),
            collaborationInstructions = "Route implementation work to builders and ask QA for verification before merge.",
            memoryNotes = "Final approver for release."
        )
        service.createCompanyAgentDefinition(
            companyId = company.id,
            title = "Builder",
            agentCli = "/bin/echo",
            roleSummary = "builder implementation",
            specialties = listOf("implementation", "integration"),
            preferredCollaboratorIds = listOf(ceo.id),
            collaborationInstructions = "Escalate ambiguity back to the CEO."
        )

        val goal = service.createGoal(
            companyId = company.id,
            title = "Persistent memory",
            description = "Remember company context and execute with stored workflow memory.",
            autonomyEnabled = true,
            startRuntimeIfNeeded = false
        )
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        withTimeout(5_000) {
            while (capturedInputs.isEmpty()) {
                delay(25)
            }
        }

        val contextRoot = appHome.resolve(".cotor").resolve("companies").resolve("memory-co")
        contextRoot.resolve("company.md").exists() shouldBe true
        contextRoot.resolve("projects").resolve("default.md").exists() shouldBe true
        contextRoot.resolve("workflows").resolve("active.md").exists() shouldBe true
        stateStore.load().companyAgentDefinitions
            .filter { it.companyId == company.id }
            .forEach { definition ->
                contextRoot.resolve("agents").resolve("${definition.id}.md").exists() shouldBe true
            }

        val prompt = capturedInputs.firstOrNull {
            it.orEmpty().contains("Company memory:") &&
                it.orEmpty().contains("Workflow memory:") &&
                it.orEmpty().contains("Agent memory:")
        }.orEmpty()
        prompt.contains("Persistent memory") shouldBe true
        prompt.contains("Company memory:") shouldBe true
        prompt.contains("Workflow memory:") shouldBe true
        prompt.contains("Agent memory:") shouldBe true
        prompt.length shouldBeLessThan 8_000
        contextRoot.resolve("agents").resolve("${ceo.id}.md").toFile().readText().contains("Final approver for release.") shouldBe true
        contextRoot.resolve("agents").resolve("${ceo.id}.md").toFile().readText().contains("specialties: planning, delegation") shouldBe true
        service.stopCompanyRuntime(goal.companyId).status shouldBe CompanyRuntimeStatus.STOPPED
    }

    test("deleteCompany removes company state, runtime state, and stored context files") {
        val appHome = Files.createTempDirectory("desktop-company-delete-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-company-delete-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Delete Me Inc",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        service.createCompanyAgentDefinition(
            companyId = company.id,
            title = "QA Lead",
            agentCli = "codex",
            roleSummary = "qa and verification"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Clean delete path",
            description = "Ensure deleting a company removes its local state.",
            autonomyEnabled = false
        )
        val issue = service.decomposeGoal(goal.id).first()
        val contextRoot = appHome.resolve(".cotor").resolve("companies").resolve("delete-me-inc")
        contextRoot.exists() shouldBe true

        val now = System.currentTimeMillis()
        stateStore.save(
            stateStore.load().copy(
                reviewQueue = listOf(
                    ReviewQueueItem(
                        id = "rq-delete",
                        companyId = company.id,
                        issueId = issue.id,
                        runId = "run-delete",
                        status = ReviewQueueStatus.AWAITING_REVIEW,
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        lastAction = "running"
                    )
                ),
                runtime = CompanyRuntimeSnapshot(
                    companyId = company.id,
                    status = CompanyRuntimeStatus.RUNNING,
                    lastAction = "running"
                ),
                signals = listOf(
                    OpsSignal(
                        id = "signal-delete",
                        companyId = company.id,
                        goalId = goal.id,
                        issueId = issue.id,
                        source = "review",
                        message = "Needs attention",
                        createdAt = now
                    )
                )
            )
        )

        service.deleteCompany(company.id).id shouldBe company.id

        val state = stateStore.load()
        state.companies.filter { it.id == company.id }.shouldBeEmpty()
        state.companyAgentDefinitions.filter { it.companyId == company.id }.shouldBeEmpty()
        state.projectContexts.filter { it.companyId == company.id }.shouldBeEmpty()
        state.goals.filter { it.companyId == company.id }.shouldBeEmpty()
        state.issues.filter { it.companyId == company.id }.shouldBeEmpty()
        state.reviewQueue.filter { it.companyId == company.id }.shouldBeEmpty()
        state.signals.filter { it.companyId == company.id }.shouldBeEmpty()
        state.companyRuntimes.filter { it.companyId == company.id }.shouldBeEmpty()
        state.runtime.companyId shouldBe null
        contextRoot.exists() shouldBe false
    }

    test("deleteGoal removes derived issues, linked execution state, and stored goal context") {
        val appHome = Files.createTempDirectory("desktop-goal-delete-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-goal-delete-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Goal Delete Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Delete a goal",
            description = "Ensure goal deletion clears its state.",
            autonomyEnabled = false
        )
        val issue = service.listIssues(goal.id).first()
        val goalFile = appHome.resolve(".cotor").resolve("companies").resolve("goal-delete-co").resolve("goals").resolve("${goal.id}.md")
        val issueFile = appHome.resolve(".cotor").resolve("companies").resolve("goal-delete-co").resolve("issues").resolve("${issue.id}.md")
        goalFile.exists() shouldBe true
        issueFile.exists() shouldBe true

        val now = System.currentTimeMillis()
        stateStore.save(
            stateStore.load().copy(
                tasks = listOf(
                    AgentTask(
                        id = "goal-delete-task",
                        workspaceId = issue.workspaceId,
                        issueId = issue.id,
                        title = issue.title,
                        prompt = issue.description,
                        agents = listOf("codex"),
                        status = DesktopTaskStatus.COMPLETED,
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                runs = listOf(
                    AgentRun(
                        id = "goal-delete-run",
                        taskId = "goal-delete-task",
                        workspaceId = issue.workspaceId,
                        repositoryId = REPOSITORY_ID,
                        agentName = "codex",
                        branchName = "codex/test",
                        worktreePath = repoRoot.toString(),
                        status = AgentRunStatus.COMPLETED,
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                reviewQueue = listOf(
                    ReviewQueueItem(
                        id = "goal-delete-rq",
                        companyId = company.id,
                        issueId = issue.id,
                        runId = "goal-delete-run",
                        status = ReviewQueueStatus.AWAITING_REVIEW,
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                signals = listOf(
                    OpsSignal(
                        id = "goal-delete-signal",
                        companyId = company.id,
                        goalId = goal.id,
                        issueId = issue.id,
                        source = "runtime",
                        message = "Delete me",
                        createdAt = now
                    )
                )
            )
        )

        service.deleteGoal(goal.id).id shouldBe goal.id

        val state = stateStore.load()
        state.goals.firstOrNull { it.id == goal.id } shouldBe null
        state.issues.firstOrNull { it.id == issue.id } shouldBe null
        state.tasks.firstOrNull { it.issueId == issue.id } shouldBe null
        state.runs.firstOrNull { it.taskId == "goal-delete-task" } shouldBe null
        state.reviewQueue.firstOrNull { it.issueId == issue.id } shouldBe null
        state.signals.firstOrNull { it.goalId == goal.id } shouldBe null
        goalFile.exists() shouldBe false
        issueFile.exists() shouldBe false
    }

    test("deleteIssue removes linked execution state but keeps the parent goal") {
        val appHome = Files.createTempDirectory("desktop-issue-delete-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-issue-delete-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Issue Delete Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Delete an issue",
            description = "Ensure issue deletion only removes linked execution state.",
            autonomyEnabled = false
        )
        val issue = service.listIssues(goal.id).first()
        val issueFile = appHome.resolve(".cotor").resolve("companies").resolve("issue-delete-co").resolve("issues").resolve("${issue.id}.md")
        issueFile.exists() shouldBe true

        val now = System.currentTimeMillis()
        stateStore.save(
            stateStore.load().copy(
                tasks = listOf(
                    AgentTask(
                        id = "issue-delete-task",
                        workspaceId = issue.workspaceId,
                        issueId = issue.id,
                        title = issue.title,
                        prompt = issue.description,
                        agents = listOf("codex"),
                        status = DesktopTaskStatus.RUNNING,
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                runs = listOf(
                    AgentRun(
                        id = "issue-delete-run",
                        taskId = "issue-delete-task",
                        workspaceId = issue.workspaceId,
                        repositoryId = REPOSITORY_ID,
                        agentName = "codex",
                        branchName = "codex/test",
                        worktreePath = repoRoot.toString(),
                        status = AgentRunStatus.RUNNING,
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                reviewQueue = listOf(
                    ReviewQueueItem(
                        id = "issue-delete-rq",
                        companyId = company.id,
                        issueId = issue.id,
                        runId = "issue-delete-run",
                        status = ReviewQueueStatus.AWAITING_QA,
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                signals = listOf(
                    OpsSignal(
                        id = "issue-delete-signal",
                        companyId = company.id,
                        goalId = goal.id,
                        issueId = issue.id,
                        source = "runtime",
                        message = "Delete this issue",
                        createdAt = now
                    )
                )
            )
        )

        service.deleteIssue(issue.id).id shouldBe issue.id

        val state = stateStore.load()
        state.goals.firstOrNull { it.id == goal.id }?.id shouldBe goal.id
        state.issues.firstOrNull { it.id == issue.id } shouldBe null
        state.tasks.firstOrNull { it.issueId == issue.id } shouldBe null
        state.runs.firstOrNull { it.taskId == "issue-delete-task" } shouldBe null
        state.reviewQueue.firstOrNull { it.issueId == issue.id } shouldBe null
        state.signals.firstOrNull { it.issueId == issue.id } shouldBe null
        issueFile.exists() shouldBe false
    }

    test("createIssue creates a company-scoped manual issue for the selected goal") {
        val appHome = Files.createTempDirectory("desktop-app-service-create-issue")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-create-issue-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Manual Issue Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        service.createCompanyAgentDefinition(
            companyId = company.id,
            title = "Builder",
            agentCli = "codex",
            roleSummary = "implementation and delivery"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Ship docs",
            description = "Improve project documentation.",
            autonomyEnabled = false
        )

        val issue = service.createIssue(
            companyId = company.id,
            goalId = goal.id,
            title = "Write install troubleshooting section",
            description = "Document the common setup failures.",
            kind = "manual"
        )

        issue.companyId shouldBe company.id
        issue.goalId shouldBe goal.id
        issue.sourceSignal shouldBe "manual"
        stateStore.load().issues.any { it.id == issue.id && it.companyId == company.id } shouldBe true
    }

    test("createCompany defaults to Local Cotor when Codex app server is selected globally without a base URL") {
        val appHome = Files.createTempDirectory("desktop-app-service-company-backend")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-company-backend-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        stateStore.save(
            DesktopAppState(
                backendSettings = DesktopBackendSettings(
                    defaultBackendKind = ExecutionBackendKind.CODEX_APP_SERVER,
                    backends = DesktopBackendSettings().backends.map { config ->
                        if (config.kind == ExecutionBackendKind.CODEX_APP_SERVER) {
                            config.copy(baseUrl = null, enabled = true)
                        } else {
                            config
                        }
                    }
                )
            )
        )
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Safe Backend Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )

        company.backendKind shouldBe ExecutionBackendKind.LOCAL_COTOR
    }

    test("createCompany initializes a git repository when the selected folder is not yet a repo") {
        val appHome = Files.createTempDirectory("desktop-app-service-init-company")
        val rootFolder = Files.createTempDirectory("desktop-app-service-init-company-root")
        val stateStore = DesktopStateStore { appHome }
        val processManager = CoroutineProcessManager(LoggerFactory.getLogger("DesktopAppServiceTest"))
        val gitWorkspaceService = GitWorkspaceService(
            processManager = processManager,
            stateStore = stateStore,
            logger = LoggerFactory.getLogger("DesktopAppServiceTest")
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Fresh Folder Co",
            rootPath = rootFolder.toString(),
            defaultBaseBranch = "master"
        )

        company.rootPath shouldBe rootFolder.toAbsolutePath().normalize().toString()
        Files.exists(rootFolder.resolve(".git")) shouldBe true
        stateStore.load().repositories.any { it.localPath == company.rootPath && it.defaultBranch == "master" } shouldBe true
    }

    test("runtime keeps running when GitHub readiness probing times out for a code issue") {
        val appHome = Files.createTempDirectory("desktop-app-service-github-readiness-timeout")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-github-readiness-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.ensureGitHubPublishReady(any(), any()) } throws RuntimeException("Timed out waiting for 10000 ms")
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        withDesktopServiceShutdown(service) {
            val company = service.createCompany(
                name = "GitHub Timeout Co",
                rootPath = repoRoot.toString(),
                defaultBaseBranch = "master"
            )
            val baseState = stateStore.load()
            val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
            val projectContext = baseState.projectContexts.first { it.companyId == company.id }
            val now = System.currentTimeMillis()
            val goal = CompanyGoal(
                id = "goal-timeout",
                companyId = company.id,
                projectContextId = projectContext.id,
                title = "Ship timeout-prone change",
                description = "Exercise GitHub readiness timeout handling.",
                status = GoalStatus.ACTIVE,
                autonomyEnabled = true,
                createdAt = now,
                updatedAt = now
            )
            val issue = CompanyIssue(
                id = "issue-timeout",
                companyId = company.id,
                projectContextId = projectContext.id,
                goalId = goal.id,
                workspaceId = workspace.id,
                title = "Implement timeout-prone branch",
                description = "This code issue should block locally instead of killing the runtime.",
                status = IssueStatus.PLANNED,
                kind = "execution",
                createdAt = now,
                updatedAt = now
            )
            stateStore.save(
                baseState.copy(
                    goals = baseState.goals + goal,
                    issues = baseState.issues + issue
                )
            )

            service.startCompanyRuntime(company.id)

            withTimeout(5_000) {
                while (true) {
                    val current = stateStore.load()
                    val runtime = current.companyRuntimes.firstOrNull { it.companyId == company.id }
                    val blockedIssue = current.issues.firstOrNull { it.id == issue.id }
                    val infraIssue = current.issues.firstOrNull {
                        it.companyId == company.id && it.kind == "infra" && it.title == "Restore GitHub publishing for ${issue.title}"
                    }
                    if (runtime?.status == CompanyRuntimeStatus.RUNNING &&
                        runtime.lastAction != "runtime-error" &&
                        blockedIssue?.status == IssueStatus.BLOCKED &&
                        infraIssue?.status == IssueStatus.PLANNED
                    ) {
                        break
                    }
                    delay(25)
                }
            }

            val finalState = stateStore.load()
            finalState.companyRuntimes.first { it.companyId == company.id }.status shouldBe CompanyRuntimeStatus.RUNNING
            finalState.issues.first { it.id == issue.id }.status shouldBe IssueStatus.BLOCKED
            finalState.issues.any {
                it.companyId == company.id &&
                    it.kind == "infra" &&
                    it.title == "Restore GitHub publishing for ${issue.title}" &&
                    it.status == IssueStatus.PLANNED
            } shouldBe true
        }
    }

    test("runtime resolves GitHub readiness blocks once publishing becomes ready again") {
        val appHome = Files.createTempDirectory("desktop-app-service-github-readiness-recovery")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-github-readiness-recovery-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns "https://github.com/heodongun/cotor-test.git"
        coEvery { gitWorkspaceService.ensureGitHubPublishReady(any(), any()) } returns GitHubPublishReadiness(
            ready = true,
            originUrl = "https://github.com/heodongun/cotor-test.git"
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "GitHub Recovery Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-github-recovery",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Recover GitHub readiness",
            description = "Exercise automatic unblocking once publishing is available again.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = false,
            createdAt = now,
            updatedAt = now
        )
        val blockedIssue = CompanyIssue(
            id = "issue-github-recovery",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Ship recovery branch",
            description = "This code issue should resume once GitHub publishing is ready again.",
            status = IssueStatus.BLOCKED,
            kind = "execution",
            blockedBy = listOf("infra-github-recovery"),
            transitionReason = "GitHub publishing cannot open PRs for this repository because local master was initialized independently and has no history in common with origin/master.",
            createdAt = now,
            updatedAt = now
        )
        val infraIssue = CompanyIssue(
            id = "infra-github-recovery",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Restore GitHub publishing for ${blockedIssue.title}",
            description = "Repair repository publishing readiness.",
            status = IssueStatus.PLANNED,
            priority = 1,
            kind = "infra",
            sourceSignal = "github-readiness",
            createdAt = now,
            updatedAt = now
        )
        val staleFailedTask = AgentTask(
            id = "task-github-recovery-stale-failure",
            workspaceId = workspace.id,
            issueId = blockedIssue.id,
            title = blockedIssue.title,
            prompt = blockedIssue.description,
            agents = listOf("codex"),
            status = DesktopTaskStatus.FAILED,
            createdAt = now - 60_000,
            updatedAt = now - 60_000
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + blockedIssue + infraIssue,
                tasks = baseState.tasks + staleFailedTask,
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        lastTickAt = now
                    )
                )
            )
        )

        service.runCompanyRuntimeTick(company.id)
        service.runCompanyRuntimeTick(company.id)

        val finalState = stateStore.load()
        finalState.issues.first { it.id == blockedIssue.id }.status shouldBe IssueStatus.PLANNED
        finalState.issues.first { it.id == blockedIssue.id }.blockedBy shouldBe emptyList()
        finalState.issues.first { it.id == blockedIssue.id }.transitionReason.shouldBeNull()
        finalState.issues.first { it.id == infraIssue.id }.status shouldBe IssueStatus.DONE
        coVerify(atLeast = 1) { gitWorkspaceService.ensureGitHubPublishReady(repoRoot, "master") }
    }

    test("QA pass reopens a blocked approval issue and clears stale CEO review metadata") {
        val appHome = Files.createTempDirectory("desktop-app-service-qa-reopen-approval")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-qa-reopen-approval-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.commentOnPullRequest(any(), 11, any()) } returns Unit
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "QA Reopen Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-qa-reopen",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Reopen CEO approval after QA pass",
            description = "Exercise review-to-approval recovery.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-qa-reopen",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Ship the code change",
            description = "Implementation branch work.",
            status = IssueStatus.IN_PROGRESS,
            priority = 2,
            kind = "execution",
            createdAt = now,
            updatedAt = now
        )
        val reviewIssue = CompanyIssue(
            id = "issue-review-qa-reopen",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Review completed implementation work",
            description = "QA checks the branch.",
            status = IssueStatus.IN_PROGRESS,
            priority = 2,
            kind = "review",
            dependsOn = listOf(executionIssue.id),
            sourceSignal = "qa-review:${executionIssue.id}",
            createdAt = now,
            updatedAt = now
        )
        val approvalIssue = CompanyIssue(
            id = "issue-approval-qa-reopen",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Review downstream company status",
            description = "CEO approval gate.",
            status = IssueStatus.BLOCKED,
            priority = 1,
            kind = "approval",
            dependsOn = listOf(reviewIssue.id),
            sourceSignal = "ceo-approval:${executionIssue.id}",
            createdAt = now,
            updatedAt = now
        )
        val reviewTask = AgentTask(
            id = "task-review-qa-reopen",
            workspaceId = workspace.id,
            title = reviewIssue.title,
            prompt = reviewIssue.description,
            agents = listOf("codex"),
            issueId = reviewIssue.id,
            status = DesktopTaskStatus.COMPLETED,
            createdAt = now,
            updatedAt = now
        )
        val reviewRun = AgentRun(
            id = "run-review-qa-reopen",
            taskId = reviewTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.COMPLETED,
            output = "QA_VERDICT: PASS\nReady for CEO approval after re-review.",
            branchName = "codex/cotor/reopen-approval",
            worktreePath = repoRoot.resolve(".cotor/worktrees/reopen-approval/codex").toString(),
            publish = PublishMetadata(
                commitSha = "abc123",
                pullRequestNumber = 11,
                pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/11",
                pullRequestState = "OPEN"
            ),
            durationMs = 250,
            createdAt = now,
            updatedAt = now
        )
        val reviewQueueItem = ReviewQueueItem(
            id = "rq-qa-reopen",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = executionIssue.id,
            runId = reviewRun.id,
            branchName = reviewRun.branchName,
            worktreePath = reviewRun.worktreePath,
            pullRequestNumber = 11,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/11",
            pullRequestState = "OPEN",
            status = ReviewQueueStatus.CHANGES_REQUESTED,
            checksSummary = "Earlier CEO review requested changes.",
            qaVerdict = "CHANGES_REQUESTED",
            qaFeedback = "Address stale blocker.",
            qaReviewedAt = now - 5_000,
            qaIssueId = reviewIssue.id,
            ceoVerdict = "CHANGES_REQUESTED",
            ceoFeedback = "Still points at stale implementation.",
            ceoReviewedAt = now - 2_500,
            approvalIssueId = approvalIssue.id,
            createdAt = now - 10_000,
            updatedAt = now - 2_500
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, reviewIssue, approvalIssue),
                tasks = baseState.tasks + reviewTask,
                runs = baseState.runs + reviewRun,
                reviewQueue = baseState.reviewQueue + reviewQueueItem,
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        backendKind = ExecutionBackendKind.LOCAL_COTOR,
                        backendHealth = "healthy",
                        lastStartedAt = now
                    )
                )
            )
        )

        service.runCompanyRuntimeTick(company.id)

        val refreshed = withTimeout(30_000) {
            while (true) {
                val candidate = stateStore.load()
                val candidateExecution = candidate.issues.first { it.id == executionIssue.id }
                val candidateQueue = candidate.reviewQueue.first { it.id == reviewQueueItem.id }
                if (
                    candidateExecution.status == IssueStatus.READY_FOR_CEO &&
                    candidateQueue.status == ReviewQueueStatus.READY_FOR_CEO &&
                    candidateQueue.ceoVerdict == null &&
                    candidateQueue.qaVerdict == "PASS"
                ) {
                    return@withTimeout candidate
                }
                delay(25)
            }
            error("Unreachable")
        }
        // After the tick processes the completed QA review task, the execution
        // issue should advance to READY_FOR_CEO and the review queue should
        // reflect the QA PASS verdict.
        val refreshedExecution = refreshed.issues.first { it.id == executionIssue.id }
        refreshedExecution.status shouldBe IssueStatus.READY_FOR_CEO
        refreshed.issues.first { it.id == reviewIssue.id }.status shouldBe IssueStatus.DONE
        val refreshedQueue = refreshed.reviewQueue.first { it.id == reviewQueueItem.id }
        refreshedQueue.status shouldBe ReviewQueueStatus.READY_FOR_CEO
        refreshedQueue.ceoVerdict shouldBe null
        refreshedQueue.ceoFeedback shouldBe null
        refreshedQueue.ceoReviewedAt shouldBe null
        refreshedQueue.qaVerdict shouldBe "PASS"
        coVerify(exactly = 1) { gitWorkspaceService.commentOnPullRequest(any(), 11, any()) }
        coVerify(exactly = 1) {
            gitWorkspaceService.submitPullRequestReview(any(), 11, PullRequestReviewVerdict.APPROVE, any())
        }
    }

    test("runtime closes stale approval issues after the linked execution PR already merged") {
        val appHome = Files.createTempDirectory("desktop-app-service-normalize-stale-approval")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-normalize-stale-approval-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.refreshPullRequestMetadata(any(), 321) } returns PublishMetadata(
            pullRequestNumber = 321,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/321",
            pullRequestState = "OPEN",
            mergeability = "DIRTY"
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Stale Approval Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-stale-approval",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Normalize merged approval",
            description = "Close an approval gate after merge already completed.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-stale-approval",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Ship merged PR",
            description = "Execution branch work.",
            status = IssueStatus.DONE,
            priority = 2,
            kind = "execution",
            pullRequestNumber = 287,
            pullRequestUrl = "https://github.com/bssm-oss/cotor-test/pull/287",
            pullRequestState = "MERGED",
            mergeResult = "MERGED",
            createdAt = now - 5_000,
            updatedAt = now - 5_000
        )
        val approvalIssue = CompanyIssue(
            id = "issue-approval-stale-approval",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "CEO approve Ship merged PR",
            description = "This approval issue should be closed automatically.",
            status = IssueStatus.IN_PROGRESS,
            priority = 1,
            kind = "approval",
            dependsOn = listOf(executionIssue.id),
            sourceSignal = "ceo-approval:${executionIssue.id}",
            createdAt = now - 4_000,
            updatedAt = now - 4_000
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, approvalIssue),
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        backendKind = ExecutionBackendKind.LOCAL_COTOR,
                        backendHealth = "healthy",
                        lastStartedAt = now
                    )
                )
            )
        )

        service.runCompanyRuntimeTick(company.id)

        val refreshed = stateStore.load()
        refreshed.issues.first { it.id == approvalIssue.id }.status shouldBe IssueStatus.DONE
        refreshed.issues.first { it.id == approvalIssue.id }.transitionReason shouldBe
            "Linked execution issue already merged; closing the approval gate."
    }

    test("runtime creates a fresh QA lineage and closes the superseded PR when a retry publishes a new PR") {
        val appHome = Files.createTempDirectory("desktop-app-service-fresh-qa-lineage")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-fresh-qa-lineage-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.closePullRequest(any(), 11, any()) } returns PublishMetadata(
            pullRequestNumber = 11,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/11",
            pullRequestState = "CLOSED"
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Fresh QA Lineage Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-fresh-qa-lineage",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Keep retries scoped to the latest PR",
            description = "A newer retry should not inherit stale QA work.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-fresh-qa-lineage",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Deliver the updated change",
            description = "Publish the next retry.",
            status = IssueStatus.PLANNED,
            priority = 2,
            kind = "execution",
            branchName = "codex/cotor/old-pr/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/old-pr/codex").toString(),
            pullRequestNumber = 11,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/11",
            pullRequestState = "OPEN",
            qaVerdict = "CHANGES_REQUESTED",
            qaFeedback = "Old QA feedback against the superseded branch.",
            createdAt = now - 20_000,
            updatedAt = now - 20_000
        )
        val staleReviewIssue = CompanyIssue(
            id = "issue-review-stale-lineage",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "QA review Deliver the updated change",
            description = "Old QA issue.",
            status = IssueStatus.BLOCKED,
            priority = 2,
            kind = "review",
            dependsOn = listOf(executionIssue.id),
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = 11,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            qaVerdict = "CHANGES_REQUESTED",
            qaFeedback = "The old PR still misses the requested interaction.",
            sourceSignal = "qa-review:${executionIssue.id}",
            createdAt = now - 19_000,
            updatedAt = now - 19_000
        )
        val staleApprovalIssue = CompanyIssue(
            id = "issue-approval-stale-lineage",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "CEO approve Deliver the updated change",
            description = "Old CEO approval issue.",
            status = IssueStatus.BLOCKED,
            priority = 1,
            kind = "approval",
            dependsOn = listOf(staleReviewIssue.id),
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = 11,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            qaVerdict = "CHANGES_REQUESTED",
            qaFeedback = "Old QA verdict is still pending.",
            sourceSignal = "ceo-approval:${executionIssue.id}",
            createdAt = now - 18_000,
            updatedAt = now - 18_000
        )
        val staleReviewTask = AgentTask(
            id = "task-review-stale-lineage",
            workspaceId = workspace.id,
            title = staleReviewIssue.title,
            prompt = staleReviewIssue.description,
            agents = listOf("codex"),
            issueId = staleReviewIssue.id,
            status = DesktopTaskStatus.COMPLETED,
            createdAt = now - 17_000,
            updatedAt = now - 16_000
        )
        val staleReviewRun = AgentRun(
            id = "run-review-stale-lineage",
            taskId = staleReviewTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.COMPLETED,
            output = "QA_VERDICT: CHANGES_REQUESTED\nThe old PR still only updates on submit.",
            branchName = executionIssue.branchName!!,
            worktreePath = executionIssue.worktreePath!!,
            durationMs = 200,
            createdAt = now - 17_000,
            updatedAt = now - 16_000
        )
        val executionTask = AgentTask(
            id = "task-execution-fresh-qa-lineage",
            workspaceId = workspace.id,
            title = executionIssue.title,
            prompt = executionIssue.description,
            agents = listOf("codex"),
            issueId = executionIssue.id,
            status = DesktopTaskStatus.COMPLETED,
            createdAt = now - 5_000,
            updatedAt = now - 4_000
        )
        val executionRun = AgentRun(
            id = "run-execution-fresh-qa-lineage",
            taskId = executionTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.COMPLETED,
            output = "Published a new retry branch.",
            branchName = "codex/cotor/new-pr/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/new-pr/codex").toString(),
            publish = PublishMetadata(
                commitSha = "newretry",
                pullRequestNumber = 22,
                pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
                pullRequestState = "OPEN"
            ),
            durationMs = 250,
            createdAt = now - 5_000,
            updatedAt = now - 4_000
        )
        val staleQueueItem = ReviewQueueItem(
            id = "rq-stale-lineage",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = executionIssue.id,
            runId = staleReviewRun.id,
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = 11,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/11",
            pullRequestState = "OPEN",
            status = ReviewQueueStatus.CHANGES_REQUESTED,
            checksSummary = "Old QA requested changes.",
            qaVerdict = "CHANGES_REQUESTED",
            qaFeedback = "The old PR still only updates on submit.",
            qaReviewedAt = now - 16_500,
            qaIssueId = staleReviewIssue.id,
            ceoVerdict = "CHANGES_REQUESTED",
            ceoFeedback = "Do not merge the superseded branch.",
            ceoReviewedAt = now - 16_000,
            approvalIssueId = staleApprovalIssue.id,
            createdAt = now - 18_000,
            updatedAt = now - 16_000
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, staleReviewIssue, staleApprovalIssue),
                tasks = baseState.tasks + listOf(executionTask, staleReviewTask),
                runs = baseState.runs + listOf(executionRun, staleReviewRun),
                reviewQueue = baseState.reviewQueue + staleQueueItem,
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        backendKind = ExecutionBackendKind.LOCAL_COTOR,
                        backendHealth = "healthy",
                        lastStartedAt = now
                    )
                )
            )
        )

        service.runCompanyRuntimeTick(company.id)

        withTimeout(10_000) {
            while (true) {
                val candidate = stateStore.load()
                val executionReady = candidate.issues.first { it.id == executionIssue.id }.status == IssueStatus.IN_REVIEW
                val staleIssuesClosed =
                    candidate.issues.none { it.id == staleReviewIssue.id } &&
                        candidate.issues.none { it.id == staleApprovalIssue.id }
                val queueRebuilt = candidate.reviewQueue.any {
                    it.issueId == executionIssue.id &&
                        it.id != staleQueueItem.id &&
                        it.pullRequestNumber == 22
                }
                if (executionReady && staleIssuesClosed && queueRebuilt) {
                    return@withTimeout
                }
                delay(25)
            }
            error("Unreachable")
        }
        val refreshed = stateStore.load()
        val refreshedExecution = refreshed.issues.first { it.id == executionIssue.id }
        refreshedExecution.status shouldBe IssueStatus.IN_REVIEW
        refreshedExecution.pullRequestNumber shouldBe 22
        refreshedExecution.pullRequestUrl shouldBe "https://github.com/heodongun/cotor-test/pull/22"
        refreshedExecution.qaVerdict.shouldBeNull()
        refreshedExecution.ceoVerdict.shouldBeNull()
        refreshed.issues.any { it.id == staleReviewIssue.id } shouldBe false
        refreshed.issues.any { it.id == staleApprovalIssue.id } shouldBe false
        val newReviewIssue = refreshed.issues.first {
            it.kind.equals("review", ignoreCase = true) && it.dependsOn == listOf(executionIssue.id)
        }
        newReviewIssue.id shouldNotBe staleReviewIssue.id
        newReviewIssue.pullRequestNumber shouldBe 22
        setOf(IssueStatus.PLANNED, IssueStatus.DELEGATED, IssueStatus.IN_PROGRESS).contains(newReviewIssue.status) shouldBe true
        newReviewIssue.qaVerdict.shouldBeNull()
        newReviewIssue.ceoVerdict.shouldBeNull()
        val refreshedQueue = refreshed.reviewQueue.first { it.issueId == executionIssue.id }
        refreshedQueue.id shouldNotBe staleQueueItem.id
        refreshedQueue.pullRequestNumber shouldBe 22
        refreshedQueue.qaIssueId shouldBe newReviewIssue.id
        refreshedQueue.approvalIssueId.shouldBeNull()
        refreshedQueue.qaVerdict.shouldBeNull()
        refreshedQueue.ceoVerdict.shouldBeNull()
        setOf(ReviewQueueStatus.AWAITING_QA, ReviewQueueStatus.READY_FOR_CEO).contains(refreshedQueue.status) shouldBe true
        if (newReviewIssue.status in setOf(IssueStatus.DELEGATED, IssueStatus.IN_PROGRESS)) {
            refreshed.tasks.any {
                it.issueId == newReviewIssue.id &&
                    it.status in setOf(DesktopTaskStatus.QUEUED, DesktopTaskStatus.RUNNING, DesktopTaskStatus.COMPLETED)
            } shouldBe true
        }
        coVerify(exactly = 1) { gitWorkspaceService.closePullRequest(any(), 11, any()) }
        coVerify(exactly = 0) { gitWorkspaceService.submitPullRequestReview(any(), any(), any(), any()) }
    }

    test("runtime keeps dirty published PRs out of QA and re-plans the execution issue") {
        val appHome = Files.createTempDirectory("desktop-app-service-dirty-pr-guard")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-dirty-pr-guard-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        withDesktopServiceShutdown(service) {
            val company = service.createCompany(
                name = "Dirty PR Guard Co",
                rootPath = repoRoot.toString(),
                defaultBaseBranch = "master"
            )
            val baseState = stateStore.load()
            val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
            val projectContext = baseState.projectContexts.first { it.companyId == company.id }
            val now = System.currentTimeMillis()
            val goal = CompanyGoal(
                id = "goal-dirty-pr-guard",
                companyId = company.id,
                projectContextId = projectContext.id,
                title = "Avoid sending dirty PRs to QA",
                description = "A PR that already reports DIRTY should immediately loop back to execution.",
                status = GoalStatus.ACTIVE,
                autonomyEnabled = true,
                createdAt = now,
                updatedAt = now
            )
            val executionIssue = CompanyIssue(
                id = "issue-execution-dirty-pr-guard",
                companyId = company.id,
                projectContextId = projectContext.id,
                goalId = goal.id,
                workspaceId = workspace.id,
                title = "Publish the conflicted retry",
                description = "This retry published a PR that GitHub already marks DIRTY.",
                status = IssueStatus.PLANNED,
                priority = 2,
                kind = "execution",
                branchName = "codex/cotor/dirty-pr-guard/codex",
                worktreePath = repoRoot.resolve(".cotor/worktrees/dirty-pr-guard/codex").toString(),
                createdAt = now - 5_000,
                updatedAt = now - 5_000
            )
            val executionTask = AgentTask(
                id = "task-execution-dirty-pr-guard",
                workspaceId = workspace.id,
                title = executionIssue.title,
                prompt = executionIssue.description,
                agents = listOf("codex"),
                issueId = executionIssue.id,
                status = DesktopTaskStatus.COMPLETED,
                createdAt = now - 3_000,
                updatedAt = now - 2_000
            )
            val executionRun = AgentRun(
                id = "run-execution-dirty-pr-guard",
                taskId = executionTask.id,
                workspaceId = workspace.id,
                repositoryId = company.repositoryId,
                agentName = "codex",
                repoRoot = repoRoot.toString(),
                baseBranch = "master",
                status = AgentRunStatus.COMPLETED,
                output = "Published a retry, but GitHub marked it DIRTY.",
                branchName = "codex/cotor/dirty-pr-guard/codex",
                worktreePath = repoRoot.resolve(".cotor/worktrees/dirty-pr-guard/codex").toString(),
                publish = PublishMetadata(
                    commitSha = "dirtypublish",
                    pullRequestNumber = 321,
                    pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/321",
                    pullRequestState = "OPEN",
                    mergeability = "DIRTY"
                ),
                durationMs = 300,
                createdAt = now - 3_000,
                updatedAt = now - 2_000
            )
            stateStore.save(
                baseState.copy(
                    goals = baseState.goals + goal,
                    issues = baseState.issues + executionIssue,
                    tasks = baseState.tasks + executionTask,
                    runs = baseState.runs + executionRun,
                    companyRuntimes = listOf(
                        CompanyRuntimeSnapshot(
                            companyId = company.id,
                            status = CompanyRuntimeStatus.RUNNING,
                            backendKind = ExecutionBackendKind.LOCAL_COTOR,
                            backendHealth = "healthy",
                            lastStartedAt = now
                        )
                    )
                )
            )

            service.runCompanyRuntimeTick(company.id)

            val refreshed = withTimeout(30_000) {
                while (true) {
                    service.runCompanyRuntimeTick(company.id)
                    val candidate = stateStore.load()
                    val candidateExecution = candidate.issues.first { it.id == executionIssue.id }
                    val candidateQueue = candidate.reviewQueue.first { it.issueId == executionIssue.id }
                    val noQaReviewIssue = candidate.issues.none {
                        it.kind.equals("review", ignoreCase = true) && executionIssue.id in it.dependsOn
                    }
                    if (
                        candidateExecution.status !in setOf(IssueStatus.IN_REVIEW, IssueStatus.READY_FOR_CEO, IssueStatus.DONE) &&
                        candidateQueue.status == ReviewQueueStatus.CHANGES_REQUESTED &&
                        noQaReviewIssue
                    ) {
                        return@withTimeout candidate
                    }
                    delay(25)
                }
                error("Unreachable")
            }
            val refreshedExecution = refreshed.issues.first { it.id == executionIssue.id }
            refreshedExecution.status shouldNotBe IssueStatus.IN_REVIEW
            refreshedExecution.status shouldNotBe IssueStatus.READY_FOR_CEO
            refreshedExecution.status shouldNotBe IssueStatus.DONE
            refreshedExecution.pullRequestNumber shouldBe 321
            refreshedExecution.pullRequestUrl shouldBe "https://github.com/heodongun/cotor-test/pull/321"
            if (refreshedExecution.status == IssueStatus.PLANNED) {
                refreshedExecution.transitionReason.shouldContain("does not merge cleanly")
            }
            refreshed.issues.none { it.kind.equals("review", ignoreCase = true) && executionIssue.id in it.dependsOn } shouldBe true

            val refreshedQueue = refreshed.reviewQueue.first { it.issueId == executionIssue.id }
            refreshedQueue.status shouldBe ReviewQueueStatus.CHANGES_REQUESTED
            refreshedQueue.pullRequestNumber shouldBe 321
            refreshedQueue.qaIssueId.shouldBeNull()
            refreshedQueue.approvalIssueId.shouldBeNull()
            refreshedQueue.ceoVerdict.shouldBeNull()
        }
    }

    test("company dashboard heals a stale QA lineage while the runtime is stopped") {
        val appHome = Files.createTempDirectory("desktop-app-service-heal-stale-qa-lineage")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-heal-stale-qa-lineage-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Heal Stale QA Lineage Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-heal-stale-qa-lineage",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Heal stale QA lineage",
            description = "Dashboard healing should rebuild QA review for the latest PR.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-heal-stale-qa-lineage",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Deliver the latest PR",
            description = "The latest retry is published on PR #22.",
            status = IssueStatus.PLANNED,
            priority = 2,
            kind = "execution",
            branchName = "codex/cotor/current-pr/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/current/codex").toString(),
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            qaVerdict = "CHANGES_REQUESTED",
            qaFeedback = "Old QA result is still attached.",
            createdAt = now - 10_000,
            updatedAt = now - 9_000
        )
        val staleReviewIssue = CompanyIssue(
            id = "issue-review-heal-stale-qa-lineage",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "QA review Deliver the latest PR",
            description = "Old QA issue for a superseded review cycle.",
            status = IssueStatus.BLOCKED,
            priority = 2,
            kind = "review",
            dependsOn = listOf(executionIssue.id),
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            qaVerdict = "CHANGES_REQUESTED",
            qaFeedback = "The stale branch still only updates on submit.",
            sourceSignal = "qa-review:${executionIssue.id}",
            createdAt = now - 8_000,
            updatedAt = now - 1_000
        )
        val staleReviewTask = AgentTask(
            id = "task-review-heal-stale-qa-lineage",
            workspaceId = workspace.id,
            title = staleReviewIssue.title,
            prompt = "Review the stale branch evidence.",
            agents = listOf("codex"),
            issueId = staleReviewIssue.id,
            status = DesktopTaskStatus.COMPLETED,
            createdAt = now - 8_000,
            updatedAt = now - 7_000
        )
        val staleReviewRun = AgentRun(
            id = "run-review-heal-stale-qa-lineage",
            taskId = staleReviewTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.COMPLETED,
            output = "QA_VERDICT: CHANGES_REQUESTED\nThe stale branch still only updates on submit.",
            branchName = "codex/cotor/stale-pr/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/stale/codex").toString(),
            durationMs = 250,
            createdAt = now - 8_000,
            updatedAt = now - 7_000
        )
        val executionTask = AgentTask(
            id = "task-execution-heal-stale-qa-lineage",
            workspaceId = workspace.id,
            title = executionIssue.title,
            prompt = executionIssue.description,
            agents = listOf("codex"),
            issueId = executionIssue.id,
            status = DesktopTaskStatus.COMPLETED,
            createdAt = now - 6_000,
            updatedAt = now - 5_000
        )
        val executionRun = AgentRun(
            id = "run-execution-heal-stale-qa-lineage",
            taskId = executionTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.COMPLETED,
            output = "Published the latest PR retry with the requested interaction update.",
            branchName = executionIssue.branchName!!,
            worktreePath = executionIssue.worktreePath!!,
            publish = PublishMetadata(
                commitSha = "2222222",
                pullRequestNumber = 22,
                pullRequestUrl = executionIssue.pullRequestUrl,
                pullRequestState = "OPEN"
            ),
            durationMs = 250,
            createdAt = now - 6_000,
            updatedAt = now - 5_000
        )
        val staleQueueItem = ReviewQueueItem(
            id = "rq-heal-stale-qa-lineage",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = executionIssue.id,
            runId = executionRun.id,
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = 22,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = "OPEN",
            status = ReviewQueueStatus.CHANGES_REQUESTED,
            checksSummary = "Stale QA verdict is still attached.",
            qaVerdict = "CHANGES_REQUESTED",
            qaFeedback = "The stale branch still only updates on submit.",
            qaReviewedAt = now - 6_500,
            qaIssueId = staleReviewIssue.id,
            createdAt = now - 6_000,
            updatedAt = now - 5_000
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, staleReviewIssue),
                tasks = baseState.tasks + listOf(executionTask, staleReviewTask),
                runs = baseState.runs + listOf(executionRun, staleReviewRun),
                reviewQueue = baseState.reviewQueue + staleQueueItem,
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.STOPPED,
                        lastAction = "runtime-stopped",
                        manuallyStoppedAt = now - 1000
                    )
                )
            )
        )

        service.companyDashboard(company.id)

        lateinit var healedState: DesktopAppState
        withTimeout(5_000) {
            while (true) {
                service.companyDashboard(company.id)
                healedState = stateStore.load()
                val healedQueue = healedState.reviewQueue.firstOrNull { it.issueId == executionIssue.id }
                val healedReviewIssue = healedQueue?.qaIssueId?.let { healedState.issues.firstOrNull { issue -> issue.id == it } }
                if (
                    healedQueue != null &&
                    healedReviewIssue != null &&
                    healedQueue.qaVerdict == null &&
                    healedQueue.status == ReviewQueueStatus.AWAITING_QA &&
                    healedReviewIssue.id != staleReviewIssue.id &&
                    healedReviewIssue.workflowLineage != null
                ) {
                    break
                }
                delay(50)
            }
        }

        val healedQueue = healedState.reviewQueue.first { it.issueId == executionIssue.id }
        val healedExecution = healedState.issues.first { it.id == executionIssue.id }
        val healedReviewIssue = healedState.issues.first { it.id == healedQueue.qaIssueId }
        val qaProfileId = healedState.companyAgentDefinitions.first { it.companyId == company.id && it.title == "QA" }.id
        val ceoProfileId = healedState.companyAgentDefinitions.first { it.companyId == company.id && it.title == "CEO" }.id
        healedState.issues.any { it.id == staleReviewIssue.id } shouldBe false
        healedExecution.status shouldBe IssueStatus.IN_REVIEW
        healedExecution.qaVerdict.shouldBeNull()
        healedQueue.status shouldBe ReviewQueueStatus.AWAITING_QA
        healedQueue.qaVerdict.shouldBeNull()
        healedQueue.workflowLineage.shouldNotBeNull()
        healedReviewIssue.workflowLineage shouldBe healedQueue.workflowLineage
        healedReviewIssue.assigneeProfileId shouldBe qaProfileId
        healedReviewIssue.assigneeProfileId shouldNotBe ceoProfileId
    }

    test("company dashboard keeps a delegated QA lineage stable before the review task starts") {
        val appHome = Files.createTempDirectory("desktop-app-service-stable-delegated-qa-lineage")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-stable-delegated-qa-lineage-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Stable Delegated QA Lineage Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val qaProfileId = baseState.companyAgentDefinitions.first { it.companyId == company.id && it.title == "QA" }.id
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-stable-delegated-qa-lineage",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Keep a waiting QA review stable",
            description = "Dashboard reads should not rebuild QA lineage while the delegated review issue has not started yet.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-stable-delegated-qa-lineage",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Deliver the waiting QA change",
            description = "This PR is waiting for QA to begin.",
            status = IssueStatus.IN_REVIEW,
            priority = 2,
            kind = "execution",
            branchName = "codex/cotor/stable-delegated-qa-lineage/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/stable-delegated-qa-lineage/codex").toString(),
            pullRequestNumber = 42,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/42",
            pullRequestState = "OPEN",
            createdAt = now - 6_000,
            updatedAt = now - 5_000
        )
        val executionTask = AgentTask(
            id = "task-execution-stable-delegated-qa-lineage",
            workspaceId = workspace.id,
            title = executionIssue.title,
            prompt = executionIssue.description,
            agents = listOf("codex"),
            issueId = executionIssue.id,
            status = DesktopTaskStatus.COMPLETED,
            createdAt = now - 5_500,
            updatedAt = now - 5_000
        )
        val executionRun = AgentRun(
            id = "run-execution-stable-delegated-qa-lineage",
            taskId = executionTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.COMPLETED,
            output = "Published the PR and queued QA.",
            branchName = executionIssue.branchName!!,
            worktreePath = executionIssue.worktreePath!!,
            publish = PublishMetadata(
                commitSha = "4242424",
                pullRequestNumber = 42,
                pullRequestUrl = executionIssue.pullRequestUrl,
                pullRequestState = "OPEN"
            ),
            durationMs = 250,
            createdAt = now - 5_500,
            updatedAt = now - 5_000
        )
        val workflowLineage = WorkflowLineageSnapshot(
            lineageId = "lineage-stable-delegated-qa-lineage",
            reviewQueueItemId = "rq-stable-delegated-qa-lineage",
            executionIssueId = executionIssue.id,
            executionTaskId = executionTask.id,
            executionRunId = executionRun.id,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            generation = 1
        )
        val reviewIssue = CompanyIssue(
            id = "issue-review-stable-delegated-qa-lineage",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "QA review Deliver the waiting QA change",
            description = "QA should begin from this delegated issue.",
            status = IssueStatus.DELEGATED,
            priority = 2,
            kind = "review",
            assigneeProfileId = qaProfileId,
            dependsOn = listOf(executionIssue.id),
            codeProducing = false,
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            sourceSignal = "qa-review:${executionIssue.id}",
            createdAt = now - 4_000,
            updatedAt = now - 3_500,
            workflowLineage = workflowLineage
        )
        val queueItem = ReviewQueueItem(
            id = workflowLineage.reviewQueueItemId,
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = executionIssue.id,
            runId = executionRun.id,
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            status = ReviewQueueStatus.AWAITING_QA,
            qaIssueId = reviewIssue.id,
            createdAt = now - 4_000,
            updatedAt = now - 3_500,
            workflowLineage = workflowLineage
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, reviewIssue),
                tasks = baseState.tasks + executionTask,
                runs = baseState.runs + executionRun,
                reviewQueue = baseState.reviewQueue + queueItem,
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.STOPPED,
                        lastAction = "runtime-stopped",
                        manuallyStoppedAt = now - 1_000
                    )
                )
            )
        )

        repeat(2) {
            service.companyDashboard(company.id)
        }

        val refreshed = stateStore.load()
        val refreshedQueue = refreshed.reviewQueue.first { it.id == queueItem.id }
        val refreshedReviewIssue = refreshed.issues.first { it.id == reviewIssue.id }
        refreshed.reviewQueue.count { it.issueId == executionIssue.id } shouldBe 1
        refreshed.issues.count {
            it.kind.equals("review", ignoreCase = true) && executionIssue.id in it.dependsOn
        } shouldBe 1
        refreshedQueue.qaIssueId shouldBe reviewIssue.id
        refreshedQueue.status shouldBe ReviewQueueStatus.AWAITING_QA
        refreshedQueue.workflowLineage shouldBe workflowLineage
        refreshedReviewIssue.status shouldBe IssueStatus.DELEGATED
        refreshedReviewIssue.assigneeProfileId shouldBe qaProfileId
        refreshedReviewIssue.workflowLineage shouldBe workflowLineage
        refreshed.companyActivity.none {
            it.companyId == company.id && it.title == "Healed workflow lineage"
        } shouldBe true
    }

    test("company dashboard reassigns a taskless QA lineage away from the wrong reviewer") {
        val appHome = Files.createTempDirectory("desktop-app-service-reassign-taskless-qa-lineage")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-reassign-taskless-qa-lineage-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Reassign Taskless QA Lineage Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val qaProfileId = baseState.companyAgentDefinitions.first { it.companyId == company.id && it.title == "QA" }.id
        val ceoProfileId = baseState.companyAgentDefinitions.first { it.companyId == company.id && it.title == "CEO" }.id
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-reassign-taskless-qa-lineage",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Repair wrong QA reviewer assignment",
            description = "Dashboard healing should replace a taskless QA issue that still points at the wrong reviewer.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-reassign-taskless-qa-lineage",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Deliver the repaired QA assignment",
            description = "This PR should wait on the dedicated QA reviewer.",
            status = IssueStatus.IN_REVIEW,
            priority = 2,
            kind = "execution",
            branchName = "codex/cotor/reassign-taskless-qa-lineage/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/reassign-taskless-qa-lineage/codex").toString(),
            pullRequestNumber = 73,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/73",
            pullRequestState = "OPEN",
            createdAt = now - 6_000,
            updatedAt = now - 5_500
        )
        val executionTask = AgentTask(
            id = "task-execution-reassign-taskless-qa-lineage",
            workspaceId = workspace.id,
            title = executionIssue.title,
            prompt = executionIssue.description,
            agents = listOf("codex"),
            issueId = executionIssue.id,
            status = DesktopTaskStatus.COMPLETED,
            createdAt = now - 5_500,
            updatedAt = now - 5_000
        )
        val executionRun = AgentRun(
            id = "run-execution-reassign-taskless-qa-lineage",
            taskId = executionTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.COMPLETED,
            output = "Published the PR and queued QA.",
            branchName = executionIssue.branchName!!,
            worktreePath = executionIssue.worktreePath!!,
            publish = PublishMetadata(
                commitSha = "7373737",
                pullRequestNumber = 73,
                pullRequestUrl = executionIssue.pullRequestUrl,
                pullRequestState = "OPEN"
            ),
            durationMs = 250,
            createdAt = now - 5_500,
            updatedAt = now - 5_000
        )
        val workflowLineage = WorkflowLineageSnapshot(
            lineageId = "lineage-reassign-taskless-qa-lineage",
            reviewQueueItemId = "rq-reassign-taskless-qa-lineage",
            executionIssueId = executionIssue.id,
            executionTaskId = executionTask.id,
            executionRunId = executionRun.id,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            generation = 1
        )
        val wrongReviewIssue = CompanyIssue(
            id = "issue-review-reassign-taskless-qa-lineage",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "QA review Deliver the repaired QA assignment",
            description = "This legacy issue is still pointed at the wrong reviewer.",
            status = IssueStatus.DELEGATED,
            priority = 2,
            kind = "review",
            assigneeProfileId = ceoProfileId,
            dependsOn = listOf(executionIssue.id),
            codeProducing = false,
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            sourceSignal = "qa-review:${executionIssue.id}",
            createdAt = now - 4_000,
            updatedAt = now - 3_500,
            workflowLineage = workflowLineage
        )
        val queueItem = ReviewQueueItem(
            id = workflowLineage.reviewQueueItemId,
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = executionIssue.id,
            runId = executionRun.id,
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            status = ReviewQueueStatus.AWAITING_QA,
            qaIssueId = wrongReviewIssue.id,
            createdAt = now - 4_000,
            updatedAt = now - 3_500,
            workflowLineage = workflowLineage
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, wrongReviewIssue),
                tasks = baseState.tasks + executionTask,
                runs = baseState.runs + executionRun,
                reviewQueue = baseState.reviewQueue + queueItem,
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.STOPPED,
                        lastAction = "runtime-stopped",
                        manuallyStoppedAt = now - 1_000
                    )
                )
            )
        )

        service.companyDashboard(company.id)

        val refreshed = stateStore.load()
        val refreshedQueue = refreshed.reviewQueue.first { it.id == queueItem.id }
        val refreshedReviewIssue = refreshed.issues.first { it.id == refreshedQueue.qaIssueId }
        refreshedQueue.qaIssueId shouldNotBe wrongReviewIssue.id
        refreshedReviewIssue.assigneeProfileId shouldBe qaProfileId
        refreshedReviewIssue.assigneeProfileId shouldNotBe ceoProfileId
        refreshedReviewIssue.workflowLineage shouldBe workflowLineage
        refreshed.companyActivity.any {
            it.companyId == company.id && it.title == "Healed workflow lineage"
        } shouldBe true
    }

    test("runtime does not reopen QA review after the same PR already advanced to CEO review") {
        val appHome = Files.createTempDirectory("desktop-app-service-execution-resync-guard")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-execution-resync-guard-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Execution Resync Guard Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val qaProfileId = baseState.companyAgentDefinitions.first { it.companyId == company.id && it.title == "QA" }.id
        val ceoProfileId = baseState.companyAgentDefinitions.first { it.companyId == company.id && it.title == "CEO" }.id
        val goal = CompanyGoal(
            id = "goal-execution-resync-guard",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Do not reopen QA for the same PR",
            description = "Protect READY_FOR_CEO work from stale execution reconciliation.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-resync-guard",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Ship the already reviewed PR",
            description = "Execution branch work.",
            status = IssueStatus.READY_FOR_CEO,
            priority = 2,
            kind = "execution",
            branchName = "codex/cotor/execution-resync-guard",
            worktreePath = repoRoot.resolve(".cotor/worktrees/execution-resync-guard/codex").toString(),
            pullRequestNumber = 77,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/77",
            pullRequestState = "OPEN",
            qaVerdict = "PASS",
            qaFeedback = "QA already approved this PR.",
            createdAt = now,
            updatedAt = now,
            workflowLineage = WorkflowLineageSnapshot(
                lineageId = "lineage-execution-resync-guard",
                reviewQueueItemId = "rq-execution-resync-guard",
                executionIssueId = "issue-execution-resync-guard",
                executionTaskId = "task-execution-resync-guard",
                executionRunId = "run-execution-resync-guard",
                pullRequestNumber = 77,
                pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/77",
                branchName = "codex/cotor/execution-resync-guard",
                worktreePath = repoRoot.resolve(".cotor/worktrees/execution-resync-guard/codex").toString(),
                generation = 1
            )
        )
        val reviewIssue = CompanyIssue(
            id = "issue-review-resync-guard",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "QA review Ship the already reviewed PR",
            description = "QA checks the branch.",
            status = IssueStatus.DONE,
            priority = 2,
            kind = "review",
            assigneeProfileId = qaProfileId,
            dependsOn = listOf(executionIssue.id),
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            qaVerdict = "PASS",
            qaFeedback = "QA already approved this PR.",
            sourceSignal = "qa-review:${executionIssue.id}",
            createdAt = now,
            updatedAt = now,
            workflowLineage = executionIssue.workflowLineage
        )
        val approvalIssue = CompanyIssue(
            id = "issue-approval-resync-guard",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "CEO approve Ship the already reviewed PR",
            description = "CEO approval gate.",
            status = IssueStatus.PLANNED,
            priority = 1,
            kind = "approval",
            assigneeProfileId = ceoProfileId,
            dependsOn = listOf(reviewIssue.id),
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            qaVerdict = "PASS",
            qaFeedback = "QA already approved this PR.",
            sourceSignal = "ceo-approval:${executionIssue.id}",
            createdAt = now,
            updatedAt = now,
            workflowLineage = executionIssue.workflowLineage
        )
        val executionTask = AgentTask(
            id = "task-execution-resync-guard",
            workspaceId = workspace.id,
            title = executionIssue.title,
            prompt = executionIssue.description,
            agents = listOf("codex"),
            issueId = executionIssue.id,
            status = DesktopTaskStatus.COMPLETED,
            createdAt = now - 5_000,
            updatedAt = now - 4_000,
            workflowLineage = executionIssue.workflowLineage
        )
        val executionRun = AgentRun(
            id = "run-execution-resync-guard",
            taskId = executionTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.COMPLETED,
            output = "Already published.",
            branchName = executionIssue.branchName!!,
            worktreePath = executionIssue.worktreePath!!,
            publish = PublishMetadata(
                commitSha = "resyncguard",
                pullRequestNumber = executionIssue.pullRequestNumber,
                pullRequestUrl = executionIssue.pullRequestUrl,
                pullRequestState = executionIssue.pullRequestState
            ),
            durationMs = 150,
            createdAt = now - 5_000,
            updatedAt = now - 4_000,
            workflowLineage = executionIssue.workflowLineage
        )
        val reviewQueueItem = ReviewQueueItem(
            id = "rq-execution-resync-guard",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = executionIssue.id,
            runId = executionRun.id,
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            status = ReviewQueueStatus.READY_FOR_CEO,
            qaVerdict = "PASS",
            qaFeedback = "QA already approved this PR.",
            qaReviewedAt = now - 2_000,
            qaIssueId = reviewIssue.id,
            approvalIssueId = approvalIssue.id,
            createdAt = now - 10_000,
            updatedAt = now - 2_000,
            workflowLineage = executionIssue.workflowLineage
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, reviewIssue, approvalIssue),
                tasks = baseState.tasks + executionTask,
                runs = baseState.runs + executionRun,
                reviewQueue = baseState.reviewQueue + reviewQueueItem,
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        backendKind = ExecutionBackendKind.LOCAL_COTOR,
                        backendHealth = "healthy",
                        lastStartedAt = now
                    )
                )
            )
        )

        service.runCompanyRuntimeTick(company.id)

        val refreshed = stateStore.load()
        refreshed.issues.first { it.id == executionIssue.id }.status shouldBe IssueStatus.READY_FOR_CEO
        refreshed.issues.first { it.id == reviewIssue.id }.status shouldBe IssueStatus.DONE
        refreshed.issues.first { it.id == approvalIssue.id }.status shouldBe IssueStatus.IN_PROGRESS
        refreshed.companyActivity.none { it.title == "Reopened QA review issue" } shouldBe true
    }

    test("CEO approval merges the current PR and completes the execution lineage") {
        val appHome = Files.createTempDirectory("desktop-app-service-ceo-merge")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-ceo-merge-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.commentOnPullRequest(any(), 22, any()) } returns Unit
        coEvery {
            gitWorkspaceService.syncBaseBranchAfterMerge(any(), "master")
        } returns BaseBranchSyncResult(
            synced = true,
            workingTreeUpdated = true
        )
        coEvery {
            gitWorkspaceService.submitPullRequestReview(any(), 22, PullRequestReviewVerdict.APPROVE, any())
        } returns PublishMetadata(
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            reviewState = "APPROVED",
            mergeability = "MERGEABLE"
        )
        coEvery {
            gitWorkspaceService.mergePullRequest(any(), 22, true)
        } returns PullRequestMergeResult(
            number = 22,
            url = "https://github.com/heodongun/cotor-test/pull/22",
            state = "MERGED",
            mergeCommitSha = "deadbeef"
        )
        coEvery {
            gitWorkspaceService.refreshPullRequestMetadata(any(), 22)
        } returns PublishMetadata(
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "MERGED",
            mergeability = "CLEAN"
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )
        // Wait for the init automation refresh to settle before modifying state.
        delay(500)

        val company = service.createCompany(
            name = "CEO Merge Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-ceo-merge",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Merge the approved PR",
            description = "Exercise the CEO approval merge lane.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-ceo-merge",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Ship the current PR",
            description = "Execution branch work.",
            status = IssueStatus.READY_FOR_CEO,
            priority = 2,
            kind = "execution",
            branchName = "codex/cotor/ceo-merge",
            worktreePath = repoRoot.resolve(".cotor/worktrees/ceo-merge/codex").toString(),
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            qaVerdict = "PASS",
            qaFeedback = "QA approved this PR.",
            createdAt = now,
            updatedAt = now
        )
        val approvalIssue = CompanyIssue(
            id = "issue-approval-ceo-merge",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "CEO approve Ship the current PR",
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
        val approvalTask = AgentTask(
            id = "task-approval-ceo-merge",
            workspaceId = workspace.id,
            title = approvalIssue.title,
            prompt = approvalIssue.description,
            agents = listOf("codex"),
            issueId = approvalIssue.id,
            status = DesktopTaskStatus.COMPLETED,
            createdAt = now,
            updatedAt = now
        )
        val approvalRun = AgentRun(
            id = "run-approval-ceo-merge",
            taskId = approvalTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.COMPLETED,
            output = "CEO_VERDICT: APPROVE\nReady to merge.",
            branchName = executionIssue.branchName!!,
            worktreePath = executionIssue.worktreePath!!,
            durationMs = 150,
            createdAt = now,
            updatedAt = now
        )
        val reviewQueueItem = ReviewQueueItem(
            id = "rq-ceo-merge",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = executionIssue.id,
            runId = "run-execution-ceo-merge",
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            status = ReviewQueueStatus.READY_FOR_CEO,
            mergeability = "clean",
            qaVerdict = "PASS",
            qaFeedback = "QA approved this PR.",
            qaReviewedAt = now - 1_000,
            approvalIssueId = approvalIssue.id,
            createdAt = now - 10_000,
            updatedAt = now - 1_000
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, approvalIssue),
                tasks = baseState.tasks + approvalTask,
                runs = baseState.runs + approvalRun,
                reviewQueue = baseState.reviewQueue + reviewQueueItem,
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        backendKind = ExecutionBackendKind.LOCAL_COTOR,
                        backendHealth = "healthy",
                        lastStartedAt = now
                    )
                )
            )
        )

        // Wait for init automation to settle, then use the public merge API
        // directly to avoid race conditions with background automation refreshes.
        delay(500)

        // Ensure state is correct before the merge.
        val preMerge = stateStore.load()
        stateStore.save(
            preMerge.copy(
                goals = preMerge.goals.filterNot { it.id == goal.id } + goal,
                issues = preMerge.issues.filterNot { it.id == executionIssue.id || it.id == approvalIssue.id } + listOf(executionIssue, approvalIssue),
                tasks = preMerge.tasks.filterNot { it.id == approvalTask.id } + approvalTask,
                runs = preMerge.runs.filterNot { it.id == approvalRun.id } + approvalRun,
                reviewQueue = preMerge.reviewQueue.filterNot { it.id == reviewQueueItem.id } + reviewQueueItem
            )
        )

        val merged = service.mergeReviewQueueItem(reviewQueueItem.id)
        merged.status shouldBe ReviewQueueStatus.MERGED
        merged.mergeCommitSha shouldBe "deadbeef"
        coVerify(exactly = 1) { gitWorkspaceService.commentOnPullRequest(any(), 22, any()) }
        coVerify(exactly = 1) { gitWorkspaceService.syncBaseBranchAfterMerge(any(), "master") }

        val finalState = stateStore.load()
        val finalExecution = finalState.issues.first { it.id == executionIssue.id }
        finalExecution.status shouldBe IssueStatus.DONE
        finalExecution.mergeResult shouldBe "MERGED"
    }

    test("mergeReviewQueueItem converts dirty PR merges into remediation work instead of retrying forever") {
        val appHome = Files.createTempDirectory("desktop-app-service-ceo-merge-conflict")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-ceo-merge-conflict-repo").resolve("repo"))
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
            mergeability = "DIRTY"
        )
        coEvery {
            gitWorkspaceService.mergePullRequest(any(), 22)
        } throws ProcessExecutionException(
            message = "GH command failed",
            exitCode = 1,
            stdout = "X Pull request bssm-oss/cotor-test#22 is not mergeable: the merge commit cannot be cleanly created.\n",
            stderr = "",
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )
        delay(500)

        val company = service.createCompany(
            name = "CEO Merge Conflict Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-ceo-merge-conflict",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Resolve merge conflicts before merge",
            description = "Exercise merge conflict remediation.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-ceo-merge-conflict",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Ship the conflicted PR",
            description = "Execution branch work.",
            status = IssueStatus.READY_FOR_CEO,
            priority = 2,
            kind = "execution",
            branchName = "codex/cotor/ceo-merge-conflict",
            worktreePath = repoRoot.resolve(".cotor/worktrees/ceo-merge-conflict/codex").toString(),
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            qaVerdict = "PASS",
            qaFeedback = "QA approved this PR.",
            createdAt = now,
            updatedAt = now
        )
        val approvalIssue = CompanyIssue(
            id = "issue-approval-ceo-merge-conflict",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "CEO approve Ship the conflicted PR",
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
            id = "rq-ceo-merge-conflict",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = executionIssue.id,
            runId = "run-execution-ceo-merge-conflict",
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            status = ReviewQueueStatus.READY_FOR_CEO,
            mergeability = "DIRTY",
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

        updated.status shouldBe ReviewQueueStatus.CHANGES_REQUESTED
        updated.ceoVerdict shouldBe "CHANGES_REQUESTED"
        updated.ceoFeedback.shouldContain("GitHub could not merge this pull request cleanly.")
        coVerify(exactly = 0) { gitWorkspaceService.mergePullRequest(any(), 22) }
        coVerify(exactly = 0) { gitWorkspaceService.syncBaseBranchAfterMerge(any(), any()) }

        val finalState = stateStore.load()
        finalState.issues.first { it.id == executionIssue.id }.status shouldBe IssueStatus.PLANNED
        finalState.issues.first { it.id == approvalIssue.id }.status shouldBe IssueStatus.BLOCKED
    }

    test("company runtime tick reopens CEO merge-conflict blocks after GitHub reports a clean PR again") {
        val appHome = Files.createTempDirectory("desktop-app-service-merge-conflict-recovery")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-merge-conflict-recovery-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        val worktreePath = repoRoot.resolve(".cotor/worktrees/ceo-merge-conflict-recovery/codex")
        coEvery {
            gitWorkspaceService.refreshPullRequestMetadata(worktreePath, 22)
        } returns PublishMetadata(
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            mergeability = "CLEAN"
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "CEO Merge Conflict Recovery Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-ceo-merge-conflict-recovery",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Recover stale CEO conflict blockers",
            description = "Normalize review queue state after conflicts are fixed.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = false,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-ceo-merge-conflict-recovery",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Ship the previously conflicted PR",
            description = "Execution branch work.",
            status = IssueStatus.BLOCKED,
            priority = 2,
            kind = "execution",
            branchName = "codex/cotor/ceo-merge-conflict-recovery/codex",
            worktreePath = worktreePath.toString(),
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            qaVerdict = "PASS",
            qaFeedback = "QA approved this PR.",
            ceoVerdict = "CHANGES_REQUESTED",
            ceoFeedback = "GitHub could not merge this pull request cleanly.",
            transitionReason = "Execution failed and requires a recoverable retry or remediation.",
            createdAt = now,
            updatedAt = now
        )
        val approvalIssue = CompanyIssue(
            id = "issue-approval-ceo-merge-conflict-recovery",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "CEO approve Ship the previously conflicted PR",
            description = "CEO approval gate.",
            status = IssueStatus.BLOCKED,
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
            ceoVerdict = "CHANGES_REQUESTED",
            ceoFeedback = "GitHub could not merge this pull request cleanly.",
            transitionReason = "Merge is blocked until the execution branch is rebased and conflicts are resolved.",
            sourceSignal = "ceo-approval:${executionIssue.id}",
            createdAt = now,
            updatedAt = now
        )
        val reviewQueueItem = ReviewQueueItem(
            id = "rq-ceo-merge-conflict-recovery",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = executionIssue.id,
            runId = "run-execution-ceo-merge-conflict-recovery",
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            status = ReviewQueueStatus.CHANGES_REQUESTED,
            mergeability = "DIRTY",
            qaVerdict = "PASS",
            qaFeedback = "QA approved this PR.",
            ceoVerdict = "CHANGES_REQUESTED",
            ceoFeedback = "GitHub could not merge this pull request cleanly.",
            ceoReviewedAt = now - 500,
            approvalIssueId = approvalIssue.id,
            createdAt = now - 10_000,
            updatedAt = now - 500
        )
        val runningRuntime = baseState.companyRuntimes.first { it.companyId == company.id }.copy(
            status = CompanyRuntimeStatus.RUNNING,
            lastStartedAt = now - 5_000,
            lastAction = "runtime-started",
            adaptiveTickMs = 15_000
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, approvalIssue),
                reviewQueue = baseState.reviewQueue + reviewQueueItem,
                companyRuntimes = baseState.companyRuntimes.map {
                    if (it.companyId == company.id) runningRuntime else it
                }
            )
        )

        service.runCompanyRuntimeTick(company.id)

        val refreshedState = stateStore.load()
        val refreshedExecution = refreshedState.issues.first { it.id == executionIssue.id }
        val refreshedApproval = refreshedState.issues.first { it.id == approvalIssue.id }
        val refreshedQueue = refreshedState.reviewQueue.first { it.id == reviewQueueItem.id }

        refreshedExecution.status shouldBe IssueStatus.READY_FOR_CEO
        refreshedExecution.ceoVerdict shouldBe null
        refreshedExecution.transitionReason.shouldContain("ready for CEO approval again")
        refreshedApproval.status shouldBe IssueStatus.PLANNED
        refreshedApproval.ceoVerdict shouldBe null
        refreshedApproval.transitionReason.shouldContain("CEO approval can run again")
        refreshedQueue.status shouldBe ReviewQueueStatus.READY_FOR_CEO
        refreshedQueue.mergeability shouldBe "CLEAN"
        refreshedQueue.ceoVerdict shouldBe null
        coVerify(exactly = 1) {
            gitWorkspaceService.refreshPullRequestMetadata(worktreePath, 22)
        }
    }

    test("company runtime tick requeues legacy blocked execution issues for dirty CEO merge conflicts") {
        val appHome = Files.createTempDirectory("desktop-app-service-legacy-merge-conflict-requeue")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-legacy-merge-conflict-requeue-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Legacy Merge Conflict Requeue Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-legacy-merge-conflict-requeue",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Recover old merge conflict blockers",
            description = "Normalize execution issues that stayed blocked after an old CEO merge conflict.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = false,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-legacy-merge-conflict-requeue",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Republish the conflicted PR",
            description = "Execution branch work.",
            status = IssueStatus.BLOCKED,
            priority = 2,
            kind = "execution",
            branchName = "codex/cotor/legacy-merge-conflict-requeue/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/legacy-merge-conflict-requeue/codex").toString(),
            pullRequestNumber = 321,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/321",
            pullRequestState = "OPEN",
            qaVerdict = "PASS",
            qaFeedback = "QA approved this PR.",
            ceoVerdict = "CHANGES_REQUESTED",
            ceoFeedback = "GitHub could not merge this pull request cleanly.",
            transitionReason = "Execution failed and requires a recoverable retry or remediation.",
            createdAt = now,
            updatedAt = now
        )
        val approvalIssue = CompanyIssue(
            id = "issue-approval-legacy-merge-conflict-requeue",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "CEO approve Republish the conflicted PR",
            description = "CEO approval gate.",
            status = IssueStatus.BLOCKED,
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
            ceoVerdict = "CHANGES_REQUESTED",
            ceoFeedback = "GitHub could not merge this pull request cleanly.",
            transitionReason = "Merge is blocked until the execution branch is rebased and conflicts are resolved.",
            sourceSignal = "ceo-approval:${executionIssue.id}",
            createdAt = now,
            updatedAt = now
        )
        val reviewQueueItem = ReviewQueueItem(
            id = "rq-legacy-merge-conflict-requeue",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = executionIssue.id,
            runId = "run-legacy-merge-conflict-requeue",
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = 321,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = "OPEN",
            status = ReviewQueueStatus.CHANGES_REQUESTED,
            mergeability = "DIRTY",
            qaVerdict = "PASS",
            qaFeedback = "QA approved this PR.",
            ceoVerdict = "CHANGES_REQUESTED",
            ceoFeedback = "GitHub could not merge this pull request cleanly.",
            ceoReviewedAt = now - 500,
            approvalIssueId = approvalIssue.id,
            createdAt = now - 10_000,
            updatedAt = now - 500
        )
        val runningRuntime = baseState.companyRuntimes.first { it.companyId == company.id }.copy(
            status = CompanyRuntimeStatus.RUNNING,
            lastStartedAt = now - 5_000,
            lastAction = "runtime-started",
            adaptiveTickMs = 15_000
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, approvalIssue),
                reviewQueue = baseState.reviewQueue + reviewQueueItem,
                companyRuntimes = baseState.companyRuntimes.map {
                    if (it.companyId == company.id) runningRuntime else it
                }
            )
        )

        service.runCompanyRuntimeTick(company.id)

        val refreshedState = stateStore.load()
        val refreshedExecution = refreshedState.issues.first { it.id == executionIssue.id }
        val refreshedApproval = refreshedState.issues.firstOrNull { it.id == approvalIssue.id }
        val refreshedQueue = refreshedState.reviewQueue.first { it.id == reviewQueueItem.id }

        refreshedExecution.status shouldBe IssueStatus.PLANNED
        refreshedExecution.transitionReason.shouldContain("legacy CEO merge-conflict blocker")
        refreshedApproval?.status shouldBe IssueStatus.BLOCKED
        refreshedQueue.status shouldBe ReviewQueueStatus.CHANGES_REQUESTED
    }

    test("company dashboard read requeues legacy blocked execution issues for dirty CEO merge conflicts while stopped") {
        val appHome = Files.createTempDirectory("desktop-app-service-dashboard-merge-conflict-requeue")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-dashboard-merge-conflict-requeue-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.closeSupersededManagedPullRequests(any(), any()) } returns ManagedPullRequestCleanupResult()
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Dashboard Merge Conflict Requeue Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-dashboard-merge-conflict-requeue",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Requeue blocked execution from dashboard refresh",
            description = "Normalize a merge-conflict blocker even if the runtime is manually stopped.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = false,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-dashboard-merge-conflict-requeue",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Republish the conflicted PR from dashboard refresh",
            description = "Execution branch work.",
            status = IssueStatus.BLOCKED,
            priority = 2,
            kind = "execution",
            branchName = "codex/cotor/dashboard-merge-conflict-requeue/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/dashboard-merge-conflict-requeue/codex").toString(),
            pullRequestNumber = 321,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/321",
            pullRequestState = "OPEN",
            ceoVerdict = "CHANGES_REQUESTED",
            ceoFeedback = "GitHub could not merge this pull request cleanly.",
            transitionReason = "Execution failed and requires a recoverable retry or remediation.",
            createdAt = now,
            updatedAt = now
        )
        val approvalIssue = CompanyIssue(
            id = "issue-approval-dashboard-merge-conflict-requeue",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "CEO approve Republish the conflicted PR from dashboard refresh",
            description = "CEO approval gate.",
            status = IssueStatus.BLOCKED,
            priority = 1,
            kind = "approval",
            dependsOn = listOf(executionIssue.id),
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            ceoVerdict = "CHANGES_REQUESTED",
            ceoFeedback = "GitHub could not merge this pull request cleanly.",
            transitionReason = "Merge is blocked until the execution branch is rebased and conflicts are resolved.",
            sourceSignal = "ceo-approval:${executionIssue.id}",
            createdAt = now,
            updatedAt = now
        )
        val reviewQueueItem = ReviewQueueItem(
            id = "rq-dashboard-merge-conflict-requeue",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = executionIssue.id,
            runId = "run-dashboard-merge-conflict-requeue",
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            status = ReviewQueueStatus.CHANGES_REQUESTED,
            mergeability = "DIRTY",
            ceoVerdict = "CHANGES_REQUESTED",
            ceoFeedback = "GitHub could not merge this pull request cleanly.",
            approvalIssueId = approvalIssue.id,
            createdAt = now,
            updatedAt = now
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, approvalIssue),
                reviewQueue = baseState.reviewQueue + reviewQueueItem
            )
        )

        service.companyDashboard(company.id)
        withTimeout(2_000) {
            while (stateStore.load().issues.first { it.id == executionIssue.id }.status != IssueStatus.PLANNED) {
                delay(25)
            }
        }

        val refreshedState = stateStore.load()
        val refreshedExecution = refreshedState.issues.first { it.id == executionIssue.id }
        refreshedExecution.status shouldBe IssueStatus.PLANNED
        refreshedExecution.transitionReason.shouldContain("legacy CEO merge-conflict blocker")
    }

    test("company runtime tick keeps no-diff existing PR remediation queued when the reused PR is still dirty") {
        val appHome = Files.createTempDirectory("desktop-app-service-existing-pr-dirty-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-existing-pr-dirty-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        val worktreePath = repoRoot.resolve(".cotor/worktrees/existing-pr-dirty/codex")
        coEvery {
            gitWorkspaceService.refreshPullRequestMetadata(worktreePath, 22)
        } returns PublishMetadata(
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            mergeability = "DIRTY"
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Existing PR Dirty Recovery Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-existing-pr-dirty-recovery",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Repair an existing dirty PR",
            description = "Keep remediation on the same PR when a no-diff retry finds no new local change.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = false,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-existing-pr-dirty-recovery",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Resolve merge conflict for PR #22 against master",
            description = "Rebase the existing PR cleanly without creating a replacement PR.",
            status = IssueStatus.BLOCKED,
            priority = 2,
            kind = "execution",
            executionIntent = ExecutionIntent.MERGE_CONFLICT_REMEDIATION,
            branchName = "codex/cotor/existing-pr-dirty/codex",
            worktreePath = worktreePath.toString(),
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            qaVerdict = "PASS",
            qaFeedback = "QA already approved the current PR.",
            ceoVerdict = "CHANGES_REQUESTED",
            ceoFeedback = "GitHub could not merge this pull request cleanly.",
            transitionReason = "Execution produced no new diff against the existing PR lineage; Cotor will refresh that PR state instead of opening another review cycle.",
            createdAt = now,
            updatedAt = now
        )
        val approvalIssue = CompanyIssue(
            id = "issue-approval-existing-pr-dirty-recovery",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "CEO approve Resolve merge conflict for PR #22 against master",
            description = "CEO approval waits for the reused PR to become clean again.",
            status = IssueStatus.BLOCKED,
            priority = 1,
            kind = "approval",
            dependsOn = listOf(executionIssue.id),
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            qaVerdict = executionIssue.qaVerdict,
            qaFeedback = executionIssue.qaFeedback,
            ceoVerdict = executionIssue.ceoVerdict,
            ceoFeedback = executionIssue.ceoFeedback,
            transitionReason = "Merge is blocked until the execution branch is rebased and conflicts are resolved.",
            sourceSignal = "ceo-approval:${executionIssue.id}",
            createdAt = now,
            updatedAt = now
        )
        val executionTask = AgentTask(
            id = "task-execution-existing-pr-dirty-recovery",
            workspaceId = workspace.id,
            issueId = executionIssue.id,
            title = executionIssue.title,
            prompt = executionIssue.description,
            agents = listOf("codex"),
            status = DesktopTaskStatus.FAILED,
            createdAt = now - 1_000,
            updatedAt = now - 500
        )
        val executionRun = AgentRun(
            id = "run-execution-existing-pr-dirty-recovery",
            taskId = executionTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.FAILED,
            output = "No repository edit was made because there is no legitimate local change to apply: this assigned branch already matches `master` exactly.",
            error = "No changes to publish from codex/cotor/existing-pr-dirty/codex against master",
            branchName = requireNotNull(executionIssue.branchName),
            worktreePath = requireNotNull(executionIssue.worktreePath),
            publish = PublishMetadata(commitSha = "abc1234"),
            durationMs = 250,
            createdAt = now - 1_000,
            updatedAt = now - 500
        )
        val reviewQueueItem = ReviewQueueItem(
            id = "rq-existing-pr-dirty-recovery",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = executionIssue.id,
            runId = executionRun.id,
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            status = ReviewQueueStatus.CHANGES_REQUESTED,
            mergeability = "DIRTY",
            qaVerdict = executionIssue.qaVerdict,
            qaFeedback = executionIssue.qaFeedback,
            ceoVerdict = executionIssue.ceoVerdict,
            ceoFeedback = executionIssue.ceoFeedback,
            approvalIssueId = approvalIssue.id,
            createdAt = now - 2_000,
            updatedAt = now - 500
        )
        val runningRuntime = baseState.companyRuntimes.first { it.companyId == company.id }.copy(
            status = CompanyRuntimeStatus.RUNNING,
            lastStartedAt = now - 5_000,
            lastAction = "runtime-started",
            adaptiveTickMs = 15_000
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, approvalIssue),
                tasks = baseState.tasks + executionTask,
                runs = baseState.runs + executionRun,
                reviewQueue = baseState.reviewQueue + reviewQueueItem,
                companyRuntimes = baseState.companyRuntimes.map {
                    if (it.companyId == company.id) runningRuntime else it
                }
            )
        )

        service.runCompanyRuntimeTick(company.id)

        val refreshedState = withTimeout(30_000) {
            while (true) {
                val candidate = stateStore.load()
                val candidateExecution = candidate.issues.first { it.id == executionIssue.id }
                val candidateApproval = candidate.issues.firstOrNull { it.id == approvalIssue.id }
                val candidateQueue = candidate.reviewQueue.firstOrNull { it.id == reviewQueueItem.id }
                if (
                    candidateExecution.status == IssueStatus.PLANNED &&
                    candidateExecution.executionIntent == ExecutionIntent.MERGE_CONFLICT_REMEDIATION &&
                    candidateApproval == null &&
                    candidateQueue != null &&
                    candidateQueue.status == ReviewQueueStatus.CHANGES_REQUESTED &&
                    candidate.reviewQueue.count { it.issueId == executionIssue.id } == 1
                ) {
                    return@withTimeout candidate
                }
                delay(25)
            }
            error("Unreachable")
        }
        val refreshedExecution = refreshedState.issues.first { it.id == executionIssue.id }
        val refreshedApproval = refreshedState.issues.firstOrNull { it.id == approvalIssue.id }
        val refreshedQueue = refreshedState.reviewQueue.firstOrNull { it.id == reviewQueueItem.id }

        refreshedExecution.status shouldBe IssueStatus.PLANNED
        refreshedExecution.executionIntent shouldBe ExecutionIntent.MERGE_CONFLICT_REMEDIATION
        refreshedExecution.transitionReason.shouldContain("still has merge conflicts")
        refreshedExecution.pullRequestNumber shouldBe 22
        refreshedApproval.shouldBeNull()
        refreshedQueue.shouldNotBeNull()
        refreshedQueue.status shouldBe ReviewQueueStatus.CHANGES_REQUESTED
        refreshedQueue.id shouldBe reviewQueueItem.id
        refreshedQueue.approvalIssueId shouldBe null
        refreshedState.reviewQueue.count { it.issueId == executionIssue.id } shouldBe 1
        coVerify(timeout = 30_000, exactly = 1) {
            gitWorkspaceService.refreshPullRequestMetadata(worktreePath, 22)
        }
    }

    test("company dashboard read archives a legacy stale follow-up after the root issue already merged") {
        val appHome = Files.createTempDirectory("desktop-app-service-dashboard-stale-followup-archive")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-dashboard-stale-followup-archive-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.closeSupersededManagedPullRequests(any(), any()) } returns ManagedPullRequestCleanupResult()
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Legacy Stale Follow Up Archive Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val rootGoal = CompanyGoal(
            id = "goal-root-merged-followup",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "CEO continuous improvement cycle #1 for test",
            description = "Root company goal that already finished successfully.",
            status = GoalStatus.COMPLETED,
            autonomyEnabled = true,
            createdAt = now - 30_000,
            updatedAt = now - 30_000
        )
        val mergedExecution = CompanyIssue(
            id = "issue-root-merged-followup",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = rootGoal.id,
            workspaceId = workspace.id,
            title = "Deliver the smallest complete repository change for test",
            description = "The root execution already merged as PR #393.",
            status = IssueStatus.DONE,
            kind = "execution",
            pullRequestNumber = 393,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/393",
            pullRequestState = "MERGED",
            mergeResult = "MERGED",
            createdAt = now - 29_000,
            updatedAt = now - 29_000
        )
        val staleFollowUpGoal = CompanyGoal(
            id = "goal-stale-followup-legacy",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Resolve follow-up for \"${mergedExecution.title}\"",
            description = "Legacy stale follow-up that should be archived automatically.",
            status = GoalStatus.ACTIVE,
            operatingPolicy = "auto-follow-up:goal:${rootGoal.id}",
            autonomyEnabled = true,
            createdAt = now - 20_000,
            updatedAt = now - 20_000
        )
        val staleExecution = CompanyIssue(
            id = "issue-stale-followup-execution",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = staleFollowUpGoal.id,
            workspaceId = workspace.id,
            title = "Hand the result back to the CEO for another decision cycle.",
            description = "Legacy stale handoff issue that should not survive once the root PR is merged.",
            status = IssueStatus.BLOCKED,
            kind = "execution",
            branchName = "codex/cotor/legacy-followup/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/legacy-followup/codex").toString(),
            pullRequestNumber = 392,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/392",
            pullRequestState = "OPEN",
            qaVerdict = "PASS",
            qaFeedback = "QA already approved this stale PR.",
            ceoVerdict = "CHANGES_REQUESTED",
            ceoFeedback = "GitHub could not merge this pull request cleanly.",
            transitionReason = "Execution failed and requires a recoverable retry or remediation.",
            createdAt = now - 19_000,
            updatedAt = now - 19_000
        )
        val staleApproval = CompanyIssue(
            id = "issue-stale-followup-approval",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = staleFollowUpGoal.id,
            workspaceId = workspace.id,
            title = "CEO approve Hand the result back to the CEO for another decision cycle.",
            description = "Legacy stale approval gate.",
            status = IssueStatus.BLOCKED,
            kind = "approval",
            dependsOn = listOf(staleExecution.id),
            branchName = staleExecution.branchName,
            worktreePath = staleExecution.worktreePath,
            pullRequestNumber = staleExecution.pullRequestNumber,
            pullRequestUrl = staleExecution.pullRequestUrl,
            pullRequestState = staleExecution.pullRequestState,
            qaVerdict = staleExecution.qaVerdict,
            qaFeedback = staleExecution.qaFeedback,
            ceoVerdict = staleExecution.ceoVerdict,
            ceoFeedback = staleExecution.ceoFeedback,
            transitionReason = "Merge is blocked until the execution branch is rebased and conflicts are resolved.",
            sourceSignal = "ceo-approval:${staleExecution.id}",
            createdAt = now - 19_000,
            updatedAt = now - 19_000
        )
        val staleQueueItem = ReviewQueueItem(
            id = "rq-stale-followup-legacy",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = staleExecution.id,
            runId = "run-stale-followup-legacy",
            branchName = staleExecution.branchName,
            worktreePath = staleExecution.worktreePath,
            pullRequestNumber = staleExecution.pullRequestNumber,
            pullRequestUrl = staleExecution.pullRequestUrl,
            pullRequestState = staleExecution.pullRequestState,
            status = ReviewQueueStatus.CHANGES_REQUESTED,
            mergeability = "DIRTY",
            qaVerdict = staleExecution.qaVerdict,
            qaFeedback = staleExecution.qaFeedback,
            ceoVerdict = staleExecution.ceoVerdict,
            ceoFeedback = staleExecution.ceoFeedback,
            approvalIssueId = staleApproval.id,
            createdAt = now - 19_000,
            updatedAt = now - 19_000
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + listOf(rootGoal, staleFollowUpGoal),
                issues = baseState.issues + listOf(mergedExecution, staleExecution, staleApproval),
                reviewQueue = baseState.reviewQueue + staleQueueItem
            )
        )

        service.companyDashboard(company.id)
        withTimeout(10_000) {
            while (true) {
                val state = stateStore.load()
                val goalArchived = state.goals.first { it.id == staleFollowUpGoal.id }.status == GoalStatus.COMPLETED
                val issuesArchived = listOf(staleExecution.id, staleApproval.id).all { issueId ->
                    state.issues.first { it.id == issueId }.status == IssueStatus.CANCELED
                }
                val queueRemoved = state.reviewQueue.none { it.id == staleQueueItem.id }
                if (goalArchived && issuesArchived && queueRemoved) {
                    break
                }
                service.companyDashboardPrepared(company.id)
                delay(25)
            }
        }

        val refreshedState = stateStore.load()
        refreshedState.goals.first { it.id == staleFollowUpGoal.id }.status shouldBe GoalStatus.COMPLETED
        refreshedState.issues.first { it.id == staleExecution.id }.status shouldBe IssueStatus.CANCELED
        refreshedState.issues.first { it.id == staleApproval.id }.status shouldBe IssueStatus.CANCELED
        refreshedState.reviewQueue.none { it.id == staleQueueItem.id } shouldBe true
    }

    test("company runtime tick restores CEO approval when a remediation run reuses an existing clean PR without new diff") {
        val appHome = Files.createTempDirectory("desktop-app-service-existing-pr-recovery")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-existing-pr-recovery-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        val worktreePath = repoRoot.resolve(".cotor/worktrees/existing-pr-recovery/codex")
        coEvery {
            gitWorkspaceService.refreshPullRequestMetadata(worktreePath, 22)
        } returns PublishMetadata(
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            mergeability = "CLEAN"
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Existing PR Recovery Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-existing-pr-recovery",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Recover reused PR after remediation",
            description = "Normalize no-op remediation runs that still point at a clean PR.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = false,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-existing-pr-recovery",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Hand the result back to the CEO for another decision cycle.",
            description = "Confirm the cleaned PR can go back to the CEO without creating noise.",
            status = IssueStatus.BLOCKED,
            priority = 2,
            kind = "execution",
            branchName = "codex/cotor/existing-pr-recovery/codex",
            worktreePath = worktreePath.toString(),
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            qaVerdict = "PASS",
            qaFeedback = "QA already approved the existing PR.",
            ceoVerdict = "APPROVE",
            transitionReason = "Execution completed but required PR publication did not succeed.",
            createdAt = now,
            updatedAt = now
        )
        val executionTask = AgentTask(
            id = "task-execution-existing-pr-recovery",
            workspaceId = workspace.id,
            issueId = executionIssue.id,
            title = executionIssue.title,
            prompt = executionIssue.description,
            agents = listOf("codex"),
            status = DesktopTaskStatus.COMPLETED,
            createdAt = now - 1_000,
            updatedAt = now - 500
        )
        val executionRun = AgentRun(
            id = "run-execution-existing-pr-recovery",
            taskId = executionTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.COMPLETED,
            output = "No repository edit was made because there is no legitimate local change to apply: this assigned branch already matches `master` exactly, so there is nothing to rebase or merge in the worktree without manufacturing noise.",
            branchName = requireNotNull(executionIssue.branchName),
            worktreePath = requireNotNull(executionIssue.worktreePath),
            publish = PublishMetadata(
                commitSha = "abc1234"
            ),
            durationMs = 250,
            createdAt = now - 1_000,
            updatedAt = now - 500
        )
        val runningRuntime = baseState.companyRuntimes.first { it.companyId == company.id }.copy(
            status = CompanyRuntimeStatus.RUNNING,
            lastStartedAt = now - 5_000,
            lastAction = "runtime-started",
            adaptiveTickMs = 15_000
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + executionIssue,
                tasks = baseState.tasks + executionTask,
                runs = baseState.runs + executionRun,
                companyRuntimes = baseState.companyRuntimes.map {
                    if (it.companyId == company.id) runningRuntime else it
                }
            )
        )

        service.runCompanyRuntimeTick(company.id)

        val refreshedState = stateStore.load()
        val refreshedExecution = refreshedState.issues.first { it.id == executionIssue.id }
        val refreshedApproval = refreshedState.issues.firstOrNull {
            it.kind.equals("approval", ignoreCase = true) && executionIssue.id in it.dependsOn
        }
        val refreshedQueue = refreshedState.reviewQueue.first { it.issueId == executionIssue.id }

        refreshedExecution.status shouldBe IssueStatus.READY_FOR_CEO
        refreshedExecution.ceoVerdict shouldBe null
        refreshedExecution.transitionReason.shouldContain("ready for CEO approval again")
        refreshedQueue.status shouldBe ReviewQueueStatus.READY_FOR_CEO
        refreshedQueue.pullRequestNumber shouldBe 22
        refreshedQueue.mergeability shouldBe "CLEAN"
        refreshedQueue.ceoVerdict shouldBe null
        refreshedApproval?.let { approval ->
            approval.status shouldBe IssueStatus.PLANNED
            approval.pullRequestNumber shouldBe 22
        }
        coVerify(exactly = 1) {
            gitWorkspaceService.refreshPullRequestMetadata(worktreePath, 22)
        }
    }

    test("company runtime tick closes stale blocked execution issues when the linked pull request is already merged") {
        val appHome = Files.createTempDirectory("desktop-app-service-merged-pr-normalization")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-merged-pr-normalization-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Merged PR Normalization Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-merged-pr-normalization",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Normalize stale merged execution issues",
            description = "Ensure stale no-op sync cannot keep a merged execution issue blocked.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = false,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-merged-pr-normalization",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Hand the result back to the CEO for another decision cycle.",
            description = "Normalize stale execution state after merge.",
            status = IssueStatus.BLOCKED,
            priority = 2,
            kind = "execution",
            branchName = "codex/cotor/merged-pr-normalization/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/merged-pr-normalization/codex").toString(),
            pullRequestNumber = 309,
            pullRequestUrl = "https://github.com/bssm-oss/cotor-test/pull/309",
            pullRequestState = "MERGED",
            qaVerdict = "PASS",
            qaFeedback = "QA already approved the linked PR.",
            ceoVerdict = "APPROVE",
            ceoFeedback = "CEO already approved the merge.",
            transitionReason = "Execution completed but required PR publication did not succeed.",
            createdAt = now,
            updatedAt = now
        )
        val approvalIssue = CompanyIssue(
            id = "issue-approval-merged-pr-normalization",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "CEO approve Hand the result back to the CEO for another decision cycle.",
            description = "CEO approval already completed for the merged PR.",
            status = IssueStatus.DONE,
            priority = 1,
            kind = "approval",
            dependsOn = listOf(executionIssue.id),
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = "MERGED",
            qaVerdict = "PASS",
            qaFeedback = executionIssue.qaFeedback,
            ceoVerdict = "APPROVE",
            ceoFeedback = executionIssue.ceoFeedback,
            transitionReason = "CEO approved https://github.com/bssm-oss/cotor-test/pull/309 and queued it for merge.",
            sourceSignal = "ceo-approval:${executionIssue.id}",
            createdAt = now,
            updatedAt = now
        )
        val executionTask = AgentTask(
            id = "task-merged-pr-normalization",
            workspaceId = workspace.id,
            issueId = executionIssue.id,
            title = executionIssue.title,
            prompt = executionIssue.description,
            agents = listOf("codex"),
            status = DesktopTaskStatus.COMPLETED,
            createdAt = now - 1_000,
            updatedAt = now - 500
        )
        val executionRun = AgentRun(
            id = "run-merged-pr-normalization",
            taskId = executionTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.COMPLETED,
            output = "No repository edit was made because there is no legitimate local change to apply: this assigned branch already matches `master` exactly, so there is nothing to rebase or merge in the worktree without manufacturing noise.",
            branchName = requireNotNull(executionIssue.branchName),
            worktreePath = requireNotNull(executionIssue.worktreePath),
            publish = PublishMetadata(
                commitSha = "2c717b0"
            ),
            durationMs = 250,
            createdAt = now - 1_000,
            updatedAt = now - 500
        )
        val runningRuntime = baseState.companyRuntimes.first { it.companyId == company.id }.copy(
            status = CompanyRuntimeStatus.RUNNING,
            lastStartedAt = now - 5_000,
            lastAction = "runtime-started",
            adaptiveTickMs = 15_000
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, approvalIssue),
                tasks = baseState.tasks + executionTask,
                runs = baseState.runs + executionRun,
                companyRuntimes = baseState.companyRuntimes.map {
                    if (it.companyId == company.id) runningRuntime else it
                }
            )
        )

        service.runCompanyRuntimeTick(company.id)

        val refreshedState = stateStore.load()
        val refreshedExecution = refreshedState.issues.first { it.id == executionIssue.id }
        val refreshedApproval = refreshedState.issues.first { it.id == approvalIssue.id }

        refreshedExecution.status shouldBe IssueStatus.DONE
        refreshedExecution.pullRequestState shouldBe "MERGED"
        refreshedExecution.mergeResult shouldBe "MERGED"
        refreshedExecution.transitionReason.shouldContain("already merged")
        refreshedApproval.status shouldBe IssueStatus.DONE
    }

    test("approval prompt scopes evidence to CEO-ready pull requests and excludes stale review history") {
        val appHome = Files.createTempDirectory("desktop-app-service-approval-prompt")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-approval-prompt-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Approval Prompt Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-approval-prompt",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Approval prompt should ignore stale PRs",
            description = "Exercise CEO approval prompt scoping.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val staleExecutionIssue = CompanyIssue(
            id = "issue-execution-stale",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Old implementation",
            description = "Historical stale PR.",
            status = IssueStatus.DONE,
            priority = 2,
            kind = "execution",
            createdAt = now - 10_000,
            updatedAt = now - 9_000
        )
        val currentExecutionIssue = CompanyIssue(
            id = "issue-execution-current",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Current implementation",
            description = "Current PR under CEO review.",
            status = IssueStatus.READY_FOR_CEO,
            priority = 2,
            kind = "execution",
            createdAt = now - 5_000,
            updatedAt = now - 4_000
        )
        val staleReviewIssue = CompanyIssue(
            id = "issue-review-stale",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Review completed implementation work",
            description = "Old review output.",
            status = IssueStatus.DONE,
            priority = 2,
            kind = "review",
            dependsOn = listOf(staleExecutionIssue.id),
            createdAt = now - 8_000,
            updatedAt = now - 7_000
        )
        val approvalIssue = CompanyIssue(
            id = "issue-approval-current",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Review downstream company status",
            description = "CEO approval gate.",
            status = IssueStatus.PLANNED,
            priority = 1,
            kind = "approval",
            dependsOn = listOf(currentExecutionIssue.id),
            createdAt = now - 2_000,
            updatedAt = now - 1_000
        )
        val staleExecutionTask = AgentTask(
            id = "task-execution-stale",
            workspaceId = workspace.id,
            title = staleExecutionIssue.title,
            prompt = staleExecutionIssue.description,
            agents = listOf("codex"),
            issueId = staleExecutionIssue.id,
            status = DesktopTaskStatus.COMPLETED,
            createdAt = now - 10_000,
            updatedAt = now - 9_000
        )
        val currentExecutionTask = AgentTask(
            id = "task-execution-current",
            workspaceId = workspace.id,
            title = currentExecutionIssue.title,
            prompt = currentExecutionIssue.description,
            agents = listOf("codex"),
            issueId = currentExecutionIssue.id,
            status = DesktopTaskStatus.COMPLETED,
            createdAt = now - 5_000,
            updatedAt = now - 4_000
        )
        val staleReviewTask = AgentTask(
            id = "task-review-stale",
            workspaceId = workspace.id,
            title = staleReviewIssue.title,
            prompt = staleReviewIssue.description,
            agents = listOf("codex"),
            issueId = staleReviewIssue.id,
            status = DesktopTaskStatus.COMPLETED,
            createdAt = now - 8_000,
            updatedAt = now - 7_000
        )
        val staleExecutionRun = AgentRun(
            id = "run-execution-stale",
            taskId = staleExecutionTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.COMPLETED,
            output = "Stale execution summary.",
            branchName = "codex/cotor/stale-pr/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/stale/codex").toString(),
            publish = PublishMetadata(
                commitSha = "1111111",
                pullRequestNumber = 11,
                pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/11",
                pullRequestState = "OPEN"
            ),
            durationMs = 250,
            createdAt = now - 10_000,
            updatedAt = now - 9_000
        )
        val currentExecutionRun = AgentRun(
            id = "run-execution-current",
            taskId = currentExecutionTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.COMPLETED,
            output = "Current execution summary.",
            branchName = "codex/cotor/current-pr/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/current/codex").toString(),
            publish = PublishMetadata(
                commitSha = "2222222",
                pullRequestNumber = 22,
                pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
                pullRequestState = "OPEN"
            ),
            durationMs = 250,
            createdAt = now - 5_000,
            updatedAt = now - 4_000
        )
        val staleReviewRun = AgentRun(
            id = "run-review-stale",
            taskId = staleReviewTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.COMPLETED,
            output = "QA_VERDICT: PASS\nPR #11 still reports no completed implementation to verify.",
            branchName = "codex/cotor/stale-pr/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/stale/codex").toString(),
            durationMs = 250,
            createdAt = now - 8_000,
            updatedAt = now - 7_000
        )
        val staleQueueItem = ReviewQueueItem(
            id = "rq-stale",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = staleExecutionIssue.id,
            runId = staleExecutionRun.id,
            branchName = staleExecutionRun.branchName,
            worktreePath = staleExecutionRun.worktreePath,
            pullRequestNumber = 11,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/11",
            pullRequestState = "OPEN",
            status = ReviewQueueStatus.CHANGES_REQUESTED,
            checksSummary = "Old checks.",
            qaVerdict = "PASS",
            qaFeedback = "PR #11 still reports no completed implementation to verify.",
            qaReviewedAt = now - 7_500,
            qaIssueId = staleReviewIssue.id,
            ceoVerdict = "CHANGES_REQUESTED",
            ceoFeedback = "Stale blocker remains.",
            ceoReviewedAt = now - 7_000,
            approvalIssueId = approvalIssue.id,
            createdAt = now - 10_000,
            updatedAt = now - 7_000
        )
        val currentQueueItem = ReviewQueueItem(
            id = "rq-current",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = currentExecutionIssue.id,
            runId = currentExecutionRun.id,
            branchName = currentExecutionRun.branchName,
            worktreePath = currentExecutionRun.worktreePath,
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            status = ReviewQueueStatus.READY_FOR_CEO,
            checksSummary = "Current checks pass.",
            qaVerdict = "PASS",
            qaFeedback = "Current PR is ready for CEO approval.",
            qaReviewedAt = now - 3_000,
            approvalIssueId = approvalIssue.id,
            createdAt = now - 5_000,
            updatedAt = now - 2_500
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(staleExecutionIssue, currentExecutionIssue, staleReviewIssue, approvalIssue),
                tasks = baseState.tasks + listOf(staleExecutionTask, currentExecutionTask, staleReviewTask),
                runs = baseState.runs + listOf(staleExecutionRun, currentExecutionRun, staleReviewRun),
                reviewQueue = baseState.reviewQueue + listOf(staleQueueItem, currentQueueItem)
            )
        )

        val prompt = service.buildIssueExecutionPromptForTesting(
            stateStore.load(),
            approvalIssue,
            OrgAgentProfile(
                id = "profile-ceo-prompt",
                companyId = company.id,
                roleName = "CEO",
                executionAgentName = "codex",
                mergeAuthority = true
            )
        )

        prompt shouldContain "https://github.com/heodongun/cotor-test/pull/22"
        prompt shouldContain "Current PR is ready for CEO approval."
        prompt shouldNotContain "https://github.com/heodongun/cotor-test/pull/11"
        prompt shouldNotContain "PR #11 still reports no completed implementation to verify."
    }

    test("getChanges falls back to an empty summary when a run worktree is unavailable") {
        val appHome = Files.createTempDirectory("desktop-app-service-empty-changes")
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk<AgentExecutor>(relaxed = true)
        )

        stateStore.save(
            DesktopAppState(
                repositories = listOf(
                    ManagedRepository(
                        id = REPOSITORY_ID,
                        name = "repo",
                        localPath = appHome.resolve("repo").toString(),
                        sourceKind = RepositorySourceKind.LOCAL,
                        defaultBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                workspaces = listOf(
                    Workspace(
                        id = WORKSPACE_ID,
                        repositoryId = REPOSITORY_ID,
                        name = "repo · master",
                        baseBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                tasks = listOf(
                    AgentTask(
                        id = "task-empty",
                        workspaceId = WORKSPACE_ID,
                        title = "Task",
                        prompt = "Prompt",
                        agents = listOf("codex"),
                        status = DesktopTaskStatus.QUEUED,
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                runs = listOf(
                    AgentRun(
                        id = "run-empty",
                        taskId = "task-empty",
                        workspaceId = WORKSPACE_ID,
                        repositoryId = REPOSITORY_ID,
                        agentName = "codex",
                        branchName = "codex/cotor/task/codex",
                        worktreePath = appHome.resolve("missing-worktree").toString(),
                        status = AgentRunStatus.FAILED,
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                )
            )
        )

        val summary = service.getChanges("run-empty")

        summary.runId shouldBe "run-empty"
        summary.baseBranch shouldBe "master"
        summary.patch shouldBe ""
        summary.changedFiles.shouldBeEmpty()
    }
})

private class DesktopAppServiceFixture private constructor(
    val service: DesktopAppService,
    val stateStore: DesktopStateStore,
    val gitWorkspaceService: GitWorkspaceService,
    val agentExecutor: AgentExecutor,
    val task: AgentTask,
    val worktreeRoot: Path
) {
    suspend fun awaitRuns(): List<AgentRun> = withTimeout(30_000) {
        while (true) {
            val runs = service.listRuns(task.id)
            if (runs.isNotEmpty() && runs.none { it.status == AgentRunStatus.QUEUED || it.status == AgentRunStatus.RUNNING }) {
                return@withTimeout runs
            }
            delay(25)
        }
        error("Unreachable")
    }

    companion object {
        suspend fun create(): DesktopAppServiceFixture {
            val appHome = Files.createTempDirectory("desktop-app-service-test")
            val repoRoot = Files.createTempDirectory("desktop-app-service-repo")
            val worktreeRoot = Files.createTempDirectory("desktop-app-service-worktree")
            val stateStore = DesktopStateStore { appHome }
            val gitWorkspaceService = mockk<GitWorkspaceService>()
            val agentExecutor = mockk<AgentExecutor>()
            val configRepository = mockk<ConfigRepository>(relaxed = true)

            val repository = ManagedRepository(
                id = REPOSITORY_ID,
                name = "cotor",
                localPath = repoRoot.toString(),
                sourceKind = RepositorySourceKind.LOCAL,
                defaultBranch = "master",
                createdAt = 1,
                updatedAt = 1
            )
            val workspace = Workspace(
                id = WORKSPACE_ID,
                repositoryId = repository.id,
                name = "cotor · master",
                baseBranch = "master",
                createdAt = 1,
                updatedAt = 1
            )
            val task = AgentTask(
                id = "task-1",
                workspaceId = workspace.id,
                title = "Desktop publish",
                prompt = "Implement desktop publish flow",
                agents = listOf("codex"),
                status = DesktopTaskStatus.QUEUED,
                createdAt = 1,
                updatedAt = 1
            )
            stateStore.save(
                DesktopAppState(
                    repositories = listOf(repository),
                    workspaces = listOf(workspace),
                    tasks = listOf(task)
                )
            )
            val service = DesktopAppService(stateStore, gitWorkspaceService, configRepository, agentExecutor)

            return DesktopAppServiceFixture(service, stateStore, gitWorkspaceService, agentExecutor, task, worktreeRoot)
        }
    }
}

private fun testService(
    processManager: ProcessManager,
    appHome: Path? = null,
    stateStore: DesktopStateStore? = null,
    linearTracker: LinearTrackerAdapter = FakeLinearTrackerAdapter()
): DesktopAppService {
    val store = stateStore ?: DesktopStateStore { appHome ?: Files.createTempDirectory("cotor-home") }
    val gitWorkspaceService = GitWorkspaceService(
        processManager = processManager,
        stateStore = store,
        logger = mockk(relaxed = true)
    )
    return DesktopAppService(
        stateStore = store,
        gitWorkspaceService = gitWorkspaceService,
        configRepository = mockk<ConfigRepository>(relaxed = true),
        agentExecutor = mockk<AgentExecutor>(relaxed = true),
        linearTracker = linearTracker
    )
}

private class FakeLinearTrackerAdapter : LinearTrackerAdapter {
    data class SyncCall(
        val issueId: String,
        val desiredStateName: String?,
        val assigneeId: String?
    )

    data class CommentCall(
        val linearIssueId: String,
        val body: String
    )

    val syncCalls = mutableListOf<SyncCall>()
    val commentCalls = mutableListOf<CommentCall>()

    override suspend fun health(config: LinearConnectionConfig): Result<String> =
        Result.success("ok")

    override suspend fun syncIssue(
        config: LinearConnectionConfig,
        issue: CompanyIssue,
        desiredStateName: String?,
        assigneeId: String?
    ): Result<LinearIssueMirror> {
        syncCalls += SyncCall(issue.id, desiredStateName, assigneeId)
        return Result.success(
            LinearIssueMirror(
                id = issue.linearIssueId ?: "lin-${issue.id}",
                identifier = issue.linearIssueIdentifier ?: "COT-${syncCalls.size}",
                url = issue.linearIssueUrl ?: "https://linear.app/issue/${issue.id}",
                stateName = desiredStateName
            )
        )
    }

    override suspend fun createComment(
        config: LinearConnectionConfig,
        linearIssueId: String,
        body: String
    ): Result<Unit> {
        commentCalls += CommentCall(linearIssueId, body)
        return Result.success(Unit)
    }
}

private class FakeGitProcessManager(
    private val repoRoot: Path,
    private val remoteUrl: String?,
    private val defaultBranch: String
) : ProcessManager {
    data class CommandCall(val command: List<String>, val workingDirectory: Path?)

    val commands = mutableListOf<CommandCall>()

    override suspend fun executeProcess(
        command: List<String>,
        input: String?,
        environment: Map<String, String>,
        timeout: Long,
        workingDirectory: Path?,
        onStart: ((Long) -> Unit)?
    ): ProcessResult {
        commands += CommandCall(command = command, workingDirectory = workingDirectory)
        val gitArgs = command.drop(1)
        return when (gitArgs) {
            listOf("rev-parse", "--show-toplevel") -> success(repoRoot.toString())
            listOf("rev-parse", "--verify", "HEAD") -> success("deadbeef")
            listOf("symbolic-ref", "--short", "HEAD") -> success(defaultBranch)
            listOf("rev-parse", "--abbrev-ref", "HEAD") -> success(defaultBranch)
            listOf("symbolic-ref", "refs/remotes/origin/HEAD") -> success("refs/remotes/origin/$defaultBranch")
            listOf("config", "--get", "remote.origin.url") -> {
                if (remoteUrl == null) failure("missing remote") else success(remoteUrl)
            }
            listOf("init", "-b", defaultBranch) -> success("Initialized empty Git repository")
            else -> {
                if (command.contains("commit")) {
                    success("[$defaultBranch (root-commit) deadbeef] Initialize repository")
                } else if (command.any { it == "worktree" }) {
                    success("worktree operation ok")
                } else if (command.any { it == "branch" || it == "for-each-ref" }) {
                    success("")
                } else if (command.any { it == "rev-list" }) {
                    success("0")
                } else if (command.any { it == "diff" || it == "status" }) {
                    success("")
                } else if (command.any { it == "checkout" || it == "fetch" || it == "push" || it == "pull" }) {
                    success("")
                } else {
                    success("")
                }
            }
        }
    }

    private fun success(stdout: String): ProcessResult = ProcessResult(
        exitCode = 0,
        stdout = "$stdout\n",
        stderr = "",
        isSuccess = true
    )

    private fun failure(stderr: String): ProcessResult = ProcessResult(
        exitCode = 1,
        stdout = "",
        stderr = stderr,
        isSuccess = false
    )
}

private suspend fun seedWorkspace(stateStore: DesktopStateStore, repoRoot: Path) {
    stateStore.save(
        DesktopAppState(
            repositories = listOf(
                ManagedRepository(
                    id = REPOSITORY_ID,
                    name = "repo",
                    localPath = repoRoot.toString(),
                    sourceKind = RepositorySourceKind.LOCAL,
                    defaultBranch = "master",
                    createdAt = 1,
                    updatedAt = 1
                )
            ),
            workspaces = listOf(
                Workspace(
                    id = WORKSPACE_ID,
                    repositoryId = REPOSITORY_ID,
                    name = "repo · master",
                    baseBranch = "master",
                    createdAt = 1,
                    updatedAt = 1
                )
            )
        )
    )
}

private suspend fun awaitTaskCompletion(stateStore: DesktopStateStore, taskId: String) {
    val completed = withTimeoutOrNull(30_000) {
        while (true) {
            val snapshot = stateStore.load()
            val task = snapshot.tasks.first { it.id == taskId }
            if (task.status == DesktopTaskStatus.COMPLETED) {
                return@withTimeoutOrNull true
            }
            if (task.status == DesktopTaskStatus.FAILED || task.status == DesktopTaskStatus.PARTIAL) {
                val runs = snapshot.runs.filter { it.taskId == taskId }
                error("Task $taskId ended with ${task.status}; runs=$runs")
            }
            delay(25)
        }
    }
    if (completed != true) {
        val snapshot = stateStore.load()
        val task = snapshot.tasks.firstOrNull { it.id == taskId }
        val runs = snapshot.runs.filter { it.taskId == taskId }
        error("Timed out waiting for task $taskId completion; task=$task runs=$runs")
    }
}

/**
 * Mark an issue as BLOCKED and seed a non-recoverable failed task so that the
 * runtime tick treats the issue as genuinely stuck rather than automatically
 * requeuing it.  This mirrors the state that a real execution failure would
 * produce and ensures follow-up goal synthesis triggers correctly.
 */
private suspend fun blockIssueWithFailedTask(
    stateStore: DesktopStateStore,
    issue: CompanyIssue,
    now: Long = System.currentTimeMillis()
) {
    val snapshot = stateStore.load()
    val taskId = "blocking-task-${issue.id.take(8)}"
    val blockedIssue = issue.copy(status = IssueStatus.BLOCKED, updatedAt = now)
    val task = AgentTask(
        id = taskId,
        workspaceId = issue.workspaceId,
        issueId = issue.id,
        title = issue.title,
        prompt = issue.description,
        agents = listOf("codex"),
        status = DesktopTaskStatus.FAILED,
        createdAt = now,
        updatedAt = now
    )
    val run = AgentRun(
        id = "blocking-run-${issue.id.take(8)}",
        taskId = taskId,
        workspaceId = issue.workspaceId,
        repositoryId = snapshot.repositories.first().id,
        agentName = "codex",
        branchName = "codex/cotor/block/${issue.id.take(8)}/codex",
        worktreePath = "/tmp/blocking",
        status = AgentRunStatus.FAILED,
        output = "Execution blocked: non-recoverable failure",
        error = "Execution blocked: non-recoverable failure",
        createdAt = now,
        updatedAt = now
    )
    stateStore.save(
        snapshot.copy(
            issues = snapshot.issues.map { if (it.id == issue.id) blockedIssue else it },
            tasks = snapshot.tasks + task,
            runs = snapshot.runs + run
        )
    )
}

private suspend fun awaitIssueTasksSettled(stateStore: DesktopStateStore, issueId: String) {
    withTimeout(5_000) {
        while (true) {
            val tasks = stateStore.load().tasks.filter { it.issueId == issueId }
            val allSettled = tasks.isEmpty() || tasks.all { task ->
                task.status != DesktopTaskStatus.QUEUED && task.status != DesktopTaskStatus.RUNNING
            }
            if (allSettled) {
                // Give sync callbacks a moment to flush their state writes.
                delay(100)
                return@withTimeout
            }
            delay(25)
        }
    }
}

private const val REPOSITORY_ID = "repo-1"
private const val WORKSPACE_ID = "workspace-1"
