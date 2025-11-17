package com.cotor.error

/**
 * User-friendly error with solutions and documentation links
 */
class UserFriendlyError(
    val problem: String,
    val solutions: List<String>,
    val docsUrl: String? = null,
    cause: Throwable? = null
) : Exception(buildMessage(problem, solutions, docsUrl), cause) {

    companion object {
        private fun buildMessage(problem: String, solutions: List<String>, docsUrl: String?): String {
            return buildString {
                appendLine()
                appendLine("❌ Error: $problem")
                appendLine()
                appendLine("📍 Problem:")
                appendLine("   $problem")
                appendLine()
                appendLine("💡 Solutions:")
                solutions.forEachIndexed { index, solution ->
                    appendLine("   ${index + 1}. $solution")
                }
                if (docsUrl != null) {
                    appendLine()
                    appendLine("📖 Documentation: $docsUrl")
                }
            }
        }
    }
}

/**
 * Common error scenarios with helpful messages
 */
object ErrorMessages {
    fun configNotFound(configPath: String) = UserFriendlyError(
        problem = "Pipeline configuration not found",
        solutions = listOf(
            "Run 'cotor init' to create a default configuration",
            "Specify config path: cotor run -c path/to/config.yaml <pipeline>",
            "Check if you're in the correct directory: $configPath"
        ),
        docsUrl = "https://docs.cotor.dev/configuration"
    )

    fun pipelineNotFound(pipelineName: String, availablePipelines: List<String>) = UserFriendlyError(
        problem = "Pipeline '$pipelineName' not found",
        solutions = buildList {
            add("Check the pipeline name spelling")
            if (availablePipelines.isNotEmpty()) {
                add("Available pipelines: ${availablePipelines.joinToString(", ")}")
                add("Run 'cotor list' to see all pipelines")
            } else {
                add("No pipelines defined in configuration")
                add("Add pipelines to cotor.yaml file")
            }
        },
        docsUrl = "https://docs.cotor.dev/pipelines"
    )

    fun agentNotFound(agentName: String) = UserFriendlyError(
        problem = "Agent '$agentName' not found",
        solutions = listOf(
            "Check if the agent is defined in cotor.yaml",
            "Verify the agent name matches the configuration",
            "Make sure the plugin class exists: com.cotor.data.plugin.<AgentName>Plugin"
        ),
        docsUrl = "https://docs.cotor.dev/agents"
    )

    fun pluginExecutionFailed(pluginName: String, error: String) = UserFriendlyError(
        problem = "Plugin '$pluginName' execution failed",
        solutions = listOf(
            "Check if the plugin executable is installed and in PATH",
            "Verify plugin configuration in cotor.yaml",
            "Run with --verbose flag to see detailed error: cotor run --verbose <pipeline>",
            "Error details: $error"
        ),
        docsUrl = "https://docs.cotor.dev/plugins"
    )

    fun validationFailed(validationErrors: List<String>) = UserFriendlyError(
        problem = "Pipeline validation failed",
        solutions = buildList {
            add("Fix the following validation errors:")
            addAll(validationErrors.map { "  - $it" })
            add("Run 'cotor validate <pipeline>' to check configuration")
        },
        docsUrl = "https://docs.cotor.dev/validation"
    )

    fun stageTimeout(stageId: String, timeout: Long) = UserFriendlyError(
        problem = "Stage '$stageId' exceeded timeout of ${timeout}ms",
        solutions = listOf(
            "Increase timeout in pipeline configuration",
            "Simplify the stage input/prompt",
            "Check if the plugin is stuck or taking too long",
            "Run with --verbose to see stage progress"
        )
    )

    fun securityViolation(executable: String) = UserFriendlyError(
        problem = "Security violation: Executable '$executable' not allowed",
        solutions = listOf(
            "Add '$executable' to security.allowedExecutables in cotor.yaml",
            "Review security settings to ensure proper whitelist configuration",
            "Verify this executable is safe to run"
        ),
        docsUrl = "https://docs.cotor.dev/security"
    )
}
