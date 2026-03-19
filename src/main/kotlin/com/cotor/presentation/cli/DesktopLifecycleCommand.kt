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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

data class DesktopScriptResult(
    val exitCode: Int,
    val output: String
)

typealias DesktopScriptRunner = (Path, String) -> DesktopScriptResult
typealias ProjectRootProvider = () -> Path?
typealias OsNameProvider = () -> String

abstract class DesktopLifecycleCommand(
    commandName: String,
    commandHelp: String,
    private val scriptName: String,
    private val actionLabel: String,
    private val scriptRunner: DesktopScriptRunner = ::runDesktopScript,
    private val projectRootProvider: ProjectRootProvider = ::detectProjectRoot,
    private val osNameProvider: OsNameProvider = { System.getProperty("os.name") }
) : CliktCommand(name = commandName, help = commandHelp) {
    override fun run() {
        if (!isMacOs(osNameProvider())) {
            echo("This command currently supports macOS only.")
            throw ProgramResult(1)
        }

        val projectRoot = projectRootProvider()
            ?: run {
                echo("Could not determine the Cotor project root. Run via shell/cotor or from a Cotor checkout.")
                throw ProgramResult(1)
            }

        val result = scriptRunner(projectRoot, scriptName)
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
            echo("cotor $actionLabel failed.")
            throw ProgramResult(result.exitCode)
        }
    }
}

class InstallCommand(
    scriptRunner: DesktopScriptRunner = ::runDesktopScript,
    projectRootProvider: ProjectRootProvider = ::detectProjectRoot,
    osNameProvider: OsNameProvider = { System.getProperty("os.name") }
) : DesktopLifecycleCommand(
    commandName = "install",
    commandHelp = "Build and install the macOS desktop app into Applications.",
    scriptName = "install-desktop-app.sh",
    actionLabel = "install",
    scriptRunner = scriptRunner,
    projectRootProvider = projectRootProvider,
    osNameProvider = osNameProvider
)

class UpdateCommand(
    scriptRunner: DesktopScriptRunner = ::runDesktopScript,
    projectRootProvider: ProjectRootProvider = ::detectProjectRoot,
    osNameProvider: OsNameProvider = { System.getProperty("os.name") }
) : DesktopLifecycleCommand(
    commandName = "update",
    commandHelp = "Rebuild and update the installed macOS desktop app.",
    scriptName = "update-desktop-app.sh",
    actionLabel = "update",
    scriptRunner = scriptRunner,
    projectRootProvider = projectRootProvider,
    osNameProvider = osNameProvider
)

class DeleteCommand(
    scriptRunner: DesktopScriptRunner = ::runDesktopScript,
    projectRootProvider: ProjectRootProvider = ::detectProjectRoot,
    osNameProvider: OsNameProvider = { System.getProperty("os.name") }
) : DesktopLifecycleCommand(
    commandName = "delete",
    commandHelp = "Delete the installed macOS desktop app and related download artifacts.",
    scriptName = "delete-desktop-app.sh",
    actionLabel = "delete",
    scriptRunner = scriptRunner,
    projectRootProvider = projectRootProvider,
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

internal fun detectProjectRoot(): Path? {
    val explicitRoot = System.getenv("COTOR_PROJECT_ROOT")
        ?.takeIf { it.isNotBlank() }
        ?.let { Paths.get(it).toAbsolutePath().normalize() }
        ?.takeIf { isProjectRoot(it) }
    if (explicitRoot != null) return explicitRoot

    val cwdRoot = locateProjectRoot(Paths.get("").toAbsolutePath().normalize())
    if (cwdRoot != null) return cwdRoot

    val codeSourceRoot = runCatching {
        Paths.get(InstallCommand::class.java.protectionDomain.codeSource.location.toURI())
            .toAbsolutePath()
            .normalize()
    }.getOrNull()?.let(::locateProjectRoot)

    return codeSourceRoot
}

private fun locateProjectRoot(start: Path): Path? {
    var current: Path? = if (Files.isDirectory(start)) start else start.parent
    repeat(8) {
        val candidate = current ?: return null
        if (isProjectRoot(candidate)) return candidate
        current = candidate.parent
    }
    return null
}

private fun isProjectRoot(path: Path): Boolean =
    Files.exists(path.resolve("gradlew")) &&
        Files.exists(path.resolve("shell/install-desktop-app.sh")) &&
        Files.exists(path.resolve("macos/Package.swift"))

private fun isMacOs(osName: String): Boolean = osName.lowercase().contains("mac")
