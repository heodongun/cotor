package com.cotor.validation.output

/**
 * File overview for OutputValidator.
 *
 * This file belongs to the validation layer that rejects invalid pipelines before execution.
 * It groups declarations around output validator so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import com.cotor.model.AgentResult
import com.cotor.model.StageValidationConfig

/**
 * Validates agent output against configured rules.
 */
interface OutputValidator {
    fun validate(result: AgentResult, config: StageValidationConfig): StageValidationOutcome
}

data class StageValidationOutcome(
    val isValid: Boolean,
    val score: Double,
    val violations: List<String>,
    val suggestions: List<String>
)
