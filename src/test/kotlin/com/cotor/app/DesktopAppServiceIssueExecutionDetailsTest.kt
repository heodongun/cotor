package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.domain.executor.AgentExecutor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import java.nio.file.Files

class DesktopAppServiceIssueExecutionDetailsTest : FunSpec({
    test("issueExecutionDetails joins assigned prompt, run logs, and publish metadata for one issue") {
        val appHome = Files.createTempDirectory("desktop-issue-execution-details-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-issue-execution-details-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedIssueExecutionWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "main"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk<AgentExecutor>(relaxed = true)
        )

        val company = service.createCompany(
            name = "Issue Execution Details Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "main"
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
            baseBranch = "main",
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
        stateStore.save(stateStore.load().copy(runs = stateStore.load().runs + run))

        val details = service.issueExecutionDetails(issue.id)

        details shouldHaveSize 1
        details.single().roleName.isNotBlank() shouldBe true
        details.single().agentCli shouldBe "opencode"
        details.single().assignedPrompt shouldContain "Implement the first branchable slice."
        details.single().stdout shouldContain "Implemented the slice"
        details.single().publishSummary shouldContain "pr state: OPEN"
        details.single().pullRequestUrl shouldBe "https://github.com/bssm-oss/cotor-test/pull/88"
    }

    test("issueExecutionDetails keeps one detail per latest run per agent") {
        val appHome = Files.createTempDirectory("desktop-issue-execution-fanout-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-issue-execution-fanout-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedIssueExecutionWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "main"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk<AgentExecutor>(relaxed = true)
        )

        val company = service.createCompany(
            name = "Issue Execution Fanout Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "main"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Fan out a traceable slice",
            description = "Generate per-agent execution details.",
            autonomyEnabled = false
        )
        val issue = service.listIssues(goal.id).first { it.kind == "execution" }
        val workspace = stateStore.load().workspaces.first { it.id == issue.workspaceId }
        val task = service.createTask(
            workspaceId = workspace.id,
            title = issue.title,
            prompt = "Implement the fan-out slice.",
            agents = listOf("opencode", "codex"),
            issueId = issue.id
        )
        val now = System.currentTimeMillis()
        val olderOpencodeRun = AgentRun(
            id = "run-old-opencode",
            taskId = task.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "opencode",
            repoRoot = repoRoot.toString(),
            baseBranch = "main",
            branchName = "opencode/older",
            worktreePath = repoRoot.resolve(".cotor/worktrees/older/opencode").toString(),
            status = AgentRunStatus.COMPLETED,
            output = "Older output",
            createdAt = now - 5_000,
            updatedAt = now - 5_000
        )
        val latestOpencodeRun = olderOpencodeRun.copy(
            id = "run-latest-opencode",
            output = "Latest opencode output",
            updatedAt = now - 1_000,
            createdAt = now - 1_000
        )
        val codexRun = AgentRun(
            id = "run-codex",
            taskId = task.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "main",
            branchName = "codex/latest",
            worktreePath = repoRoot.resolve(".cotor/worktrees/latest/codex").toString(),
            status = AgentRunStatus.COMPLETED,
            output = "Latest codex output",
            createdAt = now,
            updatedAt = now
        )
        stateStore.save(stateStore.load().copy(runs = stateStore.load().runs + listOf(olderOpencodeRun, latestOpencodeRun, codexRun)))

        val details = service.issueExecutionDetails(issue.id).sortedBy { it.agentName }

        details shouldHaveSize 2
        details[0].agentName shouldBe "codex"
        details[0].agentCli shouldBe "codex"
        details[0].stdout shouldContain "Latest codex output"
        details[1].agentName shouldBe "opencode"
        details[1].agentCli shouldBe "opencode"
        details[1].stdout shouldContain "Latest opencode output"
        details.none { it.stdout?.contains("Older output") == true } shouldBe true
    }
})

private suspend fun seedIssueExecutionWorkspace(stateStore: DesktopStateStore, repoRoot: java.nio.file.Path) {
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
            )
        )
    )
}
