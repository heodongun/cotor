package com.cotor.a2a

import java.util.concurrent.ConcurrentHashMap

class A2aDedupeStore {
    private val ackByKey = ConcurrentHashMap<String, A2aAckResponse>()

    fun get(dedupeKey: String): A2aAckResponse? = ackByKey[dedupeKey]

    fun put(dedupeKey: String, ack: A2aAckResponse) {
        ackByKey.putIfAbsent(dedupeKey, ack)
    }
}
