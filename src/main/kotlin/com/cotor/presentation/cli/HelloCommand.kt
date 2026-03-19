package com.cotor.presentation.cli

/**
 * File overview for HelloCommand.
 *
 * This file belongs to the CLI presentation layer for interactive and command-driven flows.
 * It groups declarations around hello command so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option

/**
 * Hello command to say hello to the user
 */
class HelloCommand : CliktCommand(
    name = "hello",
    help = "Say hello to the user"
) {
    private val name by option("--name", "-n", help = "Your name").default("User")

    override fun run() {
        echo("👋 Hello, $name!")
        echo("안녕하세요, ${name}님! Cotor에 오신 것을 환영합니다.")
        echo("Cotor는 로컬 우선 AI 워크플로우 러너입니다.")
    }
}
