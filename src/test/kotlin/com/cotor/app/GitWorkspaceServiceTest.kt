package com.cotor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.slf4j.Logger
import java.nio.file.Files

class GitWorkspaceServiceTest : FunSpec({
    test("publishRun commits pushes and creates a pull request when changes exist") {
        val appHome = Files.createTempDirectory("desktop-state")
        val worktreePath = Files.createTempDirectory("publish-worktree")
        val branchName = "codex/cotor/auto-publish/codex"
        var prListCount = 0
        val processManager = FakeProcessManager { command, _ ->
            when {
                command == listOf("git", "status", "--porcelain") -> ok(" M src/main/kotlin/com/cotor/app/DesktopAppService.kt\n")
                command == listOf("git", "add", "-A") -> ok()
                command == listOf("git", "diff", "--cached", "--name-only") -> ok("src/main/kotlin/com/cotor/app/DesktopAppService.kt\n")
                command.take(3) == listOf("git", "commit", "-m") -> ok("[$branchName 1234567] commit\n")
                command == listOf("git", "rev-parse", "HEAD") -> ok("1234567890abcdef\n")
                command == listOf("git", "push", "-u", "origin", branchName) -> ok("branch set up\n")
                command == listOf("gh", "pr", "list", "--head", branchName, "--state", "open", "--json", "number,url") -> {
                    prListCount += 1
                    if (prListCount == 1) {
                        ok("[]")
                    } else {
                        ok("""[{"number":140,"url":"https://github.com/heodongun/cotor/pull/140"}]""")
                    }
                }
                command.take(4) == listOf("gh", "pr", "create", "--base") -> ok("https://github.com/heodongun/cotor/pull/140\n")
                else -> error("Unhandled command: ${command.joinToString(" ")}")
            }
        }
        val service = GitWorkspaceService(
            processManager = processManager,
            stateStore = DesktopStateStore { appHome },
            logger = mockk<Logger>(relaxed = true)
        )

        val publishInfo = suspendAndReturn {
            service.publishRun(
                worktreePath = worktreePath,
                branchName = branchName,
                baseBranch = "master",
                taskTitle = "Auto publish workflow",
                agentName = "codex"
            )
        }

        publishInfo.status shouldBe AgentRunPublishStatus.PR_CREATED
        publishInfo.remoteBranch shouldBe branchName
        publishInfo.commitSha shouldBe "1234567890abcdef"
        publishInfo.pullRequestUrl shouldBe "https://github.com/heodongun/cotor/pull/140"
        publishInfo.pullRequestNumber shouldBe 140
        publishInfo.summary shouldBe "Created pull request #140"
    }

    test("publishRun skips publishing when there is no diff to publish") {
        val appHome = Files.createTempDirectory("desktop-state")
        val worktreePath = Files.createTempDirectory("publish-worktree")
        val branchName = "codex/cotor/auto-publish/codex"
        val processManager = FakeProcessManager { command, _ ->
            when (command) {
                listOf("git", "status", "--porcelain") -> ok("")
                else -> error("Unhandled command: ${command.joinToString(" ")}")
            }
        }
        val service = GitWorkspaceService(
            processManager = processManager,
            stateStore = DesktopStateStore { appHome },
            logger = mockk<Logger>(relaxed = true)
        )

        val publishInfo = suspendAndReturn {
            service.publishRun(
                worktreePath = worktreePath,
                branchName = branchName,
                baseBranch = "master",
                taskTitle = "Auto publish workflow",
                agentName = "codex"
            )
        }

        publishInfo.status shouldBe AgentRunPublishStatus.SKIPPED
        publishInfo.remoteBranch shouldBe branchName
        publishInfo.summary shouldBe "No changes to publish"
    }

    test("publishRun reports a failure when git push fails") {
        val appHome = Files.createTempDirectory("desktop-state")
        val worktreePath = Files.createTempDirectory("publish-worktree")
        val branchName = "codex/cotor/auto-publish/codex"
        val processManager = FakeProcessManager { command, _ ->
            when {
                command == listOf("git", "status", "--porcelain") -> ok(" M src/main/kotlin/com/cotor/app/DesktopAppService.kt\n")
                command == listOf("git", "add", "-A") -> ok()
                command == listOf("git", "diff", "--cached", "--name-only") -> ok("src/main/kotlin/com/cotor/app/DesktopAppService.kt\n")
                command.take(3) == listOf("git", "commit", "-m") -> ok("[$branchName 1234567] commit\n")
                command == listOf("git", "rev-parse", "HEAD") -> ok("1234567890abcdef\n")
                command == listOf("git", "push", "-u", "origin", branchName) -> fail("remote rejected the update")
                else -> error("Unhandled command: ${command.joinToString(" ")}")
            }
        }
        val service = GitWorkspaceService(
            processManager = processManager,
            stateStore = DesktopStateStore { appHome },
            logger = mockk<Logger>(relaxed = true)
        )

        val publishInfo = suspendAndReturn {
            service.publishRun(
                worktreePath = worktreePath,
                branchName = branchName,
                baseBranch = "master",
                taskTitle = "Auto publish workflow",
                agentName = "codex"
            )
        }

        publishInfo.status shouldBe AgentRunPublishStatus.FAILED
        publishInfo.remoteBranch shouldBe branchName
        publishInfo.commitSha shouldBe "1234567890abcdef"
        publishInfo.summary shouldBe "remote rejected the update"
    }
})
