package com.cotor.monitoring

/**
 * File overview for StageState.
 *
 * This file belongs to the observability layer for metrics, traces, and pipeline monitoring.
 * It groups declarations around pipeline monitor so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.model.Pipeline
import com.cotor.model.PipelineStage
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Stage execution state
 */
enum class StageState {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED
}

/**
 * Stage execution info
 */
data class StageExecutionInfo(
    val stage: PipelineStage,
    var state: StageState = StageState.PENDING,
    var startTime: Instant? = null,
    var endTime: Instant? = null,
    var error: String? = null
) {
    val duration: Duration?
        get() = if (startTime != null && endTime != null) {
            Duration.between(startTime, endTime)
        } else {
            null
        }
}

/**
 * Real-time pipeline execution monitor with live progress display
 */
class PipelineMonitor(
    private val pipeline: Pipeline,
    private val verbose: Boolean = false
) {
    private val terminal = Terminal()
    private val stageStates = ConcurrentHashMap<String, StageExecutionInfo>()
    private val startTime = Instant.now()
    private var lastProgressHash: Int = 0 // Track last rendered state to prevent duplicates
    private var lastRenderTime: Instant = Instant.now() // Debouncing timestamp
    private val minRenderInterval: Duration = Duration.ofMillis(100) // Minimum 100ms between renders

    init {
        // Initialize all stages as PENDING
        pipeline.stages.forEach { stage ->
            stageStates[stage.id] = StageExecutionInfo(stage)
        }
    }

    /**
     * Update stage state and render progress
     */
    fun updateStageState(stageId: String, state: StageState, error: String? = null) {
        val info = stageStates[stageId] ?: return

        when (state) {
            StageState.RUNNING -> {
                info.startTime = Instant.now()
                info.state = state
            }
            StageState.COMPLETED, StageState.FAILED, StageState.SKIPPED -> {
                info.endTime = Instant.now()
                info.state = state
                info.error = error
            }
            else -> {
                info.state = state
            }
        }

        renderProgress()
    }

    /**
     * Log verbose message
     */
    fun logVerbose(message: String) {
        if (verbose) {
            terminal.println(dim("  │ $message"))
        }
    }

    /**
     * Render current progress (with debouncing and state change detection)
     */
    private fun renderProgress(force: Boolean = false) {
        // Calculate current state hash to detect changes
        val currentHash = calculateProgressHash()
        val now = Instant.now()
        val timeSinceLastRender = Duration.between(lastRenderTime, now)

        // Skip rendering if:
        // 1. Nothing changed AND not forced
        // 2. Too soon since last render (debouncing) AND not forced
        if (!force) {
            if (currentHash == lastProgressHash) {
                return
            }
            if (timeSinceLastRender < minRenderInterval) {
                return
            }
        }

        lastProgressHash = currentHash
        lastRenderTime = now

        // Clear screen and move cursor to top
        if (!verbose) {
            terminal.print("\u001B[H\u001B[2J")
        }

        terminal.println(bold(blue("🚀 Running: ${pipeline.name}")) + gray(" (${pipeline.stages.size} stages)"))
        terminal.println("┌${"─".repeat(50)}┐")

        pipeline.stages.forEachIndexed { index, stage ->
            val info = stageStates[stage.id]!!
            val icon = when (info.state) {
                StageState.COMPLETED -> green("✅")
                StageState.RUNNING -> yellow("🔄")
                StageState.FAILED -> red("❌")
                StageState.PENDING -> gray("⏳")
                StageState.SKIPPED -> gray("⏭️")
            }

            val stageName = stage.id.take(30).padEnd(30)
            val duration = info.duration?.let { formatDuration(it) } ?: ""

            terminal.println("│ $icon Stage ${index + 1}: $stageName ${dim(duration)}")

            if (info.error != null && verbose) {
                terminal.println("│   ${red("Error:")} ${info.error}")
            }
        }

        terminal.println("└${"─".repeat(50)}┘")
        printStats()
    }

    /**
     * Calculate hash of current progress state
     */
    private fun calculateProgressHash(): Int {
        return stageStates.entries
            .sortedBy { it.key }
            .joinToString("|") { "${it.key}:${it.value.state}:${it.value.duration?.toMillis()}" }
            .hashCode()
    }

    /**
     * Print statistics
     */
    private fun printStats() {
        val completed = stageStates.values.count { it.state == StageState.COMPLETED }
        val failed = stageStates.values.count { it.state == StageState.FAILED }
        val total = pipeline.stages.size
        val progress = if (total > 0) (completed * 100) / total else 0

        val elapsed = Duration.between(startTime, Instant.now())
        val elapsedStr = formatDuration(elapsed)

        terminal.println(dim("⏱️  Elapsed: $elapsedStr | Progress: $progress% ($completed/$total stages completed)"))

        if (failed > 0) {
            terminal.println(red("⚠️  Failed stages: $failed"))
        }
    }

    /**
     * Show final summary
     */
    fun showSummary(totalDurationOverrideMs: Long? = null) {
        // Force final render to ensure latest state is shown
        renderProgress(force = true)
        terminal.println()
        terminal.println(bold("📊 Pipeline Execution Summary"))
        terminal.println("─".repeat(50))
        terminal.println("Pipeline: ${pipeline.name}")
        terminal.println("Execution Mode: ${pipeline.executionMode}")

        val completed = stageStates.values.count { it.state == StageState.COMPLETED }
        val failed = stageStates.values.count { it.state == StageState.FAILED }
        val total = pipeline.stages.size

        terminal.println()
        terminal.println("Results:")
        terminal.println("  ${green("✅ Completed:")} $completed/$total")
        if (failed > 0) {
            terminal.println("  ${red("❌ Failed:")} $failed/$total")
        }

        val totalDuration = totalDurationOverrideMs?.let { Duration.ofMillis(it) }
            ?: Duration.between(startTime, Instant.now())
        terminal.println("  ⏱️  Total Duration: ${formatDuration(totalDuration)}")

        if (verbose) {
            terminal.println()
            terminal.println("Stage Details:")
            pipeline.stages.forEach { stage ->
                val info = stageStates[stage.id]!!
                val icon = when (info.state) {
                    StageState.COMPLETED -> green("✅")
                    StageState.FAILED -> red("❌")
                    else -> gray("⏳")
                }
                val duration = info.duration?.let { formatDuration(it) } ?: "N/A"
                terminal.println("  $icon ${stage.id}: $duration")
            }
        }

        terminal.println("─".repeat(50))
    }

    /**
     * Format duration as human-readable string
     */
    private fun formatDuration(duration: Duration): String {
        val seconds = duration.seconds
        val minutes = seconds / 60
        val secs = seconds % 60
        val millis = duration.toMillis() % 1000

        return when {
            minutes > 0 -> "${minutes}m ${secs}s"
            secs > 0 -> "$secs.${millis / 100}s"
            else -> "${millis}ms"
        }
    }
}
