package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.data.process.ProcessManager
import com.cotor.domain.executor.AgentExecutor
import com.cotor.model.AgentConfig
import com.cotor.model.AgentResult
import com.cotor.model.ProcessResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path

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

    test("openLocalRepository stores the origin remote for an existing checkout") {
        val repoRoot = Files.createTempDirectory("cotor-repo")
        val nestedPath = repoRoot.resolve("nested").also { Files.createDirectories(it) }
        val remoteUrl = "https://github.com/heodongun/cotor.git"
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = remoteUrl,
                defaultBranch = "master"
            ),
            appHome = Files.createTempDirectory("cotor-home")
        )

        val repository = service.openLocalRepository(nestedPath.toString())

        repository.localPath shouldBe repoRoot.toString()
        repository.remoteUrl shouldBe remoteUrl
        repository.defaultBranch shouldBe "master"
    }

    test("openLocalRepository backfills missing remote metadata for an existing repository record") {
        val repoRoot = Files.createTempDirectory("cotor-repo")
        val appHome = Files.createTempDirectory("cotor-home")
        val stateStore = DesktopStateStore { appHome }
        stateStore.save(
            DesktopAppState(
                repositories = listOf(
                    ManagedRepository(
                        id = REPOSITORY_ID,
                        name = "cotor",
                        localPath = repoRoot.toString(),
                        sourceKind = RepositorySourceKind.LOCAL,
                        remoteUrl = null,
                        defaultBranch = "stale-branch",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                )
            )
        )

        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val repository = service.openLocalRepository(repoRoot.toString())
        val persisted = stateStore.load().repositories.single()

        repository.id shouldBe REPOSITORY_ID
        repository.remoteUrl shouldBe "https://github.com/heodongun/cotor.git"
        repository.defaultBranch shouldBe "master"
        persisted shouldBe repository
    }

    test("cloneRepository reuses an already connected remote instead of cloning again") {
        val repoRoot = Files.createTempDirectory("cotor-repo")
        val appHome = Files.createTempDirectory("cotor-home")
        val stateStore = DesktopStateStore { appHome }
        stateStore.save(
            DesktopAppState(
                repositories = listOf(
                    ManagedRepository(
                        id = REPOSITORY_ID,
                        name = "cotor",
                        localPath = repoRoot.toString(),
                        sourceKind = RepositorySourceKind.CLONED,
                        remoteUrl = "https://github.com/heodongun/cotor.git",
                        defaultBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                )
            )
        )

        val processManager = FakeGitProcessManager(
            repoRoot = repoRoot,
            remoteUrl = "https://github.com/heodongun/cotor.git",
            defaultBranch = "master"
        )
        val service = testService(
            processManager = processManager,
            stateStore = stateStore
        )

        val repository = service.cloneRepository("https://github.com/heodongun/cotor.git")

        repository.id shouldBe REPOSITORY_ID
        processManager.commands.shouldBeEmpty()
        processManager.commands.firstOrNull { it.command.getOrNull(1) == "clone" }.shouldBeNull()
    }

    test("createTask persists a generated execution plan") {
        val appHome = Files.createTempDirectory("desktop-planner-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-planner-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
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

        val plan = task.plan.shouldNotBeNull()
        plan.assignments.map { it.agentName } shouldBe listOf("claude", "codex")
        plan.assignments.all { it.assignedPrompt.contains("Goal-driven assignment") } shouldBe true

        val persistedTask = stateStore.load().tasks.single()
        persistedTask.plan shouldBe plan
    }

    test("runTask uses assigned prompts when present and raw prompt when absent") {
        val appHome = Files.createTempDirectory("desktop-planner-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-planner-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val configRepository = mockk<ConfigRepository>(relaxed = true)
        val agentExecutor = mockk<AgentExecutor>()
        val capturedInputs = mutableListOf<String?>()

        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } coAnswers {
            val agentName = invocation.args[3] as String
            val worktreePath = Files.createDirectories(Files.createTempDirectory("desktop-planner-worktree").resolve(agentName))
            WorktreeBinding(
                branchName = "codex/cotor/test/$agentName",
                worktreePath = worktreePath
            )
        }
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } coAnswers {
            capturedInputs += invocation.args[1] as String?
            val agent = invocation.args[0] as AgentConfig
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
        coEvery { gitWorkspaceService.publishRun(any(), any(), any(), any(), any()) } returns PublishMetadata(
            commitSha = "abc1234567890",
            pushedBranch = "codex/cotor/test/branch",
            pullRequestNumber = 77,
            pullRequestUrl = "https://github.com/heodongun/cotor/pull/77"
        )

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

        val expectedPrompts = plannedTask.plan.shouldNotBeNull().assignments.map { it.assignedPrompt }.toSet()
        capturedInputs.toSet() shouldBe expectedPrompts
        capturedInputs.none { it == plannedTask.prompt } shouldBe true

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

        capturedInputs shouldBe listOf("Legacy prompt should still flow through unchanged.")
    }

    test("startCompanyRuntime merges ready review queue items for autonomous goals") {
        val appHome = Files.createTempDirectory("desktop-runtime-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            companyRuntimeTickIntervalMs = 50
        )

        val goal = service.createGoal(
            title = "Autonomous merge loop",
            description = "Keep merging ready review items without manual approval."
        )
        val issue = stateStore.load().issues.first { it.goalId == goal.id }
        val now = System.currentTimeMillis()
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == issue.id) it.copy(status = IssueStatus.IN_REVIEW, updatedAt = now) else it
                },
                reviewQueue = listOf(
                    ReviewQueueItem(
                        id = "rq-1",
                        issueId = issue.id,
                        runId = "run-1",
                        pullRequestNumber = 99,
                        pullRequestUrl = "https://github.com/heodongun/cotor/pull/99",
                        status = ReviewQueueStatus.READY_TO_MERGE,
                        mergeability = "clean",
                        createdAt = now,
                        updatedAt = now
                    )
                )
            )
        )

        val runtime = service.startCompanyRuntime()
        var updatedState: DesktopAppState? = null
        withTimeout(5_000) {
            while (true) {
                val current = stateStore.load()
                val mergedItem = current.reviewQueue.firstOrNull { it.id == "rq-1" }
                if (mergedItem?.status == ReviewQueueStatus.MERGED) {
                    updatedState = current
                    return@withTimeout
                }
                delay(25)
            }
        }
        val settledState = updatedState.shouldNotBeNull()
        val mergedItem = settledState.reviewQueue.first { it.id == "rq-1" }

        runtime.status shouldBe CompanyRuntimeStatus.RUNNING
        mergedItem.status shouldBe ReviewQueueStatus.MERGED
        settledState.issues.first { it.id == issue.id }.status shouldBe IssueStatus.DONE
        settledState.runtime.lastAction shouldNotBe null
        service.stopCompanyRuntime().status shouldBe CompanyRuntimeStatus.STOPPED
    }

    test("stopCompanyRuntime remains stopped after an in-flight tick settles") {
        val appHome = Files.createTempDirectory("desktop-runtime-stop-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-stop-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            companyRuntimeTickIntervalMs = 25
        )

        service.createGoal(
            title = "Stop runtime safely",
            description = "Ensure a late runtime tick cannot restore RUNNING after stop."
        )

        service.startCompanyRuntime().status shouldBe CompanyRuntimeStatus.RUNNING
        service.stopCompanyRuntime().status shouldBe CompanyRuntimeStatus.STOPPED

        delay(80)

        stateStore.load().runtime.status shouldBe CompanyRuntimeStatus.STOPPED
    }

    test("default org profiles prefer installed agent CLIs over missing claude") {
        val appHome = Files.createTempDirectory("desktop-runtime-agents-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-agents-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            commandAvailability = { command -> command in setOf("codex", "gemini") }
        )

        val goal = service.createGoal(
            title = "Prefer installed agents",
            description = "Use the CLIs that are available on this machine."
        )

        val profiles = service.listOrgProfiles()
        val issues = service.listIssues(goal.id)

        profiles.map { it.executionAgentName } shouldBe listOf("codex", "gemini")
        issues.mapNotNull { it.assigneeProfileId }.toSet() shouldBe profiles.map { it.id }.toSet()
    }
})

