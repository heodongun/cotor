package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.data.process.ProcessManager
import com.cotor.domain.executor.AgentExecutor
import com.cotor.integrations.linear.LinearIssueMirror
import com.cotor.integrations.linear.LinearTrackerAdapter
import com.cotor.model.ProcessResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path

private const val REQUEUE_REPOSITORY_ID = "repo-1"
private const val REQUEUE_WORKSPACE_ID = "ws-1"

class DesktopAppServiceRequeueOrphanedTest : FunSpec({
    afterTest {
        DesktopAppService.shutdownAllForTesting()
    }

    test("company runtime requeues orphaned blocked autonomous issues with no tasks") {
        val repoRoot = Files.createTempDirectory("desktop-company-requeue-orphaned-repo")
        val appHome = Files.createTempDirectory("desktop-company-requeue-orphaned-home")
        val stateStore = DesktopStateStore { appHome }
        val company = Company(
            id = "company-requeue-orphaned",
            name = "Requeue Orphaned Co",
            rootPath = repoRoot.toString(),
            repositoryId = REQUEUE_REPOSITORY_ID,
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
            workspaceId = REQUEUE_WORKSPACE_ID,
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
                        id = REQUEUE_REPOSITORY_ID,
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
                        id = REQUEUE_WORKSPACE_ID,
                        repositoryId = REQUEUE_REPOSITORY_ID,
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
        val service = requeueTestService(
            processManager = RequeueGitProcessManager(repoRoot, null, "master"),
            stateStore = stateStore
        )

        try {
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

            val settledState = withTimeout(30_000) {
                while (true) {
                    service.companyDashboardPrepared(company.id)
                    val current = stateStore.load()
                    val currentIssue = current.issues.first { it.id == issue.id }
                    val issueTasks = current.tasks.filter { it.issueId == issue.id }
                    val requeueRecorded = current.companyActivity.any {
                        it.issueId == issue.id && it.source == "requeueRecoverableBlockedIssues"
                    }
                    if (
                        requeueRecorded &&
                        currentIssue.updatedAt > issue.updatedAt &&
                        (currentIssue.status != IssueStatus.BLOCKED || issueTasks.isNotEmpty())
                    ) {
                        return@withTimeout current
                    }
                    delay(25)
                }
                error("Unreachable")
            }
            val latestIssue = settledState.issues.first { it.id == issue.id }
            val issueTasks = settledState.tasks.filter { it.issueId == issue.id }

            settledState.companyActivity.any {
                it.issueId == issue.id && it.source == "requeueRecoverableBlockedIssues"
            } shouldBe true
            (latestIssue.status != IssueStatus.BLOCKED || issueTasks.isNotEmpty()) shouldBe true
            (latestIssue.updatedAt > issue.updatedAt) shouldBe true
        } finally {
            service.shutdown()
        }
    }
})

private fun requeueTestService(
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
        linearTracker = NoopLinearTrackerAdapter()
    )
}

private class NoopLinearTrackerAdapter : LinearTrackerAdapter {
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

private class RequeueGitProcessManager(
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
