package com.cotor.data.config

/**
 * File overview for ExampleConfigSmokeTest.
 *
 * This file belongs to the test suite that keeps documented examples aligned with
 * the plugins available in the shipped runtime.
 */

import com.cotor.data.plugin.ReflectionPluginLoader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import java.nio.file.Path

class ExampleConfigSmokeTest : FunSpec({
    test("map fanout example references loadable plugins and seeded fanout data") {
        val config = FileConfigRepository(YamlParser(), JsonParser()) { Path.of("build/tmp/example-home") }
            .loadConfigExact(Path.of("examples/map-fanout.yaml"))
        val loader = ReflectionPluginLoader(LoggerFactory.getLogger("ExampleConfigSmokeTest"))

        config.agents.forEach { agent ->
            loader.loadPlugin(agent.pluginClass).metadata.name.isNotBlank() shouldBe true
        }

        val pipeline = config.pipelines.single { it.name == "map-fanout-pipeline" }
        pipeline.stages.single().fanout?.source shouldBe "files"
    }
})
