package com.cotor.model

import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.time.Instant

/**
 * Core configuration for the Cotor system
 */
@Serializable
data class CotorConfig(
    val version: String = "1.0",
    val agents: List<AgentConfig> = emptyList(),
    val pipelines: List<Pipeline> = emptyList(),
    val security: SecurityConfig = SecurityConfig(),
    val logging: LoggingConfig = LoggingConfig(),
    val performance: PerformanceConfig = PerformanceConfig()
)

/**
 * Configuration for an individual agent
 */
@Serializable
data class AgentConfig(
    val name: String,
    val pluginClass: String,
    @Serializable(with = PathSerializer::class)
    val executablePath: Path? = null,
    val parameters: Map<String, String> = emptyMap(),
    val environment: Map<String, String> = emptyMap(),
    val timeout: Long = 30000, // 30 seconds
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val tags: List<String> = emptyList(),
    val inputFormat: DataFormat = DataFormat.JSON,
    val outputFormat: DataFormat = DataFormat.JSON
)

/**
 * Pipeline configuration with multiple stages
 */
@Serializable
data class Pipeline(
    val name: String,
    val description: String = "",
    val executionMode: ExecutionMode = ExecutionMode.SEQUENTIAL,
    val stages: List<PipelineStage> = emptyList(),
    val failureStrategy: FailureStrategy = FailureStrategy.ABORT
)

/**
 * Individual stage in a pipeline
 */
@Serializable
data class PipelineStage(
    val id: String,
    val agent: AgentReference,
    val input: String? = null,
    val dependencies: List<String> = emptyList(),
    val failureStrategy: FailureStrategy = FailureStrategy.ABORT,
    val optional: Boolean = false,
    val recovery: RecoveryConfig? = null,
    val validation: StageValidationConfig? = null
)

/**
 * Reference to an agent by name
 */
@Serializable
data class AgentReference(
    val name: String
)

/**
 * Execution mode for pipelines
 */
@Serializable
enum class ExecutionMode {
    SEQUENTIAL,  // Sequential execution
    PARALLEL,    // Parallel execution
    DAG          // Dependency graph-based execution
}

/**
 * Failure handling strategy
 */
@Serializable
enum class FailureStrategy {
    ABORT,       // Abort immediately
    CONTINUE,    // Continue execution
    RETRY,       // Retry failed operation
    FALLBACK     // Execute fallback operation
}

/**
 * Recovery strategy for a pipeline stage
 */
@Serializable
enum class RecoveryStrategy {
    RETRY,
    FALLBACK,
    RETRY_THEN_FALLBACK,
    SKIP,
    ABORT
}

/**
 * Recovery configuration applied to a stage
 */
@Serializable
data class RecoveryConfig(
    val maxRetries: Int = 3,
    val retryDelayMs: Long = 1000,
    val backoffMultiplier: Double = 2.0,
    val fallbackAgents: List<String> = emptyList(),
    val strategy: RecoveryStrategy = RecoveryStrategy.RETRY,
    val retryableErrors: List<String> = listOf("timeout", "connection", "api", "rate_limit", "validation")
)

/**
 * Custom validator configuration
 */
@Serializable
data class CustomValidatorConfig(
    val type: String,
    val options: Map<String, String> = emptyMap()
)

/**
 * Output validation configuration for a stage
 */
@Serializable
data class StageValidationConfig(
    val requiresFile: String? = null,
    val requiresCodeBlock: Boolean = false,
    val minLength: Int = 0,
    val maxLength: Int = Int.MAX_VALUE,
    val requiredKeywords: List<String> = emptyList(),
    val forbiddenKeywords: List<String> = emptyList(),
    val minQualityScore: Double = 0.0,
    val customValidators: List<CustomValidatorConfig> = emptyList()
)

/**
 * Retry policy configuration
 */
@Serializable
data class RetryPolicy(
    val maxRetries: Int = 3,
    val retryDelay: Long = 1000, // 1 second
    val backoffMultiplier: Double = 2.0
)

/**
 * Supported data formats
 */
@Serializable
enum class DataFormat {
    JSON,
    CSV,
    TEXT,
    PROTOBUF,
    XML
}

/**
 * Security configuration
 */
