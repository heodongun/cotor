package com.cotor.presentation.cli

/**
 * File overview for AppServerCommand.
 *
 * This file belongs to the CLI presentation layer for interactive and command-driven flows.
 * It groups declarations around app server command so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.app.AppServer
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int

/**
 * CLI entrypoint used by the native macOS shell.
 *
 * This keeps desktop orchestration in the same distributable as the rest of Cotor
 * instead of requiring a second bespoke backend binary.
 */
class AppServerCommand : CliktCommand(
    name = "app-server",
    help = "Launch the local API server used by the Cotor macOS desktop app"
) {
    private val host by option("--host", help = "Host interface to bind the app server to")
        .default(System.getenv("COTOR_APP_HOST") ?: "127.0.0.1")
    private val port by option("--port", "-p", help = "Port to run the app server on").int().default(8787)
    private val token by option("--token", help = "Optional bearer token required by the desktop app")
        .default(System.getenv("COTOR_APP_TOKEN").orEmpty())
    private val controlToken by option("--control-token", help = "Optional bearer token required by MCP control tools")
        .default(System.getenv("COTOR_APP_CONTROL_TOKEN").orEmpty())

    override fun run() {
        if (requiresTokenForHost(host) && token.isBlank()) {
            throw CliktError("--token or COTOR_APP_TOKEN is required when binding app-server to non-loopback host '$host'")
        }
        AppServer().start(
            host = host,
            port = port,
            token = token.ifBlank { null },
            controlToken = controlToken.ifBlank { null }
        )
    }
}

internal fun requiresTokenForHost(host: String): Boolean {
    val normalized = host.trim().lowercase()
    return normalized !in setOf("127.0.0.1", "localhost", "::1")
}
