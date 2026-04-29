package com.cotor.security

import com.cotor.model.SecurityConfig
import com.cotor.model.SecurityException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import org.slf4j.LoggerFactory

class SecurityValidatorTest : FunSpec({
    test("command whitelist accepts absolute executable path by basename") {
        val validator = DefaultSecurityValidator(
            SecurityConfig(allowedExecutables = setOf("qwen")),
            LoggerFactory.getLogger("SecurityValidatorTest")
        )

        validator.validateCommand(listOf("/opt/homebrew/bin/qwen", "{input}"))
    }

    test("command whitelist rejects executable outside allowlist") {
        val validator = DefaultSecurityValidator(
            SecurityConfig(allowedExecutables = setOf("qwen")),
            LoggerFactory.getLogger("SecurityValidatorTest")
        )

        shouldThrow<SecurityException> {
            validator.validateCommand(listOf("sh", "-c", "id"))
        }
    }
})
