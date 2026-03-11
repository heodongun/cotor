package com.cotor.app

import com.cotor.data.process.ProcessManager
import com.cotor.model.ProcessResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path

class GitWorkspaceServiceTest : FunSpec({
    test("publishRun commits, pushes, and creates a pull request when the worktree has changes") {
        val worktree = Files.createTempDirectory("git-workspace-service-test")
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(listOf("git", "status", "--porcelain"), ProcessResult(0, " M src/App.kt\n", "", true)),
                FakeProcessManager.Step(listOf("git", "add", "-A"), ProcessResult(0, "", "", true)),
                FakeProcessManager.Step(listOf("git", "commit", "-m", "Ship desktop publish flow (codex)"), ProcessResult(0, "[branch abc1234] Ship desktop publish flow\n", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-parse", "HEAD"), ProcessResult(0, "abc1234567890\n", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-list", "--count", "master..HEAD"), ProcessResult(0, "1\n", "", true)),
                FakeProcessManager.Step(listOf("git", "push", "--set-upstream", "origin", "HEAD:codex/cotor/ship-flow/codex"), ProcessResult(0, "", "", true)),
                FakeProcessManager.Step(listOf("gh", "pr", "list", "--head", "codex/cotor/ship-flow/codex", "--state", "open", "--json", "number,url,state"), ProcessResult(0, "[]", "", true)),
                FakeProcessManager.Step(listOf("gh", "pr", "create", "--base", "master", "--head", "codex/cotor/ship-flow/codex", "--title", "[codex] Ship desktop publish flow", "--body", expectedPullRequestBody()), ProcessResult(0, "https://github.com/heodongun/cotor/pull/123\n", "", true)),
                FakeProcessManager.Step(listOf("gh", "pr", "view", "codex/cotor/ship-flow/codex", "--json", "number,url,state"), ProcessResult(0, """{"number":123,"url":"https://github.com/heodongun/cotor/pull/123","state":"OPEN"}""", "", true))
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
})

private fun expectedPullRequestBody(): String = """
## Summary
- Auto-published by the Cotor desktop app after task completion.
- Task: Ship desktop publish flow
- Agent: codex
- Branch: codex/cotor/ship-flow/codex
- Base: master

## Prompt
```text
Implement the desktop publish flow.
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
        workingDirectory: Path?
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
