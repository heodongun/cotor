package com.cotor.app

import com.cotor.app.runtime.CompanyRuntimeBindingService
import com.cotor.data.config.ConfigRepository
import com.cotor.domain.executor.AgentExecutor
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
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
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
