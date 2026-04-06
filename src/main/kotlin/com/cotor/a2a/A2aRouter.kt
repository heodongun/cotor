package com.cotor.a2a

import com.cotor.app.CompanyDashboardResponse
import com.cotor.app.DesktopAppService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

class A2aRouter(
    private val desktopService: DesktopAppService,
    private val sessionStore: A2aSessionStore = A2aSessionStore(),
    private val dedupeStore: A2aDedupeStore = A2aDedupeStore()
) {
    suspend fun openSession(request: A2aSessionHelloRequest): A2aSessionWelcomeResponse =
        sessionStore.open(request)

    suspend fun acceptMessage(envelope: A2aEnvelope): A2aAckResponse {
        if (envelope.v != "a2a.v1") {
            throw IllegalArgumentException("unsupported_version")
        }
        if (System.currentTimeMillis() > envelope.ts + envelope.ttlMs) {
            throw IllegalStateException("expired_message")
        }
        dedupeStore.get(envelope.dedupeKey)?.let { existing ->
            return existing.copy(
                ack = existing.ack.copy(dedupeStatus = "already_processed")
            )
        }
        persistMessageSideEffects(envelope)
        if (envelope.to.isNotEmpty()) {
            sessionStore.enqueue(
                recipientAgentIds = envelope.to.map { it.agentId }.toSet(),
                tenant = envelope.tenant,
                message = envelope
            )
        }
        val ack = A2aAckResponse(
            ack = A2aAck(
                messageId = envelope.id,
                dedupeStatus = "accepted",
                serverTs = System.currentTimeMillis()
            )
        )
        dedupeStore.put(envelope.dedupeKey, ack)
        return ack
    }

    suspend fun pullMessages(sessionId: String, after: String?, limit: Int): A2aPullResponse {
        val session = sessionStore.get(sessionId) ?: throw IllegalArgumentException("unknown_session")
        sessionStore.touch(sessionId)
        val messages = sessionStore.pull(sessionId, after, limit.coerceIn(1, 100))
        return A2aPullResponse(
            sessionId = session.sessionId,
            messages = messages,
            nextCursor = messages.lastOrNull()?.id
        )
    }

    suspend fun snapshot(request: A2aSnapshotRequest): CompanyDashboardResponse =
        desktopService.companyDashboard(request.tenant.companyId)

    suspend fun registerArtifact(request: A2aArtifactRegistrationRequest): A2aArtifactRegistrationResponse {
        val now = System.currentTimeMillis()
        if (!request.url.isNullOrBlank()) {
            desktopService.addContextEntry(
                companyId = request.tenant.companyId,
                agentName = "a2a",
                kind = "artifact",
                title = request.label,
                content = request.url,
                issueId = request.issueId,
                goalId = null,
                visibility = "goal"
            )
        }
        return A2aArtifactRegistrationResponse(
            artifactId = UUID.randomUUID().toString(),
            kind = request.kind,
            label = request.label,
            url = request.url,
            localPath = request.localPath,
            serverTs = now
        )
    }

    private suspend fun persistMessageSideEffects(envelope: A2aEnvelope) {
        val companyId = envelope.tenant.companyId
        val fromAgent = envelope.from.roleName?.ifBlank { null } ?: envelope.from.agentId
        val issueId = envelope.correlation.issueId
        val goalId = envelope.correlation.goalId
        val title = bodyTitle(envelope.body) ?: envelope.type
        val content = bodyText(envelope.body) ?: envelope.type
        when (envelope.type) {
            "message.note" -> desktopService.addContextEntry(
                companyId = companyId,
                agentName = fromAgent,
                kind = "note",
                title = title,
                content = content,
                issueId = issueId,
                goalId = goalId,
                visibility = "goal"
            )

            "message.handoff" -> desktopService.addContextEntry(
                companyId = companyId,
                agentName = fromAgent,
                kind = "handoff",
                title = title,
                content = content,
                issueId = issueId,
                goalId = goalId,
                visibility = "goal"
            )

            "message.escalation",
            "review.request",
            "review.verdict",
            "task.assign",
            "task.accept",
            "run.update" -> desktopService.sendMessage(
                companyId = companyId,
                fromAgentName = fromAgent,
                toAgentName = envelope.to.firstOrNull()?.agentId,
                kind = envelope.type,
                subject = title,
                body = content,
                issueId = issueId,
                goalId = goalId
            )
        }
    }

    private fun bodyTitle(body: JsonElement): String? {
        val direct = bodyText(body)?.trim().orEmpty()
        if (direct.isNotBlank()) {
            return direct.take(80)
        }
        return null
    }

    private fun bodyText(body: JsonElement): String? = when (body) {
        is JsonPrimitive -> body.contentOrNull
        is JsonObject -> {
            listOf("title", "summary", "message", "text", "content", "status")
                .asSequence()
                .mapNotNull { key -> body[key] }
                .mapNotNull(::bodyText)
                .firstOrNull()
        }
        is JsonArray -> body.mapNotNull(::bodyText).firstOrNull()
        else -> null
    }
}
