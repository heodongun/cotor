package com.cotor.presentation.cli

/**
 * File overview for CompletionCommandTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around completion command test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class CompletionCommandTest : FunSpec({
    test("completion includes tui alias, test command, plugin command, company command, and auth command") {
        val result = CompletionCommand().test("bash")

        result.statusCode shouldBe 0
        result.stdout.shouldContain("interactive")
        result.stdout.shouldContain("tui")
        result.stdout.shouldContain("test")
        result.stdout.shouldContain("plugin")
        result.stdout.shouldContain("company")
        result.stdout.shouldContain("auth")
    }
})
