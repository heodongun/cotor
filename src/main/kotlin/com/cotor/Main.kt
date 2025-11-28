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
                "init", "list", "status", "version", "run", "validate", "test", "dash", "template", "resume", "checkpoint", "stats", "completion", "doctor" -> {
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
                EnhancedRunCommand(),
                CodexDashboardCommand(),
                ValidateCommand(),
                TestCommand(),
                TemplateCommand(),
                ResumeCommand(),
                CheckpointCommand(),
                StatsCommand(),
                DoctorCommand(),
                StatusCommand(),
                ListCommand(),
                VersionCommand(),
                WebCommand(),
                VersionCommand(),
                CompletionCommand()
            )
            .main(args)
    } catch (e: Exception) {
        // Enhanced error handling with suggestions
        val errorInfo = com.cotor.error.ErrorHelper.getErrorMessage(e)

        System.err.println("\n${errorInfo.title}")
        System.err.println("━".repeat(50))
        System.err.println("Error: ${errorInfo.message}")
        System.err.println()
        System.err.println("💡 Suggestions:")
        errorInfo.suggestions.forEachIndexed { index, suggestion ->
            System.err.println("  ${index + 1}. $suggestion")
        }
        System.err.println("\n🧭 Quick help: run 'cotor --short' or see docs/QUICK_START.md")
        System.err.println("📦 Examples: examples/run-examples.sh")
        System.err.println("━".repeat(50))

        if (args.contains("--debug") || args.contains("-d")) {
            System.err.println("\n🔍 Debug Stack Trace:")
            e.printStackTrace()
        } else {
            System.err.println("\nℹ️  Run with --debug for detailed stack trace")
        }
        System.exit(1)
    } finally {
        // Cleanup Koin
        stopKoin()
    }
}
