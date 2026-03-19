package com.cotor.presentation.cli

/**
 * File overview for SimpleCLI.
 *
 * This file belongs to the CLI presentation layer for interactive and command-driven flows.
 * It groups declarations around simple c l i so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import com.cotor.data.config.ConfigRepository
import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.orchestrator.PipelineOrchestrator
import com.cotor.validation.PipelineValidator
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.io.path.Path

/**
 * Simple CLI interface - codex style
 * Usage: cotor <pipeline-name>
 */
class SimpleCLI : KoinComponent {
    private val configRepository: ConfigRepository by inject()
    private val agentRegistry: AgentRegistry by inject()
    private val orchestrator: PipelineOrchestrator by inject()
    private val pipelineValidator: PipelineValidator by inject()

    fun run(args: Array<String>) = runBlocking {
        if (args.isEmpty()) {
            printHelp()
            return@runBlocking
        }

        val pipelineName = args[0]
        val configFile = if (args.size > 1) args[1] else "cotor.yaml"

        try {
            // Load config
            val config = configRepository.loadConfig(Path(configFile))

            // Register agents
            config.agents.forEach { agentRegistry.registerAgent(it) }

            // Find pipeline
            val pipeline = config.pipelines.find { it.name == pipelineName }
                ?: throw IllegalArgumentException("❌ Pipeline not found: $pipelineName")

            val validation = pipelineValidator.validate(pipeline)
            if (validation is com.cotor.validation.ValidationResult.Failure) {
                println("❌ Validation failed:")
                validation.errors.forEach { println("   - $it") }
                println("👉 수정 후 다시 실행하거나, --dry-run 으로 흐름만 확인하세요.")
                return@runBlocking
            }

            // Execute
            println("🚀 Running: $pipelineName")
            println()

            val result = orchestrator.executePipeline(pipeline)

            // Simple output
            println()
            println("✅ Completed in ${result.totalDuration}ms")
            println("   Success: ${result.successCount}/${result.totalAgents}")
            println()

            result.results.forEach { agentResult ->
                if (agentResult.isSuccess) {
                    println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    println("📦 ${agentResult.agentName} (${agentResult.duration}ms)")
                    println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    println(agentResult.output)
                    println()
                } else {
                    println("❌ ${agentResult.agentName}: ${agentResult.error}")
                }
            }
        } catch (e: Exception) {
            println("❌ Error: ${e.message}")
        }
    }

    private fun printHelp() {
        println(
            """
            🤖 Cotor - AI CLI Master-Agent System
            
            Usage:
              cotor <pipeline-name> [config-file]
              
            Examples:
              cotor compare-solutions
              cotor creative-collab test/creative-collab.yaml
              
            Quick Start:
              cotor init              # Create default config
              cotor list              # List available pipelines
              cotor web               # Start web UI
            """.trimIndent()
        )
    }
}
