package com.cotor.context

import com.cotor.model.PipelineContext

/**
 * Simple template engine to interpolate pipeline context placeholders in stage input.
 */
class TemplateEngine {
    private val stageOutputPattern =
        Regex("\\{\\{\\s*context\\.stageResults\\.([^.\\s}]+)\\.output\\s*\\}\\}")
    private val sharedStatePattern =
        Regex("\\{\\{\\s*context\\.sharedState\\.([^.\\s}]+)\\s*\\}\\}")
    private val metadataPattern =
        Regex("\\{\\{\\s*context\\.metadata\\.([^.\\s}]+)\\s*\\}\\}")
    private val allOutputsPattern = Regex("\\{\\{\\s*context\\.allOutputs\\s*\\}\\}")
    private val successfulOutputsPattern = Regex("\\{\\{\\s*context\\.successfulOutputs\\s*\\}\\}")

    fun interpolate(template: String, context: PipelineContext): String {
        var result = template
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
}
