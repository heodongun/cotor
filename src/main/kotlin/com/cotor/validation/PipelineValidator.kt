package com.cotor.validation

import com.cotor.data.registry.AgentRegistry
import com.cotor.model.Pipeline
import com.cotor.model.PipelineStage
import com.cotor.model.StageType
import com.cotor.model.RecoveryStrategy

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
    private val agentRegistry: AgentRegistry,
    private val pluginLoader: com.cotor.data.plugin.PluginLoader
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
        val stageIds = pipeline.stages.map { it.id }.toSet()
        pipeline.stages.forEach { stage ->
            validateStage(stage, errors, warnings, stageIds)
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
    private fun validateStage(
        stage: PipelineStage,
        errors: MutableList<String>,
        warnings: MutableList<String>,
        stageIds: Set<String>
    ) {
        // Validate stage ID
        if (stage.id.isBlank()) {
            errors.add("Stage ID cannot be empty")
        }

        when (stage.type) {
            StageType.EXECUTION -> {
                validateExecutionStage(stage, errors)
                validateRecovery(stage, errors, warnings)
            }
            StageType.DECISION -> validateDecisionStage(stage, errors)
            StageType.LOOP -> validateLoopStage(stage, errors, stageIds)
        }

        // Validate input for execution stages only
        if (stage.type == StageType.EXECUTION) {
            if (stage.input?.isBlank() != false) {
                warnings.add("Stage '${stage.id}': Empty input, will use previous stage output")
            } else {
                validateTemplateReferences(stage, errors, stageIds)
            }
        }
    }

    /**
     * Validate template expressions `${...}` in stage inputs.
     */
    private fun validateTemplateReferences(
        stage: PipelineStage,
        errors: MutableList<String>,
        stageIds: Set<String>
    ) {
        val expressionPattern = Regex("\\\$\\{\\s*([^}]+)\\s*\\}")
        stage.input?.let { input ->
            expressionPattern.findAll(input).forEach { matchResult ->
                val expression = matchResult.groupValues[1].trim()
                val parts = expression.split('.')
                if (parts.getOrNull(0) == "stages") {
                    val referencedStageId = parts.getOrNull(1)
                    if (referencedStageId == null) {
                        errors.add("Stage '${stage.id}': Invalid stage reference in input: '${matchResult.value}'")
                    } else if (referencedStageId !in stageIds) {
                        errors.add("Stage '${stage.id}': Referenced stage '$referencedStageId' not found in pipeline.")
                    } else if (parts.getOrNull(2) != "output") {
                        errors.add("Stage '${stage.id}': Invalid property access in '${matchResult.value}'. Only '.output' is supported.")
                    }
                }
            }
        }
    }

    private fun validateExecutionStage(
        stage: PipelineStage,
        errors: MutableList<String>
    ) {
        val agentName = stage.agent?.name
        if (agentName.isNullOrBlank()) {
            errors.add("Stage '${stage.id}': Execution stage requires an agent")
            return
        }

        val agentConfig = try {
            agentRegistry.getAgent(agentName)
        } catch (e: Exception) {
            null
        }

        if (agentConfig == null) {
            errors.add("Stage '${stage.id}': Agent '$agentName' not defined")
            return
        }

        val agentPlugin = try {
            pluginLoader.loadPlugin(agentConfig.pluginClass)
        } catch (e: Exception) {
            errors.add("Stage '${stage.id}': Could not load plugin for agent '$agentName'")
            return
        }

        validateAgentParameters(stage, agentConfig, agentPlugin, errors)
    }

    private fun validateAgentParameters(
        stage: PipelineStage,
        agentConfig: com.cotor.model.AgentConfig,
        agentPlugin: com.cotor.data.plugin.AgentPlugin,
        errors: MutableList<String>
    ) {
        val schema = agentPlugin.parameterSchema
        if (schema.parameters.isEmpty()) {
            return // No parameters to validate
        }

        val providedParameters = agentConfig.parameters
        val definedParameters = schema.parameters.associateBy { it.name }

        // Check for unknown parameters
        providedParameters.keys.forEach { paramName ->
            if (!definedParameters.containsKey(paramName)) {
                errors.add("Stage '${stage.id}': Unknown parameter '$paramName' for agent '${agentConfig.name}'")
            }
        }

        // Check for missing required parameters and type mismatches
        definedParameters.values.forEach { schemaParam ->
            if (schemaParam.required && !providedParameters.containsKey(schemaParam.name)) {
                errors.add("Stage '${stage.id}': Missing required parameter '${schemaParam.name}' for agent '${agentConfig.name}'")
            }

            providedParameters[schemaParam.name]?.let { paramValue ->
                val typeIsValid = when (schemaParam.type) {
                    com.cotor.model.ParameterType.STRING -> true
                    com.cotor.model.ParameterType.NUMBER -> paramValue.toDoubleOrNull() != null
                    com.cotor.model.ParameterType.BOOLEAN -> paramValue.lowercase() in listOf("true", "false")
                    com.cotor.model.ParameterType.MAP -> true // Basic check, advanced would require parsing
                    com.cotor.model.ParameterType.LIST -> true // Basic check
                }
                if (!typeIsValid) {
                    errors.add("Stage '${stage.id}': Invalid type for parameter '${schemaParam.name}' for agent '${agentConfig.name}'. Expected ${schemaParam.type}, but got value '$paramValue'.")
                }
            }
        }
    }

    private fun validateDecisionStage(stage: PipelineStage, errors: MutableList<String>) {
        val condition = stage.condition
        if (condition == null || condition.expression.isBlank()) {
            errors.add("Stage '${stage.id}': Decision stage requires a condition expression")
        }
    }

    private fun validateRecovery(
        stage: PipelineStage,
        errors: MutableList<String>,
        warnings: MutableList<String>
    ) {
        val recovery = stage.recovery ?: return
        val fallbackAgents = recovery.fallbackAgents
        val usesFallbacks = recovery.strategy == RecoveryStrategy.FALLBACK ||
            recovery.strategy == RecoveryStrategy.RETRY_THEN_FALLBACK

        if (usesFallbacks && fallbackAgents.isEmpty()) {
            warnings.add("Stage '${stage.id}': Recovery strategy ${recovery.strategy} has no fallbackAgents configured")
        }

        fallbackAgents.forEach { agentName ->
            val exists = try {
                agentRegistry.getAgent(agentName) != null
            } catch (e: Exception) {
                false
            }
            if (!exists) {
                errors.add("Stage '${stage.id}': Fallback agent '$agentName' not defined")
            }
        }
    }

    private fun validateLoopStage(
        stage: PipelineStage,
        errors: MutableList<String>,
        stageIds: Set<String>
    ) {
        val loopConfig = stage.loop
        if (loopConfig == null) {
            errors.add("Stage '${stage.id}': Loop stage requires loop configuration")
            return
        }

        if (loopConfig.maxIterations <= 0) {
            errors.add("Stage '${stage.id}': Loop maxIterations must be greater than 0")
        }

        if (loopConfig.targetStageId !in stageIds) {
            errors.add("Stage '${stage.id}': Loop target '${loopConfig.targetStageId}' not found")
        }
    }

    /**
     * Validate dependencies for DAG execution
     */
    private fun validateDependencies(stages: List<PipelineStage>, errors: MutableList<String>) {
        val stageIds = stages.map { it.id }.toSet()
        val stageMap = stages.associateBy { it.id }

        stages.forEach { stage ->
            stage.dependencies.forEach { depId ->
                if (depId !in stageIds) {
                    errors.add("Stage '${stage.id}': Dependency '$depId' not found")
                }
            }
        }

        val visited = mutableSetOf<String>()
        val stack = mutableSetOf<String>()

        fun detectCycle(stageId: String): Boolean {
            if (stageId in stack) return true
            if (stageId in visited) return false

            visited.add(stageId)
            stack.add(stageId)

            val stage = stageMap[stageId] ?: return false
            val hasCycle = stage.dependencies.any { detectCycle(it) }

            stack.remove(stageId)
            return hasCycle
        }

        stageIds.forEach { id ->
            if (detectCycle(id)) {
                errors.add("Stage '$id': Circular dependency detected")
            }
        }
    }

    /**
     * Estimate pipeline duration (for dry-run)
     */
    fun estimateDuration(pipeline: Pipeline): EstimatedDuration {
        val stageEstimates = pipeline.stages.map { stage ->
            StageEstimate(
                stageId = stage.id,
                agentName = stage.agent?.name ?: stage.type.name.lowercase(),
                estimatedSeconds = if (stage.type == StageType.EXECUTION) 30 else 5
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
        val stageMap = stages.associateBy { it.id }
        val memo = mutableMapOf<String, Long>()

        fun computeDuration(stageId: String): Long {
            memo[stageId]?.let { return it }
            val stage = stageMap[stageId] ?: return 0
            val stageDuration = estimateMap[stageId]?.estimatedSeconds ?: 0
            val dependencyDuration = stage.dependencies.maxOfOrNull { computeDuration(it) } ?: 0
            val total = stageDuration + dependencyDuration
            memo[stageId] = total
            return total
        }

        return stages.maxOfOrNull { computeDuration(it.id) } ?: 0
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
