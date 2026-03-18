package com.cotor.app

import com.cotor.data.process.ProcessManager
import com.cotor.model.ProcessExecutionException
import com.cotor.model.ProcessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.name

enum class PullRequestReviewVerdict {
    COMMENT,
    APPROVE,
    REQUEST_CHANGES
}

data class GitHubPublishReadiness(
    val ready: Boolean,
    val originUrl: String? = null,
    val error: String? = null
)

data class GitHubPublishEnvironment(
    val ghInstalled: Boolean,
    val ghAuthenticated: Boolean,
    val originConfigured: Boolean,
    val originUrl: String? = null,
    val repositoryPath: String? = null,
    val bootstrapAvailable: Boolean,
    val message: String? = null
)

data class PullRequestMergeResult(
    val number: Int? = null,
    val url: String? = null,
    val state: String? = null,
    val mergeCommitSha: String? = null
)

/**
 * Encapsulates every git-aware filesystem operation used by the desktop app.
 *
 * Keeping this logic in one service makes it easier to harden worktree creation
 * and repository discovery without spreading raw git shelling throughout the app.
 */
class GitWorkspaceService(
    private val processManager: ProcessManager,
    private val stateStore: DesktopStateStore,
    private val logger: Logger
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Normalizes any nested path inside a repository back to the real git root.
     */
    suspend fun resolveRepositoryRoot(candidate: Path): Path {
        val root = gitOutput(candidate, "rev-parse", "--show-toplevel")
        return Path.of(root.trim()).toAbsolutePath().normalize()
    }

    /**
     * Company creation should work for a plain folder picked from Finder.
     * If the folder is not a git repository yet, initialize one in place first.
     */
    suspend fun ensureInitializedRepositoryRoot(candidate: Path, preferredDefaultBranch: String?): Path {
        val normalized = candidate.toAbsolutePath().normalize()
        return runCatching {
            resolveRepositoryRoot(normalized).also { ensureBootstrapCommit(it) }
        }.getOrElse {
            Files.createDirectories(normalized)
            val branch = preferredDefaultBranch?.trim().takeUnless { it.isNullOrBlank() } ?: "master"
            runGit(normalized, "init", "-b", branch)
            ensureBootstrapCommit(normalized)
            normalized
        }
    }

    private suspend fun ensureBootstrapCommit(repositoryRoot: Path) {
        val hasCommit = runGit(repositoryRoot, "rev-parse", "--verify", "HEAD", failOnError = false).isSuccess
        if (hasCommit) {
            return
        }
        runCommand(
            repositoryRoot,
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
            )
        )
    }

    /**
     * Clone into the managed repository area owned by the desktop app, not into the
     * user's current shell directory. That gives the app a stable location to reopen later.
     */
    suspend fun cloneRepository(url: String): Path {
        val target = nextCloneTarget(url)
        target.parent?.createDirectories()
        runGit(
            workingDirectory = null,
            "clone",
            url,
            target.toString()
        )
        return resolveRepositoryRoot(target)
    }

    /**
     * Prefer the remote HEAD reference when available so newly cloned repositories
     * pick the canonical default branch even if the local checkout moves around later.
     */
    suspend fun detectDefaultBranch(repositoryRoot: Path): String {
        val remoteHead = runCatching {
            gitOutput(repositoryRoot, "symbolic-ref", "refs/remotes/origin/HEAD")
                .trim()
                .substringAfterLast("/")
        }.getOrNull()

        if (!remoteHead.isNullOrBlank()) {
            return remoteHead
        }

        val symbolicHead = runCatching {
            gitOutput(repositoryRoot, "symbolic-ref", "--short", "HEAD").trim()
        }.getOrNull()
        if (!symbolicHead.isNullOrBlank() && symbolicHead != "HEAD") {
            return symbolicHead
        }

        val currentHead = gitOutput(repositoryRoot, "rev-parse", "--abbrev-ref", "HEAD").trim()
        if (currentHead.isBlank() || currentHead == "HEAD") {
            throw IllegalStateException("Unable to determine default branch for $repositoryRoot")
        }
        return currentHead
    }

    /**
     * Read the canonical origin URL for repositories that were opened from an
     * existing local checkout instead of cloned through the desktop app.
     */
    suspend fun detectRemoteUrl(repositoryRoot: Path): String? {
        val result = runGit(repositoryRoot, "config", "--get", "remote.origin.url", failOnError = false)
        if (!result.isSuccess) {
            return null
        }
        return result.stdout.trim().ifBlank { null }
    }

    /**
     * Expose a deduplicated branch list for the desktop branch picker.
     *
     * Local refs are preferred, but remote origin branches are folded back into the
     * same namespace so the UI can offer branches that have not been checked out yet.
     */
    suspend fun listBranches(repositoryRoot: Path): List<String> {
        val refs = gitOutput(
            repositoryRoot,
            "for-each-ref",
            "--format=%(refname:short)",
            "refs/heads",
            "refs/remotes/origin"
        )

        return refs.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { ref ->
                when {
                    ref == "origin/HEAD" -> null
                    ref.startsWith("origin/") -> ref.removePrefix("origin/")
                    else -> ref
                }
            }
            .distinct()
            .sorted()
    }

    suspend fun buildChangeSummary(
        runId: String,
        worktreePath: Path,
        branchName: String,
        baseBranch: String
    ): ChangeSummary {
        val patch = gitOutput(worktreePath, "diff", "$baseBranch...HEAD")
        val files = gitOutput(worktreePath, "diff", "--name-only", "$baseBranch...HEAD")
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return ChangeSummary(
            runId = runId,
            branchName = branchName,
            baseBranch = baseBranch,
            patch = patch,
            changedFiles = files
        )
    }

    suspend fun ensureWorktree(
        repositoryRoot: Path,
        taskId: String,
        taskTitle: String,
        agentName: String,
        baseBranch: String
    ): WorktreeBinding {
        ensureBootstrapCommit(repositoryRoot)
        val agentSlug = slugify(agentName).ifBlank { "agent" }
        val taskSlug = slugify(taskTitle).ifBlank { "task" }
        val branchName = "codex/cotor/$taskSlug-${taskId.take(8)}/$agentSlug"
        val worktreePath = repositoryRoot
            .resolve(".cotor")
            .resolve("worktrees")
            .resolve(taskId)
            .resolve(agentSlug)
            .toAbsolutePath()
            .normalize()

        // Reopening a task should not silently create a second isolated checkout.
        // Reuse the same path when the agent already has a bound worktree.
        if (worktreePath.exists()) {
            return WorktreeBinding(branchName = branchName, worktreePath = worktreePath)
        }

        worktreePath.parent?.createDirectories()
        if (hasBranch(repositoryRoot, branchName)) {
            runGit(repositoryRoot, "worktree", "add", worktreePath.toString(), branchName)
        } else {
            runGit(repositoryRoot, "worktree", "add", "-b", branchName, worktreePath.toString(), baseBranch)
        }

        return WorktreeBinding(branchName = branchName, worktreePath = worktreePath)
    }

    /**
     * Publish one completed run by committing local edits, pushing the branch,
     * and creating or reusing a GitHub pull request via `gh`.
     */
    suspend fun publishRun(
        task: AgentTask,
        agentName: String,
        worktreePath: Path,
        branchName: String,
        baseBranch: String
    ): PublishMetadata {
        var commitSha: String? = null
        var pushedBranch: String? = null
        var pullRequest: PullRequestRef? = null

        return try {
            if (hasUncommittedChanges(worktreePath)) {
                runGit(worktreePath, "add", "-A")
                runGit(worktreePath, "commit", "-m", buildCommitMessage(task.title, agentName))
            }

            commitSha = gitOutput(worktreePath, "rev-parse", "HEAD").trim().takeIf { it.isNotBlank() }
            val aheadCount = gitOutput(worktreePath, "rev-list", "--count", "$baseBranch..HEAD")
                .trim()
                .toIntOrNull()
                ?: 0
            if (aheadCount == 0) {
                return PublishMetadata(
                    commitSha = commitSha,
                    error = "No changes to publish from $branchName against $baseBranch"
                )
            }

            val hasRemote = if (hasOriginRemote(worktreePath)) {
                true
            } else {
                ensureGitHubOrigin(worktreePath, baseBranch)
            }
            if (!hasRemote) {
                return PublishMetadata(
                    commitSha = commitSha,
                    error = "No GitHub remote configured; kept local commit only"
                )
            }

            runGit(worktreePath, "push", "--set-upstream", "origin", "HEAD:$branchName")
            pushedBranch = branchName

            pullRequest = findOpenPullRequest(worktreePath, branchName) ?: createPullRequest(
                worktreePath = worktreePath,
                branchName = branchName,
                baseBranch = baseBranch,
                title = buildPullRequestTitle(task.title, agentName),
                body = buildPullRequestBody(task, agentName, branchName, baseBranch)
            )

            PublishMetadata(
                commitSha = commitSha,
                pushedBranch = pushedBranch,
                pullRequestNumber = pullRequest?.number,
                pullRequestUrl = pullRequest?.url,
                pullRequestState = pullRequest?.state
            )
        } catch (t: Throwable) {
            PublishMetadata(
                commitSha = commitSha,
                pushedBranch = pushedBranch,
                pullRequestNumber = pullRequest?.number,
                pullRequestUrl = pullRequest?.url,
                pullRequestState = pullRequest?.state,
                error = buildPublishError(t)
            )
        }
    }

    suspend fun ensureGitHubPublishReady(worktreePath: Path, baseBranch: String): GitHubPublishReadiness {
        if (hasOriginRemote(worktreePath)) {
            return GitHubPublishReadiness(
                ready = true,
                originUrl = originRemoteUrl(worktreePath)
            )
        }

        val authReady = runCommand(
            worktreePath,
            listOf("gh", "auth", "status"),
            failOnError = false
        ).isSuccess
        if (!authReady) {
            return GitHubPublishReadiness(
                ready = false,
                error = "GitHub publishing requires an authenticated gh CLI session."
            )
        }

        val hasRemote = ensureGitHubOrigin(worktreePath, baseBranch)
        return if (hasRemote) {
            GitHubPublishReadiness(
                ready = true,
                originUrl = originRemoteUrl(worktreePath)
            )
        } else {
            GitHubPublishReadiness(
                ready = false,
                error = "GitHub publishing requires an origin remote or repo bootstrap."
            )
        }
    }

    suspend fun inspectGitHubPublishEnvironment(
        repositoryRoot: Path?,
        baseBranch: String?
    ): GitHubPublishEnvironment {
        val normalizedRoot = repositoryRoot?.toAbsolutePath()?.normalize()
        val ghInstalled = runCommand(
            normalizedRoot,
            listOf("gh", "--version"),
            failOnError = false
        ).isSuccess
        val ghAuthenticated = ghInstalled && runCommand(
            normalizedRoot,
            listOf("gh", "auth", "status"),
            failOnError = false
        ).isSuccess
        val originConfigured = normalizedRoot?.let { hasOriginRemote(it) } ?: false
        val originUrl = normalizedRoot?.let { originRemoteUrl(it) }
        val bootstrapAvailable = normalizedRoot != null && baseBranch != null && ghInstalled && ghAuthenticated
        val message = when {
            normalizedRoot == null -> "Open a repository or select a company to inspect GitHub publishing readiness."
            originConfigured -> "Origin remote is configured for this repository."
            !ghInstalled -> "GitHub PR mode requires the gh CLI."
            !ghAuthenticated -> "GitHub PR mode requires an authenticated gh CLI session."
            bootstrapAvailable -> "Cotor can bootstrap an origin remote for this repository."
            else -> "GitHub publishing is not ready for this repository."
        }
        return GitHubPublishEnvironment(
            ghInstalled = ghInstalled,
            ghAuthenticated = ghAuthenticated,
            originConfigured = originConfigured,
            originUrl = originUrl,
            repositoryPath = normalizedRoot?.toString(),
            bootstrapAvailable = bootstrapAvailable,
            message = message
        )
    }

    suspend fun submitPullRequestReview(
        worktreePath: Path,
        pullRequestNumber: Int,
        verdict: PullRequestReviewVerdict,
        body: String
    ): PublishMetadata {
        val command = mutableListOf("gh", "pr", "review", pullRequestNumber.toString())
        when (verdict) {
            PullRequestReviewVerdict.COMMENT -> command += "--comment"
            PullRequestReviewVerdict.APPROVE -> command += "--approve"
            PullRequestReviewVerdict.REQUEST_CHANGES -> command += "--request-changes"
        }
        command += listOf("--body", body)
        runCommand(worktreePath, command)

        val refreshed = viewPullRequest(worktreePath, pullRequestNumber)
        return PublishMetadata(
            pullRequestNumber = refreshed.number,
            pullRequestUrl = refreshed.url,
            pullRequestState = refreshed.state,
            reviewState = refreshed.reviewDecision,
            mergeability = refreshed.mergeStateStatus
        )
    }

    suspend fun mergePullRequest(worktreePath: Path, pullRequestNumber: Int): PullRequestMergeResult {
        runCommand(
            worktreePath,
            listOf("gh", "pr", "merge", pullRequestNumber.toString(), "--merge", "--delete-branch")
        )
        val refreshed = viewPullRequest(worktreePath, pullRequestNumber)
        return PullRequestMergeResult(
            number = refreshed.number,
            url = refreshed.url,
            state = refreshed.state,
            mergeCommitSha = refreshed.mergeCommit?.oid
        )
    }

    private suspend fun hasOriginRemote(repositoryRoot: Path): Boolean {
        val result = runGit(
            repositoryRoot,
            "config",
            "--get",
            "remote.origin.url",
            failOnError = false,
            timeoutMs = 10_000
        )
        if (!result.isSuccess) {
            return false
        }
        return result.stdout.trim().isNotBlank()
    }

    private suspend fun originRemoteUrl(repositoryRoot: Path): String? {
        val result = runGit(
            repositoryRoot,
            "config",
            "--get",
            "remote.origin.url",
            failOnError = false,
            timeoutMs = 10_000
        )
        return result.stdout.trim().takeIf { result.isSuccess && it.isNotBlank() }
    }

    private suspend fun ensureGitHubOrigin(worktreePath: Path, baseBranch: String): Boolean {
        val authReady = runCommand(
            worktreePath,
            listOf("gh", "auth", "status"),
            failOnError = false,
            timeoutMs = 10_000
        ).isSuccess
        if (!authReady) {
            return false
        }

        val repositoryRoot = repositoryCommonRoot(worktreePath)
        val repoName = chooseGitHubRepositoryName(worktreePath, repositoryRoot)
        val createResult = runCommand(
            worktreePath,
            listOf("gh", "repo", "create", repoName, "--private", "--source", ".", "--remote", "origin"),
            failOnError = false,
            timeoutMs = 30_000
        )
        if (!createResult.isSuccess && !hasOriginRemote(worktreePath)) {
            return false
        }

        runGit(
            worktreePath,
            "push",
            "--set-upstream",
            "origin",
            "refs/heads/$baseBranch:refs/heads/$baseBranch",
            failOnError = false,
            timeoutMs = 30_000
        )
        return hasOriginRemote(worktreePath)
    }

    private suspend fun repositoryCommonRoot(worktreePath: Path): Path {
        val commonDirRaw = gitOutput(worktreePath, "rev-parse", "--git-common-dir").trim()
        val commonDir = Path.of(commonDirRaw).let {
            if (it.isAbsolute) it else worktreePath.resolve(it).normalize()
        }
        return commonDir.parent?.toAbsolutePath()?.normalize()
            ?: worktreePath.toAbsolutePath().normalize()
    }

    private suspend fun chooseGitHubRepositoryName(worktreePath: Path, repositoryRoot: Path): String {
        val baseName = slugify(repositoryRoot.fileName?.toString().orEmpty()).ifBlank { "cotor-company" }
        val attempts = buildList {
            add(baseName)
            add("$baseName-${System.currentTimeMillis().toString().takeLast(6)}")
            add("$baseName-${worktreePath.fileName?.toString().orEmpty().take(8)}")
        }.distinct()

        attempts.forEach { candidate ->
            val probe = runCommand(
                worktreePath,
                listOf("gh", "repo", "view", candidate, "--json", "name"),
                failOnError = false,
                timeoutMs = 10_000
            )
            if (!probe.isSuccess) {
                return candidate
            }
        }
        return attempts.last()
    }

    /**
     * Returns a depth-limited file tree rooted inside the worktree.
     *
     * The depth cap prevents the desktop client from recursively walking an entire
     * repository on every refresh while still providing a useful expandable tree.
     */
    suspend fun listFiles(root: Path, relativePath: String? = null): List<FileTreeNode> = withContext(Dispatchers.IO) {
        val target = relativePath
            ?.takeIf { it.isNotBlank() }
            ?.let { root.resolve(it).normalize() }
            ?: root

        if (!target.exists() || !target.startsWith(root)) {
            return@withContext emptyList()
        }

        val depth = if (relativePath.isNullOrBlank()) 3 else 1
        scanTree(root, target, depth)
    }

    suspend fun listPorts(@Suppress("UNUSED_PARAMETER") runId: String): List<PortEntry> = emptyList()

    /**
     * Inspect a running child process for listening TCP ports.
     *
     * `lsof` is used here because it is available on macOS by default and lets us
     * scope the query to a single pid without maintaining our own socket registry.
     */
    suspend fun listPorts(processId: Long?): List<PortEntry> {
        if (processId == null) {
            return emptyList()
        }

        val lsofResult = processManager.executeProcess(
            command = listOf("lsof", "-Pan", "-p", processId.toString(), "-iTCP", "-sTCP:LISTEN"),
            input = null,
            environment = emptyMap(),
            timeout = 10_000,
            workingDirectory = null
        )
        if (!lsofResult.isSuccess && lsofResult.stdout.isBlank()) {
            return emptyList()
        }

        val pattern = Regex("""TCP\s+([^\s]+)\s+\(LISTEN\)""")
        return lsofResult.stdout
            .lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val match = pattern.find(line) ?: return@mapNotNull null
                val endpoint = match.groupValues[1]
                val port = endpoint.substringAfterLast(":").toIntOrNull() ?: return@mapNotNull null
                val host = endpoint.substringBeforeLast(":", missingDelimiterValue = "127.0.0.1")
                    .removePrefix("[")
                    .removeSuffix("]")
                    .let {
                        when (it) {
                            "*", "0.0.0.0", "::", "::1", "" -> "127.0.0.1"
                            else -> it
                        }
                    }
                PortEntry(
                    port = port,
                    url = "http://$host:$port",
                    label = "Port $port"
                )
            }
            .distinctBy { it.port }
            .sortedBy { it.port }
            .toList()
    }

    private suspend fun hasBranch(repositoryRoot: Path, branchName: String): Boolean {
        val result = runGit(repositoryRoot, "show-ref", "--verify", "--quiet", "refs/heads/$branchName", failOnError = false)
        return result.isSuccess
    }

    private suspend fun hasUncommittedChanges(worktreePath: Path): Boolean {
        return gitOutput(worktreePath, "status", "--porcelain").isNotBlank()
    }

    private suspend fun gitOutput(workingDirectory: Path?, vararg args: String): String {
        val result = runGit(workingDirectory, *args)
        return result.stdout
    }

    private suspend fun ghOutput(workingDirectory: Path, vararg args: String): String {
        val result = runCommand(workingDirectory, listOf("gh") + args)
        return result.stdout
    }

    private suspend fun runGit(
        workingDirectory: Path?,
        vararg args: String,
        failOnError: Boolean = true,
        timeoutMs: Long = 120_000
    ): ProcessResult {
        return runCommand(workingDirectory, listOf("git") + args, failOnError, timeoutMs)
    }

    private suspend fun runCommand(
        workingDirectory: Path?,
        command: List<String>,
        failOnError: Boolean = true,
        timeoutMs: Long = 120_000
    ): ProcessResult {
        val result = processManager.executeProcess(
            command = command,
            input = null,
            environment = emptyMap(),
            timeout = timeoutMs,
            workingDirectory = workingDirectory
        )

        if (failOnError && !result.isSuccess) {
            logger.warn("Command failed: ${command.joinToString(" ")}")
            throw ProcessExecutionException(
                message = "${command.first().uppercase()} command failed",
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr
            )
        }

        return result
    }

    private suspend fun findOpenPullRequest(worktreePath: Path, branchName: String): PullRequestRef? {
        val raw = ghOutput(
            worktreePath,
            "pr",
            "list",
            "--head",
            branchName,
            "--state",
            "open",
            "--json",
            "number,url,state,reviewDecision,mergeStateStatus"
        )
        val pullRequests = json.decodeFromString<List<PullRequestRef>>(raw.ifBlank { "[]" })
        return pullRequests.firstOrNull()
    }

    private suspend fun createPullRequest(
        worktreePath: Path,
        branchName: String,
        baseBranch: String,
        title: String,
        body: String
    ): PullRequestRef {
        ghOutput(
            worktreePath,
            "pr",
            "create",
            "--base",
            baseBranch,
            "--head",
            branchName,
            "--title",
            title,
            "--body",
            body
        )
        val created = ghOutput(
            worktreePath,
            "pr",
            "view",
            branchName,
            "--json",
            "number,url,state,reviewDecision,mergeStateStatus"
        )
        return json.decodeFromString<PullRequestRef>(created)
    }

    private suspend fun viewPullRequest(worktreePath: Path, pullRequestNumber: Int): PullRequestRef {
        val raw = ghOutput(
            worktreePath,
            "pr",
            "view",
            pullRequestNumber.toString(),
            "--json",
            "number,url,state,reviewDecision,mergeStateStatus,mergeCommit"
        )
        return json.decodeFromString<PullRequestRef>(raw)
    }

    private fun buildCommitMessage(taskTitle: String, agentName: String): String {
        val headline = taskTitle.trim().ifBlank { "Complete desktop task" }
        return "$headline ($agentName)".take(72)
    }

    private fun buildPullRequestTitle(taskTitle: String, agentName: String): String {
        val headline = taskTitle.trim().ifBlank { "Desktop task update" }
        return "[$agentName] $headline".take(120)
    }

    private fun buildPullRequestBody(task: AgentTask, agentName: String, branchName: String, baseBranch: String): String {
        val promptExcerpt = task.prompt.trim()
            .lineSequence()
            .take(20)
            .joinToString("\n")
            .take(4000)

        return buildString {
            appendLine("## Summary")
            appendLine("- Auto-published by the Cotor desktop app after task completion.")
            appendLine("- Task: ${task.title}")
            appendLine("- Agent: $agentName")
            appendLine("- Branch: $branchName")
            appendLine("- Base: $baseBranch")
            appendLine()
            appendLine("## Prompt")
            appendLine("```text")
            appendLine(promptExcerpt.ifBlank { "<no prompt provided>" })
            appendLine("```")
        }
    }

    private fun buildPublishError(t: Throwable): String {
        return when (t) {
            is ProcessExecutionException -> {
                val stderr = t.stderr.trim()
                val stdout = t.stdout.trim()
                listOf(
                    "Publish failed",
                    stderr.ifBlank { stdout }.ifBlank { t.message ?: "Unknown process error" }
                ).joinToString(": ")
            }
            else -> "Publish failed: ${t.message ?: "Unknown error"}"
        }
    }

    private fun scanTree(root: Path, directory: Path, depthRemaining: Int): List<FileTreeNode> {
        Files.list(directory).use { stream ->
            return stream
                .sorted(compareBy<Path>({ !it.isDirectory() }, { it.fileName.toString().lowercase() }))
                .map { child ->
                    val isDirectory = child.isDirectory()
                    FileTreeNode(
                        name = child.fileName.toString(),
                        relativePath = root.relativize(child).invariantSeparatorsPathString,
                        isDirectory = isDirectory,
                        sizeBytes = if (isDirectory) null else runCatching { Files.size(child) }.getOrNull(),
                        // Only descend while we still have budget left. This keeps the UI
                        // responsive for larger repositories with deep dependency trees.
                        children = if (isDirectory && depthRemaining > 0) {
                            runCatching { scanTree(root, child, depthRemaining - 1) }.getOrElse { emptyList() }
                        } else {
                            emptyList()
                        }
                    )
                }
                .toList()
        }
    }

    private fun nextCloneTarget(url: String): Path {
        val baseName = slugify(
            url.substringAfterLast("/")
                .substringBeforeLast(".git")
                .ifBlank { "repository" }
        )
        val root = stateStore.managedReposRoot()
        root.createDirectories()

        var candidate = root.resolve(baseName)
        var suffix = 2
        while (candidate.exists()) {
            candidate = root.resolve("$baseName-$suffix")
            suffix++
        }
        return candidate
    }

    private fun slugify(value: String): String {
        return value
            .trim()
            .lowercase()
            .replace("[^a-z0-9]+".toRegex(), "-")
            .trim('-')
    }

    @Serializable
    private data class PullRequestRef(
        val number: Int? = null,
        val url: String,
        val state: String? = null,
        val reviewDecision: String? = null,
        val mergeStateStatus: String? = null,
        val mergeCommit: MergeCommitRef? = null
    )

    @Serializable
    private data class MergeCommitRef(
        val oid: String? = null
    )
}
