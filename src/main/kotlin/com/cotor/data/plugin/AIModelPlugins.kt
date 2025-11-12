package com.cotor.data.plugin

import com.cotor.data.process.ProcessManager
import com.cotor.model.*

/**
 * Claude AI Plugin (Anthropic)
 * Executes: claude --print <prompt>
 */
class ClaudePlugin : AgentPlugin {
    override val metadata = AgentMetadata(
        name = "claude",
        version = "1.0.0",
        description = "Claude AI by Anthropic for code generation and analysis",
        author = "Cotor Team",
        supportedFormats = listOf(DataFormat.JSON, DataFormat.TEXT)
    )

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): String {
        val prompt = context.input ?: throw IllegalArgumentException("Input prompt is required")
        
        // Execute Claude CLI in non-interactive mode with --print
        val command = listOf("claude", "--print", prompt)
        
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
 * OpenAI Codex/GPT Plugin
 * Executes: openai <prompt>
 */
class CodexPlugin : AgentPlugin {
    override val metadata = AgentMetadata(
        name = "codex",
        version = "1.0.0",
        description = "OpenAI Codex/GPT for code generation",
        author = "Cotor Team",
        supportedFormats = listOf(DataFormat.JSON, DataFormat.TEXT)
    )

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): String {
        val prompt = context.input ?: throw IllegalArgumentException("Input prompt is required")
        val model = context.parameters["model"] ?: "gpt-4"
        
        // Execute OpenAI CLI
        val command = listOf("openai", "chat", "--model", model, "--message", prompt)
        
        val result = processManager.executeProcess(
            command = command,
            input = null,
            environment = context.environment,
            timeout = context.timeout
        )
        
        if (!result.isSuccess) {
            throw AgentExecutionException("OpenAI execution failed: ${result.stderr}")
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
        
        // Execute GitHub Copilot CLI in non-interactive mode
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
}

/**
 * Google Gemini Plugin
 * Executes: gemini <prompt>
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
        
        // Execute Gemini CLI with prompt as positional argument
        // Use --yolo to auto-approve actions
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
}

/**
 * Cursor AI Plugin
 * Executes: cursor-cli <prompt>
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
        
        // Execute Cursor CLI
        val command = listOf("cursor-cli", "generate", prompt)
        
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
}

/**
 * OpenCode Agent Plugin
 * Executes: opencode <prompt>
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
}
