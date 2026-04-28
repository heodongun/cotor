package com.cotor.presentation.cli

/**
 * File overview for EnhancedRunCommand.
 *
 * This file belongs to the CLI presentation layer for interactive and command-driven flows.
 * It groups declarations around enhanced commands so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.data.config.ConfigRepository
import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.orchestrator.PipelineOrchestrator
import com.cotor.error.ErrorMessages
import com.cotor.error.UserFriendlyError
import com.cotor.event.*
import com.cotor.event.EventBus
import com.cotor.model.PipelineContext
import com.cotor.monitoring.PipelineMonitor
import com.cotor.monitoring.StageState
import com.cotor.monitoring.TimelineCollector
import com.cotor.presentation.formatter.OutputFormatter
import com.cotor.presentation.timeline.StageTimelineEntry
import com.cotor.presentation.timeline.StageTimelineState
import com.cotor.runtime.durable.DurableRuntimeFlags
import com.cotor.validation.PipelineValidator
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
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
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.math.roundToInt

/**
 * Enhanced Run Command with monitoring, validation, and dry-run support
 */
class EnhancedRunCommand :
    CliktCommand(
        name = "run",
        help = "Run a pipeline with real-time monitoring"
    ),
    KoinComponent {
    private val configRepository: ConfigRepository by inject()
    private val agentRegistry: AgentRegistry by inject()
    private val orchestrator: PipelineOrchestrator by inject()
    private val outputFormatters: Map<String, OutputFormatter> by inject()
    private val eventBus: EventBus by inject()
    private val timelineCollector by lazy { TimelineCollector(eventBus) }
    private val terminal = Terminal()

    private val configPath by option("--config", "-c", help = "Path to configuration file")
        .path(mustExist = false)
        .default(Path("cotor.yaml"))

    val pipelineName by argument("pipeline", help = "Name of pipeline to run").optional()

    val watch by option("--watch", "-w", help = "Watch mode with live progress updates")
        .flag(default = true)

    val verbose by option("--verbose", "-v", help = "Verbose output with detailed logging")
        .flag(default = false)

    val dryRun by option("--dry-run", help = "Simulate pipeline execution without running")
        .flag(default = false)

    val outputFormat by option("--output-format", "-o", help = "Output format")
        .choice("json", "csv", "text")
        .default("json")

    val fromStage by option("--from-stage", help = "Resume execution from a specific stage ID")

    override fun run() = runBlocking {
        try {
            // Check if config exists
            if (!configPath.exists()) {
                throw ErrorMessages.configNotFound(configPath.toString())
            }

            // Load configuration
            val config = configRepository.loadConfig(configPath)

            // Register agents
            config.agents.forEach { agentRegistry.registerAgent(it) }

            // Find pipeline
            val selectedPipelineName = pipelineName ?: config.pipelines.singleOrNull()?.name
                ?: throw ErrorMessages.pipelineNotFound(
                    pipelineName ?: "<unspecified>",
                    config.pipelines.map { it.name }
                )
            val pipeline = config.pipelines.find { it.name == selectedPipelineName }
                ?: throw ErrorMessages.pipelineNotFound(
                    selectedPipelineName,
                    config.pipelines.map { it.name }
                )

            // Validate pipeline
            val validator = PipelineValidator(agentRegistry, com.cotor.data.plugin.ReflectionPluginLoader(org.slf4j.LoggerFactory.getLogger(com.cotor.data.plugin.ReflectionPluginLoader::class.java)))
            val validationResult = validator.validate(pipeline)

            if (validationResult.isFailure) {
                val failure = validationResult as com.cotor.validation.ValidationResult.Failure
                throw ErrorMessages.validationFailed(failure.errors)
            }

            // Show warnings
            val success = validationResult as com.cotor.validation.ValidationResult.Success
            if (success.warnings.isNotEmpty() && verbose) {
                echo("⚠️  Warnings:")
                success.warnings.forEach { echo("   - $it") }
                echo()
            }

            // Dry-run mode
            if (dryRun) {
                val estimate = validator.estimateDuration(pipeline)
                echo(estimate.formatEstimate())
                return@runBlocking
            }

            val monitor = if (watch) PipelineMonitor(pipeline, verbose) else null
            val monitorSubscriptions = mutableListOf<EventSubscription>()

            try {
                if (monitor != null) {
                    monitorSubscriptions += eventBus.subscribe(StageStartedEvent::class) { event ->
                        val stageEvent = event as StageStartedEvent
                        monitor.updateStageState(stageEvent.stageId, StageState.RUNNING)
                        if (verbose) {
                            monitor.logVerbose("Stage started: ${stageEvent.stageId}")
                        }
                    }

                    monitorSubscriptions += eventBus.subscribe(StageCompletedEvent::class) { event ->
                        val stageEvent = event as StageCompletedEvent
                        monitor.updateStageState(stageEvent.stageId, StageState.COMPLETED)
                        if (verbose) {
                            monitor.logVerbose("Stage completed: ${stageEvent.stageId}")
                        }
                    }

                    monitorSubscriptions += eventBus.subscribe(StageFailedEvent::class) { event ->
                        val stageEvent = event as StageFailedEvent
                        monitor.updateStageState(stageEvent.stageId, StageState.FAILED, stageEvent.error.message)
                        if (verbose) {
                            monitor.logVerbose("Stage failed: ${stageEvent.stageId} - ${stageEvent.error.message}")
                        }
                    }
                }

                terminal.println(bold("🚀 Executing pipeline: ") + cyan(pipeline.name))
                if (verbose) {
                    terminal.println(dim("   Mode: ${pipeline.executionMode} • Stages: ${pipeline.stages.size}"))
                    terminal.println()
                }

                val pipelineContext = PipelineContext(
                    pipelineId = UUID.randomUUID().toString(),
                    pipelineName = pipeline.name,
                    totalStages = pipeline.stages.size
                ).also { context ->
                    context.metadata["configPath"] = configPath.toString()
                    if (DurableRuntimeFlags.isEnabled()) {
                        DurableRuntimeFlags.enable(context)
                    }
                }
                val timelineResult = timelineCollector.runWithTimeline(pipeline.name) {
                    orchestrator.executePipeline(pipeline, fromStage, pipelineContext)
                }
                val pipelineDuration = timelineResult.totalDurationMs ?: timelineResult.result.totalDuration
                val result = timelineResult.result.copy(totalDuration = pipelineDuration)
                val timeline = timelineResult.timeline

                monitor?.showSummary(pipelineDuration)
                renderTimeline(timeline)
                renderResultSummary(result, pipeline)

                val formatter = outputFormatters[outputFormat]
                    ?: throw IllegalArgumentException("Unknown output format: $outputFormat")

                terminal.println()
                terminal.println(bold("📄 Aggregated Output"))
                echo(formatter.format(result))
            } finally {
                monitorSubscriptions.forEach { eventBus.unsubscribe(it) }
            }
        } catch (e: UserFriendlyError) {
            echo(e.message, err = true)
            throw e
        } catch (e: Exception) {
            echo("Error: ${e.message}", err = true)
            if (verbose) {
                e.printStackTrace()
            }
            throw e
        }
    }

    private fun renderTimeline(entries: List<StageTimelineEntry>) {
        if (entries.isEmpty()) {
            terminal.println(dim("No stage timeline recorded."))
            return
        }

        terminal.println()
        terminal.println(bold("⏱  Stage Timeline"))
        entries.forEach { entry ->
            val icon = when (entry.state) {
                StageTimelineState.STARTED -> yellow("●")
                StageTimelineState.COMPLETED -> green("●")
                StageTimelineState.FAILED -> red("●")
            }
            val duration = entry.durationMs?.let { dim("(${it}ms)") } ?: ""
            terminal.println("$icon ${entry.stageId} $duration - ${entry.message}")
            entry.outputPreview?.let {
                terminal.println(dim("   ${it.replace("\n", " ").take(160)}"))
            }
        }
    }

    private fun renderResultSummary(result: com.cotor.model.AggregatedResult, pipeline: com.cotor.model.Pipeline) {
        terminal.println()
        terminal.println(bold("📦 Run Summary"))
        terminal.println("   Pipeline : ${pipeline.name}")
        terminal.println("   Agents   : ${result.successCount}/${result.totalAgents} succeeded")
        terminal.println("   Duration : ${result.totalDuration}ms")
        result.analysis?.let { analysis ->
            val score = (analysis.consensusScore * 100).roundToInt().coerceIn(0, 100)
            val badge = if (analysis.hasConsensus) green("✅ Consensus") else yellow("⚠️ Divergent")
            terminal.println("   Consensus: $badge ($score%)")
            analysis.bestAgent?.let { agent ->
                val preview = analysis.bestSummary?.replace("\n", " ")?.take(80) ?: ""
                terminal.println("   Best     : $agent ${if (preview.isNotBlank()) dim("- $preview") else ""}")
            }
            if (verbose) {
                if (analysis.disagreements.isNotEmpty()) {
                    terminal.println(dim("   Disagreements:"))
                    analysis.disagreements.forEach { terminal.println(dim("      • $it")) }
                }
                if (analysis.recommendations.isNotEmpty()) {
                    terminal.println(dim("   Recommendations:"))
                    analysis.recommendations.forEach { terminal.println(dim("      • $it")) }
                }
            }
        }
    }
}

