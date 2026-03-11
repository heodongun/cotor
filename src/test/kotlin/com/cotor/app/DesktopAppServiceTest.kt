package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.domain.executor.AgentExecutor
import com.cotor.model.AgentExecutionMetadata
import com.cotor.model.AgentResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories

class DesktopAppServiceTest : FunSpec({
    val tempRoots = CopyOnWriteArrayList<Path>()

    suspend fun createServiceFixture(): DesktopServiceFixture {
        val appHome = Files.createTempDirectory("desktop-app-service-test")
        tempRoots.add(appHome)
        val repositoryRoot = appHome.resolve("repo").createDirectories()
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val configRepository = mockk<ConfigRepository>()
        val agentExecutor = mockk<AgentExecutor>()

        val repository = ManagedRepository(
            id = "repo-1",
            name = "repo",
            localPath = repositoryRoot.toString(),
            sourceKind = RepositorySourceKind.LOCAL,
            defaultBranch = "master",
            createdAt = 1,
            updatedAt = 1
        )
        val workspace = Workspace(
            id = "ws-1",
            repositoryId = repository.id,
            name = "repo · master",
            baseBranch = "master",
            createdAt = 1,
            updatedAt = 1
        )
        stateStore.save(
            DesktopAppState(
                repositories = listOf(repository),
                workspaces = listOf(workspace)
            )
        )

        return DesktopServiceFixture(
            appHome = appHome,
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            agentExecutor = agentExecutor,
            service = DesktopAppService(
                stateStore = stateStore,
                gitWorkspaceService = gitWorkspaceService,
                configRepository = configRepository,
                agentExecutor = agentExecutor
            )
        )
    }

    suspend fun awaitTaskStatus(service: DesktopAppService, taskId: String, status: DesktopTaskStatus): AgentTask {
        repeat(80) {
            val task = service.getTask(taskId).shouldNotBeNull()
            if (task.status == status) {
                return task
            }
            delay(50)
        }
        error("Timed out waiting for task $taskId to reach $status")
    }

    test("createTask persists a deterministic lead and worker assignment plan") {
        val fixture = createServiceFixture()

        val task = fixture.service.createTask(
            workspaceId = "ws-1",
            title = "Desktop orchestration",
            prompt = "Implement autonomous task decomposition",
            agents = listOf("Claude", "Codex", "Gemini")
        )

        task.leadAgent shouldBe "claude"
        task.assignments shouldHaveSize 3
        task.assignments.first().role shouldBe TaskAssignmentRole.LEAD
        task.assignments.first().agentName shouldBe "claude"
        task.assignments[1].role shouldBe TaskAssignmentRole.WORKER
        task.assignments[2].title shouldBe "Validate the change and capture regression risks"

        val persisted = fixture.service.getTask(task.id).shouldNotBeNull()
        persisted.leadAgent shouldBe "claude"
        persisted.assignments shouldBe task.assignments
    }

    test("runTask backfills old tasks and executes assignment-specific prompts") {
        val fixture = createServiceFixture()
        val capturedInputs = CopyOnWriteArrayList<String?>()
        val capturedMetadata = CopyOnWriteArrayList<AgentExecutionMetadata>()

        coEvery {
            fixture.gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any())
        } coAnswers {
            val agentName = arg<String>(3)
            val worktreePath = fixture.appHome.resolve("worktrees").resolve(agentName).createDirectories()
            WorktreeBinding(
                branchName = "codex/test/$agentName",
                worktreePath = worktreePath
            )
        }
        coEvery {
            fixture.agentExecutor.executeAgent(any(), any(), any())
        } coAnswers {
            capturedInputs += secondArg<String?>()
            capturedMetadata += thirdArg<AgentExecutionMetadata>()
            AgentResult(
                agentName = firstArg<com.cotor.model.AgentConfig>().name,
                isSuccess = true,
                output = "ok",
                error = null,
                duration = 5,
                metadata = emptyMap(),
                processId = null
            )
        }

        val legacyTask = AgentTask(
            id = "task-legacy",
            workspaceId = "ws-1",
            title = "Legacy task",
            prompt = "Ship the desktop planner",
            agents = listOf("claude", "codex"),
            status = DesktopTaskStatus.QUEUED,
            createdAt = 2,
            updatedAt = 2
        )
        fixture.stateStore.save(
            fixture.stateStore.load().copy(tasks = listOf(legacyTask))
        )

        fixture.service.runTask(legacyTask.id)
        val completedTask = awaitTaskStatus(fixture.service, legacyTask.id, DesktopTaskStatus.COMPLETED)

        completedTask.assignments shouldHaveSize 2
        completedTask.leadAgent shouldBe "claude"
        capturedInputs shouldHaveSize 2
        capturedInputs.toSet() shouldBe completedTask.assignments.map { it.prompt }.toSet()

        val runs = fixture.service.listRuns(legacyTask.id)
        runs shouldHaveSize 2
        runs.forEach { run ->
            run.assignmentId.shouldNotBeNull()
            run.assignmentRole.shouldNotBeNull()
            run.assignmentTitle.shouldNotBeNull()
            run.assignmentPrompt.shouldNotBeNull()
        }

        capturedMetadata shouldHaveSize 2
        capturedMetadata.mapNotNull { it.branchName }.toSet() shouldBe setOf("codex/test/claude", "codex/test/codex")
        capturedMetadata.mapNotNull { it.workingDirectory?.fileName?.toString() }.toSet() shouldBe setOf("claude", "codex")
        runs.mapNotNull { it.assignmentPrompt }.toSet() shouldBe capturedInputs.toSet()
        runs.mapNotNull { it.assignmentRole }.shouldNotBeEmpty()
    }

    afterSpec {
        tempRoots.forEach { root ->
            if (Files.exists(root)) {
                Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }
})

private data class DesktopServiceFixture(
    val appHome: Path,
    val stateStore: DesktopStateStore,
    val gitWorkspaceService: GitWorkspaceService,
    val agentExecutor: AgentExecutor,
    val service: DesktopAppService
)
