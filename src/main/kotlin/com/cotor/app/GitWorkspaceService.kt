package com.cotor.app

import com.cotor.data.process.ProcessManager
import com.cotor.model.ProcessExecutionException
import com.cotor.model.ProcessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.name

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
    /**
     * Normalizes any nested path inside a repository back to the real git root.
     */
    suspend fun resolveRepositoryRoot(candidate: Path): Path {
        val root = gitOutput(candidate, "rev-parse", "--show-toplevel")
        return Path.of(root.trim()).toAbsolutePath().normalize()
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

        val currentHead = gitOutput(repositoryRoot, "rev-parse", "--abbrev-ref", "HEAD").trim()
        if (currentHead.isBlank() || currentHead == "HEAD") {
            throw IllegalStateException("Unable to determine default branch for $repositoryRoot")
        }
        return currentHead
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
        val agentSlug = slugify(agentName).ifBlank { "agent" }
        val branchName = "codex/cotor/${slugify(taskTitle).ifBlank { taskId.take(8) }}/$agentSlug"
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

    private suspend fun gitOutput(workingDirectory: Path?, vararg args: String): String {
        val result = runGit(workingDirectory, *args)
        return result.stdout
    }

    private suspend fun runGit(
        workingDirectory: Path?,
        vararg args: String,
        failOnError: Boolean = true
    ): ProcessResult {
        val command = listOf("git") + args
        val result = processManager.executeProcess(
            command = command,
            input = null,
            environment = emptyMap(),
            timeout = 120_000,
            workingDirectory = workingDirectory
        )

        if (failOnError && !result.isSuccess) {
            logger.warn("Git command failed: ${command.joinToString(" ")}")
            throw ProcessExecutionException(
                message = "Git command failed",
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr
            )
        }

        return result
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
}
