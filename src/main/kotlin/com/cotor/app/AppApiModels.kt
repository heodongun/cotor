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

/**
 * Request payload for creating a multi-agent task from the desktop shell.
 */
@Serializable
data class CreateTaskRequest(
    val workspaceId: String,
    val title: String? = null,
    val prompt: String,
    val agents: List<String> = emptyList()
)

/**
 * Top-level bootstrap response consumed by the Swift client after launch/refresh.
 */
@Serializable
data class DashboardResponse(
    val repositories: List<ManagedRepository>,
    val workspaces: List<Workspace>,
    val tasks: List<AgentTask>,
    val settings: DesktopSettings
)

/**
 * Minimal readiness signal used by the desktop app before it attempts auth.
 */
@Serializable
data class HealthResponse(
    val ok: Boolean,
    val service: String
)
