package com.cotor.a2a

import java.util.LinkedHashMap

class A2aDedupeStore(
    private val maxEntries: Int = 1_000,
    private val entryTtlMs: Long = 10 * 60 * 1_000L,
    private val persistenceStore: A2aDedupePersistenceStore? = null
) {
    private data class StoredAck(
        val ack: A2aAck,
        val createdAt: Long
    )

    private val acknowledgements = LinkedHashMap<String, StoredAck>(16, 0.75f, true)

    init {
        persistenceStore?.load()?.forEach { entry ->
            acknowledgements[entry.dedupeKey] = StoredAck(
                ack = entry.ack,
                createdAt = entry.createdAt
            )
        }
    }

    @Synchronized
    fun remember(dedupeKey: String, ack: A2aAck, now: Long = System.currentTimeMillis()): Pair<Boolean, A2aAck> {
        prune(now)
        val existing = acknowledgements[dedupeKey]
        if (existing != null) {
            return false to existing.ack
        }
        acknowledgements[dedupeKey] = StoredAck(ack = ack, createdAt = now)
        prune(now)
        persist()
        return true to ack
    }

    @Synchronized
    internal fun size(): Int = acknowledgements.size

    @Synchronized
    private fun prune(now: Long) {
        var changed = false
        val iterator = acknowledgements.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.createdAt > entryTtlMs) {
                iterator.remove()
                changed = true
            }
        }
        while (acknowledgements.size > maxEntries) {
            val eldestKey = acknowledgements.entries.firstOrNull()?.key ?: break
            acknowledgements.remove(eldestKey)
            changed = true
        }
        if (changed) {
            persist()
        }
    }

    @Synchronized
    private fun persist() {
        persistenceStore?.save(
            acknowledgements.map { (key, value) ->
                A2aDedupePersistenceStore.SnapshotEntry(
                    dedupeKey = key,
                    ack = value.ack,
                    createdAt = value.createdAt
                )
            }
        )
    }
}
