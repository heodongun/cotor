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
    val failureStrategy: FailureStrategy = FailureStrategy.ABORT
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
    val timeout: Long
)

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
