package com.cotor.presentation.cli

import com.cotor.data.process.resolveExecutablePath
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

internal data class StarterAgentSpec(
    val name: String,
    val pluginClass: String,
    val executable: String,
    val parameterBlock: String = ""
)

internal fun resolveStarterAgentSpec(
    hasCommand: (String) -> Boolean = ::hasStarterCommand,
    codexReady: () -> Boolean = ::isCodexReadyForStarter,
    geminiReady: () -> Boolean = ::isGeminiReadyForStarter,
    claudeReady: () -> Boolean = ::isClaudeReadyForStarter,
    hasOpenAiApiKey: Boolean = !System.getenv("OPENAI_API_KEY").isNullOrBlank()
): StarterAgentSpec {
    val canUseCodex = hasCommand("codex") && codexReady()
    return when {
        canUseCodex -> StarterAgentSpec(
            name = "codex",
            pluginClass = "com.cotor.data.plugin.CodexPlugin",
            executable = "codex"
        )
        hasCommand("gemini") && geminiReady() -> StarterAgentSpec(
            name = "gemini",
            pluginClass = "com.cotor.data.plugin.GeminiPlugin",
            executable = "gemini"
        )
        hasCommand("claude") && claudeReady() -> StarterAgentSpec(
            name = "claude",
            pluginClass = "com.cotor.data.plugin.ClaudePlugin",
            executable = "claude",
            parameterBlock = """
model: claude-sonnet-4-20250514
            """.trimIndent()
        )
        hasOpenAiApiKey -> StarterAgentSpec(
            name = "openai",
            pluginClass = "com.cotor.data.plugin.OpenAIPlugin",
            executable = "java",
            parameterBlock = """
model: gpt-4o-mini
apiKeyEnv: OPENAI_API_KEY
            """.trimIndent()
        )
        else -> StarterAgentSpec(
            name = "example-agent",
            pluginClass = "com.cotor.data.plugin.EchoPlugin",
            executable = "echo"
        )
    }
}

internal fun canUseRealAiStarter(
    hasCommand: (String) -> Boolean = ::hasStarterCommand,
    codexReady: () -> Boolean = ::isCodexReadyForStarter,
    geminiReady: () -> Boolean = ::isGeminiReadyForStarter,
    claudeReady: () -> Boolean = ::isClaudeReadyForStarter,
    hasOpenAiApiKey: Boolean = !System.getenv("OPENAI_API_KEY").isNullOrBlank()
): Boolean {
    return (hasCommand("codex") && codexReady()) ||
        (hasCommand("gemini") && geminiReady()) ||
        (hasCommand("claude") && claudeReady()) ||
        hasOpenAiApiKey
}

internal fun starterAllowedDirectories(
    userHome: String = starterHomeDirectory().toString()
): List<String> {
    return buildList {
        if (userHome.isNotBlank()) {
            add("$userHome/.local/bin")
            add("$userHome/.opencode/bin")
            add("$userHome/bin")
        }
        add("/opt/homebrew/bin")
        add("/usr/local/bin")
    }.distinct()
}

internal fun starterAllowedDirectoriesYaml(): String {
    return starterAllowedDirectories().joinToString("\n") { "    - $it" }
}

internal fun defaultInteractiveConfigPath(
    environment: Map<String, String> = System.getenv()
): java.nio.file.Path {
    return starterHomeDirectory(environment)
        .resolve(".cotor")
        .resolve("interactive")
        .resolve("default")
        .resolve("cotor.yaml")
}

internal fun defaultInteractiveSaveDir(
    resolvedConfigPath: Path,
    environment: Map<String, String> = System.getenv()
): java.nio.file.Path {
    val packagedDefaultConfig = defaultInteractiveConfigPath(environment)
    val normalizedConfigPath = resolvedConfigPath.toAbsolutePath().normalize()
    return if (normalizedConfigPath == packagedDefaultConfig.toAbsolutePath().normalize()) {
        packagedDefaultConfig.parent
    } else {
        Paths.get(".cotor").resolve("interactive").resolve("default")
    }
}

internal fun resolveInteractiveConfigPath(
    requestedPath: Path,
    environment: Map<String, String> = System.getenv(),
    cwd: Path = Paths.get("").toAbsolutePath().normalize()
): Path {
    val defaultRelativePath = Paths.get("cotor.yaml")
    if (requestedPath != defaultRelativePath) {
        return requestedPath
    }
    val cwdResolvedPath = if (requestedPath.isAbsolute) requestedPath else cwd.resolve(requestedPath).normalize()
    if (Files.exists(cwdResolvedPath)) {
        return requestedPath
    }

    val packagedInstall = environment["COTOR_INSTALL_KIND"] == "packaged"
    return if (packagedInstall || !Files.isWritable(cwd)) {
        defaultInteractiveConfigPath(environment)
    } else {
        requestedPath
    }
}

internal fun hasStarterCommand(command: String): Boolean = resolveExecutablePath(command) != null

internal fun isCodexReadyForStarter(): Boolean {
    if (!hasStarterCommand("codex")) {
        return false
    }
    return runStatusProbe(listOf("codex", "login", "status"))
}

internal fun isGeminiReadyForStarter(
    hasCommand: (String) -> Boolean = ::hasStarterCommand,
    environment: Map<String, String> = System.getenv(),
    userHome: Path = starterHomeDirectory(environment)
): Boolean {
    if (!hasCommand("gemini")) {
        return false
    }
    if (listOf("GEMINI_API_KEY", "GOOGLE_API_KEY", "GOOGLE_GENAI_USE_VERTEXAI", "GOOGLE_GENAI_USE_GCA")
            .any { !environment[it].isNullOrBlank() }
    ) {
        return true
    }

    val geminiHome = userHome.resolve(".gemini")
    return Files.exists(geminiHome.resolve("oauth_creds.json")) ||
        Files.exists(geminiHome.resolve("google_accounts.json"))
}

internal fun isClaudeReadyForStarter(
    hasCommand: (String) -> Boolean = ::hasStarterCommand,
    environment: Map<String, String> = System.getenv(),
    userHome: Path = starterHomeDirectory(environment)
): Boolean {
    if (!hasCommand("claude")) {
        return false
    }
    if (listOf("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN").any { !environment[it].isNullOrBlank() }) {
        return true
    }

    return Files.exists(userHome.resolve(".claude.json")) ||
        Files.exists(userHome.resolve(".claude").resolve("settings.json"))
}

private fun starterHomeDirectory(environment: Map<String, String> = System.getenv()): Path {
    val home = environment["HOME"]
        ?.takeIf { it.isNotBlank() }
        ?: System.getProperty("user.home")
        ?: "."
    return Paths.get(home).toAbsolutePath().normalize()
}

private fun runStatusProbe(command: List<String>, timeoutSeconds: Long = 3): Boolean {
    return runCatching {
        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
        } finally {
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
        }
    }.getOrDefault(false)
}
