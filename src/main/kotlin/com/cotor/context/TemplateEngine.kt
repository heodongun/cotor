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
    private val expressionPattern = Regex("\\\$\\{\\s*([^}]+)\\s*\\}")

    fun interpolate(template: String, context: PipelineContext): String {
        var result = template

        // Handle new ${...} expressions first
        result = expressionPattern.replace(result) { matchResult ->
            resolveExpression(matchResult.groupValues[1], context, matchResult.value)
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

    private fun resolveExpression(expression: String, context: PipelineContext, original: String): String {
        val parts = expression.trim().split('.')
        return when (parts.getOrNull(0)) {
            "stages" -> {
                val stageId = parts.getOrNull(1)
                val property = parts.getOrNull(2)
                if (stageId != null && property == "output") {
                    context.getStageOutput(stageId) ?: "[stage:$stageId output not found]"
                } else {
                    "[invalid expression: $original]"
                }
            }
            "pipeline" -> {
                val property = parts.getOrNull(1)
                if (property == "name") {
                    context.pipelineName
                } else {
                    "[invalid expression: $original]"
                }
            }
            "env" -> {
                val varName = parts.getOrNull(1)
                if (varName != null) {
                    System.getenv(varName) ?: "[env:$varName not found]"
                } else {
                    "[invalid expression: $original]"
                }
            }
            else -> "[unknown expression: $original]"
        }
    }
}
