package com.cotor.presentation.cli

import com.cotor.data.config.FileConfigRepository
import com.cotor.data.config.JsonParser
import com.cotor.data.config.YamlParser
import com.cotor.model.AgentConfig
import com.cotor.model.CotorConfig
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.switch
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.writeText

class AgentCommand(
    private val homeDirectoryProvider: () -> java.nio.file.Path = defaultHomeDirectoryProvider,
) : CliktCommand(
    name = "agent",
    help = "Manage agent presets and definitions"
) {
    init {
        subcommands(AgentAddCommand(homeDirectoryProvider), AgentListCommand())
    }

    override fun run() = Unit
}

private enum class InstallScope {
    GLOBAL,
    LOCAL,
}

private data class AgentPreset(
    val name: String,
    val pluginClass: String,
    val postInstallHint: String,
    val defaultTimeout: Long,
    val defaultParameters: Map<String, String> = emptyMap(),
    val supportsModelOverride: Boolean = false,
)

private val defaultHomeDirectoryProvider: () -> java.nio.file.Path = {
    Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize()
}

private val builtinPresets = listOf(
    AgentPreset(
        "gemini",
        "com.cotor.data.plugin.GeminiPlugin",
        "ensure 'gemini' is installed, then run 'cotor doctor'.",
        60000,
        defaultParameters = mapOf("model" to "gemini-3.0-flash"),
        supportsModelOverride = true
    ),
    AgentPreset(
        "claude",
        "com.cotor.data.plugin.ClaudePlugin",
        "ensure 'claude' is installed, then run 'cotor doctor'.",
        60000,
        defaultParameters = mapOf("model" to "claude-sonnet-4-20250514"),
        supportsModelOverride = true
    ),
    AgentPreset(
        "codex",
        "com.cotor.data.plugin.CodexPlugin",
        "ensure 'codex' is installed, then run 'cotor doctor'.",
        60000,
        defaultParameters = mapOf("model" to "gpt-5.3-codex-spark"),
        supportsModelOverride = true
    ),
    AgentPreset(
        "copilot",
        "com.cotor.data.plugin.CopilotPlugin",
        "ensure 'copilot' is installed, then run 'cotor doctor'.",
        60000,
        defaultParameters = mapOf("model" to "copilot"),
        supportsModelOverride = true
    ),
    AgentPreset(
        "opencode",
        "com.cotor.data.plugin.OpenCodePlugin",
        "ensure 'opencode' is installed, then run 'cotor doctor'.",
        60000,
        defaultParameters = mapOf("model" to "opencode-default"),
        supportsModelOverride = true
    ),
    AgentPreset(
        "qwen",
        "com.cotor.data.plugin.CommandPlugin",
        "ensure 'qwen' is installed, then run 'cotor doctor'.",
        60000,
        defaultParameters = mapOf("model" to "qwen3-coder"),
        supportsModelOverride = true
    ),
    AgentPreset(
        "qa",
        "com.cotor.data.plugin.QaVerificationPlugin",
        "ensure the repository exposes a supported test command (Gradle, Maven, npm/pnpm/yarn, cargo, go, pytest, or make) or override `parameters.commandJson` manually.",
        600000
    )
)

