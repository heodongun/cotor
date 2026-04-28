package com.cotor.presentation.cli

/**
 * File overview for LintCommandTest.
 *
 * This file belongs to the test suite that keeps CLI static validation deterministic
 * and independent from user/global runtime overrides.
 */

import com.cotor.data.config.ConfigRepository
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
import io.mockk.coVerify
import io.mockk.mockk
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class LintCommandTest : FunSpec({
    val configRepository = mockk<ConfigRepository>()

    beforeSpec {
        startKoin {
            modules(
                module {
                    single { configRepository }
                }
            )
        }
    }

    afterSpec {
        stopKoin()
    }

    test("lint uses the exact config file instead of merging user override agents") {
        val root = createTempDirectory("cotor-lint-exact")
        val configPath = root.resolve("example.yaml")
        configPath.writeText("version: \"1.0\"")

        try {
            coEvery { configRepository.loadConfigExact(configPath) } returns CotorConfig(
                agents = listOf(AgentConfig(name = "echo", pluginClass = "com.cotor.data.plugin.EchoPlugin")),
                pipelines = listOf(
                    Pipeline(
                        name = "example",
                        stages = listOf(PipelineStage(id = "step", agent = AgentReference("echo"), input = "hello"))
                    )
                )
            )

            val result = LintCommand().test("-c $configPath")

            withClue(result.output) {
                result.statusCode shouldBe 0
                result.stdout shouldContain "Linting passed with no errors"
            }
            coVerify(exactly = 1) { configRepository.loadConfigExact(configPath) }
            coVerify(exactly = 0) { configRepository.loadConfig(configPath) }
        } finally {
            Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }
})
