package com.cotor.validation.output

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
