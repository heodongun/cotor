package com.cotor.validation

/**
 * File overview for PipelineTemplateValidator.
 *
 * This file belongs to the validation layer that rejects invalid pipelines before execution.
 * It groups declarations around pipeline template validator so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import com.cotor.context.TemplateEngine
import com.cotor.model.Pipeline
import com.cotor.model.ValidationResult

/**
 * Validates all template expressions in a pipeline.
 */
class PipelineTemplateValidator(private val templateEngine: TemplateEngine) {

    fun validate(pipeline: Pipeline): ValidationResult {
        val errors = mutableListOf<String>()
        val stageIds = pipeline.stages.map { it.id }.toSet()

        pipeline.stages.forEach { stage ->
            stage.input?.let { input ->
                templateEngine.expressionPattern.findAll(input).forEach { matchResult ->
                    val expression = matchResult.groupValues[1]
                    val parts = expression.trim().split('.')
                    if (parts.getOrNull(0) == "stages") {
                        val stageId = parts.getOrNull(1)
                        if (stageId != null && !stageIds.contains(stageId)) {
                            errors.add("Invalid stage reference in stage '${stage.id}': Stage '$stageId' not found in pipeline.")
                        }
                    }
                }
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.Success
        } else {
            ValidationResult.Failure(errors)
        }
    }
}
