package com.cotor.providers.github

import com.cotor.app.defaultDesktopAppHome
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class GitHubControlPlaneStore(
    private val appHomeProvider: () -> Path = { defaultDesktopAppHome() }
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun file(): Path =
        appHomeProvider().resolve("providers").resolve("github").resolve("state.json")

    @Synchronized
    fun load(): GitHubProviderState {
        val file = file()
        if (!file.exists()) {
            return GitHubProviderState()
        }
        return runCatching {
            json.decodeFromString(GitHubProviderState.serializer(), file.readText())
        }.getOrDefault(GitHubProviderState())
    }

    @Synchronized
    fun save(state: GitHubProviderState) {
        writeState(state)
    }

    @Synchronized
    fun update(transform: (GitHubProviderState) -> GitHubProviderState): GitHubProviderState {
        val next = transform(loadUnlocked())
        writeState(next)
        return next
    }

    private fun loadUnlocked(): GitHubProviderState {
        val file = file()
        if (!file.exists()) {
            return GitHubProviderState()
        }
        return runCatching {
            json.decodeFromString(GitHubProviderState.serializer(), file.readText())
        }.getOrDefault(GitHubProviderState())
    }

    private fun writeState(state: GitHubProviderState) {
        val file = file()
        file.parent?.createDirectories()
        val temp = Files.createTempFile(file.parent, "state", ".tmp")
        temp.writeText(json.encodeToString(GitHubProviderState.serializer(), state))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
