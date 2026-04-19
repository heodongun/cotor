package com.cotor.a2a

import com.cotor.app.defaultDesktopAppHome
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class A2aSessionPersistenceStore(
    private val appHomeProvider: () -> Path = { defaultDesktopAppHome() }
) {
    @Serializable
    data class Snapshot(
        val sessions: List<A2aSession> = emptyList(),
        val inboxes: Map<String, List<A2aQueuedMessage>> = emptyMap(),
        val cursors: Map<String, Long> = emptyMap()
    )

    private val lock = Any()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun dir(): Path = appHomeProvider().resolve("a2a")
    private fun file(): Path = dir().resolve("sessions.json")

    fun load(): Snapshot = synchronized(lock) {
        val path = file()
        if (!path.exists()) return@synchronized Snapshot()
        runCatching { json.decodeFromString(Snapshot.serializer(), path.readText()) }.getOrDefault(Snapshot())
    }

    fun save(snapshot: Snapshot) {
        synchronized(lock) {
            val root = dir()
            root.createDirectories()
            file().writeText(json.encodeToString(Snapshot.serializer(), snapshot))
        }
    }
}
