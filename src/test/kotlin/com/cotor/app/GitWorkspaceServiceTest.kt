package com.cotor.app

import com.cotor.data.process.ProcessManager
import com.cotor.model.ProcessResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path

class GitWorkspaceServiceTest : FunSpec({
    test("ensureWorktree uses unique branches for repeated task titles") {
        val repositoryRoot = Files.createTempDirectory("git-workspace-repo")
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(
                    listOf("git", "rev-parse", "--verify", "HEAD"),
                    ProcessResult(0, "abc1234567890\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "show-ref", "--verify", "--quiet", "refs/heads/codex/cotor/re-run-validation-and-capture-any-residual-risk-task-a-1/codex"),
                    ProcessResult(1, "", "", false)
                ),
                FakeProcessManager.Step(
                    listOf(
                        "git",
                        "worktree",
                        "add",
                        "-b",
                        "codex/cotor/re-run-validation-and-capture-any-residual-risk-task-a-1/codex",
                        repositoryRoot.resolve(".cotor").resolve("worktrees").resolve("task-a-1").resolve("codex").toString(),
                        "master"
                    ),
                    ProcessResult(0, "", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "rev-parse", "--verify", "HEAD"),
                    ProcessResult(0, "abc1234567890\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "show-ref", "--verify", "--quiet", "refs/heads/codex/cotor/re-run-validation-and-capture-any-residual-risk-task-b-2/codex"),
                    ProcessResult(1, "", "", false)
                ),
                FakeProcessManager.Step(
                    listOf(
                        "git",
                        "worktree",
                        "add",
                        "-b",
                        "codex/cotor/re-run-validation-and-capture-any-residual-risk-task-b-2/codex",
                        repositoryRoot.resolve(".cotor").resolve("worktrees").resolve("task-b-2").resolve("codex").toString(),
                        "master"
                    ),
                    ProcessResult(0, "", "", true)
                )
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val first = service.ensureWorktree(
            repositoryRoot = repositoryRoot,
            taskId = "task-a-1",
            taskTitle = "Re-run validation and capture any residual risk",
            agentName = "codex",
            baseBranch = "master"
        )
        val second = service.ensureWorktree(
            repositoryRoot = repositoryRoot,
            taskId = "task-b-2",
            taskTitle = "Re-run validation and capture any residual risk",
            agentName = "codex",
            baseBranch = "master"
        )

        first.branchName shouldBe "codex/cotor/re-run-validation-and-capture-any-residual-risk-task-a-1/codex"
        second.branchName shouldBe "codex/cotor/re-run-validation-and-capture-any-residual-risk-task-b-2/codex"
        first.branchName shouldContain "task-a-1"
        second.branchName shouldContain "task-b-2"
        processManager.remainingSteps() shouldBe 0
    }

    test("ensureInitializedRepositoryRoot bootstraps an existing repo that has no commits yet") {
        val repositoryRoot = Files.createTempDirectory("git-workspace-existing-repo")
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(
                    listOf("git", "rev-parse", "--show-toplevel"),
                    ProcessResult(0, repositoryRoot.toString() + "\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "rev-parse", "--verify", "HEAD"),
                    ProcessResult(1, "", "", false)
                ),
                FakeProcessManager.Step(
                    listOf(
                        "git",
                        "-c",
                        "user.name=Cotor",
                        "-c",
                        "user.email=cotor@local",
                        "commit",
                        "--allow-empty",
                        "-m",
                        "Initialize repository"
                    ),
                    ProcessResult(0, "[master (root-commit) abc1234] Initialize repository\n", "", true)
                )
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val resolved = service.ensureInitializedRepositoryRoot(repositoryRoot, "master")

        resolved shouldBe repositoryRoot
        processManager.remainingSteps() shouldBe 0
    }

    test("publishRun commits, pushes, and creates a pull request when the worktree has changes") {
        val worktree = Files.createTempDirectory("git-workspace-service-test")
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(listOf("git", "status", "--porcelain"), ProcessResult(0, " M src/App.kt\n", "", true)),
                FakeProcessManager.Step(listOf("git", "add", "-A"), ProcessResult(0, "", "", true)),
                FakeProcessManager.Step(listOf("git", "commit", "-m", "Ship desktop publish flow (codex)"), ProcessResult(0, "[branch abc1234] Ship desktop publish flow\n", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-parse", "HEAD"), ProcessResult(0, "abc1234567890\n", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-list", "--count", "master..HEAD"), ProcessResult(0, "1\n", "", true)),
                FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/cotor.git\n", "", true)),
                FakeProcessManager.Step(listOf("git", "push", "--set-upstream", "origin", "HEAD:codex/cotor/ship-flow/codex"), ProcessResult(0, "", "", true)),
                FakeProcessManager.Step(listOf("git", "ls-remote", "--heads", "origin", "master"), ProcessResult(0, "abc123\trefs/heads/master\n", "", true)),
                FakeProcessManager.Step(listOf("gh", "pr", "list", "--head", "codex/cotor/ship-flow/codex", "--state", "open", "--json", "number,url,state,reviewDecision,mergeStateStatus"), ProcessResult(0, "[]", "", true)),
                FakeProcessManager.Step(listOf("gh", "pr", "create", "--base", "master", "--head", "codex/cotor/ship-flow/codex", "--title", "[codex] Ship desktop publish flow", "--body", expectedPullRequestBody()), ProcessResult(0, "https://github.com/heodongun/cotor/pull/123\n", "", true)),
                FakeProcessManager.Step(listOf("gh", "pr", "view", "codex/cotor/ship-flow/codex", "--json", "number,url,state,reviewDecision,mergeStateStatus"), ProcessResult(0, """{"number":123,"url":"https://github.com/heodongun/cotor/pull/123","state":"OPEN","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"CLEAN"}""", "", true))
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val publish = service.publishRun(
            task = AgentTask(
                id = "task-1",
                workspaceId = "ws-1",
                title = "Ship desktop publish flow",
                prompt = "Implement the desktop publish flow.",
                agents = listOf("codex"),
                status = DesktopTaskStatus.RUNNING,
                createdAt = 0,
                updatedAt = 0
            ),
            agentName = "codex",
            worktreePath = worktree,
            branchName = "codex/cotor/ship-flow/codex",
            baseBranch = "master"
        )

        publish.commitSha shouldBe "abc1234567890"
        publish.pushedBranch shouldBe "codex/cotor/ship-flow/codex"
        publish.pullRequestNumber shouldBe 123
        publish.pullRequestUrl shouldBe "https://github.com/heodongun/cotor/pull/123"
        publish.error.shouldBeNull()
        processManager.remainingSteps() shouldBe 0
        processManager.workingDirectories().distinct() shouldBe listOf(worktree)
    }

    test("publishRun returns a clear error when there is nothing to publish") {
        val worktree = Files.createTempDirectory("git-workspace-service-empty")
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(listOf("git", "status", "--porcelain"), ProcessResult(0, "", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-parse", "HEAD"), ProcessResult(0, "abc1234567890\n", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-list", "--count", "master..HEAD"), ProcessResult(0, "0\n", "", true))
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val publish = service.publishRun(
            task = AgentTask(
                id = "task-2",
                workspaceId = "ws-1",
                title = "Read-only workflow",
                prompt = "Inspect repository state only.",
                agents = listOf("codex"),
                status = DesktopTaskStatus.RUNNING,
                createdAt = 0,
                updatedAt = 0
            ),
            agentName = "codex",
            worktreePath = worktree,
            branchName = "codex/cotor/read-only/codex",
            baseBranch = "master"
        )

        publish.commitSha shouldBe "abc1234567890"
        publish.pushedBranch.shouldBeNull()
        publish.pullRequestUrl.shouldBeNull()
        publish.error shouldBe "No changes to publish from codex/cotor/read-only/codex against master"
        processManager.remainingSteps() shouldBe 0
    }

    test("publishRun succeeds locally when no origin remote is configured") {
        val worktree = Files.createTempDirectory("git-workspace-service-local-only")
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(listOf("git", "status", "--porcelain"), ProcessResult(0, "?? smoke-artifact.txt\n", "", true)),
                FakeProcessManager.Step(listOf("git", "add", "-A"), ProcessResult(0, "", "", true)),
                FakeProcessManager.Step(listOf("git", "commit", "-m", "Local-only execution (codex)"), ProcessResult(0, "[branch abc1234] Local-only execution\n", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-parse", "HEAD"), ProcessResult(0, "abc1234567890\n", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-list", "--count", "master..HEAD"), ProcessResult(0, "1\n", "", true)),
                FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(1, "", "", false)),
                FakeProcessManager.Step(listOf("gh", "auth", "status"), ProcessResult(1, "", "not logged in", false))
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val publish = service.publishRun(
            task = AgentTask(
                id = "task-3",
                workspaceId = "ws-1",
                title = "Local-only execution",
                prompt = "Create a local artifact only.",
                agents = listOf("codex"),
                status = DesktopTaskStatus.RUNNING,
                createdAt = 0,
                updatedAt = 0
            ),
            agentName = "codex",
            worktreePath = worktree,
            branchName = "codex/cotor/local-only/codex",
            baseBranch = "master"
        )

        publish.commitSha shouldBe "abc1234567890"
        publish.pushedBranch.shouldBeNull()
        publish.pullRequestUrl.shouldBeNull()
        publish.error shouldBe "No GitHub remote configured; kept local commit only"
        processManager.remainingSteps() shouldBe 0
    }

    test("publishRun creates a GitHub remote when origin is missing and gh is available") {
        val worktree = Files.createTempDirectory("git-workspace-service-auto-github")
        val repoName = worktree.fileName.toString()
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(listOf("git", "status", "--porcelain"), ProcessResult(0, " M README.md\n", "", true)),
                FakeProcessManager.Step(listOf("git", "add", "-A"), ProcessResult(0, "", "", true)),
                FakeProcessManager.Step(listOf("git", "commit", "-m", "Auto publish to GitHub (codex)"), ProcessResult(0, "[branch abc1234] Auto publish to GitHub\n", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-parse", "HEAD"), ProcessResult(0, "abc1234567890\n", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-list", "--count", "master..HEAD"), ProcessResult(0, "1\n", "", true)),
                FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(1, "", "", false)),
                FakeProcessManager.Step(listOf("gh", "auth", "status"), ProcessResult(0, "logged in", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-parse", "--git-common-dir"), ProcessResult(0, ".git\n", "", true)),
                FakeProcessManager.Step(listOf("gh", "repo", "view", repoName, "--json", "name"), ProcessResult(1, "", "not found", false)),
                FakeProcessManager.Step(listOf("gh", "repo", "create", repoName, "--private", "--source", ".", "--remote", "origin"), ProcessResult(0, "created", "", true)),
                FakeProcessManager.Step(listOf("git", "push", "--set-upstream", "origin", "refs/heads/master:refs/heads/master"), ProcessResult(0, "", "", true)),
                FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/$repoName.git\n", "", true)),
                FakeProcessManager.Step(listOf("git", "push", "--set-upstream", "origin", "HEAD:codex/cotor/auto-publish/codex"), ProcessResult(0, "", "", true)),
                FakeProcessManager.Step(listOf("git", "ls-remote", "--heads", "origin", "master"), ProcessResult(0, "abc123\trefs/heads/master\n", "", true)),
                FakeProcessManager.Step(listOf("gh", "pr", "list", "--head", "codex/cotor/auto-publish/codex", "--state", "open", "--json", "number,url,state,reviewDecision,mergeStateStatus"), ProcessResult(0, "[]", "", true)),
                FakeProcessManager.Step(listOf("gh", "pr", "create", "--base", "master", "--head", "codex/cotor/auto-publish/codex", "--title", "[codex] Auto publish to GitHub", "--body", expectedPullRequestBody("Auto publish to GitHub", "Ship this change to GitHub.", "codex/cotor/auto-publish/codex")), ProcessResult(0, "https://github.com/heodongun/cotor/pull/124\n", "", true)),
                FakeProcessManager.Step(listOf("gh", "pr", "view", "codex/cotor/auto-publish/codex", "--json", "number,url,state,reviewDecision,mergeStateStatus"), ProcessResult(0, """{"number":124,"url":"https://github.com/heodongun/cotor/pull/124","state":"OPEN","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"CLEAN"}""", "", true))
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val publish = service.publishRun(
            task = AgentTask(
                id = "task-4",
                workspaceId = "ws-1",
                title = "Auto publish to GitHub",
                prompt = "Ship this change to GitHub.",
                agents = listOf("codex"),
                status = DesktopTaskStatus.RUNNING,
                createdAt = 0,
                updatedAt = 0
            ),
            agentName = "codex",
            worktreePath = worktree,
            branchName = "codex/cotor/auto-publish/codex",
            baseBranch = "master"
        )

        publish.commitSha shouldBe "abc1234567890"
        publish.pushedBranch shouldBe "codex/cotor/auto-publish/codex"
        publish.pullRequestNumber shouldBe 124
        publish.pullRequestUrl shouldBe "https://github.com/heodongun/cotor/pull/124"
        publish.error.shouldBeNull()
        processManager.remainingSteps() shouldBe 0
    }
})

private fun expectedPullRequestBody(
    taskTitle: String = "Ship desktop publish flow",
    prompt: String = "Implement the desktop publish flow.",
    branchName: String = "codex/cotor/ship-flow/codex"
): String = """
## Summary
- Auto-published by the Cotor desktop app after task completion.
- Task: $taskTitle
- Agent: codex
- Branch: $branchName
- Base: master

## Prompt
```text
$prompt
```
""".trimIndent() + "\n"

private class FakeProcessManager(
    steps: List<Step>
) : ProcessManager {
    private val steps = steps.toMutableList()
    private val workingDirectories = mutableListOf<Path?>()

    override suspend fun executeProcess(
        command: List<String>,
        input: String?,
        environment: Map<String, String>,
        timeout: Long,
        workingDirectory: Path?,
        onStart: ((Long) -> Unit)?
    ): ProcessResult {
        val next = steps.removeFirstOrNull() ?: error("Unexpected command: ${command.joinToString(" ")}")
        next.command shouldBe command
        workingDirectories += workingDirectory
        return next.result
    }

    fun remainingSteps(): Int = steps.size

    fun workingDirectories(): List<Path?> = workingDirectories.toList()

    data class Step(
        val command: List<String>,
        val result: ProcessResult
    )
}
