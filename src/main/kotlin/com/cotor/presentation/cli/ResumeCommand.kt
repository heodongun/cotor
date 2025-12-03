package com.cotor.presentation.cli

import com.cotor.checkpoint.CheckpointManager
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.table.table
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

/**
 * Command to manage checkpoints
 */
class CheckpointCommand : CliktCommand(
    name = "checkpoint",
    help = "Manage pipeline checkpoints"
) {
    private val terminal = Terminal()
    private val checkpointManager = CheckpointManager()

    override fun run() {
        terminal.println(bold(blue("🔖 Checkpoint Management")))
        terminal.println()

        val checkpoints = checkpointManager.listCheckpoints()
        terminal.println("Total checkpoints: ${checkpoints.size}")
        terminal.println()

        if (checkpoints.isNotEmpty()) {
            terminal.println(bold("Recent Checkpoints:"))
            checkpoints.take(5).forEach { checkpoint ->
                terminal.println("  ${green("●")} ${checkpoint.pipelineName} (${checkpoint.createdAt})")
            }
            terminal.println()
        }

        terminal.println(dim("Commands:"))
        terminal.println(dim("  cotor resume <id>       - Resume from checkpoint"))
        terminal.println(dim("  cotor checkpoint clean  - Clean old checkpoints"))
        terminal.println(dim("  cotor checkpoint list   - List all checkpoints"))
    }
}
