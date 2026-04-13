package com.cotor.runtime.actions

import com.cotor.app.defaultDesktopAppHome
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ActionStore(
    private val appHomeProvider: () -> Path = { defaultDesktopAppHome() }
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun actionsDir(): Path =
        appHomeProvider().resolve("runtime").resolve("actions")

    fun listSnapshots(): List<ActionLogSnapshot> {
        val dir = actionsDir()
        if (!dir.exists()) {
            return emptyList()
        }
        return dir.listDirectoryEntries("*.json")
            .sortedBy { it.fileName.toString() }
            .mapNotNull { path ->
                runCatching {
                    json.decodeFromString(ActionLogSnapshot.serializer(), path.readText())
                }.getOrNull()
            }
    }

    fun load(runId: String): ActionLogSnapshot? {
        val file = actionsDir().resolve("$runId.json")
        if (!file.exists()) {
            return null
        }
        return runCatching {
            json.decodeFromString(ActionLogSnapshot.serializer(), file.readText())
        }.getOrNull()
    }

    fun append(runId: String, record: ActionExecutionRecord): ActionLogSnapshot {
        val current = load(runId) ?: ActionLogSnapshot(runId = runId)
        val updated = current.copy(
            records = current.records + record,
            updatedAt = System.currentTimeMillis()
        )
        save(updated)
        return updated
    }

    fun replace(runId: String, recordId: String, transform: (ActionExecutionRecord) -> ActionExecutionRecord): ActionLogSnapshot {
        val current = load(runId) ?: ActionLogSnapshot(runId = runId)
        val updated = current.copy(
            records = current.records.map { record ->
                if (record.id == recordId) transform(record) else record
            },
            updatedAt = System.currentTimeMillis()
        )
        save(updated)
        return updated
    }

    private fun save(snapshot: ActionLogSnapshot) {
        val dir = actionsDir()
        dir.createDirectories()
        val file = dir.resolve("${snapshot.runId}.json")
        file.writeText(json.encodeToString(ActionLogSnapshot.serializer(), snapshot))
    }
}

