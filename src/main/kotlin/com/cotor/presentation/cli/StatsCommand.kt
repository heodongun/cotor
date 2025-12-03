package com.cotor.presentation.cli

import com.cotor.stats.PerformanceTrend
import com.cotor.stats.StatsManager
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Duration

/**
 * Command to show pipeline statistics
 */
class StatsCommand : CliktCommand(
    name = "stats",
    help = "Show pipeline execution statistics"
), KoinComponent {
    private val pipelineName by argument(
        name = "pipeline",
        help = "Pipeline name to show stats for"
    ).optional()
    private val clear by option(
        "--clear",
        help = "Remove stored stats for the specified pipeline"
    ).flag(default = false)
    private val history by option(
        "--history",
        help = "Show the last N execution trends"
    ).int()
    private val details by option(
        "--details",
        help = "Show detailed, stage-level statistics"
    ).flag(default = false)

    private val terminal = Terminal()
    private val statsManager: StatsManager by inject()

    override fun run() {
        if (clear) {
            if (pipelineName == null) {
                terminal.println(red("Please specify the pipeline name to clear stats for."))
                terminal.println(dim("Usage: cotor stats <pipeline> --clear"))
                return
            }

            val removed = statsManager.clearStats(pipelineName!!)
            if (removed) {
                terminal.println(green("✅ Cleared stored statistics for $pipelineName"))
            } else {
                terminal.println(yellow("No statistics file found for $pipelineName"))
            }
            return
        }

        if (pipelineName == null) {
            showAllStats()
            return
        }

        history?.let {
            showHistory(pipelineName!!, it)
            return
        }

        if (details) {
            val stageDetails = statsManager.getStatsDetails(pipelineName!!)
            if (stageDetails == null) {
                terminal.println(yellow("No statistics found for pipeline: $pipelineName"))
                terminal.println()
                terminal.println(dim("Run the pipeline first to collect statistics"))
                return
            }
            showStageDetails(stageDetails)
        } else {
            val summary = statsManager.getStatsSummary(pipelineName!!)
            if (summary == null) {
                terminal.println(yellow("No statistics found for pipeline: $pipelineName"))
                terminal.println()
                terminal.println(dim("Run the pipeline first to collect statistics"))
                return
            }
            showDetailedStats(summary)
        }
    }

    private fun showAllStats() {
        val allStats = statsManager.listAllStats()

        if (allStats.isEmpty()) {
            terminal.println(yellow("No statistics available yet"))
            terminal.println()
            terminal.println(dim("Statistics are collected automatically when pipelines run"))
            return
        }

        terminal.println(bold(blue("📊 Pipeline Statistics Overview")))
        terminal.println("─".repeat(80))
        terminal.println()

        terminal.println(String.format(
            "%-30s %10s %10s %12s %8s",
            "Pipeline", "Executions", "Success", "Avg Time", "Trend"
        ))
        terminal.println("─".repeat(80))

        allStats.forEach { stats ->
            val trendIcon = when (stats.trend) {
                PerformanceTrend.IMPROVING -> green("↗")
                PerformanceTrend.STABLE -> yellow("→")
                PerformanceTrend.DEGRADING -> red("↘")
            }

            terminal.println(String.format(
                "%-30s %10d %9.1f%% %12s %8s",
                stats.pipelineName.take(30),
                stats.totalExecutions,
                stats.successRate,
                formatDuration(stats.avgDuration),
                trendIcon
            ))
        }

        terminal.println("─".repeat(80))
        terminal.println()
        terminal.println(dim("Usage: cotor stats <pipeline-name> for detailed statistics"))
    }

    private fun showDetailedStats(summary: com.cotor.stats.StatsSummary) {
        terminal.println(bold(blue("📊 Detailed Statistics: ${summary.pipelineName}")))
        terminal.println("─".repeat(50))
        terminal.println()

        // Overview
        terminal.println(bold("Overview:"))
        terminal.println("  Total Executions: ${summary.totalExecutions}")
        terminal.println("  Success Rate: ${green(String.format("%.1f%%", summary.successRate))}")
        terminal.println("  Last Executed: ${summary.lastExecuted ?: "Never"}")
        terminal.println()

        // Performance
        terminal.println(bold("Performance:"))
        terminal.println("  Average Duration: ${formatDuration(summary.avgDuration)}")
        terminal.println("  Recent Average: ${formatDuration(summary.avgRecentDuration)}")

        val trendText = when (summary.trend) {
            PerformanceTrend.IMPROVING -> green("Improving ↗")
            PerformanceTrend.STABLE -> yellow("Stable →")
            PerformanceTrend.DEGRADING -> red("Degrading ↘")
        }
        terminal.println("  Trend: $trendText")
        terminal.println()

        // Failure Analysis
        if (summary.failureCategoryCounts.isNotEmpty()) {
            terminal.println(bold("Failure Analysis:"))
            summary.failureCategoryCounts.forEach { (category, count) ->
                terminal.println("  ${category.name.padEnd(20)}: ${red(count.toString())}")
            }
            terminal.println()
        }

        // Recommendations
        terminal.println(bold("Recommendations:"))
        when (summary.trend) {
            PerformanceTrend.IMPROVING -> {
                terminal.println(green("  ✅ Performance is improving - keep up the good work!"))
            }
            PerformanceTrend.STABLE -> {
                terminal.println(yellow("  ℹ️  Performance is stable"))
            }
            PerformanceTrend.DEGRADING -> {
                terminal.println(red("  ⚠️  Performance is degrading"))
                terminal.println("  💡 Consider reviewing recent changes or optimizing prompts")
            }
        }

        if (summary.successRate < 80) {
            terminal.println(red("  ⚠️  Low success rate detected"))
            terminal.println("  💡 Review agent configurations and error logs")
        }

        terminal.println()
        terminal.println("─".repeat(50))
    }

    private fun showStageDetails(details: com.cotor.stats.StatsDetails) {
        terminal.println(bold(blue("📊 Stage-level Statistics: ${details.pipelineName}")))
        terminal.println("─".repeat(80))
        terminal.println()

        if (details.stages.isEmpty()) {
            terminal.println(yellow("No stage-level data available for this pipeline."))
            terminal.println(dim("Ensure `recordExecution` is called with stage details."))
            terminal.println("─".repeat(80))
            return
        }

        terminal.println(String.format(
            "%-30s %15s %15s %15s",
            "Stage Name", "Avg Duration", "Success Rate", "Avg Retries"
        ))
        terminal.println("─".repeat(80))

        details.stages.forEach { stage ->
            terminal.println(String.format(
                "%-30s %15s %14.1f%% %15.1f",
                stage.stageName.take(30),
                formatDuration(stage.avgDuration),
                stage.successRate,
                stage.avgRetries
            ))
        }

        terminal.println("─".repeat(80))
    }

    private fun formatDuration(ms: Long): String {
        val duration = Duration.ofMillis(ms)
        val seconds = duration.seconds
        val minutes = seconds / 60
        val secs = seconds % 60

        return when {
            minutes > 0 -> "${minutes}m ${secs}s"
            secs > 0 -> "${secs}s"
            else -> "${ms}ms"
        }
    }

    private fun showHistory(pipelineName: String, count: Int) {
        val history = statsManager.getExecutionHistory(pipelineName, count)

        if (history.isEmpty()) {
            terminal.println(yellow("No execution history found for $pipelineName"))
            return
        }

        terminal.println(bold(blue("📈 Execution History for $pipelineName (last $count)")))
        terminal.println("─".repeat(80))
        terminal.println()

        terminal.println(String.format(
            "%-28s %10s %10s %12s",
            "Timestamp", "Success", "Failure", "Duration"
        ))
        terminal.println("─".repeat(80))

        history.forEach { exec ->
            val successText = if (exec.successCount > 0) green(exec.successCount.toString()) else exec.successCount.toString()
            val failureText = if (exec.failureCount > 0) red(exec.failureCount.toString()) else exec.failureCount.toString()

            terminal.println(String.format(
                "%-28s %10s %10s %12s",
                exec.timestamp,
                successText,
                failureText,
                formatDuration(exec.totalDuration)
            ))
        }

        terminal.println("─".repeat(80))
    }
}
