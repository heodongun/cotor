package com.cotor.data.plugin

import com.cotor.data.process.ProcessManager
import com.cotor.model.*

/**
 * Example NLP processor plugin
 */
class NaturalLanguageProcessorPlugin : AgentPlugin {

    override val metadata = AgentMetadata(
        name = "nlp-processor",
        version = "1.0.0",
        description = "Natural language processing agent",
        author = "Cotor Team",
        supportedFormats = listOf(DataFormat.JSON, DataFormat.TEXT)
    )

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): String {
        val command = listOf(
            "python3",
            "/path/to/nlp_tool.py",
            "--mode", context.parameters["mode"] ?: "analyze"
        )

        val result = processManager.executeProcess(
            command = command,
            input = context.input,
            environment = context.environment,
            timeout = context.timeout
        )

        if (!result.isSuccess) {
            throw AgentExecutionException("NLP processing failed: ${result.stderr}")
        }

        return result.stdout
    }

    override fun validateInput(input: String?): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult.Failure(listOf("Input text is required"))
        }

        if (input.length > 10000) {
            return ValidationResult.Failure(listOf("Input text exceeds maximum length of 10000 characters"))
        }

        return ValidationResult.Success
    }
}

/**
 * Example code generator plugin
 */
class CodeGeneratorPlugin : AgentPlugin {

    override val metadata = AgentMetadata(
        name = "code-generator",
        version = "1.0.0",
        description = "AI-powered code generation agent",
        author = "Cotor Team",
        supportedFormats = listOf(DataFormat.JSON),
        requiredParameters = listOf("language", "framework")
    )

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): String {
        val language = context.parameters["language"]
            ?: throw IllegalArgumentException("Parameter 'language' is required")

        val framework = context.parameters["framework"]
            ?: throw IllegalArgumentException("Parameter 'framework' is required")

        val command = listOf(
            "node",
            "/path/to/code_generator.js",
            "--language", language,
            "--framework", framework
        )

        val result = processManager.executeProcess(
            command = command,
            input = context.input,
            environment = context.environment,
            timeout = context.timeout
        )

        if (!result.isSuccess) {
            throw AgentExecutionException("Code generation failed: ${result.stderr}")
        }

        return result.stdout
    }

    override fun validateInput(input: String?): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult.Failure(listOf("Input specification is required"))
        }

        return ValidationResult.Success
    }
}

/**
 * Simple echo plugin for testing
 */
class EchoPlugin : AgentPlugin {

    override val metadata = AgentMetadata(
        name = "echo",
        version = "1.0.0",
        description = "Simple echo agent for testing",
        author = "Cotor Team",
        supportedFormats = listOf(DataFormat.TEXT, DataFormat.JSON)
    )

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): String {
        return context.input ?: ""
    }
}
