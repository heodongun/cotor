package com.cotor.a2a

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class A2aSessionStore(
    private val heartbeatIntervalMs: Long = 15_000L,
    private val sessionTtlMs: Long = heartbeatIntervalMs * 4,
    private val persistenceStore: A2aSessionPersistenceStore? = null
) {
    private val sessions = ConcurrentHashMap<String, A2aSession>()
    private val inboxes = ConcurrentHashMap<String, ArrayDeque<A2aQueuedMessage>>()
    private val cursors = ConcurrentHashMap<String, AtomicLong>()

    init {
        persistenceStore?.load()?.let { snapshot ->
            sessions.putAll(snapshot.sessions.associateBy { it.id })
            inboxes.putAll(snapshot.inboxes.mapValues { ArrayDeque(it.value) })
            cursors.putAll(snapshot.cursors.mapValues { AtomicLong(it.value) })
        }
    }

    fun open(request: A2aHelloRequest, now: Long): A2aSession {
        prune(now)
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
        persist()
        return session
    }

    fun heartbeatIntervalMs(): Long = heartbeatIntervalMs

    fun sessionsForTenant(tenant: A2aTenant): List<A2aSession> =
        sessionsForTenant(tenant, System.currentTimeMillis())

    fun sessionsForTenant(tenant: A2aTenant, now: Long): List<A2aSession> {
        prune(now)
        return sessions.values.filter { it.tenant == tenant }
    }

    fun enqueue(sessionId: String, envelope: A2aEnvelope, now: Long) {
        prune(now)
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
        persist()
    }

    fun pull(sessionId: String, after: Long?, limit: Int, now: Long): List<A2aQueuedMessage> {
        prune(now)
        val queue = inboxes[sessionId] ?: return emptyList()
        val messages = mutableListOf<A2aQueuedMessage>()
        synchronized(queue) {
            while (queue.isNotEmpty() && messages.size < limit) {
                val next = queue.first()
                if (after != null && next.cursor <= after) {
                    queue.removeFirst()
                    continue
                }
                messages += queue.removeFirst()
            }
        }
        sessions.computeIfPresent(sessionId) { _, session -> session.copy(updatedAt = now) }
        persist()
        return messages
    }

    fun session(sessionId: String): A2aSession? = session(sessionId, System.currentTimeMillis())

    fun session(sessionId: String, now: Long): A2aSession? {
        prune(now)
        return sessions[sessionId]
    }

    fun touch(sessionId: String, now: Long): A2aSession {
        prune(now)
        val updated = sessions.compute(sessionId) { _, session ->
            require(session != null) { "Unknown session: $sessionId" }
            session.copy(updatedAt = now)
        }
        persist()
        return requireNotNull(updated)
    }

    @Synchronized
    private fun prune(now: Long) {
        val expiredIds = sessions.values
            .filter { now - it.updatedAt > sessionTtlMs }
            .map { it.id }
        expiredIds.forEach { sessionId ->
            sessions.remove(sessionId)
            inboxes.remove(sessionId)
            cursors.remove(sessionId)
        }
        if (expiredIds.isNotEmpty()) {
            persist()
        }
    }

    @Synchronized
    private fun persist() {
        persistenceStore?.save(
            A2aSessionPersistenceStore.Snapshot(
                sessions = sessions.values.toList(),
                inboxes = inboxes.mapValues { (_, queue) -> queue.toList() },
                cursors = cursors.mapValues { (_, cursor) -> cursor.get() }
            )
        )
    }
}
