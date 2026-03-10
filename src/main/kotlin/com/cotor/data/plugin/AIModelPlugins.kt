package com.cotor.data.plugin

import com.cotor.data.process.ProcessManager
import com.cotor.model.*

/**
 * Claude AI Plugin (Anthropic)
 * Executes: claude --dangerously-skip-permissions --print <prompt>
 */
class ClaudePlugin : AgentPlugin {
    override val metadata = AgentMetadata(
        name = "claude",
        version = "1.0.0",
        description = "Claude AI by Anthropic for code generation and analysis",
        author = "Cotor Team",
        supportedFormats = listOf(DataFormat.JSON, DataFormat.TEXT)
    )

    override val parameterSchema = AgentParameterSchema(
        parameters = listOf(
            AgentParameter(
                name = "model",
                type = ParameterType.STRING,
                required = false,
                description = "The Claude model to use.",
                defaultValue = "claude-2"
            ),
            AgentParameter(
                name = "temperature",
                type = ParameterType.NUMBER,
                required = false,
                description = "The temperature to use for the model.",
                defaultValue = "0.7"
            )
        )
    )

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): PluginExecutionOutput {
        val prompt = context.input ?: throw IllegalArgumentException("Input prompt is required")

        val model = context.parameters.getOrDefault("model", "claude-2")

        // Execute Claude CLI with auto-approval (skip all permission prompts)
        // NOTE: `claude` CLI does not consistently support `--temperature` across versions,
        // so we avoid passing it here for compatibility.
        val command = mutableListOf("claude", "--dangerously-skip-permissions", "--print", prompt)
        if (model.isNotBlank()) {
            command.add("--model")
            command.add(model)
        }

        val result = processManager.executeProcess(
            command = command,
            input = null,
            environment = context.environment,
            timeout = context.timeout,
            workingDirectory = context.workingDirectory
        )

        if (!result.isSuccess) {
            throw AgentExecutionException("Claude execution failed: ${result.stderr}")
        }

        return PluginExecutionOutput(result.stdout, result.processId)
    }

    override fun validateInput(input: String?): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult.Failure(listOf("Input prompt is required for Claude"))
        }
        return ValidationResult.Success
    }
}

/**
 * Codex Plugin (OpenAI Code Interpreter)
 * Executes: codex --dangerously-bypass-approvals-and-sandbox <prompt>
 */
class CodexPlugin : AgentPlugin {
    override val metadata = AgentMetadata(
        name = "codex",
        version = "1.0.0",
        description = "Codex AI for code generation",
        author = "Cotor Team",
        supportedFormats = listOf(DataFormat.JSON, DataFormat.TEXT)
    )

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): PluginExecutionOutput {
        val prompt = context.input ?: throw IllegalArgumentException("Input prompt is required")

        // Codex writes its last assistant message to a file more reliably than stdout
        // when the CLI emits extra progress/logging lines, so we preserve that path.
        val outputFile = kotlin.io.path.createTempFile("cotor-codex-", ".txt")

        // Execute Codex in non-interactive mode and write only the final assistant message to file.
        val command = listOf(
            "codex", "exec",
            "--skip-git-repo-check",
            "--full-auto",
            "--color", "never",
            "--output-last-message", outputFile.toString(),
            prompt
        )

        val result = processManager.executeProcess(
            command = command,
            input = null,
            environment = context.environment,
            timeout = context.timeout,
            workingDirectory = context.workingDirectory
        )

        if (!result.isSuccess) {
            throw AgentExecutionException("Codex execution failed: ${result.stderr.ifBlank { result.stdout }}")
        }

        // Prefer the captured final assistant message and fall back to stdout only when
        // the file is empty, which keeps the plugin resilient across CLI versions.
        val finalText = java.nio.file.Files.readString(outputFile).trim()
        java.nio.file.Files.deleteIfExists(outputFile)

        return PluginExecutionOutput(
            output = if (finalText.isNotBlank()) finalText else result.stdout,
            processId = result.processId
        )
    }

    override fun validateInput(input: String?): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult.Failure(listOf("Input prompt is required for Codex"))
        }
        return ValidationResult.Success
    }
}

/**
 * GitHub Copilot Plugin
 * Executes: copilot -p <prompt> --allow-all-tools
 * Note: Copilot doesn't support full auto-approval, uses session-based silent auth
 */
