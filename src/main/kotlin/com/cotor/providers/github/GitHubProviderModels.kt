package com.cotor.providers.github

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class CheckSnapshot(
    val name: String,
    val status: String,
    val conclusion: String? = null,
    val detailsUrl: String? = null
)

@Serializable
data class MergeRequirement(
    val requiredChecks: List<String> = emptyList(),
    val branchProtectionKnown: Boolean = false,
    val reviewDecision: String? = null,
    val autoMergeEnabled: Boolean = false
)

@Serializable
data class MergeQueueState(
    val state: String = "unknown",
    val details: String? = null
)

@Serializable
data class PullRequestSnapshot(
    val number: Int,
    val url: String? = null,
    val state: String? = null,
    val mergeability: String? = null,
    val mergeable: String? = null,
    val checksSummary: String? = null,
    val checks: List<CheckSnapshot> = emptyList(),
    val mergeRequirement: MergeRequirement = MergeRequirement(),
    val mergeQueueState: MergeQueueState = MergeQueueState(),
    val companyId: String? = null,
    val issueId: String? = null,
    val runId: String? = null,
    val branchName: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class GitHubProviderEvent(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val pullRequestNumber: Int? = null,
    val companyId: String? = null,
    val issueId: String? = null,
    val runId: String? = null,
    val detail: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class GitHubProviderState(
    val pullRequests: List<PullRequestSnapshot> = emptyList(),
    val events: List<GitHubProviderEvent> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class GitHubSyncResponse(
    val companyId: String,
    val syncedPullRequests: Int,
    val resumedRuntimeTick: Boolean,
    val updatedAt: Long = System.currentTimeMillis()
)

