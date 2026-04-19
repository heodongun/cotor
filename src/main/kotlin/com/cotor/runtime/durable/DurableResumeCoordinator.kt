package com.cotor.runtime.durable

import com.cotor.data.config.ConfigRepository
import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.orchestrator.PipelineOrchestrator
import com.cotor.model.AgentResult
import com.cotor.model.PipelineContext
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.io.path.Path

class DurableResumeCoordinator(
    private val configRepository: ConfigRepository,
    private val agentRegistry: AgentRegistry,
    private val orchestrator: PipelineOrchestrator,
    private val durableRuntimeService: DurableRuntimeService
) {
    suspend fun inspect(runId: String): DurableRunSnapshot? = durableRuntimeService.inspectRun(runId)

    suspend fun continueRun(runId: String, configPathOverride: String? = null): DurableExecutionPlan {
        val snapshot = durableRuntimeService.inspectRun(runId) ?: error("No durable run found for $runId")
        val configPath = configPathOverride ?: snapshot.configPath ?: "cotor.yaml"
        val config = configRepository.loadConfig(Path(configPath))
        config.agents.forEach { agentRegistry.registerAgent(it) }
        val pipeline = config.pipelines.firstOrNull { it.name == snapshot.pipelineName }
            ?: error("Pipeline '${snapshot.pipelineName}' not found in $configPath")
        val plan = durableRuntimeService.buildExecutionPlan(runId, pipeline, replayMode = ReplayMode.CONTINUE)
        if (plan.pendingApproval != null) {
            throw ReplayApprovalRequiredException(runId, plan.pendingApproval)
        }
        if (plan.nextStageId == null) {
            return plan
        }
        val restoredContext = buildContext(plan, configPath)
        val durableContext = DurableRuntimeContext(
            runId = runId,
            replayMode = ReplayMode.CONTINUE,
            sourceRunId = snapshot.sourceRunId ?: snapshot.runId,
            sourceCheckpointId = plan.restoreCheckpointId,
            configPath = configPath
        )
        withContext(durableContext) {
            orchestrator.executePipeline(
                pipeline = pipeline,
                fromStageId = plan.nextStageId,
                context = restoredContext
            )
        }
        return durableRuntimeService.buildExecutionPlan(runId, pipeline, replayMode = ReplayMode.CONTINUE)
    }

    suspend fun forkRun(runId: String, checkpointId: String, configPathOverride: String? = null): DurableExecutionPlan {
        val source = durableRuntimeService.inspectRun(runId) ?: error("No durable run found for $runId")
        val configPath = configPathOverride ?: source.configPath ?: "cotor.yaml"
        val config = configRepository.loadConfig(Path(configPath))
        config.agents.forEach { agentRegistry.registerAgent(it) }
        val pipeline = config.pipelines.firstOrNull { it.name == source.pipelineName }
            ?: error("Pipeline '${source.pipelineName}' not found in $configPath")
        val forkRunId = UUID.randomUUID().toString()
        val plan = durableRuntimeService.buildExecutionPlan(runId, pipeline, checkpointId, ReplayMode.FORK)
        durableRuntimeService.createFork(runId, forkRunId, checkpointId, configPath)
        val restoredContext = buildContext(
            plan = plan.copy(runId = forkRunId, replayMode = ReplayMode.FORK),
            configPath = configPath,
            sourceRunId = runId,
            sourceCheckpointId = checkpointId
        )
        if (plan.pendingApproval != null) {
            throw ReplayApprovalRequiredException(forkRunId, plan.pendingApproval)
        }
        if (plan.nextStageId != null) {
            val durableContext = DurableRuntimeContext(
                runId = forkRunId,
                replayMode = ReplayMode.FORK,
                sourceRunId = runId,
                sourceCheckpointId = checkpointId,
                configPath = configPath
            )
            withContext(durableContext) {
                orchestrator.executePipeline(
                    pipeline = pipeline,
                    fromStageId = plan.nextStageId,
                    context = restoredContext
                )
            }
        }
        return durableRuntimeService.inspectRun(forkRunId)?.let {
            durableRuntimeService.buildExecutionPlan(it.runId, pipeline, checkpointId, ReplayMode.FORK)
        } ?: plan
    }

    suspend fun approve(runId: String, checkpointId: String? = null): DurableRunSnapshot =
        durableRuntimeService.approve(runId, checkpointId)

    private fun buildContext(
        plan: DurableExecutionPlan,
        configPath: String,
        sourceRunId: String? = null,
        sourceCheckpointId: String? = null
    ): PipelineContext {
        val context = PipelineContext(
            pipelineId = plan.runId,
            pipelineName = plan.pipelineName,
            totalStages = 0,
            stageResults = linkedMapOf()
        )
        plan.stageResults.forEach { node ->
            val stageId = node.stageId ?: return@forEach
            context.addStageResult(
                stageId,
                AgentResult(
                    agentName = node.agentName ?: stageId,
                    isSuccess = node.isSuccess ?: true,
                    output = node.output,
                    error = node.error,
                    duration = node.durationMs ?: 0L,
                    metadata = node.metadata
                )
            )
        }
        DurableRuntimeFlags.enable(context)
        context.metadata["durableRunId"] = plan.runId
        context.metadata["replayMode"] = plan.replayMode.name
        context.metadata["configPath"] = configPath
        sourceRunId?.let { context.metadata["sourceRunId"] = it }
        sourceCheckpointId?.let { context.metadata["sourceCheckpointId"] = it }
        plan.restoreCheckpointId?.let { context.metadata["sourceCheckpointId"] = it }
        return context
    }
}
