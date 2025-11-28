package com.cotor.error

import com.cotor.model.*

/**
 * Enhanced error messages with actionable suggestions
 */
object ErrorHelper {

    /**
     * Get user-friendly error message with suggestions
     */
    fun getErrorMessage(error: Throwable): ErrorInfo {
        return when (error) {
            is AgentExecutionException -> handleAgentError(error)
            is PipelineException -> handlePipelineError(error)
            is ValidationException -> handleValidationError(error)
            is PipelineAbortedException -> handleAbortError(error)
            else -> handleGenericError(error)
        }
    }

    private fun handleAgentError(error: AgentExecutionException): ErrorInfo {
        val message = error.message ?: "Agent execution failed"

        return when {
            message.contains("timeout", ignoreCase = true) -> ErrorInfo(
                title = "⏱️ Agent Execution Timeout",
                message = message,
                suggestions = listOf(
                    "Increase timeout in agent configuration (current timeout may be too short)",
                    "Check if the AI service is responding slowly",
                    "Simplify the input prompt to reduce processing time",
                    "Try running again - this may be a temporary issue"
                ),
                type = ErrorType.TIMEOUT
            )
            message.contains("not found", ignoreCase = true) || message.contains("no such", ignoreCase = true) -> ErrorInfo(
                title = "🔍 Agent Not Found",
                message = message,
                suggestions = listOf(
                    "Check that the agent executable is installed and in PATH",
                    "Verify the executable name in allowedExecutables list",
                    "Run 'cotor list' to see registered agents",
                    "Check security.allowedDirectories includes the executable location"
                ),
                type = ErrorType.NOT_FOUND
            )
            message.contains("permission", ignoreCase = true) || message.contains("denied", ignoreCase = true) -> ErrorInfo(
                title = "🔐 Permission Denied",
                message = message,
                suggestions = listOf(
                    "Check that the executable has execute permissions (chmod +x)",
                    "Verify security.allowedExecutables includes this agent",
                    "Ensure security.allowedDirectories includes the executable path",
                    "Try running with elevated permissions if necessary"
                ),
                type = ErrorType.PERMISSION
            )
            else -> ErrorInfo(
                title = "❌ Agent Execution Failed",
                message = message,
                suggestions = listOf(
                    "Check the agent's output for specific error details",
                    "Verify the input prompt is valid for this agent",
                    "Try running the agent command manually to debug",
                    "Check agent logs for more information"
                ),
                type = ErrorType.EXECUTION
            )
        }
    }

    private fun handlePipelineError(error: PipelineException): ErrorInfo {
        val message = error.message ?: "Pipeline execution failed"

        return when {
            message.contains("circular", ignoreCase = true) || message.contains("cycle", ignoreCase = true) -> ErrorInfo(
                title = "🔄 Circular Dependency Detected",
                message = message,
                suggestions = listOf(
                    "Check pipeline DAG for circular dependencies",
                    "Run 'cotor validate <pipeline>' to see dependency graph",
                    "Ensure each stage only depends on earlier stages",
                    "Remove or restructure dependencies causing the cycle"
                ),
                type = ErrorType.VALIDATION
            )
            message.contains("not found", ignoreCase = true) && message.contains("pipeline", ignoreCase = true) -> ErrorInfo(
                title = "📋 Pipeline Not Found",
                message = message,
                suggestions = listOf(
                    "Check the pipeline name matches exactly (case-sensitive)",
                    "Verify the pipeline is defined in the configuration file",
                    "List available pipelines with 'cotor list'",
                    "Check the --config path points to the correct file"
                ),
                type = ErrorType.NOT_FOUND
            )
            message.contains("conditional", ignoreCase = true) && message.contains("mode", ignoreCase = true) -> ErrorInfo(
                title = "⚙️ Invalid Execution Mode",
                message = message,
                suggestions = listOf(
                    "Conditional stages (DECISION, LOOP) require SEQUENTIAL mode",
                    "Change executionMode to SEQUENTIAL in your pipeline",
                    "Or remove conditional stages if using PARALLEL/DAG mode",
                    "See docs/UPGRADE_GUIDE.md for conditional stage usage"
                ),
                type = ErrorType.VALIDATION
            )
            else -> ErrorInfo(
                title = "🚨 Pipeline Error",
                message = message,
                suggestions = listOf(
                    "Run 'cotor validate <pipeline>' to check configuration",
                    "Check pipeline syntax matches the documentation",
                    "Review recent changes to pipeline configuration",
                    "Try a simpler pipeline first to isolate the issue"
                ),
                type = ErrorType.EXECUTION
            )
        }
    }

    private fun handleValidationError(error: ValidationException): ErrorInfo {
        val message = error.message ?: "Validation failed"

        return ErrorInfo(
            title = "✅ Validation Failed",
            message = message,
            suggestions = listOf(
                "Check the configuration file syntax (YAML format)",
                "Verify all required fields are present",
                "Run 'cotor validate <pipeline> -c <yaml>' for detailed validation",
                "Compare with template: 'cotor template <type> example.yaml' or examples/*.yaml",
                "Check docs/UPGRADE_GUIDE.md for configuration changes",
                "건조 실행: 'cotor run <pipeline> --dry-run -c <yaml>' 로 흐름만 검증하세요"
            ),
            type = ErrorType.VALIDATION
        )
    }

    private fun handleAbortError(error: PipelineAbortedException): ErrorInfo {
        return ErrorInfo(
            title = "🛑 Pipeline Aborted",
            message = error.message ?: "Pipeline was aborted at stage ${error.stageId}",
            suggestions = listOf(
                "Check the condition that triggered the abort",
                "Review stage '${error.stageId}' output and metadata",
                "Adjust condition expressions if abort was unexpected",
                "Set failureStrategy to CONTINUE if stage is optional"
            ),
            type = ErrorType.ABORT
        )
    }

    private fun handleGenericError(error: Throwable): ErrorInfo {
        return ErrorInfo(
            title = "⚠️ Unexpected Error",
            message = error.message ?: error::class.simpleName ?: "Unknown error",
            suggestions = listOf(
                "Run with --debug flag for detailed stack trace",
                "Check the logs at the file specified in logging.file",
                "Verify your configuration is valid",
                "Report this issue if it persists: https://github.com/yourusername/cotor/issues"
            ),
            type = ErrorType.UNKNOWN
        )
    }
}

/**
 * Error information with suggestions
 */
data class ErrorInfo(
    val title: String,
    val message: String,
    val suggestions: List<String>,
    val type: ErrorType
)

/**
 * Error types for categorization
 */
enum class ErrorType {
    TIMEOUT,
    NOT_FOUND,
    PERMISSION,
    EXECUTION,
    VALIDATION,
    ABORT,
    UNKNOWN
}
