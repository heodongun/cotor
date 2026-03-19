package com.cotor.presentation.cli

/**
 * File overview for CodexDashboardCommandTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around codex dashboard command test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CodexDashboardCommandTest : FunSpec({
    test("resolvePromptInput distinguishes EOF from blank input") {
        CodexDashboardCommand.resolvePromptInput(null, null) shouldBe null
        CodexDashboardCommand.resolvePromptInput("", null) shouldBe ""
        CodexDashboardCommand.resolvePromptInput("   ", null) shouldBe ""
        CodexDashboardCommand.resolvePromptInput("", "y") shouldBe "y"
        CodexDashboardCommand.resolvePromptInput("  q  ", null) shouldBe "q"
    }
})
