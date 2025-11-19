package com.cotor.context

import com.cotor.model.AgentResult
import com.cotor.model.PipelineContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class TemplateEngineTest : FunSpec({
    val templateEngine = TemplateEngine()

    test("renders context placeholders") {
        val context = PipelineContext(
            pipelineId = "pid",
            pipelineName = "demo",
            totalStages = 2
        )
        context.addStageResult(
            "stage1",
            AgentResult(
                agentName = "echo",
                isSuccess = true,
                output = "Stage one output",
                error = null,
                duration = 0,
                metadata = emptyMap()
            )
        )
        context.sharedState["goal"] = "Ship feature"
        context.metadata["owner"] = "codex"

        val template = """
            {{context.stageResults.stage1.output}}
            Owner: {{context.metadata.owner}}
            Goal: {{context.sharedState.goal}}
        """.trimIndent()

        val rendered = templateEngine.interpolate(template, context)

        rendered.shouldContain("Stage one output")
        rendered.shouldContain("Owner: codex")
        rendered.shouldContain("Goal: Ship feature")
    }
})
