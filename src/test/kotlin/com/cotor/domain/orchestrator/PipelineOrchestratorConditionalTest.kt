package com.cotor.domain.orchestrator

import com.cotor.analysis.DefaultResultAnalyzer
import com.cotor.data.registry.InMemoryAgentRegistry
import com.cotor.domain.aggregator.DefaultResultAggregator
import com.cotor.domain.executor.AgentExecutor
import com.cotor.event.CoroutineEventBus
import com.cotor.model.AgentConfig
import com.cotor.model.AgentExecutionMetadata
import com.cotor.model.AgentReference
import com.cotor.model.AgentResult
import com.cotor.model.ConditionAction
import com.cotor.model.ConditionOutcome
import com.cotor.model.ExecutionMode
import com.cotor.model.Pipeline
import com.cotor.model.PipelineStage
import com.cotor.model.StageConditionConfig
import com.cotor.model.StageLoopConfig
import com.cotor.model.StageType
import com.cotor.validation.output.DefaultOutputValidator
import com.cotor.validation.output.SyntaxValidator
import com.cotor.stats.StatsManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

class PipelineOrchestratorConditionalTest : FunSpec({

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
            logger = LoggerFactory.getLogger("ConditionalTest"),
            agentRegistry = registry,
            outputValidator = DefaultOutputValidator(SyntaxValidator()),
            statsManager = StatsManager()
        )
    }

    test("decision stage reroutes pipeline when condition fails") {
        val executor = RecordingAgentExecutor()
        val orchestrator = createOrchestrator(executor)

        val pipeline = Pipeline(
            name = "conditional",
            executionMode = ExecutionMode.SEQUENTIAL,
            stages = listOf(
                PipelineStage(id = "initial", agent = AgentReference("alpha")),
                PipelineStage(
                    id = "quality-check",
                    type = StageType.DECISION,
                    condition = StageConditionConfig(
                        expression = "initial.qualityScore >= 80",
                        onTrue = ConditionOutcome(action = ConditionAction.CONTINUE),
                        onFalse = ConditionOutcome(
                            action = ConditionAction.GOTO,
                            targetStageId = "improve"
                        )
                    )
                ),
                PipelineStage(id = "improve", agent = AgentReference("alpha")),
                PipelineStage(id = "final", agent = AgentReference("alpha"))
            )
        )

        val result = runBlocking { orchestrator.executePipeline(pipeline) }

        executor.executedStages.shouldContainExactly(listOf("initial", "improve", "final"))
        result.results.any { it.agentName.startsWith("decision:quality-check") } shouldBe true
    }

    test("loop stage repeats target stage until max iterations") {
        val executor = RecordingAgentExecutor()
        val orchestrator = createOrchestrator(executor)

        val pipeline = Pipeline(
            name = "looping",
            executionMode = ExecutionMode.SEQUENTIAL,
            stages = listOf(
                PipelineStage(id = "draft", agent = AgentReference("alpha")),
                PipelineStage(
                    id = "loop-controller",
                    type = StageType.LOOP,
                    loop = StageLoopConfig(
                        targetStageId = "draft",
                        maxIterations = 2
                    )
                ),
                PipelineStage(id = "review", agent = AgentReference("alpha"))
            )
        )

        runBlocking { orchestrator.executePipeline(pipeline) }

        executor.executionCount("draft") shouldBe 3 // initial + 2 loop iterations
        executor.executedStages.last() shouldBe "review"
    }
})

private class RecordingAgentExecutor : AgentExecutor {
    private val counts = mutableMapOf<String, Int>()
    val executedStages = mutableListOf<String>()

    override suspend fun executeAgent(
        agent: AgentConfig,
        input: String?,
        metadata: AgentExecutionMetadata
    ): AgentResult {
        val stageId = metadata.stageId ?: agent.name
        executedStages.add(stageId)

        val iteration = counts.merge(stageId, 1, Int::plus)!!
        val qualityScore = when (stageId) {
            "initial" -> "65"
            "improve" -> "95"
            else -> iteration.toString()
        }

        return AgentResult(
            agentName = agent.name,
            isSuccess = true,
            output = "output:$stageId#$iteration",
            error = null,
            duration = 5,
            metadata = mapOf(
                "qualityScore" to qualityScore,
                "iteration" to iteration.toString()
            )
        )
    }

    override suspend fun executeWithRetry(
        agent: AgentConfig,
        input: String?,
        retryPolicy: com.cotor.model.RetryPolicy,
        metadata: AgentExecutionMetadata
    ): AgentResult = executeAgent(agent, input, metadata)

    fun executionCount(stageId: String): Int = counts[stageId] ?: 0
}
