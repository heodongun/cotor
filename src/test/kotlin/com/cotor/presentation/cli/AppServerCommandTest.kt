package com.cotor.presentation.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AppServerCommandTest : FunSpec({
    test("app-server allows loopback bind without token") {
        requiresTokenForHost("127.0.0.1") shouldBe false
        requiresTokenForHost("localhost") shouldBe false
        requiresTokenForHost("::1") shouldBe false
    }

    test("app-server requires token for non-loopback bind") {
        requiresTokenForHost("0.0.0.0") shouldBe true
        requiresTokenForHost("192.168.0.10") shouldBe true
    }
})
