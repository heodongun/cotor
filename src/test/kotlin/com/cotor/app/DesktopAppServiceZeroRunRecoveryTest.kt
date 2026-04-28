package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.data.process.ProcessManager
import com.cotor.domain.executor.AgentExecutor
import com.cotor.integrations.linear.LinearTrackerAdapter
import com.cotor.model.ProcessResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path

class DesktopAppServiceZeroRunRecoveryTest : FunSpec({
    afterTest {
        DesktopAppService.shutdownAllForTesting()
    }

    test("company runtime tick reconciles running tasks that never wrote any runs") {
        val appHome = Files.createTempDirectory("desktop-runtime-zero-run-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-zero-run-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedZeroRunWorkspace(stateStore, repoRoot)
        val service = zeroRunTestService(
            processManager = ZeroRunFakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Zero Run Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Recover zero-run tasks",
            description = "Heal a running task that never persisted a run record.",
            autonomyEnabled = false
        )
        val issue = service.listIssues(goal.id).first { it.kind == "execution" }
        val staleAt = System.currentTimeMillis() - 60_000L
        val task = AgentTask(
            id = "zero-run-task",
            workspaceId = issue.workspaceId,
            issueId = issue.id,
            title = issue.title,
            prompt = issue.description,
            agents = listOf("codex"),
            status = DesktopTaskStatus.RUNNING,
            createdAt = staleAt,
            updatedAt = staleAt
        )
        stateStore.save(
            stateStore.load().copy(
                issues = stateStore.load().issues.map {
                    if (it.id == issue.id) it.copy(status = IssueStatus.IN_PROGRESS, updatedAt = staleAt) else it
                },
                tasks = stateStore.load().tasks + task,
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
        reconciled.runs.filter { it.taskId == task.id }.shouldBeEmpty()
        reconciled.tasks.first { it.id == task.id }.status shouldBe DesktopTaskStatus.FAILED
        reconciled.issues.first { it.id == issue.id }.status shouldBe IssueStatus.BLOCKED
    }

    test("runTask syncs the issue to failed when execution dies before any run is persisted") {
        val appHome = Files.createTempDirectory("desktop-prerun-failure-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-prerun-failure-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedZeroRunWorkspace(stateStore, repoRoot)
        val service = zeroRunTestService(
            processManager = ZeroRunFakeGitProcessManager(
                repoRoot = repoRoot,
                remoteUrl = "https://github.com/heodongun/cotor.git",
                defaultBranch = "master"
            ),
            stateStore = stateStore
        )

        val company = service.createCompany(
            name = "Pre Run Failure Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "master"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Fail before any run exists",
            description = "Exercise the launcher-side failure sync path.",
            autonomyEnabled = false
        )
        val issue = service.listIssues(goal.id).first { it.kind == "execution" }
        val task = service.createTask(
            workspaceId = issue.workspaceId,
            title = issue.title,
            prompt = issue.description,
            agents = listOf("codex"),
            issueId = issue.id
        )
        val snapshot = stateStore.load()
        stateStore.save(
            snapshot.copy(
                repositories = emptyList()
            )
        )

        service.runTask(task.id)

        withTimeout(5_000) {
            while (true) {
                val current = stateStore.load()
                val taskFailed = current.tasks.first { it.id == task.id }.status == DesktopTaskStatus.FAILED
                val issueBlocked = current.issues.first { it.id == issue.id }.status == IssueStatus.BLOCKED
                if (taskFailed && issueBlocked) {
                    return@withTimeout
                }
                delay(25)
            }
        }

        val settled = stateStore.load()
        settled.runs.filter { it.taskId == task.id }.shouldBeEmpty()
        settled.tasks.first { it.id == task.id }.status shouldBe DesktopTaskStatus.FAILED
        val settledIssue = settled.issues.first { it.id == issue.id }
        settledIssue.status shouldBe IssueStatus.BLOCKED
        settledIssue.durableRunId.shouldBeNull()
    }
})

private fun zeroRunTestService(
    processManager: ProcessManager,
    appHome: Path? = null,
    stateStore: DesktopStateStore? = null,
    linearTracker: LinearTrackerAdapter = ZeroRunFakeLinearTrackerAdapter()
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

private class ZeroRunFakeLinearTrackerAdapter : LinearTrackerAdapter {
    override suspend fun health(config: LinearConnectionConfig): Result<String> = Result.success("ok")

    override suspend fun syncIssue(
        config: LinearConnectionConfig,
        issue: CompanyIssue,
        desiredStateName: String?,
        assigneeId: String?
    ): Result<com.cotor.integrations.linear.LinearIssueMirror> = Result.success(
        com.cotor.integrations.linear.LinearIssueMirror(
            id = issue.linearIssueId ?: "lin-${issue.id}",
            identifier = issue.linearIssueIdentifier ?: "COT-1",
            url = issue.linearIssueUrl ?: "https://linear.app/issue/${issue.id}",
            stateName = desiredStateName
        )
    )

    override suspend fun createComment(
        config: LinearConnectionConfig,
        linearIssueId: String,
        body: String
    ): Result<Unit> = Result.success(Unit)
}

private class ZeroRunFakeGitProcessManager(
    private val repoRoot: Path,
    private val remoteUrl: String?,
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
            listOf("rev-parse", "--show-toplevel") -> zeroRunSuccess(repoRoot.toString())
            listOf("rev-parse", "--verify", "HEAD") -> zeroRunSuccess("deadbeef")
            listOf("symbolic-ref", "--short", "HEAD") -> zeroRunSuccess(defaultBranch)
            listOf("rev-parse", "--abbrev-ref", "HEAD") -> zeroRunSuccess(defaultBranch)
            listOf("symbolic-ref", "refs/remotes/origin/HEAD") -> zeroRunSuccess("refs/remotes/origin/$defaultBranch")
            listOf("config", "--get", "remote.origin.url") -> {
                if (remoteUrl == null) zeroRunFailure("missing remote") else zeroRunSuccess(remoteUrl)
            }
            listOf("init", "-b", defaultBranch) -> zeroRunSuccess("Initialized empty Git repository")
            else -> zeroRunSuccess("")
        }
    }
}

private fun zeroRunSuccess(stdout: String): ProcessResult = ProcessResult(
    exitCode = 0,
    stdout = "$stdout\n",
    stderr = "",
    isSuccess = true
)

private fun zeroRunFailure(stderr: String): ProcessResult = ProcessResult(
    exitCode = 1,
    stdout = "",
    stderr = stderr,
    isSuccess = false
)

private suspend fun seedZeroRunWorkspace(stateStore: DesktopStateStore, repoRoot: Path) {
    stateStore.save(
        DesktopAppState(
            repositories = listOf(
                ManagedRepository(
                    id = ZERO_RUN_REPOSITORY_ID,
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
                    id = ZERO_RUN_WORKSPACE_ID,
                    repositoryId = ZERO_RUN_REPOSITORY_ID,
                    name = "repo · master",
                    baseBranch = "master",
                    createdAt = 1,
                    updatedAt = 1
                )
            )
        )
    )
}

private const val ZERO_RUN_REPOSITORY_ID = "repo-1"
private const val ZERO_RUN_WORKSPACE_ID = "workspace-1"
