package com.cotor.presentation.cli

import com.cotor.stats.PerformanceTrend
import com.cotor.stats.StatsManager
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import java.time.Duration

/**
 * Command to show pipeline statistics
 */
class StatsCommand : CliktCommand(
    name = "stats",
    help = "Show pipeline execution statistics"
) {
    private val pipelineName by argument(
        name = "pipeline",
        help = "Pipeline name to show stats for"
    ).optional()

    private val terminal = Terminal()
    private val statsManager = StatsManager()

    override fun run() {
        if (pipelineName == null) {
            showAllStats()
            return
        }

        val summary = statsManager.getStatsSummary(pipelineName!!)
        if (summary == null) {
            terminal.println(yellow("No statistics found for pipeline: $pipelineName"))
            terminal.println()
            terminal.println(dim("Run the pipeline first to collect statistics"))
            return
        }

        showDetailedStats(summary)
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
}
