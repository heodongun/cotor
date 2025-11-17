package com.cotor.presentation.cli

import com.cotor.data.config.ConfigRepository
import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.orchestrator.PipelineOrchestrator
import com.cotor.error.ErrorMessages
import com.cotor.error.UserFriendlyError
import com.cotor.event.EventBus
import com.cotor.event.*
import com.cotor.monitoring.PipelineMonitor
import com.cotor.monitoring.StageState
import com.cotor.presentation.formatter.OutputFormatter
import com.cotor.validation.PipelineValidator
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists

/**
 * Enhanced Run Command with monitoring, validation, and dry-run support
 */
class EnhancedRunCommand : CliktCommand(
    name = "run",
    help = "Run a pipeline with real-time monitoring"
), KoinComponent {
    private val configRepository: ConfigRepository by inject()
    private val agentRegistry: AgentRegistry by inject()
    private val orchestrator: PipelineOrchestrator by inject()
    private val outputFormatters: Map<String, OutputFormatter> by inject()
    private val eventBus: EventBus by inject()

    private val configPath by option("--config", "-c", help = "Path to configuration file")
        .path(mustExist = false)
        .default(Path("cotor.yaml"))

    val pipelineName by argument("pipeline", help = "Name of pipeline to run")

    val watch by option("--watch", "-w", help = "Watch mode with live progress updates")
        .flag(default = true)

    val verbose by option("--verbose", "-v", help = "Verbose output with detailed logging")
        .flag(default = false)

    val dryRun by option("--dry-run", help = "Simulate pipeline execution without running")
        .flag(default = false)

    val outputFormat by option("--output-format", "-o", help = "Output format")
        .choice("json", "csv", "text")
        .default("json")

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
            val pipeline = config.pipelines.find { it.name == pipelineName }
                ?: throw ErrorMessages.pipelineNotFound(
                    pipelineName,
                    config.pipelines.map { it.name }
                )

            // Validate pipeline
            val validator = PipelineValidator(agentRegistry)
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

            // Create monitor
            val monitor = if (watch) PipelineMonitor(pipeline, verbose) else null

            // Subscribe to events
            if (monitor != null) {
                eventBus.subscribe(StageStartedEvent::class) { event ->
                    val stageEvent = event as StageStartedEvent
                    monitor.updateStageState(stageEvent.stageId, StageState.RUNNING)
                    if (verbose) {
                        monitor.logVerbose("Stage started: ${stageEvent.stageId}")
                    }
                }

                eventBus.subscribe(StageCompletedEvent::class) { event ->
                    val stageEvent = event as StageCompletedEvent
                    monitor.updateStageState(stageEvent.stageId, StageState.COMPLETED)
                    if (verbose) {
                        monitor.logVerbose("Stage completed: ${stageEvent.stageId}")
                    }
                }

                eventBus.subscribe(StageFailedEvent::class) { event ->
                    val stageEvent = event as StageFailedEvent
                    monitor.updateStageState(stageEvent.stageId, StageState.FAILED, stageEvent.error.message)
                    if (verbose) {
                        monitor.logVerbose("Stage failed: ${stageEvent.stageId} - ${stageEvent.error.message}")
                    }
                }
            }

            // Execute pipeline
            echo("🚀 Executing pipeline: $pipelineName")
            if (verbose) {
                echo("   Execution mode: ${pipeline.executionMode}")
                echo("   Stages: ${pipeline.stages.size}")
                echo()
            }

            val result = orchestrator.executePipeline(pipeline)

            // Show summary
            monitor?.showSummary()

            // Format and output results
            val formatter = outputFormatters[outputFormat]
                ?: throw IllegalArgumentException("Unknown output format: $outputFormat")

            echo()
            echo("📄 Results:")
            echo(formatter.format(result))

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
}

/**
 * Validate command to check pipeline configuration
 */
class ValidateCommand : CliktCommand(
    name = "validate",
    help = "Validate pipeline configuration"
), KoinComponent {
    private val configRepository: ConfigRepository by inject()
    private val agentRegistry: AgentRegistry by inject()

    private val configPath by option("--config", "-c", help = "Path to configuration file")
        .path(mustExist = false)
        .default(Path("cotor.yaml"))

    val pipelineName by argument("pipeline", help = "Name of pipeline to validate")

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
            val pipeline = config.pipelines.find { it.name == pipelineName }
                ?: throw ErrorMessages.pipelineNotFound(
                    pipelineName,
                    config.pipelines.map { it.name }
                )

            // Validate
            val validator = PipelineValidator(agentRegistry)
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
                }
            }

        } catch (e: UserFriendlyError) {
            echo(e.message, err = true)
        } catch (e: Exception) {
            echo("Error: ${e.message}", err = true)
            e.printStackTrace()
        }
    }
}

/**
 * Test command to run cotor pipeline in test environment
 */
class TestCommand : CliktCommand(
    name = "test",
    help = "Test cotor pipeline functionality"
), KoinComponent {
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
        echo("   java -jar ../../build/libs/cotor-1.0.0.jar validate board-implementation")
        echo("   java -jar ../../build/libs/cotor-1.0.0.jar run board-implementation --verbose")
    }
}
