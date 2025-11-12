package com.cotor.presentation.cli

import com.cotor.data.config.ConfigRepository
import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.orchestrator.PipelineOrchestrator
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
        println("""
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
        """.trimIndent())
    }
}
