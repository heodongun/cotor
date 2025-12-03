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
    ): String {
        val prompt = context.input ?: throw IllegalArgumentException("Input prompt is required")

        val model = context.parameters.getOrDefault("model", "claude-2")
        val temperature = context.parameters.getOrDefault("temperature", "0.7")
        
        // Execute Claude CLI with auto-approval (skip all permission prompts)
        val command = mutableListOf("claude", "--dangerously-skip-permissions", "--print", prompt)
        command.add("--model")
        command.add(model)
        command.add("--temperature")
        command.add(temperature)
        
        val result = processManager.executeProcess(
            command = command,
            input = null,
            environment = context.environment,
            timeout = context.timeout
        )
        
        if (!result.isSuccess) {
            throw AgentExecutionException("Claude execution failed: ${result.stderr}")
        }
        
        return result.stdout
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
    ): String {
        val prompt = context.input ?: throw IllegalArgumentException("Input prompt is required")
        
        // Execute Codex CLI with full auto-approval (bypass all approvals and sandbox)
        // Codex doesn't use 'exec' subcommand, just pass the prompt directly
        val command = listOf("codex", "--dangerously-bypass-approvals-and-sandbox", prompt)
        
        val result = processManager.executeProcess(
            command = command,
            input = null,
            environment = context.environment,
            timeout = context.timeout
        )
        
        if (!result.isSuccess) {
            throw AgentExecutionException("Codex execution failed: ${result.stderr}")
        }
        
        return result.stdout
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
    ): String {
        val prompt = context.input ?: throw IllegalArgumentException("Input prompt is required")
        
        // Execute GitHub Copilot CLI with all tools allowed
        // Note: Full auto-approval not supported, requires pre-authenticated session
        val command = listOf("copilot", "-p", prompt, "--allow-all-tools")
        
        val result = processManager.executeProcess(
            command = command,
            input = null,
            environment = context.environment,
            timeout = context.timeout
        )
        
        if (!result.isSuccess) {
            throw AgentExecutionException("GitHub Copilot execution failed: ${result.stderr}")
        }
        
        return result.stdout
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
    ): String {
        val prompt = context.input ?: throw IllegalArgumentException("Input prompt is required")
        
        // Execute Gemini CLI with auto-approval
        // --yolo flag enables alwaysAllow mode for all tools
        val command = listOf("gemini", "--yolo", prompt)
        
        val result = processManager.executeProcess(
            command = command,
            input = null,
            environment = context.environment,
            timeout = context.timeout
        )
        
        if (!result.isSuccess) {
            throw AgentExecutionException("Gemini execution failed: ${result.stderr}")
        }
        
        return result.stdout
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
    ): String {
        val prompt = context.input ?: throw IllegalArgumentException("Input prompt is required")
        
        // Execute Cursor CLI with Auto-Run mode
        // Uses Denylist approach: auto-runs everything except dangerous commands (rm, etc)
        val command = listOf("cursor-cli", "generate", "--auto-run", prompt)
        
        val result = processManager.executeProcess(
            command = command,
            input = null,
            environment = context.environment,
            timeout = context.timeout
        )
        
        if (!result.isSuccess) {
            throw AgentExecutionException("Cursor execution failed: ${result.stderr}")
        }
        
        return result.stdout
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
    ): String {
        val prompt = context.input ?: throw IllegalArgumentException("Input prompt is required")
        
        // Execute OpenCode CLI
        // Default permission is "allow" for all methods (configured in opencode.json)
        // Example config: { "permission": { "bash": "allow", "file": "allow" } }
        val command = listOf("opencode", "generate", prompt)
        
        val result = processManager.executeProcess(
            command = command,
            input = null,
            environment = context.environment,
            timeout = context.timeout
        )
        
        if (!result.isSuccess) {
            throw AgentExecutionException("OpenCode execution failed: ${result.stderr}")
        }
        
        return result.stdout
    }

    override fun validateInput(input: String?): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult.Failure(listOf("Input prompt is required for OpenCode"))
        }
        return ValidationResult.Success
    }
}
