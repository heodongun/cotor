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
 * Top-level company container that owns one operating root and its autonomous
 * workflow state.
 */
@Serializable
enum class ExecutionBackendKind {
    LOCAL_COTOR,
    CODEX_APP_SERVER
}

@Serializable
data class BackendConnectionConfig(
    val kind: ExecutionBackendKind,
    val baseUrl: String? = null,
    val healthCheckPath: String = "/health",
    val authMode: String = "none",
    val token: String? = null,
    val timeoutSeconds: Int = 30,
    val enabled: Boolean = true
)

@Serializable
data class ExecutionBackendCapabilities(
    val canStreamEvents: Boolean = false,
    val canResumeRuns: Boolean = true,
    val canSpawnParallelAgents: Boolean = true,
    val canPublishPullRequests: Boolean = true
)

@Serializable
data class ExecutionBackendStatus(
    val kind: ExecutionBackendKind,
    val displayName: String,
    val health: String = "unknown",
    val message: String? = null,
    val config: BackendConnectionConfig,
    val capabilities: ExecutionBackendCapabilities = ExecutionBackendCapabilities()
)

@Serializable
data class DesktopBackendSettings(
    val defaultBackendKind: ExecutionBackendKind = ExecutionBackendKind.LOCAL_COTOR,
    val backends: List<BackendConnectionConfig> = defaultBackendConfigs()
)

