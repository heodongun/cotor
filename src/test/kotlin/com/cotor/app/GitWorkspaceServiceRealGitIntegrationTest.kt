package com.cotor.app

import com.cotor.data.process.CoroutineProcessManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class GitWorkspaceServiceRealGitIntegrationTest : FunSpec({
    test("ensureInitializedRepositoryRoot bootstraps a plain folder with the requested default branch") {
        val workingDir = Files.createTempDirectory("git-workspace-real-bootstrap")
        val normalizedWorkingDir = workingDir.toAbsolutePath().normalize()
        val service = realGitService()

        val resolved = service.ensureInitializedRepositoryRoot(workingDir, "main")

        resolved.toRealPath() shouldBe normalizedWorkingDir.toRealPath()
        normalizedWorkingDir.resolve(".git").exists() shouldBe true
        git(normalizedWorkingDir, "branch", "--show-current") shouldBe "main"
        git(normalizedWorkingDir, "log", "-1", "--format=%s") shouldBe "Initialize repository"

        val second = service.ensureInitializedRepositoryRoot(workingDir, "main")
        second.toRealPath() shouldBe normalizedWorkingDir.toRealPath()
        git(normalizedWorkingDir, "branch", "--show-current") shouldBe "main"
    }

    test("ensureWorktree creates a real local-only worktree from the base branch") {
        val repositoryRoot = initRepoWithCommit(defaultBranch = "main")
        val service = realGitService()

        val binding = service.ensureWorktree(
            repositoryRoot = repositoryRoot,
            taskId = "task-real-worktree",
            taskTitle = "Validate real git worktree",
            agentName = "codex",
            baseBranch = "main"
        )

        binding.worktreePath.exists() shouldBe true
        binding.branchName shouldContain "codex/cotor/validate-real-git-worktr"
        git(binding.worktreePath, "branch", "--show-current") shouldBe binding.branchName
        git(binding.worktreePath, "merge-base", "HEAD", "main").isNotBlank() shouldBe true

        val second = service.ensureWorktree(
            repositoryRoot = repositoryRoot,
            taskId = "task-real-worktree",
            taskTitle = "Validate real git worktree",
            agentName = "codex",
            baseBranch = "main"
        )

        second.branchName shouldBe binding.branchName
        second.worktreePath shouldBe binding.worktreePath
    }

    test("detectDefaultBranch prefers the local current branch when no origin exists") {
        val repositoryRoot = initRepoWithCommit(defaultBranch = "main")
        val service = realGitService()

        service.detectDefaultBranch(repositoryRoot) shouldBe "main"
    }

    test("detectDefaultBranch prefers origin HEAD over the current local branch") {
        val remoteRoot = initBareRemote(defaultBranch = "main")
        val cloneRoot = cloneRepo(remoteRoot)
        val service = realGitService()

        git(cloneRoot, "checkout", "-b", "feature/test-default-branch")

        service.detectDefaultBranch(cloneRoot) shouldBe "main"
    }
})

private fun realGitService(): GitWorkspaceService {
    val logger = LoggerFactory.getLogger("GitWorkspaceServiceRealGitIntegrationTest")
    return GitWorkspaceService(
        processManager = CoroutineProcessManager(logger),
        stateStore = mockkStateStore(),
        logger = logger
    )
}

private fun mockkStateStore(): DesktopStateStore = DesktopStateStore {
    Files.createTempDirectory("git-workspace-real-state")
}

private fun initRepoWithCommit(defaultBranch: String): Path {
    val repositoryRoot = Files.createTempDirectory("git-workspace-real-repo")
    git(repositoryRoot, "init", "-b", defaultBranch)
    git(repositoryRoot, "config", "user.name", "Cotor")
    git(repositoryRoot, "config", "user.email", "cotor@local")
    Files.writeString(repositoryRoot.resolve("README.md"), "bootstrap\n")
    git(repositoryRoot, "add", "README.md")
    git(repositoryRoot, "commit", "-m", "Initial commit")
    return repositoryRoot
}

private fun initBareRemote(defaultBranch: String): Path {
    val remoteRoot = Files.createTempDirectory("git-workspace-real-remote")
    git(null, "init", "--bare", "-b", defaultBranch, remoteRoot.toString())

    val seedRepo = initRepoWithCommit(defaultBranch)
    git(seedRepo, "remote", "add", "origin", remoteRoot.toString())
    git(seedRepo, "push", "-u", "origin", defaultBranch)
    return remoteRoot
}

private fun cloneRepo(remoteRoot: Path): Path {
    val parent = Files.createTempDirectory("git-workspace-real-clone-parent")
    val cloneRoot = parent.resolve("clone")
    git(parent, "clone", remoteRoot.toString(), cloneRoot.toString())
    return cloneRoot
}

private fun git(workingDirectory: Path?, vararg args: String): String = runBlocking {
    val logger = LoggerFactory.getLogger("GitWorkspaceServiceRealGitIntegrationTest.git")
    val result = CoroutineProcessManager(logger).executeProcess(
        command = listOf("git") + args,
        input = null,
        environment = emptyMap(),
        timeout = 120_000,
        workingDirectory = workingDirectory,
        onStart = null
    )
    if (!result.isSuccess) {
        error("git ${args.joinToString(" ")} failed: ${result.stderr}")
    }
    result.stdout.trim()
}
