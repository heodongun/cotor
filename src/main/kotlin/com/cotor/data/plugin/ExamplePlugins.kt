package com.cotor.data.plugin

/**
 * File overview for NaturalLanguageProcessorPlugin.
 *
 * This file belongs to the plugin integration layer that adapts external agent CLIs into Cotor.
 * It groups declarations around example plugins so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


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
    ): PluginExecutionOutput {
        // These example plugins intentionally show the canonical pattern: build argv,
        // pass through stdin/env/timeout/cwd, then map ProcessResult into plugin output.
        val command = listOf(
            "python3",
            "/path/to/nlp_tool.py",
            "--mode",
            context.parameters["mode"] ?: "analyze"
        )

        val result = processManager.executeProcess(
            command = command,
            input = context.input,
            environment = context.environment,
            timeout = context.timeout,
            workingDirectory = context.workingDirectory,
            onStart = context.onProcessStarted
        )

        if (!result.isSuccess) {
            throw AgentExecutionException("NLP processing failed: ${result.stderr}")
        }

        return PluginExecutionOutput(result.stdout, result.processId)
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
    ): PluginExecutionOutput {
        // Required parameters are checked here instead of in validateInput so the example
        // mirrors how many real integrations need config validation as well as prompt validation.
        val language = context.parameters["language"]
            ?: throw IllegalArgumentException("Parameter 'language' is required")

        val framework = context.parameters["framework"]
            ?: throw IllegalArgumentException("Parameter 'framework' is required")

        val command = listOf(
            "node",
            "/path/to/code_generator.js",
            "--language",
            language,
            "--framework",
            framework
        )

        val result = processManager.executeProcess(
            command = command,
            input = context.input,
            environment = context.environment,
            timeout = context.timeout,
            workingDirectory = context.workingDirectory,
            onStart = context.onProcessStarted
        )

        if (!result.isSuccess) {
            throw AgentExecutionException("Code generation failed: ${result.stderr}")
        }

        return PluginExecutionOutput(result.stdout, result.processId)
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
    ): PluginExecutionOutput {
        // Echo remains process-free on purpose so tests can exercise the orchestration
        // stack without spawning any child tools.
        return PluginExecutionOutput(context.input ?: "")
    }
}
