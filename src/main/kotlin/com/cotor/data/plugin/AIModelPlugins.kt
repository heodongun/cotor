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
import com.cotor.model.OpenCodeDefaults
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
        val authMode = context.parameters["auth_mode"]?.trim()?.lowercase()
        val baseEnvironment = if (authMode == "oauth") {
            context.environment + mapOf("CODEX_HOME" to managedCodexOAuthHome(context.environment).toString())
        } else {
            context.environment
        }
        val reasoningEffort = normalizeCodexReasoningEffort(
            context.parameters["model_reasoning_effort"] ?: context.parameters["reasoning_effort"]
        )
        val model = CodexDefaults.normalizeModel(
            context.parameters["model"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: resolveConfiguredCodexModel(baseEnvironment)
        )
        val isolateCodexHome = shouldIsolateCodexMcpConfig(context)

        // Codex writes its last assistant message to a file more reliably than stdout
        // when the CLI emits extra progress/logging lines, so we preserve that path.
        val outputFile = kotlin.io.path.createTempFile("cotor-codex-", ".txt")
        val isolatedCodexHome = if (isolateCodexHome) {
            kotlin.io.path.createTempDirectory("cotor-codex-home-").also {
                prepareIsolatedCodexHome(it, baseEnvironment)
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
                    baseEnvironment + mapOf("CODEX_HOME" to isolatedCodexHome.toString())
                } else {
                    baseEnvironment
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

    private fun managedCodexOAuthHome(environment: Map<String, String>): Path {
        val explicit = environment["COTOR_CODEX_OAUTH_HOME"]?.trim()?.takeIf { it.isNotBlank() }
        if (explicit != null) {
            return Path.of(explicit)
        }
        val home = environment["HOME"]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home")
        return Path.of(home).resolve(".cotor").resolve("auth").resolve("codex-oauth")
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
 * Executes: opencode run --format json <prompt>
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
        val requestedModel = OpenCodeDefaults.normalizeModel(
            context.parameters["model"] ?: context.environment["OPENCODE_MODEL"]
        )

        var model = requestedModel
        var result = executeOpenCodeRun(
            prompt = prompt,
            model = model,
            context = context,
            processManager = processManager
        )
        val initialError = extractOpenCodeError(result)

        if (requestedModel != null && initialError?.contains("Model not found", ignoreCase = true) == true) {
            val fallbackModel = discoverFallbackOpenCodeModel(
                processManager = processManager,
                context = context,
                rejectedModel = requestedModel
            )
            if (fallbackModel != null) {
                model = fallbackModel
                result = executeOpenCodeRun(
                    prompt = prompt,
                    model = model,
                    context = context,
                    processManager = processManager
                )
            }
        }

        val finalError = extractOpenCodeError(result)
        if (!result.isSuccess || finalError != null) {
            throw ProcessExecutionException(
                message = "OpenCode execution failed",
                exitCode = result.exitCode,
                stdout = result.stdout.ifBlank { finalError ?: result.stdout },
                stderr = result.stderr.ifBlank { finalError ?: result.stderr }
            )
        }

        val parsedText = parseOpenCodeJsonOutput(result.stdout)
        return PluginExecutionOutput(parsedText, result.processId)
    }

    private suspend fun executeOpenCodeRun(
        prompt: String,
        model: String?,
        context: ExecutionContext,
        processManager: ProcessManager
    ): ProcessResult {
        // OpenCode run with --format json produces a structured JSON event stream
        // instead of launching an interactive TUI. Events include step_start, text,
        // step_finish, etc. We parse text events to extract the response content.
        // Default permission is "allow" for all methods (configured via opencode yolo mode).
        // Command: opencode run --model <model> --format json <prompt>
        val command = buildList {
            add("opencode")
            add("run")
            model?.let {
                add("--model")
                add(it)
            }
            add("--format")
            add("json")
            add(prompt)
        }

        return processManager.executeProcess(
            command = command,
            input = null,
            environment = context.environment,
            timeout = context.timeout,
            workingDirectory = context.workingDirectory,
            onStart = context.onProcessStarted
        )
    }

    private suspend fun discoverFallbackOpenCodeModel(
        processManager: ProcessManager,
        context: ExecutionContext,
        rejectedModel: String
    ): String? {
        val result = processManager.executeProcess(
            command = listOf("opencode", "models", "opencode"),
            input = null,
            environment = context.environment,
            timeout = minOf(context.timeout, 30_000),
            workingDirectory = context.workingDirectory,
            onStart = null
        )
        if (!result.isSuccess) return null
        val models = result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("opencode/") }
            .filter { it != rejectedModel }
            .toList()
        return models.firstOrNull { it.endsWith("-free") } ?: models.firstOrNull()
    }

    private fun extractOpenCodeError(result: ProcessResult): String? {
        val combined = listOf(result.stdout, result.stderr)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
        if (combined.isBlank()) return null
        Regex("Model not found: [^\\s\"]+")
            .find(combined)
            ?.value
            ?.let { return it }
        val json = Json { ignoreUnknownKeys = true }
        fun extract(element: JsonElement): String? = when (element) {
            is JsonArray -> element.firstNotNullOfOrNull(::extract)
            is JsonObject -> {
                if (element["type"]?.jsonPrimitive?.contentOrNull?.equals("error", ignoreCase = true) == true) {
                    element["error"]?.let(::extract)
                        ?: element["message"]?.jsonPrimitive?.contentOrNull
                } else {
                    element["data"]?.let(::extract)
                        ?: element["message"]?.jsonPrimitive?.contentOrNull
                }
            }
            is JsonPrimitive -> element.contentOrNull
            else -> null
        }
        runCatching { extract(json.parseToJsonElement(combined)) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return combined.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && (it.startsWith("{") || it.startsWith("[")) }
            .mapNotNull { line -> runCatching { extract(json.parseToJsonElement(line)) }.getOrNull() }
            .firstOrNull { it.isNotBlank() }
    }

    private fun parseOpenCodeJsonOutput(rawOutput: String): String {
        if (rawOutput.isBlank()) return rawOutput

        val json = Json { ignoreUnknownKeys = true }
        val textParts = linkedSetOf<String>()

        fun collectText(element: JsonElement) {
            when (element) {
                is JsonArray -> element.forEach(::collectText)
                is JsonObject -> {
                    val type = element["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
                    if (type == "tool_use" || type == "step_start") {
                        return
                    }
                    extractTextFromEvent(element)
                        .takeIf { it.isNotBlank() }
                        ?.let { textParts += normalizeOpenCodeText(it) }
                    val part = element["part"]
                    if (part is JsonObject) {
                        val partType = part["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
                        if (partType != "tool") {
                            extractTextFromEvent(part)
                                .takeIf { it.isNotBlank() }
                                ?.let { textParts += normalizeOpenCodeText(it) }
                        }
                    }
                }
                else -> Unit
            }
        }

        runCatching {
            collectText(json.parseToJsonElement(rawOutput.trim()))
        }.onFailure {
            rawOutput.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && (it.startsWith("{") || it.startsWith("[")) }
                .forEach { line ->
                    runCatching { collectText(json.parseToJsonElement(line)) }
                }
        }

        return textParts
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .ifBlank { rawOutput }
    }

    private fun extractTextFromEvent(event: JsonObject): String {
        val contentFields = listOf("content", "text", "message", "output", "summary")
        for (field in contentFields) {
            val value = event[field]
            if (value is JsonPrimitive && value.isString) {
                return value.content
            }
            if (value is JsonObject) {
                val nested = extractTextFromEvent(value)
                if (nested.isNotBlank()) return nested
            }
            if (value is JsonArray) {
                val nested = value.joinToString("\n") { element ->
                    when (element) {
                        is JsonPrimitive -> if (element.isString) element.content else ""
                        is JsonObject -> extractTextFromEvent(element)
                        else -> ""
                    }
                }.trim()
                if (nested.isNotBlank()) return nested
            }
        }
        return ""
    }

    private fun normalizeOpenCodeText(text: String): String =
        text.lineSequence()
            .map { it.trimEnd() }
            .filter { line ->
                val trimmed = line.trim()
                trimmed.isNotBlank() &&
                    !trimmed.startsWith("{\"type\"") &&
                    !trimmed.contains("\"sessionID\"") &&
                    !trimmed.contains("\"callID\"") &&
                    !trimmed.contains("\"tool\"") &&
                    !trimmed.contains("\"command\"")
            }
            .joinToString("\n")
            .trim()

    override fun validateInput(input: String?): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult.Failure(listOf("Input prompt is required for OpenCode"))
        }
        return ValidationResult.Success
    }
}
