package com.cotor.domain.condition

import com.cotor.model.AgentResult
import com.cotor.model.PipelineContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ConditionEvaluatorTest : FunSpec({

    val evaluator = ConditionEvaluator()

    test("evaluates numeric comparisons against stage metadata") {
        val context = PipelineContext(
            pipelineId = "pipe-1",
            pipelineName = "quality",
            totalStages = 1
        )

        context.addStageResult(
            "quality-check",
            AgentResult(
                agentName = "qa",
                isSuccess = true,
                output = "score: 65",
                error = null,
                duration = 10,
                metadata = mapOf("qualityScore" to "65")
            )
        )

        evaluator.evaluate("quality-check.qualityScore >= 60", context) shouldBe true
        evaluator.evaluate("quality-check.qualityScore >= 80", context) shouldBe false
    }

    test("supports logical expressions with shared state and outputs") {
        val context = PipelineContext(
            pipelineId = "pipe-2",
            pipelineName = "review",
            totalStages = 2
        )
        context.sharedState["status"] = "READY"
        context.addStageResult(
            "review",
            AgentResult(
                agentName = "qa",
                isSuccess = true,
                output = "PASS",
                error = null,
                duration = 5,
                metadata = mapOf("severity" to "LOW")
            )
        )

        evaluator.evaluate(
            "context.sharedState.status == 'READY' && review.severity == 'LOW'",
            context
        ) shouldBe true

        evaluator.evaluate(
            "review.output contains 'PASS' || review.severity == 'HIGH'",
            context
        ) shouldBe true
    }
})
