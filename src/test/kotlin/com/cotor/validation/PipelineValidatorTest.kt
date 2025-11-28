package com.cotor.validation

import com.cotor.data.registry.InMemoryAgentRegistry
import com.cotor.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class PipelineValidatorTest : FunSpec({

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

        val validator = PipelineValidator(registry)
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

        val validator = PipelineValidator(registry)
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

        val validator = PipelineValidator(registry)
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

        val validator = PipelineValidator(registry)
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

        val validator = PipelineValidator(registry)
        val result = validator.validate(pipeline) as ValidationResult.Success

        result.warnings.shouldContain("Stage 'stage': Recovery strategy FALLBACK has no fallbackAgents configured")
    }
})