class AgentAddCommand(
    private val homeDirectoryProvider: () -> java.nio.file.Path = defaultHomeDirectoryProvider,
) : CliktCommand(
    name = "add",
    help = "Add an agent from preset into .cotor/agents"
) {
    private val terminal = Terminal()

    private val presetName by argument("preset")
    private val configPath by option("--config", "-c", help = "Path to base configuration file")
        .path(mustExist = false)
        .default(Path("cotor.yaml"))
    private val agentName by option("--name", help = "Override agent name")
    private val model by option("--model", help = "Override default model parameter")
    private val timeout by option("--timeout", help = "Override timeout (ms)").long()
    private val force by option("--force", help = "Overwrite existing .cotor/agents/<name>.yaml file").flag(default = false)
    private val dryRun by option("--dry-run", help = "Print planned YAML without writing").flag(default = false)
    private val yes by option("--yes", help = "Skip confirmation prompt").flag(default = false)
    private val installScope by option("--global", "--local", help = "Install preset globally (~/.cotor) or in project .cotor")
        .switch(
            "--global" to InstallScope.GLOBAL,
            "--local" to InstallScope.LOCAL,
        )
        .default(InstallScope.GLOBAL)

    override fun run() {
        val preset = builtinPresets.firstOrNull { it.name == presetName.lowercase() }
            ?: throw UsageError("Unknown preset '$presetName'. Available presets: ${builtinPresets.joinToString { it.name }}")

        val resolvedName = (agentName ?: preset.name).trim()
        require(resolvedName.isNotBlank()) { "Agent name must not be blank" }

        if (model != null && !preset.supportsModelOverride) {
            throw UsageError("Preset '$presetName' does not support --model")
        }
        val resolvedTimeout = timeout ?: preset.defaultTimeout
        val resolvedParameters = presetParameters(preset, model?.trim())

        val yaml = renderAgentYaml(
            AgentConfig(
                name = resolvedName,
                pluginClass = preset.pluginClass,
                timeout = resolvedTimeout,
                parameters = resolvedParameters
            )
        )

        val projectDir = configPath.parent ?: Path(".")
        val globalDir = homeDirectoryProvider().resolve(".cotor")
        val targetDir = when (installScope) {
            InstallScope.GLOBAL -> globalDir
            InstallScope.LOCAL -> projectDir.resolve(Path(".cotor"))
        }
        val outPath = targetDir.resolve(Path("agents", "$resolvedName.yaml"))

        if (outPath.exists() && !force) {
            throw UsageError("Agent '$resolvedName' already exists at $outPath. Use --force to overwrite.")
        }

        terminal.println(bold("Planned agent definition"))
        terminal.println(yaml)

        if (dryRun) {
            terminal.println(yellow("--dry-run enabled: no files were changed."))
            return
        }

        if (!yes && !confirm("Write $outPath ?")) {
            terminal.println(yellow("Cancelled."))
            return
        }

        outPath.parent.createDirectories()
        writeAtomically(outPath, yaml)

        terminal.println(green("✅ Added agent '$resolvedName' at $outPath"))
        terminal.println("Next step: cotor agent list -c $configPath")
        terminal.println("Tip: ${preset.postInstallHint}")
    }

    private fun confirm(message: String): Boolean {
        terminal.print("$message [y/N]: ")
        val answer = readlnOrNull()?.trim()?.lowercase()
        return answer == "y" || answer == "yes"
    }

    private fun writeAtomically(path: java.nio.file.Path, content: String) {
        val tmpPath = path.resolveSibling("${path.name}.tmp")
        tmpPath.writeText(content)
        java.nio.file.Files.move(
            tmpPath,
            path,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE
        )
    }

    private fun presetParameters(preset: AgentPreset, modelOverride: String?): Map<String, String> {
        if (!preset.supportsModelOverride) {
            return preset.defaultParameters
        }

        val defaultModel = preset.defaultParameters["model"].orEmpty()
        val resolvedModel = modelOverride?.takeIf { it.isNotBlank() } ?: defaultModel

        return when (preset.name) {
            "qwen" -> mapOf(
                "model" to resolvedModel,
                "argvJson" to "[\"qwen\",\"--model\",\"$resolvedModel\",\"{input}\"]"
            )
            else -> preset.defaultParameters + mapOf("model" to resolvedModel)
        }
    }

    private fun renderAgentYaml(agent: AgentConfig): String {
        val parametersBlock = if (agent.parameters.isEmpty()) {
            "    parameters: {}\n"
        } else {
            val parameterLines = agent.parameters.entries
                .sortedBy { it.key }
                .joinToString("\n") { (key, value) -> "      $key: ${yamlScalar(value)}" }
            "    parameters:\n$parameterLines\n"
        }

        return """
agents:
  - name: ${agent.name}
    pluginClass: ${agent.pluginClass}
    timeout: ${agent.timeout}
$parametersBlock
        """.trimIndent() + "\n"
    }

    private fun yamlScalar(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}

class AgentListCommand : CliktCommand(
    name = "list",
    help = "List merged agents from config + .cotor overrides"
) {
    private val configRepository = FileConfigRepository(YamlParser(), JsonParser())

    private val configPath by option("--config", "-c", help = "Path to base configuration file")
        .path(mustExist = false)
        .default(Path("cotor.yaml"))

    override fun run() {
        val config = runBlocking {
            if (!configPath.exists()) CotorConfig() else configRepository.loadConfig(configPath)
        }

        if (config.agents.isEmpty()) {
            echo("No agents defined.")
            return
        }

        echo("Agents (${config.agents.size}):")
        config.agents.sortedBy { it.name }.forEach { agent ->
            val model = agent.parameters["model"] ?: "-"
            echo("- ${agent.name} | ${agent.pluginClass} | timeout=${agent.timeout} | model=$model")
        }
    }
}
