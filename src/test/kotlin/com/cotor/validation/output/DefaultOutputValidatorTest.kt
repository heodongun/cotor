package com.cotor.validation.output

/**
 * File overview for DefaultOutputValidatorTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around default output validator test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.model.AgentResult
import com.cotor.model.StageValidationConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class DefaultOutputValidatorTest : FunSpec({

    val validator = DefaultOutputValidator(SyntaxValidator())

    test("detects missing required file") {
        val config = StageValidationConfig(requiresFile = "non-existent-file.txt")
        val result = AgentResult("echo", true, "output", null, 0, emptyMap())

        val outcome = validator.validate(result, config)
        outcome.isValid.shouldBeFalse()
        outcome.violations.first() shouldContain "Required file not found"
    }

    test("validates keyword presence") {
        val tmpFile = Files.createTempFile("cotor-test", ".txt").also {
            it.toFile().deleteOnExit()
        }
        Files.writeString(tmpFile, "hello world")

        val config = StageValidationConfig(
            requiresFile = tmpFile.toAbsolutePath().toString(),
            requiredKeywords = listOf("Hello"),
            minLength = 5
        )

        val result = AgentResult("echo", true, "Hello there", null, 0, emptyMap())
        val outcome = validator.validate(result, config)

        outcome.isValid.shouldBeTrue()
    }

    test("fails when keyword missing") {
        val config = StageValidationConfig(requiredKeywords = listOf("MUST HAVE"))
        val result = AgentResult("echo", true, "no keyword here", null, 0, emptyMap())

        val outcome = validator.validate(result, config)
        outcome.isValid.shouldBeFalse()
    }
})