private class DesktopAppServiceFixture private constructor(
    val service: DesktopAppService,
    val stateStore: DesktopStateStore,
    val gitWorkspaceService: GitWorkspaceService,
    val agentExecutor: AgentExecutor,
    val task: AgentTask,
    val worktreeRoot: Path
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
                id = REPOSITORY_ID,
                name = "cotor",
                localPath = repoRoot.toString(),
                sourceKind = RepositorySourceKind.LOCAL,
                defaultBranch = "master",
                createdAt = 1,
                updatedAt = 1
            )
            val workspace = Workspace(
                id = WORKSPACE_ID,
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

private fun testService(
    processManager: ProcessManager,
    appHome: Path? = null,
    stateStore: DesktopStateStore? = null
): DesktopAppService {
    val store = stateStore ?: DesktopStateStore { appHome ?: Files.createTempDirectory("cotor-home") }
    val gitWorkspaceService = GitWorkspaceService(
        processManager = processManager,
        stateStore = store,
        logger = mockk(relaxed = true)
    )
    return DesktopAppService(
        stateStore = store,
        gitWorkspaceService = gitWorkspaceService,
        configRepository = mockk<ConfigRepository>(relaxed = true),
        agentExecutor = mockk<AgentExecutor>(relaxed = true)
    )
}

private class FakeGitProcessManager(
    private val repoRoot: Path,
    private val remoteUrl: String?,
    private val defaultBranch: String
) : ProcessManager {
    data class CommandCall(val command: List<String>, val workingDirectory: Path?)

    val commands = mutableListOf<CommandCall>()

    override suspend fun executeProcess(
        command: List<String>,
        input: String?,
        environment: Map<String, String>,
        timeout: Long,
        workingDirectory: Path?
    ): ProcessResult {
        commands += CommandCall(command = command, workingDirectory = workingDirectory)
        val gitArgs = command.drop(1)
        return when (gitArgs) {
            listOf("rev-parse", "--show-toplevel") -> success(repoRoot.toString())
            listOf("symbolic-ref", "refs/remotes/origin/HEAD") -> success("refs/remotes/origin/$defaultBranch")
            listOf("config", "--get", "remote.origin.url") -> {
                if (remoteUrl == null) failure("missing remote") else success(remoteUrl)
            }
            else -> error("Unexpected git command: ${command.joinToString(" ")}")
        }
    }

    private fun success(stdout: String): ProcessResult = ProcessResult(
        exitCode = 0,
        stdout = "$stdout\n",
        stderr = "",
        isSuccess = true
    )

    private fun failure(stderr: String): ProcessResult = ProcessResult(
        exitCode = 1,
        stdout = "",
        stderr = stderr,
        isSuccess = false
    )
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

private const val REPOSITORY_ID = "repo-1"
private const val WORKSPACE_ID = "workspace-1"
