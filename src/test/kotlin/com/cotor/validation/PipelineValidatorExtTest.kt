package com.cotor.validation

import com.cotor.data.plugin.AgentPlugin
import com.cotor.data.plugin.PluginLoader
import com.cotor.data.registry.InMemoryAgentRegistry
import com.cotor.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class PipelineValidatorExtTest : FunSpec({

    val mockPluginLoader = mockk<PluginLoader>()
    val mockEchoPlugin = mockk<AgentPlugin>()

    beforeTest {
        every { mockPluginLoader.loadPlugin("com.cotor.data.plugin.EchoPlugin") } returns mockEchoPlugin
        every { mockEchoPlugin.parameterSchema } returns AgentParameterSchema(emptyList())
        every { mockPluginLoader.loadPlugin("com.cotor.data.plugin.UnknownPlugin") } throws ClassNotFoundException("Plugin not found")
    }

    val registry = InMemoryAgentRegistry().apply {
        registerAgent(
            AgentConfig(
                name = "echo",
                pluginClass = "com.cotor.data.plugin.EchoPlugin"
            )
        )
        registerAgent(
            AgentConfig(
                name = "broken",
                pluginClass = "com.cotor.data.plugin.UnknownPlugin"
            )
        )
    }

    test("reports error for undefined dependency in DAG") {
        val pipeline = Pipeline(
            name = "undefined-dep",
            executionMode = ExecutionMode.DAG,
            stages = listOf(
                PipelineStage(id = "a", agent = AgentReference("echo"), dependencies = listOf("non-existent"))
            )
        )

        val validator = PipelineValidator(registry, mockPluginLoader)
        val result = validator.validate(pipeline) as ValidationResult.Failure

        result.errors.shouldContain("Stage 'a': Dependency 'non-existent' not found")
    }

    test("reports error for self-referencing dependency") {
        val pipeline = Pipeline(
            name = "self-ref",
            executionMode = ExecutionMode.DAG,
            stages = listOf(
                PipelineStage(id = "a", agent = AgentReference("echo"), dependencies = listOf("a"))
            )
        )

        val validator = PipelineValidator(registry, mockPluginLoader)
        val result = validator.validate(pipeline) as ValidationResult.Failure

        result.errors.shouldContain("Stage 'a': Circular dependency detected")
    }

    test("reports error for unknown agent reference") {
        val pipeline = Pipeline(
            name = "unknown-agent",
            stages = listOf(
                PipelineStage(id = "step1", agent = AgentReference("ghost"))
            )
        )

        val validator = PipelineValidator(registry, mockPluginLoader)
        val result = validator.validate(pipeline) as ValidationResult.Failure

        result.errors.shouldContain("Stage 'step1': Agent 'ghost' not defined")
    }

    test("reports error when agent plugin cannot be loaded") {
        val pipeline = Pipeline(
            name = "broken-plugin",
            stages = listOf(
                PipelineStage(id = "step1", agent = AgentReference("broken"))
            )
        )

        val validator = PipelineValidator(registry, mockPluginLoader)
        val result = validator.validate(pipeline) as ValidationResult.Failure

        result.errors.shouldContain("Stage 'step1': Could not load plugin for agent 'broken'")
    }

    test("reports error for duplicate stage IDs") {
        val pipeline = Pipeline(
            name = "duplicate-ids",
            stages = listOf(
                PipelineStage(id = "step1", agent = AgentReference("echo")),
                PipelineStage(id = "step1", agent = AgentReference("echo"))
            )
        )

        val validator = PipelineValidator(registry, mockPluginLoader)
        val result = validator.validate(pipeline) as ValidationResult.Failure

        result.errors.shouldContain("Duplicate stage IDs found: step1")
    }
})
