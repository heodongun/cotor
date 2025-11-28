package com.cotor.monitoring

import com.cotor.event.CotorEvent
import com.cotor.event.EventBus
import com.cotor.event.EventSubscription
import com.cotor.event.PipelineCompletedEvent
import com.cotor.event.PipelineFailedEvent
import com.cotor.event.PipelineStartedEvent
import com.cotor.model.AggregatedResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.reflect.KClass

class PipelineRunTrackerTest : FunSpec({

    test("tracks running and completed pipelines") {
        val eventBus = ImmediateEventBus()
        val tracker = PipelineRunTracker(eventBus)
        val start = Instant.parse("2024-01-01T00:00:00Z")

        runBlocking {
            eventBus.emit(PipelineStartedEvent("p1", "demo", start))
            eventBus.emit(PipelineCompletedEvent("p1", completedResult(duration = 1200), start.plusMillis(1200)))
        }

        tracker.getActiveRuns() shouldBe emptyList()
        val recent = tracker.getRecentRuns()
        recent.first().status shouldBe PipelineRunStatus.COMPLETED
        recent.first().totalDurationMs shouldBe 1200
    }

    test("captures failure reason when pipeline fails before completion") {
        val eventBus = ImmediateEventBus()
        val tracker = PipelineRunTracker(eventBus)
        val failure = RuntimeException("boom")

        runBlocking {
            eventBus.emit(PipelineStartedEvent("p2", "demo", Instant.parse("2024-01-02T00:00:00Z")))
            eventBus.emit(PipelineFailedEvent("p2", failure, Instant.parse("2024-01-02T00:00:01Z")))
        }

        val recent = tracker.getRecentRuns()
        recent.first().status shouldBe PipelineRunStatus.FAILED
        recent.first().message.shouldContain("boom")
    }
})

private fun completedResult(duration: Long): AggregatedResult {
    return AggregatedResult(
        totalAgents = 1,
        successCount = 1,
        failureCount = 0,
        totalDuration = duration,
        results = emptyList(),
        aggregatedOutput = "ok",
        timestamp = Instant.now(),
        analysis = null
    )
}

/**
 * Simple synchronous event bus for deterministic testing.
 */
private class ImmediateEventBus : EventBus {
    private val handlers = mutableMapOf<KClass<out CotorEvent>, MutableList<suspend (CotorEvent) -> Unit>>()

    override suspend fun emit(event: CotorEvent) {
        handlers[event::class]?.forEach { handler ->
            handler(event)
        }
    }

    override fun subscribe(eventType: KClass<out CotorEvent>, handler: suspend (CotorEvent) -> Unit): EventSubscription {
        handlers.computeIfAbsent(eventType) { mutableListOf() }.add(handler)
        return EventSubscription(eventType, handler)
    }

    override fun unsubscribe(subscription: EventSubscription) {
        handlers[subscription.eventType]?.remove(subscription.handler)
    }
}
