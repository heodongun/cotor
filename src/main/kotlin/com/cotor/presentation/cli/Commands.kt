package com.cotor.presentation.cli

/**
 * File overview for CheckpointCommand.
 *
 * This file belongs to the CLI presentation layer for interactive and command-driven flows.
 * It groups declarations around commands so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.data.config.ConfigRepository
import com.cotor.data.config.CotorProperties
import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.orchestrator.PipelineOrchestrator
import com.cotor.monitoring.PipelineRunSnapshot
import com.cotor.monitoring.PipelineRunStatus
import com.cotor.monitoring.PipelineRunTracker
import com.cotor.presentation.formatter.OutputFormatter
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.nio.file.Files
import java.util.Locale
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.writeText

class CheckpointCommand : CliktCommand(help = "Manage checkpoints.") {
    init {
        subcommands(CheckpointGCCommand())
    }

    override fun run() {
        // This command does nothing on its own, it only serves as a container for subcommands.
    }
}

/**
 * Base CLI command for Cotor
 */
abstract class CotorCommand : CliktCommand(), KoinComponent {
    val configPath by option("--config", "-c", help = "Path to configuration file")
        .path(mustExist = false)
        .default(Path("cotor.yaml"))

    val logLevel by option("--log-level", "-l", help = "Log level")
        .choice("DEBUG", "INFO", "WARN", "ERROR")
        .default("INFO")

    val debug by option("--debug", "-d", help = "Enable debug mode")
        .flag(default = false)
}

/**
 * Main CLI entry point
 */
class CotorCli : CliktCommand(
    name = "cotor",
    help = "AI CLI Master-Agent System",
    invokeWithoutSubcommand = true,
    printHelpOnEmptyArgs = false
) {
    private val short by option("--short", help = "Show 10-line cheat sheet").flag()

    override fun run() {
        val terminal = Terminal()
        if (short) {
            CheatSheetPrinter.printShort(terminal)
            return
        }
        if (currentContext.invokedSubcommand == null) {
            CheatSheetPrinter.printShort(terminal)
            terminal.println(
                when (CliHelpLanguage.resolve()) {
                    CliHelpLanguage.KOREAN -> "ℹ️  상세 도움말: cotor help"
                    CliHelpLanguage.ENGLISH -> "ℹ️  Detailed help: cotor help"
                }
            )
        }
    }
}

class HelpCommand(
    private val terminal: Terminal = Terminal()
) : CliktCommand(
    name = "help",
    help = "Show how to use Cotor in English or Korean"
) {
    private val languageCode by option("--lang", help = "Help output language")
        .choice("en", "ko")

    override fun run() {
        CheatSheetPrinter.printDetailed(
            terminal = terminal,
            language = CliHelpLanguage.resolve(languageCode)
        )
    }
}

/**
 * Initialize Cotor with default configuration
 */
