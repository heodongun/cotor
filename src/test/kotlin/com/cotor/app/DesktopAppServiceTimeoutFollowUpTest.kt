package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.data.process.ProcessManager
import com.cotor.domain.executor.AgentExecutor
import com.cotor.integrations.linear.LinearIssueMirror
import com.cotor.integrations.linear.LinearTrackerAdapter
import com.cotor.model.ProcessResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path

private const val FOLLOWUP_REPOSITORY_ID = "repo-1"
private const val FOLLOWUP_WORKSPACE_ID = "ws-1"

class DesktopAppServiceTimeoutFollowUpTest : FunSpec({
    test("runtime does not synthesize a follow-up goal while a timed-out issue is still recoverable") {
        val appHome = Files.createTempDirectory("desktop-runtime-timeout-followup-home")
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-runtime-timeout-followup-test").resolve("repo"))
        val stateStore = DesktopStateStore { appHome }
        seedTimeoutFollowUpWorkspace(stateStore, repoRoot)
        val service = timeoutFollowUpService(
            processManager = TimeoutFollowUpGitProcessManager(repoRoot, "https://github.com/heodongun/cotor.git", "master"),
            stateStore = stateStore
        )

        try {
            val company = service.createCompany(
                name = "Timeout Follow Up Co",
                rootPath = repoRoot.toString(),
                defaultBaseBranch = "master"
            )
            val goal = service.createGoal(
                companyId = company.id,
                title = "Retry timed out work without follow-up churn",
                description = "Timed out work should stay in retry logic before CEO follow-up is synthesized.",
                autonomyEnabled = false
            )
            val executionIssue = service.listIssues(goal.id).first { it.kind == "execution" }
            val existingTask = service.createTask(
                workspaceId = executionIssue.workspaceId,
                title = executionIssue.title,
                prompt = executionIssue.description,
                agents = listOf("opencode"),
                issueId = executionIssue.id
            )
            val timeoutAt = System.currentTimeMillis()
            val timedOutRun = AgentRun(
                id = "workflow-timeout-followup-run",
                taskId = existingTask.id,
                workspaceId = executionIssue.workspaceId,
                repositoryId = stateStore.load().repositories.first().id,
                agentName = "opencode",
                branchName = "codex/cotor/timeout-followup/opencode",
                worktreePath = repoRoot.resolve(".cotor/worktrees/timeout-followup/opencode").toString(),
                status = AgentRunStatus.FAILED,
                error = "Execution timeout after 900000ms",
                durationMs = 900_000,
                createdAt = timeoutAt,
                updatedAt = timeoutAt
            )
            val snapshot = stateStore.load()
            stateStore.save(
                snapshot.copy(
                    issues = snapshot.issues.map {
                        if (it.id == executionIssue.id) it.copy(status = IssueStatus.BLOCKED, updatedAt = timeoutAt) else it
                    },
                    tasks = snapshot.tasks.map {
                        if (it.id == existingTask.id) it.copy(status = DesktopTaskStatus.FAILED, updatedAt = timeoutAt) else it
                    },
                    runs = snapshot.runs + timedOutRun
                )
            )

            service.updateGoal(goal.id, autonomyEnabled = true)
            service.companyDashboard(company.id)

            val refreshed = stateStore.load()
            refreshed.goals.first { it.id == goal.id }.status shouldBe GoalStatus.ACTIVE
            refreshed.issues.first { it.id == executionIssue.id }.status shouldNotBe IssueStatus.CANCELED
            refreshed.goals.none {
                it.companyId == company.id && it.operatingPolicy == "auto-follow-up:goal:${goal.id}"
            } shouldBe true
        } finally {
            service.shutdown()
        }
    }
})

private fun timeoutFollowUpService(
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
        agentExecutor = mockk<AgentExecutor>(relaxed = true),
        linearTracker = NoopTimeoutFollowUpLinearTrackerAdapter()
    )
}

private class NoopTimeoutFollowUpLinearTrackerAdapter : LinearTrackerAdapter {
    override suspend fun health(config: LinearConnectionConfig): Result<String> = Result.success("disabled")

    override suspend fun syncIssue(
        config: LinearConnectionConfig,
        issue: CompanyIssue,
        desiredStateName: String?,
        assigneeId: String?
    ): Result<LinearIssueMirror> = Result.success(LinearIssueMirror(id = issue.linearIssueId ?: "noop-${issue.id}"))

    override suspend fun createComment(
        config: LinearConnectionConfig,
        linearIssueId: String,
        body: String
    ): Result<Unit> = Result.success(Unit)
}

private class TimeoutFollowUpGitProcessManager(
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
            listOf("rev-parse", "--show-toplevel") -> success(repoRoot.toString())
            listOf("rev-parse", "--verify", "HEAD") -> success("deadbeef")
            listOf("symbolic-ref", "--short", "HEAD") -> success(defaultBranch)
            listOf("rev-parse", "--abbrev-ref", "HEAD") -> success(defaultBranch)
            listOf("symbolic-ref", "refs/remotes/origin/HEAD") -> success("refs/remotes/origin/$defaultBranch")
            listOf("config", "--get", "remote.origin.url") -> if (remoteUrl == null) failure("missing remote") else success(remoteUrl)
            listOf("init", "-b", defaultBranch) -> success("Initialized empty Git repository")
            else -> success("")
        }
    }

    private fun success(stdout: String) = ProcessResult(0, "$stdout\n", "", true)
    private fun failure(stderr: String) = ProcessResult(1, "", stderr, false)
}

private suspend fun seedTimeoutFollowUpWorkspace(stateStore: DesktopStateStore, repoRoot: Path) {
    stateStore.save(
        DesktopAppState(
            repositories = listOf(
                ManagedRepository(
                    id = FOLLOWUP_REPOSITORY_ID,
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
                    id = FOLLOWUP_WORKSPACE_ID,
                    repositoryId = FOLLOWUP_REPOSITORY_ID,
                    name = "repo · master",
                    baseBranch = "master",
                    createdAt = 1,
                    updatedAt = 1
                )
            )
        )
    )
}
