package com.cotor.domain.aggregator

import com.cotor.analysis.ResultAnalyzer
import com.cotor.model.AgentResult
import com.cotor.model.ResultAnalysis
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ResultAggregatorTest : FunSpec({

    val stubAnalysis = ResultAnalysis(
        hasConsensus = true,
        consensusScore = 0.9,
        bestAgent = "claude",
        bestSummary = "ok",
        disagreements = emptyList(),
        recommendations = emptyList()
    )

    val analyzer = object : ResultAnalyzer {
        override fun analyze(results: List<AgentResult>) = stubAnalysis
    }

    val aggregator = DefaultResultAggregator(analyzer)

    test("includes analysis summary in aggregated result") {
        val aggregated = aggregator.aggregate(
            listOf(
                AgentResult("claude", true, "a", null, 10, emptyMap()),
                AgentResult("gemini", false, null, "err", 10, emptyMap())
            )
        )

        aggregated.analysis shouldBe stubAnalysis
        aggregated.successCount shouldBe 1
        aggregated.failureCount shouldBe 1
    }
})
