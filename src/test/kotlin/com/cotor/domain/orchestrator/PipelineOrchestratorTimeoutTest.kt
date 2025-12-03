package com.cotor.domain.orchestrator

import com.cotor.analysis.DefaultResultAnalyzer
import com.cotor.data.registry.InMemoryAgentRegistry
import com.cotor.domain.aggregator.DefaultResultAggregator
import com.cotor.domain.executor.AgentExecutor
import com.cotor.event.CoroutineEventBus
import com.cotor.model.*
import com.cotor.validation.output.DefaultOutputValidator
import com.cotor.validation.output.SyntaxValidator
import com.cotor.stats.StatsManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

class PipelineOrchestratorTimeoutTest : FunSpec({

    val registry = InMemoryAgentRegistry().apply {
        registerAgent(
            AgentConfig(
                name = "alpha",
                pluginClass = "com.cotor.data.plugin.EchoPlugin"
            )
        )
    }

    fun createOrchestrator(executor: AgentExecutor): DefaultPipelineOrchestrator {
        return DefaultPipelineOrchestrator(
            agentExecutor = executor,
            resultAggregator = DefaultResultAggregator(DefaultResultAnalyzer()),
            eventBus = CoroutineEventBus(),
            logger = LoggerFactory.getLogger("TimeoutTest"),
            agentRegistry = registry,
            outputValidator = DefaultOutputValidator(SyntaxValidator()),
            statsManager = StatsManager()
        )
    }

    test("pipeline should time out and throw PipelineException") {
        val executor = MappedDelayedAgentExecutor(mapOf("stage1" to 1000L))
        val orchestrator = createOrchestrator(executor)

        val pipeline = Pipeline(
            name = "timeout-pipeline",
            executionTimeoutMs = 500,
            stages = listOf(
                PipelineStage(id = "stage1", agent = AgentReference("alpha"))
            )
        )

        val exception = shouldThrow<PipelineException> {
            orchestrator.executePipeline(pipeline)
        }
        exception.message shouldContain "timed out after 500 ms"
    }

    test("stage should time out and fail pipeline") {
        val executor = MappedDelayedAgentExecutor(mapOf("stage1" to 1000L))
        val orchestrator = createOrchestrator(executor)

        val pipeline = Pipeline(
            name = "stage-timeout-fail",
            stages = listOf(
                PipelineStage(
                    id = "stage1",
                    agent = AgentReference("alpha"),
                    timeoutMs = 500,
                    timeoutPolicy = TimeoutPolicy.FAIL_PIPELINE
                )
            )
        )

        val exception = shouldThrow<PipelineException> {
            orchestrator.executePipeline(pipeline)
        }
        exception.message shouldContain "timed out after 500 ms"
    }

    test("stage should time out and continue pipeline") {
        val executor = MappedDelayedAgentExecutor(mapOf("stage1" to 1000L, "stage2" to 10L))
        val orchestrator = createOrchestrator(executor)

        val pipeline = Pipeline(
            name = "stage-timeout-continue",
            stages = listOf(
                PipelineStage(
                    id = "stage1",
                    agent = AgentReference("alpha"),
                    timeoutMs = 500,
                    timeoutPolicy = TimeoutPolicy.SKIP_STAGE_AND_CONTINUE
                ),
                PipelineStage(id = "stage2", agent = AgentReference("alpha"))
            )
        )

        val result = orchestrator.executePipeline(pipeline)
        result.results.size shouldBe 2
        result.results[0].isSuccess shouldBe false
        result.results[0].error shouldContain "timed out after 500 ms"
        result.results[1].isSuccess shouldBe true
    }
})

private class MappedDelayedAgentExecutor(private val delays: Map<String, Long>) : AgentExecutor {
    override suspend fun executeAgent(
        agent: AgentConfig,
        input: String?,
        metadata: AgentExecutionMetadata
    ): AgentResult {
        val stageId = metadata.stageId
        val delayMs = if (stageId != null) delays[stageId] ?: 0L else 0L
        if (delayMs > 0) {
            delay(delayMs)
        }
        return AgentResult(
            agentName = agent.name,
            isSuccess = true,
            output = "output for $stageId",
            error = null,
            duration = delayMs,
            metadata = emptyMap()
        )
    }

    override suspend fun executeWithRetry(
        agent: AgentConfig,
        input: String?,
        retryPolicy: RetryPolicy,
        metadata: AgentExecutionMetadata
    ): AgentResult = executeAgent(agent, input, metadata)
}
