package com.cotor.app

/**
 * File overview for PullRequestReviewVerdict.
 *
 * This file belongs to the app layer for the desktop shell and localhost app-server surface.
 * It groups declarations around git workspace service so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.data.process.ProcessManager
import com.cotor.model.ProcessExecutionException
import com.cotor.model.ProcessResult
import com.cotor.runtime.actions.ActionEvidence
import com.cotor.runtime.actions.ActionExecutionService
import com.cotor.runtime.actions.ActionKind
import com.cotor.runtime.actions.ActionRequest
import com.cotor.runtime.actions.ActionScope
import com.cotor.runtime.actions.ActionSubject
import com.cotor.runtime.durable.DurableRuntimeService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.Logger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
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

data class BaseBranchSyncResult(
    val synced: Boolean = false,
    val workingTreeUpdated: Boolean = false,
    val skippedReason: String? = null
)

data class ManagedPullRequestCleanupResult(
    val closedPullRequestNumbers: List<Int> = emptyList()
)

private data class ResolvedBaseReference(
    val startPoint: String,
    val comparisonRef: String
)

private data class BranchRestackResult(
    val comparisonRef: String,
    val error: String? = null
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
    private val logger: Logger,
    private val durableRuntimeService: DurableRuntimeService = DurableRuntimeService(),
    private val actionExecutionService: ActionExecutionService =
        ActionExecutionService(durableRuntimeService = durableRuntimeService, logger = logger)
) {
    constructor(
        processManager: ProcessManager,
        stateStore: DesktopStateStore,
        logger: Logger
    ) : this(processManager, stateStore, logger, DurableRuntimeService())

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient.newBuilder().build()

    @Volatile private var cachedGitHubLogin: String? = null
    @Volatile private var cachedGitHubToken: String? = null

    private fun githubHttpEnabled(): Boolean {
        val flag = System.getenv("COTOR_GITHUB_HTTP")?.trim()?.lowercase()
        return flag == "1" || flag == "true"
    }

    private suspend fun githubApiPreferred(worktreePath: Path): Boolean {
        if (!githubHttpEnabled()) return false
        val repo = githubRepositoryRef(worktreePath) ?: return false
        val token = githubAuthToken(worktreePath) ?: return false
        if (token.isBlank()) return false
        return repo.owner.isNotBlank() && repo.name.isNotBlank()
    }

    companion object {
        private const val BOOTSTRAP_COMMIT_MESSAGE = "Initialize repository"
        private const val BOOTSTRAP_COMMIT_EMAIL = "cotor@local"
    }

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
            val branch = preferredDefaultBranch?.trim().takeUnless { it.isNullOrBlank() } ?: "main"
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
                BOOTSTRAP_COMMIT_MESSAGE
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
        return actionExecutionService.run(
            request = ActionRequest(
                kind = ActionKind.GIT_WORKTREE,
                label = "git.ensureWorktree:$taskId:$agentName",
                scope = ActionScope.GLOBAL,
                subject = ActionSubject(taskId = taskId, agentName = agentName),
                replaySafe = true,
                approvalRequiredOnReplay = false,
                metadata = mapOf(
                    "repositoryRoot" to repositoryRoot.toString(),
                    "baseBranch" to baseBranch
                )
            ),
            onSuccess = { binding ->
                ActionEvidence(branchName = binding.branchName)
            }
        ) {
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

            if (worktreePath.exists()) {
                WorktreeBinding(branchName = branchName, worktreePath = worktreePath)
            } else {
                worktreePath.parent?.createDirectories()
                if (hasBranch(repositoryRoot, branchName)) {
                    runGit(repositoryRoot, "worktree", "add", worktreePath.toString(), branchName)
                } else {
                    val baseReference = resolveLatestBaseReference(repositoryRoot, baseBranch)
                    runGit(
                        repositoryRoot,
                        "worktree",
                        "add",
                        "-b",
                        branchName,
                        worktreePath.toString(),
                        baseReference.startPoint
                    )
                }
                WorktreeBinding(branchName = branchName, worktreePath = worktreePath)
            }
        }
    }

    suspend fun ensureExistingWorktreeLineage(
        repositoryRoot: Path,
        branchName: String,
        worktreePath: Path,
        baseBranch: String
    ): WorktreeBinding {
        ensureBootstrapCommit(repositoryRoot)
        val normalizedPath = worktreePath.toAbsolutePath().normalize()
        if (normalizedPath.exists()) {
            return WorktreeBinding(branchName = branchName, worktreePath = normalizedPath)
        }

        normalizedPath.parent?.createDirectories()
        if (hasBranch(repositoryRoot, branchName)) {
            runGit(repositoryRoot, "worktree", "add", normalizedPath.toString(), branchName)
        } else {
            val baseReference = resolveLatestBaseReference(repositoryRoot, baseBranch)
            runGit(
                repositoryRoot,
                "worktree",
                "add",
                "-b",
                branchName,
                normalizedPath.toString(),
                baseReference.startPoint
            )
        }

        return WorktreeBinding(branchName = branchName, worktreePath = normalizedPath)
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
        baseBranch: String,
        requirePullRequest: Boolean = true
    ): PublishMetadata {
        var commitSha: String? = null
        var pushedBranch: String? = null
        var pullRequest: PullRequestRef? = null
        var comparisonBaseRef = baseBranch

        return actionExecutionService.run(
            request = ActionRequest(
                kind = ActionKind.GIT_PUBLISH,
                label = "git.publish:$branchName",
                scope = ActionScope.GLOBAL,
                subject = ActionSubject(taskId = task.id, agentName = agentName),
                replaySafe = false,
                approvalRequiredOnReplay = true,
                metadata = mapOf(
                    "taskId" to task.id,
                    "agentName" to agentName,
                    "branchName" to branchName,
                    "baseBranch" to baseBranch
                )
            ),
            onSuccess = { metadata ->
                ActionEvidence(
                    branchName = metadata.pushedBranch ?: branchName,
                    pullRequestNumber = metadata.pullRequestNumber,
                    pullRequestUrl = metadata.pullRequestUrl,
                    checkSummary = metadata.checksSummary
                )
            },
            onFailure = {
                ActionEvidence(branchName = branchName)
            }
        ) {
            try {
                if (hasUncommittedChanges(worktreePath)) {
                    runGit(worktreePath, "add", "-A")
                    runGit(worktreePath, "commit", "-m", buildCommitMessage(task.title, agentName))
                }

                commitSha = gitOutput(worktreePath, "rev-parse", "HEAD").trim().takeIf { it.isNotBlank() }
                val localAheadCount = gitOutput(worktreePath, "rev-list", "--count", "$baseBranch..HEAD")
                    .trim()
                    .toIntOrNull()
                    ?: 0
                if (localAheadCount == 0) {
                    return@run PublishMetadata(
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
                    return@run PublishMetadata(
                        commitSha = commitSha,
                        error = "No GitHub remote configured; kept local commit only"
                    )
                }

                val remoteBaseBranchExists = runGit(
                    worktreePath,
                    "ls-remote",
                    "--heads",
                    "origin",
                    baseBranch,
                    failOnError = false,
                    timeoutMs = 15_000
                ).stdout.trim().isNotBlank()
                if (!remoteBaseBranchExists) {
                    val repoRoot = repositoryCommonRoot(worktreePath)
                    runGit(
                        repoRoot,
                        "push",
                        "origin",
                        "refs/heads/$baseBranch:refs/heads/$baseBranch",
                        failOnError = false,
                        timeoutMs = 30_000
                    )
                }

                val restack = restackBranchOntoLatestBase(
                    worktreePath = worktreePath,
                    branchName = branchName,
                    baseBranch = baseBranch
                )
                if (restack.error != null) {
                    return@run PublishMetadata(
                        commitSha = commitSha,
                        error = restack.error
                    )
                }
                comparisonBaseRef = restack.comparisonRef
                commitSha = gitOutput(worktreePath, "rev-parse", "HEAD").trim().takeIf { it.isNotBlank() }

                val aheadCount = gitOutput(worktreePath, "rev-list", "--count", "$comparisonBaseRef..HEAD")
                    .trim()
                    .toIntOrNull()
                    ?: 0
                if (aheadCount == 0) {
                    return@run PublishMetadata(
                        commitSha = commitSha,
                        error = "No changes to publish from $branchName against $baseBranch"
                    )
                }

                if (!requirePullRequest) {
                    return@run PublishMetadata(
                        commitSha = commitSha,
                        pushedBranch = branchName,
                        pullRequestNumber = null,
                        pullRequestUrl = null,
                        pullRequestState = null,
                        reviewState = null,
                        checksSummary = null,
                        mergeability = null,
                        lastSyncTime = System.currentTimeMillis(),
                        error = null
                    )
                }

                runGit(worktreePath, "push", "--force-with-lease", "--set-upstream", "origin", "HEAD:$branchName")
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
                    pullRequestState = pullRequest?.state,
                    reviewState = pullRequest?.reviewDecision,
                    checksSummary = summarizeStatusChecks(pullRequest?.statusCheckRollup),
                    mergeability = pullRequest?.mergeStateStatus,
                    lastSyncTime = System.currentTimeMillis()
                )
            } catch (t: Throwable) {
                PublishMetadata(
                    commitSha = commitSha,
                    pushedBranch = pushedBranch,
                    pullRequestNumber = pullRequest?.number,
                    pullRequestUrl = pullRequest?.url,
                    pullRequestState = pullRequest?.state,
                    reviewState = pullRequest?.reviewDecision,
                    checksSummary = summarizeStatusChecks(pullRequest?.statusCheckRollup),
                    mergeability = pullRequest?.mergeStateStatus,
                    error = buildPublishError(t)
                )
            }
        }
    }

    suspend fun ensureGitHubPublishReady(worktreePath: Path, baseBranch: String): GitHubPublishReadiness {
        if (hasOriginRemote(worktreePath)) {
            val originUrl = originRemoteUrl(worktreePath)
            val sharedHistoryError = ensureSharedHistoryWithRemoteBase(worktreePath, baseBranch, originUrl)
            if (sharedHistoryError != null) {
                return GitHubPublishReadiness(
                    ready = false,
                    originUrl = originUrl,
                    error = sharedHistoryError
                )
            }
            return GitHubPublishReadiness(
                ready = true,
                originUrl = originUrl
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
            val originUrl = originRemoteUrl(worktreePath)
            val sharedHistoryError = ensureSharedHistoryWithRemoteBase(worktreePath, baseBranch, originUrl)
            if (sharedHistoryError != null) {
                return GitHubPublishReadiness(
                    ready = false,
                    originUrl = originUrl,
                    error = sharedHistoryError
                )
            }
            GitHubPublishReadiness(
                ready = true,
                originUrl = originUrl
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
        return actionExecutionService.run(
            request = ActionRequest(
                kind = ActionKind.GITHUB_REVIEW,
                label = "github.review:$pullRequestNumber:$verdict",
                scope = ActionScope.GLOBAL,
                replaySafe = false,
                approvalRequiredOnReplay = true,
                metadata = mapOf("pullRequestNumber" to pullRequestNumber.toString(), "verdict" to verdict.name)
            ),
            onSuccess = { metadata ->
                ActionEvidence(
                    pullRequestNumber = metadata.pullRequestNumber,
                    pullRequestUrl = metadata.pullRequestUrl,
                    checkSummary = metadata.checksSummary
                )
            }
        ) {
            val currentLogin = currentGitHubLogin(worktreePath)
            val currentPullRequest = viewPullRequest(worktreePath, pullRequestNumber)
            val selfAuthoredReview =
                verdict != PullRequestReviewVerdict.COMMENT &&
                    !currentLogin.isNullOrBlank() &&
                    currentPullRequest.author?.login?.equals(currentLogin, ignoreCase = true) == true
            if (selfAuthoredReview) {
                logger.info("Skipping GitHub ${verdict.name.lowercase()} review for self-authored PR #$pullRequestNumber because GitHub blocks self-review.")
                return@run PublishMetadata(
                    pullRequestNumber = currentPullRequest.number,
                    pullRequestUrl = currentPullRequest.url,
                    pullRequestState = currentPullRequest.state,
                    reviewState = currentPullRequest.reviewDecision,
                    checksSummary = summarizeStatusChecks(currentPullRequest.statusCheckRollup),
                    mergeability = currentPullRequest.mergeStateStatus,
                    lastSyncTime = System.currentTimeMillis()
                )
            }
            val repo = if (githubHttpEnabled()) runCatching { githubRepositoryRef(worktreePath) }.getOrNull() else null
            val token = if (githubHttpEnabled() && repo != null) runCatching { githubAuthToken(worktreePath) }.getOrNull() else null
            if (repo != null && token != null) {
                githubApiJson(
                    worktreePath = worktreePath,
                    method = "POST",
                    path = "/repos/${repo.owner}/${repo.name}/pulls/$pullRequestNumber/reviews",
                    body = buildMap {
                        put("body", body)
                        put(
                            "event",
                            when (verdict) {
                                PullRequestReviewVerdict.COMMENT -> "COMMENT"
                                PullRequestReviewVerdict.APPROVE -> "APPROVE"
                                PullRequestReviewVerdict.REQUEST_CHANGES -> "REQUEST_CHANGES"
                            }
                        )
                    },
                    token = token
                )
            } else {
                val command = mutableListOf("gh", "pr", "review", pullRequestNumber.toString())
                when (verdict) {
                    PullRequestReviewVerdict.COMMENT -> command += "--comment"
                    PullRequestReviewVerdict.APPROVE -> command += "--approve"
                    PullRequestReviewVerdict.REQUEST_CHANGES -> command += "--request-changes"
                }
                command += listOf("--body", body)
                try {
                    runCommand(worktreePath, command)
                } catch (error: ProcessExecutionException) {
                    if (verdict != PullRequestReviewVerdict.COMMENT && isSelfReviewBlocked(error)) {
                        logger.info("Skipping GitHub ${verdict.name.lowercase()} review for self-authored PR #$pullRequestNumber because GitHub blocks self-review.")
                    } else {
                        throw error
                    }
                }
            }

            val refreshed = viewPullRequest(worktreePath, pullRequestNumber)
            PublishMetadata(
                pullRequestNumber = refreshed.number,
                pullRequestUrl = refreshed.url,
                pullRequestState = refreshed.state,
                reviewState = refreshed.reviewDecision,
                checksSummary = summarizeStatusChecks(refreshed.statusCheckRollup),
                mergeability = refreshed.mergeStateStatus,
                lastSyncTime = System.currentTimeMillis()
            )
        }
    }

    suspend fun commentOnPullRequest(
        worktreePath: Path,
        pullRequestNumber: Int,
        body: String
    ) {
        actionExecutionService.run(
            request = ActionRequest(
                kind = ActionKind.GITHUB_COMMENT,
                label = "github.comment:$pullRequestNumber",
                scope = ActionScope.GLOBAL,
                replaySafe = false,
                approvalRequiredOnReplay = true,
                metadata = mapOf("pullRequestNumber" to pullRequestNumber.toString())
            )
        ) {
            val repo = if (githubHttpEnabled()) runCatching { githubRepositoryRef(worktreePath) }.getOrNull() else null
            val token = if (githubHttpEnabled() && repo != null) runCatching { githubAuthToken(worktreePath) }.getOrNull() else null
            if (repo != null && token != null) {
                githubApiJson(
                    worktreePath = worktreePath,
                    method = "POST",
                    path = "/repos/${repo.owner}/${repo.name}/issues/$pullRequestNumber/comments",
                    body = mapOf("body" to body),
                    token = token
                )
            } else {
                runCommand(
                    worktreePath,
                    listOf("gh", "pr", "comment", pullRequestNumber.toString(), "--body", body)
                )
            }
        }
    }

    suspend fun closePullRequest(
        worktreePath: Path,
        pullRequestNumber: Int,
        comment: String? = null
    ): PublishMetadata {
        return actionExecutionService.run(
            request = ActionRequest(
                kind = ActionKind.GITHUB_COMMENT,
                label = "github.close:$pullRequestNumber",
                scope = ActionScope.GLOBAL,
                replaySafe = false,
                approvalRequiredOnReplay = true,
                metadata = mapOf("pullRequestNumber" to pullRequestNumber.toString())
            ),
            onSuccess = { metadata ->
                ActionEvidence(
                    pullRequestNumber = metadata.pullRequestNumber,
                    pullRequestUrl = metadata.pullRequestUrl,
                    checkSummary = metadata.checksSummary
                )
            }
        ) {
            if (!comment.isNullOrBlank()) {
                try {
                    commentOnPullRequest(
                        worktreePath = worktreePath,
                        pullRequestNumber = pullRequestNumber,
                        body = comment
                    )
                } catch (error: ProcessExecutionException) {
                    if (isAlreadyClosed(error) || isAlreadyMerged(error)) {
                        logger.info("Skipping comment on PR #$pullRequestNumber because it was already terminal before Cotor finished cleanup.")
                    } else {
                        throw error
                    }
                }
            }

            try {
                val repo = if (githubHttpEnabled()) runCatching { githubRepositoryRef(worktreePath) }.getOrNull() else null
                val token = if (githubHttpEnabled() && repo != null) runCatching { githubAuthToken(worktreePath) }.getOrNull() else null
                if (repo != null && token != null) {
                    githubApiJson(
                        worktreePath = worktreePath,
                        method = "PATCH",
                        path = "/repos/${repo.owner}/${repo.name}/pulls/$pullRequestNumber",
                        body = mapOf("state" to "closed"),
                        token = token
                    )
                } else {
                    runCommand(
                        worktreePath,
                        listOf("gh", "pr", "close", pullRequestNumber.toString())
                    )
                }
            } catch (error: ProcessExecutionException) {
                if (isAlreadyClosed(error) || isAlreadyMerged(error)) {
                    logger.info("PR #$pullRequestNumber was already closed before Cotor refreshed local state.")
                } else {
                    throw error
                }
            }

            val refreshed = viewPullRequest(worktreePath, pullRequestNumber)
            PublishMetadata(
                pullRequestNumber = refreshed.number,
                pullRequestUrl = refreshed.url,
                pullRequestState = refreshed.state,
                reviewState = refreshed.reviewDecision,
                checksSummary = summarizeStatusChecks(refreshed.statusCheckRollup),
                mergeability = refreshed.mergeStateStatus,
                lastSyncTime = System.currentTimeMillis()
            )
        }
    }

    suspend fun closeSupersededManagedPullRequests(
        worktreePath: Path,
        preservePullRequestNumbers: Set<Int> = emptySet()
    ): ManagedPullRequestCleanupResult {
        val currentLogin = currentGitHubLogin(worktreePath)
        val openPullRequests = listOpenPullRequests(worktreePath)
        if (openPullRequests.size <= 1) {
            return ManagedPullRequestCleanupResult()
        }

        val closed = mutableListOf<Int>()
        openPullRequests
            .groupBy(::managedPullRequestLineageKey)
            .filterKeys { it != null }
            .values
            .forEach { lineageGroup ->
                val managedGroup = lineageGroup.filter { pullRequest ->
                    isManagedCodexPullRequest(
                        pullRequest = pullRequest,
                        currentLogin = currentLogin
                    )
                }
                if (managedGroup.size <= 1) {
                    return@forEach
                }

                val preservedInGroup = managedGroup
                    .map { it.number }
                    .filterNotNull()
                    .filter { it in preservePullRequestNumbers }
                val keptPullRequest = when {
                    preservedInGroup.isNotEmpty() ->
                        managedGroup
                            .filter { it.number in preservedInGroup }
                            .maxByOrNull { it.number ?: Int.MIN_VALUE }
                    else -> managedGroup.maxByOrNull { it.number ?: Int.MIN_VALUE }
                } ?: return@forEach
                val replacementRef =
                    keptPullRequest.url
                        ?: keptPullRequest.number?.let { "#$it" }
                        ?: "the latest retry"

                managedGroup
                    .filter { candidate ->
                        candidate.number != null &&
                            candidate.number != keptPullRequest.number
                    }
                    .sortedBy { it.number ?: Int.MIN_VALUE }
                    .forEach { superseded ->
                        val pullRequestNumber = superseded.number ?: return@forEach
                        val metadata = runCatching {
                            closePullRequest(
                                worktreePath = worktreePath,
                                pullRequestNumber = pullRequestNumber,
                                comment = "Superseded by newer retry $replacementRef. Closing this outdated Cotor-managed PR to keep the review queue aligned with the latest execution branch."
                            )
                        }.getOrElse { error ->
                            if (error is CancellationException) {
                                throw error
                            }
                            logger.warn("Could not close superseded Cotor-managed PR #$pullRequestNumber", error)
                            return@forEach
                        }
                        if (metadata.pullRequestState.equals("CLOSED", ignoreCase = true) || metadata.pullRequestState.equals("MERGED", ignoreCase = true)) {
                            closed += pullRequestNumber
                        }
                    }
            }

        return ManagedPullRequestCleanupResult(
            closedPullRequestNumbers = closed.distinct().sorted()
        )
    }

    suspend fun refreshPullRequestMetadata(
        worktreePath: Path,
        pullRequestNumber: Int
    ): PublishMetadata {
        return actionExecutionService.run(
            request = ActionRequest(
                kind = ActionKind.HTTP_REQUEST,
                label = "github.refresh:$pullRequestNumber",
                scope = ActionScope.GLOBAL,
                replaySafe = true,
                approvalRequiredOnReplay = false,
                networkTarget = "github.com",
                metadata = mapOf("pullRequestNumber" to pullRequestNumber.toString())
            ),
            onSuccess = { metadata ->
                ActionEvidence(
                    pullRequestNumber = metadata.pullRequestNumber,
                    pullRequestUrl = metadata.pullRequestUrl,
                    checkSummary = metadata.checksSummary
                )
            }
        ) {
            val refreshed = viewPullRequest(worktreePath, pullRequestNumber)
            PublishMetadata(
                pullRequestNumber = refreshed.number,
                pullRequestUrl = refreshed.url,
                pullRequestState = refreshed.state,
                reviewState = refreshed.reviewDecision,
                checksSummary = summarizeStatusChecks(refreshed.statusCheckRollup),
                mergeability = refreshed.mergeStateStatus,
                lastSyncTime = System.currentTimeMillis()
            )
        }
    }

    suspend fun mergePullRequest(
        worktreePath: Path,
        pullRequestNumber: Int,
        preApprovedReviewFlow: Boolean = false
    ): PullRequestMergeResult {
        return actionExecutionService.run(
            request = ActionRequest(
                kind = ActionKind.GITHUB_MERGE,
                label = "github.merge:$pullRequestNumber",
                scope = ActionScope.GLOBAL,
                replaySafe = false,
                approvalRequiredOnReplay = true,
                metadata = buildMap {
                    put("pullRequestNumber", pullRequestNumber.toString())
                    if (preApprovedReviewFlow) {
                        put("preApprovedReviewFlow", "true")
                    }
                }
            ),
            onSuccess = { result ->
                ActionEvidence(
                    pullRequestNumber = result.number,
                    pullRequestUrl = result.url
                )
            }
        ) {
            try {
                val repo = if (githubHttpEnabled()) runCatching { githubRepositoryRef(worktreePath) }.getOrNull() else null
                val token = if (githubHttpEnabled() && repo != null) runCatching { githubAuthToken(worktreePath) }.getOrNull() else null
                if (repo != null && token != null) {
                    githubApiJson(
                        worktreePath = worktreePath,
                        method = "PUT",
                        path = "/repos/${repo.owner}/${repo.name}/pulls/$pullRequestNumber/merge",
                        body = mapOf("merge_method" to "merge"),
                        token = token
                    )
                } else {
                    runCommand(
                        worktreePath,
                        listOf("gh", "pr", "merge", pullRequestNumber.toString(), "--merge")
                    )
                }
            } catch (error: ProcessExecutionException) {
                if (isAlreadyMerged(error)) {
                    logger.info("PR #$pullRequestNumber was already merged before Cotor refreshed local state.")
                } else {
                    throw error
                }
            }
            val refreshed = viewPullRequest(worktreePath, pullRequestNumber)
            PullRequestMergeResult(
                number = refreshed.number,
                url = refreshed.url,
                state = refreshed.state,
                mergeCommitSha = refreshed.mergeCommit?.oid
            )
        }
    }

    /**
     * After GitHub merges a PR, keep the repository root's base branch aligned so the
     * user immediately sees merged changes in the folder they opened in Cotor.
     *
     * The sync is deliberately conservative:
     * - if the root checkout is already on the base branch, only fast-forward it when
     *   there are no user edits beyond Cotor's own local artifacts
     * - if another branch is checked out, update the base branch ref only and leave the
     *   current working tree untouched
     */
    suspend fun syncBaseBranchAfterMerge(worktreePath: Path, baseBranch: String): BaseBranchSyncResult {
        val normalizedBaseBranch = baseBranch.trim()
        if (normalizedBaseBranch.isBlank()) {
            return BaseBranchSyncResult(skippedReason = "No base branch configured for post-merge sync.")
        }

        val repositoryRoot = repositoryCommonRoot(worktreePath)
        if (!hasOriginRemote(repositoryRoot)) {
            return BaseBranchSyncResult(skippedReason = "No origin remote configured for post-merge sync.")
        }

        val remoteTrackingRef = "refs/remotes/origin/$normalizedBaseBranch"
        val fetchResult = runGit(
            repositoryRoot,
            "fetch",
            "--no-tags",
            "origin",
            "refs/heads/$normalizedBaseBranch:$remoteTrackingRef",
            failOnError = false,
            timeoutMs = 30_000
        )
        if (!fetchResult.isSuccess) {
            logger.warn("Could not fetch origin/$normalizedBaseBranch after merge")
            return BaseBranchSyncResult(skippedReason = "Could not fetch origin/$normalizedBaseBranch after merge.")
        }

        val currentBranch = runGit(
            repositoryRoot,
            "rev-parse",
            "--abbrev-ref",
            "HEAD",
            failOnError = false,
            timeoutMs = 10_000
        ).stdout.trim()

        if (currentBranch == normalizedBaseBranch) {
            if (!hasOnlyBenignCotorArtifacts(repositoryRoot)) {
                return BaseBranchSyncResult(
                    skippedReason = "Skipped post-merge sync for $normalizedBaseBranch because the repository root has uncommitted changes."
                )
            }
            val fastForwardResult = runGit(
                repositoryRoot,
                "merge",
                "--ff-only",
                remoteTrackingRef,
                failOnError = false,
                timeoutMs = 30_000
            )
            if (!fastForwardResult.isSuccess) {
                logger.warn("Could not fast-forward $normalizedBaseBranch to $remoteTrackingRef after merge")
                return BaseBranchSyncResult(skippedReason = "Could not fast-forward local $normalizedBaseBranch after merge.")
            }
            return BaseBranchSyncResult(synced = true, workingTreeUpdated = true)
        }

        val localBaseBranchExists = runGit(
            repositoryRoot,
            "show-ref",
            "--verify",
            "--quiet",
            "refs/heads/$normalizedBaseBranch",
            failOnError = false,
            timeoutMs = 10_000
        ).isSuccess
        val updateRefResult = if (localBaseBranchExists) {
            runGit(
                repositoryRoot,
                "branch",
                "-f",
                normalizedBaseBranch,
                remoteTrackingRef,
                failOnError = false,
                timeoutMs = 10_000
            )
        } else {
            runGit(
                repositoryRoot,
                "branch",
                normalizedBaseBranch,
                remoteTrackingRef,
                failOnError = false,
                timeoutMs = 10_000
            )
        }
        if (!updateRefResult.isSuccess) {
            logger.warn("Could not update local $normalizedBaseBranch ref to $remoteTrackingRef after merge")
            return BaseBranchSyncResult(skippedReason = "Could not update local $normalizedBaseBranch ref after merge.")
        }

        return BaseBranchSyncResult(synced = true, workingTreeUpdated = false)
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

    private suspend fun resolveLatestBaseReference(
        repositoryRoot: Path,
        baseBranch: String
    ): ResolvedBaseReference {
        val normalizedBaseBranch = baseBranch.trim()
        if (normalizedBaseBranch.isBlank()) {
            return ResolvedBaseReference(
                startPoint = baseBranch,
                comparisonRef = baseBranch
            )
        }
        if (!hasOriginRemote(repositoryRoot)) {
            return ResolvedBaseReference(
                startPoint = normalizedBaseBranch,
                comparisonRef = normalizedBaseBranch
            )
        }

        val remoteBaseBranchExists = runGit(
            repositoryRoot,
            "ls-remote",
            "--heads",
            "origin",
            normalizedBaseBranch,
            failOnError = false,
            timeoutMs = 15_000
        ).stdout.trim().isNotBlank()
        if (!remoteBaseBranchExists) {
            return ResolvedBaseReference(
                startPoint = normalizedBaseBranch,
                comparisonRef = normalizedBaseBranch
            )
        }

        val remoteTrackingRef = "refs/remotes/origin/$normalizedBaseBranch"
        val fetchResult = runGit(
            repositoryRoot,
            "fetch",
            "--no-tags",
            "origin",
            "refs/heads/$normalizedBaseBranch:$remoteTrackingRef",
            failOnError = false,
            timeoutMs = 30_000
        )
        if (!fetchResult.isSuccess) {
            logger.warn("Could not fetch origin/$normalizedBaseBranch while resolving execution base; falling back to local $normalizedBaseBranch.")
            return ResolvedBaseReference(
                startPoint = normalizedBaseBranch,
                comparisonRef = normalizedBaseBranch
            )
        }

        return ResolvedBaseReference(
            startPoint = remoteTrackingRef,
            comparisonRef = remoteTrackingRef
        )
    }

    private suspend fun restackBranchOntoLatestBase(
        worktreePath: Path,
        branchName: String,
        baseBranch: String
    ): BranchRestackResult {
        val baseReference = resolveLatestBaseReference(worktreePath, baseBranch)
        val rebaseResult = runGit(
            worktreePath,
            "rebase",
            baseReference.startPoint,
            failOnError = false,
            timeoutMs = 120_000
        )
        if (rebaseResult.isSuccess) {
            return BranchRestackResult(comparisonRef = baseReference.comparisonRef)
        }

        runGit(
            worktreePath,
            "rebase",
            "--abort",
            failOnError = false,
            timeoutMs = 30_000
        )

        val combinedOutput = listOf(rebaseResult.stdout.trim(), rebaseResult.stderr.trim())
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val message = if (looksLikeRebaseConflict(combinedOutput)) {
            "Publish failed: execution branch $branchName no longer rebases cleanly onto ${baseReference.startPoint}. Re-run from the latest base or resolve the content conflicts first."
        } else {
            listOf(
                "Publish failed: could not restack $branchName onto ${baseReference.startPoint}",
                combinedOutput.ifBlank { "Unknown rebase error" }
            ).joinToString(": ")
        }
        return BranchRestackResult(
            comparisonRef = baseReference.comparisonRef,
            error = message
        )
    }

    private suspend fun ensureSharedHistoryWithRemoteBase(
        worktreePath: Path,
        baseBranch: String,
        originUrl: String?
    ): String? {
        val localBaseBranchExists = runGit(
            worktreePath,
            "show-ref",
            "--verify",
            "--quiet",
            "refs/heads/$baseBranch",
            failOnError = false,
            timeoutMs = 10_000
        ).isSuccess
        if (!localBaseBranchExists) {
            return "GitHub publishing requires a local $baseBranch branch before opening pull requests."
        }

        val remoteBaseBranchExists = runGit(
            worktreePath,
            "ls-remote",
            "--heads",
            "origin",
            baseBranch,
            failOnError = false,
            timeoutMs = 15_000
        ).stdout.trim().isNotBlank()
        if (!remoteBaseBranchExists) {
            return null
        }

        val remoteTrackingRef = "refs/remotes/origin/$baseBranch"
        val fetchResult = runGit(
            worktreePath,
            "fetch",
            "--no-tags",
            "origin",
            "refs/heads/$baseBranch:$remoteTrackingRef",
            failOnError = false,
            timeoutMs = 30_000
        )
        if (!fetchResult.isSuccess) {
            logger.warn("Could not fetch origin/$baseBranch while checking GitHub publish readiness")
            return null
        }

        val mergeBaseResult = runGit(
            worktreePath,
            "merge-base",
            baseBranch,
            remoteTrackingRef,
            failOnError = false,
            timeoutMs = 10_000
        )
        if (mergeBaseResult.isSuccess) {
            return null
        }

        val repairedBootstrapBase = repairBootstrapBaseBranchFromRemote(
            worktreePath = worktreePath,
            baseBranch = baseBranch,
            remoteTrackingRef = remoteTrackingRef
        )
        if (repairedBootstrapBase) {
            val repairedMergeBase = runGit(
                worktreePath,
                "merge-base",
                baseBranch,
                remoteTrackingRef,
                failOnError = false,
                timeoutMs = 10_000
            )
            if (repairedMergeBase.isSuccess) {
                return null
            }
        }

        val remoteLabel = originUrl ?: "origin"
        return "GitHub publishing cannot open PRs against $remoteLabel because local $baseBranch was initialized independently and has no history in common with origin/$baseBranch."
    }

    private suspend fun repairBootstrapBaseBranchFromRemote(
        worktreePath: Path,
        baseBranch: String,
        remoteTrackingRef: String
    ): Boolean {
        val bootstrapCommitCount = runGit(
            worktreePath,
            "rev-list",
            "--count",
            baseBranch,
            failOnError = false,
            timeoutMs = 10_000
        ).stdout.trim().toIntOrNull() ?: return false
        if (bootstrapCommitCount != 1) {
            return false
        }

        val bootstrapSubject = runGit(
            worktreePath,
            "log",
            "-1",
            "--format=%s",
            baseBranch,
            failOnError = false,
            timeoutMs = 10_000
        ).stdout.trim()
        if (bootstrapSubject != BOOTSTRAP_COMMIT_MESSAGE) {
            return false
        }

        val bootstrapEmail = runGit(
            worktreePath,
            "log",
            "-1",
            "--format=%ae",
            baseBranch,
            failOnError = false,
            timeoutMs = 10_000
        ).stdout.trim()
        if (bootstrapEmail != BOOTSTRAP_COMMIT_EMAIL) {
            return false
        }

        val repositoryRoot = repositoryCommonRoot(worktreePath)
        if (!hasOnlyBenignCotorArtifacts(repositoryRoot)) {
            return false
        }

        val checkoutResult = runGit(
            repositoryRoot,
            "checkout",
            "-B",
            baseBranch,
            remoteTrackingRef,
            failOnError = false,
            timeoutMs = 30_000
        )
        if (!checkoutResult.isSuccess) {
            logger.warn("Could not align bootstrap-only $baseBranch with $remoteTrackingRef")
            return false
        }

        logger.info("Aligned bootstrap-only $baseBranch with $remoteTrackingRef at {}", repositoryRoot)
        return true
    }

    private suspend fun hasOnlyBenignCotorArtifacts(repositoryRoot: Path): Boolean {
        val statusResult = runGit(
            repositoryRoot,
            "status",
            "--porcelain",
            failOnError = false,
            timeoutMs = 10_000
        )
        if (!statusResult.isSuccess) {
            return false
        }

        return statusResult.stdout
            .lines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .all(::isBenignCotorArtifact)
    }

    private fun isBenignCotorArtifact(statusLine: String): Boolean {
        if (statusLine.length < 4) {
            return false
        }
        val status = statusLine.take(2)
        val path = statusLine.drop(3).trim()
        if (status != "??") {
            return false
        }
        return path == ".cotor" ||
            path.startsWith(".cotor/") ||
            path == "cotor.log" ||
            path.matches(Regex("""cotor\.\d{4}-\d{2}-\d{2}\.\d+\.log""")) ||
            path == ".DS_Store"
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

    private suspend fun currentGitHubLogin(worktreePath: Path): String? {
        cachedGitHubLogin?.let { return it }
        val login = if (githubApiPreferred(worktreePath)) {
            githubHostsConfiguredUser()
                ?: githubAuthToken(worktreePath)?.let { token ->
                    runCatching {
                        val raw = githubApiJson(
                            worktreePath = worktreePath,
                            path = "/user",
                            token = token
                        )
                        raw.jsonObject["login"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                }
        } else {
            runCatching {
                ghOutput(worktreePath, "api", "user", "--jq", ".login")
                    .trim()
                    .takeIf { it.isNotBlank() }
            }.getOrNull()
        }
        if (!login.isNullOrBlank()) {
            cachedGitHubLogin = login
        }
        return login
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
        if (githubHttpEnabled()) {
            val repo = githubRepositoryRef(worktreePath)
            val token = githubAuthToken(worktreePath)
            if (repo != null && token != null) {
                val encodedHead = URLEncoder.encode("${repo.owner}:$branchName", StandardCharsets.UTF_8)
                val raw = githubApiJson(
                    worktreePath = worktreePath,
                    path = "/repos/${repo.owner}/${repo.name}/pulls?state=open&head=$encodedHead",
                    token = token
                )
                return raw.jsonArray.map { pullRequestRefFromGitHubApi(it.jsonObject) }.firstOrNull()
            }
        }
        val raw = ghOutput(
            worktreePath,
            "pr",
            "list",
            "--head",
            branchName,
            "--state",
            "open",
            "--json",
            "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,updatedAt"
        )
        val pullRequests = json.decodeFromString<List<PullRequestRef>>(raw.ifBlank { "[]" })
        return pullRequests.firstOrNull()
    }

    private suspend fun listOpenPullRequests(worktreePath: Path): List<PullRequestRef> {
        if (githubHttpEnabled()) {
            val repo = githubRepositoryRef(worktreePath)
            val token = githubAuthToken(worktreePath)
            if (repo != null && token != null) {
                val raw = githubApiJson(
                    worktreePath = worktreePath,
                    path = "/repos/${repo.owner}/${repo.name}/pulls?state=open&per_page=100",
                    token = token
                )
                return raw.jsonArray.map { pullRequestRefFromGitHubApi(it.jsonObject) }
            }
        }
        val raw = ghOutput(
            worktreePath,
            "pr",
            "list",
            "--state",
            "open",
            "--limit",
            "500",
            "--json",
            "number,title,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,author,updatedAt"
        )
        return json.decodeFromString(raw.ifBlank { "[]" })
    }

    private suspend fun createPullRequest(
        worktreePath: Path,
        branchName: String,
        baseBranch: String,
        title: String,
        body: String
    ): PullRequestRef {
        if (githubHttpEnabled()) {
            val repo = githubRepositoryRef(worktreePath)
            val token = githubAuthToken(worktreePath)
            if (repo != null && token != null) {
                val created = githubApiJson(
                    worktreePath = worktreePath,
                    method = "POST",
                    path = "/repos/${repo.owner}/${repo.name}/pulls",
                    body = buildMap {
                        put("title", title)
                        put("head", branchName)
                        put("base", baseBranch)
                        put("body", body)
                    },
                    token = token
                )
                return pullRequestRefFromGitHubApi(created.jsonObject)
            }
        }
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
            "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,updatedAt"
        )
        return json.decodeFromString<PullRequestRef>(created)
    }

    private suspend fun viewPullRequest(worktreePath: Path, pullRequestNumber: Int): PullRequestRef {
        if (githubHttpEnabled()) {
            val repo = githubRepositoryRef(worktreePath)
            val token = githubAuthToken(worktreePath)
            if (repo != null && token != null) {
                val raw = githubApiJson(
                    worktreePath = worktreePath,
                    path = "/repos/${repo.owner}/${repo.name}/pulls/$pullRequestNumber",
                    token = token
                )
                return pullRequestRefFromGitHubApi(raw.jsonObject)
            }
        }
        val raw = ghOutput(
            worktreePath,
            "pr",
            "view",
            pullRequestNumber.toString(),
            "--json",
            "number,url,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup,autoMergeRequest,baseRefName,headRefName,mergeCommit,author,updatedAt"
        )
        return json.decodeFromString<PullRequestRef>(raw)
    }

    private suspend fun githubApiJson(
        worktreePath: Path,
        path: String,
        method: String = "GET",
        body: Map<String, String>? = null,
        token: String
    ): JsonElement {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com$path"))
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", "2022-11-28")

        val request = when (method.uppercase()) {
            "GET" -> requestBuilder.GET().build()
            else -> requestBuilder.method(
                method.uppercase(),
                HttpRequest.BodyPublishers.ofString(
                    buildJsonObject {
                        (body ?: emptyMap()).forEach { (key, value) -> put(key, value) }
                    }.toString()
                )
            ).header("Content-Type", "application/json").build()
        }

        val response = withContext(Dispatchers.IO) {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }
        if (response.statusCode() !in 200..299) {
            throw ProcessExecutionException(
                message = "GitHub API request failed",
                exitCode = response.statusCode(),
                stdout = response.body(),
                stderr = response.body()
            )
        }
        return json.parseToJsonElement(response.body())
    }

    private suspend fun githubAuthToken(worktreePath: Path): String? {
        cachedGitHubToken?.let { return it }
        val fromEnv = System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }
            ?: System.getenv("GH_TOKEN")?.takeIf { it.isNotBlank() }
        if (fromEnv != null) {
            cachedGitHubToken = fromEnv
            return fromEnv
        }
        val configuredUser = githubHostsConfiguredUser()
        if (!configuredUser.isNullOrBlank()) {
            val fromKeychain = runCatching {
                runCommand(
                    workingDirectory = worktreePath,
                    command = listOf("security", "find-generic-password", "-s", "gh:github.com", "-a", configuredUser, "-w"),
                    failOnError = false,
                    timeoutMs = 10_000
                ).stdout.trim().takeIf { it.isNotBlank() }
            }.getOrNull()
            if (fromKeychain != null) {
                cachedGitHubToken = fromKeychain
                return fromKeychain
            }
        }
        return runCatching {
            ghOutput(worktreePath, "auth", "token").trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun githubHostsConfiguredUser(): String? {
        val hostsFile = Path.of(System.getProperty("user.home"), ".config", "gh", "hosts.yml")
        if (!Files.exists(hostsFile)) return null
        return runCatching {
            Files.readAllLines(hostsFile)
                .firstOrNull { it.trim().startsWith("user:") }
                ?.substringAfter(":")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private suspend fun githubRepositoryRef(worktreePath: Path): GitHubRepositoryRef? {
        val hasGitDir = Files.exists(worktreePath.resolve(".git"))
        val hasGitFile = Files.exists(worktreePath.resolve(".git"))
        if (!hasGitDir && !hasGitFile) return null
        val originUrl = runCatching { originRemoteUrl(worktreePath) }.getOrNull() ?: return null
        val https = Regex("https://github\\.com/([^/]+)/([^/.]+)(?:\\.git)?$")
        val ssh = Regex("git@github\\.com:([^/]+)/([^/.]+)(?:\\.git)?$")
        val match = https.matchEntire(originUrl) ?: ssh.matchEntire(originUrl)
        return match?.let { GitHubRepositoryRef(owner = it.groupValues[1], name = it.groupValues[2]) }
    }

    private fun pullRequestRefFromGitHubApi(raw: JsonObject): PullRequestRef {
        val head = raw["head"]?.jsonObject
        val base = raw["base"]?.jsonObject
        val user = raw["user"]?.jsonObject
        val mergeCommitSha = raw["merge_commit_sha"]?.jsonPrimitive?.contentOrNull
        return PullRequestRef(
            number = raw["number"]?.jsonPrimitive?.content?.toIntOrNull(),
            title = raw["title"]?.jsonPrimitive?.contentOrNull,
            url = raw["html_url"]?.jsonPrimitive?.contentOrNull ?: "",
            state = raw["state"]?.jsonPrimitive?.contentOrNull?.uppercase(),
            reviewDecision = null,
            mergeStateStatus = raw["mergeable_state"]?.jsonPrimitive?.contentOrNull?.uppercase(),
            mergeable = raw["mergeable"]?.jsonPrimitive?.contentOrNull,
            statusCheckRollup = null,
            autoMergeRequest = raw["auto_merge"] ?: raw["autoMergeRequest"],
            baseRefName = base?.get("ref")?.jsonPrimitive?.contentOrNull,
            headRefName = head?.get("ref")?.jsonPrimitive?.contentOrNull,
            updatedAt = raw["updated_at"]?.jsonPrimitive?.contentOrNull,
            mergeCommit = mergeCommitSha?.let { MergeCommitRef(oid = it) },
            author = PullRequestAuthorRef(login = user?.get("login")?.jsonPrimitive?.contentOrNull)
        )
    }

    private data class GitHubRepositoryRef(
        val owner: String,
        val name: String
    )

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

    private fun looksLikeRebaseConflict(output: String): Boolean {
        val normalized = output.lowercase()
        return normalized.contains("could not apply") ||
            normalized.contains("resolve all conflicts manually") ||
            normalized.contains("after resolving the conflicts") ||
            normalized.contains("merge conflict") ||
            normalized.contains("conflict (")
    }

    private fun isSelfReviewBlocked(error: ProcessExecutionException): Boolean {
        val combined = listOf(error.message, error.stdout, error.stderr)
            .joinToString("\n")
            .lowercase()
        return combined.contains("approve your own pull request") ||
            combined.contains("review your own pull request") ||
            combined.contains("can not review your own pull request") ||
            combined.contains("cannot review your own pull request") ||
            combined.contains("cannot approve your own pull request") ||
            combined.contains("can not approve your own pull request")
    }

    private fun isAlreadyMerged(error: ProcessExecutionException): Boolean {
        val combined = listOf(error.message, error.stdout, error.stderr)
            .joinToString("\n")
            .lowercase()
        return combined.contains("already merged")
    }

    private fun isAlreadyClosed(error: ProcessExecutionException): Boolean {
        val combined = listOf(error.message, error.stdout, error.stderr)
            .joinToString("\n")
            .lowercase()
        return combined.contains("already closed")
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

    private fun managedPullRequestLineageKey(pullRequest: PullRequestRef): String? {
        val headRefName = pullRequest.headRefName?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        if (!headRefName.startsWith("codex/cotor/")) {
            return null
        }
        val trimmedHead = headRefName.removeSuffix("/codex")
        val normalizedHead = trimmedHead.replace(Regex("-[0-9a-f]{8,}$"), "")
        return "${pullRequest.title.orEmpty().trim()}|$normalizedHead"
    }

    private fun isManagedCodexPullRequest(
        pullRequest: PullRequestRef,
        currentLogin: String?
    ): Boolean {
        val headRefName = pullRequest.headRefName.orEmpty()
        if (!headRefName.startsWith("codex/cotor/")) {
            return false
        }
        val authorLogin = pullRequest.author?.login
        return currentLogin.isNullOrBlank() || authorLogin.isNullOrBlank() || authorLogin.equals(currentLogin, ignoreCase = true)
    }

    @Serializable
    private data class PullRequestRef(
        val number: Int? = null,
        val title: String? = null,
        val url: String,
        val state: String? = null,
        val reviewDecision: String? = null,
        val mergeStateStatus: String? = null,
        val mergeable: String? = null,
        val statusCheckRollup: JsonElement? = null,
        val autoMergeRequest: JsonElement? = null,
        val baseRefName: String? = null,
        val headRefName: String? = null,
        val updatedAt: String? = null,
        val mergeCommit: MergeCommitRef? = null,
        val author: PullRequestAuthorRef? = null
    )

    @Serializable
    private data class MergeCommitRef(
        val oid: String? = null
    )

    @Serializable
    private data class PullRequestAuthorRef(
        val login: String? = null
    )

    private fun summarizeStatusChecks(raw: JsonElement?): String? {
        val checks = raw?.jsonArray ?: return null
        if (checks.isEmpty()) {
            return null
        }
        return checks.take(12).joinToString("; ") { element ->
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull
                ?: obj["context"]?.jsonPrimitive?.contentOrNull
                ?: "check"
            val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"
            val conclusion = obj["conclusion"]?.jsonPrimitive?.contentOrNull
            if (conclusion.isNullOrBlank()) {
                "$name=$status"
            } else {
                "$name=$status/$conclusion"
            }
        }
    }
}