@Serializable
data class Company(
    val id: String,
    val name: String,
    val rootPath: String,
    val repositoryId: String,
    val defaultBaseBranch: String,
    val backendKind: ExecutionBackendKind = ExecutionBackendKind.LOCAL_COTOR,
    val backendConfigOverride: BackendConnectionConfig? = null,
    val autonomyEnabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Minimal user-authored agent definition. The system derives hierarchy and
 * routing hints from these inputs instead of requiring users to encode a full
 * workflow graph up front.
 */
@Serializable
data class CompanyAgentDefinition(
    val id: String,
    val companyId: String,
    val title: String,
    val agentCli: String,
    val roleSummary: String,
    val specialties: List<String> = emptyList(),
    val collaborationInstructions: String? = null,
    val preferredCollaboratorIds: List<String> = emptyList(),
    val memoryNotes: String? = null,
    val enabled: Boolean = true,
    val displayOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Company-local project context persisted as human-readable memory for
 * cross-session and cross-agent continuity.
 */
@Serializable
data class CompanyProjectContext(
    val id: String,
    val companyId: String,
    val name: String,
    val slug: String,
    val status: String = "ACTIVE",
    val contextDocPath: String,
    val lastUpdatedAt: Long
)

/**
 * Timeline item shown in the company operations dashboard.
 */
@Serializable
data class CompanyActivityItem(
    val id: String,
    val companyId: String,
    val projectContextId: String? = null,
    val goalId: String? = null,
    val issueId: String? = null,
    val source: String,
    val title: String,
    val detail: String? = null,
    val severity: String = "info",
    val createdAt: Long
)

@Serializable
data class AgentCollaborationEdge(
    val companyId: String,
    val fromAgentId: String,
    val toAgentId: String,
    val reason: String,
    val handoffType: String
)

@Serializable
data class WorkflowTopologySnapshot(
    val companyId: String,
    val agents: List<String> = emptyList(),
    val edges: List<AgentCollaborationEdge> = emptyList(),
    val activeLoops: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class GoalOrchestrationDecision(
    val id: String,
    val companyId: String,
    val goalId: String? = null,
    val issueId: String? = null,
    val title: String,
    val summary: String,
    val createdIssues: List<String> = emptyList(),
    val assignments: List<String> = emptyList(),
    val escalations: List<String> = emptyList(),
    val createdAt: Long
)

@Serializable
data class RunningAgentSession(
    val companyId: String,
    val runId: String,
    val taskId: String,
    val issueId: String? = null,
    val goalId: String? = null,
    val agentId: String,
    val agentName: String,
    val roleName: String? = null,
    val status: AgentRunStatus,
    val branchName: String,
    val processId: Long? = null,
    val outputSnippet: String? = null,
    val startedAt: Long,
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
 * Company-goal lifecycle shown in the autonomous operations dashboard.
 */
@Serializable
enum class GoalStatus {
    DRAFT,
    ACTIVE,
    PAUSED,
    COMPLETED,
    BLOCKED
}

/**
 * Issue lifecycle is separate from raw agent run status because one issue can move
 * through planning, delegation, implementation, review, and merge stages.
 */
@Serializable
enum class IssueStatus {
    BACKLOG,
    PLANNED,
    DELEGATED,
    IN_PROGRESS,
    IN_REVIEW,
    BLOCKED,
    DONE,
    CANCELED
}

/**
 * Review-queue status attached to issue-linked pull requests.
 */
@Serializable
enum class ReviewQueueStatus {
    AWAITING_QA,
    AWAITING_REVIEW,
    CHANGES_REQUESTED,
    READY_TO_MERGE,
    MERGED,
    FAILED_CHECKS
}

/**
 * High-level state of the headless company runtime loop.
 */
@Serializable
enum class CompanyRuntimeStatus {
    STOPPED,
    RUNNING,
    ERROR
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
    val reviewState: String? = null,
    val requestedReviewers: List<String> = emptyList(),
    val checksSummary: String? = null,
    val mergeability: String? = null,
    val lastSyncTime: Long? = null,
    val error: String? = null
)

/**
 * User-authored task that can be fanned out to multiple agents.
 */
@Serializable
data class AgentTask(
    val id: String,
    val workspaceId: String,
    val issueId: String? = null,
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
    val participantId: String? = null,
    val agentName: String,
    val role: String,
    val phase: String = "execution",
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
 * Top-level business goal driven by the CEO agent.
 */
@Serializable
data class CompanyGoal(
    val id: String,
    val companyId: String = "",
    val projectContextId: String? = null,
    val title: String,
    val description: String,
    val status: GoalStatus,
    val priority: Int = 2,
    val successMetrics: List<String> = emptyList(),
    val operatingPolicy: String? = null,
    val autonomyEnabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Execution unit derived from one company goal.
 */
@Serializable
data class CompanyIssue(
    val id: String,
    val companyId: String = "",
    val projectContextId: String? = null,
    val goalId: String,
    val workspaceId: String,
    val title: String,
    val description: String,
    val status: IssueStatus,
    val priority: Int = 3,
    val kind: String = "implementation",
    val assigneeProfileId: String? = null,
    val linearIssueId: String? = null,
    val blockedBy: List<String> = emptyList(),
    val dependsOn: List<String> = emptyList(),
    val acceptanceCriteria: List<String> = emptyList(),
    val riskLevel: String = "medium",
    val sourceSignal: String = "goal-decomposition",
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Explicit dependency edge between issues for board/canvas visualizations.
 */
@Serializable
data class IssueDependency(
    val id: String,
    val issueId: String,
    val dependsOnIssueId: String,
    val relation: String = "blocks"
)

/**
 * Organization profile used to route issues to the right execution agent.
 */
@Serializable
data class OrgAgentProfile(
    val id: String,
    val companyId: String = "",
    val roleName: String,
    val executionAgentName: String,
    val capabilities: List<String> = emptyList(),
    val linearAssigneeId: String? = null,
    val reviewerPolicy: String? = null,
    val mergeAuthority: Boolean = false,
    val enabled: Boolean = true
)

/**
 * Review queue item tied to a PR created for an issue.
 */
@Serializable
data class ReviewQueueItem(
    val id: String,
    val companyId: String = "",
    val projectContextId: String? = null,
    val issueId: String,
    val runId: String,
    val pullRequestNumber: Int? = null,
    val pullRequestUrl: String? = null,
    val status: ReviewQueueStatus,
    val checksSummary: String? = null,
    val mergeability: String? = null,
    val requestedReviewers: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Lightweight ops metrics snapshot rendered in the dashboard header.
 */
@Serializable
data class OpsMetricSnapshot(
    val openGoals: Int = 0,
    val activeIssues: Int = 0,
    val blockedIssues: Int = 0,
    val readyToMergeCount: Int = 0,
    val mergedCount: Int = 0,
    val lastUpdatedAt: Long = 0
)

/**
 * Structured operational signal generated by the autonomous runtime.
 */
@Serializable
data class OpsSignal(
    val id: String,
    val companyId: String? = null,
    val projectContextId: String? = null,
    val source: String,
    val message: String,
    val severity: String = "info",
    val goalId: String? = null,
    val issueId: String? = null,
    val createdAt: Long
)

/**
 * Snapshot rendered in the autonomous company runtime panel.
 */
@Serializable
data class CompanyRuntimeSnapshot(
    val companyId: String? = null,
    val status: CompanyRuntimeStatus = CompanyRuntimeStatus.STOPPED,
    val tickIntervalSeconds: Long = 60,
    val activeGoalCount: Int = 0,
    val activeIssueCount: Int = 0,
    val autonomyEnabledGoalCount: Int = 0,
    val lastStartedAt: Long? = null,
    val lastStoppedAt: Long? = null,
    val lastTickAt: Long? = null,
    val lastAction: String? = null,
    val lastError: String? = null,
    val backendKind: ExecutionBackendKind = ExecutionBackendKind.LOCAL_COTOR,
    val backendHealth: String = "unknown",
    val backendMessage: String? = null
)

@Serializable
data class CompanyEvent(
    val id: String,
    val companyId: String,
    val type: String,
    val title: String,
    val detail: String? = null,
    val goalId: String? = null,
    val issueId: String? = null,
    val runId: String? = null,
    val createdAt: Long
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
    val availableCliAgents: List<String> = availableAgents,
    val recentCompanies: List<String> = emptyList(),
    val defaultLaunchMode: String = "company",
    val backendSettings: DesktopBackendSettings = DesktopBackendSettings(),
    val backendStatuses: List<ExecutionBackendStatus> = emptyList(),
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
    val companies: List<Company> = emptyList(),
    val companyAgentDefinitions: List<CompanyAgentDefinition> = emptyList(),
    val projectContexts: List<CompanyProjectContext> = emptyList(),
    val repositories: List<ManagedRepository> = emptyList(),
    val workspaces: List<Workspace> = emptyList(),
    val tasks: List<AgentTask> = emptyList(),
    val runs: List<AgentRun> = emptyList(),
    val goals: List<CompanyGoal> = emptyList(),
    val issues: List<CompanyIssue> = emptyList(),
    val issueDependencies: List<IssueDependency> = emptyList(),
    val orgProfiles: List<OrgAgentProfile> = emptyList(),
    val workflowTopologies: List<WorkflowTopologySnapshot> = emptyList(),
    val goalDecisions: List<GoalOrchestrationDecision> = emptyList(),
    val reviewQueue: List<ReviewQueueItem> = emptyList(),
    val companyActivity: List<CompanyActivityItem> = emptyList(),
    val opsMetrics: OpsMetricSnapshot = OpsMetricSnapshot(),
    val signals: List<OpsSignal> = emptyList(),
    val backendSettings: DesktopBackendSettings = DesktopBackendSettings(),
    val runtime: CompanyRuntimeSnapshot = CompanyRuntimeSnapshot(),
    val companyRuntimes: List<CompanyRuntimeSnapshot> = emptyList()
)

private fun defaultBackendConfigs(): List<BackendConnectionConfig> = listOf(
    BackendConnectionConfig(
        kind = ExecutionBackendKind.LOCAL_COTOR,
        baseUrl = "http://127.0.0.1:8787",
        healthCheckPath = "/api/app/health",
        authMode = "bearer",
        timeoutSeconds = 30,
        enabled = true
    ),
    BackendConnectionConfig(
        kind = ExecutionBackendKind.CODEX_APP_SERVER,
        baseUrl = System.getenv("CODEX_APP_SERVER_URL")?.takeIf { it.isNotBlank() },
        healthCheckPath = "/health",
        authMode = "none",
        timeoutSeconds = 30,
        enabled = true
    )
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
