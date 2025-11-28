package com.cotor.stats

import com.cotor.model.AggregatedResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Instant

class StatsManagerTest : FunSpec({

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

        manager.recordExecution("pipeline-a", result)
        manager.loadStats("pipeline-a").shouldNotBeNull()

        manager.clearStats("pipeline-a") shouldBe true
        manager.loadStats("pipeline-a").shouldBeNull()
    }
})
