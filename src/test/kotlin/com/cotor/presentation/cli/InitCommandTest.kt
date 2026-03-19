package com.cotor.presentation.cli

/**
 * File overview for InitCommandTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around init command test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class InitCommandTest : FunSpec({
    test("init writes the minimal default config by default") {
        val root = Path("build/tmp/init-default-${System.currentTimeMillis()}")
        val configPath = root.resolve("cotor.yaml")

        try {
            Files.createDirectories(root)
            val result = InitCommand().test("--config $configPath")

            result.statusCode shouldBe 0
            configPath.exists() shouldBe true
            root.resolve("pipelines/default.yaml").exists() shouldBe false
            root.resolve("docs/README.md").exists() shouldBe false
            configPath.readText() shouldContain "name: example-agent"
        } finally {
            deleteRecursively(root)
        }
    }

    test("init starter template creates config, pipeline, and generated docs scaffold") {
        val root = Path("build/tmp/init-starter-${System.currentTimeMillis()}")
        val configPath = root.resolve("cotor.yaml")

        try {
            val result = InitCommand().test("--starter-template --config $configPath")

            result.statusCode shouldBe 0
            configPath.exists() shouldBe true

            val configText = configPath.readText()
            val agentName = Regex("""- name: ([^\n]+)""").find(configText)?.groupValues?.get(1)?.trim()
                ?: error("Starter agent name not found in generated config")
            val pipelinePath = root.resolve("pipelines/default.yaml")
            val readmePath = root.resolve("docs/README.md")
            val pipelineDocPath = root.resolve("docs/PIPELINES.md")

            pipelinePath.exists() shouldBe true
            readmePath.exists() shouldBe true
            pipelineDocPath.exists() shouldBe true

            configText shouldContain "imports:\n  - \"pipelines/default.yaml\""
            pipelinePath.readText() shouldContain "description: \"Starter workflow for ${root.fileName}\""
            readmePath.readText() shouldContain "Project: `${root.fileName}`"
            readmePath.readText() shouldContain "Default agent: `$agentName`"
            pipelineDocPath.readText() shouldContain "Agent: `$agentName`"
        } finally {
            deleteRecursively(root)
        }
    }
})

private fun deleteRecursively(root: java.nio.file.Path) {
    if (!root.exists()) return

    Files.walk(root)
        .sorted(Comparator.reverseOrder())
        .forEach { Files.deleteIfExists(it) }
}
