package com.cotor

/**
 * File overview for Main.
 *
 * This file belongs to the shared implementation area for the product runtime.
 * It groups declarations around main so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.di.initializeCotor
import com.cotor.error.UserFriendlyError
import com.cotor.presentation.cli.*
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
                "hello", "help", "init", "list", "status", "version", "run", "validate", "test", "dash", "interactive", "template", "resume", "checkpoint", "stats", "doctor", "web", "lint", "explain", "plugin", "agent", "company", "auth", "app-server", "install", "update", "delete", "completion", "policy", "evidence", "github", "knowledge", "verification", "mcp" -> {
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
                HelloCommand(),
                HelpCommand(),
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
                AppServerCommand(),
                LintCommand(),
                ExplainCommand(),
                PluginCommand(),
                AgentCommand(),
                AuthCommand(),
                CompanyCommand(),
                PolicyCommand(),
                EvidenceCommand(),
                GitHubProviderCommand(),
                KnowledgeCommand(),
                VerificationCommand(),
                McpCommand(),
                InstallCommand(),
                UpdateCommand(),
                DeleteCommand(),
                VersionCommand(),
                CompletionCommand()
            )
            .main(args)
    } catch (e: UserFriendlyError) {
        // User-friendly message should always be visible to users.
        System.err.println(e.message ?: "❌ Unknown error")
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
        System.err.println("\n🧭 Quick help: run 'cotor help' or 'cotor --short', or see docs/QUICK_START.md")
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
