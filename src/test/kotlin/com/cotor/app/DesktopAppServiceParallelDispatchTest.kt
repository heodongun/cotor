package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.domain.executor.AgentExecutor
import com.cotor.model.AgentConfig
import com.cotor.model.AgentResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Files

class DesktopAppServiceParallelDispatchTest : FunSpec({
    test("runtime starts multiple runnable issues even when assignees share the same execution cli") {
        val appHome = Files.createTempDirectory("desktop-runtime-shared-cli-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-shared-cli-repo").resolve("repo"))
        val worktreeRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-shared-cli-worktrees"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val agentExecutor = mockk<AgentExecutor>()
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns "https://github.com/heodongun/cotor.git"
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } answers {
            val taskId = invocation.args[1] as String
            val agentName = invocation.args[3] as String
            WorktreeBinding(
                branchName = "codex/cotor/shared-cli/$taskId/$agentName",
                worktreePath = worktreeRoot.resolve("$taskId-$agentName")
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
                processId = 777
            )
        }

        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            companyRuntimeTickIntervalMs = 50,
            commandAvailability = { command -> command == "opencode" }
        )

        try {
            val company = service.createCompany(
                name = "Shared CLI Co",
                rootPath = repoRoot.toString(),
                defaultBaseBranch = "master"
            )
            val seededAgents = service.listCompanyAgentDefinitions(company.id)
            service.batchUpdateCompanyAgentDefinitions(
                companyId = company.id,
                agentIds = seededAgents.take(2).map { it.id },
                agentCli = "opencode",
                model = "opencode/qwen3.6-plus-free"
            )

            val state = stateStore.load()
            val workspace = state.workspaces.first { it.repositoryId == company.repositoryId }
            val projectContext = state.projectContexts.first { it.companyId == company.id }
            val profiles = service.listOrgProfiles().filter { it.companyId == company.id && it.enabled }.take(2)
            profiles shouldHaveSize 2
            profiles.all { it.executionAgentName == "opencode" } shouldBe true

            val now = System.currentTimeMillis()
            val goal = CompanyGoal(
                id = "goal-shared-cli",
                companyId = company.id,
                projectContextId = projectContext.id,
                title = "Parallel shared-cli execution",
                description = "Start two runnable issues on the same execution CLI.",
                status = GoalStatus.ACTIVE,
                autonomyEnabled = true,
                createdAt = now,
                updatedAt = now
            )
            val issueOne = CompanyIssue(
                id = "issue-shared-cli-1",
                companyId = company.id,
                projectContextId = projectContext.id,
                goalId = goal.id,
                workspaceId = workspace.id,
                title = "Issue One",
                description = "First parallel issue.",
                status = IssueStatus.DELEGATED,
                priority = 1,
                kind = "execution",
                assigneeProfileId = profiles[0].id,
                createdAt = now,
                updatedAt = now
            )
            val issueTwo = CompanyIssue(
                id = "issue-shared-cli-2",
                companyId = company.id,
                projectContextId = projectContext.id,
                goalId = goal.id,
                workspaceId = workspace.id,
                title = "Issue Two",
                description = "Second parallel issue.",
                status = IssueStatus.DELEGATED,
                priority = 1,
                kind = "execution",
                assigneeProfileId = profiles[1].id,
                createdAt = now,
                updatedAt = now
            )
            stateStore.save(
                state.copy(
                    goals = state.goals + goal,
                    issues = state.issues + listOf(issueOne, issueTwo)
                )
            )

            service.startCompanyRuntime(company.id)
            service.runCompanyRuntimeTick(company.id)

            withTimeout(5_000) {
                while (true) {
                    val current = stateStore.load()
                    val startedTasks = current.tasks.filter { it.issueId in setOf(issueOne.id, issueTwo.id) }
                    if (startedTasks.size == 2) {
                        return@withTimeout
                    }
                    delay(25)
                }
            }

            val finalState = stateStore.load()
            finalState.tasks.filter { it.issueId in setOf(issueOne.id, issueTwo.id) }.size shouldBe 2
            finalState.issues
                .filter { it.id in setOf(issueOne.id, issueTwo.id) }
                .all { issue -> issue.status != IssueStatus.DELEGATED && issue.status != IssueStatus.PLANNED && issue.status != IssueStatus.BACKLOG } shouldBe true
        } finally {
            service.shutdown()
        }
    }
})
