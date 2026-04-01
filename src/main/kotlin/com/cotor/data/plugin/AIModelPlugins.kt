package com.cotor.data.plugin

/**
 * File overview for ClaudePlugin.
 *
 * This file belongs to the plugin integration layer that adapts external agent CLIs into Cotor.
 * It groups declarations around a i model plugins so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import com.cotor.data.process.ProcessManager
import com.cotor.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
                defaultValue = "claude-sonnet-4-20250514"
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

        val model = context.parameters.getOrDefault("model", "claude-sonnet-4-20250514")

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
            workingDirectory = context.workingDirectory,
            onStart = context.onProcessStarted
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
        val reasoningEffort = normalizeCodexReasoningEffort(
            context.parameters["model_reasoning_effort"] ?: context.parameters["reasoning_effort"]
        )
        val model = CodexDefaults.normalizeModel(
            context.parameters["model"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: resolveConfiguredCodexModel(context.environment)
        )
        val isolateCodexHome = shouldIsolateCodexMcpConfig(context)

        // Codex writes its last assistant message to a file more reliably than stdout
        // when the CLI emits extra progress/logging lines, so we preserve that path.
        val outputFile = kotlin.io.path.createTempFile("cotor-codex-", ".txt")
        val isolatedCodexHome = if (isolateCodexHome) {
            kotlin.io.path.createTempDirectory("cotor-codex-home-").also {
                prepareIsolatedCodexHome(it, context.environment)
            }
        } else {
            null
        }

        try {
            // Execute Codex in non-interactive mode and write only the final assistant message to file.
            val command = mutableListOf(
                "codex", "exec",
                "--skip-git-repo-check",
                "--full-auto",
                "--color", "never",
                "-c", """model_reasoning_effort="$reasoningEffort"""",
                "--output-last-message", outputFile.toString()
            )
            if (isolateCodexHome) {
                // Company/runtime task execution should not inherit flaky or expired MCP login state
                // from the user's interactive Codex setup.
                command += listOf("-c", "mcp_servers={}")
            }
            if (model != null) {
                command += listOf("--model", model)
            }
            command += prompt

            val result = processManager.executeProcess(
                command = command,
                input = null,
                environment = if (isolatedCodexHome != null) {
                    context.environment + mapOf("CODEX_HOME" to isolatedCodexHome.toString())
                } else {
                    context.environment
                },
                timeout = context.timeout,
                workingDirectory = context.workingDirectory,
                onStart = context.onProcessStarted
            )

            // Prefer the captured final assistant message and fall back to stdout only when
            // the file is empty, which keeps the plugin resilient across CLI versions.
            val finalText = Files.readString(outputFile).trim()

            if (!result.isSuccess && finalText.isBlank()) {
                throw AgentExecutionException("Codex execution failed: ${result.stderr.ifBlank { result.stdout }}")
            }

            return PluginExecutionOutput(
                output = if (finalText.isNotBlank()) finalText else result.stdout,
                processId = result.processId
            )
        } finally {
            Files.deleteIfExists(outputFile)
            isolatedCodexHome?.toFile()?.deleteRecursively()
        }
    }

    override fun validateInput(input: String?): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult.Failure(listOf("Input prompt is required for Codex"))
        }
        return ValidationResult.Success
    }

    private fun normalizeCodexReasoningEffort(raw: String?): String =
        when (raw?.trim()?.lowercase()) {
            "none", "minimal", "low", "medium", "high" -> raw.trim().lowercase()
            "xhigh" -> "high"
            else -> "high"
        }

    private fun prepareIsolatedCodexHome(targetHome: Path, environment: Map<String, String>) {
        Files.createDirectories(targetHome)
        resolveCodexAuthSource(environment)
            ?.takeIf { Files.exists(it) }
            ?.let { source ->
                Files.copy(source, targetHome.resolve("auth.json"), StandardCopyOption.REPLACE_EXISTING)
            }
        Files.writeString(targetHome.resolve("config.toml"), buildIsolatedCodexConfig())
    }

    private fun resolveConfiguredCodexModel(environment: Map<String, String>): String? {
        val configPath = resolveCodexConfigPath(environment) ?: return null
        if (!Files.exists(configPath)) return null
        return CodexDefaults.normalizeModel(
            Files.readAllLines(configPath)
            .firstOrNull { line ->
                val trimmed = line.trim()
                trimmed.startsWith("model = ") && !trimmed.startsWith("model_reasoning_effort")
            }
            ?.substringAfter("=")
            ?.trim()
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() }
        )
    }

    private fun resolveCodexAuthSource(environment: Map<String, String>): Path? =
        resolveCodexHome(environment)?.resolve("auth.json")

    private fun resolveCodexConfigPath(environment: Map<String, String>): Path? =
        resolveCodexHome(environment)?.resolve("config.toml")

    private fun resolveCodexHome(environment: Map<String, String>): Path? {
        val explicitCodexHome = environment["CODEX_HOME"]
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("CODEX_HOME")?.takeIf { it.isNotBlank() }
        if (explicitCodexHome != null) {
            return Path.of(explicitCodexHome)
        }
        val home = environment["HOME"]
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("HOME")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home")?.takeIf { it.isNotBlank() }
        return home?.let { Path.of(it).resolve(".codex") }
    }

    private fun buildIsolatedCodexConfig(): String = """
        [features]
        rmcp_client = false
        multi_agent = false
        js_repl = false
        apps = false
        prevent_idle_sleep = false
    """.trimIndent() + "\n"

    private fun shouldIsolateCodexMcpConfig(context: ExecutionContext): Boolean =
        !context.taskId.isNullOrBlank()
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
            workingDirectory = context.workingDirectory,
            onStart = context.onProcessStarted
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
            workingDirectory = context.workingDirectory,
            onStart = context.onProcessStarted
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
            workingDirectory = context.workingDirectory,
            onStart = context.onProcessStarted
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
 * Executes: opencode run <prompt>
 * Default permission: "allow" for all methods (configured in opencode.json)
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
        // Default permission is "allow" for all methods (configured via opencode yolo mode).
        // Command: opencode run <prompt>
        val command = listOf("opencode", "run", prompt)

        val result = processManager.executeProcess(
            command = command,
            input = null,
            environment = context.environment,
            timeout = context.timeout,
            workingDirectory = context.workingDirectory,
            onStart = context.onProcessStarted
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
