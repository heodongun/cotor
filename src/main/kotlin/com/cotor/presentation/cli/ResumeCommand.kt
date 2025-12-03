package com.cotor.presentation.cli

import com.cotor.checkpoint.CheckpointManager
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal

/**
 * Command to resume pipeline from checkpoint
 */
class ResumeCommand : CliktCommand(
    name = "resume",
    help = "Resume pipeline execution from checkpoint"
) {
    private val pipelineId by argument(
        name = "pipeline-id",
        help = "Pipeline ID to resume"
    ).optional()

    private val terminal = Terminal()
    private val checkpointManager = CheckpointManager()

    override fun run() {
        if (pipelineId == null) {
            listCheckpoints()
            return
        }

        val checkpoint = checkpointManager.loadCheckpoint(pipelineId!!)
        if (checkpoint == null) {
            terminal.println(red("❌ No checkpoint found for pipeline: $pipelineId"))
            terminal.println()
            terminal.println(dim("Available checkpoints:"))
            listCheckpoints()
            return
        }

        terminal.println(bold(blue("📦 Pipeline Checkpoint")))
        terminal.println("─".repeat(50))
        terminal.println("Pipeline: ${checkpoint.pipelineName}")
        terminal.println("ID: ${checkpoint.pipelineId}")
        terminal.println("Timestamp: ${checkpoint.timestamp}")
        terminal.println("Completed Stages: ${checkpoint.completedStages.size}")
        terminal.println("─".repeat(50))
        terminal.println()

        terminal.println(bold("Completed Stages:"))
        checkpoint.completedStages.forEach { stage ->
            val icon = if (stage.isSuccess) green("✅") else red("❌")
            terminal.println("  $icon ${stage.stageId} (${stage.agentName}, ${stage.duration}ms)")
        }

        terminal.println()
        terminal.println(yellow("⚠️  Resume functionality requires integration with pipeline orchestrator"))
        terminal.println(dim("This will be available in the next update"))
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

        checkpoints.forEach { checkpoint ->
            terminal.println("${green("●")} ${bold(checkpoint.pipelineName)}")
            terminal.println("  ID: ${dim(checkpoint.pipelineId)}")
            terminal.println("  Time: ${checkpoint.timestamp}")
            terminal.println("  Completed: ${checkpoint.completedStages} stages")
            terminal.println("  File: ${dim(checkpoint.file)}")
            terminal.println()
        }

        terminal.println(dim("Usage: cotor resume <pipeline-id>"))
    }
}

