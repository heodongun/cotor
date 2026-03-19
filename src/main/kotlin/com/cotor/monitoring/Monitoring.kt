package com.cotor.monitoring

/**
 * File overview for StructuredLogLevel.
 *
 * This file belongs to the observability layer for metrics, traces, and pipeline monitoring.
 * It groups declarations around monitoring so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import com.cotor.model.AgentMetrics
import com.cotor.model.MemoryStatus
import com.cotor.model.PerformanceConfig
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.Logger
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class StructuredLogLevel {
    INFO,
    WARN,
    ERROR
}

/**
 * Structured logger for JSON logging
 */
class StructuredLogger(
    private val logger: Logger
) {
    fun logObservationEvent(
        event: String,
        traceContext: TraceContext? = null,
        level: StructuredLogLevel = StructuredLogLevel.INFO,
        additionalContext: Map<String, Any?> = emptyMap()
    ) {
        val logData = buildMap<String, JsonElement> {
            put("event", event)
            put("timestamp", java.time.Instant.now().toString())
            traceContext?.let {
                put("trace_id", it.traceId)
                put("span_id", it.spanId)
                it.parentSpanId?.let { parent -> put("parent_span_id", parent) }
            }
            additionalContext.forEach { (key, value) ->
                put(key, value)
            }
        }

        val jsonLog = JsonObject(logData).toString()
        when (level) {
            StructuredLogLevel.INFO -> logger.info(jsonLog)
            StructuredLogLevel.WARN -> logger.warn(jsonLog)
            StructuredLogLevel.ERROR -> logger.error(jsonLog)
        }
    }

    fun logAgentExecution(
        agentName: String,
        duration: Long,
        success: Boolean,
        additionalContext: Map<String, Any?> = emptyMap()
    ) {
        logObservationEvent(
            event = "agent_execution",
            level = if (success) StructuredLogLevel.INFO else StructuredLogLevel.ERROR,
            additionalContext = mapOf(
                "agent_name" to agentName,
                "duration_ms" to duration,
                "success" to success
            ) + additionalContext
        )
    }

    fun logPipelineExecution(
        pipelineName: String,
        totalDuration: Long,
        successCount: Int,
        failureCount: Int
    ) {
        logObservationEvent(
            event = "pipeline_execution",
            additionalContext = mapOf(
                "pipeline_name" to pipelineName,
                "total_duration_ms" to totalDuration,
                "success_count" to successCount,
                "failure_count" to failureCount
            )
        )
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: Any?) {
        this[key] = value.toJsonElement()
    }

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }
}

data class ExecutionMetrics(
    val name: String,
    val totalExecutions: Int,
    val successCount: Int,
    val failureCount: Int,
    val averageDuration: Double,
    val minDuration: Long,
    val maxDuration: Long
)

/**
 * Metrics collector for agent performance
 */
class MetricsCollector {
    private val agentExecutionTimes = ConcurrentHashMap<String, MutableList<Long>>()
    private val agentSuccessRates = ConcurrentHashMap<String, AtomicInteger>()
    private val agentFailureRates = ConcurrentHashMap<String, AtomicInteger>()
    private val pipelineExecutionTimes = ConcurrentHashMap<String, MutableList<Long>>()
    private val pipelineSuccessRates = ConcurrentHashMap<String, AtomicInteger>()
    private val pipelineFailureRates = ConcurrentHashMap<String, AtomicInteger>()
    private val stageExecutionTimes = ConcurrentHashMap<String, MutableList<Long>>()
    private val stageSuccessRates = ConcurrentHashMap<String, AtomicInteger>()
    private val stageFailureRates = ConcurrentHashMap<String, AtomicInteger>()

    fun recordExecution(agentName: String, duration: Long, success: Boolean) {
        record(agentExecutionTimes, agentSuccessRates, agentFailureRates, agentName, duration, success)
    }

    fun recordPipelineExecution(pipelineName: String, duration: Long, success: Boolean) {
        record(pipelineExecutionTimes, pipelineSuccessRates, pipelineFailureRates, pipelineName, duration, success)
    }

    fun recordStageExecution(stageId: String, duration: Long, success: Boolean) {
        record(stageExecutionTimes, stageSuccessRates, stageFailureRates, stageId, duration, success)
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

    fun getPipelineMetrics(pipelineName: String): ExecutionMetrics {
        return executionMetricsOf(
            name = pipelineName,
            executionTimes = pipelineExecutionTimes[pipelineName],
            successCount = pipelineSuccessRates[pipelineName]?.get(),
            failureCount = pipelineFailureRates[pipelineName]?.get()
        )
    }

    fun getStageMetrics(stageId: String): ExecutionMetrics {
        return executionMetricsOf(
            name = stageId,
            executionTimes = stageExecutionTimes[stageId],
            successCount = stageSuccessRates[stageId]?.get(),
            failureCount = stageFailureRates[stageId]?.get()
        )
    }

    private fun record(
        executionTimes: ConcurrentHashMap<String, MutableList<Long>>,
        successRates: ConcurrentHashMap<String, AtomicInteger>,
        failureRates: ConcurrentHashMap<String, AtomicInteger>,
        name: String,
        duration: Long,
        success: Boolean
    ) {
        executionTimes.computeIfAbsent(name) { Collections.synchronizedList(mutableListOf()) }
            .add(duration)

        if (success) {
            successRates.computeIfAbsent(name) { AtomicInteger(0) }
                .incrementAndGet()
        } else {
            failureRates.computeIfAbsent(name) { AtomicInteger(0) }
                .incrementAndGet()
        }
    }

    private fun executionMetricsOf(
        name: String,
        executionTimes: List<Long>?,
        successCount: Int?,
        failureCount: Int?
    ): ExecutionMetrics {
        val durations = executionTimes ?: emptyList()
        val successes = successCount ?: 0
        val failures = failureCount ?: 0

        return ExecutionMetrics(
            name = name,
            totalExecutions = successes + failures,
            successCount = successes,
            failureCount = failures,
            averageDuration = if (durations.isNotEmpty()) durations.average() else 0.0,
            minDuration = durations.minOrNull() ?: 0,
            maxDuration = durations.maxOrNull() ?: 0
        )
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
