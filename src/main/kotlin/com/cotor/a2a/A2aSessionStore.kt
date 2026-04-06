package com.cotor.a2a

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class A2aSessionStore(
    private val heartbeatIntervalMs: Long = 15_000L
) {
    private val sessions = ConcurrentHashMap<String, A2aSessionRecord>()
    private val inbox = ConcurrentHashMap<String, ConcurrentLinkedQueue<A2aEnvelope>>()

    fun open(request: A2aSessionHelloRequest): A2aSessionWelcomeResponse {
        val now = System.currentTimeMillis()
        val sessionId = UUID.randomUUID().toString()
        sessions[sessionId] = A2aSessionRecord(
            sessionId = sessionId,
            agentId = request.agentId.trim(),
            roleName = request.roleName?.trim(),
            executionAgentName = request.executionAgentName?.trim(),
            capabilities = request.capabilities,
            tenant = request.tenant,
            createdAt = now,
            lastHeartbeatAt = now
        )
        inbox.putIfAbsent(sessionId, ConcurrentLinkedQueue())
        return A2aSessionWelcomeResponse(
            sessionId = sessionId,
            heartbeatIntervalMs = heartbeatIntervalMs,
            serverTs = now
        )
    }

    fun get(sessionId: String): A2aSessionRecord? = sessions[sessionId]

    fun touch(sessionId: String) {
        sessions.computeIfPresent(sessionId) { _, current ->
            current.copy(lastHeartbeatAt = System.currentTimeMillis())
        }
    }

    fun enqueue(recipientAgentIds: Set<String>, tenant: A2aTenant, message: A2aEnvelope) {
        sessions.values
            .filter { session ->
                session.tenant.companyId == tenant.companyId &&
                    recipientAgentIds.contains(session.agentId)
            }
            .forEach { session ->
                inbox.computeIfAbsent(session.sessionId) { ConcurrentLinkedQueue() }.add(message)
            }
    }

    fun pull(sessionId: String, after: String?, limit: Int): List<A2aEnvelope> {
        val queue = inbox.computeIfAbsent(sessionId) { ConcurrentLinkedQueue() }
        val pending = mutableListOf<A2aEnvelope>()
        while (pending.size < limit) {
            val next = queue.poll() ?: break
            pending += next
        }
        return if (after.isNullOrBlank()) {
            pending
        } else {
            val idx = pending.indexOfFirst { it.id == after }
            if (idx >= 0) pending.drop(idx + 1) else pending
        }
    }
}
