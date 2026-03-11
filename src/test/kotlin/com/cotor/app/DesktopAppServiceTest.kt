package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.domain.executor.AgentExecutor
import com.cotor.model.AgentExecutionMetadata
import com.cotor.model.AgentResult
import com.cotor.model.CotorConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

class DesktopAppServiceTest : FunSpec({
    test("createTask persists deterministic lead/worker assignment plan") {
        val stateDir = Files.createTempDirectory("desktop-state")
        val repoDir = Files.createTempDirectory("desktop-repo")
        val stateStore = DesktopStateStore { stateDir }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val configRepository = mockk<ConfigRepository>()
        val agentExecutor = mockk<AgentExecutor>()
        val service = DesktopAppService(stateStore, gitWorkspaceService, configRepository, agentExecutor)

        val now = System.currentTimeMillis()
        val repository = ManagedRepository(
            id = "repo-1",
            name = "repo",
            localPath = repoDir.toString(),
            sourceKind = RepositorySourceKind.LOCAL,
            defaultBranch = "main",
            createdAt = now,
            updatedAt = now
        )
        val workspace = Workspace(
            id = "ws-1",
            repositoryId = repository.id,
            name = "workspace",
            baseBranch = "main",
            createdAt = now,
            updatedAt = now
        )
        runBlocking {
            stateStore.save(DesktopAppState(repositories = listOf(repository), workspaces = listOf(workspace)))
        }

        val task = runBlocking {
            service.createTask(
                workspaceId = workspace.id,
                title = "Orchestrate feature",
                prompt = "Implement autonomous decomposition",
                agents = listOf("codex", "claude")
            )
        }

        task.leadAgent shouldBe "codex"
        task.assignments.shouldHaveSize(2)
        task.assignments[0].role shouldBe TaskAssignmentRole.LEAD
        task.assignments[0].agentName shouldBe "codex"
        task.assignments[1].role shouldBe TaskAssignmentRole.WORKER
        task.assignments[1].agentName shouldBe "claude"
        task.assignments[1].prompt.contains("lead agent 'codex'") shouldBe true
    }

    test("runTask executes persisted assignment prompts and records run assignment metadata") {
        val stateDir = Files.createTempDirectory("desktop-state")
        val repoDir = Files.createTempDirectory("desktop-repo")
        val stateStore = DesktopStateStore { stateDir }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val configRepository = mockk<ConfigRepository>()
        val agentExecutor = mockk<AgentExecutor>()
        val service = DesktopAppService(stateStore, gitWorkspaceService, configRepository, agentExecutor)

        val now = System.currentTimeMillis()
        val repository = ManagedRepository(
            id = "repo-1",
            name = "repo",
            localPath = repoDir.toString(),
            sourceKind = RepositorySourceKind.LOCAL,
            defaultBranch = "main",
            createdAt = now,
            updatedAt = now
        )
        val workspace = Workspace(
            id = "ws-1",
            repositoryId = repository.id,
            name = "workspace",
            baseBranch = "main",
            createdAt = now,
            updatedAt = now
        )
        val assignments = listOf(
            TaskAssignment(id = "task-1:1", role = TaskAssignmentRole.LEAD, agentName = "codex", prompt = "lead prompt", order = 0),
            TaskAssignment(id = "task-1:2", role = TaskAssignmentRole.WORKER, agentName = "claude", prompt = "worker prompt", order = 1)
        )
        val task = AgentTask(
            id = "task-1",
            workspaceId = workspace.id,
            title = "Task",
            prompt = "base prompt",
            agents = listOf("codex", "claude"),
            leadAgent = "codex",
            assignments = assignments,
            status = DesktopTaskStatus.QUEUED,
            createdAt = now,
            updatedAt = now
        )
        runBlocking {
            stateStore.save(
                DesktopAppState(
                    repositories = listOf(repository),
                    workspaces = listOf(workspace),
                    tasks = listOf(task)
                )
            )
        }

        val seenInputs = mutableListOf<String?>()
        val seenMetadata = mutableListOf<AgentExecutionMetadata>()
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } answers {
            val agentName = arg<String>(3)
            WorktreeBinding(branchName = "branch-$agentName", worktreePath = repoDir.resolve(agentName))
        }
        coEvery { configRepository.loadConfig(any()) } returns CotorConfig()
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } answers {
            seenInputs += secondArg<String?>()
            seenMetadata += thirdArg<AgentExecutionMetadata>()
            AgentResult(
                agentName = firstArg<com.cotor.model.AgentConfig>().name,
                isSuccess = true,
                output = "done",
                error = null,
                duration = 1,
                metadata = emptyMap()
            )
        }

        runBlocking {
            service.runTask(task.id)
            val deadline = System.currentTimeMillis() + 10.seconds.inWholeMilliseconds
            while (service.listRuns(task.id).size < 2 && System.currentTimeMillis() < deadline) {
                delay(50)
            }
        }

        seenInputs shouldBe listOf("lead prompt", "worker prompt")
        seenMetadata.map { it.agentId } shouldBe listOf("codex", "claude")

        val runs = runBlocking { service.listRuns(task.id) }
        runs.shouldHaveSize(2)
        runs.map { it.assignmentId }.toSet() shouldBe setOf("task-1:1", "task-1:2")
        runs.map { it.assignmentRole }.toSet() shouldBe setOf(TaskAssignmentRole.LEAD, TaskAssignmentRole.WORKER)
        runs.first { it.assignmentId == "task-1:2" }.assignmentPrompt shouldBe "worker prompt"

        coVerify(exactly = 2) { agentExecutor.executeAgent(any(), any(), any()) }
    }
})
