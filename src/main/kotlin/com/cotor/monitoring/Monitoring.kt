package com.cotor.monitoring

import com.cotor.model.AgentMetrics
import com.cotor.model.MemoryStatus
import com.cotor.model.PerformanceConfig
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Structured logger for JSON logging
 */
class StructuredLogger(
    private val logger: Logger
) {
    private val json = Json { prettyPrint = false }

    fun logAgentExecution(
        agentName: String,
        duration: Long,
        success: Boolean,
        additionalContext: Map<String, Any> = emptyMap()
    ) {
        val logData = mapOf(
            "event" to "agent_execution",
            "agent_name" to agentName,
            "duration_ms" to duration,
            "success" to success,
            "timestamp" to java.time.Instant.now().toString()
        ) + additionalContext

        val jsonLog = json.encodeToString(logData)

        if (success) {
            logger.info(jsonLog)
        } else {
            logger.error(jsonLog)
        }
    }

    fun logPipelineExecution(
        pipelineName: String,
        totalDuration: Long,
        successCount: Int,
        failureCount: Int
    ) {
        val logData = mapOf(
            "event" to "pipeline_execution",
            "pipeline_name" to pipelineName,
            "total_duration_ms" to totalDuration,
            "success_count" to successCount,
            "failure_count" to failureCount,
            "timestamp" to java.time.Instant.now().toString()
        )

        logger.info(json.encodeToString(logData))
    }
}

/**
 * Metrics collector for agent performance
 */
class MetricsCollector {
    private val agentExecutionTimes = ConcurrentHashMap<String, MutableList<Long>>()
    private val agentSuccessRates = ConcurrentHashMap<String, AtomicInteger>()
    private val agentFailureRates = ConcurrentHashMap<String, AtomicInteger>()

    fun recordExecution(agentName: String, duration: Long, success: Boolean) {
        agentExecutionTimes.computeIfAbsent(agentName) { mutableListOf() }
            .add(duration)

        if (success) {
            agentSuccessRates.computeIfAbsent(agentName) { AtomicInteger(0) }
                .incrementAndGet()
        } else {
            agentFailureRates.computeIfAbsent(agentName) { AtomicInteger(0) }
                .incrementAndGet()
        }
    }

    fun getMetrics(agentName: String): AgentMetrics {
        val executionTimes = agentExecutionTimes[agentName] ?: emptyList()
        val successCount = agentSuccessRates[agentName]?.get() ?: 0
        val failureCount = agentFailureRates[agentName]?.get() ?: 0

        return AgentMetrics(
            agentName = agentName,
            totalExecutions = successCount + failureCount,
            successCount = successCount,
            failureCount = failureCount,
            averageDuration = if (executionTimes.isNotEmpty()) executionTimes.average() else 0.0,
            minDuration = executionTimes.minOrNull() ?: 0,
            maxDuration = executionTimes.maxOrNull() ?: 0
        )
    }

    fun getAllMetrics(): Map<String, AgentMetrics> {
        return agentExecutionTimes.keys.associateWith { getMetrics(it) }
    }
}

/**
 * Resource monitor for memory and performance
 */
class ResourceMonitor(
    private val config: PerformanceConfig,
    private val logger: Logger
) {
    private val runtime = Runtime.getRuntime()

    fun checkMemoryUsage(): MemoryStatus {
        val usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMemoryMB = runtime.maxMemory() / (1024 * 1024)
        val usagePercent = (usedMemoryMB.toDouble() / maxMemoryMB * 100).toInt()

        return when {
            usagePercent > 90 -> {
                logger.warn("Critical memory usage: $usagePercent%")
                MemoryStatus.CRITICAL
            }
            usagePercent > 75 -> {
                logger.warn("High memory usage: $usagePercent%")
                MemoryStatus.HIGH
            }
            else -> MemoryStatus.NORMAL
        }
    }

    suspend fun enforceResourceLimits(activeAgents: Int) {
        if (activeAgents >= config.maxConcurrentAgents) {
            logger.info("Max concurrent agents reached, waiting...")
            delay(1000)
        }

        val memoryStatus = checkMemoryUsage()
        if (memoryStatus == MemoryStatus.CRITICAL) {
            System.gc()
            delay(500)
        }
    }
}
