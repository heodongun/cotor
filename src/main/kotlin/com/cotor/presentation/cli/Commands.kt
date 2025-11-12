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
import com.cotor.model.CotorConfig
import com.cotor.presentation.formatter.OutputFormatter
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.writeText

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
    help = "AI CLI Master-Agent System"
) {
    override fun run() {
        // Show help if no subcommand specified
    }
}

/**
 * Initialize Cotor with default configuration
 */
class InitCommand : CotorCommand() {
    override fun run() {
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
 * Show status of running pipelines
 */
class StatusCommand : CotorCommand() {
    private val orchestrator: PipelineOrchestrator by inject()

    override fun run() {
        echo("Status: No active pipelines")
        // TODO: Implement active pipeline tracking
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
