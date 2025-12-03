package com.cotor.presentation.cli

import com.cotor.checkpoint.CheckpointManager
import com.cotor.data.config.ConfigRepository
import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.orchestrator.PipelineOrchestrator
import com.cotor.model.AgentResult
import com.cotor.model.PipelineContext
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.runBlocking
import org.koin.core.component.inject

/**
 * Command to resume pipeline from checkpoint
 */
class ResumeCommand : CotorCommand(help = "Resume pipeline execution from checkpoint") {
    private val orchestrator: PipelineOrchestrator by inject()
    private val configRepository: ConfigRepository by inject()
    private val agentRegistry: AgentRegistry by inject()
    private val checkpointManager: CheckpointManager by inject()

    private val pipelineId by argument(
        name = "pipeline-id",
        help = "Pipeline ID to resume"
    ).optional()

    private val terminal = Terminal()

    override fun run() = runBlocking {
        if (pipelineId == null) {
            listCheckpoints()
            return@runBlocking
        }

        val checkpoint = checkpointManager.loadCheckpoint(pipelineId!!)
        if (checkpoint == null) {
            terminal.println(red("❌ No checkpoint found for pipeline: $pipelineId"))
            terminal.println()
            terminal.println(dim("Available checkpoints:"))
            listCheckpoints()
            return@runBlocking
        }

        terminal.println(bold(blue("📦 Pipeline Checkpoint")))
        terminal.println("─".repeat(50))
        terminal.println("Pipeline: ${checkpoint.pipelineName}")
        terminal.println("ID: ${checkpoint.pipelineId}")
        terminal.println("Created At: ${checkpoint.createdAt}")
        terminal.println("Version: ${checkpoint.cotorVersion} (git: ${checkpoint.gitCommit})")
        terminal.println("Environment: OS=${checkpoint.os}, JVM=${checkpoint.jvm}")
        terminal.println("Completed Stages: ${checkpoint.completedStages.size}")
        terminal.println("─".repeat(50))
        terminal.println()

        val currentJvm = System.getProperty("java.version")
        val currentOs = System.getProperty("os.name")

        if (checkpoint.jvm != currentJvm || checkpoint.os != currentOs) {
            terminal.println(yellow("⚠️  Environment mismatch detected!"))
            terminal.println(dim("  Checkpoint: JVM=${checkpoint.jvm}, OS=${checkpoint.os}"))
            terminal.println(dim("  Current:    JVM=$currentJvm, OS=$currentOs"))
            terminal.println()
        }

        terminal.println(bold("Completed Stages:"))
        checkpoint.completedStages.forEach { stage ->
            val icon = if (stage.isSuccess) green("✅") else red("❌")
            terminal.println("  $icon ${stage.stageId} (${stage.agentName}, ${stage.duration}ms)")
        }

        terminal.println()
        // Reconstruct pipeline context from checkpoint
        val context = PipelineContext(
            pipelineId = checkpoint.pipelineId,
            pipelineName = checkpoint.pipelineName,
            totalStages = 0 // Will be updated when pipeline is loaded
        )
        checkpoint.completedStages.forEach { stageCheckpoint ->
            val agentResult = AgentResult(
                agentName = stageCheckpoint.agentName,
                isSuccess = stageCheckpoint.isSuccess,
                output = stageCheckpoint.output,
                error = if (stageCheckpoint.isSuccess) null else "Resumed from failed stage",
                duration = stageCheckpoint.duration,
                metadata = mapOf("resumedFrom" to checkpoint.createdAt)
            )
            context.addStageResult(stageCheckpoint.stageId, agentResult)
        }

        // Load configuration and find the pipeline
        val config = configRepository.loadConfig(configPath)
        val pipeline = config.pipelines.find { it.name == checkpoint.pipelineName }
            ?: throw IllegalArgumentException("Pipeline '${checkpoint.pipelineName}' not found in config")

        // Register agents
        config.agents.forEach { agentRegistry.registerAgent(it) }

        // Determine the stage to resume from
        val lastCompletedStage = checkpoint.completedStages.lastOrNull { it.isSuccess }
        val fromStageId = if (lastCompletedStage != null) {
            val lastStageIndex = pipeline.stages.indexOfFirst { it.id == lastCompletedStage.stageId }
            if (lastStageIndex != -1 && lastStageIndex + 1 < pipeline.stages.size) {
                pipeline.stages[lastStageIndex + 1].id
            } else {
                null // Pipeline was already completed
            }
        } else {
            pipeline.stages.firstOrNull()?.id // Nothing completed successfully, start from the beginning
        }

        if (fromStageId == null) {
            terminal.println(green("✅ Pipeline already completed. Nothing to resume."))
            return@runBlocking
        }

        // Update total stages in context
        context.totalStages = pipeline.stages.size

        terminal.println(green("🚀 Resuming pipeline execution from stage: $fromStageId"))

        // Execute the pipeline from the specified stage
        orchestrator.executePipeline(
            pipeline = pipeline,
            fromStageId = fromStageId,
            context = context
        )

        terminal.println(green("✅ Pipeline execution resumed successfully."))
    }

    private fun listCheckpoints() {
        val checkpoints = checkpointManager.listCheckpoints()

        if (checkpoints.isEmpty()) {
            terminal.println(yellow("No checkpoints found"))
            terminal.println()
            terminal.println(dim("Checkpoints are automatically created when pipelines complete stages"))
            return
        }

        terminal.println(bold(blue("📋 Available Checkpoints")))
        terminal.println()

        val table = table {
            header {
                row("Pipeline", "ID", "Created At", "Version", "Git Commit", "OS", "JVM", "Stages")
            }
            body {
                checkpoints.forEach { checkpoint ->
                    row(
                        checkpoint.pipelineName,
                        checkpoint.pipelineId.take(8),
                        checkpoint.createdAt,
                        checkpoint.cotorVersion,
                        checkpoint.gitCommit,
                        checkpoint.os,
                        checkpoint.jvm,
                        checkpoint.completedStages.toString()
                    )
                }
            }
        }
        terminal.println(table)

        terminal.println(dim("Usage: cotor resume <pipeline-id>"))
    }
}
