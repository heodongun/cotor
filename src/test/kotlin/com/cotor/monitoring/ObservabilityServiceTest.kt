package com.cotor.monitoring

/**
 * File overview for ObservabilityServiceTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around observability service test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.model.AgentExecutionMetadata
import com.cotor.model.AgentResult
import com.cotor.model.AggregatedResult
import com.cotor.model.PipelineContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.slf4j.Logger
import java.time.Instant

class ObservabilityServiceTest : FunSpec({

    test("records hierarchical trace ids and metrics across pipeline stage and agent lifecycles") {
        val logger = mockk<Logger>(relaxed = true)
        val metricsCollector = MetricsCollector()
        val service: ObservabilityService = DefaultObservabilityService(
            structuredLogger = StructuredLogger(logger),
            metricsCollector = metricsCollector
        )

        val pipeline = service.startPipeline("pipeline-1", "demo-pipeline", totalStages = 1)
        val stage = service.startStage("pipeline-1", "stage-1", "agent-a")
        val metadata = AgentExecutionMetadata(
            pipelineContext = PipelineContext("pipeline-1", "demo-pipeline", totalStages = 1),
            stageId = "stage-1"
        )
        val agent = service.startAgent("agent-a", metadata)

        val agentResult = AgentResult(
            agentName = "agent-a",
            isSuccess = true,
            output = "ok",
            error = null,
            duration = 25,
            metadata = emptyMap()
        )
        val agentMetadata = service.completeAgent(agent, metadata, agentResult)
        service.completeStage(stage, agentResult)
        service.completePipeline(
            pipeline,
            AggregatedResult(
                totalAgents = 1,
                successCount = 1,
                failureCount = 0,
                totalDuration = 25,
                results = listOf(agentResult),
                aggregatedOutput = "ok",
                timestamp = Instant.parse("2026-03-11T00:00:00Z"),
                analysis = null
            )
        )

        stage?.traceContext?.traceId shouldBe pipeline?.traceContext?.traceId
        stage?.traceContext?.parentSpanId shouldBe pipeline?.traceContext?.spanId
        agentMetadata["traceId"] shouldBe pipeline?.traceContext?.traceId
        agentMetadata["parentSpanId"] shouldBe stage?.traceContext?.spanId
        metricsCollector.getMetrics("agent-a").successCount shouldBe 1
        metricsCollector.getStageMetrics("stage-1").successCount shouldBe 1
        metricsCollector.getPipelineMetrics("demo-pipeline").successCount shouldBe 1

        verify {
            logger.info(
                match {
                    it.contains("\"event\":\"pipeline_started\"") &&
                        it.contains("\"trace_id\":\"${pipeline?.traceContext?.traceId}\"")
                }
            )
        }
    }

    test("metrics collector keeps only bounded duration samples per key") {
        val collector = MetricsCollector()

        repeat(250) { index ->
            collector.recordExecution("agent-a", index.toLong(), success = true)
        }

        val metrics = collector.getMetrics("agent-a")
        metrics.totalExecutions shouldBe 250
        metrics.minDuration shouldBe 50
        metrics.maxDuration shouldBe 249
    }
})
