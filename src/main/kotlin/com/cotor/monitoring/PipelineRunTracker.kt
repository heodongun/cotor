package com.cotor.monitoring

import com.cotor.event.EventBus
import com.cotor.event.EventSubscription
import com.cotor.event.PipelineCompletedEvent
import com.cotor.event.PipelineFailedEvent
import com.cotor.event.PipelineStartedEvent
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Lifecycle status for a pipeline execution.
 */
enum class PipelineRunStatus {
    RUNNING,
    COMPLETED,
    FAILED
}

/**
 * Snapshot of a single pipeline run for status reporting.
 */
data class PipelineRunSnapshot(
    val pipelineId: String,
    val pipelineName: String,
    val status: PipelineRunStatus,
    val startedAt: Instant,
    val updatedAt: Instant,
    val totalDurationMs: Long? = null,
    val successCount: Int? = null,
    val failureCount: Int? = null,
    val message: String? = null
) {
    val elapsed: Duration
        get() = Duration.between(startedAt, updatedAt)
}

/**
 * Tracks active and recent pipelines by subscribing to pipeline events.
 */
class PipelineRunTracker(
    eventBus: EventBus,
    private val maxHistory: Int = 20
) {
    private val runs = ConcurrentHashMap<String, PipelineRunSnapshot>()
    private val history = ConcurrentLinkedDeque<String>()
    @Suppress("unused")
    private val subscriptions: List<EventSubscription>

    init {
        subscriptions = listOf(
            eventBus.subscribe(PipelineStartedEvent::class) { handleStarted(it as PipelineStartedEvent) },
            eventBus.subscribe(PipelineCompletedEvent::class) { handleCompleted(it as PipelineCompletedEvent) },
            eventBus.subscribe(PipelineFailedEvent::class) { handleFailed(it as PipelineFailedEvent) }
        )
    }

    fun getActiveRuns(): List<PipelineRunSnapshot> {
        return runs.values
            .filter { it.status == PipelineRunStatus.RUNNING }
            .sortedByDescending { it.startedAt }
    }

    fun getRecentRuns(limit: Int = 5): List<PipelineRunSnapshot> {
        return history
            .asSequence()
            .mapNotNull { runs[it] }
            .distinctBy { it.pipelineId }
            .take(limit)
            .toList()
    }

    fun getPipeline(pipelineId: String): PipelineRunSnapshot? = runs[pipelineId]

    private fun handleStarted(event: PipelineStartedEvent) {
        val snapshot = PipelineRunSnapshot(
            pipelineId = event.pipelineId,
            pipelineName = event.pipelineName,
            status = PipelineRunStatus.RUNNING,
            startedAt = event.timestamp,
            updatedAt = event.timestamp
        )
        runs[event.pipelineId] = snapshot
        trackHistory(event.pipelineId)
    }

    private fun handleCompleted(event: PipelineCompletedEvent) {
        val existing = runs[event.pipelineId]
        val snapshot = (existing ?: PipelineRunSnapshot(
            pipelineId = event.pipelineId,
            pipelineName = event.pipelineId,
            status = PipelineRunStatus.RUNNING,
            startedAt = event.timestamp,
            updatedAt = event.timestamp
        )).copy(
            status = PipelineRunStatus.COMPLETED,
            updatedAt = event.timestamp,
            totalDurationMs = event.result.totalDuration,
            successCount = event.result.successCount,
            failureCount = event.result.failureCount,
            message = null
        )

        runs[event.pipelineId] = snapshot
        trackHistory(event.pipelineId)
    }

    private fun handleFailed(event: PipelineFailedEvent) {
        val existing = runs[event.pipelineId]
        val snapshot = (existing ?: PipelineRunSnapshot(
            pipelineId = event.pipelineId,
            pipelineName = event.pipelineId,
            status = PipelineRunStatus.RUNNING,
            startedAt = event.timestamp,
            updatedAt = event.timestamp
        )).copy(
            status = PipelineRunStatus.FAILED,
            updatedAt = event.timestamp,
            message = event.error.message ?: event.error::class.simpleName ?: "Unknown error"
        )

        runs[event.pipelineId] = snapshot
        trackHistory(event.pipelineId)
    }

    private fun trackHistory(pipelineId: String) {
        history.remove(pipelineId)
        history.addFirst(pipelineId)
        while (history.size > maxHistory) {
            history.removeLast()
        }
    }
}