@Serializable
data class SecurityConfig(
    val useWhitelist: Boolean = true,
    val allowedExecutables: Set<String> = emptySet(),
    @Serializable(with = PathListSerializer::class)
    val allowedDirectories: List<Path> = emptyList(),
    val maxCommandLength: Int = 1000,
    val enablePathValidation: Boolean = true
)

/**
 * Logging configuration
 */
@Serializable
data class LoggingConfig(
    val level: String = "INFO",
    val file: String = "cotor.log",
    val maxFileSize: String = "10MB",
    val maxHistory: Int = 7,
    val format: String = "json"
)

/**
 * Performance configuration
 */
@Serializable
data class PerformanceConfig(
    val maxConcurrentAgents: Int = 10,
    val coroutinePoolSize: Int = Runtime.getRuntime().availableProcessors(),
    val memoryThresholdMB: Int = 1024
)

/**
 * Result from agent execution
 */
data class AgentResult(
    val agentName: String,
    val isSuccess: Boolean,
    val output: String?,
    val error: String?,
    val duration: Long,
    val metadata: Map<String, String>
)

/**
 * Aggregated results from multiple agents
 */
data class AggregatedResult(
    val totalAgents: Int,
    val successCount: Int,
    val failureCount: Int,
    val totalDuration: Long,
    val results: List<AgentResult>,
    val aggregatedOutput: String,
    val timestamp: Instant
)

/**
 * Result from process execution
 */
data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val isSuccess: Boolean
)

/**
 * Pipeline execution status
 */
enum class PipelineStatus {
    NOT_FOUND,
    RUNNING,
    COMPLETED,
    CANCELLED,
    UNKNOWN
}

/**
 * Agent metadata
 */
data class AgentMetadata(
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val supportedFormats: List<DataFormat>,
    val requiredParameters: List<String> = emptyList()
)

/**
 * Execution context for agents
 */
data class ExecutionContext(
    val agentName: String,
    val input: String?,
    val parameters: Map<String, String>,
    val environment: Map<String, String>,
    val timeout: Long,
    val pipelineContext: PipelineContext? = null,
    val currentStageId: String? = null
)

/**
 * Metadata describing the current execution target
 */
data class AgentExecutionMetadata(
    val pipelineContext: PipelineContext? = null,
    val stageId: String? = null
)

/**
 * Shared pipeline context accessible to all stages
 */
data class PipelineContext(
    val pipelineId: String,
    val pipelineName: String,
    val totalStages: Int,
    val stageResults: MutableMap<String, AgentResult> = java.util.concurrent.ConcurrentHashMap(),
    val sharedState: MutableMap<String, Any> = java.util.concurrent.ConcurrentHashMap(),
    val metadata: MutableMap<String, Any> = java.util.concurrent.ConcurrentHashMap(),
    val startTime: Long = System.currentTimeMillis()
) {
    @Volatile
    var currentStageIndex: Int = 0

    fun addStageResult(stageId: String, result: AgentResult) {
        stageResults[stageId] = result
    }

    fun getStageResult(stageId: String): AgentResult? = stageResults[stageId]

    fun getStageOutput(stageId: String): String? = stageResults[stageId]?.output

    fun getAllOutputs(): String {
        return stageResults.values
            .mapNotNull { it.output }
            .joinToString("\n\n---\n\n")
    }

    fun getSuccessfulOutputs(): String {
        return stageResults.values
            .filter { it.isSuccess }
            .mapNotNull { it.output }
            .joinToString("\n\n---\n\n")
    }

    fun elapsedTime(): Long = System.currentTimeMillis() - startTime
}

/**
 * Validation result
 */
sealed class ValidationResult {
    object Success : ValidationResult()
    data class Failure(val errors: List<String>) : ValidationResult()
}

/**
 * Memory usage status
 */
enum class MemoryStatus {
    NORMAL, HIGH, CRITICAL
}

/**
 * Agent execution metrics
 */
data class AgentMetrics(
    val agentName: String,
    val totalExecutions: Int,
    val successCount: Int,
    val failureCount: Int,
    val averageDuration: Double,
    val minDuration: Long,
    val maxDuration: Long
) {
    val successRate: Double
        get() = if (totalExecutions > 0) successCount.toDouble() / totalExecutions * 100 else 0.0
}
