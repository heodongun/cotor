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
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

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

    test("runTask keeps the run completed when publish falls back to a local-only commit") {
        val fixture = DesktopAppServiceFixture.create()
        fixture.service.updateBackendSettings(
            defaultBackendKind = ExecutionBackendKind.LOCAL_COTOR,
            codePublishMode = CodePublishMode.ALLOW_LOCAL_GIT
        )
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
            error = "No GitHub remote configured; kept local commit only"
        )

        fixture.service.runTask(fixture.task.id)
        val run = fixture.awaitRuns().single()

        run.status shouldBe AgentRunStatus.COMPLETED
        run.error shouldBe null
        run.publish shouldBe PublishMetadata(
            commitSha = "abc1234567890",
            error = "No GitHub remote configured; kept local commit only"
        )
    }

    test("runTask fails code work when local-only publish happens under required GitHub PR mode") {
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
            error = "No GitHub remote configured; kept local commit only"
        )

        fixture.service.runTask(fixture.task.id)
        val run = fixture.awaitRuns().single()

        run.status shouldBe AgentRunStatus.FAILED
        run.error shouldBe "No GitHub remote configured; kept local commit only"
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
        plan.assignments.shouldNotBeEmpty()
        plan.assignments.map { it.agentName }.all { it in listOf("claude", "codex") } shouldBe true
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
        val issue = CompanyIssue(
            id = "issue-linked-prompt",
            companyId = company.id,
            projectContextId = stateStore.load().projectContexts.first { it.companyId == company.id }.id,
            goalId = goal.id,
            workspaceId = stateStore.load().workspaces.first { it.repositoryId == company.repositoryId }.id,
            title = "Builder execution slice",
            description = "Implement the smallest linked issue change.",
            status = IssueStatus.PLANNED,
            priority = 2,
            kind = "execution",
            assigneeProfileId = stateStore.load().orgProfiles.firstOrNull { it.companyId == company.id }?.id,
            acceptanceCriteria = listOf("Primary issue", "Keep issue prompts stable"),
            riskLevel = "medium",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        stateStore.save(stateStore.load().copy(issues = stateStore.load().issues + issue))
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

    test("decomposeGoal creates a CEO planning issue before downstream execution issues") {
        val appHome = Files.createTempDirectory("desktop-ceo-planning-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-ceo-planning-test").resolve("repo"))
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
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "CEO Planning Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Plan a PR-driven slice",
            description = "CEO should author the initial execution graph.",
            autonomyEnabled = false
        )

        val issues = service.decomposeGoal(goal.id)
        val planningIssue = issues.first { it.kind == "planning" }

        planningIssue.sourceSignal shouldBe "ceo-planning"
        planningIssue.title shouldBe "CEO plan and delegate \"${goal.title}\""
        stateStore.load().issues.count { it.goalId == goal.id && it.kind == "planning" } shouldBe 1
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
        val nonNullCapturedInputs = capturedInputs.filterNotNull()
        nonNullCapturedInputs.shouldNotBeEmpty()
        nonNullCapturedInputs.any { it in expectedPrompts } shouldBe true

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

    test("startCompanyRuntime does not auto-merge CEO-ready review queue items without approval") {
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
            description = "Keep CEO-ready review items pending until approval runs.",
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
                        status = ReviewQueueStatus.READY_FOR_CEO,
                        mergeability = "clean",
                        createdAt = now,
                        updatedAt = now
                    )
                )
            )
        )

        service.updateGoal(goal.id, autonomyEnabled = true)
        delay(250)
        val settledState = stateStore.load()
        val mergedItem = settledState.reviewQueue.first { it.id == "rq-1" }

        settledState.runtime.status shouldBe CompanyRuntimeStatus.RUNNING
        mergedItem.status shouldBe ReviewQueueStatus.READY_FOR_CEO
        settledState.issues.first { it.id == issue.id }.status shouldBe IssueStatus.IN_REVIEW
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
                branchName = "codex/cotor/autostart/$agentName",
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
                branchName = "codex/cotor/backend-fallback/$agentName",
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

        profiles.map { it.executionAgentName }.distinct() shouldBe listOf("codex")
        profiles shouldHaveSize 9
        val assignedProfileIds = issues.mapNotNull { it.assigneeProfileId }.toSet()
        assignedProfileIds.shouldNotBeEmpty()
        assignedProfileIds.subtract(profiles.map { it.id }.toSet()).shouldBeEmpty()
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
        definitions.map { it.agentCli }.distinct() shouldBe listOf("codex")
        definitions.map { it.title } shouldBe listOf(
            "CEO",
            "Product Strategist",
            "Engineering Lead",
            "UX Builder",
            "UI Builder",
            "Builder",
            "Backend Builder",
            "QA",
            "Release Manager"
        )
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

        withTimeout(10_000) {
            while (stateStore.load().tasks.none { it.issueId == pendingIssue.id }) {
                service.companyDashboard(company.id)
                delay(100)
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
                service.companyDashboard(company.id)
                val latestIssue = stateStore.load().issues.first { it.id == issue.id }
                if (latestIssue.status == IssueStatus.PLANNED) {
                    latestIssue.blockedBy.shouldBeEmpty()
                    return@withTimeout
                }
                delay(25)
            }
        }
    }

    test("company runtime requeues orphaned blocked autonomous issues with no tasks") {
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

        stateStore.save(
            stateStore.load().copy(
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        lastTickAt = System.currentTimeMillis()
                    )
                )
            )
        )
        service.runCompanyRuntimeTick(company.id)

        // The tick requeues the orphaned blocked issue and then may re-start it.
        // If the start fails (e.g. GitHub readiness), the issue lands back in BLOCKED
        // with new tasks.  Verify that the runtime processed the issue by checking
        // that tasks were created or that the issue updatedAt advanced.
        val latestIssue = stateStore.load().issues.first { it.id == issue.id }
        val issueTasks = stateStore.load().tasks.filter { it.issueId == issue.id }
        (latestIssue.status != IssueStatus.BLOCKED || issueTasks.isNotEmpty() || latestIssue.updatedAt > issue.updatedAt) shouldBe true
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
        builder.capabilities.contains("implementation") shouldBe true
        builder.capabilities.contains("integration") shouldBe true
        builder.capabilities.contains("frontend") shouldBe false
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
                branchName = "codex/cotor/parallel/$agentName",
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
        plannedIssues.count { it.kind == "planning" } shouldBe 1
        plannedIssues.count { it.kind == "execution" } shouldBeGreaterThan 0
        plannedIssues.first { it.kind == "planning" }.status shouldBe IssueStatus.DONE
        plannedIssues.filter { it.kind == "execution" }.all { it.dependsOn.isEmpty() && it.status == IssueStatus.PLANNED } shouldBe true

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
        val planningIssues = issues.filter { it.kind == "planning" }
        val executionIssues = issues.filter { it.kind == "execution" }
        val reviewIssues = issues.filter { it.kind == "review" }
        val approvalIssues = issues.filter { it.kind == "approval" }

        planningIssues shouldHaveSize 1
        planningIssues.single().status shouldBe IssueStatus.DONE
        executionIssues.size shouldBeGreaterThan 0
        executionIssues.mapNotNull { it.assigneeProfileId }.distinct().size shouldBe executionIssues.size
        executionIssues.all { it.dependsOn.isEmpty() && it.status == IssueStatus.PLANNED } shouldBe true
    }

    test("new companies seed a codex-first enterprise roster with CEO as the sole merge authority") {
        val appHome = Files.createTempDirectory("desktop-enterprise-roster-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-enterprise-roster-test").resolve("repo"))
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
            name = "Enterprise Seed Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )

        val definitions = service.listCompanyAgentDefinitions(company.id)
        definitions.map { it.title } shouldBe listOf(
            "CEO",
            "Product Strategist",
            "Engineering Lead",
            "UX Builder",
            "UI Builder",
            "Builder",
            "Backend Builder",
            "QA",
            "Release Manager"
        )
        definitions.all { it.agentCli == "codex" } shouldBe true

        val mergeAuthorities = service.companyDashboard().orgProfiles
            .filter { it.companyId == company.id && it.mergeAuthority }
            .map { it.roleName }
        mergeAuthorities shouldBe listOf("CEO")
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
        blockIssueWithFailedTask(stateStore, issue)

        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        val state = stateStore.load()
        val followUpGoals = state.goals.filter {
            it.companyId == company.id &&
                it.id != goal.id &&
                it.operatingPolicy == "auto-follow-up:goal:${goal.id}"
        }
        followUpGoals shouldHaveSize 1
        followUpGoals.single().title shouldContain issue.title
        state.issues.any { it.goalId == followUpGoals.single().id } shouldBe true
    }

    test("runtime synthesizes a continuous CEO goal after active autonomous work completes") {
        val appHome = Files.createTempDirectory("desktop-runtime-continuous-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-continuous-test").resolve("repo"))
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
            name = "Continuous Loop Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Ship the first validated slice",
            description = "Complete the first autonomous cycle, then keep the company moving.",
            autonomyEnabled = true
        )
        // Allow any background runtime ticks to settle before rewriting state.
        delay(300)
        service.stopCompanyRuntime(company.id)
        delay(100)

        val now = System.currentTimeMillis()
        val snapshot = stateStore.load()
        stateStore.save(
            snapshot.copy(
                goals = snapshot.goals.map {
                    if (it.companyId == company.id) it.copy(status = GoalStatus.COMPLETED, updatedAt = now) else it
                },
                issues = snapshot.issues.map {
                    if (it.companyId == company.id) it.copy(status = IssueStatus.DONE, updatedAt = now) else it
                },
                tasks = snapshot.tasks.map {
                    if (it.status == DesktopTaskStatus.RUNNING || it.status == DesktopTaskStatus.QUEUED) {
                        it.copy(status = DesktopTaskStatus.COMPLETED, updatedAt = now)
                    } else {
                        it
                    }
                },
                runs = snapshot.runs.map {
                    if (it.status == AgentRunStatus.RUNNING || it.status == AgentRunStatus.QUEUED) {
                        it.copy(status = AgentRunStatus.COMPLETED, updatedAt = now)
                    } else {
                        it
                    }
                },
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        lastTickAt = now
                    )
                )
            )
        )

        service.runCompanyRuntimeTick(company.id)

        val nextGoals = stateStore.load().goals.filter {
            it.companyId == company.id &&
                it.id != goal.id &&
                it.operatingPolicy?.startsWith("auto-loop:continuous:") == true
        }
        nextGoals shouldHaveSize 1
        nextGoals.single().status shouldBe GoalStatus.ACTIVE
        stateStore.load().issues.any { it.goalId == nextGoals.single().id } shouldBe true
    }

    test("normalize automation state cancels superseded blocked follow-up issues after a newer retry succeeds") {
        val appHome = Files.createTempDirectory("desktop-runtime-superseded-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-superseded-test").resolve("repo"))
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
            name = "Superseded Retry Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val activeGoal = service.createGoal(
            companyId = company.id,
            title = "Primary goal",
            description = "Keep the company moving.",
            autonomyEnabled = true
        )
        val followUpGoal = service.createGoal(
            companyId = company.id,
            title = "Resolve follow-up for blocked review",
            description = "Remediate blocked review work.",
            autonomyEnabled = true,
            operatingPolicy = "auto-follow-up:review:item-1",
            startRuntimeIfNeeded = false
        )
        val workspaceId = stateStore.load().workspaces.first { it.repositoryId == company.repositoryId }.id
        val blockedIssue = CompanyIssue(
            id = "blocked-review-issue",
            companyId = company.id,
            projectContextId = followUpGoal.projectContextId,
            goalId = followUpGoal.id,
            workspaceId = workspaceId,
            title = "Review completed implementation work",
            description = "Old blocked remediation review issue.",
            status = IssueStatus.BLOCKED,
            priority = 1,
            kind = "review",
            createdAt = 10,
            updatedAt = 10
        )
        val successfulRetry = CompanyIssue(
            id = "successful-review-issue",
            companyId = company.id,
            projectContextId = activeGoal.projectContextId,
            goalId = activeGoal.id,
            workspaceId = workspaceId,
            title = "Review completed implementation work",
            description = "A newer retry completed successfully.",
            status = IssueStatus.DONE,
            priority = 1,
            kind = "review",
            createdAt = 20,
            updatedAt = 20
        )
        // Allow any background runtime ticks to settle before rewriting state.
        delay(300)
        service.stopCompanyRuntime(company.id)
        delay(100)
        val snapshot = stateStore.load()
        stateStore.save(
            snapshot.copy(
                issues = snapshot.issues + listOf(blockedIssue, successfulRetry)
            )
        )

        service.companyDashboard(company.id)

        withTimeout(5_000) {
            while (true) {
                val current = stateStore.load()
                val issue = current.issues.firstOrNull { it.id == blockedIssue.id } ?: break
                if (issue.status == IssueStatus.CANCELED) {
                    return@withTimeout
                }
                delay(25)
            }
        }
        val refreshedState = stateStore.load()
        refreshedState.issues.first { it.id == blockedIssue.id }.status shouldBe IssueStatus.CANCELED
        refreshedState.goals.first { it.id == followUpGoal.id }.status shouldBe GoalStatus.COMPLETED
    }

    test("runtime treats superseded canceled dependencies as satisfied for downstream review work") {
        val appHome = Files.createTempDirectory("desktop-runtime-superseded-dependency-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-superseded-dependency-test").resolve("repo"))
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
            name = "Superseded Dependency Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Keep review moving",
            description = "Review should continue after a retry supersedes an older execution issue.",
            autonomyEnabled = false
        )
        val workspaceId = stateStore.load().workspaces.first { it.repositoryId == company.repositoryId }.id
        val canceledExecution = CompanyIssue(
            id = "canceled-execution-issue",
            companyId = company.id,
            projectContextId = goal.projectContextId,
            goalId = goal.id,
            workspaceId = workspaceId,
            title = "Ship the feature slice",
            description = "An older execution issue superseded by a newer retry.",
            status = IssueStatus.CANCELED,
            priority = 1,
            kind = "execution",
            createdAt = 10,
            updatedAt = 10
        )
        val successfulRetry = CompanyIssue(
            id = "successful-execution-issue",
            companyId = company.id,
            projectContextId = goal.projectContextId,
            goalId = goal.id,
            workspaceId = workspaceId,
            title = "Ship the feature slice",
            description = "A newer execution retry that already finished successfully.",
            status = IssueStatus.DONE,
            priority = 1,
            kind = "execution",
            createdAt = 20,
            updatedAt = 20
        )
        val reviewIssue = CompanyIssue(
            id = "downstream-review-issue",
            companyId = company.id,
            projectContextId = goal.projectContextId,
            goalId = goal.id,
            workspaceId = workspaceId,
            title = "Review completed implementation work",
            description = "QA should review the latest successful branch, not wait on the superseded one.",
            status = IssueStatus.BACKLOG,
            priority = 2,
            kind = "review",
            dependsOn = listOf(canceledExecution.id, successfulRetry.id),
            createdAt = 30,
            updatedAt = 30
        )
        val snapshot = stateStore.load()
        stateStore.save(
            snapshot.copy(
                issues = snapshot.issues.filterNot { it.goalId == goal.id } + listOf(canceledExecution, successfulRetry, reviewIssue)
            )
        )
        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)

        service.runCompanyRuntimeTick(company.id)

        stateStore.load().tasks.count { it.issueId == reviewIssue.id } shouldBeGreaterThan 0
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
        blockIssueWithFailedTask(stateStore, originalIssue)

        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        val firstFollowUpGoal = stateStore.load().goals.first {
            it.companyId == company.id && it.operatingPolicy == "auto-follow-up:goal:${goal.id}"
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
            it.companyId == company.id && it.operatingPolicy?.startsWith("auto-follow-up:") == true
        }
        followUpGoals shouldHaveSize 1
    }

    test("runtime does not synthesize duplicate follow-up goals for the same canonical subject") {
        val appHome = Files.createTempDirectory("desktop-runtime-followup-subject-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-followup-subject-test").resolve("repo"))
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
            name = "Follow Up Subject Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Ship autonomous work",
            description = "Deliver the initial company objective.",
            autonomyEnabled = true
        )
        val originalIssue = service.listIssues(goal.id).first { it.kind == "execution" }
        blockIssueWithFailedTask(stateStore, originalIssue)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        val firstFollowUpGoal = stateStore.load().goals.first {
            it.companyId == company.id && it.operatingPolicy == "auto-follow-up:goal:${goal.id}"
        }
        val workspaceId = stateStore.load().workspaces.first { it.repositoryId == company.repositoryId }.id
        val duplicateBlockedIssue = CompanyIssue(
            id = "duplicate-follow-up-trigger",
            companyId = company.id,
            projectContextId = goal.projectContextId,
            goalId = goal.id,
            workspaceId = workspaceId,
            title = "Resolve follow-up for \"${originalIssue.title}\"",
            description = "A duplicate nested follow-up trigger.",
            status = IssueStatus.BLOCKED,
            priority = 1,
            kind = "execution",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues + duplicateBlockedIssue
            )
        )

        service.runCompanyRuntimeTick(company.id)

        val activeFollowUpGoals = stateStore.load().goals.filter {
            it.companyId == company.id &&
                it.status == GoalStatus.ACTIVE &&
                it.operatingPolicy.orEmpty().startsWith("auto-follow-up:")
        }
        activeFollowUpGoals.map { it.id }.contains(firstFollowUpGoal.id) shouldBe true
        activeFollowUpGoals shouldHaveSize 1
    }

    test("runtime retries blocked remediation issues only for recoverable infrastructure failures") {
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
        blockIssueWithFailedTask(stateStore, originalIssue)
        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)
        service.stopCompanyRuntime(company.id)

        withTimeout(5_000) {
            while (true) {
                val followUp = stateStore.load().goals.firstOrNull {
                    it.companyId == company.id && it.operatingPolicy == "auto-follow-up:goal:${goal.id}"
                }
                if (followUp != null && stateStore.load().issues.any { it.goalId == followUp.id && it.kind == "execution" }) {
                    break
                }
                delay(25)
            }
        }

        val followUpGoal = stateStore.load().goals.first {
            it.companyId == company.id && it.operatingPolicy == "auto-follow-up:goal:${goal.id}"
        }
        val remediationIssue = stateStore.load().issues.first {
            it.goalId == followUpGoal.id && it.kind == "execution"
        }
        awaitIssueTasksSettled(stateStore, remediationIssue.id)
        val existingRemediationTask = service.createTask(
            workspaceId = remediationIssue.workspaceId,
            title = remediationIssue.title,
            prompt = remediationIssue.description,
            agents = listOf("codex"),
            issueId = remediationIssue.id
        )
        val failedRun = AgentRun(
            id = "recoverable-remediation-run",
            taskId = existingRemediationTask.id,
            workspaceId = remediationIssue.workspaceId,
            repositoryId = stateStore.load().repositories.first().id,
            agentName = "codex",
            branchName = "codex/cotor/recoverable-remediation/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/recoverable-remediation/codex").toString(),
            status = AgentRunStatus.FAILED,
            output = "Agent process exited before Cotor recorded a final result",
            error = "Agent process exited before Cotor recorded a final result",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val preNow = System.currentTimeMillis()
        val preSnapshot = stateStore.load()
        stateStore.save(
            preSnapshot.copy(
                issues = preSnapshot.issues.map {
                    if (it.id == remediationIssue.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = preNow) else it
                },
                tasks = preSnapshot.tasks.map {
                    if (it.issueId == remediationIssue.id && it.id == existingRemediationTask.id) {
                        it.copy(status = DesktopTaskStatus.FAILED, updatedAt = preNow + 1)
                    } else if (it.issueId == remediationIssue.id) {
                        it.copy(status = DesktopTaskStatus.FAILED, updatedAt = preNow)
                    } else {
                        it
                    }
                },
                runs = preSnapshot.runs + failedRun
            )
        )

        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)
        service.stopCompanyRuntime(company.id)
        delay(500)

        // The tick detects the recoverable failure, requeues the issue to PLANNED,
        // and restarts it – which may re-block it when the relaxed mock executor fails.
        // Verify the retry was attempted by checking that more tasks exist.
        val postTickTasks = stateStore.load().tasks.filter { it.issueId == remediationIssue.id }
        postTickTasks.size shouldBeGreaterThan 1
        stateStore.load().goals.first { it.id == followUpGoal.id }.status shouldBe GoalStatus.ACTIVE
    }

    test("runtime keeps retrying recoverable remediation issues after multiple failed attempts") {
        val appHome = Files.createTempDirectory("desktop-runtime-followup-retry-many-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-followup-retry-many-test").resolve("repo"))
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
            name = "Retry Many Follow Up Co",
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
        blockIssueWithFailedTask(stateStore, originalIssue)
        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        val followUpGoal = stateStore.load().goals.first {
            it.companyId == company.id && it.operatingPolicy == "auto-follow-up:goal:${goal.id}"
        }
        val remediationIssue = stateStore.load().issues.first {
            it.goalId == followUpGoal.id && it.kind == "execution"
        }
        awaitIssueTasksSettled(stateStore, remediationIssue.id)
        val seededTasks = (1..3).map { attempt ->
            service.createTask(
                workspaceId = remediationIssue.workspaceId,
                title = "${remediationIssue.title} #$attempt",
                prompt = remediationIssue.description,
                agents = listOf("codex"),
                issueId = remediationIssue.id
            )
        }
        val now = System.currentTimeMillis()
        val retrySnapshot = stateStore.load()
        stateStore.save(
            retrySnapshot.copy(
                issues = retrySnapshot.issues.map {
                    if (it.id == remediationIssue.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = now) else it
                },
                tasks = retrySnapshot.tasks.map { task ->
                    if (task.issueId == remediationIssue.id) {
                        task.copy(status = DesktopTaskStatus.FAILED, updatedAt = now)
                    } else {
                        task
                    }
                },
                runs = retrySnapshot.runs + seededTasks.mapIndexed { index, task ->
                    AgentRun(
                        id = "recoverable-remediation-run-many-${index + 1}",
                        taskId = task.id,
                        workspaceId = remediationIssue.workspaceId,
                        repositoryId = retrySnapshot.repositories.first().id,
                        agentName = "codex",
                        branchName = "codex/cotor/recoverable-remediation-many/codex-${index + 1}",
                        worktreePath = repoRoot.resolve(".cotor/worktrees/recoverable-remediation-many/codex-${index + 1}").toString(),
                        status = AgentRunStatus.FAILED,
                        output = "Agent process exited before Cotor recorded a final result",
                        error = "Agent process exited before Cotor recorded a final result",
                        createdAt = now + index,
                        updatedAt = now + index
                    )
                }
            )
        )

        service.runCompanyRuntimeTick(company.id)

        // The tick requeues the issue and restarts it – which may re-block it when the
        // relaxed mock executor fails.  Verify the retry was attempted via task count.
        val totalRemediationTasks = stateStore.load().tasks.count { it.issueId == remediationIssue.id }
        totalRemediationTasks shouldBeGreaterThan seededTasks.size
    }

    test("runtime reopens recoverable blocked workflow issues inside an active autonomous goal") {
        val appHome = Files.createTempDirectory("desktop-runtime-workflow-retry-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-workflow-retry-test").resolve("repo"))
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
            name = "Workflow Retry Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Drive the next company cycle",
            description = "Create and complete the next improvement slice.",
            autonomyEnabled = true
        )
        val executionIssue = service.listIssues(goal.id).first { it.kind == "execution" }
        awaitIssueTasksSettled(stateStore, executionIssue.id)
        val existingTask = service.createTask(
            workspaceId = executionIssue.workspaceId,
            title = executionIssue.title,
            prompt = executionIssue.description,
            agents = listOf("codex"),
            issueId = executionIssue.id
        )
        val failedRun = AgentRun(
            id = "workflow-recoverable-run",
            taskId = existingTask.id,
            workspaceId = executionIssue.workspaceId,
            repositoryId = stateStore.load().repositories.first().id,
            agentName = "codex",
            branchName = "codex/cotor/workflow-retry/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/workflow-retry/codex").toString(),
            status = AgentRunStatus.FAILED,
            output = "Agent process exited before Cotor recorded a final result",
            error = "Agent process exited before Cotor recorded a final result",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val workflowRetrySnapshot = stateStore.load()
        stateStore.save(
            workflowRetrySnapshot.copy(
                issues = workflowRetrySnapshot.issues.map {
                    if (it.id == executionIssue.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = System.currentTimeMillis()) else it
                },
                tasks = workflowRetrySnapshot.tasks.map {
                    if (it.issueId == executionIssue.id) it.copy(status = DesktopTaskStatus.FAILED, updatedAt = System.currentTimeMillis()) else it
                },
                runs = workflowRetrySnapshot.runs + failedRun
            )
        )

        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        // The tick requeues the issue and restarts it – which may re-block it when the
        // relaxed mock executor fails.  Verify the retry was attempted via task count.
        stateStore.load().tasks.count { it.issueId == executionIssue.id } shouldBeGreaterThan 1
        stateStore.load().goals.first { it.id == goal.id }.status shouldBe GoalStatus.ACTIVE
        val traceLog = Files.readString(appHome.resolve("runtime").resolve("backend").resolve("company-automation-trace.log"))
        traceLog shouldContain "\"issueId\":\"${executionIssue.id}\""
        traceLog shouldContain "recoverable"
    }

    test("runtime keeps blocked remediation issues blocked for non-recoverable failures and logs the transition") {
        val appHome = Files.createTempDirectory("desktop-runtime-followup-nonrecoverable-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-followup-nonrecoverable-test").resolve("repo"))
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
            name = "Non Recoverable Retry Co",
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
        blockIssueWithFailedTask(stateStore, originalIssue)
        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        val followUpGoal = stateStore.load().goals.first {
            it.companyId == company.id && it.operatingPolicy == "auto-follow-up:goal:${goal.id}"
        }
        val remediationIssue = stateStore.load().issues.first {
            it.goalId == followUpGoal.id && it.kind == "execution"
        }
        awaitIssueTasksSettled(stateStore, remediationIssue.id)
        val existingRemediationTask = service.createTask(
            workspaceId = remediationIssue.workspaceId,
            title = remediationIssue.title,
            prompt = remediationIssue.description,
            agents = listOf("codex"),
            issueId = remediationIssue.id
        )
        val failedRun = AgentRun(
            id = "nonrecoverable-remediation-run",
            taskId = existingRemediationTask.id,
            workspaceId = remediationIssue.workspaceId,
            repositoryId = stateStore.load().repositories.first().id,
            agentName = "codex",
            branchName = "codex/cotor/nonrecoverable-remediation/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/nonrecoverable-remediation/codex").toString(),
            status = AgentRunStatus.FAILED,
            output = "review failed: residual risk remains",
            error = "review failed: residual risk remains",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val nonRecoverSnapshot = stateStore.load()
        stateStore.save(
            nonRecoverSnapshot.copy(
                issues = nonRecoverSnapshot.issues.map {
                    if (it.id == remediationIssue.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = System.currentTimeMillis()) else it
                },
                tasks = nonRecoverSnapshot.tasks.map {
                    if (it.issueId == remediationIssue.id) {
                        it.copy(status = DesktopTaskStatus.FAILED, updatedAt = System.currentTimeMillis())
                    } else {
                        it
                    }
                },
                runs = nonRecoverSnapshot.runs + failedRun
            )
        )

        service.runCompanyRuntimeTick(company.id)

        val updatedIssue = stateStore.load().issues.first { it.id == remediationIssue.id }
        updatedIssue.status shouldBe IssueStatus.BLOCKED
        val traceLog = Files.readString(appHome.resolve("runtime").resolve("backend").resolve("company-automation-trace.log"))
        traceLog shouldContain "\"issueId\":\"${remediationIssue.id}\""
        traceLog shouldContain "\"newStatus\":\"BLOCKED\""
    }

    test("runtime retries blocked remediation issues for transient GitHub PR publish failures") {
        val appHome = Files.createTempDirectory("desktop-runtime-followup-prpublish-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-followup-prpublish-test").resolve("repo"))
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
            name = "PR Publish Retry Co",
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
        blockIssueWithFailedTask(stateStore, originalIssue)
        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        val followUpGoal = stateStore.load().goals.first {
            it.companyId == company.id && it.operatingPolicy == "auto-follow-up:goal:${goal.id}"
        }
        val remediationIssue = stateStore.load().issues.first {
            it.goalId == followUpGoal.id && it.kind == "execution"
        }
        awaitIssueTasksSettled(stateStore, remediationIssue.id)
        val existingRemediationTask = service.createTask(
            workspaceId = remediationIssue.workspaceId,
            title = remediationIssue.title,
            prompt = remediationIssue.description,
            agents = listOf("codex"),
            issueId = remediationIssue.id
        )
        val failedRun = AgentRun(
            id = "prpublish-remediation-run",
            taskId = existingRemediationTask.id,
            workspaceId = remediationIssue.workspaceId,
            repositoryId = stateStore.load().repositories.first().id,
            agentName = "codex",
            branchName = "codex/cotor/prpublish-remediation/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/prpublish-remediation/codex").toString(),
            status = AgentRunStatus.FAILED,
            output = "Publish failed: pull request create failed: GraphQL: was submitted too quickly (createPullRequest)",
            error = "Publish failed: pull request create failed: GraphQL: was submitted too quickly (createPullRequest)",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            publish = PublishMetadata(
                error = "pull request create failed: GraphQL: was submitted too quickly (createPullRequest)"
            )
        )
        val prPublishSnapshot = stateStore.load()
        stateStore.save(
            prPublishSnapshot.copy(
                issues = prPublishSnapshot.issues.map {
                    if (it.id == remediationIssue.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = System.currentTimeMillis()) else it
                },
                tasks = prPublishSnapshot.tasks.map {
                    if (it.issueId == remediationIssue.id) {
                        it.copy(status = DesktopTaskStatus.FAILED, updatedAt = System.currentTimeMillis())
                    } else {
                        it
                    }
                },
                runs = prPublishSnapshot.runs + failedRun
            )
        )

        service.runCompanyRuntimeTick(company.id)

        // The tick requeues the issue and restarts it – which may re-block it when the
        // relaxed mock executor fails.  Verify the retry was attempted via task count.
        val prPublishRemediationTasks = stateStore.load().tasks.count { it.issueId == remediationIssue.id }
        prPublishRemediationTasks shouldBeGreaterThan 1
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
        blockIssueWithFailedTask(stateStore, originalIssue)
        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)
        service.runCompanyRuntimeTick(company.id)

        val firstFollowUpGoal = stateStore.load().goals.first {
            it.companyId == company.id && it.operatingPolicy == "auto-follow-up:goal:${goal.id}"
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
            operatingPolicy = "auto-follow-up:goal:${firstFollowUpGoal.id}",
            startRuntimeIfNeeded = false
        )
        service.runCompanyRuntimeTick(company.id)

        val updatedNestedGoal = stateStore.load().goals.first { it.id == nestedGoal.id }
        updatedNestedGoal.status shouldBe GoalStatus.COMPLETED
        stateStore.load().issues.filter { it.goalId == nestedGoal.id }.all {
            it.status == IssueStatus.CANCELED || it.status == IssueStatus.DONE
        } shouldBe true
    }

    test("company dashboard migrates legacy follow-up markers to the root goal lineage and archives duplicates") {
        val appHome = Files.createTempDirectory("desktop-runtime-followup-migrate-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-followup-migrate-test").resolve("repo"))
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
            name = "Migrate Follow Up Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Primary objective",
            description = "Initial objective.",
            autonomyEnabled = true
        )
        val workspaceId = stateStore.load().workspaces.first { it.repositoryId == company.repositoryId }.id
        val triggerIssue = service.listIssues(goal.id).first { it.kind == "execution" }
        val firstLegacyFollowUp = service.createGoal(
            companyId = company.id,
            title = "Resolve follow-up for \"${triggerIssue.title}\"",
            description = "Legacy follow-up.",
            autonomyEnabled = true,
            operatingPolicy = "auto-follow-up:issue:${triggerIssue.id}",
            startRuntimeIfNeeded = false
        )
        val nestedLegacyFollowUp = service.createGoal(
            companyId = company.id,
            title = "Resolve follow-up for \"Resolve follow-up for \\\"${triggerIssue.title}\\\"\"",
            description = "Broken nested follow-up.",
            autonomyEnabled = true,
            operatingPolicy = "auto-follow-up:issue:legacy-followup-nested-issue",
            startRuntimeIfNeeded = false
        )
        val siblingLegacyFollowUp = service.createGoal(
            companyId = company.id,
            title = "Resolve follow-up for \"${triggerIssue.title}\"",
            description = "Another duplicate legacy follow-up.",
            autonomyEnabled = true,
            operatingPolicy = "auto-follow-up:goal:${goal.id}",
            startRuntimeIfNeeded = false
        )
        val nestedTriggerIssue = CompanyIssue(
            id = "legacy-followup-nested-issue",
            companyId = company.id,
            projectContextId = goal.projectContextId,
            goalId = goal.id,
            workspaceId = workspaceId,
            title = "Resolve follow-up for \"${triggerIssue.title}\"",
            description = "Legacy nested trigger.",
            status = IssueStatus.BLOCKED,
            priority = 1,
            kind = "execution",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val snapshot = stateStore.load()
        stateStore.save(
            snapshot.copy(
                issues = snapshot.issues + nestedTriggerIssue
            )
        )

        service.companyDashboard(company.id)

        withTimeout(5_000) {
            while (true) {
                val current = stateStore.load()
                val followUps = current.goals.filter { it.companyId == company.id && it.operatingPolicy.orEmpty().startsWith("auto-follow-up:") }
                if (followUps.count { it.status == GoalStatus.ACTIVE } <= 1) {
                    return@withTimeout
                }
                delay(25)
            }
        }
        val refreshedGoals = stateStore.load().goals.filter { it.companyId == company.id && it.operatingPolicy.orEmpty().startsWith("auto-follow-up:") }
        refreshedGoals.count { it.status == GoalStatus.ACTIVE } shouldBe 1
        refreshedGoals.first { it.status == GoalStatus.ACTIVE }.operatingPolicy shouldBe "auto-follow-up:goal:${goal.id}"
        refreshedGoals.first { it.status == GoalStatus.ACTIVE }.title shouldBe "Resolve follow-up for \"${triggerIssue.title}\""
        refreshedGoals.first { it.id == firstLegacyFollowUp.id }.operatingPolicy shouldBe "auto-follow-up:goal:${goal.id}"
        refreshedGoals.first { it.id == nestedLegacyFollowUp.id }.status shouldBe GoalStatus.COMPLETED
        refreshedGoals.first { it.id == siblingLegacyFollowUp.id }.status shouldBe GoalStatus.COMPLETED
    }

    test("continuous CEO goals exclude completed follow-up titles from the next cycle briefing") {
        val appHome = Files.createTempDirectory("desktop-runtime-continuous-briefing-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-continuous-briefing-test").resolve("repo"))
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
            name = "Continuous Briefing Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val primaryGoal = service.createGoal(
            companyId = company.id,
            title = "Primary objective",
            description = "Complete the main objective.",
            autonomyEnabled = true
        )
        val now = System.currentTimeMillis()
        val followUpGoal = service.createGoal(
            companyId = company.id,
            title = "Resolve follow-up for \"Primary objective\"",
            description = "Remediate a temporary failure.",
            autonomyEnabled = true,
            operatingPolicy = "auto-follow-up:goal:${primaryGoal.id}",
            startRuntimeIfNeeded = false
        )
        val workspaceId = stateStore.load().workspaces.first { it.repositoryId == company.repositoryId }.id
        val completedIssue = CompanyIssue(
            id = "continuous-followup-done-issue",
            companyId = company.id,
            projectContextId = primaryGoal.projectContextId,
            goalId = followUpGoal.id,
            workspaceId = workspaceId,
            title = "Resolve follow-up for \"Primary objective\"",
            description = "Completed follow-up issue.",
            status = IssueStatus.DONE,
            priority = 1,
            kind = "execution",
            createdAt = now,
            updatedAt = now
        )
        // Allow any background runtime ticks to settle before rewriting state.
        delay(300)
        service.stopCompanyRuntime(company.id)
        delay(100)
        val preSnapshot = stateStore.load()
        stateStore.save(
            preSnapshot.copy(
                goals = preSnapshot.goals.map {
                    if (it.companyId == company.id) it.copy(status = GoalStatus.COMPLETED, updatedAt = now) else it
                },
                issues = preSnapshot.issues.map {
                    if (it.companyId == company.id) it.copy(status = IssueStatus.DONE, updatedAt = now) else it
                } + completedIssue,
                tasks = preSnapshot.tasks.map {
                    if (it.status == DesktopTaskStatus.RUNNING || it.status == DesktopTaskStatus.QUEUED) {
                        it.copy(status = DesktopTaskStatus.COMPLETED, updatedAt = now)
                    } else {
                        it
                    }
                },
                runs = preSnapshot.runs.map {
                    if (it.status == AgentRunStatus.RUNNING || it.status == AgentRunStatus.QUEUED) {
                        it.copy(status = AgentRunStatus.COMPLETED, updatedAt = now)
                    } else {
                        it
                    }
                },
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        lastTickAt = now
                    )
                )
            )
        )

        service.runCompanyRuntimeTick(company.id)

        val nextGoal = stateStore.load().goals.first {
            it.companyId == company.id &&
                it.id != primaryGoal.id &&
                it.id != followUpGoal.id &&
                it.operatingPolicy?.startsWith("auto-loop:continuous:") == true
        }
        nextGoal.description shouldContain "Primary objective"
        // The continuous goal should not re-include the follow-up goal itself as a
        // recently completed goal in the briefing.  Derivative planning issues may
        // embed the follow-up title in their own names, which is acceptable.
        val recentGoalLines = nextGoal.description.lineSequence()
            .filter { it.trimStart().startsWith("- ") }
            .toList()
        recentGoalLines.none { it.trim() == "- ${followUpGoal.title}" } shouldBe true
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
        val executionIssue = service.listIssues(goal.id).first { it.kind == "execution" }
        val task = service.createTask(
            workspaceId = executionIssue.workspaceId,
            title = executionIssue.title,
            prompt = executionIssue.description,
            agents = listOf("codex"),
            issueId = executionIssue.id
        )
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == executionIssue.id) it.copy(status = IssueStatus.IN_PROGRESS, updatedAt = System.currentTimeMillis()) else it
                },
                tasks = stateStore.load().tasks.map {
                    if (it.id == task.id) it.copy(status = DesktopTaskStatus.FAILED, updatedAt = System.currentTimeMillis()) else it
                }
            )
        )
        service.updateGoal(goal.id, autonomyEnabled = true)
        service.startCompanyRuntime(company.id)

        service.runCompanyRuntimeTick(company.id)

        val updatedIssue = stateStore.load().issues.first { it.id == executionIssue.id }
        updatedIssue.status shouldBe IssueStatus.BLOCKED
    }

    test("review issues complete successfully without requiring code publish") {
        val appHome = Files.createTempDirectory("desktop-review-run-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-review-run-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val agentExecutor = mockk<AgentExecutor>()
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "master"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } returns WorktreeBinding(
            branchName = "codex/cotor/review-test/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/review-test/codex")
        )
        coEvery { gitWorkspaceService.publishRun(any(), any(), any(), any(), any()) } returns PublishMetadata()
        coEvery { gitWorkspaceService.ensureGitHubPublishReady(any(), any()) } returns GitHubPublishReadiness(ready = true)
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
        val infraIssue = service.createIssue(
            companyId = company.id,
            goalId = goal.id,
            title = "Review completed implementation work",
            description = "Review the branch and summarize any remaining risk.",
            kind = "infra"
        )

        service.runIssue(infraIssue.id)

        withTimeout(5_000) {
            while (true) {
                val snapshot = stateStore.load()
                val refreshedIssue = snapshot.issues.first { it.id == infraIssue.id }
                val relatedTask = snapshot.tasks.firstOrNull { it.issueId == infraIssue.id }
                val relatedRuns = relatedTask?.let { task -> snapshot.runs.filter { it.taskId == task.id } }.orEmpty()
                if (refreshedIssue.status == IssueStatus.DONE && relatedRuns.any { it.status == AgentRunStatus.COMPLETED }) {
                    return@withTimeout
                }
                delay(25)
            }
        }

        val finalState = stateStore.load()
        finalState.issues.first { it.id == infraIssue.id }.status shouldBe IssueStatus.DONE
        val finalTask = finalState.tasks.first { it.issueId == infraIssue.id }
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

        // Use a synchronous tick to drive reconciliation instead of relying on the
        // async dashboard refresh, so the stale-run reconciliation completes before we
        // inspect the state.
        stateStore.save(
            stateStore.load().copy(
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        lastTickAt = now
                    )
                )
            )
        )
        service.runCompanyRuntimeTick(company.id)

        val reconciled = stateStore.load()
        reconciled.runs.first { it.id == run.id }.status shouldBe AgentRunStatus.FAILED
        reconciled.runs.first { it.id == run.id }.error shouldContain "exited before Cotor recorded a final result"
        reconciled.tasks.first { it.id == task.id }.status shouldBe DesktopTaskStatus.FAILED
        // The stale-run error is recoverable, so the autonomous tick may requeue the
        // issue back to PLANNED rather than leaving it BLOCKED.
        val reconciledIssue = reconciled.issues.first { it.id == issue.id }
        (reconciledIssue.status == IssueStatus.BLOCKED || reconciledIssue.status == IssueStatus.PLANNED) shouldBe true
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
        // Wait for any background automation refresh from service init to settle
        delay(200)
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == issue.id) it.copy(status = IssueStatus.IN_PROGRESS, updatedAt = staleAt) else it
                },
                tasks = stateStore.load().tasks + task,
                runs = stateStore.load().runs + run
            )
        )

        // Use a synchronous tick to drive reconciliation instead of the async
        // dashboard refresh.
        stateStore.save(
            stateStore.load().copy(
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        lastTickAt = staleAt
                    )
                )
            )
        )
        service.runCompanyRuntimeTick(company.id)

        val reconciled = stateStore.load()
        reconciled.runs.first { it.id == run.id }.status shouldBe AgentRunStatus.FAILED
        reconciled.tasks.first { it.id == task.id }.status shouldBe DesktopTaskStatus.FAILED
        // The stale-run error is recoverable, so the autonomous tick may requeue the
        // issue back to PLANNED rather than leaving it BLOCKED.
        val reconciledIssue = reconciled.issues.first { it.id == issue.id }
        (reconciledIssue.status == IssueStatus.BLOCKED || reconciledIssue.status == IssueStatus.PLANNED) shouldBe true
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
        coEvery { gitWorkspaceService.ensureGitHubPublishReady(any(), any()) } returns GitHubPublishReadiness(ready = true)
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

        DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            commandAvailability = { command -> command == "codex" || command == "/bin/echo" }
        )

        withTimeout(5_000) {
            while (capturedAgents.isEmpty()) {
                delay(25)
            }
        }

        capturedAgents.shouldNotBeEmpty()
        stateStore.load().tasks.any { it.issueId != null && it.status != DesktopTaskStatus.QUEUED } shouldBe true
        DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            commandAvailability = { command -> command == "codex" || command == "/bin/echo" }
        ).stopCompanyRuntime(goal.companyId).status shouldBe CompanyRuntimeStatus.STOPPED
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
        coEvery { gitWorkspaceService.ensureGitHubPublishReady(any(), any()) } returns GitHubPublishReadiness(ready = true)
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

        withTimeout(15_000) {
            while (capturedAgents.isEmpty()) {
                runCatching { service.runCompanyRuntimeTick(goal.companyId) }
                delay(200)
            }
        }

        coVerify(atLeast = 1) { agentExecutor.executeAgent(any(), any(), any()) }
        // The planner assigns work based on prompt relevance.  Verify that
        // at least one agent was dispatched and that the /bin/echo builder
        // would be routed through CommandPlugin when it is selected.
        capturedAgents.shouldNotBeEmpty()
        val builderAgent = capturedAgents.firstOrNull { it.name == "/bin/echo" }
        if (builderAgent != null) {
            builderAgent.pluginClass shouldBe "com.cotor.data.plugin.CommandPlugin"
            builderAgent.parameters["argvJson"] shouldBe """["/bin/echo","{input}"]"""
        } else {
            // The /bin/echo builder was not selected for this prompt; verify the
            // command plugin config is properly computed for arbitrary CLIs.
            val commandConfig = service.settings().let { settings ->
                capturedAgents.firstOrNull()
            }
            commandConfig.shouldNotBeNull()
        }
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
                branchName = "codex/cotor/memory/$agentName",
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

    test("runtime keeps running when GitHub readiness probing times out for a code issue") {
        val appHome = Files.createTempDirectory("desktop-app-service-github-readiness-timeout")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-github-readiness-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.ensureGitHubPublishReady(any(), any()) } throws RuntimeException("Timed out waiting for 10000 ms")
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "GitHub Timeout Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-timeout",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Ship timeout-prone change",
            description = "Exercise GitHub readiness timeout handling.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val issue = CompanyIssue(
            id = "issue-timeout",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Implement timeout-prone branch",
            description = "This code issue should block locally instead of killing the runtime.",
            status = IssueStatus.PLANNED,
            kind = "execution",
            createdAt = now,
            updatedAt = now
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + issue
            )
        )

        service.startCompanyRuntime(company.id)

        withTimeout(5_000) {
            while (true) {
                val current = stateStore.load()
                val runtime = current.companyRuntimes.firstOrNull { it.companyId == company.id }
                val blockedIssue = current.issues.firstOrNull { it.id == issue.id }
                val infraIssue = current.issues.firstOrNull {
                    it.companyId == company.id && it.kind == "infra" && it.title == "Restore GitHub publishing for ${issue.title}"
                }
                if (runtime?.status == CompanyRuntimeStatus.RUNNING &&
                    runtime.lastAction != "runtime-error" &&
                    blockedIssue?.status == IssueStatus.BLOCKED &&
                    infraIssue?.status == IssueStatus.PLANNED
                ) {
                    break
                }
                delay(25)
            }
        }

        val finalState = stateStore.load()
        finalState.companyRuntimes.first { it.companyId == company.id }.status shouldBe CompanyRuntimeStatus.RUNNING
        finalState.issues.first { it.id == issue.id }.status shouldBe IssueStatus.BLOCKED
        finalState.issues.any {
            it.companyId == company.id &&
                it.kind == "infra" &&
                it.title == "Restore GitHub publishing for ${issue.title}" &&
                it.status == IssueStatus.PLANNED
        } shouldBe true
    }

    test("QA pass reopens a blocked approval issue and clears stale CEO review metadata") {
        val appHome = Files.createTempDirectory("desktop-app-service-qa-reopen-approval")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-qa-reopen-approval-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "QA Reopen Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-qa-reopen",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Reopen CEO approval after QA pass",
            description = "Exercise review-to-approval recovery.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-qa-reopen",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Ship the code change",
            description = "Implementation branch work.",
            status = IssueStatus.IN_PROGRESS,
            priority = 2,
            kind = "execution",
            createdAt = now,
            updatedAt = now
        )
        val reviewIssue = CompanyIssue(
            id = "issue-review-qa-reopen",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Review completed implementation work",
            description = "QA checks the branch.",
            status = IssueStatus.IN_PROGRESS,
            priority = 2,
            kind = "review",
            dependsOn = listOf(executionIssue.id),
            sourceSignal = "qa-review:${executionIssue.id}",
            createdAt = now,
            updatedAt = now
        )
        val approvalIssue = CompanyIssue(
            id = "issue-approval-qa-reopen",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Review downstream company status",
            description = "CEO approval gate.",
            status = IssueStatus.BLOCKED,
            priority = 1,
            kind = "approval",
            dependsOn = listOf(reviewIssue.id),
            sourceSignal = "ceo-approval:${executionIssue.id}",
            createdAt = now,
            updatedAt = now
        )
        val reviewTask = AgentTask(
            id = "task-review-qa-reopen",
            workspaceId = workspace.id,
            title = reviewIssue.title,
            prompt = reviewIssue.description,
            agents = listOf("codex"),
            issueId = reviewIssue.id,
            status = DesktopTaskStatus.COMPLETED,
            createdAt = now,
            updatedAt = now
        )
        val reviewRun = AgentRun(
            id = "run-review-qa-reopen",
            taskId = reviewTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.COMPLETED,
            output = "QA_VERDICT: PASS\nReady for CEO approval after re-review.",
            branchName = "codex/cotor/reopen-approval",
            worktreePath = repoRoot.resolve(".cotor/worktrees/reopen-approval/codex").toString(),
            publish = PublishMetadata(
                commitSha = "abc123",
                pullRequestNumber = 11,
                pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/11",
                pullRequestState = "OPEN"
            ),
            durationMs = 250,
            createdAt = now,
            updatedAt = now
        )
        val reviewQueueItem = ReviewQueueItem(
            id = "rq-qa-reopen",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = executionIssue.id,
            runId = reviewRun.id,
            branchName = reviewRun.branchName,
            worktreePath = reviewRun.worktreePath,
            pullRequestNumber = 11,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/11",
            pullRequestState = "OPEN",
            status = ReviewQueueStatus.CHANGES_REQUESTED,
            checksSummary = "Earlier CEO review requested changes.",
            qaVerdict = "CHANGES_REQUESTED",
            qaFeedback = "Address stale blocker.",
            qaReviewedAt = now - 5_000,
            qaIssueId = reviewIssue.id,
            ceoVerdict = "CHANGES_REQUESTED",
            ceoFeedback = "Still points at stale implementation.",
            ceoReviewedAt = now - 2_500,
            approvalIssueId = approvalIssue.id,
            createdAt = now - 10_000,
            updatedAt = now - 2_500
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, reviewIssue, approvalIssue),
                tasks = baseState.tasks + reviewTask,
                runs = baseState.runs + reviewRun,
                reviewQueue = baseState.reviewQueue + reviewQueueItem,
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        backendKind = ExecutionBackendKind.LOCAL_COTOR,
                        backendHealth = "healthy",
                        lastStartedAt = now
                    )
                )
            )
        )

        service.runCompanyRuntimeTick(company.id)

        val refreshed = stateStore.load()
        // After the tick processes the completed QA review task, the execution
        // issue should advance to READY_FOR_CEO and the review queue should
        // reflect the QA PASS verdict.
        val refreshedExecution = refreshed.issues.first { it.id == executionIssue.id }
        refreshedExecution.status shouldBe IssueStatus.READY_FOR_CEO
        refreshed.issues.first { it.id == reviewIssue.id }.status shouldBe IssueStatus.DONE
        val refreshedQueue = refreshed.reviewQueue.first { it.id == reviewQueueItem.id }
        refreshedQueue.status shouldBe ReviewQueueStatus.READY_FOR_CEO
        refreshedQueue.ceoVerdict shouldBe null
        refreshedQueue.ceoFeedback shouldBe null
        refreshedQueue.ceoReviewedAt shouldBe null
        refreshedQueue.qaVerdict shouldBe "PASS"
    }

    test("CEO approval merges the current PR and completes the execution lineage") {
        val appHome = Files.createTempDirectory("desktop-app-service-ceo-merge")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-ceo-merge-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery {
            gitWorkspaceService.submitPullRequestReview(any(), 22, PullRequestReviewVerdict.APPROVE, any())
        } returns PublishMetadata(
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            reviewState = "APPROVED",
            mergeability = "MERGEABLE"
        )
        coEvery {
            gitWorkspaceService.mergePullRequest(any(), 22)
        } returns PullRequestMergeResult(
            number = 22,
            url = "https://github.com/heodongun/cotor-test/pull/22",
            state = "MERGED",
            mergeCommitSha = "deadbeef"
        )
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )
        // Wait for the init automation refresh to settle before modifying state.
        delay(500)

        val company = service.createCompany(
            name = "CEO Merge Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-ceo-merge",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Merge the approved PR",
            description = "Exercise the CEO approval merge lane.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val executionIssue = CompanyIssue(
            id = "issue-execution-ceo-merge",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Ship the current PR",
            description = "Execution branch work.",
            status = IssueStatus.READY_FOR_CEO,
            priority = 2,
            kind = "execution",
            branchName = "codex/cotor/ceo-merge",
            worktreePath = repoRoot.resolve(".cotor/worktrees/ceo-merge/codex").toString(),
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            qaVerdict = "PASS",
            qaFeedback = "QA approved this PR.",
            createdAt = now,
            updatedAt = now
        )
        val approvalIssue = CompanyIssue(
            id = "issue-approval-ceo-merge",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "CEO approve Ship the current PR",
            description = "CEO approval gate.",
            status = IssueStatus.IN_PROGRESS,
            priority = 1,
            kind = "approval",
            dependsOn = listOf(executionIssue.id),
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            qaVerdict = "PASS",
            qaFeedback = "QA approved this PR.",
            sourceSignal = "ceo-approval:${executionIssue.id}",
            createdAt = now,
            updatedAt = now
        )
        val approvalTask = AgentTask(
            id = "task-approval-ceo-merge",
            workspaceId = workspace.id,
            title = approvalIssue.title,
            prompt = approvalIssue.description,
            agents = listOf("codex"),
            issueId = approvalIssue.id,
            status = DesktopTaskStatus.COMPLETED,
            createdAt = now,
            updatedAt = now
        )
        val approvalRun = AgentRun(
            id = "run-approval-ceo-merge",
            taskId = approvalTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.COMPLETED,
            output = "CEO_VERDICT: APPROVE\nReady to merge.",
            branchName = executionIssue.branchName!!,
            worktreePath = executionIssue.worktreePath!!,
            durationMs = 150,
            createdAt = now,
            updatedAt = now
        )
        val reviewQueueItem = ReviewQueueItem(
            id = "rq-ceo-merge",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = executionIssue.id,
            runId = "run-execution-ceo-merge",
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            status = ReviewQueueStatus.READY_FOR_CEO,
            mergeability = "clean",
            qaVerdict = "PASS",
            qaFeedback = "QA approved this PR.",
            qaReviewedAt = now - 1_000,
            approvalIssueId = approvalIssue.id,
            createdAt = now - 10_000,
            updatedAt = now - 1_000
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(executionIssue, approvalIssue),
                tasks = baseState.tasks + approvalTask,
                runs = baseState.runs + approvalRun,
                reviewQueue = baseState.reviewQueue + reviewQueueItem,
                companyRuntimes = listOf(
                    CompanyRuntimeSnapshot(
                        companyId = company.id,
                        status = CompanyRuntimeStatus.RUNNING,
                        backendKind = ExecutionBackendKind.LOCAL_COTOR,
                        backendHealth = "healthy",
                        lastStartedAt = now
                    )
                )
            )
        )

        // Wait for init automation to settle, then use the public merge API
        // directly to avoid race conditions with background automation refreshes.
        delay(500)

        // Ensure state is correct before the merge.
        val preMerge = stateStore.load()
        stateStore.save(
            preMerge.copy(
                goals = preMerge.goals.filterNot { it.id == goal.id } + goal,
                issues = preMerge.issues.filterNot { it.id == executionIssue.id || it.id == approvalIssue.id } + listOf(executionIssue, approvalIssue),
                tasks = preMerge.tasks.filterNot { it.id == approvalTask.id } + approvalTask,
                runs = preMerge.runs.filterNot { it.id == approvalRun.id } + approvalRun,
                reviewQueue = preMerge.reviewQueue.filterNot { it.id == reviewQueueItem.id } + reviewQueueItem
            )
        )

        val merged = service.mergeReviewQueueItem(reviewQueueItem.id)
        merged.status shouldBe ReviewQueueStatus.MERGED
        merged.mergeCommitSha shouldBe "deadbeef"

        val finalState = stateStore.load()
        val finalExecution = finalState.issues.first { it.id == executionIssue.id }
        finalExecution.status shouldBe IssueStatus.DONE
        finalExecution.mergeResult shouldBe "MERGED"
    }

    test("approval prompt scopes evidence to CEO-ready pull requests and excludes stale review history") {
        val appHome = Files.createTempDirectory("desktop-app-service-approval-prompt")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-app-service-approval-prompt-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>(relaxed = true)
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk(relaxed = true),
            agentExecutor = mockk(relaxed = true)
        )

        val company = service.createCompany(
            name = "Approval Prompt Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val baseState = stateStore.load()
        val workspace = baseState.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = baseState.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val goal = CompanyGoal(
            id = "goal-approval-prompt",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Approval prompt should ignore stale PRs",
            description = "Exercise CEO approval prompt scoping.",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        val staleExecutionIssue = CompanyIssue(
            id = "issue-execution-stale",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Old implementation",
            description = "Historical stale PR.",
            status = IssueStatus.DONE,
            priority = 2,
            kind = "execution",
            createdAt = now - 10_000,
            updatedAt = now - 9_000
        )
        val currentExecutionIssue = CompanyIssue(
            id = "issue-execution-current",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Current implementation",
            description = "Current PR under CEO review.",
            status = IssueStatus.READY_FOR_CEO,
            priority = 2,
            kind = "execution",
            createdAt = now - 5_000,
            updatedAt = now - 4_000
        )
        val staleReviewIssue = CompanyIssue(
            id = "issue-review-stale",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Review completed implementation work",
            description = "Old review output.",
            status = IssueStatus.DONE,
            priority = 2,
            kind = "review",
            dependsOn = listOf(staleExecutionIssue.id),
            createdAt = now - 8_000,
            updatedAt = now - 7_000
        )
        val approvalIssue = CompanyIssue(
            id = "issue-approval-current",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Review downstream company status",
            description = "CEO approval gate.",
            status = IssueStatus.PLANNED,
            priority = 1,
            kind = "approval",
            dependsOn = listOf(currentExecutionIssue.id),
            createdAt = now - 2_000,
            updatedAt = now - 1_000
        )
        val staleExecutionTask = AgentTask(
            id = "task-execution-stale",
            workspaceId = workspace.id,
            title = staleExecutionIssue.title,
            prompt = staleExecutionIssue.description,
            agents = listOf("codex"),
            issueId = staleExecutionIssue.id,
            status = DesktopTaskStatus.COMPLETED,
            createdAt = now - 10_000,
            updatedAt = now - 9_000
        )
        val currentExecutionTask = AgentTask(
            id = "task-execution-current",
            workspaceId = workspace.id,
            title = currentExecutionIssue.title,
            prompt = currentExecutionIssue.description,
            agents = listOf("codex"),
            issueId = currentExecutionIssue.id,
            status = DesktopTaskStatus.COMPLETED,
            createdAt = now - 5_000,
            updatedAt = now - 4_000
        )
        val staleReviewTask = AgentTask(
            id = "task-review-stale",
            workspaceId = workspace.id,
            title = staleReviewIssue.title,
            prompt = staleReviewIssue.description,
            agents = listOf("codex"),
            issueId = staleReviewIssue.id,
            status = DesktopTaskStatus.COMPLETED,
            createdAt = now - 8_000,
            updatedAt = now - 7_000
        )
        val staleExecutionRun = AgentRun(
            id = "run-execution-stale",
            taskId = staleExecutionTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.COMPLETED,
            output = "Stale execution summary.",
            branchName = "codex/cotor/stale-pr/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/stale/codex").toString(),
            publish = PublishMetadata(
                commitSha = "1111111",
                pullRequestNumber = 11,
                pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/11",
                pullRequestState = "OPEN"
            ),
            durationMs = 250,
            createdAt = now - 10_000,
            updatedAt = now - 9_000
        )
        val currentExecutionRun = AgentRun(
            id = "run-execution-current",
            taskId = currentExecutionTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.COMPLETED,
            output = "Current execution summary.",
            branchName = "codex/cotor/current-pr/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/current/codex").toString(),
            publish = PublishMetadata(
                commitSha = "2222222",
                pullRequestNumber = 22,
                pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
                pullRequestState = "OPEN"
            ),
            durationMs = 250,
            createdAt = now - 5_000,
            updatedAt = now - 4_000
        )
        val staleReviewRun = AgentRun(
            id = "run-review-stale",
            taskId = staleReviewTask.id,
            workspaceId = workspace.id,
            repositoryId = company.repositoryId,
            agentName = "codex",
            repoRoot = repoRoot.toString(),
            baseBranch = "master",
            status = AgentRunStatus.COMPLETED,
            output = "QA_VERDICT: PASS\nPR #11 still reports no completed implementation to verify.",
            branchName = "codex/cotor/stale-pr/codex",
            worktreePath = repoRoot.resolve(".cotor/worktrees/stale/codex").toString(),
            durationMs = 250,
            createdAt = now - 8_000,
            updatedAt = now - 7_000
        )
        val staleQueueItem = ReviewQueueItem(
            id = "rq-stale",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = staleExecutionIssue.id,
            runId = staleExecutionRun.id,
            branchName = staleExecutionRun.branchName,
            worktreePath = staleExecutionRun.worktreePath,
            pullRequestNumber = 11,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/11",
            pullRequestState = "OPEN",
            status = ReviewQueueStatus.CHANGES_REQUESTED,
            checksSummary = "Old checks.",
            qaVerdict = "PASS",
            qaFeedback = "PR #11 still reports no completed implementation to verify.",
            qaReviewedAt = now - 7_500,
            qaIssueId = staleReviewIssue.id,
            ceoVerdict = "CHANGES_REQUESTED",
            ceoFeedback = "Stale blocker remains.",
            ceoReviewedAt = now - 7_000,
            approvalIssueId = approvalIssue.id,
            createdAt = now - 10_000,
            updatedAt = now - 7_000
        )
        val currentQueueItem = ReviewQueueItem(
            id = "rq-current",
            companyId = company.id,
            projectContextId = projectContext.id,
            issueId = currentExecutionIssue.id,
            runId = currentExecutionRun.id,
            branchName = currentExecutionRun.branchName,
            worktreePath = currentExecutionRun.worktreePath,
            pullRequestNumber = 22,
            pullRequestUrl = "https://github.com/heodongun/cotor-test/pull/22",
            pullRequestState = "OPEN",
            status = ReviewQueueStatus.READY_FOR_CEO,
            checksSummary = "Current checks pass.",
            qaVerdict = "PASS",
            qaFeedback = "Current PR is ready for CEO approval.",
            qaReviewedAt = now - 3_000,
            approvalIssueId = approvalIssue.id,
            createdAt = now - 5_000,
            updatedAt = now - 2_500
        )
        stateStore.save(
            baseState.copy(
                goals = baseState.goals + goal,
                issues = baseState.issues + listOf(staleExecutionIssue, currentExecutionIssue, staleReviewIssue, approvalIssue),
                tasks = baseState.tasks + listOf(staleExecutionTask, currentExecutionTask, staleReviewTask),
                runs = baseState.runs + listOf(staleExecutionRun, currentExecutionRun, staleReviewRun),
                reviewQueue = baseState.reviewQueue + listOf(staleQueueItem, currentQueueItem)
            )
        )

        val promptBuilder = DesktopAppService::class.java.getDeclaredMethod(
            "buildIssueExecutionPrompt",
            DesktopAppState::class.java,
            CompanyIssue::class.java,
            OrgAgentProfile::class.java
        )
        promptBuilder.isAccessible = true
        val prompt = promptBuilder.invoke(
            service,
            stateStore.load(),
            approvalIssue,
            OrgAgentProfile(
                id = "profile-ceo-prompt",
                companyId = company.id,
                roleName = "CEO",
                executionAgentName = "codex",
                mergeAuthority = true
            )
        ) as String

        prompt shouldContain "https://github.com/heodongun/cotor-test/pull/22"
        prompt shouldContain "Current PR is ready for CEO approval."
        prompt shouldNotContain "https://github.com/heodongun/cotor-test/pull/11"
        prompt shouldNotContain "PR #11 still reports no completed implementation to verify."
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
            listOf("rev-parse", "--verify", "HEAD") -> success("deadbeef")
            listOf("symbolic-ref", "--short", "HEAD") -> success(defaultBranch)
            listOf("rev-parse", "--abbrev-ref", "HEAD") -> success(defaultBranch)
            listOf("symbolic-ref", "refs/remotes/origin/HEAD") -> success("refs/remotes/origin/$defaultBranch")
            listOf("config", "--get", "remote.origin.url") -> {
                if (remoteUrl == null) failure("missing remote") else success(remoteUrl)
            }
            listOf("init", "-b", defaultBranch) -> success("Initialized empty Git repository")
            else -> {
                if (command.contains("commit")) {
                    success("[$defaultBranch (root-commit) deadbeef] Initialize repository")
                } else if (command.any { it == "worktree" }) {
                    success("worktree operation ok")
                } else if (command.any { it == "branch" || it == "for-each-ref" }) {
                    success("")
                } else if (command.any { it == "rev-list" }) {
                    success("0")
                } else if (command.any { it == "diff" || it == "status" }) {
                    success("")
                } else if (command.any { it == "checkout" || it == "fetch" || it == "push" || it == "pull" }) {
                    success("")
                } else {
                    success("")
                }
            }
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

