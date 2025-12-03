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

    test("handles nested expressions and operator precedence") {
        val context = PipelineContext("pipe-3", "complex", 3)
        context.addStageResult("step1", AgentResult(agentName="a", isSuccess = true, output = null, error = null, duration = 0, metadata = mapOf("tokens" to "1500")))
        context.addStageResult("step2", AgentResult(agentName="b", isSuccess = false, output = null, error = null, duration = 0, metadata = mapOf("reason" to "timeout")))
        context.addStageResult("step3", AgentResult(agentName="c", isSuccess = true, output = "OK", error = null, duration = 0, metadata = emptyMap()))

        evaluator.evaluate("step1.success == true && (step2.success == false || step3.output == 'OK')", context) shouldBe true
        evaluator.evaluate("step1.tokens > 1000 && step2.reason == 'timeout' && step3.output == 'OK'", context) shouldBe true
        evaluator.evaluate("step1.tokens > 2000 || (step2.reason == 'timeout' && step3.output == 'OK')", context) shouldBe true
    }

    test("supports NOT operator") {
        val context = PipelineContext("pipe-4", "negation", 1)
        context.addStageResult("check", AgentResult(agentName="d", isSuccess = true, output = null, error = null, duration = 0, metadata = mapOf("is_flagged" to "false")))

        evaluator.evaluate("!check.is_flagged", context) shouldBe true
        evaluator.evaluate("!(check.is_flagged == true)", context) shouldBe true
        evaluator.evaluate("!check.success == false", context) shouldBe true
    }

    test("handles complex nested expressions") {
        val context = PipelineContext("pipe-5", "super-complex", 4)
        context.addStageResult("a", AgentResult(agentName="a", isSuccess=true, output=null, error=null, duration=0, metadata=mapOf("x" to "10")))
        context.addStageResult("b", AgentResult(agentName="b", isSuccess=false, output=null, error=null, duration=0, metadata=mapOf("y" to "20")))
        context.addStageResult("c", AgentResult(agentName="c", isSuccess=true, output="done", error=null, duration=0, metadata=emptyMap()))
        context.addStageResult("d", AgentResult(agentName="d", isSuccess=true, output=null, error=null, duration=0, metadata=mapOf("z" to "30")))

        evaluator.evaluate("(a.x > 5 && b.y == 20) || (c.output == 'done' && d.z > 25)", context) shouldBe true
        evaluator.evaluate("a.x > 5 && (b.y == 20 || (c.output == 'done' && d.z > 35))", context) shouldBe true
        evaluator.evaluate("!(a.x > 15 || b.y < 10)", context) shouldBe true
    }

    test("supports expression DSL") {
        val context = PipelineContext("pipe-6", "dsl-test", 2)
        context.addStageResult("step1", AgentResult(agentName="a", isSuccess = true, output = null, error = null, duration = 0, metadata = mapOf("tokens" to "1500")))
        context.addStageResult("step2", AgentResult(agentName="b", isSuccess = false, output = null, error = null, duration = 0, metadata = mapOf("reason" to "timeout")))

        evaluator.evaluate("success(step1) && tokens(step1) > 1000", context) shouldBe true
        evaluator.evaluate("success(step1) && tokens(step1) > 2000", context) shouldBe false
        evaluator.evaluate("!success(step2) || reason(step2) == 'timeout'", context) shouldBe true
    }
})
