package com.cotor.domain.orchestrator

import com.cotor.analysis.DefaultResultAnalyzer
import com.cotor.data.registry.InMemoryAgentRegistry
import com.cotor.domain.aggregator.DefaultResultAggregator
import com.cotor.domain.executor.AgentExecutor
import com.cotor.event.CoroutineEventBus
import com.cotor.model.AgentConfig
import com.cotor.model.AgentReference
import com.cotor.model.ExecutionMode
import com.cotor.model.Pipeline
import com.cotor.model.PipelineException
import com.cotor.model.PipelineStage
import com.cotor.stats.StatsManager
import com.cotor.validation.output.DefaultOutputValidator
import com.cotor.validation.output.SyntaxValidator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files

class PipelineOrchestratorDagValidationTest : FunSpec({
    fun createOrchestrator(executor: AgentExecutor): DefaultPipelineOrchestrator {
        val registry = InMemoryAgentRegistry().apply {
            registerAgent(AgentConfig(name = "alpha", pluginClass = "com.cotor.data.plugin.EchoPlugin"))
        }
        return DefaultPipelineOrchestrator(
            agentExecutor = executor,
            resultAggregator = DefaultResultAggregator(DefaultResultAnalyzer()),
            eventBus = CoroutineEventBus(),
            logger = LoggerFactory.getLogger("PipelineOrchestratorDagValidationTest"),
            agentRegistry = registry,
            outputValidator = DefaultOutputValidator(SyntaxValidator()),
            statsManager = StatsManager(Files.createTempDirectory("dag-validation-stats").toString())
        )
    }

    test("DAG mode rejects missing dependency before executing agents") {
        val executor = mockk<AgentExecutor>(relaxed = true)
        val orchestrator = createOrchestrator(executor)
        val pipeline = Pipeline(
            name = "missing-dependency",
            executionMode = ExecutionMode.DAG,
            stages = listOf(
                PipelineStage(id = "a", agent = AgentReference("alpha"), dependencies = listOf("missing"))
            )
        )

        val error = shouldThrow<PipelineException> {
            runBlocking { orchestrator.executePipeline(pipeline) }
        }

        error.message shouldContain "Dependency 'missing' not found"
        coVerify(exactly = 0) { executor.executeAgent(any(), any(), any()) }
    }

    test("DAG mode rejects circular dependency before executing agents") {
        val executor = mockk<AgentExecutor>(relaxed = true)
        val orchestrator = createOrchestrator(executor)
        val pipeline = Pipeline(
            name = "cycle",
            executionMode = ExecutionMode.DAG,
            stages = listOf(
                PipelineStage(id = "a", agent = AgentReference("alpha"), dependencies = listOf("b")),
                PipelineStage(id = "b", agent = AgentReference("alpha"), dependencies = listOf("a"))
            )
        )

        val error = shouldThrow<PipelineException> {
            runBlocking { orchestrator.executePipeline(pipeline) }
        }

        error.message shouldContain "Circular dependency detected"
        coVerify(exactly = 0) { executor.executeAgent(any(), any(), any()) }
    }
})
