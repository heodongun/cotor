package com.cotor.presentation.cli

import com.cotor.analysis.DefaultResultAnalyzer
import com.cotor.analysis.ResultAnalyzer
import com.cotor.data.config.ConfigRepository
import com.cotor.data.config.FileConfigRepository
import com.cotor.data.config.JsonParser
import com.cotor.data.config.YamlParser
import com.cotor.data.plugin.PluginLoader
import com.cotor.data.plugin.ReflectionPluginLoader
import com.cotor.data.process.CoroutineProcessManager
import com.cotor.data.process.ProcessManager
import com.cotor.data.registry.AgentRegistry
import com.cotor.data.registry.InMemoryAgentRegistry
import com.cotor.domain.aggregator.DefaultResultAggregator
import com.cotor.domain.aggregator.ResultAggregator
import com.cotor.domain.executor.AgentExecutor
import com.cotor.domain.executor.DefaultAgentExecutor
import com.cotor.model.SecurityConfig
import com.cotor.security.DefaultSecurityValidator
import com.cotor.security.SecurityValidator
import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class InteractiveCommandTest : FunSpec({
    beforeSpec {
        startKoin {
            modules(
                module {
                    single<Logger> { LoggerFactory.getLogger("CotorTest") }
                    single { YamlParser() }
                    single { JsonParser() }
                    single<ConfigRepository> { FileConfigRepository(get(), get()) }
                    single<AgentRegistry> { InMemoryAgentRegistry() }
                    single<ProcessManager> { CoroutineProcessManager(get()) }
                    single<PluginLoader> { ReflectionPluginLoader(get()) }

                    single {
                        SecurityConfig(
                            useWhitelist = false,
                            allowedExecutables = emptySet(),
                            allowedDirectories = emptyList(),
                            enablePathValidation = false
                        )
                    }
                    single<SecurityValidator> { DefaultSecurityValidator(get(), get()) }
                    single<AgentExecutor> { DefaultAgentExecutor(get(), get(), get(), get()) }
                    single<ResultAnalyzer> { DefaultResultAnalyzer() }
                    single<ResultAggregator> { DefaultResultAggregator(get()) }
                }
            )
        }
    }

    afterSpec {
        stopKoin()
    }

    test("interactive --prompt (single) runs agent and writes transcript") {
        val dir = Files.createTempDirectory("cotor-interactive-single")
        val configPath = dir.resolve("cotor.yaml")
        val saveDir = dir.resolve("out")

        configPath.writeText(
            """
            version: "1.0"
            agents:
              - name: echo1
                pluginClass: com.cotor.data.plugin.EchoPlugin
            """.trimIndent()
        )

        val result = InteractiveCommand().test(
            "--config", configPath.toString(),
            "--mode", "single",
            "--agent", "echo1",
            "--no-context",
            "--save-dir", saveDir.toString(),
            "--prompt", "hello"
        )

        result.statusCode shouldBe 0
        result.stdout.trim() shouldBe "hello"

        val md = saveDir.resolve("transcript.md")
        val txt = saveDir.resolve("transcript.txt")
        md.exists() shouldBe true
        txt.exists() shouldBe true
        md.readText().shouldContain("hello")
        txt.readText().shouldContain("hello")
    }

    test("interactive --prompt (compare) returns aggregated output for multiple agents") {
        val dir = Files.createTempDirectory("cotor-interactive-compare")
        val configPath = dir.resolve("cotor.yaml")
        val saveDir = dir.resolve("out")

        configPath.writeText(
            """
            version: "1.0"
            agents:
              - name: echo1
                pluginClass: com.cotor.data.plugin.EchoPlugin
              - name: echo2
                pluginClass: com.cotor.data.plugin.EchoPlugin
            """.trimIndent()
        )

        val result = InteractiveCommand().test(
            "--config", configPath.toString(),
            "--mode", "compare",
            "--agents", "echo1,echo2",
            "--no-context",
            "--save-dir", saveDir.toString(),
            "--prompt", "ping"
        )

        result.statusCode shouldBe 0
        result.stdout.shouldContain("[echo1]")
        result.stdout.shouldContain("[echo2]")
        result.stdout.shouldContain("ping")
    }
})
