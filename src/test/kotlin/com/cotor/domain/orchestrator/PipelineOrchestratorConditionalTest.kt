package com.cotor.domain.orchestrator

/**
 * File overview for PipelineOrchestratorConditionalTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around pipeline orchestrator conditional test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.analysis.DefaultResultAnalyzer
import com.cotor.checkpoint.CheckpointManager
import com.cotor.context.TemplateEngine
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
import com.cotor.stats.StatsManager
import com.cotor.validation.PipelineTemplateValidator
import com.cotor.validation.output.DefaultOutputValidator
import com.cotor.validation.output.SyntaxValidator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files

class PipelineOrchestratorConditionalTest : FunSpec({

    val registry = InMemoryAgentRegistry().apply {
        registerAgent(
            AgentConfig(
                name = "alpha",
                pluginClass = "com.cotor.data.plugin.EchoPlugin"
            )
        )
    }

    fun createOrchestrator(
        executor: AgentExecutor,
        statsManager: StatsManager = StatsManager(Files.createTempDirectory("conditional-stats").toString()),
        checkpointManager: CheckpointManager = CheckpointManager(Files.createTempDirectory("conditional-checkpoints").toString())
    ): DefaultPipelineOrchestrator {
        return DefaultPipelineOrchestrator(
            agentExecutor = executor,
            resultAggregator = DefaultResultAggregator(DefaultResultAnalyzer()),
            eventBus = CoroutineEventBus(),
            logger = LoggerFactory.getLogger("ConditionalTest"),
            agentRegistry = registry,
            outputValidator = DefaultOutputValidator(SyntaxValidator()),
            statsManager = statsManager,
            checkpointManager = checkpointManager,
            templateValidator = PipelineTemplateValidator(TemplateEngine())
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

    test("loop stage statistics preserve every traversal by stage id") {
        val executor = RecordingAgentExecutor()
        val statsManager = StatsManager(Files.createTempDirectory("loop-stats").toString())
        val orchestrator = createOrchestrator(executor, statsManager)

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

        val execution = statsManager.loadStats("looping")!!.executions.single()
        execution.stages.map { it.name }.shouldContainExactly(
            listOf(
                "draft",
                "loop-controller",
                "draft",
                "loop-controller",
                "draft",
                "loop-controller",
                "review"
            )
        )
        execution.stages.map { it.duration }.shouldContainExactly(listOf(5L, 0L, 5L, 0L, 5L, 0L, 5L))
    }

    test("loop stage checkpoints preserve every traversal by stage id") {
        val executor = RecordingAgentExecutor()
        val checkpointManager = CheckpointManager(Files.createTempDirectory("loop-checkpoints").toString())
        val orchestrator = createOrchestrator(executor, checkpointManager = checkpointManager)

        val pipeline = Pipeline(
            name = "looping-checkpoint",
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

        val checkpoint = checkpointManager.getCheckpoints().single()
        checkpoint.completedStages.map { it.stageId }.shouldContainExactly(
            listOf(
                "draft",
                "loop-controller",
                "draft",
                "loop-controller",
                "draft",
                "loop-controller",
                "review"
            )
        )
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
