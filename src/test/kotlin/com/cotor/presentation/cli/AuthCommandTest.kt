package com.cotor.presentation.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class AuthCommandTest : FunSpec({
    test("auth codex-oauth status prints managed auth paths") {
        val result = AuthCommand().test("codex-oauth status")

        result.statusCode shouldBe 0
        result.output shouldContain "home:"
        result.output shouldContain "authFile:"
        result.output shouldContain "authenticated:"
    }
})
