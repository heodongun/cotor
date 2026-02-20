package com.cotor.presentation.cli

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
