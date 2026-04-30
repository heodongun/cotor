package com.cotor.domain.orchestrator

/**
 * File overview for PipelineOrchestrator.
 *
 * This file belongs to the pipeline orchestration layer that coordinates stage execution and recovery.
 * It groups declarations around pipeline orchestrator so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.checkpoint.CheckpointManager
import com.cotor.checkpoint.toCheckpoint
import com.cotor.context.TemplateEngine
import com.cotor.data.config.CotorProperties
import com.cotor.domain.aggregator.ResultAggregator
import com.cotor.domain.condition.ConditionEvaluator
import com.cotor.domain.executor.AgentExecutor
import com.cotor.event.*
import com.cotor.event.EventBus
import com.cotor.model.*
import com.cotor.monitoring.NoopObservabilityService
import com.cotor.monitoring.ObservabilityService
import com.cotor.recovery.RecoveryExecutor
import com.cotor.runtime.durable.DurableRuntimeContext
import com.cotor.runtime.durable.DurableRuntimeFlags
import com.cotor.runtime.durable.DurableRuntimeService
import com.cotor.runtime.durable.ReplayMode
import com.cotor.stats.StatsManager
import com.cotor.validation.PipelineTemplateValidator
import com.cotor.validation.output.OutputValidator
import kotlinx.coroutines.*
import org.slf4j.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Interface for pipeline orchestration
 */
