package com.cotor.presentation.cli

import com.cotor.data.process.resolveExecutablePath
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists

class AuthCommand : CliktCommand(
    name = "auth",
    help = "Manage authentication helpers for external runtimes"
) {
    init {
        subcommands(CodexOAuthCommand())
    }

    override fun run() = Unit
}

private class CodexOAuthCommand : CliktCommand(
    name = "codex-oauth",
    help = "Manage the dedicated Codex OAuth home used by Cotor"
) {
    init {
        subcommands(CodexOAuthLoginCommand(), CodexOAuthStatusCommand(), CodexOAuthLogoutCommand())
    }

    override fun run() = Unit
}

private abstract class CodexOAuthBaseCommand(
    name: String,
    help: String
) : CliktCommand(name = name, help = help) {
    protected fun oauthHome(): Path {
        val override = System.getenv("COTOR_CODEX_OAUTH_HOME")?.trim()?.takeIf { it.isNotBlank() }
        if (override != null) {
            return Path.of(override)
        }
        val home = System.getenv("HOME")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home")
        return Path.of(home).resolve(".cotor").resolve("auth").resolve("codex-oauth")
    }

    protected fun authFile(): Path = oauthHome().resolve("auth.json")
}

private class CodexOAuthStatusCommand : CodexOAuthBaseCommand(
    name = "status",
    help = "Show whether the managed Codex OAuth auth.json exists"
) {
    override fun run() {
        val home = oauthHome()
        val authFile = authFile()
        echo("home: $home")
        echo("authFile: $authFile")
        echo("authenticated: ${Files.exists(authFile)}")
    }
}

private class CodexOAuthLoginCommand : CodexOAuthBaseCommand(
    name = "login",
    help = "Run `codex login` against Cotor's dedicated OAuth home"
) {
    override fun run() {
        val codex = resolveExecutablePath("codex")?.toString()
            ?: error("codex executable not found in PATH")
        val home = oauthHome()
        home.createDirectories()
        val process = ProcessBuilder(codex, "login")
            .directory(home.toFile())
            .inheritIO()
            .apply {
                environment()["CODEX_HOME"] = home.toString()
            }
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("codex login failed with exit code $exitCode")
        }
        echo("Codex OAuth login completed.")
        echo("home: $home")
        echo("authFile: ${authFile()}")
    }
}

private class CodexOAuthLogoutCommand : CodexOAuthBaseCommand(
    name = "logout",
    help = "Remove the managed Codex OAuth auth.json"
) {
    override fun run() {
        val removed = authFile().deleteIfExists()
        echo("removedAuthFile: $removed")
        echo("authFile: ${authFile()}")
    }
}