class InitCommand :
    CliktCommand(
        name = "init",
        help = "Initialize Cotor with default configuration"
    ),
    KoinComponent {
    val configPath by option("--config", "-c", help = "Path to configuration file")
        .path(mustExist = false)
        .default(Path("cotor.yaml"))
    private val interactive by option("--interactive", "-i", help = "대화형으로 기본 파이프라인 설정").flag()
    private val starterTemplate by option(
        "--starter-template",
        help = "프로젝트용 starter config, pipeline, docs 스캐폴드를 생성"
    ).flag()
    private val terminal = Terminal()

    override fun run() {
        if (interactive) {
            writeInteractiveConfig()
            return
        }

        if (starterTemplate) {
            writeStarterProjectScaffold()
            return
        }

        val defaultConfig = """
version: "1.0"

# Agent definitions
agents:
  - name: example-agent
    pluginClass: com.cotor.data.plugin.EchoPlugin
    timeout: 30000
    parameters:
      key: value
    tags:
      - example

# Pipeline definitions
pipelines:
  - name: example-pipeline
    description: "Example sequential pipeline"
    executionMode: SEQUENTIAL
    stages:
      - id: step1
        agent:
          name: example-agent
        input: "test input"

# Security settings
security:
  useWhitelist: true
  allowedExecutables:
    - python3
    - node
  allowedDirectories:
${starterAllowedDirectoriesYaml()}

# Logging settings
logging:
  level: INFO
  file: cotor.log
  format: json

# Performance settings
performance:
  maxConcurrentAgents: 10
  coroutinePoolSize: 8
        """.trimIndent()

        configPath.writeText(defaultConfig)
        echo("Initialized cotor configuration at: $configPath")
    }

    private fun writeStarterProjectScaffold() {
        val root = configPath.parent ?: Path(".")
        val pipelinesDir = root.resolve("pipelines")
        val docsDir = root.resolve("docs")
        val starter = resolveStarterAgent()
        val projectName = root.name.takeUnless { it.isBlank() || it == "." } ?: "cotor-project"
        val pipelineName = buildPipelineName(projectName)
        val configText = buildStarterConfig(starter)
        val pipelineText = buildStarterPipeline(projectName, pipelineName, starter)
        val docsReadmeText = buildStarterReadme(projectName, pipelineName, starter)
        val pipelineDocText = buildStarterPipelineDoc(projectName, pipelineName, starter)

        root.createDirectories()
        pipelinesDir.createDirectories()
        docsDir.createDirectories()

        configPath.writeText(configText)
        pipelinesDir.resolve("default.yaml").writeText(pipelineText)
        docsDir.resolve("README.md").writeText(docsReadmeText)
        docsDir.resolve("PIPELINES.md").writeText(pipelineDocText)

        terminal.println(green("✅ Starter project scaffold created: $configPath"))
        terminal.println("   - Pipeline: ${pipelinesDir.resolve("default.yaml")}")
        terminal.println("   - Docs: ${docsDir.resolve("README.md")}, ${docsDir.resolve("PIPELINES.md")}")
        terminal.println()
        terminal.println(dim("Next steps:"))
        terminal.println(dim("  1. Review docs/README.md and docs/PIPELINES.md"))
        terminal.println(dim("  2. Run: cotor validate $pipelineName -c $configPath"))
        terminal.println(dim("  3. Execute: cotor run $pipelineName -c $configPath --output-format text"))
    }

    private fun buildStarterConfig(starter: StarterAgentSpec): String {
        val parameterSection = if (starter.parameterBlock.isBlank()) {
            ""
        } else {
            """
    parameters:
${starter.parameterBlock.prependIndent("      ")}
""".trimEnd()
        }

        return """
version: "1.0"

imports:
  - "pipelines/default.yaml"

agents:
  - name: ${starter.name}
    pluginClass: ${starter.pluginClass}
    timeout: 60000
$parameterSection
    tags:
      - starter
      - scaffold

security:
  useWhitelist: true
  allowedExecutables:
    - ${starter.executable}
  allowedDirectories:
${starterAllowedDirectoriesYaml()}

logging:
  level: INFO
  file: cotor.log
  format: json

performance:
  maxConcurrentAgents: 5
  coroutinePoolSize: 4
        """.trimIndent()
    }

    private fun buildStarterPipeline(projectName: String, pipelineName: String, starter: StarterAgentSpec): String {
        return """
pipelines:
  - name: $pipelineName
    description: "Starter workflow for $projectName"
    executionMode: SEQUENTIAL
    stages:
      - id: brief
        agent:
          name: ${starter.name}
        input: "Summarize the goal of $projectName and propose the next implementation step."
      - id: refine
        agent:
          name: ${starter.name}
        input: "Take the previous result and turn it into a concise execution checklist."
        """.trimIndent()
    }

    private fun buildStarterReadme(projectName: String, pipelineName: String, starter: StarterAgentSpec): String {
        return """
# $projectName

Starter project scaffold generated by `cotor init --starter-template`.

## Included Files

- `cotor.yaml`: root config that imports `pipelines/default.yaml`
- `pipelines/default.yaml`: starter sequential workflow named `$pipelineName`
- `docs/README.md`: project overview and common commands
- `docs/PIPELINES.md`: workflow intent and extension notes

## Starter Profile

- Project: `$projectName`
- Pipeline: `$pipelineName`
- Default agent: `${starter.name}` (`${starter.pluginClass}`)

## Common Commands

```bash
cotor validate $pipelineName -c cotor.yaml
cotor run $pipelineName -c cotor.yaml --output-format text
cotor template --list
```

## Customization Checklist

1. Replace the starter prompts in `pipelines/default.yaml`.
2. Add project-specific agents or plugin parameters in `cotor.yaml`.
3. Expand `docs/PIPELINES.md` as the workflow grows.
        """.trimIndent()
    }

    private fun buildStarterPipelineDoc(projectName: String, pipelineName: String, starter: StarterAgentSpec): String {
        return """
# Pipeline Notes

This scaffold was generated for `$projectName`.

## Default Workflow

- Pipeline name: `$pipelineName`
- Execution mode: `SEQUENTIAL`
- Agent: `${starter.name}`
- Stages:
  - `brief`: generate a short plan for the project
  - `refine`: turn the brief into an executable checklist

## Editing Guide

1. Update the stage prompts to match your task.
2. Change the execution mode if you need fan-out or review flows.
3. Add more stages once the project moves beyond the starter workflow.

## Validation Reminder

Run `cotor validate $pipelineName -c cotor.yaml` after changing the scaffold.
        """.trimIndent()
    }

    private fun buildPipelineName(projectName: String): String {
        val slug = projectName
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "starter" }
        return "$slug-starter"
    }

    private fun writeInteractiveConfig() {
        terminal.println("🧭 대화형 설정을 시작합니다. 기본값은 Enter 로 유지하세요.")

        val agentType = prompt(
            label = "에이전트 종류 선택 (codex, claude, gemini, openai, echo)",
            default = "codex",
            options = setOf("codex", "claude", "gemini", "openai", "echo")
        )
        val pipelineName = prompt("파이프라인 이름", "my-pipeline")
        val description = prompt("파이프라인 설명", "An interactive-generated pipeline")
        val executionMode = prompt(
            label = "실행 모드 (SEQUENTIAL, PARALLEL, DAG)",
            default = "SEQUENTIAL",
            options = setOf("SEQUENTIAL", "PARALLEL", "DAG")
        ).uppercase()
        val promptText = prompt("첫 단계 프롬프트", "Hello, Cotor!")

        val (agentName, pluginClass) = when (agentType.lowercase()) {
            "codex" -> "codex" to "com.cotor.data.plugin.CodexPlugin"
            "gemini" -> "gemini" to "com.cotor.data.plugin.GeminiPlugin"
            "openai" -> "openai" to "com.cotor.data.plugin.OpenAIPlugin"
            "echo" -> "echo-agent" to "com.cotor.data.plugin.EchoPlugin"
            else -> "claude" to "com.cotor.data.plugin.ClaudePlugin"
        }

        val pipelinesDir = Path("pipelines")
        if (!pipelinesDir.exists()) {
            try {
                Files.createDirectories(pipelinesDir)
                terminal.println("📁 'pipelines' 디렉토리를 생성했습니다.")
            } catch (e: Exception) {
                terminal.println(red("❌ 'pipelines' 디렉토리 생성에 실패했습니다: ${e.message}"))
                return
            }
        }

        val cotorYamlContent = """
version: "1.0"

imports:
  - "pipelines/default.yaml"

agents:
  - name: $agentName
    pluginClass: $pluginClass
    timeout: 60000

security:
  useWhitelist: true
  allowedExecutables:
    - $agentName
  allowedDirectories:
    - /usr/local/bin
    - /opt/homebrew/bin

logging:
  level: INFO
  file: cotor.log
  format: json

performance:
  maxConcurrentAgents: 5
  coroutinePoolSize: 4
        """.trimIndent()

        val pipelineYamlContent = """
pipelines:
  - name: $pipelineName
    description: "$description"
    executionMode: $executionMode
    stages:
      - id: $agentName-stage-1
        agent:
          name: $agentName
        input: "$promptText"
        """.trimIndent()

        val pipelinePath = pipelinesDir.resolve("default.yaml")
        configPath.writeText(cotorYamlContent)
        pipelinePath.writeText(pipelineYamlContent)

        terminal.println()
        terminal.println(green("✅ 대화형 설정이 완료되었습니다!"))
        terminal.println("   - 기본 설정: $configPath")
        terminal.println("   - 파이프라인: $pipelinePath")
        terminal.println()
        terminal.println("다음 명령을 시도해보세요:")
        terminal.println(bold("  cotor validate $pipelineName"))
        terminal.println(bold("  cotor run $pipelineName --output-format text"))
    }

    private fun prompt(label: String, default: String, options: Set<String>? = null): String {
        while (true) {
            terminal.print("$label [$default]: ")
            val input = readLine()?.trim()
            val result = if (input.isNullOrBlank()) default else input

            if (options == null || options.any { it.equals(result, ignoreCase = true) }) {
                return result
            }
            terminal.println(red("   잘못된 입력입니다. ${options.joinToString(", ")} 중 하나를 선택하세요."))
        }
    }

    private fun resolveStarterAgent(): StarterAgentSpec = resolveStarterAgentSpec()
}

