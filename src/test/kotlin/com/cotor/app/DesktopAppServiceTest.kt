package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.data.process.CoroutineProcessManager
import com.cotor.data.process.ProcessManager
import com.cotor.domain.executor.AgentExecutor
import com.cotor.integrations.linear.LinearIssueMirror
import com.cotor.integrations.linear.LinearTrackerAdapter
import com.cotor.model.AgentConfig
import com.cotor.model.AgentResult
import com.cotor.model.ProcessResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import org.slf4j.LoggerFactory

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

    test("createTask keeps linked issue prompts as a single issue assignment instead of replanning the whole context") {
        val appHome = Files.createTempDirectory("desktop-issue-plan-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-issue-plan-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk()
        )

        val company = service.createCompany(
            name = "Issue Plan Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val agent = service.createCompanyAgentDefinition(
            companyId = company.id,
            title = "Builder",
            agentCli = "codex",
            roleSummary = "implementation and delivery"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Keep issue prompts stable",
            description = "Do not explode issue execution prompts into dozens of synthetic subtasks.",
            autonomyEnabled = false
        )
        val issue = service.decomposeGoal(goal.id).first()
        val prompt = """
            Company execution context
            - company: Issue Plan Co

            Primary issue
            ${issue.title}
        """.trimIndent()

        val task = service.createTask(
            workspaceId = issue.workspaceId,
            title = issue.title,
            prompt = prompt,
            agents = listOf(agent.agentCli),
            issueId = issue.id
        )

        val plan = task.plan.shouldNotBeNull()
        plan.decompositionSource shouldBe "issue-assignment"
        plan.assignments shouldHaveSize 1
        plan.assignments.single().assignedPrompt.contains("Primary issue") shouldBe true
        plan.assignments.single().subtasks.size shouldBe issue.acceptanceCriteria.size
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

    test("runTask falls back to builtin or command-backed agents when cotor yaml cannot be parsed") {
        val appHome = Files.createTempDirectory("desktop-config-fallback-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-config-fallback-test").resolve("repo"))
        Files.writeString(repoRoot.resolve("cotor.yaml"), "bad: [yaml")
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val configRepository = mockk<ConfigRepository>()
        val agentExecutor = mockk<AgentExecutor>()
        val capturedInputs = mutableListOf<String?>()

        coEvery { configRepository.loadConfig(any()) } throws IllegalStateException("broken config")
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } answers {
            val agentName = invocation.args[3] as String
            val worktreePath = Files.createDirectories(Files.createTempDirectory("desktop-config-fallback-worktree").resolve(agentName))
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
        coEvery { gitWorkspaceService.publishRun(any(), any(), any(), any(), any()) } returns PublishMetadata()

        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = configRepository,
            agentExecutor = agentExecutor,
            commandAvailability = { command -> command == "codex" }
        )

        val task = service.createTask(
            workspaceId = WORKSPACE_ID,
            title = "Broken config fallback",
            prompt = "Still execute even if cotor yaml is broken.",
            agents = listOf("codex")
        )

        service.runTask(task.id)
        awaitTaskCompletion(stateStore, task.id)

        capturedInputs shouldBe listOf(task.plan?.assignments?.single()?.assignedPrompt ?: task.prompt)
        stateStore.load().runs.single().status shouldBe AgentRunStatus.COMPLETED
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
            description = "Keep merging ready review items without manual approval.",
            autonomyEnabled = false
        )
        val issue = stateStore.load().issues.first { it.goalId == goal.id }
        val now = System.currentTimeMillis()
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    it.copy(status = IssueStatus.IN_REVIEW, updatedAt = now)
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

        service.updateGoal(goal.id, autonomyEnabled = true)
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

        settledState.runtime.status shouldBe CompanyRuntimeStatus.RUNNING
        mergedItem.status shouldBe ReviewQueueStatus.MERGED
        settledState.issues.first { it.id == issue.id }.status shouldBe IssueStatus.DONE
        settledState.runtime.lastAction shouldNotBe null
        service.stopCompanyRuntime().status shouldBe CompanyRuntimeStatus.STOPPED
    }

    test("createGoal automatically starts the company runtime when autonomy is enabled") {
        val appHome = Files.createTempDirectory("desktop-runtime-autostart-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-autostart-test").resolve("repo"))
        val worktreeRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-autostart-worktree"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val agentExecutor = mockk<AgentExecutor>()
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } answers {
            val agentName = invocation.args[3] as String
            WorktreeBinding(
                branchName = "codex/cotor/autostart/${agentName}",
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
                duration = 50,
                metadata = emptyMap(),
                processId = 1234
            )
        }
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            companyRuntimeTickIntervalMs = 50,
            commandAvailability = { command -> command == "codex" }
        )

        val goal = service.createGoal(
            title = "Autostart runtime",
            description = "Creating an autonomous goal should boot the company loop immediately."
        )

        withTimeout(5_000) {
            while (service.runtimeStatus(goal.companyId).status != CompanyRuntimeStatus.RUNNING) {
                delay(25)
            }
        }

        service.stopCompanyRuntime(goal.companyId).status shouldBe CompanyRuntimeStatus.STOPPED
    }

    test("company runtime falls back to local execution when codex app server is unavailable") {
        val appHome = Files.createTempDirectory("desktop-runtime-backend-fallback-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-backend-fallback-test").resolve("repo"))
        val worktreeRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-backend-fallback-worktree"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val agentExecutor = mockk<AgentExecutor>()

        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } answers {
            val agentName = invocation.args[3] as String
            WorktreeBinding(
                branchName = "codex/cotor/backend-fallback/${agentName}",
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
                processId = 5678
            )
        }

        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            companyRuntimeTickIntervalMs = 50,
            commandAvailability = { command -> command == "codex" }
        )

        service.updateBackendSettings(
            defaultBackendKind = ExecutionBackendKind.CODEX_APP_SERVER,
            codexAppServerBaseUrl = "http://127.0.0.1:9999",
            codexTimeoutSeconds = 1
        )

        val company = service.createCompany(
            name = "Fallback Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        service.createCompanyAgentDefinition(
            companyId = company.id,
            title = "CEO",
            agentCli = "codex",
            roleSummary = "CEO planning and review"
        )
        service.createCompanyAgentDefinition(
            companyId = company.id,
            title = "Builder",
            agentCli = "codex",
            roleSummary = "Builder implementation"
        )

        val goal = service.createGoal(
            companyId = company.id,
            title = "Fallback execution",
            description = "Keep autonomous company execution alive even when Codex app server is unavailable."
        )

        var companyRuns = emptyList<AgentRun>()
        withTimeout(10_000) {
            while (true) {
                service.runCompanyRuntimeTick(goal.companyId)
                val snapshot = stateStore.load()
                val companyTasks = snapshot.tasks.filter { task ->
                    val issueId = task.issueId ?: return@filter false
                    snapshot.issues.any { it.id == issueId && it.companyId == company.id }
                }
                companyRuns = snapshot.runs.filter { run ->
                    val task = snapshot.tasks.firstOrNull { it.id == run.taskId } ?: return@filter false
                    val issueId = task.issueId ?: return@filter false
                    snapshot.issues.any { it.id == issueId && it.companyId == company.id }
                }
                if (companyTasks.any {
                        it.status == DesktopTaskStatus.RUNNING ||
                            it.status == DesktopTaskStatus.COMPLETED ||
                            it.status == DesktopTaskStatus.PARTIAL ||
                            it.status == DesktopTaskStatus.FAILED
                    } &&
                    companyRuns.isNotEmpty()
                ) {
                    return@withTimeout
                }
                delay(25)
            }
        }

        companyRuns = stateStore.load().runs.filter { run ->
            val task = stateStore.load().tasks.firstOrNull { it.id == run.taskId } ?: return@filter false
            val issueId = task.issueId ?: return@filter false
            stateStore.load().issues.any { it.id == issueId && it.companyId == company.id }
        }
        companyRuns.shouldNotBeEmpty()
        companyRuns.any { it.status != AgentRunStatus.QUEUED } shouldBe true
        service.runtimeStatus(goal.companyId).status shouldBe CompanyRuntimeStatus.RUNNING
    }

    test("enabled company Linear sync mirrors decomposed issues and stores external identifiers") {
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-linear-create-test").resolve("repo"))
        val linearTracker = FakeLinearTrackerAdapter()
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            appHome = Files.createTempDirectory("desktop-linear-create-home"),
            linearTracker = linearTracker
        )

        val company = service.createCompany(
            name = "Linear Create Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        service.updateCompanyLinear(
            companyId = company.id,
            enabled = true,
            teamId = "team-1",
            projectId = "project-1"
        )

        val goal = service.createGoal(
            companyId = company.id,
            title = "Mirror issue creation",
            description = "Create local issues and mirror them to Linear.",
            autonomyEnabled = false
        )

        withTimeout(5_000) {
            while (true) {
                val mirroredIssues = service.listIssues(goal.id, company.id)
                if (mirroredIssues.isNotEmpty() && mirroredIssues.all { it.linearIssueId != null && it.linearIssueIdentifier != null && it.linearIssueUrl != null }) {
                    return@withTimeout
                }
                delay(25)
            }
        }

        val mirroredIssues = service.listIssues(goal.id, company.id)
        mirroredIssues.all { it.linearIssueId != null && it.linearIssueIdentifier != null && it.linearIssueUrl != null } shouldBe true
        linearTracker.syncCalls.size shouldBe mirroredIssues.size
    }

    test("manual company Linear sync returns summary counts") {
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-linear-sync-test").resolve("repo"))
        val linearTracker = FakeLinearTrackerAdapter()
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            appHome = Files.createTempDirectory("desktop-linear-sync-home"),
            linearTracker = linearTracker
        )

        val company = service.createCompany(
            name = "Linear Sync Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        service.updateCompanyLinear(
            companyId = company.id,
            enabled = true,
            teamId = "team-1",
            projectId = "project-1"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Manual sync",
            description = "Sync all local issues to Linear.",
            autonomyEnabled = false
        )

        val response = service.syncCompanyLinear(company.id)

        response.ok shouldBe true
        response.syncedIssues shouldBe service.listIssues(goal.id, company.id).size
        response.failedIssues.shouldBeEmpty()
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

        val goal = service.createGoal(
            title = "Stop runtime safely",
            description = "Ensure a late runtime tick cannot restore RUNNING after stop.",
            autonomyEnabled = false
        )
        val now = System.currentTimeMillis()
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    it.copy(status = IssueStatus.DONE, updatedAt = now)
                }
            )
        )
        service.updateGoal(goal.id, autonomyEnabled = true)
        withTimeout(5_000) {
            while (stateStore.load().runtime.status != CompanyRuntimeStatus.RUNNING) {
                delay(25)
            }
        }
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
            description = "Use the CLIs that are available on this machine.",
            autonomyEnabled = false
        )

        val profiles = service.listOrgProfiles()
        val issues = service.listIssues(goal.id)

        profiles.map { it.executionAgentName } shouldBe listOf("codex", "codex", "codex")
        issues.mapNotNull { it.assigneeProfileId }.toSet() shouldBe profiles.map { it.id }.toSet()
    }

    test("company dashboard backfills legacy companies with a codex-first seeded roster") {
        val appHome = Files.createTempDirectory("desktop-legacy-company-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-legacy-company-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val now = System.currentTimeMillis()
        val company = Company(
            id = "legacy-company",
            name = "Legacy Company",
            rootPath = repoRoot.toString(),
            repositoryId = REPOSITORY_ID,
            defaultBaseBranch = "master",
            createdAt = now,
            updatedAt = now
        )
        stateStore.save(
            DesktopAppState(
                repositories = listOf(
                    ManagedRepository(
                        id = REPOSITORY_ID,
                        name = "legacy",
                        localPath = repoRoot.toString(),
                        sourceKind = RepositorySourceKind.LOCAL,
                        defaultBranch = "master",
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                companies = listOf(company),
                companyRuntimes = listOf(CompanyRuntimeSnapshot(companyId = company.id))
            )
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(relaxed = true),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            commandAvailability = { command -> command == "codex" }
        )

        service.companyDashboard(company.id)

        val definitions = service.listCompanyAgentDefinitions(company.id)
        definitions.map { it.agentCli } shouldBe listOf("codex", "codex", "codex")
        definitions.map { it.title } shouldBe listOf("CEO", "Builder", "QA")
    }

    test("company dashboard restarts autonomous company runtimes that were left stopped") {
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
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } answers {
            val agentName = invocation.args[3] as String
            WorktreeBinding(
                branchName = "codex/cotor/dashboard-runtime/${agentName}",
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
            commandAvailability = { command -> command == "codex" }
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
            autonomyEnabled = true
        )
        service.stopCompanyRuntime(company.id)

        service.companyDashboard(company.id)

        withTimeout(5_000) {
            while (
                service.runtimeStatus(company.id).status != CompanyRuntimeStatus.RUNNING ||
                    !stateStore.load().tasks.any { it.issueId != null }
            ) {
                delay(25)
            }
        }
        stateStore.load().tasks.any { it.issueId != null } shouldBe true
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
                branchName = "codex/cotor/dashboard-idle/${agentName}",
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
                processId = 6101
            )
        }
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            companyRuntimeTickIntervalMs = 50,
            commandAvailability = { command -> command == "codex" }
        )

        val company = service.createCompany(
            name = "Dashboard Idle Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Recover idle work",
            description = "The dashboard should resume pending autonomous work.",
            autonomyEnabled = true
        )
        withTimeout(5_000) {
            while (stateStore.load().issues.none { it.goalId == goal.id }) {
                delay(25)
            }
        }
        service.stopCompanyRuntime(company.id)
        val pausedState = stateStore.load()
        val pendingIssue = pausedState.issues.first { it.goalId == goal.id && it.kind == "execution" }
        val rewoundState = pausedState.copy(
            issues = pausedState.issues.map {
                if (it.id == pendingIssue.id) {
                    it.copy(status = IssueStatus.DELEGATED, updatedAt = System.currentTimeMillis() - 10_000)
                } else {
                    it
                }
            },
            tasks = pausedState.tasks.filterNot { it.issueId == pendingIssue.id },
            runs = pausedState.runs.filterNot { run ->
                pausedState.tasks.any { it.issueId == pendingIssue.id && it.id == run.taskId }
            },
            companyRuntimes = pausedState.companyRuntimes.map {
                if (it.companyId == company.id) {
                    it.copy(
                        status = CompanyRuntimeStatus.RUNNING,
                        lastTickAt = System.currentTimeMillis() - 10_000,
                        lastAction = "stale-running-state"
                    )
                } else {
                    it
                }
            }
        )
        stateStore.save(rewoundState)

        service.companyDashboard(company.id)

        withTimeout(5_000) {
            while (stateStore.load().tasks.none { it.issueId == pendingIssue.id }) {
                delay(25)
            }
        }
    }

    test("company dashboard requeues recoverable blocked issues caused by infrastructure failures") {
        val repoRoot = Files.createTempDirectory("desktop-company-requeue-repo")
        val appHome = Files.createTempDirectory("desktop-company-requeue-home")
        val stateStore = DesktopStateStore { appHome }
        val company = Company(
            id = "company-requeue",
            name = "Requeue Co",
            rootPath = repoRoot.toString(),
            repositoryId = REPOSITORY_ID,
            defaultBaseBranch = "master",
            createdAt = 1L,
            updatedAt = 1L
        )
        val goal = CompanyGoal(
            id = "goal-requeue",
            companyId = company.id,
            projectContextId = "project-requeue",
            title = "hello",
            description = "say hello",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = false,
            createdAt = 2L,
            updatedAt = 2L
        )
        val issue = CompanyIssue(
            id = "issue-requeue",
            companyId = company.id,
            projectContextId = "project-requeue",
            goalId = goal.id,
            workspaceId = WORKSPACE_ID,
            title = "Execution issue",
            description = "Do the work",
            status = IssueStatus.BLOCKED,
            createdAt = 3L,
            updatedAt = 3L
        )
        val task = AgentTask(
            id = "task-requeue",
            workspaceId = WORKSPACE_ID,
            issueId = issue.id,
            title = issue.title,
            prompt = "prompt",
            agents = listOf("codex"),
            status = DesktopTaskStatus.FAILED,
            createdAt = 4L,
            updatedAt = 4L
        )
        val run = AgentRun(
            id = "run-requeue",
            taskId = task.id,
            workspaceId = WORKSPACE_ID,
            repositoryId = REPOSITORY_ID,
            agentName = "codex",
            branchName = "codex/cotor/legacy/codex",
            worktreePath = repoRoot.toString(),
            status = AgentRunStatus.FAILED,
            error = "GIT command failed",
            createdAt = 5L,
            updatedAt = 5L
        )
        stateStore.save(
            DesktopAppState(
                companies = listOf(company),
                repositories = listOf(
                    ManagedRepository(
                        id = REPOSITORY_ID,
                        name = "repo",
                        localPath = repoRoot.toString(),
                        sourceKind = RepositorySourceKind.LOCAL,
                        defaultBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                workspaces = listOf(
                    Workspace(
                        id = WORKSPACE_ID,
                        repositoryId = REPOSITORY_ID,
                        name = "repo · master",
                        baseBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                projectContexts = listOf(
                    CompanyProjectContext(
                        id = "project-requeue",
                        companyId = company.id,
                        name = "Requeue Co",
                        slug = "requeue-co",
                        contextDocPath = appHome.resolve("project.md").toString(),
                        lastUpdatedAt = 1L
                    )
                ),
                goals = listOf(goal),
                issues = listOf(issue),
                tasks = listOf(task),
                runs = listOf(run)
            )
        )
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = null,
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        service.companyDashboard(company.id)

        withTimeout(5_000) {
            while (true) {
                val latestIssue = stateStore.load().issues.first { it.id == issue.id }
                if (latestIssue.status == IssueStatus.PLANNED) {
                    latestIssue.blockedBy.shouldBeEmpty()
                    return@withTimeout
                }
                delay(25)
            }
        }
    }

    test("company dashboard requeues orphaned blocked autonomous issues with no tasks") {
        val repoRoot = Files.createTempDirectory("desktop-company-requeue-orphaned-repo")
        val appHome = Files.createTempDirectory("desktop-company-requeue-orphaned-home")
        val stateStore = DesktopStateStore { appHome }
        val company = Company(
            id = "company-requeue-orphaned",
            name = "Requeue Orphaned Co",
            rootPath = repoRoot.toString(),
            repositoryId = REPOSITORY_ID,
            defaultBaseBranch = "master",
            createdAt = 1L,
            updatedAt = 1L
        )
        val goal = CompanyGoal(
            id = "goal-requeue-orphaned",
            companyId = company.id,
            projectContextId = "project-requeue-orphaned",
            title = "hello",
            description = "say hello",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = 2L,
            updatedAt = 2L
        )
        val issue = CompanyIssue(
            id = "issue-requeue-orphaned",
            companyId = company.id,
            projectContextId = "project-requeue-orphaned",
            goalId = goal.id,
            workspaceId = WORKSPACE_ID,
            title = "Execution issue",
            description = "Do the work",
            status = IssueStatus.BLOCKED,
            createdAt = 3L,
            updatedAt = 3L
        )
        stateStore.save(
            DesktopAppState(
                companies = listOf(company),
                repositories = listOf(
                    ManagedRepository(
                        id = REPOSITORY_ID,
                        name = "repo",
                        localPath = repoRoot.toString(),
                        sourceKind = RepositorySourceKind.LOCAL,
                        defaultBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                workspaces = listOf(
                    Workspace(
                        id = WORKSPACE_ID,
                        repositoryId = REPOSITORY_ID,
                        name = "repo · master",
                        baseBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                projectContexts = listOf(
                    CompanyProjectContext(
                        id = "project-requeue-orphaned",
                        companyId = company.id,
                        name = "Requeue Orphaned Co",
                        slug = "requeue-orphaned-co",
                        contextDocPath = appHome.resolve("project.md").toString(),
                        lastUpdatedAt = 1L
                    )
                ),
                goals = listOf(goal),
                issues = listOf(issue)
            )
        )
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = null,
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        service.companyDashboard(company.id)

        withTimeout(5_000) {
            while (true) {
                val latestIssue = stateStore.load().issues.first { it.id == issue.id }
                if (latestIssue.status == IssueStatus.PLANNED) {
                    latestIssue.blockedBy.shouldBeEmpty()
                    return@withTimeout
                }
                delay(25)
            }
        }
    }

    test("seeded builder profile does not infer frontend capability from the word builder") {
        val appHome = Files.createTempDirectory("desktop-builder-capability-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-builder-capability-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = mockk(relaxed = true),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk(relaxed = true),
            commandAvailability = { command -> command == "codex" }
        )

        val company = service.createCompany(
            name = "Capability Check Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )

        val builder = service.listOrgProfiles().first { it.companyId == company.id && it.roleName == "Builder" }
        builder.capabilities shouldBe listOf("implementation", "integration")
    }

    test("autonomous runtime leaves goal issues unblocked for parallel scheduling and starts work automatically") {
        val appHome = Files.createTempDirectory("desktop-runtime-parallel-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-parallel-test").resolve("repo"))
        val worktreeRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-parallel-worktree"))
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
                branchName = "codex/cotor/parallel/${agentName}",
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
                duration = 50,
                metadata = emptyMap(),
                processId = 555
            )
        }
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            companyRuntimeTickIntervalMs = 50,
            commandAvailability = { command -> command in setOf("codex", "gemini") }
        )

        val company = service.createCompany(
            name = "Parallel Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Parallel company execution",
            description = """
                - Build the main implementation path
                - Cover regression and failure handling
                - Document final validation
            """.trimIndent(),
            autonomyEnabled = false
        )

        val plannedIssues = service.listIssues(goal.id)
        plannedIssues.size shouldBe 3
        plannedIssues.count { it.dependsOn.isEmpty() && it.status == IssueStatus.PLANNED } shouldBe 1
        plannedIssues.count { it.dependsOn.isNotEmpty() && it.status == IssueStatus.BACKLOG } shouldBe 2

        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(goal.companyId)
        service.runCompanyRuntimeTick(goal.companyId)

        withTimeout(5_000) {
            while (true) {
                val current = stateStore.load()
                val issueTasks = current.tasks.filter { it.issueId != null }
                val activeIssues = current.issues.filter { it.goalId == goal.id }.count { it.status != IssueStatus.PLANNED && it.status != IssueStatus.BACKLOG }
                if (issueTasks.isNotEmpty() || activeIssues > 0) {
                    return@withTimeout
                }
                delay(25)
            }
        }

        withTimeout(5_000) {
            while (true) {
                val state = stateStore.load()
                if (state.issues.filter { it.goalId == goal.id }.count { it.status != IssueStatus.PLANNED } > 0) {
                    return@withTimeout
                }
                delay(25)
            }
        }
        service.stopCompanyRuntime(goal.companyId).status shouldBe CompanyRuntimeStatus.STOPPED
    }

    test("autonomous goal execution creates and completes runs without a manual issue trigger") {
        val appHome = Files.createTempDirectory("desktop-runtime-autorun-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-autorun-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val processManager = FakeGitProcessManager(
            repoRoot = repoRoot,
            remoteUrl = "https://github.com/heodongun/cotor.git",
            defaultBranch = "master"
        )
        val gitWorkspaceService = GitWorkspaceService(
            processManager = processManager,
            stateStore = stateStore,
            logger = mockk(relaxed = true)
        )
        val configRepository = mockk<ConfigRepository>(relaxed = true)
        val agentExecutor = mockk<AgentExecutor>()
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } answers {
            val agent = invocation.args[0] as AgentConfig
            AgentResult(
                agentName = agent.name,
                isSuccess = true,
                output = "completed",
                error = null,
                duration = 50,
                metadata = emptyMap(),
                processId = 4321
            )
        }
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = configRepository,
            agentExecutor = agentExecutor,
            companyRuntimeTickIntervalMs = 50,
            commandAvailability = { command -> command == "codex" }
        )

        val company = service.createCompany(
            name = "Autonomous Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )

        val goal = service.createGoal(
            companyId = company.id,
            title = "Autonomous codex execution",
            description = "Create the plan and execute it without pressing any issue run button.",
            autonomyEnabled = false
        )
        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(goal.companyId)
        service.runCompanyRuntimeTick(goal.companyId)

        repeat(10) {
            service.runCompanyRuntimeTick(goal.companyId)
            delay(25)
        }

        withTimeout(30_000) {
            while (true) {
                service.runCompanyRuntimeTick(goal.companyId)
                val state = stateStore.load()
                val issueRunCount = state.runs.count { run -> state.tasks.firstOrNull { it.id == run.taskId }?.issueId != null }
                if (state.tasks.count { it.issueId != null } > 0 && issueRunCount > 0) {
                    return@withTimeout
                }
                delay(50)
            }
        }

        val state = stateStore.load()
        state.tasks.count { it.issueId != null } shouldBeGreaterThan 0
        state.runs.count { run -> state.tasks.firstOrNull { it.id == run.taskId }?.issueId != null } shouldBeGreaterThan 0
        service.stopCompanyRuntime(goal.companyId).status shouldBe CompanyRuntimeStatus.STOPPED
    }

    test("company decomposition keeps duplicate worker CLIs as separate execution issues and builds review dependencies") {
        val appHome = Files.createTempDirectory("desktop-runtime-roster-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-roster-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Dynamic Roster Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val seededAgents = service.listCompanyAgentDefinitions(company.id)
        val seededChief = seededAgents.first { it.title == "CEO" }
        val seededBuilder = seededAgents.first { it.title == "Builder" }
        val seededQa = seededAgents.first { it.title == "QA" }
        service.updateCompanyAgentDefinition(company.id, seededChief.id, title = "CEO", agentCli = "codex", roleSummary = "ceo final approval")
        service.updateCompanyAgentDefinition(company.id, seededBuilder.id, title = "Builder 1", agentCli = "/bin/echo", roleSummary = "builder implementation delivery")
        service.updateCompanyAgentDefinition(company.id, seededQa.id, title = "QA Lead", agentCli = "/bin/echo", roleSummary = "qa review verification")
        repeat(4) { index ->
            service.createCompanyAgentDefinition(
                company.id,
                "Builder ${index + 2}",
                "/bin/echo",
                "builder implementation delivery"
            )
        }

        val goal = service.createGoal(
            companyId = company.id,
            title = "Dynamic agent company",
            description = """
                - Build the planner
                - Build the UI shell
                - Add verification
                - Validate branch publishing
                - Summarize release readiness
            """.trimIndent(),
            autonomyEnabled = false
        )

        val issues = service.listIssues(goal.id)
        val executionIssues = issues.filter { it.kind == "execution" }
        val reviewIssues = issues.filter { it.kind == "review" }
        val approvalIssues = issues.filter { it.kind == "approval" }

        executionIssues shouldHaveSize 5
        reviewIssues shouldHaveSize 1
        approvalIssues shouldHaveSize 1
        executionIssues.mapNotNull { it.assigneeProfileId }.distinct().size shouldBe 5
        executionIssues.all { it.dependsOn.isEmpty() && it.status == IssueStatus.PLANNED } shouldBe true
        reviewIssues.single().dependsOn.toSet() shouldBe executionIssues.map { it.id }.toSet()
        approvalIssues.single().dependsOn shouldBe listOf(reviewIssues.single().id)
    }

    test("runtime synthesizes a follow-up goal when blocked work needs another CEO loop") {
        val appHome = Files.createTempDirectory("desktop-runtime-followup-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-followup-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Follow Up Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val seededAgents = service.listCompanyAgentDefinitions(company.id)
        val seededChief = seededAgents.first { it.title == "CEO" }
        val seededBuilder = seededAgents.first { it.title == "Builder" }
        service.updateCompanyAgentDefinition(company.id, seededChief.id, title = "CEO", agentCli = "codex", roleSummary = "ceo strategy and approval")
        service.updateCompanyAgentDefinition(company.id, seededBuilder.id, title = "Builder", agentCli = "/bin/echo", roleSummary = "builder implementation")

        val goal = service.createGoal(
            companyId = company.id,
            title = "Ship autonomous work",
            description = "Deliver the initial company objective.",
            autonomyEnabled = false
        )
        val issue = service.listIssues(goal.id).first { it.kind == "execution" }
        val now = System.currentTimeMillis()
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == issue.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = now) else it
                }
            )
        )

        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        val state = stateStore.load()
        val followUpGoals = state.goals.filter {
            it.companyId == company.id &&
                it.id != goal.id &&
                it.operatingPolicy == "auto-follow-up:issue:${issue.id}"
        }
        followUpGoals shouldHaveSize 1
        followUpGoals.single().title shouldContain issue.title
        state.issues.any { it.goalId == followUpGoals.single().id } shouldBe true
    }

    test("runtime does not synthesize recursive follow-up goals from blocked remediation work") {
        val appHome = Files.createTempDirectory("desktop-runtime-followup-recursive-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-followup-recursive-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Recursive Follow Up Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Ship autonomous work",
            description = "Deliver the initial company objective.",
            autonomyEnabled = false
        )
        val originalIssue = service.listIssues(goal.id).first { it.kind == "execution" }
        val now = System.currentTimeMillis()
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == originalIssue.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = now) else it
                }
            )
        )

        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        val firstFollowUpGoal = stateStore.load().goals.first {
            it.companyId == company.id && it.operatingPolicy == "auto-follow-up:issue:${originalIssue.id}"
        }
        val remediationIssue = stateStore.load().issues.first {
            it.goalId == firstFollowUpGoal.id && it.kind == "execution"
        }
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == remediationIssue.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = System.currentTimeMillis()) else it
                }
            )
        )

        service.runCompanyRuntimeTick(company.id)

        val followUpGoals = stateStore.load().goals.filter {
            it.companyId == company.id && it.operatingPolicy?.startsWith("auto-follow-up:issue:") == true
        }
        followUpGoals shouldHaveSize 1
    }

    test("runtime retries blocked remediation issues inside an existing follow-up goal") {
        val appHome = Files.createTempDirectory("desktop-runtime-followup-retry-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-followup-retry-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Retry Follow Up Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Ship autonomous work",
            description = "Deliver the initial company objective.",
            autonomyEnabled = false
        )
        val originalIssue = service.listIssues(goal.id).first { it.kind == "execution" }
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == originalIssue.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = System.currentTimeMillis()) else it
                }
            )
        )
        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        val followUpGoal = stateStore.load().goals.first {
            it.companyId == company.id && it.operatingPolicy == "auto-follow-up:issue:${originalIssue.id}"
        }
        val remediationIssue = stateStore.load().issues.first {
            it.goalId == followUpGoal.id && it.kind == "execution"
        }
        val existingRemediationTask = service.createTask(
            workspaceId = remediationIssue.workspaceId,
            title = remediationIssue.title,
            prompt = remediationIssue.description,
            agents = listOf("codex"),
            issueId = remediationIssue.id
        )
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == remediationIssue.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = System.currentTimeMillis()) else it
                },
                tasks = stateStore.load().tasks.map {
                    if (it.id == existingRemediationTask.id) {
                        it.copy(status = DesktopTaskStatus.FAILED, updatedAt = System.currentTimeMillis())
                    } else {
                        it
                    }
                }
            )
        )

        service.runCompanyRuntimeTick(company.id)

        val updatedIssue = stateStore.load().issues.first { it.id == remediationIssue.id }
        updatedIssue.status shouldNotBe IssueStatus.BLOCKED
        stateStore.load().tasks.count { it.issueId == remediationIssue.id } shouldBe 2
    }

    test("runtime archives recursively nested follow-up goals from older broken state") {
        val appHome = Files.createTempDirectory("desktop-runtime-followup-archive-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-followup-archive-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Archive Follow Up Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Ship autonomous work",
            description = "Deliver the initial company objective.",
            autonomyEnabled = false
        )
        val originalIssue = service.listIssues(goal.id).first { it.kind == "execution" }
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == originalIssue.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = System.currentTimeMillis()) else it
                }
            )
        )
        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        val firstFollowUpGoal = stateStore.load().goals.first {
            it.companyId == company.id && it.operatingPolicy == "auto-follow-up:issue:${originalIssue.id}"
        }
        val firstFollowUpExecution = stateStore.load().issues.first {
            it.goalId == firstFollowUpGoal.id && it.kind == "execution"
        }
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == firstFollowUpExecution.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = System.currentTimeMillis()) else it
                }
            )
        )
        val nestedGoal = service.createGoal(
            companyId = company.id,
            title = "Resolve follow-up for \"${firstFollowUpExecution.title}\"",
            description = "Incorrect recursive remediation goal.",
            autonomyEnabled = true,
            operatingPolicy = "auto-follow-up:issue:${firstFollowUpExecution.id}"
        )
        service.runCompanyRuntimeTick(company.id)

        val updatedNestedGoal = stateStore.load().goals.first { it.id == nestedGoal.id }
        updatedNestedGoal.status shouldBe GoalStatus.COMPLETED
        stateStore.load().issues.filter { it.goalId == nestedGoal.id }.all { it.status == IssueStatus.CANCELED } shouldBe true
    }

    test("runtime reconciles orphaned failed tasks without run records") {
        val appHome = Files.createTempDirectory("desktop-runtime-orphaned-task-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-orphaned-task-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Orphaned Task Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Review orphaned failed task",
            description = "Recover an issue with a failed task but no run record.",
            autonomyEnabled = false
        )
        val reviewIssue = service.listIssues(goal.id).first { it.kind == "review" }
        val task = service.createTask(
            workspaceId = reviewIssue.workspaceId,
            title = reviewIssue.title,
            prompt = reviewIssue.description,
            agents = listOf("codex"),
            issueId = reviewIssue.id
        )
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == reviewIssue.id) it.copy(status = IssueStatus.IN_PROGRESS, updatedAt = System.currentTimeMillis()) else it
                },
                tasks = stateStore.load().tasks.map {
                    if (it.id == task.id) it.copy(status = DesktopTaskStatus.FAILED, updatedAt = System.currentTimeMillis()) else it
                }
            )
        )
        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)

        service.runCompanyRuntimeTick(company.id)

        val updatedIssue = stateStore.load().issues.first { it.id == reviewIssue.id }
        updatedIssue.status shouldBe IssueStatus.BLOCKED
    }

    test("review issues complete successfully without requiring code publish") {
        val appHome = Files.createTempDirectory("desktop-review-run-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-review-run-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val agentExecutor = mockk<AgentExecutor>()
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } returns WorktreeBinding(
            branchName = "codex/cotor/review-test/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/review-test/codex")
        )
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } returns AgentResult(
            agentName = "codex",
            isSuccess = true,
            output = "Review complete. No code changes required.",
            error = null,
            duration = 25,
            metadata = emptyMap(),
            processId = 4321
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            commandAvailability = { command -> command == "codex" }
        )

        val company = service.createCompany(
            name = "Review Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Review a completed change",
            description = "Confirm the completed work is acceptable.",
            autonomyEnabled = false
        )
        val reviewIssue = service.createIssue(
            companyId = company.id,
            goalId = goal.id,
            title = "Review completed implementation work",
            description = "Review the branch and summarize any remaining risk.",
            kind = "review"
        )

        service.runIssue(reviewIssue.id)

        withTimeout(5_000) {
            while (true) {
                val snapshot = stateStore.load()
                val refreshedIssue = snapshot.issues.first { it.id == reviewIssue.id }
                val relatedTask = snapshot.tasks.firstOrNull { it.issueId == reviewIssue.id }
                val relatedRuns = relatedTask?.let { task -> snapshot.runs.filter { it.taskId == task.id } }.orEmpty()
                if (refreshedIssue.status == IssueStatus.DONE && relatedRuns.any { it.status == AgentRunStatus.COMPLETED }) {
                    return@withTimeout
                }
                delay(25)
            }
        }

        val finalState = stateStore.load()
        finalState.issues.first { it.id == reviewIssue.id }.status shouldBe IssueStatus.DONE
        val finalTask = finalState.tasks.first { it.issueId == reviewIssue.id }
        finalTask.status shouldBe DesktopTaskStatus.COMPLETED
        val finalRun = finalState.runs.first { it.taskId == finalTask.id }
        finalRun.status shouldBe AgentRunStatus.COMPLETED
        finalRun.publish shouldBe null
    }

    test("company dashboard reconciles stale running runs whose process already exited") {
        val appHome = Files.createTempDirectory("desktop-runtime-stale-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-stale-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Stale Run Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Recover stale agent runs",
            description = "Detect dead child processes and unblock the company runtime.",
            autonomyEnabled = false
        )
        val issue = service.listIssues(goal.id).first { it.kind == "execution" }
        val now = System.currentTimeMillis()
        val task = AgentTask(
            id = "stale-task",
            workspaceId = issue.workspaceId,
            issueId = issue.id,
            title = issue.title,
            prompt = issue.description,
            agents = listOf("codex"),
            status = DesktopTaskStatus.RUNNING,
            createdAt = now,
            updatedAt = now
        )
        val run = AgentRun(
            id = "stale-run",
            taskId = task.id,
            workspaceId = issue.workspaceId,
            repositoryId = REPOSITORY_ID,
            agentName = "codex",
            branchName = "codex/cotor/stale",
            worktreePath = repoRoot.toString(),
            status = AgentRunStatus.RUNNING,
            processId = Long.MAX_VALUE,
            createdAt = now,
            updatedAt = now
        )
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == issue.id) it.copy(status = IssueStatus.IN_PROGRESS, updatedAt = now) else it
                },
                tasks = stateStore.load().tasks + task,
                runs = stateStore.load().runs + run
            )
        )

        service.companyDashboard(company.id)

        val reconciled: DesktopAppState = withTimeout(5_000) {
            while (true) {
                val current = stateStore.load()
                if (current.runs.first { it.id == run.id }.status == AgentRunStatus.FAILED) {
                    return@withTimeout current
                }
                delay(25)
            }
            error("unreachable")
        }
        reconciled.runs.first { it.id == run.id }.status shouldBe AgentRunStatus.FAILED
        reconciled.runs.first { it.id == run.id }.error shouldContain "exited before Cotor recorded a final result"
        reconciled.tasks.first { it.id == task.id }.status shouldBe DesktopTaskStatus.FAILED
        reconciled.issues.first { it.id == issue.id }.status shouldBe IssueStatus.BLOCKED
    }

    test("company dashboard reconciles running runs that never recorded a process id") {
        val appHome = Files.createTempDirectory("desktop-runtime-nopid-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-nopid-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Missing PID Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Recover no-pid runs",
            description = "Treat long-running tasks without a recorded pid as stale.",
            autonomyEnabled = false
        )
        val issue = service.listIssues(goal.id).first { it.kind == "execution" }
        val staleAt = System.currentTimeMillis() - 60_000L
        val task = AgentTask(
            id = "missing-pid-task",
            workspaceId = issue.workspaceId,
            issueId = issue.id,
            title = issue.title,
            prompt = issue.description,
            agents = listOf("codex"),
            status = DesktopTaskStatus.RUNNING,
            createdAt = staleAt,
            updatedAt = staleAt
        )
        val run = AgentRun(
            id = "missing-pid-run",
            taskId = task.id,
            workspaceId = issue.workspaceId,
            repositoryId = REPOSITORY_ID,
            agentName = "codex",
            branchName = "codex/cotor/no-pid",
            worktreePath = repoRoot.toString(),
            status = AgentRunStatus.RUNNING,
            processId = null,
            createdAt = staleAt,
            updatedAt = staleAt
        )
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == issue.id) it.copy(status = IssueStatus.IN_PROGRESS, updatedAt = staleAt) else it
                },
                tasks = stateStore.load().tasks + task,
                runs = stateStore.load().runs + run
            )
        )

        service.companyDashboard(company.id)

        val reconciled: DesktopAppState = withTimeout(5_000) {
            while (true) {
                val current = stateStore.load()
                if (current.runs.first { it.id == run.id }.status == AgentRunStatus.FAILED) {
                    return@withTimeout current
                }
                delay(25)
            }
            error("unreachable")
        }
        reconciled.runs.first { it.id == run.id }.status shouldBe AgentRunStatus.FAILED
        reconciled.tasks.first { it.id == task.id }.status shouldBe DesktopTaskStatus.FAILED
        reconciled.issues.first { it.id == issue.id }.status shouldBe IssueStatus.BLOCKED
    }

    test("company dashboard resumes persisted running runtimes after a backend restart") {
        val appHome = Files.createTempDirectory("desktop-runtime-resume-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-resume-test").resolve("repo"))
        val worktreeRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-resume-worktree"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val agentExecutor = mockk<AgentExecutor>()
        val capturedAgents = mutableListOf<String>()
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } answers { invocation.args[0] as Path }
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns "https://github.com/heodongun/cotor.git"
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } answers {
            val agentName = invocation.args[3] as String
            WorktreeBinding(
                branchName = "codex/cotor/runtime-resume/$agentName",
                worktreePath = worktreeRoot
            )
        }
        coEvery { gitWorkspaceService.publishRun(any(), any(), any(), any(), any()) } returns PublishMetadata()
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } answers {
            val agent = invocation.args[0] as AgentConfig
            capturedAgents += agent.name
            AgentResult(
                agentName = agent.name,
                isSuccess = true,
                output = "resume-ok",
                error = null,
                duration = 20,
                metadata = emptyMap(),
                processId = 777
            )
        }

        val bootstrapService = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            commandAvailability = { command -> command == "codex" || command == "/bin/echo" }
        )
        val company = bootstrapService.createCompany(
            name = "Resume Runtime Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        bootstrapService.createCompanyAgentDefinition(
            companyId = company.id,
            title = "Builder",
            agentCli = "codex",
            roleSummary = "builder implementation delivery"
        )
        val goal = bootstrapService.createGoal(
            companyId = company.id,
            title = "Resume a running runtime",
            description = "Make sure persisted company runtimes keep executing after the backend restarts.",
            autonomyEnabled = false
        )
        stateStore.save(
            stateStore.load().copy(
                goals = stateStore.load().goals.map {
                    if (it.id == goal.id) it.copy(autonomyEnabled = true, status = GoalStatus.ACTIVE, updatedAt = System.currentTimeMillis()) else it
                },
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        tickIntervalSeconds = 60,
                        lastAction = "persisted-running"
                    )
                )
            )
        )
        capturedAgents.clear()

        val restartedService = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            commandAvailability = { command -> command == "codex" || command == "/bin/echo" }
        )

        restartedService.companyDashboard(company.id)

        withTimeout(5_000) {
            while (capturedAgents.isEmpty()) {
                delay(25)
            }
        }

        capturedAgents.shouldNotBeEmpty()
        stateStore.load().tasks.any { it.issueId != null && it.status != DesktopTaskStatus.QUEUED } shouldBe true
        restartedService.stopCompanyRuntime(goal.companyId).status shouldBe CompanyRuntimeStatus.STOPPED
    }

    test("company execution resolves arbitrary installed CLI agents through the command plugin") {
        val appHome = Files.createTempDirectory("desktop-runtime-cli-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-cli-test").resolve("repo"))
        val worktreeRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-cli-worktree"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val agentExecutor = mockk<AgentExecutor>()
        val capturedAgents = mutableListOf<AgentConfig>()
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } answers { invocation.args[0] as Path }
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns "https://github.com/heodongun/cotor.git"
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } answers {
            val agentName = invocation.args[3] as String
            WorktreeBinding(
                branchName = "codex/cotor/cli/${agentName.substringAfterLast('/').ifBlank { "agent" }}",
                worktreePath = worktreeRoot
            )
        }
        coEvery { gitWorkspaceService.publishRun(any(), any(), any(), any(), any()) } returns PublishMetadata()
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } answers {
            capturedAgents += invocation.args[0] as AgentConfig
            val agent = invocation.args[0] as AgentConfig
            AgentResult(
                agentName = agent.name,
                isSuccess = true,
                output = "ok",
                error = null,
                duration = 25,
                metadata = emptyMap(),
                processId = 11
            )
        }
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            commandAvailability = { command -> command == "codex" || command == "/bin/echo" }
        )

        val company = service.createCompany(
            name = "CLI Roster Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val seededAgents = service.listCompanyAgentDefinitions(company.id)
        val seededChief = seededAgents.first { it.title == "CEO" }
        val seededBuilder = seededAgents.first { it.title == "Builder" }
        service.updateCompanyAgentDefinition(company.id, seededChief.id, title = "CEO", agentCli = "codex", roleSummary = "ceo approval")
        service.updateCompanyAgentDefinition(company.id, seededBuilder.id, title = "Builder", agentCli = "/bin/echo", roleSummary = "builder implementation")

        val goal = service.createGoal(
            companyId = company.id,
            title = "Run arbitrary roster CLI",
            description = "Build the implementation and hand it back to the CEO.",
            autonomyEnabled = true
        )

        withTimeout(5_000) {
            while (capturedAgents.isEmpty()) {
                delay(25)
            }
        }

        val builderAgent = capturedAgents.first { it.name == "/bin/echo" }
        builderAgent.pluginClass shouldBe "com.cotor.data.plugin.CommandPlugin"
        builderAgent.parameters["argvJson"] shouldBe """["/bin/echo","{input}"]"""
        coVerify(atLeast = 1) { agentExecutor.executeAgent(any(), any(), any()) }
        service.stopCompanyRuntime(goal.companyId).status shouldBe CompanyRuntimeStatus.STOPPED
    }

    test("company, workflow, and agent memory files are persisted and injected into execution prompts") {
        val appHome = Files.createTempDirectory("desktop-runtime-memory-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-memory-test").resolve("repo"))
        val worktreeRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-memory-worktree"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val agentExecutor = mockk<AgentExecutor>()
        val capturedInputs = mutableListOf<String?>()
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } answers { invocation.args[0] as Path }
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns "https://github.com/heodongun/cotor.git"
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } answers {
            val agentName = invocation.args[3] as String
            WorktreeBinding(
                branchName = "codex/cotor/memory/${agentName}",
                worktreePath = worktreeRoot.resolve(agentName)
            )
        }
        coEvery { gitWorkspaceService.publishRun(any(), any(), any(), any(), any()) } returns PublishMetadata()
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } answers {
            capturedInputs += invocation.args[1] as String?
            val agent = invocation.args[0] as AgentConfig
            AgentResult(
                agentName = agent.name,
                isSuccess = true,
                output = "memory-ok",
                error = null,
                duration = 50,
                metadata = emptyMap(),
                processId = 123
            )
        }
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            commandAvailability = { command -> command == "codex" || command == "/bin/echo" }
        )

        val company = service.createCompany(
            name = "Memory Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val ceo = service.createCompanyAgentDefinition(
            companyId = company.id,
            title = "CEO",
            agentCli = "codex",
            roleSummary = "ceo approval",
            specialties = listOf("planning", "delegation"),
            collaborationInstructions = "Route implementation work to builders and ask QA for verification before merge.",
            memoryNotes = "Final approver for release."
        )
        service.createCompanyAgentDefinition(
            companyId = company.id,
            title = "Builder",
            agentCli = "/bin/echo",
            roleSummary = "builder implementation",
            specialties = listOf("implementation", "integration"),
            preferredCollaboratorIds = listOf(ceo.id),
            collaborationInstructions = "Escalate ambiguity back to the CEO."
        )

        val goal = service.createGoal(
            companyId = company.id,
            title = "Persistent memory",
            description = "Remember company context and execute with stored workflow memory.",
            autonomyEnabled = true
        )

        withTimeout(5_000) {
            while (capturedInputs.isEmpty()) {
                delay(25)
            }
        }

        val contextRoot = appHome.resolve(".cotor").resolve("companies").resolve("memory-co")
        contextRoot.resolve("company.md").exists() shouldBe true
        contextRoot.resolve("projects").resolve("default.md").exists() shouldBe true
        contextRoot.resolve("workflows").resolve("active.md").exists() shouldBe true
        stateStore.load().companyAgentDefinitions
            .filter { it.companyId == company.id }
            .forEach { definition ->
                contextRoot.resolve("agents").resolve("${definition.id}.md").exists() shouldBe true
            }

        val prompt = capturedInputs.first().orEmpty()
        prompt.contains("Persistent memory") shouldBe true
        prompt.contains("Company memory:") shouldBe true
        prompt.contains("Workflow memory:") shouldBe true
        prompt.contains("Agent memory:") shouldBe true
        prompt.length shouldBeLessThan 8_000
        contextRoot.resolve("agents").resolve("${ceo.id}.md").toFile().readText().contains("Final approver for release.") shouldBe true
        contextRoot.resolve("agents").resolve("${ceo.id}.md").toFile().readText().contains("specialties: planning, delegation") shouldBe true
        service.stopCompanyRuntime(goal.companyId).status shouldBe CompanyRuntimeStatus.STOPPED
    }

    test("deleteCompany removes company state, runtime state, and stored context files") {
        val appHome = Files.createTempDirectory("desktop-company-delete-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-company-delete-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Delete Me Inc",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        service.createCompanyAgentDefinition(
            companyId = company.id,
            title = "QA Lead",
            agentCli = "codex",
            roleSummary = "qa and verification"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Clean delete path",
            description = "Ensure deleting a company removes its local state.",
            autonomyEnabled = false
        )
        val issue = service.decomposeGoal(goal.id).first()
        val contextRoot = appHome.resolve(".cotor").resolve("companies").resolve("delete-me-inc")
        contextRoot.exists() shouldBe true

        val now = System.currentTimeMillis()
        stateStore.save(
            stateStore.load().copy(
                reviewQueue = listOf(
                    ReviewQueueItem(
                        id = "rq-delete",
                        companyId = company.id,
                        issueId = issue.id,
                        runId = "run-delete",
                        status = ReviewQueueStatus.AWAITING_REVIEW,
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        lastAction = "running"
                    )
                ),
                runtime = CompanyRuntimeSnapshot(
                    companyId = company.id,
                    status = CompanyRuntimeStatus.RUNNING,
                    lastAction = "running"
                ),
                signals = listOf(
                    OpsSignal(
                        id = "signal-delete",
                        companyId = company.id,
                        goalId = goal.id,
                        issueId = issue.id,
                        source = "review",
                        message = "Needs attention",
                        createdAt = now
                    )
                )
            )
        )

        service.deleteCompany(company.id).id shouldBe company.id

        val state = stateStore.load()
        state.companies.filter { it.id == company.id }.shouldBeEmpty()
        state.companyAgentDefinitions.filter { it.companyId == company.id }.shouldBeEmpty()
        state.projectContexts.filter { it.companyId == company.id }.shouldBeEmpty()
        state.goals.filter { it.companyId == company.id }.shouldBeEmpty()
        state.issues.filter { it.companyId == company.id }.shouldBeEmpty()
        state.reviewQueue.filter { it.companyId == company.id }.shouldBeEmpty()
        state.signals.filter { it.companyId == company.id }.shouldBeEmpty()
        state.companyRuntimes.filter { it.companyId == company.id }.shouldBeEmpty()
        state.runtime.companyId shouldBe null
        contextRoot.exists() shouldBe false
    }

    test("deleteGoal removes derived issues, linked execution state, and stored goal context") {
        val appHome = Files.createTempDirectory("desktop-goal-delete-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-goal-delete-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Goal Delete Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Delete a goal",
            description = "Ensure goal deletion clears its state.",
            autonomyEnabled = false
        )
        val issue = service.listIssues(goal.id).first()
        val goalFile = appHome.resolve(".cotor").resolve("companies").resolve("goal-delete-co").resolve("goals").resolve("${goal.id}.md")
        val issueFile = appHome.resolve(".cotor").resolve("companies").resolve("goal-delete-co").resolve("issues").resolve("${issue.id}.md")
        goalFile.exists() shouldBe true
        issueFile.exists() shouldBe true

        val now = System.currentTimeMillis()
        stateStore.save(
            stateStore.load().copy(
                tasks = listOf(
                    AgentTask(
                        id = "goal-delete-task",
                        workspaceId = issue.workspaceId,
                        issueId = issue.id,
                        title = issue.title,
                        prompt = issue.description,
                        agents = listOf("codex"),
                        status = DesktopTaskStatus.COMPLETED,
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                runs = listOf(
                    AgentRun(
                        id = "goal-delete-run",
                        taskId = "goal-delete-task",
                        workspaceId = issue.workspaceId,
                        repositoryId = REPOSITORY_ID,
                        agentName = "codex",
                        branchName = "codex/test",
                        worktreePath = repoRoot.toString(),
                        status = AgentRunStatus.COMPLETED,
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                reviewQueue = listOf(
                    ReviewQueueItem(
                        id = "goal-delete-rq",
                        companyId = company.id,
                        issueId = issue.id,
                        runId = "goal-delete-run",
                        status = ReviewQueueStatus.AWAITING_REVIEW,
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                signals = listOf(
                    OpsSignal(
                        id = "goal-delete-signal",
                        companyId = company.id,
                        goalId = goal.id,
                        issueId = issue.id,
                        source = "runtime",
                        message = "Delete me",
                        createdAt = now
                    )
                )
            )
        )

        service.deleteGoal(goal.id).id shouldBe goal.id

        val state = stateStore.load()
        state.goals.firstOrNull { it.id == goal.id } shouldBe null
        state.issues.firstOrNull { it.id == issue.id } shouldBe null
        state.tasks.firstOrNull { it.issueId == issue.id } shouldBe null
        state.runs.firstOrNull { it.taskId == "goal-delete-task" } shouldBe null
        state.reviewQueue.firstOrNull { it.issueId == issue.id } shouldBe null
        state.signals.firstOrNull { it.goalId == goal.id } shouldBe null
        goalFile.exists() shouldBe false
        issueFile.exists() shouldBe false
    }

    test("deleteIssue removes linked execution state but keeps the parent goal") {
        val appHome = Files.createTempDirectory("desktop-issue-delete-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-issue-delete-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val service = testService(
            processManager = FakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Issue Delete Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Delete an issue",
            description = "Ensure issue deletion only removes linked execution state.",
            autonomyEnabled = false
        )
        val issue = service.listIssues(goal.id).first()
        val issueFile = appHome.resolve(".cotor").resolve("companies").resolve("issue-delete-co").resolve("issues").resolve("${issue.id}.md")
        issueFile.exists() shouldBe true

        val now = System.currentTimeMillis()
        stateStore.save(
            stateStore.load().copy(
                tasks = listOf(
                    AgentTask(
                        id = "issue-delete-task",
                        workspaceId = issue.workspaceId,
                        issueId = issue.id,
                        title = issue.title,
                        prompt = issue.description,
                        agents = listOf("codex"),
                        status = DesktopTaskStatus.RUNNING,
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                runs = listOf(
                    AgentRun(
                        id = "issue-delete-run",
                        taskId = "issue-delete-task",
                        workspaceId = issue.workspaceId,
                        repositoryId = REPOSITORY_ID,
                        agentName = "codex",
                        branchName = "codex/test",
                        worktreePath = repoRoot.toString(),
                        status = AgentRunStatus.RUNNING,
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                reviewQueue = listOf(
                    ReviewQueueItem(
                        id = "issue-delete-rq",
                        companyId = company.id,
                        issueId = issue.id,
                        runId = "issue-delete-run",
                        status = ReviewQueueStatus.AWAITING_QA,
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                signals = listOf(
                    OpsSignal(
                        id = "issue-delete-signal",
                        companyId = company.id,
                        goalId = goal.id,
                        issueId = issue.id,
                        source = "runtime",
                        message = "Delete this issue",
                        createdAt = now
                    )
                )
            )
        )

        service.deleteIssue(issue.id).id shouldBe issue.id

        val state = stateStore.load()
        state.goals.firstOrNull { it.id == goal.id }?.id shouldBe goal.id
        state.issues.firstOrNull { it.id == issue.id } shouldBe null
        state.tasks.firstOrNull { it.issueId == issue.id } shouldBe null
        state.runs.firstOrNull { it.taskId == "issue-delete-task" } shouldBe null
        state.reviewQueue.firstOrNull { it.issueId == issue.id } shouldBe null
        state.signals.firstOrNull { it.issueId == issue.id } shouldBe null
        issueFile.exists() shouldBe false
    }

    test("createIssue creates a company-scoped manual issue for the selected goal") {
        val appHome = Files.createTempDirectory("desktop-app-service-create-issue")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-create-issue-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Manual Issue Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        service.createCompanyAgentDefinition(
            companyId = company.id,
            title = "Builder",
            agentCli = "codex",
            roleSummary = "implementation and delivery"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Ship docs",
            description = "Improve project documentation.",
            autonomyEnabled = false
        )

        val issue = service.createIssue(
            companyId = company.id,
            goalId = goal.id,
            title = "Write install troubleshooting section",
            description = "Document the common setup failures.",
            kind = "manual"
        )

        issue.companyId shouldBe company.id
        issue.goalId shouldBe goal.id
        issue.sourceSignal shouldBe "manual"
        stateStore.load().issues.any { it.id == issue.id && it.companyId == company.id } shouldBe true
    }

    test("createCompany defaults to Local Cotor when Codex app server is selected globally without a base URL") {
        val appHome = Files.createTempDirectory("desktop-app-service-company-backend")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-company-backend-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        stateStore.save(
            DesktopAppState(
                backendSettings = DesktopBackendSettings(
                    defaultBackendKind = ExecutionBackendKind.CODEX_APP_SERVER,
                    backends = DesktopBackendSettings().backends.map { config ->
                        if (config.kind == ExecutionBackendKind.CODEX_APP_SERVER) {
                            config.copy(baseUrl = null, enabled = true)
                        } else {
                            config
                        }
                    }
                )
            )
        )
        seedWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Safe Backend Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )

        company.backendKind shouldBe ExecutionBackendKind.LOCAL_COTOR
    }

    test("createCompany initializes a git repository when the selected folder is not yet a repo") {
        val appHome = Files.createTempDirectory("desktop-app-service-init-company")
        val rootFolder = Files.createTempDirectory("desktop-app-service-init-company-root")
        val stateStore = DesktopStateStore { appHome }
        val processManager = CoroutineProcessManager(LoggerFactory.getLogger("DesktopAppServiceTest"))
        val gitWorkspaceService = GitWorkspaceService(
            processManager = processManager,
            stateStore = stateStore,
            logger = LoggerFactory.getLogger("DesktopAppServiceTest")
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Fresh Folder Co",
            rootPath = rootFolder.toString(),
            defaultBaseBranch = "master"
        )

        company.rootPath shouldBe rootFolder.toAbsolutePath().normalize().toString()
        Files.exists(rootFolder.resolve(".git")) shouldBe true
        stateStore.load().repositories.any { it.localPath == company.rootPath && it.defaultBranch == "master" } shouldBe true
    }

    test("getChanges falls back to an empty summary when a run worktree is unavailable") {
        val appHome = Files.createTempDirectory("desktop-app-service-empty-changes")
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk<AgentExecutor>(relaxed = true)
        )

        stateStore.save(
            DesktopAppState(
                repositories = listOf(
                    ManagedRepository(
                        id = REPOSITORY_ID,
                        name = "repo",
                        localPath = appHome.resolve("repo").toString(),
                        sourceKind = RepositorySourceKind.LOCAL,
                        defaultBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                workspaces = listOf(
                    Workspace(
                        id = WORKSPACE_ID,
                        repositoryId = REPOSITORY_ID,
                        name = "repo · master",
                        baseBranch = "master",
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                tasks = listOf(
                    AgentTask(
                        id = "task-empty",
                        workspaceId = WORKSPACE_ID,
                        title = "Task",
                        prompt = "Prompt",
                        agents = listOf("codex"),
                        status = DesktopTaskStatus.QUEUED,
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                ),
                runs = listOf(
                    AgentRun(
                        id = "run-empty",
                        taskId = "task-empty",
                        workspaceId = WORKSPACE_ID,
                        repositoryId = REPOSITORY_ID,
                        agentName = "codex",
                        branchName = "codex/cotor/task/codex",
                        worktreePath = appHome.resolve("missing-worktree").toString(),
                        status = AgentRunStatus.FAILED,
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                )
            )
        )

        val summary = service.getChanges("run-empty")

        summary.runId shouldBe "run-empty"
        summary.baseBranch shouldBe "master"
        summary.patch shouldBe ""
        summary.changedFiles.shouldBeEmpty()
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
    stateStore: DesktopStateStore? = null,
    linearTracker: LinearTrackerAdapter = FakeLinearTrackerAdapter()
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
        agentExecutor = mockk<AgentExecutor>(relaxed = true),
        linearTracker = linearTracker
    )
}

private class FakeLinearTrackerAdapter : LinearTrackerAdapter {
    data class SyncCall(
        val issueId: String,
        val desiredStateName: String?,
        val assigneeId: String?
    )

    data class CommentCall(
        val linearIssueId: String,
        val body: String
    )

    val syncCalls = mutableListOf<SyncCall>()
    val commentCalls = mutableListOf<CommentCall>()

    override suspend fun health(config: LinearConnectionConfig): Result<String> =
        Result.success("ok")

    override suspend fun syncIssue(
        config: LinearConnectionConfig,
        issue: CompanyIssue,
        desiredStateName: String?,
        assigneeId: String?
    ): Result<LinearIssueMirror> {
        syncCalls += SyncCall(issue.id, desiredStateName, assigneeId)
        return Result.success(
            LinearIssueMirror(
                id = issue.linearIssueId ?: "lin-${issue.id}",
                identifier = issue.linearIssueIdentifier ?: "COT-${syncCalls.size}",
                url = issue.linearIssueUrl ?: "https://linear.app/issue/${issue.id}",
                stateName = desiredStateName
            )
        )
    }

    override suspend fun createComment(
        config: LinearConnectionConfig,
        linearIssueId: String,
        body: String
    ): Result<Unit> {
        commentCalls += CommentCall(linearIssueId, body)
        return Result.success(Unit)
    }
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
        workingDirectory: Path?,
        onStart: ((Long) -> Unit)?
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
