package com.cotor.a2a

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class A2aTenant(
    val companyId: String,
    val projectContextId: String? = null
)

@Serializable
data class A2aParticipant(
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
    val v: String = "a2a.v1",
    val id: String,
    val type: String,
    val ts: Long,
    val tenant: A2aTenant,
    val from: A2aParticipant,
    val to: List<A2aParticipant> = emptyList(),
    val correlation: A2aCorrelation = A2aCorrelation(),
    val causation: A2aCausation = A2aCausation(),
    val dedupeKey: String,
    val ttlMs: Long,
    val body: JsonElement
)

@Serializable
data class A2aSessionHelloRequest(
    val agentId: String,
    val roleName: String? = null,
    val executionAgentName: String? = null,
    val capabilities: List<String> = emptyList(),
    val tenant: A2aTenant,
    val nonce: String? = null
)

@Serializable
data class A2aSessionRecord(
    val sessionId: String,
    val agentId: String,
    val roleName: String? = null,
    val executionAgentName: String? = null,
    val capabilities: List<String> = emptyList(),
    val tenant: A2aTenant,
    val status: String = "ACTIVE",
    val createdAt: Long,
    val lastHeartbeatAt: Long
)

@Serializable
data class A2aSessionWelcomeResponse(
    val ok: Boolean = true,
    val sessionId: String,
    val heartbeatIntervalMs: Long,
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
data class A2aPullResponse(
    val ok: Boolean = true,
    val sessionId: String,
    val messages: List<A2aEnvelope>,
    val nextCursor: String? = null
)

@Serializable
data class A2aSnapshotRequest(
    val tenant: A2aTenant,
    val includeIssues: Boolean = true,
    val includeTasks: Boolean = true,
    val includeRuns: Boolean = true
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
data class A2aArtifactRegistrationResponse(
    val ok: Boolean = true,
    val artifactId: String,
    val kind: String,
    val label: String,
    val url: String? = null,
    val localPath: String? = null,
    val serverTs: Long
)

@Serializable
data class A2aErrorPayload(
    val code: String,
    val message: String
)

@Serializable
data class A2aErrorResponse(
    val ok: Boolean = false,
    val error: A2aErrorPayload
)