/**
 * Run a pipeline
 */
class RunCommand : CotorCommand() {
    private val configRepository: ConfigRepository by inject()
    private val agentRegistry: AgentRegistry by inject()
    private val orchestrator: PipelineOrchestrator by inject()
    private val outputFormatters: Map<String, OutputFormatter> by inject()

    val pipelineName by argument("pipeline", help = "Name of pipeline to run")

    val outputFormat by option("--output-format", "-o", help = "Output format")
        .choice("json", "csv", "text")
        .default("json")

    override fun run() = runBlocking {
        try {
            // Load configuration
            val config = configRepository.loadConfig(configPath)

            // Register agents
            config.agents.forEach { agentRegistry.registerAgent(it) }

            // Find pipeline
            val pipeline = config.pipelines.find { it.name == pipelineName }
                ?: throw IllegalArgumentException("Pipeline not found: $pipelineName")

            // Execute pipeline
            echo("Executing pipeline: $pipelineName")
            val result = orchestrator.executePipeline(pipeline)

            // Format and output results
            val formatter = outputFormatters[outputFormat]
                ?: throw IllegalArgumentException("Unknown output format: $outputFormat")

            echo(formatter.format(result))
        } catch (e: Exception) {
            echo("Error: ${e.message}", err = true)
            throw e
        }
    }
}