/**
 * Validate command to check pipeline configuration
 */
class ValidateCommand :
    CliktCommand(
        name = "validate",
        help = "Validate pipeline configuration"
    ),
    KoinComponent {
    private val configRepository: ConfigRepository by inject()
    private val agentRegistry: AgentRegistry by inject()

    private val configPath by option("--config", "-c", help = "Path to configuration file")
        .path(mustExist = false)
        .default(Path("cotor.yaml"))

    val pipelineName by argument("pipeline", help = "Name of pipeline to validate").optional()

    override fun run() = runBlocking {
        try {
            // Check if config exists
            if (!configPath.exists()) {
                throw ErrorMessages.configNotFound(configPath.toString())
            }

            // Load configuration
            val config = configRepository.loadConfig(configPath)

            // Register agents
            config.agents.forEach { agentRegistry.registerAgent(it) }

            // Find pipeline
            val selectedPipelineName = pipelineName ?: config.pipelines.singleOrNull()?.name
                ?: throw ErrorMessages.pipelineNotFound(
                    pipelineName ?: "<unspecified>",
                    config.pipelines.map { it.name }
                )
            val pipeline = config.pipelines.find { it.name == selectedPipelineName }
                ?: throw ErrorMessages.pipelineNotFound(
                    selectedPipelineName,
                    config.pipelines.map { it.name }
                )

            // Validate
            val validator = PipelineValidator(agentRegistry, com.cotor.data.plugin.ReflectionPluginLoader(org.slf4j.LoggerFactory.getLogger(com.cotor.data.plugin.ReflectionPluginLoader::class.java)))
            val result = validator.validate(pipeline)

            when (result) {
                is com.cotor.validation.ValidationResult.Success -> {
                    echo("✅ Pipeline structure: valid")
                    echo("✅ All agents defined: valid")
                    echo("✅ Stage dependencies: valid")

                    if (result.warnings.isNotEmpty()) {
                        echo()
                        echo("⚠️  Warnings:")
                        result.warnings.forEach { echo("   - $it") }
                    } else {
                        echo()
                        echo("🎉 No warnings found!")
                    }
                }
                is com.cotor.validation.ValidationResult.Failure -> {
                    echo("❌ Validation failed!")
                    echo()
                    echo("Errors:")
                    result.errors.forEach { echo("   - $it") }

                    if (result.warnings.isNotEmpty()) {
                        echo()
                        echo("Warnings:")
                        result.warnings.forEach { echo("   - $it") }
                    }
                    throw ProgramResult(1)
                }
            }
        } catch (e: UserFriendlyError) {
            echo(e.message, err = true)
            throw e
        } catch (e: Exception) {
            echo("Error: ${e.message}", err = true)
            e.printStackTrace()
            throw e
        }
    }
}

