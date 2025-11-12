package com.cotor.event

import com.cotor.model.AgentResult
import com.cotor.model.AggregatedResult
import java.time.Instant

/**
 * Base event class for all Cotor events
 */
sealed class CotorEvent {
    abstract val timestamp: Instant
}

/**
 * Pipeline started event
 */
data class PipelineStartedEvent(
    val pipelineId: String,
    val pipelineName: String,
    override val timestamp: Instant = Instant.now()
) : CotorEvent()

/**
 * Pipeline completed successfully event
 */
data class PipelineCompletedEvent(
    val pipelineId: String,
    val result: AggregatedResult,
    override val timestamp: Instant = Instant.now()
) : CotorEvent()

/**
 * Pipeline failed event
 */
data class PipelineFailedEvent(
    val pipelineId: String,
    val error: Throwable,
    override val timestamp: Instant = Instant.now()
) : CotorEvent()

/**
 * Agent started event
 */
data class AgentStartedEvent(
    val agentName: String,
    val pipelineId: String?,
    override val timestamp: Instant = Instant.now()
) : CotorEvent()

/**
 * Agent completed successfully event
 */
data class AgentCompletedEvent(
    val agentName: String,
    val result: AgentResult,
    override val timestamp: Instant = Instant.now()
) : CotorEvent()

/**
 * Agent failed event
 */
data class AgentFailedEvent(
    val agentName: String,
    val error: String,
    override val timestamp: Instant = Instant.now()
) : CotorEvent()
