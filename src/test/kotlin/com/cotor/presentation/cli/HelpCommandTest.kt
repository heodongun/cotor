package com.cotor.presentation.cli

/**
 * File overview for HelpCommandTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around help command test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class HelpCommandTest : FunSpec({
    test("detailed help printer renders Korean usage guidance") {
        val recorder = TerminalRecorder()
        val terminal = Terminal(recorder)

        CheatSheetPrinter.printDetailed(terminal, CliHelpLanguage.KOREAN)
        recorder.output() shouldContain "Cotor 사용법"
        recorder.output() shouldContain "대화형 TUI 채팅 시작"
        recorder.output() shouldContain "cotor help --lang en"
    }

    test("detailed help printer renders English usage guidance") {
        val recorder = TerminalRecorder()
        val terminal = Terminal(recorder)

        CheatSheetPrinter.printDetailed(terminal, CliHelpLanguage.ENGLISH)
        recorder.output() shouldContain "Cotor Help"
        recorder.output() shouldContain "Start the interactive TUI chat"
        recorder.output() shouldContain "cotor help --lang ko"
    }

    test("help language resolves Korean from explicit request and environment") {
        CliHelpLanguage.resolve(requested = "ko") shouldBe CliHelpLanguage.KOREAN
        CliHelpLanguage.resolve(
            environment = mapOf(
                "LANG" to "ko_KR.UTF-8",
                "LC_ALL" to "C.UTF-8"
            )
        ) shouldBe CliHelpLanguage.KOREAN
    }
})
