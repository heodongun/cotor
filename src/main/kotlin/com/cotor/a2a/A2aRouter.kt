package com.cotor.a2a

import com.cotor.app.DesktopAppService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.UUID

class A2aRouter(
    private val desktopService: DesktopAppService,
    private val sessionStore: A2aSessionStore = A2aSessionStore(),
    private val dedupeStore: A2aDedupeStore = A2aDedupeStore()
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val artifacts = mutableListOf<A2aArtifactRegistration>()

    fun openSession(request: A2aHelloRequest, now: Long = System.currentTimeMillis()): A2aWelcomeResponse {
        val session = sessionStore.open(request, now)
        return A2aWelcomeResponse(
            sessionId = session.id,
            heartbeatIntervalMs = sessionStore.heartbeatIntervalMs(),
            serverTs = now
        )
    }

    fun postMessage(envelope: A2aEnvelope, now: Long = System.currentTimeMillis()): A2aAckResponse {
        validateEnvelope(envelope, now)
        val freshAck = A2aAck(
            messageId = envelope.id,
            dedupeStatus = "accepted",
            serverTs = now
        )
        val (accepted, ack) = dedupeStore.remember(envelope.dedupeKey, freshAck)
        if (!accepted) {
            return A2aAckResponse(
                ack = ack.copy(dedupeStatus = "already_processed")
            )
        }
        recipientsFor(envelope).forEach { session ->
            sessionStore.enqueue(session.id, envelope, now)
        }
        return A2aAckResponse(ack = ack)
    }

    fun pullMessages(sessionId: String, after: Long?, limit: Int, now: Long = System.currentTimeMillis()): A2aPullResponse {
        require(limit > 0) { "limit must be positive" }
        require(sessionStore.session(sessionId) != null) { "Unknown session: $sessionId" }
        val messages = sessionStore.pull(sessionId, after, limit.coerceAtMost(100), now)
        return A2aPullResponse(
            messages = messages,
            nextCursor = messages.lastOrNull()?.cursor
        )
    }

    suspend fun snapshot(request: A2aSnapshotRequest, now: Long = System.currentTimeMillis()): A2aSnapshotResponse {
        val dashboard = desktopService.companyDashboard(request.tenant.companyId)
        val snapshot = buildJsonObject {
            put("companyId", request.tenant.companyId)
            put("serverTs", now)
            put("dashboard", json.encodeToJsonElement(dashboard))
        }
        return A2aSnapshotResponse(serverTs = now, snapshot = snapshot)
    }

    fun registerArtifact(request: A2aArtifactRegistrationRequest, now: Long = System.currentTimeMillis()): A2aArtifactRegistrationResponse {
        val artifact = A2aArtifactRegistration(
            id = UUID.randomUUID().toString(),
            tenant = request.tenant,
            kind = request.kind.trim(),
            label = request.label.trim(),
            url = request.url?.trim(),
            localPath = request.localPath?.trim(),
            issueId = request.issueId,
            taskId = request.taskId,
            runId = request.runId,
            createdAt = now
        )
        artifacts += artifact
        return A2aArtifactRegistrationResponse(artifact = artifact)
    }

    private fun validateEnvelope(envelope: A2aEnvelope, now: Long) {
        require(envelope.v == "a2a.v1") { "Unsupported protocol version: ${envelope.v}" }
        require(envelope.id.isNotBlank()) { "message id is required" }
        require(envelope.type in SUPPORTED_MESSAGE_TYPES) { "Unsupported message type: ${envelope.type}" }
        require(envelope.dedupeKey.isNotBlank()) { "dedupeKey is required" }
        require(envelope.ttlMs > 0) { "ttlMs must be positive" }
        require(envelope.from.agentId.isNotBlank()) { "from.agentId is required" }
        require(envelope.tenant.companyId.isNotBlank()) { "tenant.companyId is required" }
        if (envelope.ts + envelope.ttlMs < now) {
            error("expired_message")
        }
    }

    private fun recipientsFor(envelope: A2aEnvelope): List<A2aSession> {
        val tenantSessions = sessionStore.sessionsForTenant(envelope.tenant)
        if (envelope.to.isEmpty()) {
            return tenantSessions.filterNot { it.agentId == envelope.from.agentId }
        }
        return tenantSessions.filter { session ->
            envelope.to.any { target ->
                target.agentId.equals(session.agentId, ignoreCase = true) ||
                    (!target.executionAgentName.isNullOrBlank() &&
                        target.executionAgentName.equals(session.executionAgentName, ignoreCase = true))
            }
        }
    }

    companion object {
        val SUPPORTED_MESSAGE_TYPES = setOf(
            "task.assign",
            "task.accept",
            "run.update",
            "review.request",
            "review.verdict",
            "message.note",
            "message.handoff",
            "message.escalation",
            "sync.snapshot.request",
            "sync.snapshot.response"
        )
    }
}
