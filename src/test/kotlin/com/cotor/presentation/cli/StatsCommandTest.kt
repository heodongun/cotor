package com.cotor.presentation.cli

import com.cotor.stats.PerformanceTrend
import com.cotor.stats.StatsManager
import com.cotor.stats.StatsSummary
import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.koin.dsl.module
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

class StatsCommandTest : FunSpec({

    val statsManager = mockk<StatsManager>()

    beforeSpec {
        startKoin {
            modules(module {
                single { statsManager }
            })
        }
    }

    afterSpec {
        stopKoin()
    }

    test("stats command with --output-format json returns json") {
        val statsSummary = StatsSummary(
            pipelineName = "test-pipeline",
            totalExecutions = 10,
            successRate = 90.0,
            avgDuration = 1000,
            avgRecentDuration = 900,
            lastExecuted = "2023-10-27T10:00:00Z",
            trend = PerformanceTrend.IMPROVING
        )
        every { statsManager.getStatsSummary("test-pipeline") } returns statsSummary

        val result = StatsCommand().test("--output-format", "json", "test-pipeline")

        val expectedJson = """
            {
                "pipelineName": "test-pipeline",
                "totalExecutions": 10,
                "successRate": 90.0,
                "avgDuration": 1000,
                "avgRecentDuration": 900,
                "lastExecuted": "2023-10-27T10:00:00Z",
                "trend": "IMPROVING"
            }
        """.replace(Regex("\\s"), "")

        result.stdout.replace(Regex("\\s"), "") shouldBe expectedJson
    }

    test("stats command with --output-format csv returns csv") {
        val statsSummary = StatsSummary(
            pipelineName = "test-pipeline",
            totalExecutions = 10,
            successRate = 90.0,
            avgDuration = 1000,
            avgRecentDuration = 900,
            lastExecuted = "2023-10-27T10:00:00Z",
            trend = PerformanceTrend.IMPROVING
        )
        every { statsManager.getStatsSummary("test-pipeline") } returns statsSummary

        val result = StatsCommand().test("--output-format", "csv", "test-pipeline")

        val expectedCsv = """
            Pipeline,TotalExecutions,SuccessRate,AvgDuration,AvgRecentDuration,LastExecuted,Trend
            test-pipeline,10,90.0,1000,900,2023-10-27T10:00:00Z,IMPROVING
        """.trimIndent()

        result.stdout.trim() shouldBe expectedCsv
    }
})
