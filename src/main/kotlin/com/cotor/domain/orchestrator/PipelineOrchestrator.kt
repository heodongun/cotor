package com.cotor.domain.orchestrator

import com.cotor.context.TemplateEngine
import com.cotor.domain.aggregator.ResultAggregator
import com.cotor.domain.executor.AgentExecutor
import com.cotor.event.EventBus
import com.cotor.event.*
import com.cotor.model.*
import com.cotor.recovery.RecoveryExecutor
import com.cotor.validation.output.OutputValidator
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

/**
 * Default implementation of pipeline orchestrator
 */
class DefaultPipelineOrchestrator(
    private val agentExecutor: AgentExecutor,
    private val resultAggregator: ResultAggregator,
    private val eventBus: EventBus,
    private val logger: Logger,
    private val agentRegistry: com.cotor.data.registry.AgentRegistry,
    private val outputValidator: OutputValidator
) : PipelineOrchestrator {

    private val activePipelines = ConcurrentHashMap<String, Deferred<AggregatedResult>>()
    private val templateEngine = TemplateEngine()
    private val recoveryExecutor = RecoveryExecutor(
        agentExecutor = agentExecutor,
        agentRegistry = agentRegistry,
        outputValidator = outputValidator,
        logger = logger
    )

    override suspend fun executePipeline(pipeline: Pipeline): AggregatedResult = coroutineScope {
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
                result
            } catch (e: Exception) {
                logger.error("Pipeline failed: ${pipeline.name}", e)
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
        var previousOutput: String? = null

        pipeline.stages.forEachIndexed { index, stage ->
            pipelineContext.currentStageIndex = index
            val interpolatedInput = stage.input?.let { templateEngine.interpolate(it, pipelineContext) }
            val stageInput = interpolatedInput ?: previousOutput
            val result = executeStageWithGuards(stage, pipelineId, pipelineContext, stageInput)
            results.add(result)

            if (result.isSuccess && !result.output.isNullOrBlank()) {
                previousOutput = result.output
            }

            if (!result.isSuccess && stage.failureStrategy == FailureStrategy.ABORT && !stage.optional) {
                return resultAggregator.aggregate(results)
            }
        }

        return resultAggregator.aggregate(results)
    }

    private suspend fun executeParallel(
        pipeline: Pipeline,
        pipelineId: String,
        pipelineContext: PipelineContext
    ): AggregatedResult = coroutineScope {
        val results = pipeline.stages.map { stage ->
            async(Dispatchers.Default) {
                val interpolatedInput = stage.input?.let { templateEngine.interpolate(it, pipelineContext) }
                executeStageWithGuards(stage, pipelineId, pipelineContext, interpolatedInput)
            }
        }.awaitAll()

        resultAggregator.aggregate(results)
    }

    private suspend fun executeDag(
        pipeline: Pipeline,
        pipelineId: String,
        pipelineContext: PipelineContext
    ): AggregatedResult {
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

        return resultAggregator.aggregate(results.values.toList())
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
        return try {
            runStage(stage, pipelineId, pipelineContext, input)
        } catch (e: Exception) {
            val failureResult = pipelineContext.getStageResult(stage.id)
                ?: AgentResult(stage.agent.name, false, null, e.message, 0, emptyMap())
            if (stage.optional || stage.failureStrategy == FailureStrategy.CONTINUE) {
                failureResult
            } else {
                throw e
            }
        }
    }

    private suspend fun runStage(
        stage: PipelineStage,
        pipelineId: String,
        pipelineContext: PipelineContext,
        input: String?
    ): AgentResult {
        eventBus.emit(StageStartedEvent(stage.id, pipelineId))

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
                agentName = stage.agent.name,
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
