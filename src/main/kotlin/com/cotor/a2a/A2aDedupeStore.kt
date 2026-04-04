package com.cotor.a2a

import java.util.concurrent.ConcurrentHashMap

class A2aDedupeStore {
    private val acknowledgements = ConcurrentHashMap<String, A2aAck>()

    fun remember(dedupeKey: String, ack: A2aAck): Pair<Boolean, A2aAck> {
        val existing = acknowledgements.putIfAbsent(dedupeKey, ack)
        return if (existing == null) {
            true to ack
        } else {
            false to existing
        }
    }
}
