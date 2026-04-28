package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.data.process.ProcessManager
import com.cotor.domain.executor.AgentExecutor
import com.cotor.model.AgentConfig
import com.cotor.model.AgentExecutionMetadata
import com.cotor.model.AgentResult
import com.cotor.model.ProcessResult
import com.cotor.model.RetryPolicy
import com.cotor.testsupport.withDesktopServiceShutdown
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path

class DesktopAppServiceExecutionLogFailureClassTest : FunSpec({
    afterTest {
        DesktopAppService.shutdownAllForTesting()
    }

    test("company execution log classifies QA artifact quality scan failures") {
        val appHome = Files.createTempDirectory("desktop-artifact-quality-failure-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-artifact-quality-failure-repo").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = GitWorkspaceService(
                processManager = ArtifactQualityFakeGitProcessManager(repoRoot, defaultBranch = "master"),
                stateStore = stateStore,
                logger = mockk(relaxed = true)
            ),
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = ArtifactQualityFailingExecutor
        )

        withDesktopServiceShutdown(service) {
            val company = service.createCompany(
                name = "Artifact Quality Co",
                rootPath = repoRoot.toString(),
                defaultBaseBranch = "master"
            )
            service.batchUpdateCompanyAgentDefinitions(
                companyId = company.id,
                agentIds = service.listCompanyAgentDefinitions(company.id).map { it.id },
                agentCli = "echo",
                specialties = null,
                enabled = null
            )
            val goal = service.createGoal(
                companyId = company.id,
                title = "Artifact quality goal",
                description = "Validate artifact quality failure classification.",
                startRuntimeIfNeeded = false
            )
            val issue = service.createIssue(
                companyId = company.id,
                goalId = goal.id,
                title = "Artifact quality validation",
                description = "Run local checks only",
                kind = "execution"
            )

            val settled = service.runIssueAndAwaitSettlement(issue.id, timeoutMs = 30_000)

            settled.status shouldBe IssueStatus.BLOCKED
            settled.providerBlockReason shouldContain "QA artifact quality scan failed"
            val artifactIssueLog = service.executionLog(company.id).single { it["issueId"] == issue.id }
            val artifactTasks = artifactIssueLog["tasks"] as List<Map<String, Any?>>
            artifactTasks
                .flatMap { task -> task["runs"] as List<Map<String, Any?>> }
                .single { run -> run["failureClass"] == "artifact-quality" }
                .let { run ->
                    run["error"].toString() shouldContain "Generated artifact quality scan failed"
                    run["failureClass"] shouldBe "artifact-quality"
                }
        }
    }

    test("company execution log classifies no-diff publish failures and blocked activity is warning") {
        val appHome = Files.createTempDirectory("desktop-no-diff-failure-home")
        val repoRoot = Files.createTempDirectory("desktop-no-diff-failure-repo")
        val worktreeRoot = Files.createTempDirectory("desktop-no-diff-failure-worktree")
        val stateStore = DesktopStateStore { appHome }
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        val agentExecutor = mockk<AgentExecutor>()
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = agentExecutor,
            autoStartAutomationRefresh = false
        )
        val repository = ManagedRepository(
            id = "repo-no-diff",
            name = "repo",
            localPath = repoRoot.toString(),
            sourceKind = RepositorySourceKind.LOCAL,
            defaultBranch = "master",
            createdAt = 1L,
            updatedAt = 1L
        )
        val workspace = Workspace(
            id = "workspace-no-diff",
            repositoryId = repository.id,
            name = "repo · master",
            baseBranch = "master",
            createdAt = 1L,
            updatedAt = 1L
        )
        val company = Company(
            id = "company-no-diff",
            name = "No Diff Co",
            rootPath = repoRoot.toString(),
            repositoryId = repository.id,
            defaultBaseBranch = "master",
            createdAt = 1L,
            updatedAt = 1L
        )
        val projectContext = CompanyProjectContext(
            id = "context-no-diff",
            companyId = company.id,
            name = "No Diff Co",
            slug = "no-diff-co",
            contextDocPath = appHome.resolve("context.md").toString(),
            lastUpdatedAt = 1L
        )
        val goal = CompanyGoal(
            id = "goal-no-diff",
            companyId = company.id,
            projectContextId = projectContext.id,
            title = "Ship code change",
            description = "Code-producing work must produce a reviewable diff.",
            status = GoalStatus.ACTIVE,
            createdAt = 1L,
            updatedAt = 1L
        )
        val issue = CompanyIssue(
            id = "issue-no-diff",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "Implement code change",
            description = "Make a code change.",
            status = IssueStatus.IN_PROGRESS,
            priority = 1,
            kind = "execution",
            codeProducing = true,
            executionIntent = ExecutionIntent.CODE_CHANGE,
            createdAt = 1L,
            updatedAt = 1L
        )
        val task = AgentTask(
            id = "task-no-diff",
            workspaceId = workspace.id,
            issueId = issue.id,
            title = issue.title,
            prompt = issue.description,
            agents = listOf("codex"),
            status = DesktopTaskStatus.QUEUED,
            createdAt = 1L,
            updatedAt = 1L
        )
        stateStore.save(
            DesktopAppState(
                repositories = listOf(repository),
                workspaces = listOf(workspace),
                companies = listOf(company),
                projectContexts = listOf(projectContext),
                goals = listOf(goal),
                issues = listOf(issue),
                tasks = listOf(task)
            )
        )
        coEvery { gitWorkspaceService.ensureWorktree(any(), any(), any(), any(), any()) } returns WorktreeBinding(
            branchName = "codex/cotor/no-diff/codex",
            worktreePath = worktreeRoot
        )
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } returns AgentResult(
            agentName = "codex",
            isSuccess = true,
            output = "No code changes were needed.",
            error = null,
            duration = 250,
            metadata = emptyMap(),
            processId = 4242
        )
        coEvery { gitWorkspaceService.publishRun(any(), any(), any(), any(), any(), any()) } returns PublishMetadata(
            pushedBranch = "codex/cotor/no-diff/codex",
            error = "No changes to publish from codex/cotor/no-diff/codex against master"
        )

        withDesktopServiceShutdown(service) {
            service.runTask(task.id)
            withTimeout(10_000) {
                while (stateStore.load().issues.single { it.id == issue.id }.status != IssueStatus.BLOCKED) {
                    delay(25)
                }
            }

            val updatedState = stateStore.load()
            updatedState.issues.single { it.id == issue.id }.providerBlockReason shouldContain "No changes to publish"
            updatedState.companyActivity.first {
                it.source == "task-run" && it.issueId == issue.id && it.title == "Updated issue state"
            }.severity shouldBe "warning"
            var latestRunLog: Map<String, Any?>? = null
            val runLog = try {
                withTimeout(10_000) {
                    suspend fun latestRun(): Map<String, Any?> {
                        val issueLog = service.executionLog(company.id).single { it["issueId"] == issue.id }
                        val issueTasks = issueLog["tasks"] as List<Map<String, Any?>>
                        return issueTasks
                            .flatMap { task -> task["runs"] as List<Map<String, Any?>> }
                            .single()
                    }
                    var latest = latestRun()
                    latestRunLog = latest
                    while (latest["status"] != "FAILED" || latest["failureClass"] != "no-diff") {
                        delay(25)
                        latest = latestRun()
                        latestRunLog = latest
                    }
                    latest
                }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("Timed out waiting for no-diff failed run. Latest run log: $latestRunLog", timeout)
            }
            runLog["status"] shouldBe "FAILED"
            runLog["publishError"].toString() shouldContain "No changes to publish"
            runLog["failureClass"] shouldBe "no-diff"
        }
    }
})

