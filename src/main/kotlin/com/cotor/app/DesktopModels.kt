package com.cotor.app

import kotlinx.serialization.Serializable

/**
 * Indicates whether the repository is an already-existing local checkout or one
 * the desktop app cloned into its managed storage area.
 */
@Serializable
enum class RepositorySourceKind {
    LOCAL,
    CLONED
}

/**
 * Persisted repository record shown in the desktop sidebar.
 */
@Serializable
data class ManagedRepository(
    val id: String,
    val name: String,
    val localPath: String,
    val sourceKind: RepositorySourceKind,
    val remoteUrl: String? = null,
    val defaultBranch: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * A workspace pins one repository to one base branch.
 *
 * Tasks run inside a workspace so every agent branch can diff against the same base.
 */
@Serializable
data class Workspace(
    val id: String,
    val repositoryId: String,
    val name: String,
    val baseBranch: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Task lifecycle as surfaced in the desktop shell.
 */
@Serializable
enum class DesktopTaskStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED
}

/**
 * Per-agent execution lifecycle inside one desktop task.
 */
@Serializable
enum class AgentRunStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED
}

/**
 * Publish outcome attached to one agent run after the desktop workflow tries to
 * commit, push, and open a pull request for the worktree branch.
 */
@Serializable
data class PublishMetadata(
    val commitSha: String? = null,
    val pushedBranch: String? = null,
    val pullRequestNumber: Int? = null,
    val pullRequestUrl: String? = null,
    val error: String? = null
)

/**
 * User-authored task that can be fanned out to multiple agents.
 */
