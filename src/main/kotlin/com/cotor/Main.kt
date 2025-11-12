package com.cotor

import com.cotor.di.initializeCotor
import com.cotor.presentation.cli.*
import com.cotor.presentation.web.WebServer
import com.github.ajalt.clikt.core.subcommands
import org.koin.core.context.stopKoin

/**
 * Main entry point for Cotor CLI
 */
fun main(args: Array<String>) {
    // Initialize Koin dependency injection
    initializeCotor()

    try {
        // Simple mode - just run pipeline directly
        if (args.isNotEmpty() && !args[0].startsWith("-")) {
            when (args[0]) {
                "web" -> {
                    println("🌐 Starting Cotor Web UI...")
                    println("   Open http://localhost:8080 in your browser")
                    println()
                    WebServer().start()
                    return
                }
                "init", "list", "status", "version" -> {
                    // Use full CLI for these commands
                }
                else -> {
                    // Direct pipeline execution
                    SimpleCLI().run(args)
                    return
                }
            }
        }
        
        // Full CLI mode
        CotorCli()
            .subcommands(
                InitCommand(),
                RunCommand(),
                StatusCommand(),
                ListCommand(),
                VersionCommand()
            )
            .main(args)
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        if (args.contains("--debug") || args.contains("-d")) {
            e.printStackTrace()
        }
        System.exit(1)
    } finally {
        // Cleanup Koin
        stopKoin()
    }
}
