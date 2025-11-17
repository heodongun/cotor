package com.cotor.validation

import com.cotor.data.registry.AgentRegistry
import com.cotor.model.Pipeline
import com.cotor.model.PipelineStage

/**
 * Validation result
 */
sealed class ValidationResult {
    data class Success(val warnings: List<String> = emptyList()) : ValidationResult()
    data class Failure(val errors: List<String>, val warnings: List<String> = emptyList()) : ValidationResult()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
}

/**
 * Pipeline validator
 */
class PipelineValidator(
    private val agentRegistry: AgentRegistry
) {
    /**
     * Validate pipeline configuration
     */
    fun validate(pipeline: Pipeline): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Validate pipeline name
        if (pipeline.name.isBlank()) {
            errors.add("Pipeline name cannot be empty")
        }

        // Validate stages
        if (pipeline.stages.isEmpty()) {
            errors.add("Pipeline must have at least one stage")
        }

        // Validate each stage
        pipeline.stages.forEach { stage ->
            validateStage(stage, errors, warnings)
        }

        // Validate stage IDs are unique
        val duplicateIds = pipeline.stages.groupBy { it.id }
            .filter { it.value.size > 1 }
            .keys

        if (duplicateIds.isNotEmpty()) {
            errors.add("Duplicate stage IDs found: ${duplicateIds.joinToString(", ")}")
        }

        // Validate dependencies for DAG mode
        if (pipeline.executionMode.name == "DAG") {
            validateDependencies(pipeline.stages, errors)
        }

        return if (errors.isEmpty()) {
            ValidationResult.Success(warnings)
        } else {
            ValidationResult.Failure(errors, warnings)
        }
    }

    /**
     * Validate a single stage
     */
    private fun validateStage(stage: PipelineStage, errors: MutableList<String>, warnings: MutableList<String>) {
        // Validate stage ID
        if (stage.id.isBlank()) {
            errors.add("Stage ID cannot be empty")
        }

        // Validate agent exists
        val agentExists = try {
            agentRegistry.getAgent(stage.agent.name) != null
        } catch (e: Exception) {
            false
        }

        if (!agentExists) {
            errors.add("Stage '${stage.id}': Agent '${stage.agent.name}' not defined")
        }

        // Validate input
        if (stage.input?.isBlank() != false) {
            warnings.add("Stage '${stage.id}': Empty input, will use previous stage output")
        }
    }

    /**
     * Validate dependencies for DAG execution
     */
    private fun validateDependencies(stages: List<PipelineStage>, errors: MutableList<String>) {
        val stageIds = stages.map { it.id }.toSet()

        stages.forEach { stage ->
            stage.dependencies.forEach { depId ->
                if (depId !in stageIds) {
                    errors.add("Stage '${stage.id}': Dependency '$depId' not found")
                }
            }

            // Check for circular dependencies
            if (hasCircularDependency(stage, stages)) {
                errors.add("Stage '${stage.id}': Circular dependency detected")
            }
        }
    }

    /**
     * Check for circular dependencies
     */
    private fun hasCircularDependency(stage: PipelineStage, stages: List<PipelineStage>): Boolean {
        val visited = mutableSetOf<String>()
        val stageMap = stages.associateBy { it.id }

        fun visit(currentId: String): Boolean {
            if (currentId in visited) return true
            visited.add(currentId)

            val current = stageMap[currentId] ?: return false
            return current.dependencies.any { visit(it) }
        }

        return visit(stage.id)
    }

    /**
     * Estimate pipeline duration (for dry-run)
     */
    fun estimateDuration(pipeline: Pipeline): EstimatedDuration {
        val stageEstimates = pipeline.stages.map { stage ->
            StageEstimate(
                stageId = stage.id,
                agentName = stage.agent.name,
                estimatedSeconds = 30 // Default 30s estimate per stage
            )
        }

        val totalSeconds = when (pipeline.executionMode.name) {
            "SEQUENTIAL" -> stageEstimates.sumOf { it.estimatedSeconds }
            "PARALLEL" -> stageEstimates.maxOfOrNull { it.estimatedSeconds } ?: 0
            "DAG" -> estimateDagDuration(pipeline.stages, stageEstimates)
            else -> stageEstimates.sumOf { it.estimatedSeconds }
        }

        return EstimatedDuration(
            pipelineName = pipeline.name,
            executionMode = pipeline.executionMode.name,
            stageEstimates = stageEstimates,
            totalEstimatedSeconds = totalSeconds
        )
    }

    /**
     * Estimate DAG execution duration
     */
    private fun estimateDagDuration(stages: List<PipelineStage>, estimates: List<StageEstimate>): Long {
        val estimateMap = estimates.associateBy { it.stageId }
        val criticalPath = findCriticalPath(stages, estimateMap)
        return criticalPath.sumOf { estimateMap[it]?.estimatedSeconds ?: 0 }
    }

    /**
     * Find critical path in DAG
     */
    private fun findCriticalPath(stages: List<PipelineStage>, estimates: Map<String, StageEstimate>): List<String> {
        val stageMap = stages.associateBy { it.id }
        val distances = mutableMapOf<String, Long>()

        fun computeDistance(stageId: String): Long {
            if (stageId in distances) return distances[stageId]!!

            val stage = stageMap[stageId] ?: return 0
            val stageDuration = estimates[stageId]?.estimatedSeconds ?: 0

            val maxDepDistance = stage.dependencies.maxOfOrNull { computeDistance(it) } ?: 0
            val totalDistance = stageDuration + maxDepDistance

            distances[stageId] = totalDistance
            return totalDistance
        }

        stages.forEach { computeDistance(it.id) }

        return distances.entries
            .sortedByDescending { it.value }
            .take(1)
            .map { it.key }
    }
}

/**
 * Stage duration estimate
 */
data class StageEstimate(
    val stageId: String,
    val agentName: String,
    val estimatedSeconds: Long
)

/**
 * Pipeline duration estimate
 */
data class EstimatedDuration(
    val pipelineName: String,
    val executionMode: String,
    val stageEstimates: List<StageEstimate>,
    val totalEstimatedSeconds: Long
) {
    fun formatEstimate(): String {
        val minutes = totalEstimatedSeconds / 60
        val seconds = totalEstimatedSeconds % 60

        return buildString {
            appendLine("📋 Pipeline Estimate: $pipelineName")
            appendLine("   Execution Mode: $executionMode")
            appendLine()
            appendLine("Stages:")
            stageEstimates.forEach { estimate ->
                appendLine("  ├─ ${estimate.stageId} (${estimate.agentName})")
                appendLine("  │  └─ ~${estimate.estimatedSeconds}s")
            }
            appendLine()
            if (minutes > 0) {
                appendLine("⏱️  Total Estimated Duration: ~${minutes}m ${seconds}s")
            } else {
                appendLine("⏱️  Total Estimated Duration: ~${seconds}s")
            }
        }
    }
}
