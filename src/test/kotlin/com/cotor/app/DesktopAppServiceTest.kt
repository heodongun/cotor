package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.data.process.ProcessManager
import com.cotor.domain.executor.AgentExecutor
import com.cotor.model.AgentResult
import com.cotor.model.CotorConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import java.nio.file.Files
import java.util.UUID

class DesktopAppServiceTest : FunSpec({
    test("successful agent runs persist publish metadata on the completed run") {
        val serviceFixture = desktopServiceFixture()
        val task = runBlocking {
            serviceFixture.service.createTask(
                workspaceId = serviceFixture.workspace.id,
                title = "Auto publish workflow",
                prompt = "Ship the auto publish workflow",
                agents = listOf("codex")
            )
        }

        runBlocking {
            serviceFixture.service.runTask(task.id)
        }

        val run = awaitCompletedRun(serviceFixture.stateStore, task.id)
        run.status shouldBe AgentRunStatus.COMPLETED
        run.publishInfo.status shouldBe AgentRunPublishStatus.PR_CREATED
        run.publishInfo.remoteBranch shouldBe run.branchName
        run.publishInfo.commitSha shouldBe "1234567890abcdef"
        run.publishInfo.pullRequestUrl shouldBe "https://github.com/heodongun/cotor/pull/140"
        run.publishInfo.pullRequestNumber shouldBe 140
        run.publishInfo.summary shouldBe "Created pull request #140"
    }

    test("publish exceptions are recorded without downgrading the successful run status") {
        val serviceFixture = desktopServiceFixture(
            processManager = FakeProcessManager { command, _ ->
                when {
                    command.take(3) == listOf("git", "show-ref", "--verify") -> fail("missing branch")
                    command.take(4) == listOf("git", "worktree", "add", "-b") -> ok()
                    command == listOf("git", "status", "--porcelain") -> throw IllegalStateException("gh unavailable")
                    else -> error("Unhandled command: ${command.joinToString(" ")}")
                }
            }
        )
        val task = runBlocking {
            serviceFixture.service.createTask(
                workspaceId = serviceFixture.workspace.id,
                title = "Auto publish workflow",
                prompt = "Ship the auto publish workflow",
                agents = listOf("codex")
            )
        }

        runBlocking {
            serviceFixture.service.runTask(task.id)
        }

        val run = awaitCompletedRun(serviceFixture.stateStore, task.id)
        run.status shouldBe AgentRunStatus.COMPLETED
        run.publishInfo.status shouldBe AgentRunPublishStatus.FAILED
        run.publishInfo.remoteBranch shouldBe run.branchName
        run.publishInfo.summary shouldBe "gh unavailable"
    }
})

private data class DesktopServiceFixture(
    val service: DesktopAppService,
    val stateStore: DesktopStateStore,
    val workspace: Workspace
)

private fun desktopServiceFixture(
    processManager: ProcessManager = defaultDesktopProcessManager()
): DesktopServiceFixture {
    val appHome = Files.createTempDirectory("desktop-state")
    val repositoryRoot = Files.createTempDirectory("desktop-repo")
    val stateStore = DesktopStateStore { appHome }
    val now = System.currentTimeMillis()
    val repository = ManagedRepository(
        id = UUID.randomUUID().toString(),
        name = "cotor",
        localPath = repositoryRoot.toString(),
        sourceKind = RepositorySourceKind.LOCAL,
        defaultBranch = "master",
        createdAt = now,
        updatedAt = now
    )
    val workspace = Workspace(
        id = UUID.randomUUID().toString(),
        repositoryId = repository.id,
        name = "cotor · master",
        baseBranch = "master",
        createdAt = now,
        updatedAt = now
    )
    runBlocking {
        stateStore.save(
            DesktopAppState(
                repositories = listOf(repository),
                workspaces = listOf(workspace)
            )
        )
    }

    val configRepository = mockk<ConfigRepository>()
    coEvery { configRepository.loadConfig(any()) } returns CotorConfig()

    val agentExecutor = mockk<AgentExecutor>()
    coEvery { agentExecutor.executeAgent(any(), any(), any()) } returns AgentResult(
        agentName = "codex",
        isSuccess = true,
        output = "done",
        error = null,
        duration = 125,
        metadata = emptyMap(),
        processId = 42
    )

    return DesktopServiceFixture(
        service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = GitWorkspaceService(
                processManager = processManager,
                stateStore = stateStore,
                logger = mockk<Logger>(relaxed = true)
            ),
            configRepository = configRepository,
            agentExecutor = agentExecutor
        ),
        stateStore = stateStore,
        workspace = workspace
    )
}

private fun defaultDesktopProcessManager(): ProcessManager {
    val pullRequestLookup = mutableMapOf<String, Int>()
    return FakeProcessManager { command, _ ->
        when {
            command.take(3) == listOf("git", "show-ref", "--verify") -> fail("missing branch")
            command.take(4) == listOf("git", "worktree", "add", "-b") -> ok()
            command == listOf("git", "status", "--porcelain") -> ok(" M src/main/kotlin/com/cotor/app/DesktopAppService.kt\n")
            command == listOf("git", "add", "-A") -> ok()
            command == listOf("git", "diff", "--cached", "--name-only") -> ok("src/main/kotlin/com/cotor/app/DesktopAppService.kt\n")
            command.take(3) == listOf("git", "commit", "-m") -> ok("[branch 1234567] commit\n")
            command == listOf("git", "rev-parse", "HEAD") -> ok("1234567890abcdef\n")
            command.take(4) == listOf("git", "push", "-u", "origin") -> ok()
            command.take(6) == listOf("gh", "pr", "list", "--head", command[4], "--state") -> {
                val branchName = command[4]
                val prNumber = pullRequestLookup[branchName]
                if (prNumber == null) {
                    ok("[]")
                } else {
                    ok("""[{"number":$prNumber,"url":"https://github.com/heodongun/cotor/pull/$prNumber"}]""")
                }
            }
            command.take(4) == listOf("gh", "pr", "create", "--base") -> {
                val branchName = command[6]
                pullRequestLookup[branchName] = 140
                ok("https://github.com/heodongun/cotor/pull/140\n")
            }
            else -> error("Unhandled command: ${command.joinToString(" ")}")
        }
    }
}

private fun awaitCompletedRun(stateStore: DesktopStateStore, taskId: String): AgentRun = runBlocking {
    repeat(80) {
        val state = stateStore.load()
        val run = state.runs.firstOrNull { it.taskId == taskId }
        if (run != null && run.status != AgentRunStatus.QUEUED && run.status != AgentRunStatus.RUNNING) {
            return@runBlocking run
        }
        delay(25)
    }

    stateStore.load().runs.firstOrNull { it.taskId == taskId }.shouldNotBeNull()
}
