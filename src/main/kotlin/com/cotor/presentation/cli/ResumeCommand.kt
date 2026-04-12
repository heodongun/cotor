package com.cotor.presentation.cli

import com.cotor.checkpoint.CheckpointManager
import com.cotor.runtime.durable.ApprovalPauseStatus
import com.cotor.runtime.durable.DurableResumeCoordinator
import com.cotor.runtime.durable.DurableRunSnapshot
import com.cotor.runtime.durable.DurableRuntimeService
import com.cotor.runtime.durable.ReplayApprovalRequiredException
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.TextColors.blue
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.io.path.Path

class ResumeCommand : CliktCommand(
    name = "resume",
    help = "Inspect, continue, fork, and approve durable pipeline runs",
    invokeWithoutSubcommand = true
), KoinComponent {
    private val runId by argument("run-id", help = "Durable run ID or legacy pipeline ID").optional()
    private val terminal = Terminal()
    private val durableRuntimeService: DurableRuntimeService by inject()
    private val checkpointManager = CheckpointManager()

    init {
        subcommands(
            ResumeInspectCommand(),
            ResumeContinueCommand(),
            ResumeForkCommand(),
            ResumeApproveCommand()
        )
    }

    override fun run() {
        if (currentContext.invokedSubcommand != null) {
            return
        }
        if (runId == null) {
            listAvailableRuns()
            return
        }
        val durableRun = durableRuntimeService.inspectRun(runId!!)
        if (durableRun != null) {
            renderDurableRun(terminal, durableRun)
            return
        }
        val checkpoint = checkpointManager.loadCheckpoint(runId!!)
        if (checkpoint == null) {
            terminal.println(red("❌ No durable run or legacy checkpoint found for: $runId"))
            terminal.println()
            listAvailableRuns()
            return
        }
        terminal.println(yellow("⚠️  Imported legacy checkpoint; use `cotor resume inspect $runId` for durable view after the next run."))
        terminal.println()
        printLegacyCheckpoint(runId!!)
    }

    private fun listAvailableRuns() {
        val durableRuns = durableRuntimeService.listRuns()
        if (durableRuns.isNotEmpty()) {
            terminal.println(bold(blue("📦 Durable Runs")))
            terminal.println(
                table {
                    header { row("Pipeline", "Run", "Mode", "Status", "Checkpoints", "Pending approvals") }
                    body {
                        durableRuns.forEach { run ->
                            row(
                                run.pipelineName,
                                run.runId.take(8),
                                run.replayMode.name,
                                run.status.name,
                                run.checkpoints.size.toString(),
                                run.approvalPauses.count { it.status == ApprovalPauseStatus.PENDING }.toString()
                            )
                        }
                    }
                }
            )
            terminal.println()
        }

        val checkpoints = checkpointManager.listCheckpoints()
        if (checkpoints.isNotEmpty()) {
            terminal.println(bold(blue("📋 Legacy Checkpoints")))
            terminal.println(
                table {
                    header { row("Pipeline", "ID", "Created At", "Stages") }
                    body {
                        checkpoints.forEach { checkpoint ->
                            row(
                                checkpoint.pipelineName,
                                checkpoint.pipelineId.take(8),
                                checkpoint.createdAt,
                                checkpoint.completedStages.toString()
                            )
                        }
                    }
                }
            )
            terminal.println()
        }

        if (durableRuns.isEmpty() && checkpoints.isEmpty()) {
            terminal.println(yellow("No durable runs or checkpoints found"))
        } else {
            terminal.println("Use: cotor resume inspect <run-id>, cotor resume continue <run-id>, cotor resume fork <run-id> --from <checkpoint-id>")
        }
    }

    private fun printLegacyCheckpoint(id: String) {
        val checkpoint = checkpointManager.loadCheckpoint(id) ?: return
        terminal.println(bold(blue("📦 Pipeline Checkpoint")))
        terminal.println("─".repeat(50))
        terminal.println("Pipeline: ${checkpoint.pipelineName}")
        terminal.println("ID: ${checkpoint.pipelineId}")
        terminal.println("Created At: ${checkpoint.createdAt}")
        terminal.println("Version: ${checkpoint.cotorVersion} (git: ${checkpoint.gitCommit})")
        terminal.println("Environment: OS=${checkpoint.os}, JVM=${checkpoint.jvm}")
        terminal.println("Completed Stages: ${checkpoint.completedStages.size}")
        terminal.println("─".repeat(50))
    }

}

class ResumeInspectCommand : CliktCommand(name = "inspect", help = "Inspect one durable run"), KoinComponent {
    private val runId by argument("run-id")
    private val durableRuntimeService: DurableRuntimeService by inject()
    private val terminal = Terminal()

    override fun run() {
        val run = durableRuntimeService.inspectRun(runId)
            ?: error("No durable run or legacy checkpoint found for $runId")
        renderDurableRun(terminal, run)
    }
}

