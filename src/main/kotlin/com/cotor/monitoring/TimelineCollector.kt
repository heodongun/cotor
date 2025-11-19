package com.cotor.monitoring

import com.cotor.event.*
import com.cotor.presentation.timeline.StageTimelineEntry
import com.cotor.presentation.timeline.StageTimelineState
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

data class TimelineResult<T>(
    val result: T,
    val timeline: List<StageTimelineEntry>,
    val totalDurationMs: Long?
)

class TimelineCollector(
    private val eventBus: EventBus
) {
    suspend fun <T> runWithTimeline(
        pipelineName: String,
        block: suspend () -> T
    ): TimelineResult<T> {
        val timeline = CopyOnWriteArrayList<StageTimelineEntry>()
        val subscriptions = mutableListOf<EventSubscription>()
        val pipelineIdRef = AtomicReference<String?>(null)
        val pipelineStartRef = AtomicReference<Instant?>()
        val pipelineEndRef = AtomicReference<Instant?>()

        subscriptions += eventBus.subscribe(PipelineStartedEvent::class) { event ->
            val pipelineEvent = event as PipelineStartedEvent
            if (pipelineEvent.pipelineName == pipelineName) {
                pipelineIdRef.set(pipelineEvent.pipelineId)
                pipelineStartRef.set(pipelineEvent.timestamp)
            }
        }

        subscriptions += eventBus.subscribe(PipelineCompletedEvent::class) { event ->
            val completedEvent = event as PipelineCompletedEvent
            val targetId = pipelineIdRef.get() ?: return@subscribe
            if (completedEvent.pipelineId == targetId) {
                pipelineEndRef.set(completedEvent.timestamp)
            }
        }

        subscriptions += eventBus.subscribe(PipelineFailedEvent::class) { event ->
            val failedEvent = event as PipelineFailedEvent
            val targetId = pipelineIdRef.get() ?: return@subscribe
            if (failedEvent.pipelineId == targetId) {
                pipelineEndRef.set(failedEvent.timestamp)
            }
        }

        subscriptions += eventBus.subscribe(StageStartedEvent::class) { event ->
            val startedEvent = event as StageStartedEvent
            val targetId = pipelineIdRef.get() ?: return@subscribe
            if (startedEvent.pipelineId == targetId) {
                timeline += StageTimelineEntry(
                    stageId = startedEvent.stageId,
                    state = StageTimelineState.STARTED,
                    message = "Stage started",
                    timestamp = startedEvent.timestamp
                )
            }
        }

        subscriptions += eventBus.subscribe(StageCompletedEvent::class) { event ->
            val completedEvent = event as StageCompletedEvent
            val targetId = pipelineIdRef.get() ?: return@subscribe
            if (completedEvent.pipelineId == targetId) {
                val outputPreview = completedEvent.result.output?.take(200)
                timeline += StageTimelineEntry(
                    stageId = completedEvent.stageId,
                    state = StageTimelineState.COMPLETED,
                    message = "Completed successfully",
                    durationMs = completedEvent.result.duration,
                    outputPreview = outputPreview,
                    timestamp = completedEvent.timestamp
                )
            }
        }

        subscriptions += eventBus.subscribe(StageFailedEvent::class) { event ->
            val failedEvent = event as StageFailedEvent
            val targetId = pipelineIdRef.get() ?: return@subscribe
            if (failedEvent.pipelineId == targetId) {
                timeline += StageTimelineEntry(
                    stageId = failedEvent.stageId,
                    state = StageTimelineState.FAILED,
                    message = failedEvent.error.message ?: "Stage failed",
                    timestamp = failedEvent.timestamp
                )
            }
        }

        return try {
            val result = block()
            val durationMs = pipelineStartRef.get()?.let { start ->
                val end = pipelineEndRef.get() ?: Instant.now()
                Duration.between(start, end).toMillis()
            }
            TimelineResult(result, timeline.sortedBy { it.timestamp }, durationMs)
        } finally {
            subscriptions.forEach { eventBus.unsubscribe(it) }
        }
    }
}
