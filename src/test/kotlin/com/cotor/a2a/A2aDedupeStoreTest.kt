package com.cotor.a2a

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class A2aDedupeStoreTest : FunSpec({
    test("dedupe store prunes entries older than ttl") {
        val store = A2aDedupeStore(maxEntries = 10, entryTtlMs = 100)
        val oldAck = A2aAck(messageId = "old", dedupeStatus = "accepted", serverTs = 1)
        val newAck = A2aAck(messageId = "new", dedupeStatus = "accepted", serverTs = 2)

        store.remember("old", oldAck, now = 0)
        store.remember("new", newAck, now = 150)

        store.size() shouldBe 1
        store.remember("old", A2aAck(messageId = "old-2", dedupeStatus = "accepted", serverTs = 3), now = 151).first shouldBe true
    }

    test("dedupe store keeps only the latest bounded number of entries") {
        val store = A2aDedupeStore(maxEntries = 2, entryTtlMs = 10_000)

        store.remember("a", A2aAck(messageId = "a", dedupeStatus = "accepted", serverTs = 1), now = 1)
        store.remember("b", A2aAck(messageId = "b", dedupeStatus = "accepted", serverTs = 2), now = 2)
        store.remember("c", A2aAck(messageId = "c", dedupeStatus = "accepted", serverTs = 3), now = 3)

        store.size() shouldBe 2
        store.remember("a", A2aAck(messageId = "a-2", dedupeStatus = "accepted", serverTs = 4), now = 4).first shouldBe true
    }

    test("dedupe store persists entries across recreation") {
        val appHome = Files.createTempDirectory("a2a-dedupe-store-test")
        val persistence = A2aDedupePersistenceStore { appHome }
        val first = A2aDedupeStore(maxEntries = 10, entryTtlMs = 10_000, persistenceStore = persistence)

        first.remember("company-1:key-1", A2aAck(messageId = "m1", dedupeStatus = "accepted", serverTs = 1), now = 1)

        val second = A2aDedupeStore(maxEntries = 10, entryTtlMs = 10_000, persistenceStore = persistence)
        second.size() shouldBe 1
        second.remember("company-1:key-1", A2aAck(messageId = "m2", dedupeStatus = "accepted", serverTs = 2), now = 2).first shouldBe false
    }
})
