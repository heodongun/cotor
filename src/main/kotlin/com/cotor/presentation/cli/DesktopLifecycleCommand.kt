package com.cotor.presentation.cli

/**
 * File overview for DesktopScriptResult.
 *
 * This file belongs to the CLI presentation layer for interactive and command-driven flows.
 * It groups declarations around desktop lifecycle command so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import java.nio.file.Path
import kotlin.io.path.exists

data class DesktopScriptResult(
    val exitCode: Int,
    val output: String
)

internal typealias DesktopScriptRunner = (Path, String) -> DesktopScriptResult
internal typealias PackagedDesktopActionRunner = (DesktopInstallLayout, DesktopInstallAction) -> DesktopScriptResult
internal typealias DesktopLayoutResolver = () -> DesktopInstallLayout?
internal typealias OsNameProvider = () -> String

internal abstract class DesktopLifecycleCommand(
    commandName: String,
    commandHelp: String,
    private val action: DesktopInstallAction,
    private val scriptRunner: DesktopScriptRunner = ::runDesktopScript,
    private val packagedActionRunner: PackagedDesktopActionRunner = ::runPackagedDesktopAction,
    private val layoutResolver: DesktopLayoutResolver = ::detectDesktopInstallLayout,
    private val osNameProvider: OsNameProvider = { System.getProperty("os.name") }
) : CliktCommand(name = commandName, help = commandHelp) {
    override fun run() {
        if (!isMacOs(osNameProvider())) {
            echo("This command currently supports macOS only.")
            throw ProgramResult(1)
        }

        val layout = layoutResolver()
            ?: run {
                echo("Could not determine a usable Cotor install layout. Run via shell/cotor, from a Cotor checkout, or from a packaged install wrapper.")
                throw ProgramResult(1)
            }

        val result = when (layout.kind) {
            DesktopInstallLayoutKind.SOURCE_CHECKOUT -> scriptRunner(layout.root, action.scriptName)
            DesktopInstallLayoutKind.PACKAGED_INSTALL -> packagedActionRunner(layout, action)
        }
        if (result.output.isNotBlank()) {
            val lines = result.output.lines()
            lines.forEachIndexed { index, line ->
                val isTrailingEmptyLine = index == lines.lastIndex && line.isEmpty()
                if (!isTrailingEmptyLine) {
                    echo(line)
                }
            }
        }
        if (result.exitCode != 0) {
            echo("cotor ${action.actionLabel} failed.")
            throw ProgramResult(result.exitCode)
        }
    }
}

internal class InstallCommand(
    scriptRunner: DesktopScriptRunner = ::runDesktopScript,
    packagedActionRunner: PackagedDesktopActionRunner = ::runPackagedDesktopAction,
    layoutResolver: DesktopLayoutResolver = ::detectDesktopInstallLayout,
    osNameProvider: OsNameProvider = { System.getProperty("os.name") }
) : DesktopLifecycleCommand(
    commandName = "install",
    commandHelp = "Install the macOS desktop app into Applications.",
    action = DesktopInstallAction.INSTALL,
    scriptRunner = scriptRunner,
    packagedActionRunner = packagedActionRunner,
    layoutResolver = layoutResolver,
    osNameProvider = osNameProvider
)

internal class UpdateCommand(
    scriptRunner: DesktopScriptRunner = ::runDesktopScript,
    packagedActionRunner: PackagedDesktopActionRunner = ::runPackagedDesktopAction,
    layoutResolver: DesktopLayoutResolver = ::detectDesktopInstallLayout,
    osNameProvider: OsNameProvider = { System.getProperty("os.name") }
) : DesktopLifecycleCommand(
    commandName = "update",
    commandHelp = "Update the installed macOS desktop app.",
    action = DesktopInstallAction.UPDATE,
    scriptRunner = scriptRunner,
    packagedActionRunner = packagedActionRunner,
    layoutResolver = layoutResolver,
    osNameProvider = osNameProvider
)

internal class DeleteCommand(
    scriptRunner: DesktopScriptRunner = ::runDesktopScript,
    packagedActionRunner: PackagedDesktopActionRunner = ::runPackagedDesktopAction,
    layoutResolver: DesktopLayoutResolver = ::detectDesktopInstallLayout,
    osNameProvider: OsNameProvider = { System.getProperty("os.name") }
) : DesktopLifecycleCommand(
    commandName = "delete",
    commandHelp = "Delete the installed macOS desktop app and related download artifacts.",
    action = DesktopInstallAction.DELETE,
    scriptRunner = scriptRunner,
    packagedActionRunner = packagedActionRunner,
    layoutResolver = layoutResolver,
    osNameProvider = osNameProvider
)

internal fun runDesktopScript(projectRoot: Path, scriptName: String): DesktopScriptResult {
    val scriptPath = projectRoot.resolve("shell").resolve(scriptName)
    if (!scriptPath.exists()) {
        return DesktopScriptResult(
            exitCode = 1,
            output = "Missing desktop lifecycle script: $scriptPath\n"
        )
    }

    val process = ProcessBuilder("/bin/bash", scriptPath.toString())
        .directory(projectRoot.toFile())
        .redirectErrorStream(true)
        .apply {
            environment()["COTOR_PROJECT_ROOT"] = projectRoot.toString()
        }
        .start()

    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    return DesktopScriptResult(exitCode = exitCode, output = output)
}

private fun isMacOs(osName: String): Boolean = osName.lowercase().contains("mac")
