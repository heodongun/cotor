package com.cotor.validation

import com.cotor.model.CotorConfig

/**
 * Represents the result of a linting operation.
 * @property errors A list of critical issues found.
 * @property warnings A list of non-critical issues or suggestions.
 */
data class LintResult(
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    val isSuccess: Boolean get() = errors.isEmpty()
}

/**
 * A service to perform static analysis on Cotor configuration files.
 * It checks for schema violations, best practice deviations, and potential errors.
 */
class Linter {

    /**
     * Analyzes the given Cotor configuration and returns a list of issues.
     * @param config The CotorConfig object to analyze.
     * @return A LintResult containing all found errors and warnings.
     */
    fun lint(config: CotorConfig): LintResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        errors.addAll(checkDuplicateAgentNames(config))
        warnings.addAll(checkUnusedAgents(config))

        config.pipelines.forEach { pipeline ->
            errors.addAll(checkDuplicateStageIds(pipeline))
            errors.addAll(checkUndefinedAgentReferences(pipeline, config))
        }

        return LintResult(errors, warnings)
    }

    private fun checkDuplicateAgentNames(config: CotorConfig): List<String> {
        val duplicates = config.agents
            .groupingBy { it.name }
            .eachCount()
            .filter { it.value > 1 }
            .keys
        return duplicates.map { "Duplicate agent name found: '$it'" }
    }

    private fun checkUnusedAgents(config: CotorConfig): List<String> {
        val allAgentNames = config.agents.map { it.name }.toSet()
        val usedAgentNames = config.pipelines
            .flatMap { it.stages }
            .mapNotNull { it.agent?.name }
            .toSet()
        val unused = allAgentNames - usedAgentNames
        return unused.map { "Unused agent definition: '$it'" }
    }

    private fun checkDuplicateStageIds(pipeline: com.cotor.model.Pipeline): List<String> {
        val duplicates = pipeline.stages
            .groupingBy { it.id }
            .eachCount()
            .filter { it.value > 1 }
            .keys
        return duplicates.map { "Duplicate stage ID '$it' found in pipeline '${pipeline.name}'" }
    }

    private fun checkUndefinedAgentReferences(pipeline: com.cotor.model.Pipeline, config: CotorConfig): List<String> {
        val definedAgentNames = config.agents.map { it.name }.toSet()
        return pipeline.stages
            .mapNotNull { stage ->
                stage.agent?.name?.let { agentName ->
                    if (!definedAgentNames.contains(agentName)) {
                        "Stage '${stage.id}' in pipeline '${pipeline.name}' refers to an undefined agent: '$agentName'"
                    } else {
                        null
                    }
                }
            }
    }
}