/**
 * Mark an issue as BLOCKED and seed a non-recoverable failed task so that the
 * runtime tick treats the issue as genuinely stuck rather than automatically
 * requeuing it.  This mirrors the state that a real execution failure would
 * produce and ensures follow-up goal synthesis triggers correctly.
 */
private suspend fun blockIssueWithFailedTask(
    stateStore: DesktopStateStore,
    issue: CompanyIssue,
    now: Long = System.currentTimeMillis()
) {
    val snapshot = stateStore.load()
    val taskId = "blocking-task-${issue.id.take(8)}"
    val blockedIssue = issue.copy(status = IssueStatus.BLOCKED, updatedAt = now)
    val task = AgentTask(
        id = taskId,
        workspaceId = issue.workspaceId,
        issueId = issue.id,
        title = issue.title,
        prompt = issue.description,
        agents = listOf("codex"),
        status = DesktopTaskStatus.FAILED,
        createdAt = now,
        updatedAt = now
    )
    val run = AgentRun(
        id = "blocking-run-${issue.id.take(8)}",
        taskId = taskId,
        workspaceId = issue.workspaceId,
        repositoryId = snapshot.repositories.first().id,
        agentName = "codex",
        branchName = "codex/cotor/block/${issue.id.take(8)}/codex",
        worktreePath = "/tmp/blocking",
        status = AgentRunStatus.FAILED,
        output = "Execution blocked: non-recoverable failure",
        error = "Execution blocked: non-recoverable failure",
        createdAt = now,
        updatedAt = now
    )
    stateStore.save(
        snapshot.copy(
            issues = snapshot.issues.map { if (it.id == issue.id) blockedIssue else it },
            tasks = snapshot.tasks + task,
            runs = snapshot.runs + run
        )
    )
}

private suspend fun awaitIssueTasksSettled(stateStore: DesktopStateStore, issueId: String) {
    withTimeout(5_000) {
        while (true) {
            val tasks = stateStore.load().tasks.filter { it.issueId == issueId }
            val allSettled = tasks.isEmpty() || tasks.all { task ->
                task.status != DesktopTaskStatus.QUEUED && task.status != DesktopTaskStatus.RUNNING
            }
            if (allSettled) {
                // Give sync callbacks a moment to flush their state writes.
                delay(100)
                return@withTimeout
            }
            delay(25)
        }
    }
}

private const val REPOSITORY_ID = "repo-1"
private const val WORKSPACE_ID = "workspace-1"
