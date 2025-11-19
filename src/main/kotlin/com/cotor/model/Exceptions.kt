package com.cotor.model

/**
 * Base exception for all Cotor-related errors
 */
sealed class CotorException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Exception thrown when configuration is invalid or cannot be loaded
 */
class ConfigurationException(message: String, cause: Throwable? = null) : CotorException(message, cause)

/**
 * Exception thrown when a plugin fails to load
 */
class PluginLoadException(message: String, cause: Throwable? = null) : CotorException(message, cause)

/**
 * Exception thrown when agent execution fails
 */
class AgentExecutionException(message: String, cause: Throwable? = null) : CotorException(message, cause)

/**
 * Exception thrown when security validation fails
 */
class SecurityException(message: String, cause: Throwable? = null) : CotorException(message, cause)

/**
 * Exception thrown when pipeline execution fails
 */
class PipelineException(message: String, cause: Throwable? = null) : CotorException(message, cause)

/**
 * Exception thrown when a pipeline is intentionally aborted by a stage
 */
class PipelineAbortedException(
    val stageId: String,
    message: String,
    cause: Throwable? = null
) : CotorException(message, cause)

/**
 * Exception thrown when validation fails
 */
class ValidationException(message: String, val errors: List<String>) : CotorException(message)
