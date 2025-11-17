package com.cotor.domain.orchestrator

import com.cotor.domain.aggregator.ResultAggregator
import com.cotor.domain.executor.AgentExecutor
import com.cotor.event.EventBus
import com.cotor.event.*
import com.cotor.model.*
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
    private val agentRegistry: com.cotor.data.registry.AgentRegistry
) : PipelineOrchestrator {

    private val activePipelines = ConcurrentHashMap<String, Deferred<AggregatedResult>>()

    override suspend fun executePipeline(pipeline: Pipeline): AggregatedResult = coroutineScope {
        val pipelineId = UUID.randomUUID().toString()
        logger.info("Starting pipeline: ${pipeline.name} (ID: $pipelineId)")

        val deferred = async {
            eventBus.emit(PipelineStartedEvent(pipelineId, pipeline.name))

            try {
                val result = when (pipeline.executionMode) {
                    ExecutionMode.SEQUENTIAL -> executeSequential(pipeline)
                    ExecutionMode.PARALLEL -> executeParallel(pipeline)
                    ExecutionMode.DAG -> executeDag(pipeline)
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

    private suspend fun executeSequential(pipeline: Pipeline): AggregatedResult {
        val results = mutableListOf<AgentResult>()
        var previousOutput: String? = null
        val pipelineId = UUID.randomUUID().toString()

        for (stage in pipeline.stages) {
            try {
                eventBus.emit(StageStartedEvent(stage.id, pipelineId))

                val agentConfig = agentRegistry.getAgent(stage.agent.name)
                    ?: throw IllegalArgumentException("Agent not found: ${stage.agent.name}")

                val input = previousOutput ?: stage.input
                val result = agentExecutor.executeAgent(agentConfig, input)
                results.add(result)

                eventBus.emit(StageCompletedEvent(stage.id, pipelineId, result))

                if (!result.isSuccess && stage.failureStrategy == FailureStrategy.ABORT) {
                    break
                }

                previousOutput = result.output
            } catch (e: Exception) {
                eventBus.emit(StageFailedEvent(stage.id, pipelineId, e))
                throw e
            }
        }

        return resultAggregator.aggregate(results)
    }

    private suspend fun executeParallel(pipeline: Pipeline): AggregatedResult = coroutineScope {
        val results = pipeline.stages.map { stage ->
            async(Dispatchers.Default) {
                val agentConfig = agentRegistry.getAgent(stage.agent.name)
                    ?: throw IllegalArgumentException("Agent not found: ${stage.agent.name}")
                agentExecutor.executeAgent(agentConfig, stage.input)
            }
        }.awaitAll()

        resultAggregator.aggregate(results)
    }

    private suspend fun executeDag(pipeline: Pipeline): AggregatedResult {
        // Build dependency graph
        val results = mutableMapOf<String, AgentResult>()
        val stageMap = pipeline.stages.associateBy { it.id }

        // Topological sort
        val sortedStages = topologicalSort(pipeline.stages)

        for (stage in sortedStages) {
            val agentConfig = agentRegistry.getAgent(stage.agent.name)
                ?: throw IllegalArgumentException("Agent not found: ${stage.agent.name}")
            
            val input = if (stage.dependencies.isEmpty()) {
                stage.input
            } else {
                resolveDependencies(stage.dependencies, results)
            }

            val result = agentExecutor.executeAgent(agentConfig, input)
            results[stage.id] = result
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
    ): String {
        return dependencies
            .mapNotNull { results[it]?.output }
            .joinToString("\n")
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
