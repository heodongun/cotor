package com.cotor.presentation.cli

/**
 * File overview for TemplateCommandTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around generated pipeline templates so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.data.config.FileConfigRepository
import com.cotor.data.config.JsonParser
import com.cotor.data.config.YamlParser
import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class TemplateCommandTest : FunSpec({
    test("all listed templates generate YAML that can be parsed by the config repository") {
        val root = createTempDirectory("cotor-template-parse")
        val fakeHome = createTempDirectory("cotor-template-home")
        val repository = FileConfigRepository(YamlParser(), JsonParser()) { fakeHome }
        val templateTypes = listOf(
            "compare",
            "chain",
            "review",
            "consensus",
            "fanout",
            "selfheal",
            "verified",
            "blocked-escalation",
            "release",
            "custom"
        )

        try {
            templateTypes.forEach { type ->
                val output = root.resolve("$type.yaml")
                val result = TemplateCommand().test("$type $output")

                withClue("template=$type output=${result.output}") {
                    result.statusCode shouldBe 0
                    runBlocking {
                        repository.loadConfigExact(output).pipelines.isNotEmpty() shouldBe true
                    }
                }
            }
        } finally {
            deleteRecursively(root)
            deleteRecursively(fakeHome)
        }
    }

    test("verified template makes artifact quality scanning explicit") {
        val root = createTempDirectory("cotor-verified-template")
        val output = root.resolve("verified.yaml")

        try {
            val result = TemplateCommand().test("verified $output")

            result.statusCode shouldBe 0
            output.toFile().readText() shouldContain "artifactQualityScan: \"true\""
        } finally {
            deleteRecursively(root)
        }
    }
})

private fun deleteRecursively(root: java.nio.file.Path) {
    Files.walk(root)
        .sorted(Comparator.reverseOrder())
        .forEach { Files.deleteIfExists(it) }
}
