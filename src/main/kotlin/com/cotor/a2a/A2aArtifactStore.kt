package com.cotor.a2a

import com.cotor.app.defaultDesktopAppHome
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class A2aArtifactStore(
    private val appHomeProvider: () -> Path = { defaultDesktopAppHome() },
    private val maxArtifacts: Int = 1_000
) {
    private val lock = Any()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun dir(): Path = appHomeProvider().resolve("a2a")
    private fun file(): Path = dir().resolve("artifacts.json")

    fun append(artifact: A2aArtifactRegistration) {
        synchronized(lock) {
            val current = loadAllLocked()
            val updated = (current + artifact).takeLast(maxArtifacts)
            persist(updated)
        }
    }

    fun list(
        tenant: A2aTenant,
        issueId: String? = null,
        taskId: String? = null,
        runId: String? = null
    ): List<A2aArtifactRegistration> = synchronized(lock) {
        loadAllLocked().filter { artifact ->
            artifact.tenant.companyId == tenant.companyId &&
                (issueId == null || artifact.issueId == issueId) &&
                (taskId == null || artifact.taskId == taskId) &&
                (runId == null || artifact.runId == runId)
        }
    }

    internal fun count(): Int = synchronized(lock) { loadAllLocked().size }

    private fun loadAllLocked(): List<A2aArtifactRegistration> {
        val path = file()
        if (!path.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(A2aArtifactRegistration.serializer()), path.readText())
        }.getOrDefault(emptyList())
    }

    private fun persist(artifacts: List<A2aArtifactRegistration>) {
        val root = dir()
        root.createDirectories()
        file().writeText(json.encodeToString(ListSerializer(A2aArtifactRegistration.serializer()), artifacts))
    }
}
