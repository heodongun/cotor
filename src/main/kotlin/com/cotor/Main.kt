package com.cotor

import com.cotor.di.initializeCotor
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
        // Execute CLI
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