/**
 * Show status of running and recent pipelines
 */
class StatusCommand :
    CliktCommand(
        name = "status",
        help = "Show status of running and recent pipelines"
    ),
    KoinComponent {
    private val terminal = Terminal()
    private val runTracker: PipelineRunTracker by inject()

    override fun run() {
        val active = runTracker.getActiveRuns()
        val recent = runTracker.getRecentRuns()
        val activeIds = active.map { it.pipelineId }.toSet()

        renderActive(active)
        terminal.println()
        renderRecent(recent.filterNot { run -> activeIds.contains(run.pipelineId) })
    }

    private fun renderActive(active: List<PipelineRunSnapshot>) {
        terminal.println(bold("📡 Active Pipelines"))
        if (active.isEmpty()) {
            terminal.println(dim("No pipelines are currently running."))
            return
        }

        active.forEach { run ->
            val elapsed = formatDuration(run.elapsed.toMillis())
            terminal.println(" - ${yellow("🔄")} ${run.pipelineName} ${dim("(${run.pipelineId.take(8)})")} • $elapsed")
        }
    }

    private fun renderRecent(recent: List<PipelineRunSnapshot>) {
        terminal.println(bold("📝 Recent Pipelines"))
        if (recent.isEmpty()) {
            terminal.println(dim("No recent executions captured yet."))
            terminal.println(dim("Runs are tracked automatically when pipelines start."))
            return
        }

        recent.forEach { run ->
            val icon = when (run.status) {
                PipelineRunStatus.COMPLETED -> green("✅")
                PipelineRunStatus.RUNNING -> yellow("🔄")
                PipelineRunStatus.FAILED -> red("❌")
            }
            val duration = run.totalDurationMs?.let { formatDuration(it) } ?: formatDuration(run.elapsed.toMillis())
            val counts = if (run.successCount != null && run.failureCount != null) {
                "success ${run.successCount}/${run.successCount + run.failureCount}"
            } else {
                null
            }
            val message = run.message?.let { " • ${it.take(80)}" } ?: ""

            val summary = buildString {
                append(" - $icon ${run.pipelineName} ${dim("(${run.pipelineId.take(8)})")}")
                append(" • $duration")
                counts?.let { append(" • $it") }
                append(message)
            }
            terminal.println(summary)
        }
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val remainderSeconds = seconds % 60

        return when {
            minutes > 0 -> "${minutes}m ${remainderSeconds}s"
            seconds > 0 -> "${seconds}s"
            else -> "${ms}ms"
        }
    }
}

/**
 * List registered agents
 */
class ListCommand :
    CliktCommand(
        name = "list",
        help = "List registered agents and pipelines"
    ),
    KoinComponent {
    private val configRepository: ConfigRepository by inject()
    private val agentRegistry: AgentRegistry by inject()

    private val configPath by option("--config", "-c", help = "Path to configuration file")
        .path(mustExist = false)
        .default(Path("cotor.yaml"))

    override fun run() = runBlocking {
        try {
            // Load configuration
            val config = configRepository.loadConfig(configPath)

            // Register agents
            config.agents.forEach { agentRegistry.registerAgent(it) }

            // List agents
            val agents = agentRegistry.getAllAgents()

            echo("Registered Agents (${agents.size}):")
            agents.forEach { agent ->
                echo("  - ${agent.name} (${agent.pluginClass})")
                echo("    Timeout: ${agent.timeout}ms")
                echo("    Tags: ${agent.tags.joinToString(", ")}")
            }
        } catch (e: Exception) {
            echo("Error: ${e.message}", err = true)
        }
    }
}

