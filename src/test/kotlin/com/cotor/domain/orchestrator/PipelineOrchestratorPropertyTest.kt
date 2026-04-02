package com.cotor.domain.orchestrator

/**
 * File overview for PipelineOrchestratorPropertyTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around pipeline orchestrator property test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.analysis.ResultAnalyzer
import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.aggregator.DefaultResultAggregator
import com.cotor.domain.executor.AgentExecutor
import com.cotor.event.EventBus
import com.cotor.model.*
import com.cotor.stats.StatsManager
import com.cotor.validation.output.OutputValidator
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.slf4j.Logger

class PipelineOrchestratorPropertyTest {

    private val agentExecutor: AgentExecutor = mockk()
    private val resultAnalyzer: ResultAnalyzer = mockk(relaxed = true)
    private val resultAggregator = DefaultResultAggregator(resultAnalyzer)
    private val eventBus: EventBus = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val agentRegistry: AgentRegistry = mockk(relaxed = true)
    private val outputValidator: OutputValidator = mockk(relaxed = true)
    private val statsManager: StatsManager = mockk(relaxed = true)

    private val orchestrator = DefaultPipelineOrchestrator(
        agentExecutor = agentExecutor,
        resultAggregator = resultAggregator,
        eventBus = eventBus,
        logger = logger,
        agentRegistry = agentRegistry,
        outputValidator = outputValidator,
        statsManager = statsManager
    )

    @Test
    fun `MAP mode should preserve fanout cardinality for arbitrary item lists`() = runBlocking {
        coEvery { agentRegistry.getAgent(any()) } returns AgentConfig(
            name = "test-agent",
            pluginClass = "com.cotor.agent.TestAgent"
        )
        coEvery { agentExecutor.executeAgent(any(), any(), any()) } answers {
            val input = secondArg<String?>()
            AgentResult(
                agentName = "test-agent",
                isSuccess = true,
                output = "processed:$input",
                error = null,
                duration = 1,
                metadata = emptyMap()
            )
        }

        checkAll(iterations = 30, Arb.list(Arb.string(minSize = 0, maxSize = 8), range = 0..25)) { items ->
            val pipeline = Pipeline(
                name = "map-property",
                executionMode = ExecutionMode.MAP,
                stages = listOf(
                    PipelineStage(
                        id = "fanout",
                        agent = AgentReference("test-agent"),
                        fanout = FanoutConfig(source = "items")
                    )
                )
            )
            val context = PipelineContext(
                pipelineId = "p-${items.size}",
                pipelineName = "map-property",
                totalStages = 1
            )
            context.sharedState["items"] = items

            val result = orchestrator.executePipeline(pipeline, context = context)

            assertEquals(items.size, result.results.size)
            assertEquals(items.size, result.successCount)
            assertEquals(0, result.failureCount)
        }
    }

    @Test
    fun `MAP mode should reject arbitrary fanout stage counts other than one`() = runBlocking {
        checkAll(iterations = 25, Arb.int(0..4).filter { it != 1 }) { fanoutCount ->
            val stages = (0 until fanoutCount).map { idx ->
                PipelineStage(
                    id = "fanout-$idx",
                    agent = AgentReference("test-agent"),
                    fanout = FanoutConfig(source = "items")
                )
            }

            val pipeline = Pipeline(
                name = "invalid-map-$fanoutCount",
                executionMode = ExecutionMode.MAP,
                stages = stages
            )
            val context = PipelineContext(
                pipelineId = "invalid-$fanoutCount",
                pipelineName = "invalid-map",
                totalStages = stages.size
            ).also { it.sharedState["items"] = listOf("x") }

            val errorMessage = try {
                orchestrator.executePipeline(pipeline, context = context)
                fail("Expected PipelineException")
            } catch (e: PipelineException) {
                e.message
            }
            assertEquals("MAP execution mode requires exactly one stage with a fanout configuration", errorMessage)
        }
    }
}
