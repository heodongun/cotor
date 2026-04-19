package com.cotor.a2a

import com.cotor.app.defaultDesktopAppHome
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class A2aDedupePersistenceStore(
    private val appHomeProvider: () -> Path = { defaultDesktopAppHome() }
) {
    @Serializable
    data class SnapshotEntry(
        val dedupeKey: String,
        val ack: A2aAck,
        val createdAt: Long
    )

    private val lock = Any()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun dir(): Path = appHomeProvider().resolve("a2a")
    private fun file(): Path = dir().resolve("dedupe.json")

    fun load(): List<SnapshotEntry> = synchronized(lock) {
        val path = file()
        if (!path.exists()) return@synchronized emptyList()
        runCatching {
            json.decodeFromString(ListSerializer(SnapshotEntry.serializer()), path.readText())
        }.getOrDefault(emptyList())
    }

    fun save(entries: List<SnapshotEntry>) {
        synchronized(lock) {
            val root = dir()
            root.createDirectories()
            file().writeText(json.encodeToString(ListSerializer(SnapshotEntry.serializer()), entries))
        }
    }
}
