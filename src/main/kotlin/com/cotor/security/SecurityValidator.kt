package com.cotor.security

import com.cotor.model.AgentConfig
import com.cotor.model.SecurityConfig
import com.cotor.model.SecurityException
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isSymbolicLink

/**
 * Interface for security validation
 */
interface SecurityValidator {
    /**
     * Validate agent configuration
     * @param agent AgentConfig to validate
     * @throws SecurityException if validation fails
     */
    fun validate(agent: AgentConfig)

    /**
     * Validate command and arguments
     * @param command Command list to validate
     * @throws SecurityException if validation fails
     */
    fun validateCommand(command: List<String>)

    /**
     * Validate file path
     * @param path Path to validate
     * @throws SecurityException if validation fails
     */
    fun validatePath(path: Path)
}

/**
 * Default implementation of security validator
 */
class DefaultSecurityValidator(
    private val config: SecurityConfig,
    private val logger: Logger
) : SecurityValidator {

    override fun validate(agent: AgentConfig) {
        // Validate execution path
        agent.executablePath?.let { validatePath(it) }

        // Validate environment variables
        validateEnvironment(agent.environment)

        // Validate parameters
        validateParameters(agent.parameters)
    }

    override fun validateCommand(command: List<String>) {
        if (command.isEmpty()) {
            throw SecurityException("Empty command is not allowed")
        }

        val executable = command.first()

        // Whitelist validation
        if (config.useWhitelist && !config.allowedExecutables.contains(executable)) {
            throw SecurityException("Executable not in whitelist: $executable")
        }

        // Dangerous commands check
        val dangerousCommands = listOf("rm", "del", "format", "dd", "mkfs")
        if (dangerousCommands.any { executable.endsWith(it) }) {
            logger.warn("Potentially dangerous command detected: $executable")
        }

        // Command injection pattern check
        command.forEach { arg ->
            if (containsInjectionPattern(arg)) {
                throw SecurityException("Command injection pattern detected: $arg")
            }
        }

        // Command length check
        val commandString = command.joinToString(" ")
        if (commandString.length > config.maxCommandLength) {
            throw SecurityException("Command length exceeds maximum: ${commandString.length} > ${config.maxCommandLength}")
        }
    }

    override fun validatePath(path: Path) {
        val absolutePath = path.toAbsolutePath().normalize()

        // Check if path is in allowed directories
        if (config.enablePathValidation) {
            val isAllowed = config.allowedDirectories.any { allowedDir ->
                absolutePath.startsWith(allowedDir.toAbsolutePath().normalize())
            }

            if (!isAllowed) {
                throw SecurityException("Path not in allowed directories: $absolutePath")
            }
        }

        // Validate symbolic links
        if (path.isSymbolicLink()) {
            val target = Files.readSymbolicLink(path)
            validatePath(target)
        }
    }

    private fun validateEnvironment(environment: Map<String, String>) {
        val dangerousVars = listOf("LD_PRELOAD", "LD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES")

        environment.keys.forEach { key ->
            if (dangerousVars.contains(key)) {
                throw SecurityException("Dangerous environment variable: $key")
            }
        }
    }

    private fun validateParameters(parameters: Map<String, String>) {
        parameters.values.forEach { value ->
            if (containsInjectionPattern(value)) {
                throw SecurityException("Injection pattern in parameter: $value")
            }
        }
    }

    private fun containsInjectionPattern(input: String): Boolean {
        val injectionPatterns = listOf(
            ";", "&&", "||", "|", "`", "$(",
            "../", "..\\", "<", ">", "\n", "\r"
        )

        return injectionPatterns.any { pattern ->
            input.contains(pattern)
        }
    }
}