class ResumeContinueCommand : CliktCommand(name = "continue", help = "Continue a durable run from its latest safe checkpoint"), KoinComponent {
    private val runId by argument("run-id")
    private val configPath by option("--config", "-c", help = "Override configuration path").path(mustExist = false)
    private val coordinator: DurableResumeCoordinator by inject()
    private val terminal = Terminal()

    override fun run() {
        kotlinx.coroutines.runBlocking {
            try {
                val plan = coordinator.continueRun(runId, configPath?.toString())
                terminal.println(green("✅ Continued run ${plan.runId} from ${plan.restoreCheckpointId ?: "start"}"))
                plan.nextStageId?.let { terminal.println("Next stage: $it") }
            } catch (error: ReplayApprovalRequiredException) {
                terminal.println(yellow("⏸️  Replay paused for approval: ${error.pause.label}"))
                terminal.println("Approve with: cotor resume approve $runId --checkpoint ${error.pause.checkpointId ?: ""}".trim())
            }
        }
    }
}

class ResumeForkCommand : CliktCommand(name = "fork", help = "Fork a durable run from a checkpoint"), KoinComponent {
    private val runId by argument("run-id")
    private val checkpointId by option("--from", help = "Checkpoint ID to fork from").required()
    private val configPath by option("--config", "-c", help = "Override configuration path").path(mustExist = false)
    private val coordinator: DurableResumeCoordinator by inject()
    private val terminal = Terminal()

    override fun run() {
        kotlinx.coroutines.runBlocking {
            try {
                val plan = coordinator.forkRun(runId, checkpointId, configPath?.toString())
                terminal.println(green("✅ Forked run ${plan.runId} from checkpoint ${plan.restoreCheckpointId}"))
                plan.nextStageId?.let { terminal.println("Next stage: $it") }
            } catch (error: ReplayApprovalRequiredException) {
                terminal.println(yellow("⏸️  Fork paused for approval: ${error.pause.label}"))
            }
        }
    }
}

class ResumeApproveCommand : CliktCommand(name = "approve", help = "Approve replay-unsafe durable side effects"), KoinComponent {
    private val runId by argument("run-id")
    private val checkpointId by option("--checkpoint", help = "Approve only one checkpoint scope")
    private val coordinator: DurableResumeCoordinator by inject()
    private val terminal = Terminal()

    override fun run() {
        kotlinx.coroutines.runBlocking {
            val updated = coordinator.approve(runId, checkpointId)
            terminal.println(green("✅ Approved replay side effects for ${updated.runId}"))
            terminal.println("Pending approvals: ${updated.approvalPauses.count { it.status == ApprovalPauseStatus.PENDING }}")
        }
    }
}

private fun renderDurableRun(terminal: Terminal, run: DurableRunSnapshot) {
    terminal.println(bold(blue("📦 Durable Run")))
    terminal.println("─".repeat(60))
    terminal.println("Pipeline: ${run.pipelineName}")
    terminal.println("Run ID: ${run.runId}")
    terminal.println("Replay mode: ${run.replayMode}")
    terminal.println("Status: ${run.status}")
    run.configPath?.let { terminal.println("Config: $it") }
    run.sourceRunId?.let { terminal.println("Source run: $it") }
    run.sourceCheckpointId?.let { terminal.println("Source checkpoint: $it") }
    terminal.println("Checkpoints: ${run.checkpoints.size}")
    terminal.println("Side effects: ${run.sideEffects.size}")
    val pendingApprovals = run.approvalPauses.filter { it.status == ApprovalPauseStatus.PENDING }
    terminal.println("Pending approvals: ${pendingApprovals.size}")
    if (run.checkpoints.isNotEmpty()) {
        terminal.println()
        terminal.println(bold("Checkpoint Graph:"))
        run.checkpoints.sortedBy { it.ordinal }.forEach { node ->
            val stageText = node.stageId ?: "(root)"
            terminal.println("  ${node.ordinal.toString().padStart(2, '0')} ${node.state} $stageText")
        }
    }
    if (run.sideEffects.isNotEmpty()) {
        terminal.println()
        terminal.println(bold("Side Effects:"))
        run.sideEffects.sortedBy { it.createdAt }.forEach { effect ->
            terminal.println(
                "  - ${effect.kind} ${effect.label} [${effect.status}] " +
                    if (effect.approvalRequiredOnReplay) yellow("(approval on replay)") else green("(replay-safe)")
            )
        }
    }
    if (pendingApprovals.isNotEmpty()) {
        terminal.println()
        terminal.println(yellow("Approval required before replay:"))
        pendingApprovals.forEach { pause ->
            terminal.println("  - ${pause.label} (checkpoint=${pause.checkpointId ?: "latest"})")
        }
    }
    terminal.println("─".repeat(60))
}
