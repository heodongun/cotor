package com.cotor.presentation.cli

import com.cotor.data.config.ConfigRepository
import com.cotor.data.registry.AgentRegistry
import com.cotor.model.AgentConfig
import com.cotor.model.CotorConfig
import com.cotor.stats.PerformanceTrend
import com.cotor.stats.StatsManager
import com.cotor.stats.StatsSummary
import com.github.ajalt.clikt.testing.test
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class CliGoldenSnapshotTest : FunSpec({
    val updateGolden = System.getenv("UPDATE_GOLDEN") == "1"

    lateinit var configRepository: ConfigRepository
    lateinit var agentRegistry: AgentRegistry
    lateinit var statsManager: StatsManager

    beforeSpec {
        configRepository = mockk()
        agentRegistry = mockk()
        statsManager = mockk()

        startKoin {
            modules(
                module {
                    single { configRepository }
                    single { agentRegistry }
                    single { statsManager }
                }
            )
        }
    }

    afterSpec {
        stopKoin()
    }

    test("golden snapshot: list") {
        val cotorConfig = CotorConfig(
            agents = listOf(
                AgentConfig(
                    name = "claude",
                    pluginClass = "com.cotor.data.plugin.ClaudePlugin",
                    timeout = 60000,
                    tags = listOf("primary", "reasoning")
                ),
                AgentConfig(
                    name = "gemini",
                    pluginClass = "com.cotor.data.plugin.GeminiPlugin",
                    timeout = 45000,
                    tags = listOf("secondary")
                )
            )
        )

        coEvery { configRepository.loadConfig(any()) } returns cotorConfig
        every { agentRegistry.registerAgent(any()) } just runs
        every { agentRegistry.getAllAgents() } returns cotorConfig.agents

        val result = ListCommand().test(emptyList())

        assertSnapshot(
            name = "list",
            content = normalize(result.stdout),
            updateGolden = updateGolden
        )
    }

    test("golden snapshot: stats") {
        every { statsManager.listAllStats() } returns listOf(
            StatsSummary(
                pipelineName = "compare-solutions",
                totalExecutions = 12,
                successRate = 91.7,
                avgDuration = 2350,
                avgRecentDuration = 2100,
                lastExecuted = "2026-02-28T09:30:00Z",
                trend = PerformanceTrend.IMPROVING
            )
        )

        val result = StatsCommand().test(emptyList())

        assertSnapshot(
            name = "stats",
            content = normalize(result.stdout),
            updateGolden = updateGolden
        )
    }

    test("golden snapshot: doctor") {
        val projectRoot = Files.createTempDirectory("doctor-snapshot")
        createDoctorFixture(projectRoot)

        val recorder = TerminalRecorder()
        val terminal = Terminal(recorder)

        DoctorCommand(
            terminal = terminal,
            projectRootProvider = { projectRoot },
            javaVersionProvider = { "21.0.2" },
            commandAvailable = { it == "cotor" }
        ).test(emptyList())

        assertSnapshot(
            name = "doctor",
            content = normalize(recorder.output()),
            updateGolden = updateGolden
        )
    }

    test("golden snapshot: template --list") {
        val recorder = TerminalRecorder()
        val terminal = Terminal(recorder)

        TemplateCommand(terminal = terminal).test("--list")

        assertSnapshot(
            name = "template-list",
            content = normalize(recorder.output()),
            updateGolden = updateGolden
        )
    }
})

private fun createDoctorFixture(projectRoot: Path) {
    projectRoot.resolve("build/libs").createDirectories()
    projectRoot.resolve("build/libs/cotor-1.0.0-all.jar").writeText("jar")
    projectRoot.resolve("cotor.yaml").writeText("version: \"1.0\"")

    projectRoot.resolve("examples").createDirectories()
    projectRoot.resolve("examples/single-agent.yaml").writeText("single")
    projectRoot.resolve("examples/parallel-compare.yaml").writeText("parallel")
    projectRoot.resolve("examples/decision-loop.yaml").writeText("loop")
    projectRoot.resolve("examples/run-examples.sh").writeText("#!/usr/bin/env bash")
}

private fun normalize(value: String): String = value
    .replace("\r\n", "\n")
    .trimEnd() + "\n"

private fun assertSnapshot(name: String, content: String, updateGolden: Boolean) {
    val snapshotPath = Paths.get("src/test/resources/snapshots/cli/$name.txt")

    if (updateGolden || !Files.exists(snapshotPath)) {
        snapshotPath.parent?.createDirectories()
        snapshotPath.writeText(content)
    }

    Files.readString(snapshotPath) shouldBe content
}
