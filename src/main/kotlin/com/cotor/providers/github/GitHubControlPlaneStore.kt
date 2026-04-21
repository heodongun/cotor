package com.cotor.providers.github

import com.cotor.app.defaultDesktopAppHome
import kotlinx.serialization.json.Json
import java.nio.file.Path
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

    fun load(): GitHubProviderState {
        val file = file()
        if (!file.exists()) {
            return GitHubProviderState()
        }
        return runCatching {
            json.decodeFromString(GitHubProviderState.serializer(), file.readText())
        }.getOrDefault(GitHubProviderState())
    }

    fun save(state: GitHubProviderState) {
        val file = file()
        file.parent?.createDirectories()
        file.writeText(json.encodeToString(GitHubProviderState.serializer(), state))
    }
}
