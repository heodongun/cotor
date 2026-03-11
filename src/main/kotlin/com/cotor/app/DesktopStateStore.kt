package com.cotor.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Persists the lightweight desktop state into Application Support.
 *
 * The store is intentionally forgiving: if the JSON file becomes unreadable we
 * fall back to an empty state instead of blocking the whole desktop app from booting.
 */
class DesktopStateStore(
    private val appHomeProvider: () -> Path = { defaultDesktopAppHome() },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    // A single process can finish multiple background runs nearly at once, so writes
    // need to be serialized even though the backing file is small.
    private val mutex = Mutex()

    fun appHome(): Path = appHomeProvider()

    fun managedReposRoot(): Path = appHome().resolve("ManagedRepos")

    suspend fun load(): DesktopAppState = withContext(Dispatchers.IO) {
        val stateFile = stateFile()
        if (!stateFile.exists()) {
            return@withContext DesktopAppState()
        }

        runCatching { json.decodeFromString<DesktopAppState>(stateFile.readText()) }
            .getOrElse { DesktopAppState() }
    }

    suspend fun save(state: DesktopAppState) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val file = stateFile()
                // Always create the parent directories before the first write so the
                // app can start from a completely clean machine state.
                file.parent?.createDirectories()
                managedReposRoot().createDirectories()
                file.writeText(json.encodeToString(DesktopAppState.serializer(), state))
            }
        }
    }

    private fun stateFile(): Path = appHome().resolve("state.json")
}

/**
 * Desktop data lives under the conventional macOS Application Support location
 * so it behaves like a native app instead of leaving metadata in arbitrary repos.
 */
fun defaultDesktopAppHome(): Path {
    val userHome = java.nio.file.Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize()
    return userHome
        .resolve("Library")
        .resolve("Application Support")
        .resolve("CotorDesktop")
}