private object ArtifactQualityFailingExecutor : AgentExecutor {
    override suspend fun executeAgent(
        agent: AgentConfig,
        input: String?,
        metadata: AgentExecutionMetadata
    ): AgentResult = AgentResult(
        agentName = agent.name,
        isSuccess = false,
        output = null,
        error = """
            QA artifact quality scan failed
            Generated artifact quality scan failed after the verification command succeeded.
            - index.html:1: contains unresolved VALUE token in user-facing text
        """.trimIndent(),
        duration = 18,
        metadata = emptyMap()
    )

    override suspend fun executeWithRetry(
        agent: AgentConfig,
        input: String?,
        retryPolicy: RetryPolicy,
        metadata: AgentExecutionMetadata
    ): AgentResult = executeAgent(agent, input, metadata)
}

private class ArtifactQualityFakeGitProcessManager(
    private val repoRoot: Path,
    private val defaultBranch: String
) : ProcessManager {
    override suspend fun executeProcess(
        command: List<String>,
        input: String?,
        environment: Map<String, String>,
        timeout: Long,
        workingDirectory: Path?,
        onStart: ((Long) -> Unit)?
    ): ProcessResult {
        val gitArgs = command.drop(1)
        return when (gitArgs) {
            listOf("rev-parse", "--show-toplevel") -> success(repoRoot.toString())
            listOf("rev-parse", "--verify", "HEAD") -> success("deadbeef")
            listOf("symbolic-ref", "--short", "HEAD") -> success(defaultBranch)
            listOf("rev-parse", "--abbrev-ref", "HEAD") -> success(defaultBranch)
            listOf("symbolic-ref", "refs/remotes/origin/HEAD") -> success("refs/remotes/origin/$defaultBranch")
            listOf("config", "--get", "remote.origin.url") -> failure("missing remote")
            listOf("init", "-b", defaultBranch) -> success("Initialized empty Git repository")
            else -> success("")
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