class CopilotPlugin : AgentPlugin {
    override val metadata = AgentMetadata(
        name = "copilot",
        version = "1.0.0",
        description = "GitHub Copilot for code suggestions",
        author = "Cotor Team",
        supportedFormats = listOf(DataFormat.TEXT)
    )

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): PluginExecutionOutput {
        val prompt = context.input ?: throw IllegalArgumentException("Input prompt is required")

        // Copilot's session model is quieter than the other CLIs, so this wrapper simply
        // forwards the prompt and trusts the pre-authenticated CLI state on the machine.
        // Note: Full auto-approval not supported, requires pre-authenticated session
        val command = listOf("copilot", "-p", prompt, "--allow-all-tools")

        val result = processManager.executeProcess(
            command = command,
            input = null,
            environment = context.environment,
            timeout = context.timeout,
            workingDirectory = context.workingDirectory
        )

        if (!result.isSuccess) {
            throw AgentExecutionException("GitHub Copilot execution failed: ${result.stderr}")
        }

        return PluginExecutionOutput(result.stdout, result.processId)
    }

    override fun validateInput(input: String?): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult.Failure(listOf("Input prompt is required for Copilot"))
        }
        return ValidationResult.Success
    }
}

/**
 * Google Gemini Plugin
 * Executes: gemini --yolo <prompt>
 * Uses alwaysAllow whitelist for auto-approval
 */
class GeminiPlugin : AgentPlugin {
    override val metadata = AgentMetadata(
        name = "gemini",
        version = "1.0.0",
        description = "Google Gemini for code generation and analysis",
        author = "Cotor Team",
        supportedFormats = listOf(DataFormat.JSON, DataFormat.TEXT)
    )

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): PluginExecutionOutput {
        val prompt = context.input ?: throw IllegalArgumentException("Input prompt is required")

        // Gemini exposes a single flag for broad tool approval, so the wrapper remains
        // intentionally thin and lets cwd/env carry the worktree isolation.
        // --yolo flag enables alwaysAllow mode for all tools
        val command = listOf("gemini", "--yolo", prompt)

        val result = processManager.executeProcess(
            command = command,
            input = null,
            environment = context.environment,
            timeout = context.timeout,
            workingDirectory = context.workingDirectory
        )

        if (!result.isSuccess) {
            throw AgentExecutionException("Gemini execution failed: ${result.stderr}")
        }

        return PluginExecutionOutput(result.stdout, result.processId)
    }

    override fun validateInput(input: String?): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult.Failure(listOf("Input prompt is required for Gemini"))
        }
        return ValidationResult.Success
    }
}

/**
 * Cursor AI Plugin
 * Executes: cursor-cli generate --auto-run <prompt>
 * Uses Auto-Run mode with Denylist for dangerous commands
 */
class CursorPlugin : AgentPlugin {
    override val metadata = AgentMetadata(
        name = "cursor",
        version = "1.0.0",
        description = "Cursor AI for intelligent code editing",
        author = "Cotor Team",
        supportedFormats = listOf(DataFormat.TEXT)
    )

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): PluginExecutionOutput {
        val prompt = context.input ?: throw IllegalArgumentException("Input prompt is required")

        // Cursor's CLI is another child-process-based integration, so its output path
        // mirrors the other local tools and captures the pid for port inspection.
        // Uses Denylist approach: auto-runs everything except dangerous commands (rm, etc)
        val command = listOf("cursor-cli", "generate", "--auto-run", prompt)

        val result = processManager.executeProcess(
            command = command,
            input = null,
            environment = context.environment,
            timeout = context.timeout,
            workingDirectory = context.workingDirectory
        )

        if (!result.isSuccess) {
            throw AgentExecutionException("Cursor execution failed: ${result.stderr}")
        }

        return PluginExecutionOutput(result.stdout, result.processId)
    }

    override fun validateInput(input: String?): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult.Failure(listOf("Input prompt is required for Cursor"))
        }
        return ValidationResult.Success
    }
}

/**
 * OpenCode Agent Plugin
 * Executes: opencode generate <prompt>
 * Default permission: "allow" for all methods (bash, file operations, etc)
 */
class OpenCodePlugin : AgentPlugin {
    override val metadata = AgentMetadata(
        name = "opencode",
        version = "1.0.0",
        description = "OpenCode agent for open-source code generation",
        author = "Cotor Team",
        supportedFormats = listOf(DataFormat.JSON, DataFormat.TEXT)
    )

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): PluginExecutionOutput {
        val prompt = context.input ?: throw IllegalArgumentException("Input prompt is required")

        // OpenCode is treated as a standard CLI integration. The plugin does not try
        // to interpret stdout beyond surfacing it back to the orchestration layer.
        // Default permission is "allow" for all methods (configured in opencode.json)
        // Example config: { "permission": { "bash": "allow", "file": "allow" } }
        val command = listOf("opencode", "generate", prompt)

        val result = processManager.executeProcess(
            command = command,
            input = null,
            environment = context.environment,
            timeout = context.timeout,
            workingDirectory = context.workingDirectory
        )

        if (!result.isSuccess) {
            throw ProcessExecutionException(
                message = "OpenCode execution failed",
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr
            )
        }

        return PluginExecutionOutput(result.stdout, result.processId)
    }

    override fun validateInput(input: String?): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult.Failure(listOf("Input prompt is required for OpenCode"))
        }
        return ValidationResult.Success
    }
}
