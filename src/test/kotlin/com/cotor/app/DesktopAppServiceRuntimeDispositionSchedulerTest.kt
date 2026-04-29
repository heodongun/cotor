package com.cotor.app

import com.cotor.app.runtime.CompanyRuntimeBindingService
import com.cotor.data.config.ConfigRepository
import com.cotor.domain.executor.AgentExecutor
import com.cotor.model.AgentResult
import com.cotor.policy.PolicyEngine
import com.cotor.policy.PolicyStore
import com.cotor.providers.github.GitHubControlPlaneService
import com.cotor.providers.github.GitHubControlPlaneStore
import com.cotor.providers.github.PullRequestSnapshot
import com.cotor.runtime.actions.ActionStore
import com.cotor.runtime.durable.DurableRuntimeService
import com.cotor.runtime.durable.DurableRuntimeStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import java.nio.file.Files

class DesktopAppServiceRuntimeDispositionSchedulerTest : FunSpec({
    afterTest {
        DesktopAppService.shutdownAllForTesting()
    }

    test("runCompanyRuntimeTick does not start issues whose projected runtimeDisposition is not RUNNABLE") {
        val appHome = Files.createTempDirectory("desktop-runtime-disposition-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-disposition-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedRuntimeDispositionWorkspace(stateStore, repoRoot)
        val githubStore = GitHubControlPlaneStore { appHome }
        val companyId = "company-1"
        val issueId = "issue-waiting-ci"
        GitHubControlPlaneService(store = githubStore).recordSnapshot(
            PullRequestSnapshot(
                number = 12,
                state = "OPEN",
                checksSummary = "ci=COMPLETED/FAILURE",
                companyId = companyId,
                issueId = issueId,
                runId = null
            ),
            eventType = "sync",
            detail = "sync"
        )

        val gitWorkspaceService = mockk<GitWorkspaceService>()
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "main"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns "https://github.com/example/cotor.git"

        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk<AgentExecutor>(relaxed = true),
            runtimeBindingService = CompanyRuntimeBindingService(
                durableRuntimeService = DurableRuntimeService(runtimeStore = DurableRuntimeStore(appHome.resolve("runtime"))),
                actionStore = ActionStore { appHome },
                policyEngine = PolicyEngine(PolicyStore { appHome }),
                gitHubControlPlaneService = GitHubControlPlaneService(store = githubStore)
            ),
            gitHubControlPlaneService = GitHubControlPlaneService(store = githubStore)
        )

        val state = stateStore.load()
        stateStore.save(
            state.copy(
                companies = listOf(
                    Company(
                        id = companyId,
                        name = "Runtime Co",
                        rootPath = repoRoot.toString(),
                        repositoryId = "repo-1",
                        defaultBaseBranch = "main",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                goals = listOf(
                    CompanyGoal(
                        id = "goal-1",
                        companyId = companyId,
                        projectContextId = "project-1",
                        title = "Goal",
                        description = "desc",
                        status = GoalStatus.ACTIVE,
                        autonomyEnabled = true,
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                issues = listOf(
                    CompanyIssue(
                        id = issueId,
                        companyId = companyId,
                        projectContextId = "project-1",
                        goalId = "goal-1",
                        workspaceId = "workspace-1",
                        title = "Blocked by CI",
                        description = "desc",
                        status = IssueStatus.PLANNED,
                        pullRequestNumber = 12,
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(companyId = companyId, status = CompanyRuntimeStatus.RUNNING)
                )
            )
        )

        val snapshot = service.runCompanyRuntimeTick(companyId)

        snapshot.lastAction shouldBe "idle-pending-issues"
        stateStore.load().tasks.shouldBeEmpty()
    }

    test("runCompanyRuntimeTick starts existing runnable issues when goal autonomy is disabled") {
        val appHome = Files.createTempDirectory("desktop-runtime-manual-issue-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-manual-issue-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedRuntimeDispositionWorkspace(stateStore, repoRoot)
        val companyId = "company-manual"
        val issueId = "issue-existing-manual"

        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } returns WorktreeBinding(
            branchName = "codex/cotor/manual-issue/opencode",
            worktreePath = repoRoot.resolve(".cotor/worktrees/manual-issue/opencode")
        )
        coEvery { gitWorkspaceService.publishRun(any(), any(), any(), any(), any(), any()) } returns PublishMetadata()

        val agentExecutor = mockk<AgentExecutor>()
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } coAnswers {
            delay(500)
            AgentResult(
                agentName = "opencode",
                isSuccess = true,
                output = "manual issue finished",
                error = null,
                duration = 500,
                metadata = emptyMap()
            )
        }

        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor
        )

        val state = stateStore.load()
        stateStore.save(
            state.copy(
                backendSettings = state.backendSettings.copy(codePublishMode = CodePublishMode.ALLOW_LOCAL_GIT),
                companies = listOf(
                    Company(
                        id = companyId,
                        name = "Manual Runtime Co",
                        rootPath = repoRoot.toString(),
                        repositoryId = "repo-1",
                        defaultBaseBranch = "main",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                goals = listOf(
                    CompanyGoal(
                        id = "goal-manual",
                        companyId = companyId,
                        projectContextId = "project-1",
                        title = "Manual goal",
                        description = "Existing runnable work should run even when new autonomous planning is off.",
                        status = GoalStatus.ACTIVE,
                        autonomyEnabled = false,
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                issues = listOf(
                    CompanyIssue(
                        id = issueId,
                        companyId = companyId,
                        projectContextId = "project-1",
                        goalId = "goal-manual",
                        workspaceId = "workspace-1",
                        title = "Existing manual issue",
                        description = "Run this issue.",
                        status = IssueStatus.PLANNED,
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(companyId = companyId, status = CompanyRuntimeStatus.RUNNING)
                )
            )
        )

        val snapshot = service.runCompanyRuntimeTick(companyId)
        val refreshed = stateStore.load()

        snapshot.lastAction shouldBe "started:$issueId"
        refreshed.tasks.shouldNotBeEmpty()
        refreshed.issues.first { it.id == issueId }.status shouldBe IssueStatus.IN_PROGRESS
        coVerify(timeout = 2_000, exactly = 1) { agentExecutor.executeAgent(any(), any(), any()) }
    }
})

private suspend fun seedRuntimeDispositionWorkspace(stateStore: DesktopStateStore, repoRoot: java.nio.file.Path) {
    stateStore.save(
        DesktopAppState(
            repositories = listOf(
                ManagedRepository(
                    id = "repo-1",
                    name = "repo",
                    localPath = repoRoot.toString(),
                    sourceKind = RepositorySourceKind.LOCAL,
                    defaultBranch = "main",
                    createdAt = 1,
                    updatedAt = 1
                )
            ),
            workspaces = listOf(
                Workspace(
                    id = "workspace-1",
                    repositoryId = "repo-1",
                    name = "repo · main",
                    baseBranch = "main",
                    createdAt = 1,
                    updatedAt = 1
                )
            ),
            projectContexts = listOf(
                CompanyProjectContext(
                    id = "project-1",
                    companyId = "company-1",
                    name = "Project",
                    slug = "project",
                    contextDocPath = repoRoot.resolve("PROJECT.md").toString(),
                    lastUpdatedAt = 1
                )
            )
        )
    )
}
