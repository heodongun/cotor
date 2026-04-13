package com.cotor.runtime.actions

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class ActionKind(val wireValue: String) {
    SHELL_EXEC("shell.exec"),
    FILE_WRITE("file.write"),
    HTTP_REQUEST("http.request"),
    AGENT_EXEC("agent.exec"),
    GIT_WORKTREE("git.worktree"),
    GIT_PUBLISH("git.publish"),
    GITHUB_REVIEW("github.review"),
    GITHUB_COMMENT("github.comment"),
    GITHUB_MERGE("github.merge"),
    SECRET_READ("secret.read");

    companion object {
        fun fromWireValue(value: String): ActionKind? =
            entries.firstOrNull { it.wireValue.equals(value.trim(), ignoreCase = true) }
    }
}

@Serializable
enum class ActionScope {
    GLOBAL,
    COMPANY,
    GOAL,
    ISSUE,
    RUN
}

@Serializable
data class ActionSubject(
    val runId: String? = null,
    val companyId: String? = null,
    val goalId: String? = null,
    val issueId: String? = null,
    val taskId: String? = null,
    val agentName: String? = null
)

@Serializable
data class ActionEvidence(
    val filePaths: List<String> = emptyList(),
    val branchName: String? = null,
    val pullRequestNumber: Int? = null,
    val pullRequestUrl: String? = null,
    val checkSummary: String? = null
)

@Serializable
data class ActionRequest(
    val id: String = UUID.randomUUID().toString(),
    val kind: ActionKind,
    val label: String,
    val scope: ActionScope = ActionScope.GLOBAL,
    val subject: ActionSubject = ActionSubject(),
    val replaySafe: Boolean = true,
    val approvalRequiredOnReplay: Boolean = false,
    val command: List<String> = emptyList(),
    val path: String? = null,
    val networkTarget: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val evidence: ActionEvidence = ActionEvidence()
)

@Serializable
enum class ActionStatus {
    STARTED,
    SUCCEEDED,
    FAILED,
    DENIED,
    WAITING_FOR_APPROVAL
}

@Serializable
data class ActionResult(
    val status: ActionStatus,
    val outputSummary: String? = null,
    val error: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class ActionExecutionRecord(
    val id: String,
    val runId: String,
    val request: ActionRequest,
    val status: ActionStatus,
    val outputSummary: String? = null,
    val error: String? = null,
    val policyDecisionId: String? = null,
    val evidence: ActionEvidence = ActionEvidence(),
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class ActionLogSnapshot(
    val runId: String,
    val records: List<ActionExecutionRecord> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

class ActionDeniedException(
    val request: ActionRequest,
    message: String
) : IllegalStateException(message)

class ActionApprovalRequiredException(
    val request: ActionRequest,
    message: String
) : IllegalStateException(message)

