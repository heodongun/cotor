package com.cotor.validation

import com.cotor.model.AgentConfig
import com.cotor.model.AgentReference
import com.cotor.model.CotorConfig
import com.cotor.model.Pipeline
import com.cotor.model.PipelineStage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class LinterTest : FunSpec({
    lateinit var linter: Linter

    beforeEach {
        linter = Linter()
    }

    test("lint should return no errors or warnings for a valid config") {
        val config = CotorConfig(
            agents = listOf(AgentConfig(name = "agent1", pluginClass = "dummy")),
            pipelines = listOf(
                Pipeline(
                    name = "pipeline1",
                    stages = listOf(PipelineStage(id = "stage1", agent = AgentReference(name = "agent1")))
                )
            )
        )

        val result = linter.lint(config)

        result.isSuccess shouldBe true
        result.errors.shouldBeEmpty()
        result.warnings.shouldBeEmpty()
    }

    test("lint should detect duplicate agent names") {
        val config = CotorConfig(
            agents = listOf(
                AgentConfig(name = "agent1", pluginClass = "dummy"),
                AgentConfig(name = "agent1", pluginClass = "dummy")
            )
        )

        val result = linter.lint(config)

        result.isSuccess shouldBe false
        result.errors shouldContainExactly listOf("Duplicate agent name found: 'agent1'")
    }

    test("lint should detect unused agents") {
        val config = CotorConfig(
            agents = listOf(
                AgentConfig(name = "agent1", pluginClass = "dummy"),
                AgentConfig(name = "unused-agent", pluginClass = "dummy")
            ),
            pipelines = listOf(
                Pipeline(
                    name = "pipeline1",
                    stages = listOf(PipelineStage(id = "stage1", agent = AgentReference(name = "agent1")))
                )
            )
        )

        val result = linter.lint(config)

        result.isSuccess shouldBe true
        result.warnings shouldContainExactly listOf("Unused agent definition: 'unused-agent'")
    }

    test("lint should detect duplicate stage IDs in a pipeline") {
        val config = CotorConfig(
            agents = listOf(AgentConfig(name = "agent1", pluginClass = "dummy")),
            pipelines = listOf(
                Pipeline(
                    name = "pipeline1",
                    stages = listOf(
                        PipelineStage(id = "stage1", agent = AgentReference(name = "agent1")),
                        PipelineStage(id = "stage1", agent = AgentReference(name = "agent1"))
                    )
                )
            )
        )

        val result = linter.lint(config)

        result.isSuccess shouldBe false
        result.errors shouldContainExactly listOf("Duplicate stage ID 'stage1' found in pipeline 'pipeline1'")
    }

    test("lint should detect undefined agent references in a pipeline") {
        val config = CotorConfig(
            agents = listOf(AgentConfig(name = "agent1", pluginClass = "dummy")),
            pipelines = listOf(
                Pipeline(
                    name = "pipeline1",
                    stages = listOf(PipelineStage(id = "stage1", agent = AgentReference(name = "undefined-agent")))
                )
            )
        )

        val result = linter.lint(config)

        result.isSuccess shouldBe false
        result.errors shouldContainExactly listOf("Stage 'stage1' in pipeline 'pipeline1' refers to an undefined agent: 'undefined-agent'")
    }

    test("lint should handle multiple errors and warnings correctly") {
        val config = CotorConfig(
            agents = listOf(
                AgentConfig(name = "agent1", pluginClass = "dummy"),
                AgentConfig(name = "agent1", pluginClass = "dummy"),
                AgentConfig(name = "unused-agent", pluginClass = "dummy")
            ),
            pipelines = listOf(
                Pipeline(
                    name = "pipeline1",
                    stages = listOf(
                        PipelineStage(id = "stage1", agent = AgentReference(name = "agent1")),
                        PipelineStage(id = "stage1", agent = AgentReference(name = "undefined-agent"))
                    )
                )
            )
        )

        val result = linter.lint(config)

        result.isSuccess shouldBe false
        result.errors.sorted() shouldBe listOf(
            "Duplicate agent name found: 'agent1'",
            "Duplicate stage ID 'stage1' found in pipeline 'pipeline1'",
            "Stage 'stage1' in pipeline 'pipeline1' refers to an undefined agent: 'undefined-agent'"
        ).sorted()
        result.warnings shouldContainExactly listOf("Unused agent definition: 'unused-agent'")
    }
})
