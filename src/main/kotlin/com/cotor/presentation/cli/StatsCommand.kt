package com.cotor.presentation.cli

/**
 * File overview for StatsCommand.
 *
 * This file belongs to the CLI presentation layer for interactive and command-driven flows.
 * It groups declarations around stats command so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import com.cotor.stats.PerformanceTrend
import com.cotor.stats.StatsDetails
import com.cotor.stats.StatsManager
import com.cotor.stats.StatsSummary
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Duration

/**
 * Command to show pipeline statistics
 */
class StatsCommand :
    CliktCommand(
        name = "stats",
        help = "Show pipeline execution statistics"
    ),
    KoinComponent {
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
    private val outputFormat by option(
        "--output-format",
        help = "Output format (json, csv)"
    ).choice("json", "csv")

    private val statsManager: StatsManager by inject()
    private val json = Json { prettyPrint = true }

    override fun run() {
        if (clear) {
            if (pipelineName == null) {
                echo(red("Please specify the pipeline name to clear stats for."))
                echo(dim("Usage: cotor stats <pipeline> --clear"))
                return
            }

            val removed = statsManager.clearStats(pipelineName!!)
            if (removed) {
                echo(green("✅ Cleared stored statistics for $pipelineName"))
            } else {
                echo(yellow("No statistics file found for $pipelineName"))
            }
            return
        }

        if (pipelineName == null) {
            val allStats = statsManager.listAllStats()
            when (outputFormat) {
                "json" -> echo(json.encodeToString(allStats))
                "csv" -> printAllStatsCsv(allStats)
                else -> showAllStats(allStats)
            }
            return
        }

        history?.let {
            showHistory(pipelineName!!, it)
            return
        }

        if (details) {
            val stageDetails = statsManager.getStatsDetails(pipelineName!!)
            if (stageDetails == null) {
                echo(yellow("No statistics found for pipeline: $pipelineName"))
                echo()
                echo(dim("Run the pipeline first to collect statistics"))
                return
            }

            when (outputFormat) {
                "json" -> echo(json.encodeToString(stageDetails))
                "csv" -> printStageDetailsCsv(stageDetails)
                else -> showStageDetails(stageDetails)
            }
        } else {
            val summary = statsManager.getStatsSummary(pipelineName!!)
            if (summary == null) {
                echo(yellow("No statistics found for pipeline: $pipelineName"))
                echo()
                echo(dim("Run the pipeline first to collect statistics"))
                return
            }

            when (outputFormat) {
                "json" -> echo(json.encodeToString(summary))
                "csv" -> printDetailedStatsCsv(summary)
                else -> showDetailedStats(summary)
            }
        }
    }

    private fun printAllStatsCsv(allStats: List<StatsSummary>) {
        echo("Pipeline,Executions,SuccessRate,AvgDuration,Trend")
        allStats.forEach {
            echo("${it.pipelineName},${it.totalExecutions},${it.successRate},${it.avgDuration},${it.trend}")
        }
    }

    private fun printDetailedStatsCsv(summary: StatsSummary) {
        echo("Pipeline,TotalExecutions,SuccessRate,AvgDuration,AvgRecentDuration,LastExecuted,Trend")
        echo("${summary.pipelineName},${summary.totalExecutions},${summary.successRate},${summary.avgDuration},${summary.avgRecentDuration},${summary.lastExecuted},${summary.trend}")
    }

    private fun printStageDetailsCsv(details: StatsDetails) {
        echo("StageName,AvgDuration,SuccessRate,AvgRetries")
        details.stages.forEach {
            echo("${it.stageName},${it.avgDuration},${it.successRate},${it.avgRetries}")
        }
    }

    private fun showAllStats(allStats: List<StatsSummary>) {
        if (allStats.isEmpty()) {
            echo(yellow("No statistics available yet"))
            echo()
            echo(dim("Statistics are collected automatically when pipelines run"))
            return
        }

        echo(bold(blue("📊 Pipeline Statistics Overview")))
        echo("─".repeat(80))
        echo()

        echo(
            String.format(
                "%-30s %10s %10s %12s %8s",
                "Pipeline",
                "Executions",
                "Success",
                "Avg Time",
                "Trend"
            )
        )
        echo("─".repeat(80))

        allStats.forEach { stats ->
            val trendIcon = when (stats.trend) {
                PerformanceTrend.IMPROVING -> green("↗")
                PerformanceTrend.STABLE -> yellow("→")
                PerformanceTrend.DEGRADING -> red("↘")
            }

            echo(
                String.format(
                    "%-30s %10d %9.1f%% %12s %8s",
                    stats.pipelineName.take(30),
                    stats.totalExecutions,
                    stats.successRate,
                    formatDuration(stats.avgDuration),
                    trendIcon
                )
            )
        }

        echo("─".repeat(80))
        echo()
        echo(dim("Usage: cotor stats <pipeline-name> for detailed statistics"))
    }

    private fun showDetailedStats(summary: com.cotor.stats.StatsSummary) {
        echo(bold(blue("📊 Detailed Statistics: ${summary.pipelineName}")))
        echo("─".repeat(50))
        echo()

        // Overview
        echo(bold("Overview:"))
        echo("  Total Executions: ${summary.totalExecutions}")
        echo("  Success Rate: ${green(String.format("%.1f%%", summary.successRate))}")
        echo("  Last Executed: ${summary.lastExecuted ?: "Never"}")
        echo()

        // Performance
        echo(bold("Performance:"))
        echo("  Average Duration: ${formatDuration(summary.avgDuration)}")
        echo("  Recent Average: ${formatDuration(summary.avgRecentDuration)}")

        val trendText = when (summary.trend) {
            PerformanceTrend.IMPROVING -> green("Improving ↗")
            PerformanceTrend.STABLE -> yellow("Stable →")
            PerformanceTrend.DEGRADING -> red("Degrading ↘")
        }
        echo("  Trend: $trendText")
        echo()

        // Failure Analysis
        if (summary.failureCategoryCounts.isNotEmpty()) {
            echo(bold("Failure Analysis:"))
            summary.failureCategoryCounts.forEach { (category, count) ->
                echo("  ${category.name.padEnd(20)}: ${red(count.toString())}")
            }
            echo()
        }

        // Recommendations
        echo(bold("Recommendations:"))
        when (summary.trend) {
            PerformanceTrend.IMPROVING -> {
                echo(green("  ✅ Performance is improving - keep up the good work!"))
            }
            PerformanceTrend.STABLE -> {
                echo(yellow("  ℹ️  Performance is stable"))
            }
            PerformanceTrend.DEGRADING -> {
                echo(red("  ⚠️  Performance is degrading"))
                echo("  💡 Consider reviewing recent changes or optimizing prompts")
            }
        }

        if (summary.successRate < 80) {
            echo(red("  ⚠️  Low success rate detected"))
            echo("  💡 Review agent configurations and error logs")
        }

        echo()
        echo("─".repeat(50))
    }

    private fun showStageDetails(details: com.cotor.stats.StatsDetails) {
        echo(bold(blue("📊 Stage-level Statistics: ${details.pipelineName}")))
        echo("─".repeat(80))
        echo()

        if (details.stages.isEmpty()) {
            echo(yellow("No stage-level data available for this pipeline."))
            echo(dim("Ensure `recordExecution` is called with stage details."))
            echo("─".repeat(80))
            return
        }

        echo(
            String.format(
                "%-30s %15s %15s %15s",
                "Stage Name",
                "Avg Duration",
                "Success Rate",
                "Avg Retries"
            )
        )
        echo("─".repeat(80))

        details.stages.forEach { stage ->
            echo(
                String.format(
                    "%-30s %15s %14.1f%% %15.1f",
                    stage.stageName.take(30),
                    formatDuration(stage.avgDuration),
                    stage.successRate,
                    stage.avgRetries
                )
            )
        }

        echo("─".repeat(80))
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
            echo(yellow("No execution history found for $pipelineName"))
            return
        }

        echo(bold(blue("📈 Execution History for $pipelineName (last $count)")))
        echo("─".repeat(80))
        echo()

        echo(
            String.format(
                "%-28s %10s %10s %12s",
                "Timestamp",
                "Success",
                "Failure",
                "Duration"
            )
        )
        echo("─".repeat(80))

        history.forEach { exec ->
            val successText = if (exec.successCount > 0) green(exec.successCount.toString()) else exec.successCount.toString()
            val failureText = if (exec.failureCount > 0) red(exec.failureCount.toString()) else exec.failureCount.toString()

            echo(
                String.format(
                    "%-28s %10s %10s %12s",
                    exec.timestamp,
                    successText,
                    failureText,
                    formatDuration(exec.totalDuration)
                )
            )
        }

        echo("─".repeat(80))
    }
}
