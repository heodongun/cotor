package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.domain.executor.AgentExecutor
import com.cotor.model.AgentResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Files

class DesktopAppServiceTest : FunSpec({
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
            fixture.gitWorkspaceService.publishRun(any(), any(), any(), any(), any())
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
            fixture.gitWorkspaceService.publishRun(any(), any(), any(), any(), any())
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
})

private class DesktopAppServiceFixture private constructor(
    val service: DesktopAppService,
    val stateStore: DesktopStateStore,
    val gitWorkspaceService: GitWorkspaceService,
    val agentExecutor: AgentExecutor,
    val task: AgentTask,
    val worktreeRoot: java.nio.file.Path
) {
    suspend fun awaitRuns(): List<AgentRun> = withTimeout(5_000) {
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
            val service = DesktopAppService(stateStore, gitWorkspaceService, configRepository, agentExecutor)

            val repository = ManagedRepository(
                id = "repo-1",
                name = "cotor",
                localPath = repoRoot.toString(),
                sourceKind = RepositorySourceKind.LOCAL,
                defaultBranch = "master",
                createdAt = 1,
                updatedAt = 1
            )
            val workspace = Workspace(
                id = "ws-1",
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

            return DesktopAppServiceFixture(service, stateStore, gitWorkspaceService, agentExecutor, task, worktreeRoot)
        }
    }
}
