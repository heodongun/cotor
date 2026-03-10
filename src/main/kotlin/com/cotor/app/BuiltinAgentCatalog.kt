package com.cotor.app

import com.cotor.model.AgentConfig

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
    private val specs = mapOf(
        "claude" to BuiltinAgentSpec(
            pluginClass = "com.cotor.data.plugin.ClaudePlugin",
            defaultTimeoutMs = 60_000,
            defaultParameters = mapOf("model" to "claude-3-7-sonnet-latest")
        ),
        "codex" to BuiltinAgentSpec(
            pluginClass = "com.cotor.data.plugin.CodexPlugin",
            defaultTimeoutMs = 60_000,
            defaultParameters = mapOf("model" to "gpt-5.3-codex-spark")
        ),
        "gemini" to BuiltinAgentSpec(
            pluginClass = "com.cotor.data.plugin.GeminiPlugin",
            defaultTimeoutMs = 60_000,
            defaultParameters = mapOf("model" to "gemini-3.0-flash")
        ),
        "copilot" to BuiltinAgentSpec(
            pluginClass = "com.cotor.data.plugin.CopilotPlugin",
            defaultTimeoutMs = 60_000
        ),
        "cursor" to BuiltinAgentSpec(
            pluginClass = "com.cotor.data.plugin.CursorPlugin",
            defaultTimeoutMs = 60_000
        ),
        "opencode" to BuiltinAgentSpec(
            pluginClass = "com.cotor.data.plugin.OpenCodePlugin",
            defaultTimeoutMs = 60_000
        ),
        "qwen" to BuiltinAgentSpec(
            pluginClass = "com.cotor.data.plugin.CommandPlugin",
            defaultTimeoutMs = 60_000,
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
