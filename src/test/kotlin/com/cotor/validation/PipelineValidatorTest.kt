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

class PipelineValidatorTest : FunSpec({

    val mockPluginLoader = mockk<PluginLoader>()
    val mockClaudePlugin = mockk<AgentPlugin>()
    val mockEchoPlugin = mockk<AgentPlugin>()

    beforeTest {
        every { mockPluginLoader.loadPlugin("com.cotor.data.plugin.ClaudePlugin") } returns mockClaudePlugin
        every { mockClaudePlugin.parameterSchema } returns AgentParameterSchema(
            parameters = listOf(
                AgentParameter("model", ParameterType.STRING, required = true),
                AgentParameter("temperature", ParameterType.NUMBER, required = false)
            )
        )
        every { mockPluginLoader.loadPlugin("com.cotor.data.plugin.EchoPlugin") } returns mockEchoPlugin
        every { mockEchoPlugin.parameterSchema } returns AgentParameterSchema(emptyList())
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
                name = "alt",
                pluginClass = "com.cotor.data.plugin.EchoPlugin"
            )
        )
        registerAgent(
            AgentConfig(
                name = "claude",
                pluginClass = "com.cotor.data.plugin.ClaudePlugin",
                parameters = mapOf("model" to "claude-2", "temperature" to "0.8")
            )
        )
    }

    test("valid DAG pipeline does not report circular dependency") {
        val pipeline = Pipeline(
            name = "dag-demo",
            executionMode = ExecutionMode.DAG,
            stages = listOf(
                PipelineStage(id = "start", agent = AgentReference("echo")),
                PipelineStage(id = "branchA", agent = AgentReference("echo"), dependencies = listOf("start")),
                PipelineStage(id = "branchB", agent = AgentReference("alt"), dependencies = listOf("start")),
                PipelineStage(id = "merge", agent = AgentReference("echo"), dependencies = listOf("branchA", "branchB"))
            )
        )

        val validator = PipelineValidator(registry, mockPluginLoader)
        val result = validator.validate(pipeline)

        result.isSuccess shouldBe true
    }

    test("detects circular dependencies") {
        val pipeline = Pipeline(
            name = "invalid-dag",
            executionMode = ExecutionMode.DAG,
            stages = listOf(
                PipelineStage(id = "a", agent = AgentReference("echo"), dependencies = listOf("c")),
                PipelineStage(id = "b", agent = AgentReference("echo"), dependencies = listOf("a")),
                PipelineStage(id = "c", agent = AgentReference("echo"), dependencies = listOf("b"))
            )
        )

        val validator = PipelineValidator(registry, mockPluginLoader)
        val result = validator.validate(pipeline) as ValidationResult.Failure

        result.errors.shouldContain("Stage 'a': Circular dependency detected")
    }

    test("dag duration estimate uses critical path") {
        val pipeline = Pipeline(
            name = "duration",
            executionMode = ExecutionMode.DAG,
            stages = listOf(
                PipelineStage(id = "start", agent = AgentReference("echo")),
                PipelineStage(id = "fast", agent = AgentReference("echo"), dependencies = listOf("start")),
                PipelineStage(id = "slow", agent = AgentReference("echo"), dependencies = listOf("start")),
                PipelineStage(id = "finish", agent = AgentReference("echo"), dependencies = listOf("slow"))
            )
        )

        val validator = PipelineValidator(registry, mockPluginLoader)
        val estimate = validator.estimateDuration(pipeline)

        // Default 30s per stage. Critical path start -> slow -> finish = 90 seconds.
        estimate.totalEstimatedSeconds shouldBe 90
    }

    test("reports error when fallback agent is missing") {
        val pipeline = Pipeline(
            name = "fallback-missing",
            stages = listOf(
                PipelineStage(
                    id = "stage",
                    agent = AgentReference("echo"),
                    input = "ok",
                    recovery = RecoveryConfig(
                        strategy = RecoveryStrategy.FALLBACK,
                        fallbackAgents = listOf("ghost-agent")
                    )
                )
            )
        )

        val validator = PipelineValidator(registry, mockPluginLoader)
        val result = validator.validate(pipeline) as ValidationResult.Failure

        result.errors.shouldContain("Stage 'stage': Fallback agent 'ghost-agent' not defined")
    }

    test("warns when fallback strategy has no fallback agents") {
        val pipeline = Pipeline(
            name = "fallback-warning",
            stages = listOf(
                PipelineStage(
                    id = "stage",
                    agent = AgentReference("echo"),
                    input = "ok",
                    recovery = RecoveryConfig(
                        strategy = RecoveryStrategy.FALLBACK,
                        fallbackAgents = emptyList()
                    )
                )
            )
        )

        val validator = PipelineValidator(registry, mockPluginLoader)
        val result = validator.validate(pipeline) as ValidationResult.Success

        result.warnings.shouldContain("Stage 'stage': Recovery strategy FALLBACK has no fallbackAgents configured")
    }

    test("reports error for invalid stage reference in input") {
        val pipeline = Pipeline(
            name = "invalid-ref",
            stages = listOf(
                PipelineStage(id = "step1", agent = AgentReference("echo")),
                PipelineStage(
                    id = "step2",
                    agent = AgentReference("echo"),
                    input = "Using \${stages.nonexistent.output}"
                )
            )
        )

        val validator = PipelineValidator(registry, mockPluginLoader)
        val result = validator.validate(pipeline) as ValidationResult.Failure

        result.errors.shouldContain("Stage 'step2': Referenced stage 'nonexistent' not found in pipeline.")
    }

    test("accepts valid stage reference in input") {
        val pipeline = Pipeline(
            name = "valid-ref",
            stages = listOf(
                PipelineStage(id = "step1", agent = AgentReference("echo")),
                PipelineStage(
                    id = "step2",
                    agent = AgentReference("echo"),
                    input = "Using \${stages.step1.output}"
                )
            )
        )

        val validator = PipelineValidator(registry, mockPluginLoader)
        val result = validator.validate(pipeline)

        result.isSuccess shouldBe true
    }

    test("reports error for invalid property access in input") {
        val pipeline = Pipeline(
            name = "invalid-property",
            stages = listOf(
                PipelineStage(id = "step1", agent = AgentReference("echo")),
                PipelineStage(
                    id = "step2",
                    agent = AgentReference("echo"),
                    input = "Using \${stages.step1.foo}"
                )
            )
        )

        val validator = PipelineValidator(registry, mockPluginLoader)
        val result = validator.validate(pipeline) as ValidationResult.Failure

        result.errors.shouldContain("Stage 'step2': Invalid property access in '\${stages.step1.foo}'. Only '.output' is supported.")
    }

    test("reports error for missing required parameter") {
        val pipeline = Pipeline(
            name = "missing-param",
            stages = listOf(
                PipelineStage(
                    id = "stage",
                    agent = AgentReference("claude")
                )
            )
        )
        // unregister the claude agent with the parameters
        registry.unregisterAgent("claude")
        registry.registerAgent(
            AgentConfig(
                name = "claude",
                pluginClass = "com.cotor.data.plugin.ClaudePlugin",
                parameters = mapOf("temperature" to "0.8")
            )
        )

        val validator = PipelineValidator(registry, mockPluginLoader)
        val result = validator.validate(pipeline) as ValidationResult.Failure

        result.errors.shouldContain("Stage 'stage': Missing required parameter 'model' for agent 'claude'")
    }
})
