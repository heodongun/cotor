package com.cotor.monitoring

import java.security.SecureRandom

data class TraceContext(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String? = null
)

class ObservabilityService(
    private val structuredLogger: StructuredLogger,
    private val metricsCollector: MetricsCollector
) {
    private val random = SecureRandom()

    fun startPipelineTrace(pipelineName: String, pipelineId: String): TraceContext {
        val context = TraceContext(traceId = randomHex(32), spanId = randomHex(16))
        structuredLogger.logLifecycle(
            event = "pipeline_start",
            name = pipelineName,
            success = true,
            context = context,
            additionalContext = mapOf("pipeline_id" to pipelineId)
        )
        return context
    }

    fun childContext(traceId: String, parentSpanId: String): TraceContext {
        return TraceContext(traceId = traceId, spanId = randomHex(16), parentSpanId = parentSpanId)
    }

    fun logStageLifecycle(event: String, stageId: String, success: Boolean, context: TraceContext) {
        structuredLogger.logLifecycle(event, stageId, success, context)
    }

    fun recordAgentExecution(agentName: String, duration: Long, success: Boolean, context: TraceContext) {
        structuredLogger.logAgentExecution(
            agentName = agentName,
            duration = duration,
            success = success,
            additionalContext = mapOf(
                "trace_id" to context.traceId,
                "span_id" to context.spanId,
                "parent_span_id" to (context.parentSpanId ?: "")
            )
        )
        metricsCollector.recordExecution(agentName, duration, success)
    }

    private fun randomHex(bytes: Int): String {
        val buffer = ByteArray(bytes / 2)
        random.nextBytes(buffer)
        return buffer.joinToString("") { "%02x".format(it) }
    }
}
