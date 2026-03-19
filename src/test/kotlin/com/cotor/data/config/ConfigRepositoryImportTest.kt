package com.cotor.data.config

/**
 * File overview for ConfigRepositoryImportTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around config repository import test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class ConfigRepositoryImportTest : FunSpec({
    test("loadConfig resolves imported pipeline files relative to the root config") {
        val root = Path("build/tmp/config-import-${System.currentTimeMillis()}")
        val configPath = root.resolve("cotor.yaml")
        val pipelinePath = root.resolve("pipelines/default.yaml")
        val isolatedHome = root.resolve("isolated-home")

        try {
            root.resolve("pipelines").createDirectories()
            isolatedHome.createDirectories()
            configPath.writeText(
                """
                version: "1.0"
                imports:
                  - "pipelines/default.yaml"
                agents:
                  - name: example-agent
                    pluginClass: com.cotor.data.plugin.EchoPlugin
                """.trimIndent()
            )
            pipelinePath.writeText(
                """
                pipelines:
                  - name: imported-pipeline
                    description: "Imported pipeline"
                    executionMode: SEQUENTIAL
                    stages:
                      - id: step1
                        agent:
                          name: example-agent
                        input: "hello"
                """.trimIndent()
            )

            val repository = FileConfigRepository(
                yamlParser = YamlParser(),
                jsonParser = JsonParser(),
                homeDirectoryProvider = { isolatedHome.toAbsolutePath().normalize() }
            )
            val config = runBlocking { repository.loadConfig(configPath) }

            config.agents.shouldHaveSize(1)
            config.pipelines.shouldHaveSize(1)
            config.pipelines.single().name shouldBe "imported-pipeline"
        } finally {
            if (Files.exists(root)) {
                Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }
})
