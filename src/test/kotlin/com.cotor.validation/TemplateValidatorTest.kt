package com.cotor.validation

import com.cotor.context.TemplateEngine
import com.cotor.model.AgentReference
import com.cotor.model.Pipeline
import com.cotor.model.PipelineStage
import com.cotor.model.ValidationResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
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

        val validator = PipelineTemplateValidator(TemplateEngine())
        val result = validator.validate(pipeline)

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

        val validator = PipelineTemplateValidator(TemplateEngine())
        val result = validator.validate(pipeline)

        result.shouldBeInstanceOf<ValidationResult.Failure>()
        (result as ValidationResult.Failure).errors shouldContainExactly listOf("Invalid stage reference in stage 'step2': Stage 'nonexistent' not found in pipeline.")
    }
})
