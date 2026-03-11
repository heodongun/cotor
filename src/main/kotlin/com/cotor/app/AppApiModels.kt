package com.cotor.app

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
data class CreateCompanyRequest(
    val name: String,
    val rootPath: String,
    val defaultBaseBranch: String? = null,
    val autonomyEnabled: Boolean = true
)

@Serializable
data class UpdateCompanyRequest(
    val name: String? = null,
    val defaultBaseBranch: String? = null,
    val autonomyEnabled: Boolean? = null
)

@Serializable
data class CreateCompanyAgentDefinitionRequest(
    val title: String,
    val agentCli: String,
    val roleSummary: String,
    val enabled: Boolean = true
)

@Serializable
data class UpdateCompanyAgentDefinitionRequest(
    val title: String? = null,
    val agentCli: String? = null,
    val roleSummary: String? = null,
    val enabled: Boolean? = null,
    val displayOrder: Int? = null
)

/**
 * Placeholder sync response until the real Linear adapter lands.
 */
@Serializable
data class LinearSyncResponse(
    val ok: Boolean,
    val message: String
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
