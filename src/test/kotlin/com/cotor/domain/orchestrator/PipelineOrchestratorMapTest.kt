package com.cotor.domain.orchestrator

import com.cotor.analysis.ResultAnalyzer
import com.cotor.domain.aggregator.DefaultResultAggregator
import com.cotor.domain.executor.AgentExecutor
import com.cotor.event.EventBus
import com.cotor.model.*
import com.cotor.stats.StatsManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.slf4j.Logger

class PipelineOrchestratorMapTest {

    private val agentExecutor: AgentExecutor = mockk()
    private val resultAnalyzer: ResultAnalyzer = mockk(relaxed = true)
    private val resultAggregator: DefaultResultAggregator = DefaultResultAggregator(resultAnalyzer)
    private val eventBus: EventBus = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val agentRegistry: com.cotor.data.registry.AgentRegistry = mockk(relaxed = true)
    private val outputValidator: com.cotor.validation.output.OutputValidator = mockk(relaxed = true)
    private val statsManager: StatsManager = mockk(relaxed = true)

    @Test
    fun `executePipeline with MAP execution mode should fan out and aggregate results`() = runBlocking {
        // Given
        val pipeline = Pipeline(
            name = "map-pipeline",
            executionMode = ExecutionMode.MAP,
            stages = listOf(
                PipelineStage(
                    id = "fanout-stage",
                    agent = AgentReference("test-agent"),
                    fanout = FanoutConfig(source = "items")
                )
            )
        )
        val orchestrator = DefaultPipelineOrchestrator(
            agentExecutor = agentExecutor,
            resultAggregator = resultAggregator,
            eventBus = eventBus,
            logger = logger,
            agentRegistry = agentRegistry,
            outputValidator = outputValidator,
            statsManager = statsManager
        )
        val items = listOf("item1", "item2", "item3")
        val pipelineContext = PipelineContext(
            pipelineId = "test-pipeline",
            pipelineName = "map-pipeline",
            totalStages = 1,
        )
        pipelineContext.sharedState["items"] = items

        coEvery { agentExecutor.executeAgent(any(), any(), any()) } returns AgentResult(
            agentName = "test-agent",
            isSuccess = true,
            output = "output",
            error = null,
            duration = 100,
            metadata = emptyMap()
        )

        coEvery { agentRegistry.getAgent(any()) } returns AgentConfig(
            name = "test-agent",
            pluginClass = "com.cotor.agent.TestAgent"
        )

        // When
        val result = orchestrator.executePipeline(pipeline, context = pipelineContext)

        // Then
        assertEquals(3, result.successCount)
        assertEquals(0, result.failureCount)
        assertEquals(3, result.results.size)
    }

    @Test
    fun `executePipeline with MAP execution mode should support multiple fanout stages`() = runBlocking {
        // Given
        val pipeline = Pipeline(
            name = "map-pipeline-multiple-fanout",
            executionMode = ExecutionMode.MAP,
            stages = listOf(
                PipelineStage(
                    id = "fanout-stage-1",
                    agent = AgentReference("test-agent-1"),
                    fanout = FanoutConfig(source = "items1")
                ),
                PipelineStage(
                    id = "fanout-stage-2",
                    agent = AgentReference("test-agent-2"),
                    fanout = FanoutConfig(source = "items2")
                )
            )
        )
        val orchestrator = DefaultPipelineOrchestrator(
            agentExecutor = agentExecutor,
            resultAggregator = resultAggregator,
            eventBus = eventBus,
            logger = logger,
            agentRegistry = agentRegistry,
            outputValidator = outputValidator,
            statsManager = statsManager
        )
        val items1 = listOf("a", "b", "c")
        val items2 = listOf("x", "y")
        val pipelineContext = PipelineContext(
            pipelineId = "test-pipeline-multiple",
            pipelineName = "map-pipeline-multiple-fanout",
            totalStages = 2
        )
        pipelineContext.sharedState["items1"] = items1
        pipelineContext.sharedState["items2"] = items2

        coEvery { agentExecutor.executeAgent(any(), any(), any()) } returns AgentResult(
            agentName = "test-agent",
            isSuccess = true,
            output = "output",
            error = null,
            duration = 100,
            metadata = emptyMap()
        )
        coEvery { agentRegistry.getAgent("test-agent-1") } returns AgentConfig(
            name = "test-agent-1",
            pluginClass = "com.cotor.agent.TestAgent1"
        )
        coEvery { agentRegistry.getAgent("test-agent-2") } returns AgentConfig(
            name = "test-agent-2",
            pluginClass = "com.cotor.agent.TestAgent2"
        )

        // When
        val result = orchestrator.executePipeline(pipeline, context = pipelineContext)

        // Then
        assertEquals(5, result.successCount)
        assertEquals(0, result.failureCount)
        assertEquals(5, result.results.size)
    }
}
