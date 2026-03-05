package com.cotor.data.config

import com.cotor.model.AgentConfig
import com.cotor.model.ConfigurationException
import com.cotor.model.CotorConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText

class FileConfigRepositoryTest : FunSpec({
    val yamlParser = YamlParser()
    val jsonParser = JsonParser()

    test("merges configs from .cotor directory") {
        val repo = FileConfigRepository(yamlParser, jsonParser) { Files.createTempDirectory("fake-home") }
        val baseDir = Files.createTempDirectory("config-merge")
        val cotorDir = baseDir.resolve(".cotor").createDirectory()

        val mainConfigPath = baseDir.resolve("cotor.yaml")
        mainConfigPath.writeText(
            """
            version: "1.0"
            agents:
              - name: agent1
                pluginClass: "com.cotor.Agent1"
            pipelines:
              - name: pipeline1
                description: "Main pipeline"
            """.trimIndent()
        )

        val serviceConfigPath = cotorDir.resolve("service.yaml")
        serviceConfigPath.writeText(
            """
            agents:
              - name: agent2
                pluginClass: "com.cotor.Agent2"
            logging:
              level: "DEBUG"
            """.trimIndent()
        )

        val devConfigPath = cotorDir.resolve("dev.yaml")
        devConfigPath.writeText(
            """
            version: "1.1"
            agents:
              - name: agent1
                pluginClass: "com.cotor.Agent1.dev"
              - name: agent3
                pluginClass: "com.cotor.Agent3"
            """.trimIndent()
        )

        val loadedConfig = runBlocking { repo.loadConfig(mainConfigPath) }

        loadedConfig.version shouldBe "1.1"
        loadedConfig.logging.level shouldBe "DEBUG"
        loadedConfig.agents.size shouldBe 3

        val agent1 = loadedConfig.agents.find { it.name == "agent1" }
        agent1?.pluginClass shouldBe "com.cotor.Agent1.dev"

        val agent2 = loadedConfig.agents.find { it.name == "agent2" }
        agent2?.pluginClass shouldBe "com.cotor.Agent2"

        val agent3 = loadedConfig.agents.find { it.name == "agent3" }
        agent3?.pluginClass shouldBe "com.cotor.Agent3"

        loadedConfig.pipelines.size shouldBe 1
        loadedConfig.pipelines.first().name shouldBe "pipeline1"
    }

    test("deep merges nested config objects") {
        val repo = FileConfigRepository(yamlParser, jsonParser) { Files.createTempDirectory("fake-home") }
        val baseDir = Files.createTempDirectory("config-deep-merge")
        val cotorDir = baseDir.resolve(".cotor").createDirectory()

        val mainConfigPath = baseDir.resolve("cotor.yaml")
        mainConfigPath.writeText(
            """
            logging:
              level: "INFO"
              file: "/var/log/cotor.log"
              format: "json"
            """.trimIndent()
        )

        val overrideConfigPath = cotorDir.resolve("dev.yaml")
        overrideConfigPath.writeText(
            """
            logging:
              level: "DEBUG"
            """.trimIndent()
        )

        val loadedConfig = runBlocking { repo.loadConfig(mainConfigPath) }

        loadedConfig.logging.level shouldBe "DEBUG"
        loadedConfig.logging.file shouldBe "/var/log/cotor.log"
        loadedConfig.logging.format shouldBe "json"
    }

    test("can override with empty list") {
        val repo = FileConfigRepository(yamlParser, jsonParser) { Files.createTempDirectory("fake-home") }
        val baseDir = Files.createTempDirectory("config-empty-list")
        val cotorDir = baseDir.resolve(".cotor").createDirectory()

        val mainConfigPath = baseDir.resolve("cotor.yaml")
        mainConfigPath.writeText(
            """
            security:
              allowedExecutables:
                - "bash"
                - "python"
            """.trimIndent()
        )

        val overrideConfigPath = cotorDir.resolve("lockdown.yaml")
        overrideConfigPath.writeText(
            """
            security:
              allowedExecutables: []
            """.trimIndent()
        )

        val loadedConfig = runBlocking { repo.loadConfig(mainConfigPath) }

        loadedConfig.security.allowedExecutables.size shouldBe 0
    }

    test("keeps base list when override omits collection field") {
        val repo = FileConfigRepository(yamlParser, jsonParser) { Files.createTempDirectory("fake-home") }
        val baseDir = Files.createTempDirectory("config-omit-list")
        val cotorDir = baseDir.resolve(".cotor").createDirectory()

        val mainConfigPath = baseDir.resolve("cotor.yaml")
        mainConfigPath.writeText(
            """
            security:
              allowedExecutables:
                - "bash"
                - "python"
            """.trimIndent()
        )

        val overrideConfigPath = cotorDir.resolve("dev.yaml")
        overrideConfigPath.writeText(
            """
            logging:
              level: "DEBUG"
            """.trimIndent()
        )

        val loadedConfig = runBlocking { repo.loadConfig(mainConfigPath) }

        loadedConfig.security.allowedExecutables shouldBe listOf("bash", "python")
        loadedConfig.logging.level shouldBe "DEBUG"
    }

    test("can override List<Path> with empty list") {
        val repo = FileConfigRepository(yamlParser, jsonParser) { Files.createTempDirectory("fake-home") }
        val baseDir = Files.createTempDirectory("config-empty-path-list")
        val cotorDir = baseDir.resolve(".cotor").createDirectory()

        val mainConfigPath = baseDir.resolve("cotor.yaml")
        mainConfigPath.writeText(
            """
            security:
              allowedDirectories:
                - "/tmp"
                - "/var/log"
            """.trimIndent()
        )

        val overrideConfigPath = cotorDir.resolve("restrict.yaml")
        overrideConfigPath.writeText(
            """
            security:
              allowedDirectories: []
            """.trimIndent()
        )

        val loadedConfig = runBlocking { repo.loadConfig(mainConfigPath) }

        loadedConfig.security.allowedDirectories.size shouldBe 0
    }


    test("merges global and local .cotor overrides") {
        val baseDir = Files.createTempDirectory("config-global-local")
        val localCotorDir = baseDir.resolve(".cotor").createDirectory()
        val fakeHome = baseDir.resolve("home").createDirectory()
        val globalCotorDir = fakeHome.resolve(".cotor").createDirectory()
        val repo = FileConfigRepository(yamlParser, jsonParser) { fakeHome }

        val mainConfigPath = baseDir.resolve("cotor.yaml")
        mainConfigPath.writeText(
            """
            version: "1.0"
            agents:
              - name: base
                pluginClass: "com.cotor.Base"
            """.trimIndent()
        )

        globalCotorDir.resolve("agents.yaml").writeText(
            """
            agents:
              - name: global-agent
                pluginClass: "com.cotor.Global"
              - name: shared-agent
                pluginClass: "com.cotor.GlobalShared"
            """.trimIndent()
        )

        localCotorDir.resolve("agents.yaml").writeText(
            """
            agents:
              - name: local-agent
                pluginClass: "com.cotor.Local"
              - name: shared-agent
                pluginClass: "com.cotor.LocalShared"
            """.trimIndent()
        )

        val loadedConfig = runBlocking { repo.loadConfig(mainConfigPath) }

        loadedConfig.agents.map { it.name }.toSet() shouldBe setOf("base", "global-agent", "local-agent", "shared-agent")
        loadedConfig.agents.find { it.name == "shared-agent" }?.pluginClass shouldBe "com.cotor.LocalShared"
    }

    test("throws friendly error when config file is missing") {
        val repo = FileConfigRepository(yamlParser, jsonParser) { Files.createTempDirectory("fake-home") }
        val missing = Files.createTempDirectory("config-missing").resolve("nope.yaml")

        val error = shouldThrow<ConfigurationException> {
            runBlocking { repo.loadConfig(missing) }
        }

        error.message shouldBe "Configuration file not found: $missing"
    }

    test("creates parent directories when saving configuration") {
        val repo = FileConfigRepository(yamlParser, jsonParser) { Files.createTempDirectory("fake-home") }
        val baseDir = Files.createTempDirectory("config-save")
        val target = baseDir.resolve("nested/config.yaml")
        val config = CotorConfig(
            agents = listOf(AgentConfig(name = "echo", pluginClass = "com.cotor.data.plugin.EchoPlugin"))
        )

        runBlocking { repo.saveConfig(config, target) }

        target.exists().shouldBeTrue()
        val loaded = runBlocking { repo.loadConfig(target) }
        loaded.agents.first().name shouldBe "echo"
    }
})
