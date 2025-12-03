package com.cotor.stats

import com.cotor.model.AggregatedResult
import com.cotor.model.FailureCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import java.time.Instant

/**
 * Manages pipeline execution statistics
 */
class StatsManager(
    private val statsDir: String = ".cotor/stats"
) {
    private val json = Json { prettyPrint = true }

    init {
        File(statsDir).mkdirs()
    }

    /**
     * Record pipeline execution
     */
    fun recordExecution(
        pipelineName: String,
        result: AggregatedResult,
        stages: List<StageExecution>
    ) {
        val execution = PipelineExecution(
            pipelineName = pipelineName,
            timestamp = Instant.now().toString(),
            totalDuration = result.totalDuration,
            successCount = result.successCount,
            failureCount = result.failureCount,
            totalAgents = result.totalAgents,
            stages = stages
        )

        val statsFile = getStatsFile(pipelineName)
        val stats = loadStats(pipelineName) ?: PipelineStats(pipelineName = pipelineName)

        val updatedFailureCounts = stats.failureCategoryCounts.toMutableMap()
        result.results.filter { !it.isSuccess }.forEach {
            val categoryString = it.metadata["failureCategory"] ?: FailureCategory.UNKNOWN.name
            val category = FailureCategory.valueOf(categoryString)
            updatedFailureCounts[category] = (updatedFailureCounts[category] ?: 0) + 1
        }

        val updatedStats = stats.copy(
            executions = stats.executions + execution,
            totalExecutions = stats.totalExecutions + 1,
            totalSuccesses = stats.totalSuccesses + result.successCount,
            totalFailures = stats.totalFailures + result.failureCount,
            totalDuration = stats.totalDuration + result.totalDuration,
            lastExecuted = execution.timestamp,
            failureCategoryCounts = updatedFailureCounts
        )

        statsFile.writeText(json.encodeToString(updatedStats))
    }

    /**
     * Load statistics for a pipeline
     */
    fun loadStats(pipelineName: String): PipelineStats? {
        val statsFile = getStatsFile(pipelineName)
        if (!statsFile.exists()) {
            return null
        }

        return try {
            json.decodeFromString<PipelineStats>(statsFile.readText())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get statistics summary for a pipeline
     */
    fun getStatsSummary(pipelineName: String): StatsSummary? {
        val stats = loadStats(pipelineName) ?: return null

        val avgDuration = if (stats.totalExecutions > 0) {
            stats.totalDuration / stats.totalExecutions
        } else 0L

        val successRate = if (stats.totalExecutions > 0) {
            (stats.totalSuccesses.toDouble() / (stats.totalSuccesses + stats.totalFailures)) * 100
        } else 0.0

        val recentExecutions = stats.executions.takeLast(10)
        val avgRecentDuration = if (recentExecutions.isNotEmpty()) {
            recentExecutions.map { it.totalDuration }.average().toLong()
        } else 0L

        return StatsSummary(
            pipelineName = stats.pipelineName,
            totalExecutions = stats.totalExecutions,
            successRate = successRate,
            avgDuration = avgDuration,
            avgRecentDuration = avgRecentDuration,
            lastExecuted = stats.lastExecuted,
            trend = calculateTrend(recentExecutions),
            failureCategoryCounts = stats.failureCategoryCounts
        )
    }

    /**
     * Get detailed statistics for a pipeline, including stage-level data.
     */
    fun getStatsDetails(pipelineName: String): StatsDetails? {
        val stats = loadStats(pipelineName) ?: return null
        if (stats.executions.isEmpty()) {
            return StatsDetails(pipelineName, 0, emptyList())
        }

        val stageStats = stats.executions
            .asSequence()
            .flatMap { it.stages }
            .groupBy { it.name }
            .map { (stageName, stageExecutions) ->
                val totalExecutions = stageExecutions.size
                val avgDuration = stageExecutions.map { it.duration }.average().toLong()
                val successRate = stageExecutions.count { it.status == ExecutionStatus.SUCCESS }.toDouble() / totalExecutions * 100
                val avgRetries = stageExecutions.map { it.retries }.average()
                StageStats(
                    stageName = stageName,
                    avgDuration = avgDuration,
                    successRate = successRate,
                    avgRetries = avgRetries
                )
            }

        return StatsDetails(
            pipelineName = pipelineName,
            totalExecutions = stats.totalExecutions,
            stages = stageStats
        )
    }

    /**
     * List all pipeline statistics
     */
    fun listAllStats(): List<StatsSummary> {
        val dir = File(statsDir)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val stats = json.decodeFromString<PipelineStats>(file.readText())
                    getStatsSummary(stats.pipelineName)
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.totalExecutions }
            ?: emptyList()
    }

    /**
     * Calculate performance trend
     */
    private fun calculateTrend(executions: List<PipelineExecution>): PerformanceTrend {
        if (executions.size < 2) return PerformanceTrend.STABLE

        val recent = executions.takeLast(3).map { it.totalDuration }.average()
        val previous = executions.dropLast(3).takeLast(3).map { it.totalDuration }.average()

        return when {
            recent < previous * 0.9 -> PerformanceTrend.IMPROVING
            recent > previous * 1.1 -> PerformanceTrend.DEGRADING
            else -> PerformanceTrend.STABLE
        }
    }

    /**
     * Clear statistics for a pipeline
     */
    fun clearStats(pipelineName: String): Boolean {
        return getStatsFile(pipelineName).delete()
    }

    /**
     * Get execution history for a pipeline
     */
    fun getExecutionHistory(pipelineName: String, count: Int): List<PipelineExecution> {
        val stats = loadStats(pipelineName) ?: return emptyList()
        return stats.executions.takeLast(count)
    }

    private fun getStatsFile(pipelineName: String): File {
        val safeName = pipelineName.replace("[^a-zA-Z0-9-_]".toRegex(), "_")
        return File(statsDir, "$safeName.json")
    }
}

/**
 * Pipeline statistics data
 */
@Serializable
data class PipelineStats(
    val pipelineName: String,
    val executions: List<PipelineExecution> = emptyList(),
    val totalExecutions: Int = 0,
    val totalSuccesses: Int = 0,
    val totalFailures: Int = 0,
    val totalDuration: Long = 0,
    val lastExecuted: String? = null,
    val failureCategoryCounts: Map<FailureCategory, Int> = emptyMap()
)

/**
 * Single pipeline execution record
 */
@Serializable
data class PipelineExecution(
    val pipelineName: String,
    val timestamp: String,
    val totalDuration: Long,
    val successCount: Int,
    val failureCount: Int,
    val totalAgents: Int,
    val stages: List<StageExecution> = emptyList()
)

@Serializable
data class StageExecution(
    val name: String,
    val duration: Long,
    val status: ExecutionStatus,
    val retries: Int
)

@Serializable
enum class ExecutionStatus {
    SUCCESS,
    FAILURE
}

/**
 * Statistics summary
 */
data class StatsSummary(
    val pipelineName: String,
    val totalExecutions: Int,
    val successRate: Double,
    val avgDuration: Long,
    val avgRecentDuration: Long,
    val lastExecuted: String?,
    val trend: PerformanceTrend,
    val failureCategoryCounts: Map<FailureCategory, Int> = emptyMap()
)

/**
 * Performance trend
 */
enum class PerformanceTrend {
    IMPROVING,
    STABLE,
    DEGRADING
}

/**
 * Detailed statistics, including stage-level data
 */
data class StatsDetails(
    val pipelineName: String,
    val totalExecutions: Int,
    val stages: List<StageStats>
)

/**
 * Stage statistics
 */
data class StageStats(
    val stageName: String,
    val avgDuration: Long,
    val successRate: Double,
    val avgRetries: Double
)