@Serializable
data class AgentTask(
    val id: String,
    val workspaceId: String,
    val title: String,
    val prompt: String,
    val agents: List<String>,
    val plan: TaskExecutionPlan? = null,
    val status: DesktopTaskStatus,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Persisted decomposition of one task goal into explicit per-agent assignments.
 */
@Serializable
data class TaskExecutionPlan(
    val version: Int = 1,
    val goalSummary: String,
    val decompositionSource: String,
    val sharedChecklist: List<String> = emptyList(),
    val assignments: List<AgentAssignmentPlan> = emptyList()
) {
    fun assignmentFor(agentName: String): AgentAssignmentPlan? =
        assignments.firstOrNull { it.agentName.equals(agentName, ignoreCase = true) }
}

/**
 * Agent-specific view of the generated plan, including the prompt to execute.
 */
@Serializable
data class AgentAssignmentPlan(
    val agentName: String,
    val role: String,
    val focus: String,
    val subtasks: List<TaskSubtask> = emptyList(),
    val assignedPrompt: String
)

/**
 * One concrete subtask owned by a specific agent within a generated plan.
 */
@Serializable
data class TaskSubtask(
    val id: String,
    val title: String,
    val details: String
)

/**
 * Persisted execution record for a single agent inside a task.
 *
 * `branchName` and `worktreePath` are the key isolation fields used by the UI.
 */
@Serializable
data class AgentRun(
    val id: String,
    val taskId: String,
    val workspaceId: String,
    val repositoryId: String,
    val agentId: String = "",
    val agentName: String,
    val repoRoot: String = "",
    val baseBranch: String = "",
    val branchName: String,
    val worktreePath: String,
    val status: AgentRunStatus,
    val processId: Long? = null,
    val output: String? = null,
    val error: String? = null,
    val publish: PublishMetadata? = null,
    val durationMs: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Interactive TUI lifecycle inside the desktop shell.
 */
@Serializable
enum class TuiSessionStatus {
    STARTING,
    RUNNING,
    EXITED,
    FAILED
}

/**
 * Snapshot of one interactive `cotor tui` session shown in the center pane.
 *
 * The transcript is sent inline because the native app polls for the current
 * terminal state and renders it directly as a terminal surface.
 */
@Serializable
data class TuiSession(
    val id: String,
    val workspaceId: String,
    val repositoryId: String,
    val repositoryPath: String,
    val agentName: String,
    val baseBranch: String,
    val status: TuiSessionStatus,
    val transcript: String,
    val transcriptStartOffset: Long = 0,
    val transcriptEndOffset: Long = transcript.length.toLong(),
    val processId: Long? = null,
    val exitCode: Int? = null,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Incremental terminal output chunk used by the web-based terminal emulator.
 */
@Serializable
data class TuiSessionDelta(
    val sessionId: String,
    val status: TuiSessionStatus,
    val offset: Long,
    val nextOffset: Long,
    val reset: Boolean,
    val chunk: String,
    val exitCode: Int? = null
)

/**
 * Request used to open or reuse the TUI session attached to a workspace.
 */
@Serializable
data class OpenTuiSessionRequest(
    val workspaceId: String,
    val preferredAgent: String? = null
)

/**
 * One user-entered line forwarded into the interactive TUI session.
 */
@Serializable
data class TuiInputRequest(
    val input: String
)

/**
 * Diff summary rendered in the right-hand inspector.
 */
@Serializable
data class ChangeSummary(
    val runId: String,
    val branchName: String,
    val baseBranch: String,
    val patch: String,
    val changedFiles: List<String>
)

/**
 * Tree node sent to the desktop client for the repository/file browser.
 */
@Serializable
data class FileTreeNode(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
    val children: List<FileTreeNode> = emptyList()
)

/**
 * Reserved for locally exposed services started by an agent run.
 */
@Serializable
data class PortEntry(
    val port: Int,
    val url: String,
    val label: String
)

/**
 * Desktop-only settings and discovery data used during bootstrap.
 */
@Serializable
data class DesktopSettings(
    val appHome: String,
    val managedReposRoot: String,
    val availableAgents: List<String>,
    val shortcuts: ShortcutConfig = ShortcutConfig()
)

/**
 * Readable list of keyboard shortcuts shown in the desktop settings UI.
 */
@Serializable
data class ShortcutConfig(
    val bindings: List<ShortcutBinding> = defaultShortcutBindings()
)

/**
 * One visible shortcut row in the desktop settings screen.
 */
@Serializable
data class ShortcutBinding(
    val id: String,
    val title: String,
    val shortcut: String
)

/**
 * Entire persisted desktop state snapshot.
 */
@Serializable
data class DesktopAppState(
    val repositories: List<ManagedRepository> = emptyList(),
    val workspaces: List<Workspace> = emptyList(),
    val tasks: List<AgentTask> = emptyList(),
    val runs: List<AgentRun> = emptyList()
)

/**
 * In-memory binding between one agent and its isolated git worktree.
 */
data class WorktreeBinding(
    val branchName: String,
    val worktreePath: java.nio.file.Path
)

private fun defaultShortcutBindings(): List<ShortcutBinding> = listOf(
    ShortcutBinding(id = "openRepository", title = "Open Repository", shortcut = "Command-N"),
    ShortcutBinding(id = "createTask", title = "Create Task", shortcut = "Command-T"),
    ShortcutBinding(id = "showBrowser", title = "Show Browser Tab", shortcut = "Command-B"),
    ShortcutBinding(id = "cloneRepository", title = "Clone Repository", shortcut = "Command-L"),
    ShortcutBinding(id = "showFiles", title = "Show Files Tab", shortcut = "Command-F"),
    ShortcutBinding(id = "settings", title = "Open Settings", shortcut = "Command-,"),
    ShortcutBinding(id = "changesTab", title = "Show Changes Tab", shortcut = "Command-1"),
    ShortcutBinding(id = "filesTab", title = "Show Files Tab", shortcut = "Command-2"),
    ShortcutBinding(id = "portsTab", title = "Show Ports Tab", shortcut = "Command-3"),
    ShortcutBinding(id = "browserTab", title = "Show Browser Tab", shortcut = "Command-4")
)
