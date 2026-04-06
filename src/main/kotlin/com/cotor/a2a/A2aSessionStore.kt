package com.cotor.a2a

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class A2aSessionStore(
    private val heartbeatIntervalMs: Long = 15_000L
) {
    private val sessions = ConcurrentHashMap<String, A2aSession>()
    private val inboxes = ConcurrentHashMap<String, ArrayDeque<A2aQueuedMessage>>()
    private val cursors = ConcurrentHashMap<String, AtomicLong>()

    fun open(request: A2aHelloRequest, now: Long): A2aSession {
        val session = A2aSession(
            id = UUID.randomUUID().toString(),
            agentId = request.agentId.trim(),
            roleName = request.roleName?.trim(),
            executionAgentName = request.executionAgentName?.trim(),
            capabilities = request.capabilities.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            tenant = request.tenant,
            nonce = request.nonce,
            createdAt = now,
            updatedAt = now
        )
        sessions[session.id] = session
        inboxes.putIfAbsent(session.id, ArrayDeque())
        cursors.putIfAbsent(session.id, AtomicLong(0))
        return session
    }

    fun heartbeatIntervalMs(): Long = heartbeatIntervalMs

    fun sessionsForTenant(tenant: A2aTenant): List<A2aSession> =
        sessions.values.filter { it.tenant == tenant }

    fun enqueue(sessionId: String, envelope: A2aEnvelope, now: Long) {
        val cursor = cursors.getOrPut(sessionId) { AtomicLong(0) }.incrementAndGet()
        val queue = inboxes.getOrPut(sessionId) { ArrayDeque() }
        synchronized(queue) {
            queue.addLast(
                A2aQueuedMessage(
                    cursor = cursor,
                    receivedAt = now,
                    envelope = envelope
                )
            )
        }
        sessions.computeIfPresent(sessionId) { _, session -> session.copy(updatedAt = now) }
    }

    fun pull(sessionId: String, after: Long?, limit: Int, now: Long): List<A2aQueuedMessage> {
        val queue = inboxes[sessionId] ?: return emptyList()
        val messages = mutableListOf<A2aQueuedMessage>()
        synchronized(queue) {
            while (queue.isNotEmpty() && messages.size < limit) {
                val next = queue.removeFirst()
                if (after == null || next.cursor > after) {
                    messages += next
                }
            }
        }
        sessions.computeIfPresent(sessionId) { _, session -> session.copy(updatedAt = now) }
        return messages
    }

    fun session(sessionId: String): A2aSession? = sessions[sessionId]
}
