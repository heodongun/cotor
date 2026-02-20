package com.cotor.presentation.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class CompletionCommandTest : FunSpec({
    test("completion includes tui alias and test command") {
        val result = CompletionCommand().test("bash")

        result.statusCode shouldBe 0
        result.stdout.shouldContain("interactive")
        result.stdout.shouldContain("tui")
        result.stdout.shouldContain("test")
    }
})
