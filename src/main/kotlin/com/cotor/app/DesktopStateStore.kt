package com.cotor.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
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
        isLenient = true
        coerceInputValues = true
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

        withStateFileLock {
            val raw = runCatching { stateFile.readText() }.getOrElse { return@withStateFileLock DesktopAppState() }
            decodeState(raw)?.also { decoded ->
                if (raw != json.encodeToString(DesktopAppState.serializer(), decoded)) {
                    saveLocked(decoded)
                }
                return@withStateFileLock decoded
            } ?: DesktopAppState()
        }
    }

    suspend fun save(state: DesktopAppState) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                withStateFileLock {
                    saveLocked(state)
                }
            }
        }
    }

    private fun stateFile(): Path = appHome().resolve("state.json")

    private fun lockFile(): Path = appHome().resolve("state.lock")

    private fun saveLocked(state: DesktopAppState) {
        val file = stateFile()
        // Always create the parent directories before the first write so the
        // app can start from a completely clean machine state.
        file.parent?.createDirectories()
        managedReposRoot().createDirectories()
        val payload = json.encodeToString(DesktopAppState.serializer(), state)
        val tempFile = file.resolveSibling("${file.fileName}.tmp")
        tempFile.writeText(payload)
        Files.move(
            tempFile,
            file,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    }

    private fun decodeStateOrNull(raw: String): DesktopAppState? =
        runCatching { json.decodeFromString<DesktopAppState>(raw) }.getOrNull()

    private fun decodeState(raw: String): DesktopAppState? {
        decodeStateOrNull(raw)?.let { return it }
        var candidate = raw.trimEnd()
        while (candidate.isNotEmpty()) {
            candidate = candidate.dropLast(1).trimEnd()
            decodeStateOrNull(candidate)?.let { return it }
        }
        return null
    }

    private fun <T> withStateFileLock(block: () -> T): T {
        val lockPath = lockFile()
        lockPath.parent?.createDirectories()
        return try {
            FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            ).use { channel ->
                channel.lock().use {
                    block()
                }
            }
        } catch (_: OverlappingFileLockException) {
            // The same JVM can legitimately re-enter state reads/writes while a
            // sibling coroutine still holds the process-wide file lock. In that
            // case the in-process mutexes already provide serialization, so we
            // only need the inter-process lock when it is available.
            block()
        }
    }
}

/**
 * Desktop data lives under the conventional macOS Application Support location
 * so it behaves like a native app instead of leaving metadata in arbitrary repos.
 */
fun defaultDesktopAppHome(): Path {
    val overriddenHome = sequenceOf(
        System.getenv("COTOR_DESKTOP_APP_HOME"),
        System.getenv("COTOR_APP_HOME")
    )
        .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .map { java.nio.file.Paths.get(it).toAbsolutePath().normalize() }
        .firstOrNull()
    if (overriddenHome != null) {
        return overriddenHome
    }
    val userHome = java.nio.file.Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize()
    return userHome
        .resolve("Library")
        .resolve("Application Support")
        .resolve("CotorDesktop")
}
