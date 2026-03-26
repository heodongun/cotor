package com.cotor.app

/**
 * File overview for OpenRepositoryRequest.
 *
 * This file belongs to the app layer for the desktop shell and localhost app-server surface.
 * It groups declarations around app api models so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import kotlinx.serialization.Serializable

/**
 * Request payload for registering an existing local git checkout.
 */
@Serializable
data class OpenRepositoryRequest(
    val path: String
)

/**
 * Request payload for cloning a remote repository into the app-managed area.
 */
@Serializable
data class CloneRepositoryRequest(
    val url: String
)

/**
 * Request payload for creating a branch-pinned workspace under a repository.
 */
@Serializable
data class CreateWorkspaceRequest(
    val repositoryId: String,
    val name: String? = null,
    val baseBranch: String? = null
)

@Serializable
data class UpdateWorkspaceBaseBranchRequest(
    val baseBranch: String
)

/**
 * Request payload for creating a multi-agent task from the desktop shell.
 */
@Serializable
data class CreateTaskRequest(
    val workspaceId: String,
    val title: String? = null,
    val prompt: String,
    val agents: List<String> = emptyList(),
    val issueId: String? = null
)

/**
 * Request payload for creating an autonomous company goal.
 */
@Serializable
data class CreateGoalRequest(
    val title: String,
    val description: String,
    val successMetrics: List<String> = emptyList(),
    val autonomyEnabled: Boolean = true
)

@Serializable
data class UpdateGoalRequest(
    val title: String? = null,
    val description: String? = null,
    val successMetrics: List<String>? = null,
    val autonomyEnabled: Boolean? = null
)

@Serializable
data class CreateIssueRequest(
    val goalId: String,
    val title: String,
    val description: String,
    val priority: Int = 3,
    val kind: String = "manual"
)

@Serializable
data class CreateCompanyRequest(
    val name: String,
    val rootPath: String,
    val defaultBaseBranch: String? = null,
    val autonomyEnabled: Boolean = true
)

@Serializable
data class CreateCompanyResponse(
    val company: Company,
    val githubPublishStatus: GitHubPublishStatus
)

@Serializable
data class UpdateCompanyRequest(
    val name: String? = null,
    val defaultBaseBranch: String? = null,
    val autonomyEnabled: Boolean? = null,
    val backendKind: ExecutionBackendKind? = null
)

@Serializable
data class UpdateCompanyLinearRequest(
    val enabled: Boolean,
    val endpoint: String? = null,
    val apiToken: String? = null,
    val teamId: String? = null,
    val projectId: String? = null,
    val stateMappings: List<LinearStateMapping>? = null,
    val useGlobalDefault: Boolean = false
)

@Serializable
data class CreateCompanyAgentDefinitionRequest(
    val title: String,
    val agentCli: String,
    val roleSummary: String,
    val specialties: List<String> = emptyList(),
    val collaborationInstructions: String? = null,
    val preferredCollaboratorIds: List<String> = emptyList(),
    val memoryNotes: String? = null,
    val enabled: Boolean = true
)

@Serializable
data class UpdateCompanyAgentDefinitionRequest(
    val title: String? = null,
    val agentCli: String? = null,
    val roleSummary: String? = null,
    val specialties: List<String>? = null,
    val collaborationInstructions: String? = null,
    val preferredCollaboratorIds: List<String>? = null,
    val memoryNotes: String? = null,
    val enabled: Boolean? = null,
    val displayOrder: Int? = null
)

@Serializable
data class UpdateBackendSettingsRequest(
    val defaultBackendKind: ExecutionBackendKind,
    val codePublishMode: CodePublishMode? = null,
    val codexLaunchMode: BackendLaunchMode? = null,
    val codexCommand: String? = null,
    val codexArgs: List<String>? = null,
    val codexWorkingDirectory: String? = null,
    val codexPort: Int? = null,
    val codexStartupTimeoutSeconds: Int? = null,
    val codexAppServerBaseUrl: String? = null,
    val codexAuthMode: String? = null,
    val codexToken: String? = null,
    val codexTimeoutSeconds: Int? = null
)

@Serializable
data class UpdateCompanyBackendRequest(
    val backendKind: ExecutionBackendKind,
    val launchMode: BackendLaunchMode? = null,
    val command: String? = null,
    val args: List<String>? = null,
    val workingDirectory: String? = null,
    val port: Int? = null,
    val startupTimeoutSeconds: Int? = null,
    val baseUrl: String? = null,
    val authMode: String? = null,
    val token: String? = null,
    val timeoutSeconds: Int? = null,
    val useGlobalDefault: Boolean = false
)

@Serializable
data class TestBackendRequest(
    val kind: ExecutionBackendKind,
    val launchMode: BackendLaunchMode? = null,
    val command: String? = null,
    val args: List<String>? = null,
    val workingDirectory: String? = null,
    val port: Int? = null,
    val startupTimeoutSeconds: Int? = null,
    val baseUrl: String? = null,
    val authMode: String? = null,
    val token: String? = null,
    val timeoutSeconds: Int? = null
)

