package com.cotor.analysis

import com.cotor.model.AgentResult
import com.cotor.model.ResultAnalysis
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class DefaultResultAnalyzerTest : FunSpec({

    val analyzer = DefaultResultAnalyzer()

    fun result(agent: String, output: String, metadata: Map<String, String> = emptyMap()) = AgentResult(
        agentName = agent,
        isSuccess = true,
        output = output,
        error = null,
        duration = 10,
        metadata = metadata
    )

    test("detects consensus and best agent using validation score") {
        val analysis = analyzer.analyze(
            listOf(
                result("claude", "Implemented API with pagination and validation", mapOf("validationScore" to "0.92")),
                result("gemini", "Implemented API with pagination & validation checks", mapOf("validationScore" to "0.85")),
                result("copilot", "Implemented API with pagination and validation logic", mapOf("validationScore" to "0.80"))
            )
        )!!

        analysis.hasConsensus.shouldBeTrue()
        analysis.bestAgent shouldBe "claude"
        analysis.bestSummary!!.contains("pagination").shouldBeTrue()
        analysis.disagreements shouldBe emptyList()
    }

    test("flags disagreements and produces recommendations") {
        val analysis = analyzer.analyze(
            listOf(
                result("claude", "Generate Kotlin data class with serialization"),
                result("gemini", "Write end-to-end Cypress tests for cart workflow")
            )
        )!!

        analysis.hasConsensus shouldBe false
        analysis.disagreements.shouldHaveSize(1)
        analysis.recommendations.shouldHaveSize(1)
    }
})
