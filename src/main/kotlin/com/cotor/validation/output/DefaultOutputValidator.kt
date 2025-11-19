package com.cotor.validation.output

import com.cotor.model.AgentResult
import com.cotor.model.StageValidationConfig
import java.nio.file.Files
import java.nio.file.Path

class DefaultOutputValidator(
    private val syntaxValidator: SyntaxValidator
) : OutputValidator {

    override fun validate(result: AgentResult, config: StageValidationConfig): StageValidationOutcome {
        val violations = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        var score = 1.0
        val output = result.output.orEmpty()

        config.requiresFile?.let { filePath ->
            val path = Path.of(filePath)
            if (!Files.exists(path)) {
                violations.add("Required file not found: $filePath")
                suggestions.add("Ensure the agent persists output to $filePath")
                score -= 0.3
            }
        }

        if (config.requiresCodeBlock && !output.contains("```")) {
            violations.add("Output missing code block")
            suggestions.add("Ask the agent to wrap code samples inside markdown fences")
            score -= 0.2
        }

        if (output.length < config.minLength) {
            violations.add("Output too short (${output.length} < ${config.minLength})")
            suggestions.add("Request more detailed output for this stage")
            score -= 0.15
        }

        if (output.length > config.maxLength) {
            violations.add("Output too long (${output.length} > ${config.maxLength})")
            suggestions.add("Ask the agent to trim unnecessary details")
            score -= 0.1
        }

        config.requiredKeywords.forEach { keyword ->
            if (!output.contains(keyword, ignoreCase = true)) {
                violations.add("Missing required keyword: $keyword")
                suggestions.add("Ensure the response references '$keyword'")
                score -= 0.1
            }
        }

        config.forbiddenKeywords.forEach { keyword ->
            if (output.contains(keyword, ignoreCase = true)) {
                violations.add("Forbidden keyword present: $keyword")
                suggestions.add("Remove occurrences of '$keyword'")
                score -= 0.1
            }
        }

        config.customValidators.forEach { validator ->
            if (validator.type.equals("syntax", ignoreCase = true)) {
                val language = validator.options["language"] ?: return@forEach
                val targetPath = validator.options["file"] ?: config.requiresFile ?: return@forEach
                val syntaxResult = syntaxValidator.validate(language, targetPath)
                if (!syntaxResult.isValid) {
                    violations.addAll(syntaxResult.errors)
                    score -= 0.2
                }
            }
        }

        val normalizedScore = score.coerceIn(0.0, 1.0)
        val isValid = violations.isEmpty() && normalizedScore >= config.minQualityScore

        return StageValidationOutcome(
            isValid = isValid,
            score = normalizedScore,
            violations = violations,
            suggestions = suggestions
        )
    }
}
