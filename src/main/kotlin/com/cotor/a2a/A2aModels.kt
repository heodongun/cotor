package com.cotor.a2a

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class A2aTenant(
    val companyId: String,
    val projectContextId: String? = null
)

@Serializable
data class A2aParty(
    val agentId: String,
    val roleName: String? = null,
    val executionAgentName: String? = null
)

@Serializable
data class A2aCorrelation(
    val goalId: String? = null,
    val issueId: String? = null,
    val taskId: String? = null,
    val runId: String? = null,
    val reviewQueueItemId: String? = null
)

@Serializable
data class A2aCausation(
    val messageId: String? = null
)

@Serializable
data class A2aEnvelope(
    val v: String,
    val id: String,
    val type: String,
    val ts: Long,
    val tenant: A2aTenant,
    val from: A2aParty,
    val to: List<A2aParty> = emptyList(),
    val correlation: A2aCorrelation? = null,
    val causation: A2aCausation? = null,
    val dedupeKey: String,
    val ttlMs: Long,
    val body: JsonElement
)

@Serializable
data class A2aHelloRequest(
    val agentId: String,
    val roleName: String? = null,
    val executionAgentName: String? = null,
    val capabilities: List<String> = emptyList(),
    val tenant: A2aTenant,
    val nonce: String? = null
)

@Serializable
data class A2aWelcomeResponse(
    val ok: Boolean = true,
    val sessionId: String,
    val heartbeatIntervalMs: Long,
    val serverTs: Long
)

@Serializable
data class A2aHeartbeatResponse(
    val ok: Boolean = true,
    val sessionId: String,
    val serverTs: Long
)

@Serializable
data class A2aAck(
    val messageId: String,
    val dedupeStatus: String,
    val serverTs: Long
)

@Serializable
data class A2aAckResponse(
    val ok: Boolean = true,
    val ack: A2aAck
)

@Serializable
data class A2aErrorBody(
    val code: String,
    val message: String
)

@Serializable
data class A2aErrorResponse(
    val ok: Boolean = false,
    val error: A2aErrorBody
)

@Serializable
data class A2aQueuedMessage(
    val cursor: Long,
    val receivedAt: Long,
    val envelope: A2aEnvelope
)

@Serializable
data class A2aPullResponse(
    val ok: Boolean = true,
    val messages: List<A2aQueuedMessage>,
    val nextCursor: Long?
)

@Serializable
data class A2aAckMessagesRequest(
    val sessionId: String,
    val throughCursor: Long
)

@Serializable
data class A2aAckMessagesResponse(
    val ok: Boolean = true,
    val sessionId: String,
    val removedCount: Int,
    val pendingCount: Int,
    val serverTs: Long
)

@Serializable
data class A2aSnapshotRequest(
    val tenant: A2aTenant,
    val includeIssues: Boolean = true,
    val includeTasks: Boolean = true,
    val includeRuns: Boolean = true,
    val includeReviewQueue: Boolean = true,
    val includeActivity: Boolean = true
)

@Serializable
data class A2aSnapshotResponse(
    val ok: Boolean = true,
    val serverTs: Long,
    val snapshot: JsonObject
)

@Serializable
data class A2aArtifactRegistrationRequest(
    val tenant: A2aTenant,
    val kind: String,
    val label: String,
    val url: String? = null,
    val localPath: String? = null,
    val issueId: String? = null,
    val taskId: String? = null,
    val runId: String? = null
)

@Serializable
data class A2aArtifactRegistration(
    val id: String,
    val tenant: A2aTenant,
    val kind: String,
    val label: String,
    val url: String? = null,
    val localPath: String? = null,
    val issueId: String? = null,
    val taskId: String? = null,
    val runId: String? = null,
    val createdAt: Long
)

@Serializable
data class A2aArtifactRegistrationResponse(
    val ok: Boolean = true,
    val artifact: A2aArtifactRegistration
)

@Serializable
data class A2aArtifactListResponse(
    val ok: Boolean = true,
    val artifacts: List<A2aArtifactRegistration>
)

@Serializable
data class A2aSession(
    val id: String,
    val agentId: String,
    val roleName: String? = null,
    val executionAgentName: String? = null,
    val capabilities: List<String> = emptyList(),
    val tenant: A2aTenant,
    val nonce: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

internal fun a2aError(code: String, message: String) = A2aErrorResponse(
    error = A2aErrorBody(code = code, message = message)
)

internal fun emptyA2aSnapshot() = buildJsonObject {
    put("companies", buildJsonObject { })
}
