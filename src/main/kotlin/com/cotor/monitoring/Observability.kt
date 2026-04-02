package com.cotor.monitoring

/**
 * File overview for TraceContext.
 *
 * This file belongs to the observability layer for metrics, traces, and pipeline monitoring.
 * It groups declarations around observability so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.model.AgentExecutionMetadata
import com.cotor.model.AgentResult
import com.cotor.model.AggregatedResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TraceContext(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String? = null
)

data class ObservationHandle(
    val kind: String,
    val name: String,
    val traceContext: TraceContext,
    val startedAtMs: Long,
    val attributes: Map<String, String>
) {
    fun elapsedMs(): Long = System.currentTimeMillis() - startedAtMs
}

interface ObservabilityService {
    fun startPipeline(pipelineId: String, pipelineName: String, totalStages: Int): ObservationHandle?

    fun completePipeline(observation: ObservationHandle?, result: AggregatedResult)

    fun failPipeline(observation: ObservationHandle?, error: Throwable)

    fun startStage(pipelineId: String, stageId: String, agentName: String? = null): ObservationHandle?

    fun completeStage(observation: ObservationHandle?, result: AgentResult)

    fun failStage(observation: ObservationHandle?, error: Throwable)

    fun startAgent(agentName: String, metadata: AgentExecutionMetadata): ObservationHandle?

    fun completeAgent(observation: ObservationHandle?, metadata: AgentExecutionMetadata, result: AgentResult): Map<String, String>

    fun failAgent(
        observation: ObservationHandle?,
        metadata: AgentExecutionMetadata,
        durationMs: Long,
        error: Throwable
    ): Map<String, String>
}

object NoopObservabilityService : ObservabilityService {
    override fun startPipeline(pipelineId: String, pipelineName: String, totalStages: Int): ObservationHandle? = null

    override fun completePipeline(observation: ObservationHandle?, result: AggregatedResult) = Unit

    override fun failPipeline(observation: ObservationHandle?, error: Throwable) = Unit

    override fun startStage(pipelineId: String, stageId: String, agentName: String?): ObservationHandle? = null

    override fun completeStage(observation: ObservationHandle?, result: AgentResult) = Unit

    override fun failStage(observation: ObservationHandle?, error: Throwable) = Unit

    override fun startAgent(agentName: String, metadata: AgentExecutionMetadata): ObservationHandle? = null

    override fun completeAgent(
        observation: ObservationHandle?,
        metadata: AgentExecutionMetadata,
        result: AgentResult
    ): Map<String, String> = emptyMap()

    override fun failAgent(
        observation: ObservationHandle?,
        metadata: AgentExecutionMetadata,
        durationMs: Long,
        error: Throwable
    ): Map<String, String> = emptyMap()
}

class DefaultObservabilityService(
    private val structuredLogger: StructuredLogger,
    private val metricsCollector: MetricsCollector
) : ObservabilityService {
    private val pipelineObservations = ConcurrentHashMap<String, ObservationHandle>()
    private val stageObservations = ConcurrentHashMap<String, ObservationHandle>()

    override fun startPipeline(pipelineId: String, pipelineName: String, totalStages: Int): ObservationHandle {
        val observation = ObservationHandle(
            kind = "pipeline",
            name = pipelineName,
            traceContext = newRootTrace(),
            startedAtMs = System.currentTimeMillis(),
            attributes = mapOf(
                "pipeline_id" to pipelineId,
                "pipeline_name" to pipelineName,
                "total_stages" to totalStages.toString()
            )
        )

        pipelineObservations[pipelineId] = observation
        structuredLogger.logObservationEvent(
            event = "pipeline_started",
            traceContext = observation.traceContext,
            additionalContext = observation.attributes
        )
        return observation
    }

    override fun completePipeline(observation: ObservationHandle?, result: AggregatedResult) {
        observation ?: return
        val pipelineId = observation.attributes["pipeline_id"]
        pipelineId?.let { id ->
            pipelineObservations.remove(id)
            stageObservations.keys.removeIf { key -> key.startsWith("$id:") }
        }
        metricsCollector.recordPipelineExecution(observation.name, result.totalDuration, result.failureCount == 0)
        structuredLogger.logObservationEvent(
            event = "pipeline_completed",
            traceContext = observation.traceContext,
            additionalContext = observation.attributes + mapOf(
                "duration_ms" to result.totalDuration,
                "success_count" to result.successCount,
                "failure_count" to result.failureCount
            )
        )
    }

    override fun failPipeline(observation: ObservationHandle?, error: Throwable) {
        observation ?: return
        val pipelineId = observation.attributes["pipeline_id"]
        pipelineId?.let { id ->
            pipelineObservations.remove(id)
            stageObservations.keys.removeIf { key -> key.startsWith("$id:") }
        }
        metricsCollector.recordPipelineExecution(observation.name, observation.elapsedMs(), success = false)
        structuredLogger.logObservationEvent(
            event = "pipeline_failed",
            traceContext = observation.traceContext,
            level = StructuredLogLevel.ERROR,
            additionalContext = observation.attributes + mapOf(
                "duration_ms" to observation.elapsedMs(),
                "error" to (error.message ?: error::class.simpleName ?: "Unknown error")
            )
        )
    }

    override fun startStage(pipelineId: String, stageId: String, agentName: String?): ObservationHandle {
        val parent = pipelineObservations[pipelineId]?.traceContext
        val observation = ObservationHandle(
            kind = "stage",
            name = stageId,
            traceContext = newChildTrace(parent),
            startedAtMs = System.currentTimeMillis(),
            attributes = buildMap {
                put("pipeline_id", pipelineId)
                put("stage_id", stageId)
                agentName?.let { put("agent_name", it) }
            }
        )

        stageObservations[stageKey(pipelineId, stageId)] = observation
        structuredLogger.logObservationEvent(
            event = "stage_started",
            traceContext = observation.traceContext,
            additionalContext = observation.attributes
        )
        return observation
    }

    override fun completeStage(observation: ObservationHandle?, result: AgentResult) {
        observation ?: return
        removeStageObservation(observation)
        metricsCollector.recordStageExecution(observation.name, result.duration, result.isSuccess)
        structuredLogger.logObservationEvent(
            event = "stage_completed",
            traceContext = observation.traceContext,
            level = if (result.isSuccess) StructuredLogLevel.INFO else StructuredLogLevel.ERROR,
            additionalContext = observation.attributes + mapOf(
                "duration_ms" to result.duration,
                "success" to result.isSuccess,
                "error" to (result.error ?: "")
            )
        )
    }

    override fun failStage(observation: ObservationHandle?, error: Throwable) {
        observation ?: return
        removeStageObservation(observation)
        metricsCollector.recordStageExecution(observation.name, observation.elapsedMs(), success = false)
        structuredLogger.logObservationEvent(
            event = "stage_failed",
            traceContext = observation.traceContext,
            level = StructuredLogLevel.ERROR,
            additionalContext = observation.attributes + mapOf(
                "duration_ms" to observation.elapsedMs(),
                "error" to (error.message ?: error::class.simpleName ?: "Unknown error")
            )
        )
    }

    override fun startAgent(agentName: String, metadata: AgentExecutionMetadata): ObservationHandle {
        val pipelineId = metadata.pipelineContext?.pipelineId
        val stageId = metadata.stageId
        val parent = when {
            pipelineId != null && stageId != null -> stageObservations[stageKey(pipelineId, stageId)]?.traceContext
            pipelineId != null -> pipelineObservations[pipelineId]?.traceContext
            else -> null
        }

        val observation = ObservationHandle(
            kind = "agent",
            name = agentName,
            traceContext = newChildTrace(parent),
            startedAtMs = System.currentTimeMillis(),
            attributes = buildMap {
                put("agent_name", agentName)
                pipelineId?.let { put("pipeline_id", it) }
                metadata.pipelineContext?.pipelineName?.let { put("pipeline_name", it) }
                stageId?.let { put("stage_id", it) }
            }
        )

        structuredLogger.logObservationEvent(
            event = "agent_started",
            traceContext = observation.traceContext,
            additionalContext = observation.attributes
        )
        return observation
    }

    override fun completeAgent(
        observation: ObservationHandle?,
        metadata: AgentExecutionMetadata,
        result: AgentResult
    ): Map<String, String> {
        observation ?: return emptyMap()
        metricsCollector.recordExecution(observation.name, result.duration, result.isSuccess)
        structuredLogger.logObservationEvent(
            event = "agent_completed",
            traceContext = observation.traceContext,
            level = if (result.isSuccess) StructuredLogLevel.INFO else StructuredLogLevel.ERROR,
            additionalContext = observation.attributes + mapOf(
                "duration_ms" to result.duration,
                "success" to result.isSuccess,
                "error" to (result.error ?: "")
            )
        )
        return metadataFields(observation)
    }

    override fun failAgent(
        observation: ObservationHandle?,
        metadata: AgentExecutionMetadata,
        durationMs: Long,
        error: Throwable
    ): Map<String, String> {
        observation ?: return emptyMap()
        metricsCollector.recordExecution(observation.name, durationMs, success = false)
        structuredLogger.logObservationEvent(
            event = "agent_failed",
            traceContext = observation.traceContext,
            level = StructuredLogLevel.ERROR,
            additionalContext = observation.attributes + mapOf(
                "duration_ms" to durationMs,
                "error" to (error.message ?: error::class.simpleName ?: "Unknown error")
            )
        )
        return metadataFields(observation)
    }

    private fun metadataFields(observation: ObservationHandle): Map<String, String> {
        return buildMap {
            put("traceId", observation.traceContext.traceId)
            put("spanId", observation.traceContext.spanId)
            observation.traceContext.parentSpanId?.let { put("parentSpanId", it) }
            putAll(observation.attributes)
        }
    }

    private fun removeStageObservation(observation: ObservationHandle) {
        val pipelineId = observation.attributes["pipeline_id"] ?: return
        val stageId = observation.attributes["stage_id"] ?: return
        stageObservations.remove(stageKey(pipelineId, stageId))
    }

    private fun stageKey(pipelineId: String, stageId: String): String = "$pipelineId:$stageId"

    private fun newRootTrace(): TraceContext {
        return TraceContext(
            traceId = UUID.randomUUID().toString().replace("-", ""),
            spanId = newSpanId()
        )
    }

    private fun newChildTrace(parent: TraceContext?): TraceContext {
        return if (parent == null) {
            newRootTrace()
        } else {
            TraceContext(
                traceId = parent.traceId,
                spanId = newSpanId(),
                parentSpanId = parent.spanId
            )
        }
    }

    private fun newSpanId(): String = UUID.randomUUID().toString().replace("-", "").takeLast(16)
}
