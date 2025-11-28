package com.cotor.domain.orchestrator

import com.cotor.context.TemplateEngine
import com.cotor.domain.condition.ConditionEvaluator
import com.cotor.domain.aggregator.ResultAggregator
import com.cotor.domain.executor.AgentExecutor
import com.cotor.event.EventBus
import com.cotor.event.*
import com.cotor.model.*
import com.cotor.recovery.RecoveryExecutor
import com.cotor.stats.StatsManager
import com.cotor.validation.output.OutputValidator
import com.cotor.checkpoint.CheckpointManager
import com.cotor.checkpoint.toCheckpoint
import kotlinx.coroutines.*
import org.slf4j.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface for pipeline orchestration
 */
interface PipelineOrchestrator {
    /**
     * Execute a pipeline
     * @param pipeline Pipeline to execute
     * @return AggregatedResult from pipeline execution
     */
    suspend fun executePipeline(pipeline: Pipeline): AggregatedResult

    /**
     * Cancel a running pipeline
     * @param pipelineId ID of pipeline to cancel
     */
    suspend fun cancelPipeline(pipelineId: String)

    /**
     * Get pipeline status
     * @param pipelineId ID of pipeline
     * @return PipelineStatus
     */
    fun getPipelineStatus(pipelineId: String): PipelineStatus
}

private data class DecisionStageResult(
    val agentResult: AgentResult,
    val outcome: ConditionOutcome
)

private data class LoopStageResult(
    val agentResult: AgentResult,
    val nextIndex: Int
)

/**
 * Default implementation of pipeline orchestrator
 */
