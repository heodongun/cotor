package com.cotor.app

/**
 * File overview for GitWorkspaceServiceTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around git workspace service test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.data.process.ProcessManager
import com.cotor.model.ProcessResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Flow
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession

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
                    listOf("git", "config", "--get", "remote.origin.url"),
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
                    listOf("git", "config", "--get", "remote.origin.url"),
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

    test("ensureWorktree creates new execution branches from origin when the remote base is newer") {
        val repositoryRoot = Files.createTempDirectory("git-workspace-origin-base")
        val worktreePath = repositoryRoot.resolve(".cotor").resolve("worktrees").resolve("task-origin").resolve("codex").toString()
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(
                    listOf("git", "rev-parse", "--verify", "HEAD"),
                    ProcessResult(0, "abc1234567890\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "show-ref", "--verify", "--quiet", "refs/heads/codex/cotor/ship-from-origin-task-ori/codex"),
                    ProcessResult(1, "", "", false)
                ),
                FakeProcessManager.Step(
                    listOf("git", "config", "--get", "remote.origin.url"),
                    ProcessResult(0, "https://github.com/heodongun/cotor.git\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "ls-remote", "--heads", "origin", "master"),
                    ProcessResult(0, "abc123\trefs/heads/master\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "fetch", "--no-tags", "origin", "refs/heads/master:refs/remotes/origin/master"),
                    ProcessResult(0, "", "", true)
                ),
                FakeProcessManager.Step(
                    listOf(
                        "git",
                        "worktree",
                        "add",
                        "-b",
                        "codex/cotor/ship-from-origin-task-ori/codex",
                        worktreePath,
                        "refs/remotes/origin/master"
                    ),
                    ProcessResult(0, "", "", true)
                )
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val binding = service.ensureWorktree(
            repositoryRoot = repositoryRoot,
            taskId = "task-origin",
            taskTitle = "Ship from origin",
            agentName = "codex",
            baseBranch = "master"
        )

        binding.branchName shouldBe "codex/cotor/ship-from-origin-task-ori/codex"
        binding.worktreePath.toString() shouldBe worktreePath
        processManager.remainingSteps() shouldBe 0
    }

    test("ensureExistingWorktreeLineage reuses the recorded PR branch and worktree path") {
        val repositoryRoot = Files.createTempDirectory("git-workspace-existing-lineage")
        val worktreePath = repositoryRoot.resolve(".cotor").resolve("worktrees").resolve("existing-pr").resolve("codex")
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(
                    listOf("git", "rev-parse", "--verify", "HEAD"),
                    ProcessResult(0, "abc1234567890\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "show-ref", "--verify", "--quiet", "refs/heads/codex/cotor/existing-pr/codex"),
                    ProcessResult(0, "", "", true)
                ),
                FakeProcessManager.Step(
                    listOf(
                        "git",
                        "worktree",
                        "add",
                        worktreePath.toString(),
                        "codex/cotor/existing-pr/codex"
                    ),
                    ProcessResult(0, "", "", true)
                )
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val binding = service.ensureExistingWorktreeLineage(
            repositoryRoot = repositoryRoot,
            branchName = "codex/cotor/existing-pr/codex",
            worktreePath = worktreePath,
            baseBranch = "master"
        )

        binding.branchName shouldBe "codex/cotor/existing-pr/codex"
        binding.worktreePath shouldBe worktreePath
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

    test("ensureGitHubPublishReady rejects repositories whose local base branch has no shared history with origin") {
        val repositoryRoot = Files.createTempDirectory("git-workspace-publish-readiness")
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(
                    listOf("git", "config", "--get", "remote.origin.url"),
                    ProcessResult(0, "https://github.com/heodongun/cotor.git\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "config", "--get", "remote.origin.url"),
                    ProcessResult(0, "https://github.com/heodongun/cotor.git\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "show-ref", "--verify", "--quiet", "refs/heads/master"),
                    ProcessResult(0, "", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "ls-remote", "--heads", "origin", "master"),
                    ProcessResult(0, "abc123\trefs/heads/master\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "fetch", "--no-tags", "origin", "refs/heads/master:refs/remotes/origin/master"),
                    ProcessResult(0, "", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "merge-base", "master", "refs/remotes/origin/master"),
                    ProcessResult(1, "", "", false)
                ),
                FakeProcessManager.Step(
                    listOf("git", "rev-list", "--count", "master"),
                    ProcessResult(0, "2\n", "", true)
                )
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val readiness = service.ensureGitHubPublishReady(repositoryRoot, "master")

        readiness.ready shouldBe false
        readiness.originUrl shouldBe "https://github.com/heodongun/cotor.git"
        readiness.error shouldContain "no history in common"
        processManager.remainingSteps() shouldBe 0
    }

    test("ensureGitHubPublishReady repairs a bootstrap-only base branch by aligning it to origin") {
        val repositoryRoot = Files.createTempDirectory("git-workspace-bootstrap-repair")
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(
                    listOf("git", "config", "--get", "remote.origin.url"),
                    ProcessResult(0, "https://github.com/heodongun/cotor.git\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "config", "--get", "remote.origin.url"),
                    ProcessResult(0, "https://github.com/heodongun/cotor.git\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "show-ref", "--verify", "--quiet", "refs/heads/master"),
                    ProcessResult(0, "", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "ls-remote", "--heads", "origin", "master"),
                    ProcessResult(0, "abc123\trefs/heads/master\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "fetch", "--no-tags", "origin", "refs/heads/master:refs/remotes/origin/master"),
                    ProcessResult(0, "", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "merge-base", "master", "refs/remotes/origin/master"),
                    ProcessResult(1, "", "", false)
                ),
                FakeProcessManager.Step(
                    listOf("git", "rev-list", "--count", "master"),
                    ProcessResult(0, "1\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "log", "-1", "--format=%s", "master"),
                    ProcessResult(0, "Initialize repository\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "log", "-1", "--format=%ae", "master"),
                    ProcessResult(0, "cotor@local\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "rev-parse", "--git-common-dir"),
                    ProcessResult(0, ".git\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "status", "--porcelain"),
                    ProcessResult(0, "?? .cotor/\n?? cotor.log\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "checkout", "-B", "master", "refs/remotes/origin/master"),
                    ProcessResult(0, "Reset branch 'master'\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "merge-base", "master", "refs/remotes/origin/master"),
                    ProcessResult(0, "abc123\n", "", true)
                )
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val readiness = service.ensureGitHubPublishReady(repositoryRoot, "master")

        readiness.ready shouldBe true
        readiness.originUrl shouldBe "https://github.com/heodongun/cotor.git"
        readiness.error.shouldBeNull()
        processManager.remainingSteps() shouldBe 0
    }

    test("submitPullRequestReview skips self-authored approvals before invoking gh pr review") {
        val repositoryRoot = Files.createTempDirectory("git-workspace-self-approval")
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(
                    listOf("gh", "api", "user", "--jq", ".login"),
                    ProcessResult(0, "heodongun\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "view", "22", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,mergeCommit,author,updatedAt"),
                    ProcessResult(
                        0,
                        """{"number":22,"url":"https://github.com/heodongun/cotor-test/pull/22","state":"OPEN","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"CLEAN","mergeable":"MERGEABLE","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/example/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"}""",
                        "",
                        true
                    )
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "view", "22", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,mergeCommit,author,updatedAt"),
                    ProcessResult(
                        0,
                        """{"number":22,"url":"https://github.com/heodongun/cotor-test/pull/22","state":"OPEN","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"CLEAN","mergeable":"MERGEABLE","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/example/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"}""",
                        "",
                        true
                    )
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "view", "22", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,mergeCommit,author,updatedAt"),
                    ProcessResult(
                        0,
                        """{"number":22,"url":"https://github.com/heodongun/cotor-test/pull/22","state":"OPEN","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"CLEAN","mergeable":"MERGEABLE","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/example/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"}""",
                        "",
                        true
                    )
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "view", "22", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,mergeCommit,author,updatedAt"),
                    ProcessResult(
                        0,
                        """{"number":22,"url":"https://github.com/heodongun/cotor-test/pull/22","state":"OPEN","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"CLEAN","mergeable":"MERGEABLE","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/example/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"}""",
                        "",
                        true
                    )
                )
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val metadata = service.submitPullRequestReview(
            worktreePath = repositoryRoot,
            pullRequestNumber = 22,
            verdict = PullRequestReviewVerdict.APPROVE,
            body = "CEO approved the PR."
        )

        metadata.pullRequestNumber shouldBe 22
        metadata.pullRequestUrl shouldBe "https://github.com/heodongun/cotor-test/pull/22"
        metadata.pullRequestState shouldBe "OPEN"
        metadata.reviewState shouldBe "REVIEW_REQUIRED"
        metadata.mergeability shouldBe "CLEAN"
    }

    test("submitPullRequestReview skips self-authored request-changes reviews and leaves the PR metadata intact") {
        val repositoryRoot = Files.createTempDirectory("git-workspace-self-request-changes")
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(
                    listOf("gh", "api", "user", "--jq", ".login"),
                    ProcessResult(0, "heodongun\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "view", "289", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,mergeCommit,author,updatedAt"),
                    ProcessResult(
                        0,
                        """{"number":289,"url":"https://github.com/heodongun/cotor-test/pull/289","state":"OPEN","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"CLEAN","mergeable":"MERGEABLE","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/example/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"}""",
                        "",
                        true
                    )
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "view", "289", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,mergeCommit,author,updatedAt"),
                    ProcessResult(
                        0,
                        """{"number":289,"url":"https://github.com/heodongun/cotor-test/pull/289","state":"OPEN","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"CLEAN","mergeable":"MERGEABLE","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/example/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"}""",
                        "",
                        true
                    )
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "view", "289", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,mergeCommit,author,updatedAt"),
                    ProcessResult(
                        0,
                        """{"number":289,"url":"https://github.com/heodongun/cotor-test/pull/289","state":"OPEN","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"CLEAN","mergeable":"MERGEABLE","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/example/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"}""",
                        "",
                        true
                    )
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "view", "289", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,mergeCommit,author,updatedAt"),
                    ProcessResult(
                        0,
                        """{"number":289,"url":"https://github.com/heodongun/cotor-test/pull/289","state":"OPEN","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"CLEAN","mergeable":"MERGEABLE","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/example/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"}""",
                        "",
                        true
                    )
                )
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val metadata = service.submitPullRequestReview(
            worktreePath = repositoryRoot,
            pullRequestNumber = 289,
            verdict = PullRequestReviewVerdict.REQUEST_CHANGES,
            body = "QA requested changes."
        )

        metadata.pullRequestNumber shouldBe 289
        metadata.pullRequestUrl shouldBe "https://github.com/heodongun/cotor-test/pull/289"
        metadata.pullRequestState shouldBe "OPEN"
        metadata.reviewState shouldBe "REVIEW_REQUIRED"
        metadata.mergeability shouldBe "CLEAN"
    }

    test("mergePullRequest tolerates already-merged responses and refreshes merged state") {
        val repositoryRoot = Files.createTempDirectory("git-workspace-already-merged")
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(
                    listOf("gh", "pr", "merge", "22", "--merge"),
                    ProcessResult(
                        1,
                        "! Pull request bssm-oss/cotor-test#22 was already merged\n",
                        "failed to delete local branch codex/example/codex used by worktree\n",
                        false
                    )
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "view", "22", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,mergeCommit,author,updatedAt"),
                    ProcessResult(
                        0,
                        """{"number":22,"url":"https://github.com/heodongun/cotor-test/pull/22","state":"MERGED","mergeStateStatus":"CLEAN","mergeable":"MERGEABLE","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/example/codex","mergeCommit":{"oid":"deadbeef"},"author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"}""",
                        "",
                        true
                    )
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "view", "22", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,mergeCommit,author,updatedAt"),
                    ProcessResult(
                        0,
                        """{"number":22,"url":"https://github.com/heodongun/cotor-test/pull/22","state":"MERGED","mergeStateStatus":"CLEAN","mergeable":"MERGEABLE","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/example/codex","mergeCommit":{"oid":"deadbeef"},"author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"}""",
                        "",
                        true
                    )
                )
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val result = service.mergePullRequest(repositoryRoot, 22)

        result.number shouldBe 22
        result.url shouldBe "https://github.com/heodongun/cotor-test/pull/22"
        result.state shouldBe "MERGED"
        result.mergeCommitSha shouldBe "deadbeef"
    }

    test("closePullRequest comments, closes, and refreshes the superseded PR state") {
        val repositoryRoot = Files.createTempDirectory("git-workspace-close-superseded-pr")
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(
                    listOf("gh", "pr", "comment", "123", "--body", "Superseded by newer retry PR #124."),
                    ProcessResult(0, "", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "close", "123"),
                    ProcessResult(0, "✓ Closed pull request\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "view", "123", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,mergeCommit,author,updatedAt"),
                    ProcessResult(
                        0,
                        """{"number":123,"url":"https://github.com/heodongun/cotor-test/pull/123","state":"CLOSED","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"UNKNOWN","mergeable":"UNKNOWN","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/example/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"}""",
                        "",
                        true
                    )
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "view", "123", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,mergeCommit,author,updatedAt"),
                    ProcessResult(
                        0,
                        """{"number":123,"url":"https://github.com/heodongun/cotor-test/pull/123","state":"CLOSED","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"UNKNOWN","mergeable":"UNKNOWN","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/example/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"}""",
                        "",
                        true
                    )
                )
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val metadata = service.closePullRequest(
            worktreePath = repositoryRoot,
            pullRequestNumber = 123,
            comment = "Superseded by newer retry PR #124."
        )

        metadata.pullRequestNumber shouldBe 123
        metadata.pullRequestUrl shouldBe "https://github.com/heodongun/cotor-test/pull/123"
        metadata.pullRequestState shouldBe "CLOSED"
        metadata.reviewState shouldBe "REVIEW_REQUIRED"
        metadata.mergeability shouldBe "UNKNOWN"
    }

    test("closeSupersededManagedPullRequests propagates cancellation instead of swallowing shutdown cleanup") {
        val repositoryRoot = Files.createTempDirectory("git-workspace-close-superseded-cancelled")
        val commands = mutableListOf<List<String>>()
        val processManager = object : ProcessManager {
            override suspend fun executeProcess(
                command: List<String>,
                input: String?,
                environment: Map<String, String>,
                timeout: Long,
                workingDirectory: Path?,
                onStart: ((Long) -> Unit)?
            ): ProcessResult {
                commands += command
                return when (command) {
                    listOf("gh", "api", "user", "--jq", ".login") ->
                        ProcessResult(0, "heodongun\n", "", true)
                    listOf("gh", "pr", "list", "--state", "open", "--limit", "500", "--json", "number,title,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,author,updatedAt") ->
                        ProcessResult(
                            0,
                            """
                            [
                              {"number":123,"title":"Deliver the smallest complete repository change for \"test\"","url":"https://github.com/heodongun/cotor-test/pull/123","state":"OPEN","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"CLEAN","mergeable":"MERGEABLE","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/deliver-the-smallest-complete-repository-change-for-test-aaaaaaaa/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"},
                              {"number":124,"title":"Deliver the smallest complete repository change for \"test\"","url":"https://github.com/heodongun/cotor-test/pull/124","state":"OPEN","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"CLEAN","mergeable":"MERGEABLE","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/deliver-the-smallest-complete-repository-change-for-test-bbbbbbbb/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"}
                            ]
                            """.trimIndent(),
                            "",
                            true
                        )
                    listOf("gh", "pr", "comment", "123", "--body", "Superseded by newer retry https://github.com/heodongun/cotor-test/pull/124. Closing this outdated Cotor-managed PR to keep the review queue aligned with the latest execution branch.") ->
                        throw CancellationException("simulate app shutdown during stale PR cleanup")
                    else -> error("Unexpected command: ${command.joinToString(" ")}")
                }
            }
        }
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        shouldThrow<CancellationException> {
            service.closeSupersededManagedPullRequests(
                worktreePath = repositoryRoot,
                preservePullRequestNumbers = setOf(124)
            )
        }

        commands.first() shouldBe listOf("gh", "api", "user", "--jq", ".login")
        commands[1] shouldBe listOf("gh", "pr", "list", "--state", "open", "--limit", "500", "--json", "number,title,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,author,updatedAt")
        (commands.any { it == listOf("gh", "pr", "comment", "123", "--body", "Superseded by newer retry https://github.com/heodongun/cotor-test/pull/124. Closing this outdated Cotor-managed PR to keep the review queue aligned with the latest execution branch.") }) shouldBe true
    }

    test("closePullRequest tolerates comment failures after the PR was already closed elsewhere") {
        val repositoryRoot = Files.createTempDirectory("git-workspace-close-already-closed-comment")
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(
                    listOf("gh", "pr", "comment", "285", "--body", "Superseded by newer retry PR #306."),
                    ProcessResult(1, "", "pull request is already closed\n", false)
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "close", "285"),
                    ProcessResult(1, "", "pull request is already closed\n", false)
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "view", "285", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,mergeCommit,author,updatedAt"),
                    ProcessResult(
                        0,
                        """{"number":285,"url":"https://github.com/heodongun/cotor-test/pull/285","state":"CLOSED","reviewDecision":"","mergeStateStatus":"DIRTY","mergeable":"CONFLICTING","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/example/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"}""",
                        "",
                        true
                    )
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "view", "285", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,mergeCommit,author,updatedAt"),
                    ProcessResult(
                        0,
                        """{"number":285,"url":"https://github.com/heodongun/cotor-test/pull/285","state":"CLOSED","reviewDecision":"","mergeStateStatus":"DIRTY","mergeable":"CONFLICTING","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/example/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"}""",
                        "",
                        true
                    )
                )
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val metadata = service.closePullRequest(
            worktreePath = repositoryRoot,
            pullRequestNumber = 285,
            comment = "Superseded by newer retry PR #306."
        )

        metadata.pullRequestNumber shouldBe 285
        metadata.pullRequestState shouldBe "CLOSED"
        metadata.mergeability shouldBe "DIRTY"
    }

    test("closeSupersededManagedPullRequests closes older codex-managed PRs in the same lineage and keeps the preserved latest PR") {
        val repositoryRoot = Files.createTempDirectory("git-workspace-reconcile-superseded-prs")
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(
                    listOf("gh", "api", "user", "--jq", ".login"),
                    ProcessResult(0, "heodongun\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "list", "--state", "open", "--limit", "500", "--json", "number,title,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,author,updatedAt"),
                    ProcessResult(
                        0,
                        """
                        [
                          {"number":122,"title":"[codex] Deliver the smallest complete repository change for \"AI demo\"","url":"https://github.com/heodongun/cotor-test/pull/122","state":"OPEN","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"CLEAN","mergeable":"MERGEABLE","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/deliver-the-smallest-complete-repository-change-for-ai-demo-a1b2c3d4/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"},
                          {"number":123,"title":"[codex] Deliver the smallest complete repository change for \"AI demo\"","url":"https://github.com/heodongun/cotor-test/pull/123","state":"OPEN","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"CLEAN","mergeable":"MERGEABLE","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/deliver-the-smallest-complete-repository-change-for-ai-demo-b2c3d4e5/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"},
                          {"number":124,"title":"[codex] Deliver the smallest complete repository change for \"AI demo\"","url":"https://github.com/heodongun/cotor-test/pull/124","state":"OPEN","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"CLEAN","mergeable":"MERGEABLE","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/deliver-the-smallest-complete-repository-change-for-ai-demo-c3d4e5f6/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"},
                          {"number":222,"title":"[codex] Review completed implementation work","url":"https://github.com/heodongun/cotor-test/pull/222","state":"OPEN","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"CLEAN","mergeable":"MERGEABLE","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/review-completed-implementation-work-deadbeef/codex","author":{"login":"someone-else"},"updatedAt":"2026-04-14T00:00:00Z"}
                        ]
                        """.trimIndent(),
                        "",
                        true
                    )
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "comment", "122", "--body", "Superseded by newer retry https://github.com/heodongun/cotor-test/pull/124. Closing this outdated Cotor-managed PR to keep the review queue aligned with the latest execution branch."),
                    ProcessResult(0, "", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "close", "122"),
                    ProcessResult(0, "✓ Closed pull request\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "view", "122", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,mergeCommit,author,updatedAt"),
                    ProcessResult(
                        0,
                        """{"number":122,"url":"https://github.com/heodongun/cotor-test/pull/122","state":"CLOSED","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"UNKNOWN","mergeable":"UNKNOWN","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/deliver-the-smallest-complete-repository-change-for-ai-demo-a1b2c3d4/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"}""",
                        "",
                        true
                    )
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "view", "122", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,mergeCommit,author,updatedAt"),
                    ProcessResult(
                        0,
                        """{"number":122,"url":"https://github.com/heodongun/cotor-test/pull/122","state":"CLOSED","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"UNKNOWN","mergeable":"UNKNOWN","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/deliver-the-smallest-complete-repository-change-for-ai-demo-a1b2c3d4/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"}""",
                        "",
                        true
                    )
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "comment", "123", "--body", "Superseded by newer retry https://github.com/heodongun/cotor-test/pull/124. Closing this outdated Cotor-managed PR to keep the review queue aligned with the latest execution branch."),
                    ProcessResult(0, "", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "close", "123"),
                    ProcessResult(0, "✓ Closed pull request\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "view", "123", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,mergeCommit,author,updatedAt"),
                    ProcessResult(
                        0,
                        """{"number":123,"url":"https://github.com/heodongun/cotor-test/pull/123","state":"CLOSED","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"UNKNOWN","mergeable":"UNKNOWN","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/deliver-the-smallest-complete-repository-change-for-ai-demo-b2c3d4e5/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"}""",
                        "",
                        true
                    )
                ),
                FakeProcessManager.Step(
                    listOf("gh", "pr", "view", "123", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,mergeCommit,author,updatedAt"),
                    ProcessResult(
                        0,
                        """{"number":123,"url":"https://github.com/heodongun/cotor-test/pull/123","state":"CLOSED","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"UNKNOWN","mergeable":"UNKNOWN","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/deliver-the-smallest-complete-repository-change-for-ai-demo-b2c3d4e5/codex","author":{"login":"heodongun"},"updatedAt":"2026-04-14T00:00:00Z"}""",
                        "",
                        true
                    )
                )
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val result = service.closeSupersededManagedPullRequests(
            worktreePath = repositoryRoot,
            preservePullRequestNumbers = setOf(124)
        )

        (122 in result.closedPullRequestNumbers) shouldBe true
        (124 in result.closedPullRequestNumbers) shouldBe false
    }

    test("syncBaseBranchAfterMerge fast-forwards the checked-out clean base branch and ignores Cotor artifacts") {
        val worktree = Files.createTempDirectory("git-workspace-sync-base-fast-forward")
        val repositoryRoot = Files.createDirectories(worktree.resolve(".git").parent ?: worktree)
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(
                    listOf("git", "rev-parse", "--git-common-dir"),
                    ProcessResult(0, ".git\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "config", "--get", "remote.origin.url"),
                    ProcessResult(0, "https://github.com/heodongun/cotor-test.git\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "fetch", "--no-tags", "origin", "refs/heads/master:refs/remotes/origin/master"),
                    ProcessResult(0, "", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "rev-parse", "--abbrev-ref", "HEAD"),
                    ProcessResult(0, "master\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "status", "--porcelain"),
                    ProcessResult(0, "?? .cotor/\n?? cotor.log\n?? cotor.2026-03-25.0.log\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "merge", "--ff-only", "refs/remotes/origin/master"),
                    ProcessResult(0, "Updating d15fdcb..b5d084a\nFast-forward\n", "", true)
                )
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val result = service.syncBaseBranchAfterMerge(worktree, "master")

        result.synced shouldBe true
        result.workingTreeUpdated shouldBe true
        result.skippedReason.shouldBeNull()
        processManager.remainingSteps() shouldBe 0
    }

    test("syncBaseBranchAfterMerge skips fast-forward when the checked-out base branch has real local edits") {
        val worktree = Files.createTempDirectory("git-workspace-sync-base-dirty")
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(
                    listOf("git", "rev-parse", "--git-common-dir"),
                    ProcessResult(0, ".git\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "config", "--get", "remote.origin.url"),
                    ProcessResult(0, "https://github.com/heodongun/cotor-test.git\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "fetch", "--no-tags", "origin", "refs/heads/master:refs/remotes/origin/master"),
                    ProcessResult(0, "", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "rev-parse", "--abbrev-ref", "HEAD"),
                    ProcessResult(0, "master\n", "", true)
                ),
                FakeProcessManager.Step(
                    listOf("git", "status", "--porcelain"),
                    ProcessResult(0, " M README.md\n?? .cotor/\n", "", true)
                )
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val result = service.syncBaseBranchAfterMerge(worktree, "master")

        result.synced shouldBe false
        result.workingTreeUpdated shouldBe false
        result.skippedReason shouldContain "uncommitted changes"
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
                FakeProcessManager.Step(listOf("git", "ls-remote", "--heads", "origin", "master"), ProcessResult(0, "abc123\trefs/heads/master\n", "", true)),
                FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/cotor.git\n", "", true)),
                FakeProcessManager.Step(listOf("git", "ls-remote", "--heads", "origin", "master"), ProcessResult(0, "abc123\trefs/heads/master\n", "", true)),
                FakeProcessManager.Step(listOf("git", "fetch", "--no-tags", "origin", "refs/heads/master:refs/remotes/origin/master"), ProcessResult(0, "", "", true)),
                FakeProcessManager.Step(listOf("git", "rebase", "refs/remotes/origin/master"), ProcessResult(0, "Current branch codex/cotor/ship-flow/codex is up to date.\n", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-parse", "HEAD"), ProcessResult(0, "abc1234567890\n", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-list", "--count", "refs/remotes/origin/master..HEAD"), ProcessResult(0, "1\n", "", true)),
                FakeProcessManager.Step(listOf("git", "push", "--force-with-lease", "--set-upstream", "origin", "HEAD:codex/cotor/ship-flow/codex"), ProcessResult(0, "", "", true)),
                FakeProcessManager.Step(listOf("gh", "pr", "list", "--head", "codex/cotor/ship-flow/codex", "--state", "open", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,updatedAt"), ProcessResult(0, "[]", "", true)),
                FakeProcessManager.Step(listOf("gh", "pr", "create", "--base", "master", "--head", "codex/cotor/ship-flow/codex", "--title", "[codex] Ship desktop publish flow", "--body", expectedPullRequestBody()), ProcessResult(0, "https://github.com/heodongun/cotor/pull/123\n", "", true)),
                FakeProcessManager.Step(listOf("gh", "pr", "view", "codex/cotor/ship-flow/codex", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,updatedAt"), ProcessResult(0, """{"number":123,"url":"https://github.com/heodongun/cotor/pull/123","state":"OPEN","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"CLEAN","mergeable":"MERGEABLE","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/ship-flow/codex","updatedAt":"2026-04-14T00:00:00Z"}""", "", true))
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

    test("publishRun stops before opening a PR when the execution branch no longer rebases onto origin") {
        val worktree = Files.createTempDirectory("git-workspace-service-rebase-conflict")
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(listOf("git", "status", "--porcelain"), ProcessResult(0, " M index.html\n", "", true)),
                FakeProcessManager.Step(listOf("git", "add", "-A"), ProcessResult(0, "", "", true)),
                FakeProcessManager.Step(listOf("git", "commit", "-m", "Retry conflicted publish (codex)"), ProcessResult(0, "[branch abc1234] Retry conflicted publish\n", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-parse", "HEAD"), ProcessResult(0, "abc1234567890\n", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-list", "--count", "master..HEAD"), ProcessResult(0, "1\n", "", true)),
                FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/cotor.git\n", "", true)),
                FakeProcessManager.Step(listOf("git", "ls-remote", "--heads", "origin", "master"), ProcessResult(0, "abc123\trefs/heads/master\n", "", true)),
                FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/cotor.git\n", "", true)),
                FakeProcessManager.Step(listOf("git", "ls-remote", "--heads", "origin", "master"), ProcessResult(0, "abc123\trefs/heads/master\n", "", true)),
                FakeProcessManager.Step(listOf("git", "fetch", "--no-tags", "origin", "refs/heads/master:refs/remotes/origin/master"), ProcessResult(0, "", "", true)),
                FakeProcessManager.Step(listOf("git", "rebase", "refs/remotes/origin/master"), ProcessResult(1, "Auto-merging index.html\nCONFLICT (content): Merge conflict in index.html\n", "", false)),
                FakeProcessManager.Step(listOf("git", "rebase", "--abort"), ProcessResult(0, "", "", true))
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val publish = service.publishRun(
            task = AgentTask(
                id = "task-rebase-conflict",
                workspaceId = "ws-1",
                title = "Retry conflicted publish",
                prompt = "Retry the conflicted publish.",
                agents = listOf("codex"),
                status = DesktopTaskStatus.RUNNING,
                createdAt = 0,
                updatedAt = 0
            ),
            agentName = "codex",
            worktreePath = worktree,
            branchName = "codex/cotor/retry-conflicted-publish/codex",
            baseBranch = "master"
        )

        publish.error shouldContain "no longer rebases cleanly"
        publish.pullRequestNumber.shouldBeNull()
        publish.pushedBranch.shouldBeNull()
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
                FakeProcessManager.Step(listOf("git", "ls-remote", "--heads", "origin", "master"), ProcessResult(0, "abc123\trefs/heads/master\n", "", true)),
                FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/$repoName.git\n", "", true)),
                FakeProcessManager.Step(listOf("git", "ls-remote", "--heads", "origin", "master"), ProcessResult(0, "abc123\trefs/heads/master\n", "", true)),
                FakeProcessManager.Step(listOf("git", "fetch", "--no-tags", "origin", "refs/heads/master:refs/remotes/origin/master"), ProcessResult(0, "", "", true)),
                FakeProcessManager.Step(listOf("git", "rebase", "refs/remotes/origin/master"), ProcessResult(0, "Current branch codex/cotor/auto-publish/codex is up to date.\n", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-parse", "HEAD"), ProcessResult(0, "abc1234567890\n", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-list", "--count", "refs/remotes/origin/master..HEAD"), ProcessResult(0, "1\n", "", true)),
                FakeProcessManager.Step(listOf("git", "push", "--force-with-lease", "--set-upstream", "origin", "HEAD:codex/cotor/auto-publish/codex"), ProcessResult(0, "", "", true)),
                FakeProcessManager.Step(listOf("gh", "pr", "list", "--head", "codex/cotor/auto-publish/codex", "--state", "open", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,updatedAt"), ProcessResult(0, "[]", "", true)),
                FakeProcessManager.Step(listOf("gh", "pr", "create", "--base", "master", "--head", "codex/cotor/auto-publish/codex", "--title", "[codex] Auto publish to GitHub", "--body", expectedPullRequestBody("Auto publish to GitHub", "Ship this change to GitHub.", "codex/cotor/auto-publish/codex")), ProcessResult(0, "https://github.com/heodongun/cotor/pull/124\n", "", true)),
                FakeProcessManager.Step(listOf("gh", "pr", "view", "codex/cotor/auto-publish/codex", "--json", "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,updatedAt"), ProcessResult(0, """{"number":124,"url":"https://github.com/heodongun/cotor/pull/124","state":"OPEN","reviewDecision":"REVIEW_REQUIRED","mergeStateStatus":"CLEAN","mergeable":"MERGEABLE","statusCheckRollup":null,"autoMergeRequest":null,"baseRefName":"master","headRefName":"codex/cotor/auto-publish/codex","updatedAt":"2026-04-14T00:00:00Z"}""", "", true))
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

    test("publishRun returns local publish metadata without pushing when pull requests are disabled") {
        val worktree = Files.createTempDirectory("git-workspace-service-no-pr")
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(listOf("git", "status", "--porcelain"), ProcessResult(0, " M README.md\n", "", true)),
                FakeProcessManager.Step(listOf("git", "add", "-A"), ProcessResult(0, "", "", true)),
                FakeProcessManager.Step(listOf("git", "commit", "-m", "Publish without PR (codex)"), ProcessResult(0, "[branch abc1234] Publish without PR\n", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-parse", "HEAD"), ProcessResult(0, "abc1234567890\n", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-list", "--count", "master..HEAD"), ProcessResult(0, "1\n", "", true)),
                FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/cotor.git\n", "", true)),
                FakeProcessManager.Step(listOf("git", "ls-remote", "--heads", "origin", "master"), ProcessResult(0, "abc123\trefs/heads/master\n", "", true)),
                FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/cotor.git\n", "", true)),
                FakeProcessManager.Step(listOf("git", "ls-remote", "--heads", "origin", "master"), ProcessResult(0, "abc123\trefs/heads/master\n", "", true)),
                FakeProcessManager.Step(listOf("git", "fetch", "--no-tags", "origin", "refs/heads/master:refs/remotes/origin/master"), ProcessResult(0, "", "", true)),
                FakeProcessManager.Step(listOf("git", "rebase", "refs/remotes/origin/master"), ProcessResult(0, "Current branch codex/cotor/publish-without-pr/codex is up to date.\n", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-parse", "HEAD"), ProcessResult(0, "abc1234567890\n", "", true)),
                FakeProcessManager.Step(listOf("git", "rev-list", "--count", "refs/remotes/origin/master..HEAD"), ProcessResult(0, "1\n", "", true))
            )
        )
        val service = GitWorkspaceService(processManager, mockk(relaxed = true), mockk<Logger>(relaxed = true))

        val publish = service.publishRun(
            task = AgentTask(
                id = "task-no-pr",
                workspaceId = "ws-1",
                title = "Publish without PR",
                prompt = "Publish directly without opening a pull request.",
                agents = listOf("codex"),
                status = DesktopTaskStatus.RUNNING,
                createdAt = 0,
                updatedAt = 0
            ),
            agentName = "codex",
            worktreePath = worktree,
            branchName = "codex/cotor/publish-without-pr/codex",
            baseBranch = "master",
            requirePullRequest = false
        )

        publish.commitSha shouldBe "abc1234567890"
        publish.pushedBranch shouldBe "codex/cotor/publish-without-pr/codex"
        publish.pullRequestNumber.shouldBeNull()
        publish.error.shouldBeNull()
        processManager.remainingSteps() shouldBe 0
    }

    test("publishRun uses the direct GitHub API path when HTTP mode is enabled") {
        withGitHubHostsHome {
            val worktree = Files.createTempDirectory("git-workspace-service-http-publish")
            markAsGitWorktree(worktree)
            val processManager = FakeProcessManager(
                listOf(
                    FakeProcessManager.Step(listOf("git", "status", "--porcelain"), ProcessResult(0, " M src/App.kt\n", "", true)),
                    FakeProcessManager.Step(listOf("git", "add", "-A"), ProcessResult(0, "", "", true)),
                    FakeProcessManager.Step(listOf("git", "commit", "-m", "Ship desktop publish flow (codex)"), ProcessResult(0, "[branch abc1234] Ship desktop publish flow\n", "", true)),
                    FakeProcessManager.Step(listOf("git", "rev-parse", "HEAD"), ProcessResult(0, "abc1234567890\n", "", true)),
                    FakeProcessManager.Step(listOf("git", "rev-list", "--count", "master..HEAD"), ProcessResult(0, "1\n", "", true)),
                    FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/cotor.git\n", "", true)),
                    FakeProcessManager.Step(listOf("git", "ls-remote", "--heads", "origin", "master"), ProcessResult(0, "abc123\trefs/heads/master\n", "", true)),
                    FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/cotor.git\n", "", true)),
                    FakeProcessManager.Step(listOf("git", "ls-remote", "--heads", "origin", "master"), ProcessResult(0, "abc123\trefs/heads/master\n", "", true)),
                    FakeProcessManager.Step(listOf("git", "fetch", "--no-tags", "origin", "refs/heads/master:refs/remotes/origin/master"), ProcessResult(0, "", "", true)),
                    FakeProcessManager.Step(listOf("git", "rebase", "refs/remotes/origin/master"), ProcessResult(0, "Current branch codex/cotor/ship-flow/codex is up to date.\n", "", true)),
                    FakeProcessManager.Step(listOf("git", "rev-parse", "HEAD"), ProcessResult(0, "abc1234567890\n", "", true)),
                    FakeProcessManager.Step(listOf("git", "rev-list", "--count", "refs/remotes/origin/master..HEAD"), ProcessResult(0, "1\n", "", true)),
                    FakeProcessManager.Step(listOf("git", "push", "--force-with-lease", "--set-upstream", "origin", "HEAD:codex/cotor/ship-flow/codex"), ProcessResult(0, "", "", true)),
                    FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/cotor.git\n", "", true)),
                    FakeProcessManager.Step(listOf("security", "find-generic-password", "-s", "gh:github.com", "-a", "heodongun", "-w"), ProcessResult(0, "test-token\n", "", true)),
                    FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/cotor.git\n", "", true))
                )
            )
            val httpClient = FakeHttpClient(
                listOf(
                    FakeHttpResponseSpec(200, "[]"),
                    FakeHttpResponseSpec(201, """{"number":123,"html_url":"https://github.com/heodongun/cotor/pull/123","state":"open","mergeable_state":"clean","mergeable":true,"head":{"ref":"codex/cotor/ship-flow/codex"},"base":{"ref":"master"},"updated_at":"2026-04-14T00:00:00Z","user":{"login":"heodongun"}}""")
                )
            )
            val service = GitWorkspaceService(
                processManager = processManager,
                stateStore = mockk(relaxed = true),
                logger = mockk(relaxed = true),
                httpClient = httpClient,
                githubHttpEnabledProvider = { true }
            )

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

            publish.pullRequestNumber shouldBe 123
            publish.pullRequestUrl shouldBe "https://github.com/heodongun/cotor/pull/123"
            processManager.remainingSteps() shouldBe 0
            httpClient.recordedRequests.map { it.method to it.pathAndQuery } shouldBe listOf(
                "GET" to "/repos/heodongun/cotor/pulls?state=open&head=heodongun%3Acodex%2Fcotor%2Fship-flow%2Fcodex",
                "POST" to "/repos/heodongun/cotor/pulls"
            )
            httpClient.recordedRequests[0].authorization shouldBe "Bearer test-token"
            httpClient.recordedRequests[1].body shouldContain "\"title\":\"[codex] Ship desktop publish flow\""
            httpClient.recordedRequests[1].body shouldContain "\"head\":\"codex/cotor/ship-flow/codex\""
            httpClient.recordedRequests[1].body shouldContain "\"base\":\"master\""
        }
    }

    test("submitPullRequestReview uses the direct GitHub API path when HTTP mode is enabled") {
        withGitHubHostsHome {
            val repositoryRoot = Files.createTempDirectory("git-workspace-http-review")
            markAsGitWorktree(repositoryRoot)
            val processManager = FakeProcessManager(
                listOf(
                    FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/cotor-test.git\n", "", true)),
                    FakeProcessManager.Step(listOf("security", "find-generic-password", "-s", "gh:github.com", "-a", "heodongun", "-w"), ProcessResult(0, "test-token\n", "", true)),
                    FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/cotor-test.git\n", "", true)),
                    FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/cotor-test.git\n", "", true)),
                    FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/cotor-test.git\n", "", true))
                )
            )
            val httpClient = FakeHttpClient(
                listOf(
                    FakeHttpResponseSpec(200, """{"number":55,"html_url":"https://github.com/heodongun/cotor-test/pull/55","state":"open","mergeable_state":"clean","mergeable":true,"head":{"ref":"codex/cotor/example/codex"},"base":{"ref":"master"},"updated_at":"2026-04-14T00:00:00Z","user":{"login":"other-user"}}"""),
                    FakeHttpResponseSpec(200, "{}"),
                    FakeHttpResponseSpec(200, """{"number":55,"html_url":"https://github.com/heodongun/cotor-test/pull/55","state":"open","mergeable_state":"clean","mergeable":true,"head":{"ref":"codex/cotor/example/codex"},"base":{"ref":"master"},"updated_at":"2026-04-14T00:00:00Z","user":{"login":"other-user"}}""")
                )
            )
            val service = GitWorkspaceService(
                processManager = processManager,
                stateStore = mockk(relaxed = true),
                logger = mockk(relaxed = true),
                httpClient = httpClient,
                githubHttpEnabledProvider = { true }
            )

            val metadata = service.submitPullRequestReview(
                worktreePath = repositoryRoot,
                pullRequestNumber = 55,
                verdict = PullRequestReviewVerdict.APPROVE,
                body = "CEO approved the PR."
            )

            metadata.pullRequestNumber shouldBe 55
            metadata.pullRequestUrl shouldBe "https://github.com/heodongun/cotor-test/pull/55"
            processManager.remainingSteps() shouldBe 0
            httpClient.recordedRequests.map { it.method to it.pathAndQuery } shouldBe listOf(
                "GET" to "/repos/heodongun/cotor-test/pulls/55",
                "POST" to "/repos/heodongun/cotor-test/pulls/55/reviews",
                "GET" to "/repos/heodongun/cotor-test/pulls/55"
            )
            httpClient.recordedRequests[1].body shouldContain "\"event\":\"APPROVE\""
            httpClient.recordedRequests[1].body shouldContain "\"body\":\"CEO approved the PR.\""
        }
    }

    test("closePullRequest uses the direct GitHub API path when HTTP mode is enabled") {
        withGitHubHostsHome {
            val repositoryRoot = Files.createTempDirectory("git-workspace-http-close")
            markAsGitWorktree(repositoryRoot)
            val processManager = FakeProcessManager(
                listOf(
                    FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/cotor-test.git\n", "", true)),
                    FakeProcessManager.Step(listOf("security", "find-generic-password", "-s", "gh:github.com", "-a", "heodongun", "-w"), ProcessResult(0, "test-token\n", "", true)),
                    FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/cotor-test.git\n", "", true)),
                    FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/cotor-test.git\n", "", true))
                )
            )
            val httpClient = FakeHttpClient(
                listOf(
                    FakeHttpResponseSpec(201, "{}"),
                    FakeHttpResponseSpec(200, "{}"),
                    FakeHttpResponseSpec(200, """{"number":123,"html_url":"https://github.com/heodongun/cotor-test/pull/123","state":"closed","mergeable_state":"unknown","mergeable":false,"head":{"ref":"codex/cotor/example/codex"},"base":{"ref":"master"},"updated_at":"2026-04-14T00:00:00Z","user":{"login":"heodongun"}}""")
                )
            )
            val service = GitWorkspaceService(
                processManager = processManager,
                stateStore = mockk(relaxed = true),
                logger = mockk(relaxed = true),
                httpClient = httpClient,
                githubHttpEnabledProvider = { true }
            )

            val metadata = service.closePullRequest(
                worktreePath = repositoryRoot,
                pullRequestNumber = 123,
                comment = "Superseded by newer retry PR #124."
            )

            metadata.pullRequestState shouldBe "CLOSED"
            processManager.remainingSteps() shouldBe 0
            httpClient.recordedRequests.map { it.method to it.pathAndQuery } shouldBe listOf(
                "POST" to "/repos/heodongun/cotor-test/issues/123/comments",
                "PATCH" to "/repos/heodongun/cotor-test/pulls/123",
                "GET" to "/repos/heodongun/cotor-test/pulls/123"
            )
            httpClient.recordedRequests[0].body shouldContain "Superseded by newer retry PR #124."
            httpClient.recordedRequests[1].body shouldContain "\"state\":\"closed\""
        }
    }

    test("mergePullRequest uses the direct GitHub API path when HTTP mode is enabled") {
        withGitHubHostsHome {
            val repositoryRoot = Files.createTempDirectory("git-workspace-http-merge")
            markAsGitWorktree(repositoryRoot)
            val processManager = FakeProcessManager(
                listOf(
                    FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/cotor-test.git\n", "", true)),
                    FakeProcessManager.Step(listOf("security", "find-generic-password", "-s", "gh:github.com", "-a", "heodongun", "-w"), ProcessResult(0, "test-token\n", "", true)),
                    FakeProcessManager.Step(listOf("git", "config", "--get", "remote.origin.url"), ProcessResult(0, "https://github.com/heodongun/cotor-test.git\n", "", true))
                )
            )
            val httpClient = FakeHttpClient(
                listOf(
                    FakeHttpResponseSpec(200, "{}"),
                    FakeHttpResponseSpec(200, """{"number":22,"html_url":"https://github.com/heodongun/cotor-test/pull/22","state":"merged","merge_commit_sha":"deadbeef","mergeable_state":"clean","mergeable":true,"head":{"ref":"codex/cotor/example/codex"},"base":{"ref":"master"},"updated_at":"2026-04-14T00:00:00Z","user":{"login":"heodongun"}}""")
                )
            )
            val service = GitWorkspaceService(
                processManager = processManager,
                stateStore = mockk(relaxed = true),
                logger = mockk(relaxed = true),
                httpClient = httpClient,
                githubHttpEnabledProvider = { true }
            )

            val result = service.mergePullRequest(repositoryRoot, 22)

            result.number shouldBe 22
            result.state shouldBe "MERGED"
            result.mergeCommitSha shouldBe "deadbeef"
            processManager.remainingSteps() shouldBe 0
            httpClient.recordedRequests.map { it.method to it.pathAndQuery } shouldBe listOf(
                "PUT" to "/repos/heodongun/cotor-test/pulls/22/merge",
                "GET" to "/repos/heodongun/cotor-test/pulls/22"
            )
            httpClient.recordedRequests[0].body shouldContain "\"merge_method\":\"merge\""
        }
    }

    test("listPorts(runId) resolves the stored process id and returns listening ports") {
        val processManager = FakeProcessManager(
            listOf(
                FakeProcessManager.Step(
                    listOf("lsof", "-Pan", "-p", "4242", "-iTCP", "-sTCP:LISTEN"),
                    ProcessResult(
                        exitCode = 0,
                        stdout = "COMMAND PID USER FD TYPE DEVICE SIZE/OFF NODE NAME\ncotor 4242 user 12u IPv4 0x0 0t0 TCP 127.0.0.1:8787 (LISTEN)\n",
                        stderr = "",
                        isSuccess = true
                    )
                )
            )
        )
        val stateStore = mockk<DesktopStateStore>()
        coEvery { stateStore.load() } returns DesktopAppState(
            runs = listOf(
                AgentRun(
                    id = "run-1",
                    taskId = "task-1",
                    workspaceId = "ws-1",
                    repositoryId = "repo-1",
                    agentName = "codex",
                    branchName = "codex/cotor/ports",
                    worktreePath = "/tmp/worktree",
                    status = AgentRunStatus.RUNNING,
                    processId = 4242,
                    createdAt = 1L,
                    updatedAt = 1L
                )
            )
        )
        val service = GitWorkspaceService(processManager, stateStore, mockk<Logger>(relaxed = true))

        val ports = service.listPorts("run-1")

        ports shouldBe listOf(
            PortEntry(
                port = 8787,
                url = "http://127.0.0.1:8787",
                label = "Port 8787"
            )
        )
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

private inline fun <T> withGitHubHostsHome(configuredUser: String = "heodongun", block: () -> T): T {
    val home = Files.createTempDirectory("git-workspace-service-home")
    val hostsDir = home.resolve(".config").resolve("gh")
    Files.createDirectories(hostsDir)
    Files.writeString(
        hostsDir.resolve("hosts.yml"),
        """
        github.com:
          user: $configuredUser
        """.trimIndent() + "\n"
    )
    val previous = System.getProperty("user.home")
    System.setProperty("user.home", home.toString())
    return try {
        block()
    } finally {
        if (previous == null) {
            System.clearProperty("user.home")
        } else {
            System.setProperty("user.home", previous)
        }
    }
}

private fun markAsGitWorktree(path: Path) {
    Files.writeString(path.resolve(".git"), "gitdir: .git/worktrees/test\n")
}

private data class FakeHttpResponseSpec(
    val statusCode: Int,
    val body: String
)

private data class RecordedHttpRequest(
    val method: String,
    val pathAndQuery: String,
    val body: String,
    val authorization: String?
)

private class FakeHttpClient(
    responses: List<FakeHttpResponseSpec>
) : HttpClient() {
    private val responses = responses.toMutableList()
    val recordedRequests = mutableListOf<RecordedHttpRequest>()

    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

    override fun connectTimeout(): Optional<Duration> = Optional.empty()

    override fun followRedirects(): Redirect = Redirect.NEVER

    override fun proxy(): Optional<ProxySelector> = Optional.empty()

    override fun sslContext(): SSLContext? = null

    override fun sslParameters(): SSLParameters = SSLParameters()

    override fun authenticator(): Optional<Authenticator> = Optional.empty()

    override fun version(): Version = Version.HTTP_1_1

    override fun executor(): Optional<Executor> = Optional.empty()

    override fun <T : Any?> send(request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val response = responses.removeFirstOrNull() ?: error("Unexpected HTTP request: ${request.method()} ${request.uri()}")
        recordedRequests += RecordedHttpRequest(
            method = request.method(),
            pathAndQuery = request.uri().rawPath + (request.uri().rawQuery?.let { "?$it" } ?: ""),
            body = request.readBody(),
            authorization = request.headers().firstValue("Authorization").orElse(null)
        )
        @Suppress("UNCHECKED_CAST")
        return FakeHttpResponse(request.uri(), request.method(), response.statusCode, response.body as T)
    }

    override fun <T : Any?> sendAsync(request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler<T>): CompletableFuture<HttpResponse<T>> {
        return CompletableFuture.completedFuture(send(request, responseBodyHandler))
    }

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>
    ): CompletableFuture<HttpResponse<T>> {
        return CompletableFuture.completedFuture(send(request, responseBodyHandler))
    }
}

private data class FakeHttpResponse<T>(
    private val uri: URI,
    private val method: String,
    private val statusCode: Int,
    private val body: T
) : HttpResponse<T> {
    override fun statusCode(): Int = statusCode

    override fun request(): HttpRequest = HttpRequest.newBuilder(uri).method(method, HttpRequest.BodyPublishers.noBody()).build()

    override fun previousResponse(): Optional<HttpResponse<T>> = Optional.empty()

    override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

    override fun body(): T = body

    override fun sslSession(): Optional<SSLSession> = Optional.empty()

    override fun uri(): URI = uri

    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
}

private fun HttpRequest.readBody(): String {
    val publisher = bodyPublisher().orElse(null) ?: return ""
    val completed = CompletableFuture<String>()
    val buffers = mutableListOf<ByteArray>()
    publisher.subscribe(object : Flow.Subscriber<ByteBuffer> {
        override fun onSubscribe(subscription: Flow.Subscription) {
            subscription.request(Long.MAX_VALUE)
        }

        override fun onNext(item: ByteBuffer) {
            val copy = ByteArray(item.remaining())
            item.get(copy)
            buffers += copy
        }

        override fun onError(throwable: Throwable) {
            completed.completeExceptionally(throwable)
        }

        override fun onComplete() {
            completed.complete(buffers.joinToString(separator = "") { bytes -> bytes.toString(Charsets.UTF_8) })
        }
    })
    return completed.join()
}

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
        System.err.println("FakeProcessManager command: ${command.joinToString(" ")}")
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