interface PipelineOrchestrator {
    /**
     * Execute a pipeline
     * @param pipeline Pipeline to execute
     * @param fromStageId Optional stage ID to resume execution from
     * @return AggregatedResult from pipeline execution
     */
    suspend fun executePipeline(
        pipeline: Pipeline,
        fromStageId: String? = null,
        context: PipelineContext? = null
    ): AggregatedResult

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
    private val checkpointManager: CheckpointManager = CheckpointManager(),
    private val templateValidator: PipelineTemplateValidator = PipelineTemplateValidator(TemplateEngine()),
    private val observability: ObservabilityService = NoopObservabilityService,
    private val durableRuntimeService: DurableRuntimeService = DurableRuntimeService()
) : PipelineOrchestrator {
    constructor(
        agentExecutor: AgentExecutor,
        resultAggregator: ResultAggregator,
        eventBus: EventBus,
        logger: Logger,
        agentRegistry: com.cotor.data.registry.AgentRegistry,
        outputValidator: OutputValidator,
        statsManager: StatsManager
    ) : this(
        agentExecutor = agentExecutor,
        resultAggregator = resultAggregator,
        eventBus = eventBus,
        logger = logger,
        agentRegistry = agentRegistry,
        outputValidator = outputValidator,
        statsManager = statsManager,
        checkpointManager = CheckpointManager(),
        templateValidator = PipelineTemplateValidator(TemplateEngine()),
        observability = NoopObservabilityService
    )

    constructor(
        agentExecutor: AgentExecutor,
        resultAggregator: ResultAggregator,
        eventBus: EventBus,
        logger: Logger,
        agentRegistry: com.cotor.data.registry.AgentRegistry,
        outputValidator: OutputValidator,
        statsManager: StatsManager,
        checkpointManager: CheckpointManager,
        templateValidator: PipelineTemplateValidator
    ) : this(
        agentExecutor = agentExecutor,
        resultAggregator = resultAggregator,
        eventBus = eventBus,
        logger = logger,
        agentRegistry = agentRegistry,
        outputValidator = outputValidator,
        statsManager = statsManager,
        checkpointManager = checkpointManager,
        templateValidator = templateValidator,
        observability = NoopObservabilityService
    )

    private val activePipelines = ConcurrentHashMap<String, Deferred<AggregatedResult>>()
    private val templateEngine = TemplateEngine()
    private val conditionEvaluator = ConditionEvaluator()
    private val recoveryExecutor = RecoveryExecutor(
        agentExecutor = agentExecutor,
        agentRegistry = agentRegistry,
        outputValidator = outputValidator,
        logger = logger
    )

    override suspend fun executePipeline(
        pipeline: Pipeline,
        fromStageId: String?,
        context: PipelineContext?
    ): AggregatedResult = coroutineScope {
        // This method is the single entry point for every execution mode. It validates mode/stage
        // compatibility up front, creates the shared pipeline context, wires observability, and
        // then hands off to the mode-specific executor that owns the actual traversal logic.
        if (pipeline.executionMode != ExecutionMode.SEQUENTIAL) {
            val conditionalStages = pipeline.stages.filter { it.type != StageType.EXECUTION }
            if (conditionalStages.isNotEmpty()) {
                val ids = conditionalStages.joinToString(", ") { it.id }
                throw PipelineException("Conditional stages ($ids) require SEQUENTIAL execution mode")
            }
        }
        val pipelineId = context?.pipelineId ?: UUID.randomUUID().toString()
        val pipelineContext = context ?: PipelineContext(
            pipelineId = pipelineId,
            pipelineName = pipeline.name,
            totalStages = pipeline.stages.size
        )

        // Validate pipeline templates before execution
        val validationResult = templateValidator.validate(pipeline)
        if (validationResult is ValidationResult.Failure) {
            val errorMessage = "Pipeline template validation failed:\n" + validationResult.errors.joinToString("\n")
            throw PipelineException(errorMessage)
        }

        fromStageId?.let {
            logger.info("Resuming pipeline from stage: $it")
        }

        logger.info("Starting pipeline: ${pipeline.name} (ID: $pipelineId)")
        durableRuntimeService.beginPipelineRun(pipeline, pipelineContext, fromStageId)
        val pipelineObservation = observability.startPipeline(pipelineId, pipeline.name, pipeline.stages.size)
        pipelineObservation?.let {
            pipelineContext.metadata["traceId"] = it.traceContext.traceId
            pipelineContext.metadata["pipelineSpanId"] = it.traceContext.spanId
        }

        val deferred = async {
            eventBus.emit(PipelineStartedEvent(pipelineId, pipeline.name))

            try {
                val executePipelineBlock: suspend () -> AggregatedResult = {
                    try {
                        when (pipeline.executionMode) {
                            ExecutionMode.SEQUENTIAL -> executeSequential(pipeline, pipelineId, pipelineContext, fromStageId)
                            ExecutionMode.PARALLEL -> executeParallel(pipeline, pipelineId, pipelineContext)
                            ExecutionMode.DAG -> executeDag(pipeline, pipelineId, pipelineContext)
                            ExecutionMode.MAP -> executeMap(pipeline, pipelineId, pipelineContext)
                        }
                    } catch (e: IllegalArgumentException) {
                        val configErrorResult = AgentResult(
                            agentName = "pipeline-config",
                            isSuccess = false,
                            output = null,
                            error = e.message,
                            duration = 0,
                            metadata = mapOf(
                                "stageId" to "pipeline-config",
                                "failureCategory" to FailureCategory.CONFIG_ERROR.name
                            )
                        )
                        pipelineContext.addStageResult("pipeline-config", configErrorResult)
                        throw e
                    }
                }

                val durableContext = if (DurableRuntimeFlags.isEnabled(pipelineContext)) {
                    DurableRuntimeContext(
                        runId = pipelineContext.metadata["durableRunId"]?.toString() ?: pipelineId,
                        replayMode = pipelineContext.metadata["replayMode"]?.toString()?.let {
                            runCatching { ReplayMode.valueOf(it) }.getOrDefault(ReplayMode.LIVE)
                        } ?: ReplayMode.LIVE,
                        sourceRunId = pipelineContext.metadata["sourceRunId"]?.toString(),
                        sourceCheckpointId = pipelineContext.metadata["sourceCheckpointId"]?.toString(),
                        configPath = pipelineContext.metadata["configPath"]?.toString()
                    )
                } else {
                    null
                }
                val result: AggregatedResult? = withContext(durableContext ?: EmptyCoroutineContext) {
                    if (pipeline.executionTimeoutMs != null) {
                        withTimeoutOrNull(pipeline.executionTimeoutMs) { executePipelineBlock() }
                    } else {
                        executePipelineBlock()
                    }
                }

                val finalResult = result
                    ?: throw PipelineException("Pipeline '${pipeline.name}' timed out after ${pipeline.executionTimeoutMs} ms")

                eventBus.emit(PipelineCompletedEvent(pipelineId, finalResult))
                observability.completePipeline(pipelineObservation, finalResult)

                // Record execution statistics
                val stageExecutions = finalResult.results.map {
                    com.cotor.stats.StageExecution(
                        name = it.metadata["stageId"] ?: it.agentName,
                        duration = it.duration,
                        status = if (it.isSuccess) com.cotor.stats.ExecutionStatus.SUCCESS else com.cotor.stats.ExecutionStatus.FAILURE,
                        retries = it.metadata["retries"]?.toIntOrNull() ?: 0
                    )
                }
                statsManager.recordExecution(pipeline.name, finalResult, stageExecutions)

                // Save checkpoint for resume functionality
                saveCheckpoint(pipelineId, pipeline.name, pipelineContext)
                durableRuntimeService.completeRun(pipelineContext)

                finalResult
            } catch (e: Exception) {
                if (e is PipelineAbortedException) {
                    logger.warn("Pipeline aborted at stage ${e.stageId}: ${e.message}")
                } else {
                    logger.error("Pipeline failed: ${pipeline.name}", e)
                }
                observability.failPipeline(pipelineObservation, e)
                eventBus.emit(PipelineFailedEvent(pipelineId, e))
                durableRuntimeService.failRun(pipelineContext)
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
        pipelineContext: PipelineContext,
        fromStageId: String? = null
    ): AggregatedResult {
        // Sequential mode is also the only mode that supports decision and loop stages. The index
        // is therefore mutable and may jump forward, backward, or terminate early depending on the
        // outcome of guard expressions and loop bookkeeping.
        val results = pipelineContext.getStageExecutionHistory()
            .map { it.result }
            .ifEmpty { pipelineContext.stageResults.values.toList() }
            .toMutableList()
        val stageIndexMap = pipeline.stages.mapIndexed { index, stage -> stage.id to index }.toMap()
        var previousOutput: String? = results.lastOrNull()?.output

        val startIndex = fromStageId?.let {
            stageIndexMap[it] ?: throw PipelineException("Resume stage '$it' not found in pipeline")
        } ?: 0

        var index = startIndex
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
                        // If the stage timed out and the policy is to continue, don't abort
                        val isTimeout = result.metadata["timeout"] == "true"
                        if (isTimeout && stage.timeoutPolicy == TimeoutPolicy.SKIP_STAGE_AND_CONTINUE) {
                            // Continue to the next stage
                        } else {
                            return aggregateResults(results, pipelineContext)
                        }
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
        // Parallel mode keeps the contract deliberately narrow: execution stages only, all launched
        // independently, with aggregation deferred until every branch finishes.
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
        // DAG mode still executes one stage at a time, but it changes stage ordering from author
        // order to dependency order and derives the input from upstream stage outputs when needed.
        pipeline.stages.firstOrNull { it.type != StageType.EXECUTION }?.let {
            throw PipelineException("Stage ${it.id} uses ${it.type} which is not supported in DAG mode")
        }
        val results = mutableMapOf<String, AgentResult>()
        validateDagDependencies(pipeline.stages)
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

    private fun validateDagDependencies(stages: List<PipelineStage>) {
        val stageIds = stages.map { it.id }.toSet()
        stages.forEach { stage ->
            stage.dependencies.forEach { depId ->
                if (depId !in stageIds) {
                    throw PipelineException("Stage '${stage.id}': Dependency '$depId' not found")
                }
            }
        }

        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        val stageMap = stages.associateBy { it.id }

        fun visit(stageId: String) {
            if (stageId in visiting) {
                throw PipelineException("Stage '$stageId': Circular dependency detected")
            }
            if (stageId in visited) return
            visiting.add(stageId)
            stageMap.getValue(stageId).dependencies.forEach(::visit)
            visiting.remove(stageId)
            visited.add(stageId)
        }

        stageIds.forEach(::visit)
    }

    private suspend fun executeMap(
        pipeline: Pipeline,
        pipelineId: String,
        pipelineContext: PipelineContext
    ): AggregatedResult = coroutineScope {
        // MAP mode is fanout over shared-state data. Exactly one stage declares the fanout source,
        // and that stage is executed once per source item with the item rendered as stage input.
        val fanoutStages = pipeline.stages.filter { it.fanout != null }
        if (fanoutStages.size != 1) {
            throw PipelineException("MAP execution mode requires exactly one stage with a fanout configuration")
        }
        val fanoutStage = fanoutStages.first()

        val sourceName = fanoutStage.fanout?.source
            ?: throw PipelineException("Fanout stage ${fanoutStage.id} is missing a source")

        val sourceData = pipelineContext.sharedState[sourceName] as? List<*>
            ?: throw PipelineException("Fanout source '$sourceName' not found in shared state or is not a list")

        val results = sourceData.map { item ->
            async(Dispatchers.Default) {
                val stageInput = item.toString()
                executeStageWithGuards(fanoutStage, pipelineId, pipelineContext, stageInput)
            }
        }.awaitAll()

        aggregateResults(results, pipelineContext)
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
        val stageObservation = observability.startStage(pipelineId, stage.id, stage.agent?.name)
        eventBus.emit(StageStartedEvent(stage.id, pipelineId))
        durableRuntimeService.recordStageStarted(pipelineContext, stage)
        val agentName = stage.agent?.name ?: stage.id

        val result: AgentResult? = try {
            if (stage.timeoutMs != null) {
                withTimeoutOrNull(stage.timeoutMs) {
                    recoveryExecutor.executeWithRecovery(stage, input, pipelineContext)
                }
            } else {
                recoveryExecutor.executeWithRecovery(stage, input, pipelineContext)
            }
        } catch (e: Exception) {
            val failureResult = AgentResult(
                agentName = agentName,
                isSuccess = false,
                output = null,
                error = e.message ?: "Stage ${stage.id} failed",
                duration = 0,
                metadata = stageMetadata(stage)
            )
            pipelineContext.addStageResult(stage.id, failureResult)
            saveCheckpoint(pipelineId, pipelineContext.pipelineName, pipelineContext)
            durableRuntimeService.recordStageFailed(pipelineContext, stage, failureResult)
            observability.failStage(stageObservation, e)
            eventBus.emit(StageFailedEvent(stage.id, pipelineId, e))
            throw e
        }

        if (result == null) {
            val errorMessage = "Stage '${stage.id}' timed out after ${stage.timeoutMs} ms"
            logger.warn(errorMessage)
            val timeoutResult = AgentResult(
                agentName = agentName,
                isSuccess = false,
                output = null,
                error = errorMessage,
                duration = stage.timeoutMs ?: 0,
                metadata = stageMetadata(
                    stage,
                    mapOf(
                        "timeout" to "true",
                        "failureCategory" to FailureCategory.TIMEOUT.name
                    )
                )
            )
            pipelineContext.addStageResult(stage.id, timeoutResult)
            saveCheckpoint(pipelineId, pipelineContext.pipelineName, pipelineContext)
            durableRuntimeService.recordStageFailed(pipelineContext, stage, timeoutResult)
            observability.failStage(stageObservation, RuntimeException(errorMessage))
            eventBus.emit(StageFailedEvent(stage.id, pipelineId, RuntimeException(errorMessage)))

            if (stage.timeoutPolicy == TimeoutPolicy.FAIL_PIPELINE) {
                throw PipelineException(errorMessage)
            }
            return timeoutResult
        }

        val stageResult = result.withStageMetadata(stage)
        pipelineContext.addStageResult(stage.id, stageResult)

        if (stageResult.isSuccess) {
            saveCheckpoint(pipelineId, pipelineContext.pipelineName, pipelineContext)
            durableRuntimeService.recordStageCompleted(pipelineContext, stage, stageResult)
            observability.completeStage(stageObservation, stageResult)
            eventBus.emit(StageCompletedEvent(stage.id, pipelineId, stageResult))
        } else {
            saveCheckpoint(pipelineId, pipelineContext.pipelineName, pipelineContext)
            durableRuntimeService.recordStageFailed(pipelineContext, stage, stageResult)
            val error = RuntimeException(stageResult.error ?: "Stage ${stage.id} failed")
            observability.failStage(stageObservation, error)
            eventBus.emit(StageFailedEvent(stage.id, pipelineId, error))
        }

        return stageResult
    }

    private fun AgentResult.withStageMetadata(stage: PipelineStage): AgentResult {
        return copy(metadata = stageMetadata(stage, metadata))
    }

    private fun stageMetadata(stage: PipelineStage, metadata: Map<String, String> = emptyMap()): Map<String, String> {
        return metadata + mapOf(
            "stageId" to stage.id,
            "stageType" to stage.type.name
        )
    }

    private suspend fun executeDecisionStage(
        stage: PipelineStage,
        pipelineId: String,
        pipelineContext: PipelineContext
    ): DecisionStageResult {
        val condition = stage.condition
            ?: throw PipelineException("Decision stage ${stage.id} is missing a condition")

        val stageObservation = observability.startStage(pipelineId, stage.id, stage.agent?.name)
        try {
            eventBus.emit(StageStartedEvent(stage.id, pipelineId))
            val passed = conditionEvaluator.evaluate(condition.expression, pipelineContext)
            val outcome = if (passed) condition.onTrue else condition.onFalse
            applySharedStateUpdates(outcome, pipelineContext)

            val metadata = mutableMapOf(
                "stageId" to stage.id,
                "stageType" to stage.type.name,
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
            saveCheckpoint(pipelineId, pipelineContext.pipelineName, pipelineContext)
            durableRuntimeService.recordStageCompleted(pipelineContext, stage, result)
            observability.completeStage(stageObservation, result)
            eventBus.emit(StageCompletedEvent(stage.id, pipelineId, result))
            return DecisionStageResult(result, outcome)
        } catch (e: Exception) {
            observability.failStage(stageObservation, e)
            throw e
        }
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
        val stageObservation = observability.startStage(pipelineId, stage.id, stage.agent?.name)
        try {
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
                metadata = stageMetadata(
                    stage,
                    mapOf(
                        "loopIteration" to completedIterations.toString(),
                        "loopAction" to if (shouldRepeat) "CONTINUE" else "BREAK"
                    )
                )
            )

            pipelineContext.addStageResult(stage.id, result)
            saveCheckpoint(pipelineId, pipelineContext.pipelineName, pipelineContext)
            durableRuntimeService.recordStageCompleted(pipelineContext, stage, result)

            val nextIndex = if (shouldRepeat) {
                pipelineContext.metadata[iterationKey] = completedIterations + 1
                stageIndexMap[loopConfig.targetStageId]
                    ?: throw PipelineException("Loop stage ${stage.id} target '${loopConfig.targetStageId}' not found")
            } else {
                pipelineContext.metadata.remove(iterationKey)
                currentIndex + 1
            }

            observability.completeStage(stageObservation, result)
            eventBus.emit(StageCompletedEvent(stage.id, pipelineId, result))
            return LoopStageResult(result, nextIndex)
        } catch (e: Exception) {
            observability.failStage(stageObservation, e)
            throw e
        }
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
            val completedStages = context.getStageExecutionHistory().map { entry ->
                entry.result.toCheckpoint(entry.stageId)
            }.ifEmpty {
                context.stageResults.entries.map { entry -> entry.value.toCheckpoint(entry.key) }
            }

            if (completedStages.isNotEmpty()) {
                val checkpointPath = checkpointManager.saveCheckpoint(
                    pipelineId = pipelineId,
                    pipelineName = pipelineName,
                    completedStages = completedStages,
                    cotorVersion = CotorProperties.version,
                    gitCommit = getGitCommit(),
                    os = System.getProperty("os.name"),
                    jvm = System.getProperty("java.version")
                )
                logger.info("Checkpoint saved: $checkpointPath")
            }
        } catch (e: Exception) {
            logger.warn("Failed to save checkpoint for pipeline $pipelineId: ${e.message}")
        }
    }

    private fun getGitCommit(): String {
        return try {
            val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD").start()
            process.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) {
            "unknown"
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
