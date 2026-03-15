package com.cotor.presentation.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class HelloCommandTest : FunSpec({
    test("hello command says hello to the default user") {
        val result = HelloCommand().test()

        result.statusCode shouldBe 0
        result.stdout shouldContain "👋 Hello, User!"
        result.stdout shouldContain "안녕하세요, User님! Cotor에 오신 것을 환영합니다."
    }

    test("hello command says hello to a specific name") {
        val result = HelloCommand().test("--name Gemini")

        result.statusCode shouldBe 0
        result.stdout shouldContain "👋 Hello, Gemini!"
        result.stdout shouldContain "안녕하세요, Gemini님! Cotor에 오신 것을 환영합니다."
    }
})
