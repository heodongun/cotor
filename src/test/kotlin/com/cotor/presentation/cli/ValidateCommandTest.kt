package com.cotor.presentation.cli

/**
 * File overview for ValidateCommandTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around validate/run pipeline resolution so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.data.config.ConfigRepository
import com.cotor.data.registry.AgentRegistry
import com.cotor.data.registry.InMemoryAgentRegistry
import com.cotor.model.AgentConfig
import com.cotor.model.AgentReference
import com.cotor.model.CotorConfig
import com.cotor.model.Pipeline
import com.cotor.model.PipelineStage
import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class ValidateCommandTest : FunSpec({
    val configRepository = mockk<ConfigRepository>()
    val agentRegistry = InMemoryAgentRegistry()

    beforeSpec {
        startKoin {
            modules(
                module {
                    single { configRepository }
                    single<AgentRegistry> { agentRegistry }
                }
            )
        }
    }

    afterSpec {
        stopKoin()
    }

    test("validate defaults to the only configured pipeline when no pipeline argument is supplied") {
        val root = createTempDirectory("cotor-validate-default")
        val configPath = root.resolve("cotor.yaml")
        configPath.writeText("version: \"1.0\"")
        val config = singlePipelineConfig()

        try {
            coEvery { configRepository.loadConfig(configPath) } returns config

            val result = ValidateCommand().test("-c $configPath")

            withClue(result.output) {
                result.statusCode shouldBe 0
                result.stdout shouldContain "Pipeline structure: valid"
            }
        } finally {
            Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    test("run dry-run defaults to the only configured pipeline when no pipeline argument is supplied") {
        val root = createTempDirectory("cotor-run-default")
        val configPath = root.resolve("cotor.yaml")
        configPath.writeText("version: \"1.0\"")
        val config = singlePipelineConfig()

        try {
            coEvery { configRepository.loadConfig(configPath) } returns config

            val result = EnhancedRunCommand().test("--dry-run -c $configPath")

            withClue(result.output) {
                result.statusCode shouldBe 0
                result.stdout shouldContain "Pipeline Estimate: single-pipeline"
            }
        } finally {
            Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }
})

private fun singlePipelineConfig(): CotorConfig = CotorConfig(
    agents = listOf(
        AgentConfig(
            name = "example-agent",
            pluginClass = "com.cotor.data.plugin.EchoPlugin"
        )
    ),
    pipelines = listOf(
        Pipeline(
            name = "single-pipeline",
            stages = listOf(
                PipelineStage(
                    id = "brief",
                    agent = AgentReference("example-agent"),
                    input = "Summarize the work."
                )
            )
        )
    )
)