@Serializable
data class CompanyEventEnvelope(
    val event: CompanyEvent,
    val dashboard: DashboardResponse? = null
)

@Serializable
data class LinearSyncResponse(
    val ok: Boolean,
    val message: String,
    val syncedIssues: Int = 0,
    val createdIssues: Int = 0,
    val commentedIssues: Int = 0,
    val failedIssues: List<String> = emptyList()
)

/**
 * Focused autonomous-company dashboard contract used by the operations UI.
 */
@Serializable
data class CompanyDashboardResponse(
    val companies: List<Company> = emptyList(),
    val companyAgentDefinitions: List<CompanyAgentDefinition> = emptyList(),
    val projectContexts: List<CompanyProjectContext> = emptyList(),
    val goals: List<CompanyGoal> = emptyList(),
    val issues: List<CompanyIssue> = emptyList(),
    val issueDependencies: List<IssueDependency> = emptyList(),
    val reviewQueue: List<ReviewQueueItem> = emptyList(),
    val orgProfiles: List<OrgAgentProfile> = emptyList(),
    val workflowTopologies: List<WorkflowTopologySnapshot> = emptyList(),
    val goalDecisions: List<GoalOrchestrationDecision> = emptyList(),
    val runningAgentSessions: List<RunningAgentSession> = emptyList(),
    val backendStatuses: List<ExecutionBackendStatus> = emptyList(),
    val opsMetrics: OpsMetricSnapshot = OpsMetricSnapshot(),
    val runtime: CompanyRuntimeSnapshot = CompanyRuntimeSnapshot(),
    val signals: List<OpsSignal> = emptyList(),
    val activity: List<CompanyActivityItem> = emptyList()
)

/**
 * Top-level bootstrap response consumed by the Swift client after launch/refresh.
 */
@Serializable
data class DashboardResponse(
    val repositories: List<ManagedRepository>,
    val workspaces: List<Workspace>,
    val tasks: List<AgentTask>,
    val settings: DesktopSettings,
    val companies: List<Company> = emptyList(),
    val companyAgentDefinitions: List<CompanyAgentDefinition> = emptyList(),
    val projectContexts: List<CompanyProjectContext> = emptyList(),
    val goals: List<CompanyGoal> = emptyList(),
    val issues: List<CompanyIssue> = emptyList(),
    val reviewQueue: List<ReviewQueueItem> = emptyList(),
    val orgProfiles: List<OrgAgentProfile> = emptyList(),
    val workflowTopologies: List<WorkflowTopologySnapshot> = emptyList(),
    val goalDecisions: List<GoalOrchestrationDecision> = emptyList(),
    val runningAgentSessions: List<RunningAgentSession> = emptyList(),
    val backendStatuses: List<ExecutionBackendStatus> = emptyList(),
    val opsMetrics: OpsMetricSnapshot = OpsMetricSnapshot(),
    val activity: List<CompanyActivityItem> = emptyList(),
    val companyRuntimes: List<CompanyRuntimeSnapshot> = emptyList()
)

/**
 * Minimal readiness signal used by the desktop app before it attempts auth.
 */
@Serializable
data class HealthResponse(
    val ok: Boolean,
    val service: String
)

// ── Pipeline API Models ─────────────────────────────────────────────

@Serializable
data class CreatePipelineRequest(
    val name: String,
    val stages: List<PipelineStageRequest>
)

@Serializable
data class UpdatePipelineRequest(
    val name: String? = null,
    val stages: List<PipelineStageRequest>? = null
)

@Serializable
data class PipelineStageRequest(
    val id: String? = null,
    val kind: String,
    val title: String,
    val assigneeRoleName: String? = null,
    val verdictKey: String? = null,
    val verdictPassValue: String? = null,
    val verdictFailValue: String? = null,
    val skipWhen: String? = null
)

// ── Context Entry API Models ────────────────────────────────────────

@Serializable
data class CreateContextEntryRequest(
    val agentName: String,
    val kind: String,
    val title: String,
    val content: String,
    val issueId: String? = null,
    val goalId: String? = null,
    val visibility: String? = null
)

// ── Agent Message API Models ────────────────────────────────────────

@Serializable
data class BudgetResponse(
    val dailyBudgetCents: Int? = null,
    val monthlyBudgetCents: Int? = null,
    val todaySpentCents: Int = 0,
    val monthSpentCents: Int = 0,
    val budgetPaused: Boolean = false
)

@Serializable
data class SendMessageRequest(
    val fromAgentName: String,
    val toAgentName: String? = null,
    val kind: String,
    val subject: String,
    val body: String,
    val issueId: String? = null,
    val goalId: String? = null
)
