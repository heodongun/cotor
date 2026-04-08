package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.domain.executor.AgentExecutor
import com.cotor.model.AgentConfig
import com.cotor.model.AgentResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Files

class DesktopAppServiceDashboardRuntimeTest : FunSpec({
    test("company dashboard keeps manually stopped autonomous runtimes stopped") {
        val appHome = Files.createTempDirectory("desktop-dashboard-runtime-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-dashboard-runtime-test").resolve("repo"))
        val worktreeRoot = Files.createDirectories(Files.createTempDirectory("desktop-dashboard-runtime-worktree"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val agentExecutor = mockk<AgentExecutor>()
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns "https://github.com/heodongun/cotor.git"
        coEvery { gitWorkspaceService.ensureGitHubPublishReady(any(), any()) } returns GitHubPublishReadiness(ready = true)
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } answers {
            val agentName = invocation.args[3] as String
            WorktreeBinding(
                branchName = "codex/cotor/dashboard-runtime/$agentName",
                worktreePath = worktreeRoot.resolve(agentName)
            )
        }
        coEvery { gitWorkspaceService.publishRun(any(), any(), any(), any(), any()) } returns PublishMetadata()
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } answers {
            val agent = invocation.args[0] as AgentConfig
            AgentResult(
                agentName = agent.name,
                isSuccess = true,
                output = "done",
                error = null,
                duration = 25,
                metadata = emptyMap(),
                processId = 6001
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

        val company = service.createCompany(
            name = "Dashboard Runtime Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        service.createGoal(
            companyId = company.id,
            title = "Resume on dashboard",
            description = "Opening the company dashboard should revive autonomous execution.",
            autonomyEnabled = true,
            startRuntimeIfNeeded = false
        )
        service.stopCompanyRuntime(company.id)
        val taskIdsBeforeDashboard = stateStore.load().tasks.filter { it.issueId != null }.map { it.id }.toSet()

        service.companyDashboard(company.id)
        delay(150)

        val runtime = service.runtimeStatus(company.id)
        runtime.status shouldBe CompanyRuntimeStatus.STOPPED
        runtime.manuallyStoppedAt shouldNotBe null
        stateStore.load().tasks.filter { it.issueId != null }.map { it.id }.toSet() shouldBe taskIdsBeforeDashboard
    }

    test("company dashboard re-ticks autonomous companies when pending issues are idle") {
        val appHome = Files.createTempDirectory("desktop-dashboard-idle-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-dashboard-idle-test").resolve("repo"))
        val worktreeRoot = Files.createDirectories(Files.createTempDirectory("desktop-dashboard-idle-worktree"))
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
                branchName = "codex/cotor/dashboard-idle/$agentName",
                worktreePath = worktreeRoot.resolve(agentName)
            )
        }
        coEvery { gitWorkspaceService.publishRun(any(), any(), any(), any(), any()) } returns PublishMetadata()
        coEvery { gitWorkspaceService.ensureGitHubPublishReady(any(), any()) } returns GitHubPublishReadiness(ready = true)
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } answers {
            val agent = invocation.args[0] as AgentConfig
            AgentResult(
                agentName = agent.name,
                isSuccess = true,
                output = "done",
                error = null,
                duration = 25,
                metadata = emptyMap(),
                processId = 6101
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

        val company = service.createCompany(
            name = "Dashboard Idle Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val assigneeProfile = service.listOrgProfiles().first { it.companyId == company.id && it.enabled }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-dashboard-idle",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Recover idle work",
            description = "The dashboard should resume pending autonomous work.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val pendingIssue = CompanyIssue(
            id = "issue-dashboard-idle",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Resume delegated execution",
            description = "Delegated issue left idle.",
            status = IssueStatus.DELEGATED,
            priority = 1,
            kind = "execution",
            assigneeProfileId = assigneeProfile.id,
            createdAt = now,
            updatedAt = now - 10_000
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + pendingIssue,
                companyRuntimes = baseState.companyRuntimes.map {
                    if (it.companyId == company.id) {
                        it.copy(
                            status = CompanyRuntimeStatus.RUNNING,
                            lastTickAt = now - 10_000,
                            lastAction = "stale-running-state",
                            manuallyStoppedAt = null
                        )
                    } else {
                        it
                    }
                }
            )
        )

        service.companyDashboard(company.id)

        withTimeout(30_000) {
            while (stateStore.load().tasks.none { it.issueId == pendingIssue.id }) {
                service.companyDashboard(company.id)
                delay(100)
            }
        }

        stateStore.load().tasks.filter { it.issueId == pendingIssue.id }.map { it.issueId }.distinct() shouldContainExactly listOf(pendingIssue.id)
    }
})
