package com.cotor.security

import com.cotor.model.SecurityConfig
import com.cotor.model.SecurityException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import org.slf4j.LoggerFactory
import java.nio.file.Files

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

    test("command validation rejects shell interpreter execution even when shell is allowlisted") {
        val validator = DefaultSecurityValidator(
            SecurityConfig(allowedExecutables = setOf("sh")),
            LoggerFactory.getLogger("SecurityValidatorTest")
        )

        shouldThrow<SecurityException> {
            validator.validateCommand(listOf("sh", "-c", "id"))
        }
    }

    test("command validation checks resolved absolute path against allowed directories") {
        val blockedDir = Files.createTempDirectory("cotor-blocked-bin")
        val executable = blockedDir.resolve("qwen").toFile()
        executable.writeText("#!/bin/sh\nexit 0\n")
        executable.setExecutable(true)
        val allowedDir = Files.createTempDirectory("cotor-allowed-bin")
        val validator = DefaultSecurityValidator(
            SecurityConfig(
                allowedExecutables = setOf("qwen"),
                allowedDirectories = listOf(allowedDir)
            ),
            LoggerFactory.getLogger("SecurityValidatorTest")
        )

        shouldThrow<SecurityException> {
            validator.validateCommand(listOf(executable.absolutePath))
        }
    }
})