/**
 * Test command to run cotor pipeline in test environment
 */
class TestCommand :
    CliktCommand(
        name = "test",
        help = "Test cotor pipeline functionality"
    ),
    KoinComponent {
    private val testDir by option("--test-dir", help = "Test directory")
        .default("test")

    override fun run() {
        echo("🧪 Running Cotor Pipeline Tests")
        echo("─".repeat(50))

        // Test 1: Check cotor.yaml exists
        echo()
        echo("Test 1: Configuration file check")
        val configPath = Path("$testDir/board-feature/board-pipeline.yaml")
        if (configPath.exists()) {
            echo("   ✅ Configuration file exists: $configPath")
        } else {
            echo("   ❌ Configuration file not found: $configPath")
            return
        }

        // Test 2: Validate pipeline
        echo()
        echo("Test 2: Pipeline validation")
        echo("   Run: ./cotor validate board-implementation -c $configPath")

        // Test 3: Dry-run
        echo()
        echo("Test 3: Dry-run simulation")
        echo("   Run: ./cotor run board-implementation --dry-run -c $configPath")

        // Test 4: Actual execution
        echo()
        echo("Test 4: Actual pipeline execution")
        echo("   Run: ./cotor run board-implementation -c $configPath --verbose")

        echo()
        echo("─".repeat(50))
        echo("💡 To run tests manually:")
        echo("   cd $testDir/board-feature")
        echo("   ../../gradlew shadowJar")
        echo("   java -jar ../../build/libs/cotor-1.0.0-all.jar validate board-implementation")
        echo("   java -jar ../../build/libs/cotor-1.0.0-all.jar run board-implementation --verbose")
    }
}
