package com.cotor.presentation.cli

import com.cotor.data.config.ConfigRepository
import com.cotor.error.ErrorMessages
import com.cotor.error.UserFriendlyError
import com.cotor.validation.Linter
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.TextColors.*
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.system.exitProcess

/**
 * Lint command to statically check configuration files
 */
class LintCommand : CliktCommand(
    name = "lint",
    help = "Statically check configuration for schema violations and best practices."
), KoinComponent {
    private val configRepository: ConfigRepository by inject()
    private val linter = Linter()

    private val configPath by option("--config", "-c", help = "Path to configuration file")
        .path(mustExist = false)
        .default(Path("cotor.yaml"))

    override fun run() = runBlocking {
        try {
            if (!configPath.exists()) {
                throw ErrorMessages.configNotFound(configPath.toString())
            }

            val config = configRepository.loadConfig(configPath)
            val result = linter.lint(config)

            if (result.warnings.isNotEmpty()) {
                terminal.println(yellow("⚠️  Warnings:"))
                result.warnings.forEach { terminal.println("   - $it") }
                terminal.println()
            }

            if (result.errors.isNotEmpty()) {
                terminal.println(red("❌ Errors found:"))
                result.errors.forEach { terminal.println("   - $it") }
                terminal.println()
                terminal.println(red("Linting failed with ${result.errors.size} errors."))
                exitProcess(1)
            }

            if (result.isSuccess) {
                terminal.println(green("✅ Linting passed with no errors."))
                if (result.warnings.isEmpty()) {
                    terminal.println(green("🎉 No warnings found!"))
                }
            }

        } catch (e: UserFriendlyError) {
            terminal.println(red(e.message ?: "An unknown user-friendly error occurred."))
            exitProcess(1)
        } catch (e: Exception) {
            terminal.println(red("An unexpected error occurred: ${e.message ?: "Unknown error"}"))
            e.printStackTrace()
            exitProcess(1)
        }
    }
}
