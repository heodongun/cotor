package com.cotor.validation

import com.cotor.context.TemplateEngine
import com.cotor.model.*
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class TemplateValidatorTest : StringSpec({

    "should pass validation for a valid pipeline" {
        val pipeline = Pipeline(
            name = "test-pipeline",
            stages = listOf(
                PipelineStage(id = "step1", agent = AgentReference("agent1"), input = "input"),
                PipelineStage(id = "step2", agent = AgentReference("agent2"), input = "\${stages.step1.output}")
            )
        )
        val context = PipelineContext("test", "test", 2)
        context.addStageResult("step1", AgentResult("agent1", true, "output", null, 0, emptyMap()))

        val validator = PipelineTemplateValidator(TemplateEngine())
        val result = validator.validate(pipeline, context)

        result.shouldBeInstanceOf<ValidationResult.Success>()
    }

    "should fail validation for a pipeline with an invalid reference" {
        val pipeline = Pipeline(
            name = "test-pipeline",
            stages = listOf(
                PipelineStage(id = "step1", agent = AgentReference("agent1"), input = "input"),
                PipelineStage(id = "step2", agent = AgentReference("agent2"), input = "\${stages.nonexistent.output}")
            )
        )
        val context = PipelineContext("test", "test", 2)
        context.addStageResult("step1", AgentResult("agent1", true, "output", null, 0, emptyMap()))

        val validator = PipelineTemplateValidator(TemplateEngine())
        val result = validator.validate(pipeline, context)

        result.shouldBeInstanceOf<ValidationResult.Failure>()
        (result as ValidationResult.Failure).errors shouldContainExactly listOf("[stage 'nonexistent' not found or not yet executed in expression: \${stages.nonexistent.output}]")
    }
})
