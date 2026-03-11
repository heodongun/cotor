package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.domain.executor.AgentExecutor
import com.cotor.model.AgentConfig
import com.cotor.model.AgentResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DesktopAppServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `createTask persists a generated execution plan`() = runBlocking {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))
        val stateStore = DesktopStateStore { tempDir.resolve("app-home") }
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

        assertNotNull(task.plan)
        assertEquals(listOf("claude", "codex"), task.plan?.assignments?.map { it.agentName })
        assertTrue(task.plan?.assignments?.all { it.assignedPrompt.contains("Goal-driven assignment") } == true)

        val persistedTask = stateStore.load().tasks.single()
        assertEquals(task.plan, persistedTask.plan)
    }

    @Test
    fun `runTask uses assigned prompts when present and raw prompt when absent`() = runBlocking {
        val repoRoot = Files.createDirectories(tempDir.resolve("repo"))
        val stateStore = DesktopStateStore { tempDir.resolve("app-home") }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val configRepository = mockk<ConfigRepository>(relaxed = true)
        val agentExecutor = mockk<AgentExecutor>()
        val capturedInputs = mutableListOf<String?>()

        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } coAnswers {
            val agentName = arg<String>(3)
            val worktreePath = Files.createDirectories(tempDir.resolve("worktrees").resolve(agentName))
            WorktreeBinding(
                branchName = "codex/cotor/test/$agentName",
                worktreePath = worktreePath
            )
        }
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } coAnswers {
            capturedInputs += secondArg<String?>()
            val agent = firstArg<AgentConfig>()
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

        val expectedPrompts = plannedTask.plan?.assignments?.map { it.assignedPrompt }?.toSet()
        assertEquals(expectedPrompts, capturedInputs.toSet())
        assertTrue(capturedInputs.none { it == plannedTask.prompt })

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

        assertEquals(listOf("Legacy prompt should still flow through unchanged."), capturedInputs)
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
        withTimeout(5_000) {
            while (true) {
                val task = stateStore.load().tasks.first { it.id == taskId }
                if (task.status == DesktopTaskStatus.COMPLETED) {
                    return@withTimeout
                }
                delay(25)
            }
        }
    }

    private companion object {
        const val REPOSITORY_ID = "repo-1"
        const val WORKSPACE_ID = "workspace-1"
    }
}
