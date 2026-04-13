package com.cotor.knowledge

import com.cotor.app.defaultDesktopAppHome
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class KnowledgeStore(
    private val appHomeProvider: () -> Path = { defaultDesktopAppHome() }
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun file(): Path =
        appHomeProvider().resolve("knowledge").resolve("memory.json")

    fun load(): KnowledgeSnapshot {
        val file = file()
        if (!file.exists()) {
            return KnowledgeSnapshot()
        }
        return runCatching {
            json.decodeFromString(KnowledgeSnapshot.serializer(), file.readText())
        }.getOrDefault(KnowledgeSnapshot())
    }

    fun save(snapshot: KnowledgeSnapshot) {
        val file = file()
        file.parent?.createDirectories()
        file.writeText(json.encodeToString(KnowledgeSnapshot.serializer(), snapshot))
    }
}

