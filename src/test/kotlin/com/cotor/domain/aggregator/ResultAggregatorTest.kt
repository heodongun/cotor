package com.cotor.domain.aggregator

import com.cotor.model.AgentResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ResultAggregatorTest : FunSpec({

    val aggregator = DefaultResultAggregator()

    test("should aggregate results correctly") {
        // Given
        val results = listOf(
            AgentResult(
                agentName = "agent1",
                isSuccess = true,
                output = "output1",
                error = null,
                duration = 100,
                metadata = emptyMap()
            ),
            AgentResult(
                agentName = "agent2",
                isSuccess = true,
                output = "output2",
                error = null,
                duration = 200,
                metadata = emptyMap()
            ),
            AgentResult(
                agentName = "agent3",
                isSuccess = false,
                output = null,
                error = "failed",
                duration = 50,
                metadata = emptyMap()
            )
        )

        // When
        val aggregated = aggregator.aggregate(results)

        // Then
        aggregated.totalAgents shouldBe 3
        aggregated.successCount shouldBe 2
        aggregated.failureCount shouldBe 1
        aggregated.totalDuration shouldBe 350
        aggregated.aggregatedOutput.contains("agent1") shouldBe true
        aggregated.aggregatedOutput.contains("agent2") shouldBe true
    }

    test("should handle empty results") {
        // Given
        val results = emptyList<AgentResult>()

        // When
        val aggregated = aggregator.aggregate(results)

        // Then
        aggregated.totalAgents shouldBe 0
        aggregated.successCount shouldBe 0
        aggregated.failureCount shouldBe 0
        aggregated.totalDuration shouldBe 0
    }
})
