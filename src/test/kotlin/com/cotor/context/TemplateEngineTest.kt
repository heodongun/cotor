package com.cotor.context

import com.cotor.model.AgentResult
import com.cotor.model.PipelineContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TemplateEngineTest {

    private lateinit var templateEngine: TemplateEngine
    private lateinit var pipelineContext: PipelineContext

    @BeforeEach
    fun setUp() {
        templateEngine = TemplateEngine()
        pipelineContext = mockk<PipelineContext>(relaxed = true)

        // Mock basic context properties
        every { pipelineContext.pipelineName } returns "test-pipeline"
        every { pipelineContext.pipelineId } returns "pipeline-123"

        // Mock stage results
        every { pipelineContext.getStageOutput("step1") } returns "output from step1"
        every { pipelineContext.getStageOutput("nonexistent") } returns null

        // Mock shared state
        every { pipelineContext.sharedState } returns mutableMapOf("user" to "jules")
    }

    @Test
    fun `should interpolate stage output expression`() {
        val template = "Input is \${stages.step1.output}"
        val expected = "Input is output from step1"
        val actual = templateEngine.interpolate(template, pipelineContext)
        assertEquals(expected, actual)
    }

    @Test
    fun `should handle nonexistent stage output`() {
        val template = "Input is \${stages.nonexistent.output}"
        val expected = "Input is [stage:nonexistent output not found]"
        val actual = templateEngine.interpolate(template, pipelineContext)
        assertEquals(expected, actual)
    }

    @Test
    fun `should interpolate pipeline name`() {
        val template = "Running \${pipeline.name}"
        val expected = "Running test-pipeline"
        val actual = templateEngine.interpolate(template, pipelineContext)
        assertEquals(expected, actual)
    }

    @Test
    fun `should interpolate environment variables`() {
        // This test relies on the underlying environment where the test is run.
        // Let's assume USER is a common variable.
        val user = System.getenv("USER") ?: "unknown"
        val template = "Running as user: \${env.USER}"
        val expected = "Running as user: $user"
        val actual = templateEngine.interpolate(template, pipelineContext)
        assertEquals(expected, actual)
    }

    @Test
    fun `should handle multiple expressions`() {
        val template = "Pipeline \${pipeline.name} got \${stages.step1.output}"
        val expected = "Pipeline test-pipeline got output from step1"
        val actual = templateEngine.interpolate(template, pipelineContext)
        assertEquals(expected, actual)
    }

    @Test
    fun `should handle invalid expressions gracefully`() {
        val template = "This is an \${invalid.expression}"
        val expected = "This is an [unknown expression: \${invalid.expression}]"
        val actual = templateEngine.interpolate(template, pipelineContext)
        assertEquals(expected, actual)
    }

    @Test
    fun `should maintain legacy mustache syntax for shared state`() {
        val template = "User is {{ context.sharedState.user }}"
        val expected = "User is jules"
        val actual = templateEngine.interpolate(template, pipelineContext)
        assertEquals(expected, actual)
    }
}
