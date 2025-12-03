package com.cotor.context

import com.cotor.model.PipelineContext

/**
 * Simple template engine to interpolate pipeline context placeholders in stage input.
 * Supports legacy `{{...}}` and new `${...}` syntax.
 */
class TemplateEngine {
    // Legacy patterns
    private val stageOutputPattern =
        Regex("\\{\\{\\s*context\\.stageResults\\.([^.\\s}]+)\\.output\\s*\\}\\}")
    private val sharedStatePattern =
        Regex("\\{\\{\\s*context\\.sharedState\\.([^.\\s}]+)\\s*\\}\\}")
    private val metadataPattern =
        Regex("\\{\\{\\s*context\\.metadata\\.([^.\\s}]+)\\s*\\}\\}")
    private val allOutputsPattern = Regex("\\{\\{\\s*context\\.allOutputs\\s*\\}\\}")
    private val successfulOutputsPattern = Regex("\\{\\{\\s*context\\.successfulOutputs\\s*\\}\\}")

    // New unified expression pattern
    internal val expressionPattern = Regex("\\\$\\{\\s*([^}]+)\\s*\\}")

    fun interpolate(template: String, context: PipelineContext): String {
        var result = template

        // Handle new ${...} expressions first
        result = expressionPattern.replace(result) { matchResult ->
            resolveExpression(matchResult.groupValues[1], context, InterpolationMode.INTERPOLATE) as String
        }

        // Legacy support
        result = stageOutputPattern.replace(result) { matchResult ->
            val stageId = matchResult.groupValues[1]
            context.getStageOutput(stageId) ?: "[stage:$stageId output not found]"
        }

        result = sharedStatePattern.replace(result) { matchResult ->
            val key = matchResult.groupValues[1]
            context.sharedState[key]?.toString() ?: "[sharedState:$key missing]"
        }

        result = metadataPattern.replace(result) { matchResult ->
            val key = matchResult.groupValues[1]
            context.metadata[key]?.toString() ?: "[metadata:$key missing]"
        }

        result = allOutputsPattern.replace(result) {
            context.getAllOutputs()
        }

        result = successfulOutputsPattern.replace(result) {
            context.getSuccessfulOutputs()
        }

        return result
    }

    internal fun validate(template: String, context: PipelineContext): List<String> {
        val errors = mutableListOf<String>()
        expressionPattern.findAll(template).forEach { matchResult ->
            val result = resolveExpression(matchResult.groupValues[1], context, InterpolationMode.VALIDATE)
            if (result is ValidationResult.Error) {
                errors.add(result.message)
            }
        }
        return errors
    }

    private sealed class ValidationResult {
        object Success : ValidationResult()
        data class Error(val message: String) : ValidationResult()
    }

    private enum class InterpolationMode {
        INTERPOLATE,
        VALIDATE
    }

    private fun resolveExpression(
        expression: String,
        context: PipelineContext,
        mode: InterpolationMode
    ): Any {
        val parts = expression.trim().split('.')
        val original = "\${$expression}"

        val handleError = { message: String ->
            if (mode == InterpolationMode.VALIDATE) ValidationResult.Error(message) else message
        }

        return when (val scope = parts.getOrNull(0)) {
            "stages" -> handleStageScope(parts, context, original, handleError)
            "pipeline" -> handlePipelineScope(parts, context, original, handleError)
            "env" -> handleEnvScope(parts, original, handleError)
            else -> handleError("[unknown scope '$scope' in expression: $original]")
        }
    }

    private fun handleStageScope(
        parts: List<String>,
        context: PipelineContext,
        original: String,
        handleError: (String) -> Any
    ): Any {
        val stageId = parts.getOrNull(1)
            ?: return handleError("[missing stage id in expression: $original]")
        val property = parts.getOrNull(2)
            ?: return handleError("[missing property for stage in expression: $original]")

        if (context.stageResults[stageId] == null) {
            return handleError("[stage '$stageId' not found or not yet executed in expression: $original]")
        }

        return when (property) {
            "output" -> context.getStageOutput(stageId)
                ?: handleError("[stage '$stageId' output not found for expression: $original]")
            else -> handleError("[invalid property '$property' for stage in expression: $original]")
        }
    }

    private fun handlePipelineScope(
        parts: List<String>,
        context: PipelineContext,
        original: String,
        handleError: (String) -> Any
    ): Any {
        return when (val property = parts.getOrNull(1)) {
            "name" -> context.pipelineName
            "id" -> context.pipelineId
            else -> handleError("[invalid property '$property' for pipeline in expression: $original]")
        }
    }

    private fun handleEnvScope(
        parts: List<String>,
        original: String,
        handleError: (String) -> Any
    ): Any {
        val varName = parts.getOrNull(1)
            ?: return handleError("[missing environment variable name in expression: $original]")

        return System.getenv(varName)
            ?: handleError("[env variable '$varName' not found for expression: $original]")
    }
}
