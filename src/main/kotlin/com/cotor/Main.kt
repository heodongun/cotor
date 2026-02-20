package com.cotor

import com.cotor.di.initializeCotor
import com.cotor.error.UserFriendlyError
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
        // Default entry: launch TUI (interactive mode) when no args are provided.
        if (args.isEmpty()) {
            InteractiveCommand().main(emptyArray())
            return
        }

        // Support `cotor tui` as a friendly alias to interactive mode.
        if (args[0] == "tui") {
            InteractiveCommand().main(args.drop(1).toTypedArray())
            return
        }

        // Simple mode - just run pipeline directly
        if (!args[0].startsWith("-")) {
            when (args[0]) {
                "init", "list", "status", "version", "run", "validate", "test", "dash", "interactive", "template", "resume", "checkpoint", "stats", "completion", "doctor", "web", "lint", "explain" -> {
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
                InteractiveCommand(),
                ValidateCommand(),
                TestCommand(),
                TemplateCommand(),
                ResumeCommand(),
                CheckpointCommand(),
                StatsCommand(),
                DoctorCommand(),
                StatusCommand(),
                ListCommand(),
                WebCommand(),
                LintCommand(),
                ExplainCommand(),
                VersionCommand(),
                CompletionCommand()
            )
            .main(args)
    } catch (e: UserFriendlyError) {
        // UserFriendlyError는 이미 명령어에서 출력했으므로 조용히 종료
        System.exit(1)
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
