package com.cotor.runtime.durable

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DurableRuntimeStore(
    private val rootDir: Path = defaultDurableRuntimeRoot()
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun runsDir(): Path = rootDir.resolve("runs")

    init {
        runsDir().createDirectories()
    }

    fun listRuns(): List<DurableRunSnapshot> =
        Files.list(runsDir()).use { paths ->
            paths.toList()
                .filter { it.fileName.toString().endsWith(".json") }
                .mapNotNull { path ->
                    runCatching { json.decodeFromString<DurableRunSnapshot>(path.readText()) }.getOrNull()
                }
                .sortedByDescending { snapshot -> snapshot.updatedAt }
        }

    fun loadRun(runId: String): DurableRunSnapshot? {
        val path = runPath(runId)
        if (!path.exists()) return null
        return runCatching { json.decodeFromString<DurableRunSnapshot>(path.readText()) }.getOrNull()
    }

    fun saveRun(snapshot: DurableRunSnapshot) {
        runPath(snapshot.runId).writeText(json.encodeToString(snapshot))
    }

    fun deleteRun(runId: String): Boolean =
        runCatching { Files.deleteIfExists(runPath(runId)) }.getOrDefault(false)

    private fun runPath(runId: String): Path = runsDir().resolve("$runId.json")
}

private fun defaultDurableRuntimeRoot(): Path {
    val overriddenHome = sequenceOf(
        System.getenv("COTOR_DESKTOP_APP_HOME"),
        System.getenv("COTOR_APP_HOME")
    )
        .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .map { Paths.get(it).toAbsolutePath().normalize() }
        .firstOrNull()
    val appHome = overriddenHome
        ?: Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize()
            .resolve("Library")
            .resolve("Application Support")
            .resolve("CotorDesktop")
    return appHome.resolve("runtime")
}
