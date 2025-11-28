package com.cotor.data.config

import com.cotor.model.AgentConfig
import com.cotor.model.CotorConfig
import com.cotor.model.ConfigurationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.exists

class FileConfigRepositoryTest : FunSpec({
    val yamlParser = YamlParser()
    val jsonParser = JsonParser()

    test("throws friendly error when config file is missing") {
        val repo = FileConfigRepository(yamlParser, jsonParser)
        val missing = Files.createTempDirectory("config-missing").resolve("nope.yaml")

        val error = shouldThrow<ConfigurationException> {
            runBlocking { repo.loadConfig(missing) }
        }

        error.message shouldBe "Configuration file not found: $missing"
    }

    test("creates parent directories when saving configuration") {
        val repo = FileConfigRepository(yamlParser, jsonParser)
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
