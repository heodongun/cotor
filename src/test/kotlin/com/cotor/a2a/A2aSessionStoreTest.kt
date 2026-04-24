package com.cotor.a2a

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files

class A2aSessionStoreTest : FunSpec({
    test("sessions, inbox messages, and cursors survive a new store instance") {
        val appHome = Files.createTempDirectory("a2a-session-store-test")
        val persistence = A2aSessionPersistenceStore { appHome }
        val store = A2aSessionStore(heartbeatIntervalMs = 15_000, persistenceStore = persistence)
        val now = 1_000L
        val session = store.open(
            A2aHelloRequest(
                agentId = "agent-builder",
                capabilities = listOf("task.assign"),
                tenant = A2aTenant("company-1")
            ),
            now = now
        )
        val envelope = A2aEnvelope(
            v = "a2a.v1",
            id = "message-1",
            type = "message.note",
            ts = now,
            ttlMs = 60_000,
            dedupeKey = "key-1",
            tenant = A2aTenant("company-1"),
            from = A2aParty(agentId = "agent-ceo"),
            body = buildJsonObject { put("title", "hello") }
        )
        store.enqueue(session.id, envelope, now = now + 1)

        val reloaded = A2aSessionStore(heartbeatIntervalMs = 15_000, persistenceStore = persistence)

        reloaded.session(session.id, now + 2)?.id shouldBe session.id
        val pulled = reloaded.pull(session.id, after = null, limit = 10, now = now + 3)
        pulled.size shouldBe 1
        pulled.single().envelope.id shouldBe "message-1"
        reloaded.pull(session.id, after = null, limit = 10, now = now + 4).size shouldBe 1
        val (removed, pending) = reloaded.acknowledge(session.id, throughCursor = pulled.single().cursor, now = now + 5)
        removed shouldBe 1
        pending shouldBe 0
        reloaded.pull(session.id, after = null, limit = 10, now = now + 6).size shouldBe 0
    }
})
