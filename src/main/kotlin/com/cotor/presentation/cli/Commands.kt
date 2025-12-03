package com.cotor.presentation.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import com.cotor.data.config.ConfigRepository
import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.orchestrator.PipelineOrchestrator
import com.cotor.presentation.formatter.OutputFormatter
import com.cotor.monitoring.PipelineRunSnapshot
import com.cotor.monitoring.PipelineRunStatus
import com.cotor.monitoring.PipelineRunTracker
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.io.path.Path
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
    init {
        subcommands(CheckpointCommand())
    }

    private val short by option("--short", help = "Show 10-line cheat sheet").flag()

    override fun run() {
        val terminal = Terminal()
        if (short) {
            CheatSheetPrinter.print(terminal)
            return
        }
        if (currentContext.invokedSubcommand == null) {
            CheatSheetPrinter.print(terminal)
            terminal.println("ℹ️  상세 도움말: cotor --help")
        }
    }
}

/**
 * Initialize Cotor with default configuration
 */
class InitCommand : CotorCommand() {
    private val interactive by option("--interactive", "-i", help = "대화형으로 기본 파이프라인 설정").flag()
    private val terminal = Terminal()

    override fun run() {
        if (interactive) {
            writeInteractiveConfig()
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
          pluginClass: com.cotor.data.plugin.EchoPlugin
        input: "test input"

# Security settings
security:
  useWhitelist: true
  allowedExecutables:
    - python3
    - node
  allowedDirectories:
    - /usr/local/bin

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

    private fun writeInteractiveConfig() {
        terminal.println("🧭 대화형 설정을 시작합니다. 기본값은 Enter 로 유지하세요.")

        val pipelineName = prompt("파이프라인 이름", "my-pipeline")
        val description = prompt("파이프라인 설명", "Interactive pipeline")
        val agentName = prompt("에이전트 이름", "claude")
        val pluginClass = prompt("플러그인 클래스", "com.cotor.data.plugin.ClaudePlugin")
        val timeout = prompt("타임아웃(ms)", "60000").toLongOrNull() ?: 60000L
        val executionMode = prompt("실행 모드(SEQUENTIAL/PARALLEL/DAG)", "SEQUENTIAL").uppercase()
        val promptText = prompt("프롬프트 내용", "Hello from Cotor!")

        val yaml = """
version: "1.0"

agents:
  - name: $agentName
    pluginClass: $pluginClass
    timeout: $timeout

pipelines:
  - name: $pipelineName
    description: "$description"
    executionMode: $executionMode
    stages:
      - id: ${agentName}-stage-1
        agent:
          name: $agentName
        input: "$promptText"

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

        configPath.writeText(yaml)
        terminal.println("✅ 대화형 설정이 생성되었습니다: $configPath")
        terminal.println("다음 명령을 시도해보세요:")
        terminal.println("  cotor validate $pipelineName -c $configPath")
        terminal.println("  cotor run $pipelineName -c $configPath --output-format text")
    }

    private fun prompt(label: String, default: String): String {
        terminal.print("$label [$default]: ")
        val input = readLine()?.trim()
        return if (input.isNullOrBlank()) default else input
    }
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
class StatusCommand : CotorCommand() {
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
            terminal.println(" - ${yellow("🔄")} ${run.pipelineName} ${dim("(${run.pipelineId.take(8)})")} • ${elapsed}")
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
            } else null
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
class ListCommand : CotorCommand() {
    private val configRepository: ConfigRepository by inject()
    private val agentRegistry: AgentRegistry by inject()

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
        echo("Cotor version 1.0.0")
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
        "init", "list", "run", "validate", "template", "dash", "web", "resume", "checkpoint", "stats", "doctor", "version", "completion"
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
object CheatSheetPrinter {
    fun print(terminal: Terminal) {
        terminal.println("🧭 Cotor 10줄 요약")
        terminal.println("--------------------")
        terminal.println("1) ./shell/install-global.sh  또는  ./gradlew shadowJar && ./shell/cotor version")
        terminal.println("2) cotor init  (또는 cotor init --interactive)")
        terminal.println("3) cotor list  |  cotor template")
        terminal.println("4) cotor validate <pipeline> -c <yaml>")
        terminal.println("5) cotor run <pipeline> -c <yaml> --output-format text")
        terminal.println("6) cotor dash -c <yaml>  |  cotor web")
        terminal.println("7) 예제 실행: examples/run-examples.sh")
        terminal.println("8) Claude 연동: ./shell/install-claude-integration.sh")
        terminal.println("9) 문제 발생 시 cotor doctor, --debug, docs/QUICK_START.md")
        terminal.println("10) 자동완성/alias: cotor completion zsh|bash|fish")
        terminal.println()
    }
}
