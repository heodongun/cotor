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
    val executable: String,
    val defaultTimeout: Long,
    val defaultModel: String,
)

private val defaultHomeDirectoryProvider: () -> java.nio.file.Path = {
    Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize()
}

private val builtinPresets = listOf(
    AgentPreset("gemini", "com.cotor.data.plugin.GeminiPlugin", "gemini", 60000, "gemini-3.0-flash"),
    AgentPreset("claude", "com.cotor.data.plugin.ClaudePlugin", "claude", 60000, "claude-sonnet-4-20250514"),
    AgentPreset("codex", "com.cotor.data.plugin.CodexPlugin", "codex", 60000, "gpt-5.3-codex-spark"),
    AgentPreset("copilot", "com.cotor.data.plugin.CopilotPlugin", "copilot", 60000, "copilot"),
    AgentPreset("opencode", "com.cotor.data.plugin.OpenCodePlugin", "opencode", 60000, "opencode-default"),
    AgentPreset("qwen", "com.cotor.data.plugin.CommandPlugin", "qwen", 60000, "qwen3-coder")
)

class AgentAddCommand(
    private val homeDirectoryProvider: () -> java.nio.file.Path = defaultHomeDirectoryProvider,
) : CliktCommand(
    name = "add",
    help = "Add an agent preset as a reusable YAML definition",
    epilog = """
        Presets: ${builtinPresets.joinToString { it.name }}

        Install location:
        - default (--global): ~/.cotor/agents/<name>.yaml
        - --local: <config directory>/.cotor/agents/<name>.yaml

        Examples:
        - cotor agent add gemini --local --yes
        - cotor agent add codex --model gpt-5.3-codex-spark --name reviewer
        - cotor agent add qwen --dry-run
    """.trimIndent()
) {
    private val terminal = Terminal()

    private val presetName by argument("preset")
    private val configPath by option("--config", "-c", help = "Base config path (used to resolve --local target directory)")
        .path(mustExist = false)
        .default(Path("cotor.yaml"))
    private val agentName by option("--name", help = "Output file + agent name override (default: preset name)")
    private val model by option("--model", help = "Model parameter override (default comes from preset)")
    private val timeout by option("--timeout", help = "Timeout override in milliseconds").long()
    private val force by option("--force", help = "Overwrite existing target file if present").flag(default = false)
    private val dryRun by option("--dry-run", help = "Print generated YAML only (does not write files)").flag(default = false)
    private val yes by option("--yes", help = "Skip interactive confirmation").flag(default = false)
    private val installScope by option("--global", "--local", help = "Install destination scope")
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

        val resolvedModel = (model ?: preset.defaultModel).trim()
        val resolvedTimeout = timeout ?: preset.defaultTimeout

        val yaml = renderAgentYaml(
            AgentConfig(
                name = resolvedName,
                pluginClass = preset.pluginClass,
                timeout = resolvedTimeout,
                parameters = presetParameters(preset.name, resolvedModel)
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
        terminal.println("Tip: ensure '${preset.executable}' is installed, then run 'cotor doctor'.")
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

    private fun presetParameters(preset: String, model: String): Map<String, String> {
        return when (preset) {
            "qwen" -> mapOf(
                "model" to model,
                "argvJson" to "[\"qwen\",\"--model\",\"$model\",\"{input}\"]"
            )
            else -> mapOf("model" to model)
        }
    }

    private fun renderAgentYaml(agent: AgentConfig): String {
        val parameterLines = agent.parameters.entries
            .sortedBy { it.key }
            .joinToString("\n") { (key, value) -> "      $key: ${yamlScalar(value)}" }

        return """
agents:
  - name: ${agent.name}
    pluginClass: ${agent.pluginClass}
    timeout: ${agent.timeout}
    parameters:
$parameterLines
        """.trimIndent() + "\n"
    }

    private fun yamlScalar(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}

class AgentListCommand : CliktCommand(
    name = "list",
    help = "List merged agents from base config and .cotor overrides",
    epilog = """
        Resolution order:
        1) agents in the base config file
        2) agent files under .cotor/agents (project + global)

        Example:
        - cotor agent list --config cotor.yaml
    """.trimIndent()
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
