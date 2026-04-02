package com.cotor.app

/**
 * File overview for BuiltinAgentSpec.
 *
 * This file belongs to the app layer for the desktop shell and localhost app-server surface.
 * It groups declarations around builtin agent catalog so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import com.cotor.model.AgentConfig
import com.cotor.model.CodexDefaults
import com.cotor.model.OpenCodeDefaults

/**
 * Internal template used to synthesize AgentConfig values for the built-in agent roster.
 */
private data class BuiltinAgentSpec(
    val pluginClass: String,
    val defaultTimeoutMs: Long,
    val defaultParameters: Map<String, String> = emptyMap()
)

/**
 * Central registry for agent names the desktop app can offer before a repo-specific
 * `cotor.yaml` overrides or extends them.
 */
object BuiltinAgentCatalog {
    private const val DEFAULT_AI_TIMEOUT_MS = 15 * 60_000L

    private val specs = mapOf(
        "claude" to BuiltinAgentSpec(
            pluginClass = "com.cotor.data.plugin.ClaudePlugin",
            defaultTimeoutMs = DEFAULT_AI_TIMEOUT_MS,
            defaultParameters = mapOf("model" to "claude-sonnet-4-20250514")
        ),
        "codex" to BuiltinAgentSpec(
            pluginClass = "com.cotor.data.plugin.CodexPlugin",
            defaultTimeoutMs = DEFAULT_AI_TIMEOUT_MS,
            defaultParameters = mapOf("model" to CodexDefaults.DEFAULT_MODEL)
        ),
        "codex-exec" to BuiltinAgentSpec(
            pluginClass = "com.cotor.data.plugin.CodexPlugin",
            defaultTimeoutMs = DEFAULT_AI_TIMEOUT_MS,
            defaultParameters = mapOf(
                "model" to CodexDefaults.DEFAULT_MODEL,
                "auth_mode" to "exec"
            )
        ),
        "codex-oauth" to BuiltinAgentSpec(
            pluginClass = "com.cotor.data.plugin.CodexPlugin",
            defaultTimeoutMs = DEFAULT_AI_TIMEOUT_MS,
            defaultParameters = mapOf(
                "model" to CodexDefaults.DEFAULT_MODEL,
                "auth_mode" to "oauth"
            )
        ),
        "gemini" to BuiltinAgentSpec(
            pluginClass = "com.cotor.data.plugin.GeminiPlugin",
            defaultTimeoutMs = DEFAULT_AI_TIMEOUT_MS,
            defaultParameters = mapOf("model" to "gemini-3.0-flash")
        ),
        "copilot" to BuiltinAgentSpec(
            pluginClass = "com.cotor.data.plugin.CopilotPlugin",
            defaultTimeoutMs = DEFAULT_AI_TIMEOUT_MS
        ),
        "cursor" to BuiltinAgentSpec(
            pluginClass = "com.cotor.data.plugin.CursorPlugin",
            defaultTimeoutMs = DEFAULT_AI_TIMEOUT_MS
        ),
        "opencode" to BuiltinAgentSpec(
            pluginClass = "com.cotor.data.plugin.OpenCodePlugin",
            defaultTimeoutMs = DEFAULT_AI_TIMEOUT_MS,
            defaultParameters = mapOf("model" to OpenCodeDefaults.DEFAULT_MODEL)
        ),
        "qwen" to BuiltinAgentSpec(
            pluginClass = "com.cotor.data.plugin.CommandPlugin",
            defaultTimeoutMs = DEFAULT_AI_TIMEOUT_MS,
            defaultParameters = mapOf("argvJson" to """["qwen","{input}"]""")
        ),
        "echo" to BuiltinAgentSpec(
            pluginClass = "com.cotor.data.plugin.EchoPlugin",
            defaultTimeoutMs = 10_000
        )
    )

    /**
     * The UI uses this sorted list to render stable agent chips and menus.
     */
    fun names(): List<String> = specs.keys.sorted()

    /**
     * Convert the catalog entry back into the normal runtime AgentConfig model so
     * the rest of the execution pipeline does not need a special desktop-only type.
     */
    fun get(name: String): AgentConfig? {
        val key = name.trim().lowercase()
        val spec = specs[key] ?: return null
        return AgentConfig(
            name = key,
            pluginClass = spec.pluginClass,
            timeout = spec.defaultTimeoutMs,
            parameters = spec.defaultParameters
        )
    }
}