/**
 * Show version information
 */
class VersionCommand : CliktCommand(
    name = "version",
    help = "Show version information"
) {
    override fun run() {
        echo("Cotor version ${CotorProperties.version}")
        echo("Kotlin ${KotlinVersion.CURRENT}")
        echo("JVM ${System.getProperty("java.version")}")
    }
}

/**
 * Print shell completion scripts
 */
class CompletionCommand : CliktCommand(
    name = "completion",
    help = "Generate shell completion for bash/zsh/fish"
) {
    private val shell by argument("shell", help = "bash | zsh | fish")
        .choice("bash", "zsh", "fish")

    override fun run() {
        when (shell) {
            "bash" -> echo(bashCompletion)
            "zsh" -> echo(zshCompletion)
            "fish" -> echo(fishCompletion)
        }
        echo("\n# 위 내용을 쉘 설정에 추가하세요. 예) cotor completion $shell > /tmp/cotor.$shell && source /tmp/cotor.$shell")
        echo("# alias 추천: alias co='cotor'")
    }

    private val commonSubcommands = listOf(
        "init", "list", "run", "validate", "test", "template", "plugin", "dash", "interactive", "tui", "web", "resume", "checkpoint", "stats", "doctor", "status", "lint", "explain", "agent", "company", "auth", "version", "completion"
    )

    private val bashCompletion: String
        get() = """
_cotor_completions() {
  local cur prev
  COMPREPLY=()
  cur="${'$'}{COMP_WORDS[COMP_CWORD]}"
  prev="${'$'}{COMP_WORDS[COMP_CWORD-1]}"

  if [[ ${'$'}COMP_CWORD -eq 1 ]]; then
    COMPREPLY=( $(compgen -W "${commonSubcommands.joinToString(" ")}" -- "${'$'}cur") )
  fi
  return 0
}
complete -F _cotor_completions cotor
        """.trimIndent()

    private val zshCompletion: String
        get() = """
#compdef cotor
_cotor_completions() {
  local -a subcmds
  subcmds=(${commonSubcommands.joinToString(" ")})
  _arguments "1: :->subcmds"
  case ${'$'}state in
    subcmds)
      _describe 'command' subcmds
    ;;
  esac
}
_cotor_completions "${'$'}@"
        """.trimIndent()

    private val fishCompletion: String
        get() = """
complete -c cotor -n "__fish_is_first_arg" -f -a "${commonSubcommands.joinToString(" ")}"
        """.trimIndent()
}

/**
 * Simple cheat sheet printer used by --short flag
 */
enum class CliHelpLanguage {
    ENGLISH,
    KOREAN;

    companion object {
        fun resolve(
            requested: String? = null,
            environment: Map<String, String> = System.getenv(),
            locale: Locale = Locale.getDefault()
        ): CliHelpLanguage {
            val candidate = sequenceOf(
                requested,
                environment["COTOR_HELP_LANG"],
                environment["LANG"],
                environment["LC_ALL"],
                locale.toLanguageTag()
            )
                .mapNotNull { raw ->
                    raw
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.takeUnless { it.equals("C", ignoreCase = true) || it.equals("C.UTF-8", ignoreCase = true) || it.equals("POSIX", ignoreCase = true) }
                }
                .firstOrNull()
                ?: locale.toLanguageTag()
            val normalized = candidate.trim().lowercase()
            return if (normalized.startsWith("ko") || normalized.startsWith("kr")) {
                KOREAN
            } else {
                ENGLISH
            }
        }
    }
}

