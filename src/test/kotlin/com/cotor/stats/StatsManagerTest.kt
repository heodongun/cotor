package com.cotor.stats

import com.cotor.model.AggregatedResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Instant
import io.kotest.matchers.doubles.plusOrMinus

class StatsManagerTest : FunSpec({

    test("getStatsDetails calculates correct stage-level statistics") {
        val dir = Files.createTempDirectory("stats-test")
        val manager = StatsManager(dir.toString())
        val result = AggregatedResult(1, 1, 0, 1000, emptyList(), "output", Instant.now())
        val stages1 = listOf(
            StageExecution("build", 500, ExecutionStatus.SUCCESS, 0),
            StageExecution("test", 500, ExecutionStatus.SUCCESS, 1)
        )
        val stages2 = listOf(
            StageExecution("build", 700, ExecutionStatus.SUCCESS, 0),
            StageExecution("test", 300, ExecutionStatus.FAILURE, 0)
        )

        manager.recordExecution("pipeline-a", result, stages1)
        manager.recordExecution("pipeline-a", result.copy(failureCount = 1, successCount = 0), stages2)

        val details = manager.getStatsDetails("pipeline-a")
        details.shouldNotBeNull()
        details.pipelineName shouldBe "pipeline-a"
        details.totalExecutions shouldBe 2
        details.stages.size shouldBe 2

        val buildStats = details.stages.first { it.stageName == "build" }
        buildStats.avgDuration shouldBe 600L
        buildStats.successRate shouldBe 100.0
        buildStats.avgRetries shouldBe 0.0

        val testStats = details.stages.first { it.stageName == "test" }
        testStats.avgDuration shouldBe 400L
        testStats.successRate shouldBe 50.0
        testStats.avgRetries.shouldBe(0.5 plusOrMinus 0.01)
    }

    test("getStatsDetails handles stages with retries") {
        val dir = Files.createTempDirectory("stats-test-retries")
        val manager = StatsManager(dir.toString())
        val result = AggregatedResult(1, 1, 0, 1000, emptyList(), "output", Instant.now())
        val stages = listOf(
            StageExecution("flaky-stage", 200, ExecutionStatus.SUCCESS, 2),
            StageExecution("flaky-stage", 300, ExecutionStatus.FAILURE, 3)
        )
        manager.recordExecution("retry-pipeline", result, stages)
        val details = manager.getStatsDetails("retry-pipeline")
        details.shouldNotBeNull()
        val flakyStats = details.stages.first { it.stageName == "flaky-stage" }
        flakyStats.avgRetries.shouldBe(2.5 plusOrMinus 0.01)
    }

    test("clearStats removes stored statistics") {
        val dir = Files.createTempDirectory("stats-test")
        val manager = StatsManager(dir.toString())
        val result = AggregatedResult(
            totalAgents = 1,
            successCount = 1,
            failureCount = 0,
            totalDuration = 500,
            results = emptyList(),
            aggregatedOutput = "done",
            timestamp = Instant.now()
        )

        manager.recordExecution("pipeline-a", result, emptyList())
        manager.loadStats("pipeline-a").shouldNotBeNull()

        manager.clearStats("pipeline-a") shouldBe true
        manager.loadStats("pipeline-a").shouldBeNull()
    }
})