class DefaultPipelineOrchestrator(
    private val agentExecutor: AgentExecutor,
    private val resultAggregator: ResultAggregator,
    private val eventBus: EventBus,
    private val logger: Logger,
    private val agentRegistry: com.cotor.data.registry.AgentRegistry,
    private val outputValidator: OutputValidator,
    private val statsManager: StatsManager,
    private val checkpointManager: CheckpointManager = CheckpointManager()
) : PipelineOrchestrator {

    private val activePipelines = ConcurrentHashMap<String, Deferred<AggregatedResult>>()
    private val templateEngine = TemplateEngine()
    private val conditionEvaluator = ConditionEvaluator()
    private val recoveryExecutor = RecoveryExecutor(
        agentExecutor = agentExecutor,
        agentRegistry = agentRegistry,
        outputValidator = outputValidator,
        logger = logger
    )

    override suspend fun executePipeline(pipeline: Pipeline): AggregatedResult = coroutineScope {
        if (pipeline.executionMode != ExecutionMode.SEQUENTIAL) {
            val conditionalStages = pipeline.stages.filter { it.type != StageType.EXECUTION }
            if (conditionalStages.isNotEmpty()) {
                val ids = conditionalStages.joinToString(", ") { it.id }
                throw PipelineException("Conditional stages ($ids) require SEQUENTIAL execution mode")
            }
        }
        val pipelineId = UUID.randomUUID().toString()
        val context = PipelineContext(
            pipelineId = pipelineId,
            pipelineName = pipeline.name,
            totalStages = pipeline.stages.size
        )
        logger.info("Starting pipeline: ${pipeline.name} (ID: $pipelineId)")

        val deferred = async {
            eventBus.emit(PipelineStartedEvent(pipelineId, pipeline.name))

            try {
                val result = when (pipeline.executionMode) {
                    ExecutionMode.SEQUENTIAL -> executeSequential(pipeline, pipelineId, context)
                    ExecutionMode.PARALLEL -> executeParallel(pipeline, pipelineId, context)
                    ExecutionMode.DAG -> executeDag(pipeline, pipelineId, context)
                }

                eventBus.emit(PipelineCompletedEvent(pipelineId, result))

                // Record execution statistics
                statsManager.recordExecution(pipeline.name, result)

                // Save checkpoint for resume functionality
                saveCheckpoint(pipelineId, pipeline.name, context)

                result
            } catch (e: Exception) {
                if (e is PipelineAbortedException) {
                    logger.warn("Pipeline aborted at stage ${e.stageId}: ${e.message}")
                } else {
                    logger.error("Pipeline failed: ${pipeline.name}", e)
                }
                eventBus.emit(PipelineFailedEvent(pipelineId, e))
                throw e
            } finally {
                activePipelines.remove(pipelineId)
            }
        }

        activePipelines[pipelineId] = deferred
        deferred.await()
    }

    private suspend fun executeSequential(
        pipeline: Pipeline,
        pipelineId: String,
        pipelineContext: PipelineContext
    ): AggregatedResult {
        val results = mutableListOf<AgentResult>()
        val stageIndexMap = pipeline.stages.mapIndexed { index, stage -> stage.id to index }.toMap()
        var previousOutput: String? = null
        var index = 0
        var safetyCounter = 0

        while (index in pipeline.stages.indices) {
            if (++safetyCounter > pipeline.stages.size * 20) {
                throw PipelineException("Exceeded loop safety limit while executing pipeline ${pipeline.name}")
            }

            val stage = pipeline.stages[index]
            pipelineContext.currentStageIndex = index

            when (stage.type) {
                StageType.EXECUTION -> {
                    val interpolatedInput = stage.input?.let { templateEngine.interpolate(it, pipelineContext) }
                    val stageInput = interpolatedInput ?: previousOutput
                    val result = executeStageWithGuards(stage, pipelineId, pipelineContext, stageInput)
                    results.add(result)

                    if (result.isSuccess && !result.output.isNullOrBlank()) {
                        previousOutput = result.output
                    }

                    if (!result.isSuccess && stage.failureStrategy == FailureStrategy.ABORT && !stage.optional) {
                        return aggregateResults(results, pipelineContext)
                    }

                    index++
                }
                StageType.DECISION -> {
                    val decisionResult = executeDecisionStage(stage, pipelineId, pipelineContext)
                    results.add(decisionResult.agentResult)
                    index = resolveDecisionNextIndex(
                        decisionResult.outcome,
                        stageIndexMap,
                        index,
                        stage.id,
                        pipeline.name
                    )
                }
                StageType.LOOP -> {
                    val loopResult = handleLoopStage(
                        stage,
                        pipelineId,
                        pipelineContext,
                        stageIndexMap,
                        index
                    )
                    results.add(loopResult.agentResult)
                    index = loopResult.nextIndex
                }
            }
        }

        return aggregateResults(results, pipelineContext)
    }

    private suspend fun executeParallel(
        pipeline: Pipeline,
        pipelineId: String,
        pipelineContext: PipelineContext
    ): AggregatedResult = coroutineScope {
        pipeline.stages.firstOrNull { it.type != StageType.EXECUTION }?.let {
            throw PipelineException("Stage ${it.id} uses ${it.type} which is not supported in PARALLEL mode")
        }
        val results = pipeline.stages.map { stage ->
            async(Dispatchers.Default) {
                val interpolatedInput = stage.input?.let { templateEngine.interpolate(it, pipelineContext) }
                executeStageWithGuards(stage, pipelineId, pipelineContext, interpolatedInput)
            }
        }.awaitAll()

        aggregateResults(results, pipelineContext)
    }

    private suspend fun executeDag(
        pipeline: Pipeline,
        pipelineId: String,
        pipelineContext: PipelineContext
    ): AggregatedResult {
        pipeline.stages.firstOrNull { it.type != StageType.EXECUTION }?.let {
            throw PipelineException("Stage ${it.id} uses ${it.type} which is not supported in DAG mode")
        }
        val results = mutableMapOf<String, AgentResult>()
        val sortedStages = topologicalSort(pipeline.stages)

        for (stage in sortedStages) {
            val baseInput = stage.input?.let { templateEngine.interpolate(it, pipelineContext) }
            val dependencyInput = if (stage.dependencies.isEmpty()) {
                null
            } else {
                resolveDependencies(stage.dependencies, results)
            }

            val stageInput = baseInput ?: dependencyInput
            val result = executeStageWithGuards(stage, pipelineId, pipelineContext, stageInput)
            results[stage.id] = result

            if (!result.isSuccess && stage.failureStrategy == FailureStrategy.ABORT && !stage.optional) {
                break
            }
        }

        return aggregateResults(results.values.toList(), pipelineContext)
    }

    private fun topologicalSort(stages: List<PipelineStage>): List<PipelineStage> {
        val sorted = mutableListOf<PipelineStage>()
        val visited = mutableSetOf<String>()
        val stageMap = stages.associateBy { it.id }

        fun visit(stage: PipelineStage) {
            if (stage.id in visited) return
            visited.add(stage.id)

            stage.dependencies.forEach { depId ->
                stageMap[depId]?.let { visit(it) }
            }

            sorted.add(stage)
        }

        stages.forEach { visit(it) }
        return sorted
    }

    private fun resolveDependencies(
        dependencies: List<String>,
        results: Map<String, AgentResult>
    ): String? {
        return dependencies
            .mapNotNull { results[it]?.output }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
    }

    private suspend fun executeStageWithGuards(
        stage: PipelineStage,
        pipelineId: String,
        pipelineContext: PipelineContext,
        input: String?
    ): AgentResult {
        if (stage.agent == null) {
            throw PipelineException("Stage ${stage.id} requires an agent for execution")
        }
        return try {
            runStage(stage, pipelineId, pipelineContext, input)
        } catch (e: Exception) {
            val failureResult = pipelineContext.getStageResult(stage.id)
                ?: AgentResult(stage.agent?.name ?: stage.id, false, null, e.message, 0, emptyMap())
            if (stage.optional || stage.failureStrategy == FailureStrategy.CONTINUE) {
                failureResult
            } else {
                throw e
            }
        }
    }

    private fun aggregateResults(
        results: List<AgentResult>,
        pipelineContext: PipelineContext
    ): AggregatedResult {
        val aggregated = resultAggregator.aggregate(results)
        return aggregated.copy(totalDuration = pipelineContext.elapsedTime())
    }

    private suspend fun runStage(
        stage: PipelineStage,
        pipelineId: String,
        pipelineContext: PipelineContext,
        input: String?
    ): AgentResult {
        eventBus.emit(StageStartedEvent(stage.id, pipelineId))
        val agentName = stage.agent?.name ?: stage.id

        return try {
            val result = recoveryExecutor.executeWithRecovery(stage, input, pipelineContext)
            pipelineContext.addStageResult(stage.id, result)

            if (result.isSuccess) {
                eventBus.emit(StageCompletedEvent(stage.id, pipelineId, result))
            } else {
                val error = RuntimeException(result.error ?: "Stage ${stage.id} failed")
                eventBus.emit(StageFailedEvent(stage.id, pipelineId, error))
            }

            result
        } catch (e: Exception) {
            val failureResult = AgentResult(
                agentName = agentName,
                isSuccess = false,
                output = null,
                error = e.message ?: "Stage ${stage.id} failed",
                duration = 0,
                metadata = emptyMap()
            )
            pipelineContext.addStageResult(stage.id, failureResult)
            eventBus.emit(StageFailedEvent(stage.id, pipelineId, e))
            throw e
        }
    }

    private suspend fun executeDecisionStage(
        stage: PipelineStage,
        pipelineId: String,
        pipelineContext: PipelineContext
    ): DecisionStageResult {
        val condition = stage.condition
            ?: throw PipelineException("Decision stage ${stage.id} is missing a condition")

        eventBus.emit(StageStartedEvent(stage.id, pipelineId))
        val passed = conditionEvaluator.evaluate(condition.expression, pipelineContext)
        val outcome = if (passed) condition.onTrue else condition.onFalse
        applySharedStateUpdates(outcome, pipelineContext)

        val metadata = mutableMapOf(
            "conditionExpression" to condition.expression,
            "conditionResult" to passed.toString(),
            "nextAction" to outcome.action.name
        )
        outcome.targetStageId?.let { metadata["targetStage"] = it }
        outcome.message?.let { metadata["message"] = it }

        val result = AgentResult(
            agentName = stage.agent?.name ?: "decision:${stage.id}",
            isSuccess = true,
            output = buildString {
                appendLine("Condition: ${condition.expression}")
                append("Result: ${if (passed) "TRUE" else "FALSE"} → ${outcome.action}")
                outcome.targetStageId?.let { append(" ($it)") }
                outcome.message?.let { append(" • $it") }
            }.trim(),
            error = null,
            duration = 0,
            metadata = metadata
        )

        pipelineContext.addStageResult(stage.id, result)
        eventBus.emit(StageCompletedEvent(stage.id, pipelineId, result))
        return DecisionStageResult(result, outcome)
    }

    private fun resolveDecisionNextIndex(
        outcome: ConditionOutcome,
        stageIndexMap: Map<String, Int>,
        currentIndex: Int,
        stageId: String,
        pipelineName: String
    ): Int {
        return when (outcome.action) {
            ConditionAction.CONTINUE -> currentIndex + 1
            ConditionAction.GOTO -> {
                val targetId = outcome.targetStageId
                    ?: throw PipelineException("Decision stage '$stageId' requires targetStageId for GOTO action")
                stageIndexMap[targetId]
                    ?: throw PipelineException("Decision stage '$stageId' target '$targetId' not found in pipeline $pipelineName")
            }
            ConditionAction.ABORT -> throw PipelineAbortedException(
                stageId,
                outcome.message ?: "Pipeline aborted by decision stage '$stageId'"
            )
        }
    }

    private suspend fun handleLoopStage(
        stage: PipelineStage,
        pipelineId: String,
        pipelineContext: PipelineContext,
        stageIndexMap: Map<String, Int>,
        currentIndex: Int
    ): LoopStageResult {
        val loopConfig = stage.loop
            ?: throw PipelineException("Loop stage ${stage.id} is missing loop configuration")
        eventBus.emit(StageStartedEvent(stage.id, pipelineId))

        val iterationKey = "loop:${stage.id}:iterations"
        val completedIterations = (pipelineContext.metadata[iterationKey] as? Int) ?: 0
        val conditionMet = loopConfig.untilExpression
            ?.let { conditionEvaluator.evaluate(it, pipelineContext) }
            ?: false
        val canRepeat = completedIterations < loopConfig.maxIterations
        val shouldRepeat = !conditionMet && canRepeat

        val result = AgentResult(
            agentName = stage.agent?.name ?: "loop:${stage.id}",
            isSuccess = true,
            output = if (shouldRepeat) {
                "Loop ${stage.id} continuing to ${loopConfig.targetStageId} (${completedIterations + 1}/${loopConfig.maxIterations})"
            } else {
                "Loop ${stage.id} completed after $completedIterations iteration(s)"
            },
            error = null,
            duration = 0,
            metadata = mapOf(
                "loopIteration" to completedIterations.toString(),
                "loopAction" to if (shouldRepeat) "CONTINUE" else "BREAK"
            )
        )

        pipelineContext.addStageResult(stage.id, result)

        val nextIndex = if (shouldRepeat) {
            pipelineContext.metadata[iterationKey] = completedIterations + 1
            stageIndexMap[loopConfig.targetStageId]
                ?: throw PipelineException("Loop stage ${stage.id} target '${loopConfig.targetStageId}' not found")
        } else {
            pipelineContext.metadata.remove(iterationKey)
            currentIndex + 1
        }

        eventBus.emit(StageCompletedEvent(stage.id, pipelineId, result))
        return LoopStageResult(result, nextIndex)
    }

    private fun applySharedStateUpdates(outcome: ConditionOutcome, pipelineContext: PipelineContext) {
        if (outcome.sharedState.isEmpty()) return

        outcome.sharedState.forEach { (key, value) ->
            val interpolated = if (value.isBlank()) "" else templateEngine.interpolate(value, pipelineContext)
            pipelineContext.sharedState[key] = interpolated
        }
    }

    /**
     * Save checkpoint for completed pipeline stages
     */
    private fun saveCheckpoint(
        pipelineId: String,
        pipelineName: String,
        context: PipelineContext
    ) {
        try {
            val completedStages = context.stageResults.map { (stageId, result) ->
                result.toCheckpoint(stageId)
            }

            if (completedStages.isNotEmpty()) {
                val checkpointPath = checkpointManager.saveCheckpoint(
                    pipelineId = pipelineId,
                    pipelineName = pipelineName,
                    completedStages = completedStages
                )
                logger.info("Checkpoint saved: $checkpointPath")
            }
        } catch (e: Exception) {
            logger.warn("Failed to save checkpoint for pipeline $pipelineId: ${e.message}")
        }
    }

    override suspend fun cancelPipeline(pipelineId: String) {
        activePipelines[pipelineId]?.cancel()
        logger.info("Pipeline cancelled: $pipelineId")
    }

    override fun getPipelineStatus(pipelineId: String): PipelineStatus {
        val deferred = activePipelines[pipelineId]
        return when {
            deferred == null -> PipelineStatus.NOT_FOUND
            deferred.isActive -> PipelineStatus.RUNNING
            deferred.isCompleted -> PipelineStatus.COMPLETED
            deferred.isCancelled -> PipelineStatus.CANCELLED
            else -> PipelineStatus.UNKNOWN
        }
    }
}