object CheatSheetPrinter {
    fun printShort(
        terminal: Terminal,
        language: CliHelpLanguage = CliHelpLanguage.resolve()
    ) {
        when (language) {
            CliHelpLanguage.KOREAN -> {
                terminal.println("🧭 Cotor 10줄 요약")
                terminal.println("--------------------")
                terminal.println("1) ./shell/install-global.sh  또는  ./gradlew shadowJar && ./shell/cotor version")
                terminal.println("2) cotor help  |  cotor help --lang en")
                terminal.println("3) cotor install  |  cotor update  |  cotor delete")
                terminal.println("4) cotor list  |  cotor template")
                terminal.println("5) cotor validate <pipeline> -c <yaml>")
                terminal.println("6) cotor run <pipeline> -c <yaml> --output-format text")
                terminal.println("7) cotor interactive  |  cotor dash -c <yaml>  |  cotor web")
                terminal.println("8) 예제 실행: examples/run-examples.sh")
                terminal.println("9) 문제 발생 시 cotor doctor, --debug, docs/QUICK_START.md")
                terminal.println("10) 자동완성/alias: cotor completion zsh|bash|fish")
            }

            CliHelpLanguage.ENGLISH -> {
                terminal.println("🧭 Cotor 10-line summary")
                terminal.println("------------------------")
                terminal.println("1) ./shell/install-global.sh  or  ./gradlew shadowJar && ./shell/cotor version")
                terminal.println("2) cotor help  |  cotor help --lang ko")
                terminal.println("3) cotor install  |  cotor update  |  cotor delete")
                terminal.println("4) cotor list  |  cotor template")
                terminal.println("5) cotor validate <pipeline> -c <yaml>")
                terminal.println("6) cotor run <pipeline> -c <yaml> --output-format text")
                terminal.println("7) cotor interactive  |  cotor dash -c <yaml>  |  cotor web")
                terminal.println("8) Example runs: examples/run-examples.sh")
                terminal.println("9) Troubleshooting: cotor doctor, --debug, docs/QUICK_START.md")
                terminal.println("10) Completion/alias: cotor completion zsh|bash|fish")
            }
        }
        terminal.println()
    }

    fun printDetailed(
        terminal: Terminal,
        language: CliHelpLanguage = CliHelpLanguage.resolve()
    ) {
        when (language) {
            CliHelpLanguage.KOREAN -> {
                terminal.println("🧭 Cotor 사용법")
                terminal.println("----------------")
                terminal.println("시작")
                terminal.println("  cotor                     대화형 TUI 채팅 시작")
                terminal.println("  cotor tui                 interactive의 별칭")
                terminal.println("  cotor help --lang en      영어 도움말 보기")
                terminal.println()
                terminal.println("프로젝트 준비")
                terminal.println("  cotor init --starter-template")
                terminal.println("  cotor list")
                terminal.println("  cotor template --list")
                terminal.println()
                terminal.println("파이프라인 실행")
                terminal.println("  cotor validate <pipeline> -c cotor.yaml")
                terminal.println("  cotor run <pipeline> -c cotor.yaml --output-format text")
                terminal.println("  cotor explain cotor.yaml <pipeline>")
                terminal.println()
                terminal.println("데스크톱 / 서버")
                terminal.println("  cotor install | update | delete")
                terminal.println("  cotor app-server --port 8787")
                terminal.println("  cotor web --open")
                terminal.println()
                terminal.println("문제 해결")
                terminal.println("  cotor doctor")
                terminal.println("  cotor <command> --debug")
                terminal.println("  docs/QUICK_START.md")
            }

            CliHelpLanguage.ENGLISH -> {
                terminal.println("🧭 Cotor Help")
                terminal.println("--------------")
                terminal.println("Start")
                terminal.println("  cotor                     Start the interactive TUI chat")
                terminal.println("  cotor tui                 Alias for interactive mode")
                terminal.println("  cotor help --lang ko      Show this guide in Korean")
                terminal.println()
                terminal.println("Project setup")
                terminal.println("  cotor init --starter-template")
                terminal.println("  cotor list")
                terminal.println("  cotor template --list")
                terminal.println()
                terminal.println("Run pipelines")
                terminal.println("  cotor validate <pipeline> -c cotor.yaml")
                terminal.println("  cotor run <pipeline> -c cotor.yaml --output-format text")
                terminal.println("  cotor explain cotor.yaml <pipeline>")
                terminal.println()
                terminal.println("Desktop / server")
                terminal.println("  cotor install | update | delete")
                terminal.println("  cotor app-server --port 8787")
                terminal.println("  cotor web --open")
                terminal.println()
                terminal.println("Troubleshooting")
                terminal.println("  cotor doctor")
                terminal.println("  cotor <command> --debug")
                terminal.println("  docs/QUICK_START.md")
            }
        }
        terminal.println()
    }
}
