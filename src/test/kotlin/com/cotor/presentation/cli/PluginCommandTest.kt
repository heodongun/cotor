package com.cotor.presentation.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class PluginCommandTest : FunSpec({
    test("plugin init creates kotlin scaffold") {
        val pluginDirName = "build/tmp/plugin-test-${System.currentTimeMillis()}"
        val pluginRoot = Path(pluginDirName)

        try {
            val result = PluginCommand().test("init $pluginDirName")

            result.statusCode shouldBe 0
            pluginRoot.exists() shouldBe true
            pluginRoot.resolve("build.gradle.kts").readText() shouldContain "../build/libs/cotor-1.0.0-all.jar"
            pluginRoot.resolve("src/main/kotlin/com/example/plugin/SamplePlugin.kt").readText() shouldContain "class SamplePlugin"
            pluginRoot.resolve("src/main/resources/META-INF/services/com.cotor.data.plugin.AgentPlugin").readText() shouldContain "com.example.plugin.SamplePlugin"
        } finally {
            if (pluginRoot.exists()) {
                Files.walk(pluginRoot)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }
})
