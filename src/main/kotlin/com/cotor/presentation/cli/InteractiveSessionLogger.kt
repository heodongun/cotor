package com.cotor.presentation.cli

/**
 * File overview for InteractiveSessionLogger.
 *
 * This file belongs to the CLI presentation layer for interactive and command-driven flows.
 * It groups declarations around interactive session logging so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.chat.ChatMode
import com.cotor.model.AgentConfig
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createDirectories

internal data class InteractiveAgentSummary(
    val agentName: String,
    val success: Boolean,
    val durationMs: Long,
    val error: String? = null
)

internal data class InteractiveTurnOutcome(
    val response: String,
    val agentSummaries: List<InteractiveAgentSummary>
)

internal class InteractiveTurnException(
    message: String,
    val agentSummaries: List<InteractiveAgentSummary> = emptyList(),
    cause: Throwable? = null
) : IllegalStateException(message, cause)

internal data class InteractiveTurnLogContext(
    val startedAt: Instant = Instant.now()
)

internal class InteractiveSessionLogger(
    private val saveDir: Path
) {
    private val logFile = saveDir.resolve("interactive.log")

    init {
        saveDir.createDirectories()
    }

    fun filePath(): Path = logFile

    fun logSessionStart(
        configPath: Path,
        chatMode: ChatMode,
        selectedAgents: List<AgentConfig>,
        activeAgent: AgentConfig?
    ) {
        appendLine(
            "session_started " +
                "config=${configPath.toAbsolutePath().normalize()} " +
                "mode=$chatMode " +
                "active_agent=${activeAgent?.name ?: "-"} " +
                "selected_agents=${selectedAgents.joinToString(",") { it.name }}"
        )
    }

    fun logSessionEnd(reason: String) {
        appendLine("session_ended reason=${sanitize(reason)}")
    }

    fun startTurn(
        chatMode: ChatMode,
        selectedAgents: List<AgentConfig>,
        activeAgent: AgentConfig?,
        userInput: String
    ): InteractiveTurnLogContext {
        appendLine(
            "turn_started " +
                "mode=$chatMode " +
                "active_agent=${activeAgent?.name ?: "-"} " +
                "selected_agents=${selectedAgents.joinToString(",") { it.name }} " +
                "input=${sanitize(preview(userInput))}"
        )
        return InteractiveTurnLogContext()
    }

    fun logTurnSuccess(
        context: InteractiveTurnLogContext,
        outcome: InteractiveTurnOutcome
    ) {
        appendLine(
            "turn_completed " +
                "duration_ms=${Duration.between(context.startedAt, Instant.now()).toMillis()} " +
                "success=true " +
                "response_chars=${outcome.response.length}"
        )
        outcome.agentSummaries.forEach(::appendAgentSummary)
    }

    fun logTurnFailure(
        context: InteractiveTurnLogContext,
        error: Throwable,
        agentSummaries: List<InteractiveAgentSummary>
    ) {
        appendLine(
            "turn_completed " +
                "duration_ms=${Duration.between(context.startedAt, Instant.now()).toMillis()} " +
                "success=false " +
                "error=${sanitize(error.message ?: error::class.java.simpleName)}"
        )
        agentSummaries.forEach(::appendAgentSummary)
        appendLine("stacktrace=${sanitize(stackTraceOf(error))}")
    }

    private fun appendAgentSummary(summary: InteractiveAgentSummary) {
        appendLine(
            "agent_result " +
                "name=${summary.agentName} " +
                "success=${summary.success} " +
                "duration_ms=${summary.durationMs} " +
                "error=${sanitize(summary.error ?: "-")}"
        )
    }

    private fun appendLine(line: String) {
        Files.writeString(
            logFile,
            "[${Instant.now()}] $line\n",
            CREATE,
            WRITE,
            java.nio.file.StandardOpenOption.APPEND
        )
    }

    private fun preview(text: String, maxChars: Int = 200): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "..."
    }

    private fun sanitize(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun stackTraceOf(error: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { error.printStackTrace(it) }
        return writer.toString().trim()
    }
}
