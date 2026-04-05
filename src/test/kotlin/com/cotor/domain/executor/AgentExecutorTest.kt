package com.cotor.domain.executor

/**
 * File overview for AgentExecutorTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around agent executor test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.data.plugin.AgentPlugin
import com.cotor.data.plugin.PluginLoader
import com.cotor.data.process.ProcessManager
import com.cotor.model.AgentConfig
import com.cotor.model.ProcessExecutionException
import com.cotor.model.PluginExecutionOutput
import com.cotor.security.SecurityValidator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.delay
import org.slf4j.Logger

/**
 * Executor tests focus on orchestration behavior, not on any real external tool.
 *
 * Every dependency is mocked so the suite can verify validation, timeout, and result
 * mapping without spawning child processes.
 */
class AgentExecutorTest : FunSpec({

    val mockProcessManager = mockk<ProcessManager>()
    val mockPluginLoader = mockk<PluginLoader>()
    val mockSecurityValidator = mockk<SecurityValidator>()
    val mockLogger = mockk<Logger>(relaxed = true)

    val executor = DefaultAgentExecutor(
        mockProcessManager,
        mockPluginLoader,
        mockSecurityValidator,
        mockLogger
    )

    test("should execute agent successfully") {
        // Given
        val agentConfig = AgentConfig(
            name = "test-agent",
            pluginClass = "com.example.TestPlugin",
            timeout = 5000
        )

        val mockPlugin = mockk<AgentPlugin>()
        // The plugin returns the new structured output type so the executor can carry
        // both human-readable output and runtime metadata if present.
        coEvery { mockPlugin.execute(any(), any()) } returns PluginExecutionOutput("success output")
        every { mockPlugin.validateInput(any()) } returns com.cotor.model.ValidationResult.Success
        every { mockPluginLoader.loadPlugin(any()) } returns mockPlugin
        every { mockSecurityValidator.validate(any()) } just runs

        // When
        val result = executor.executeAgent(agentConfig, "test input")

        // Then
        result.isSuccess shouldBe true
        result.output shouldBe "success output"
        result.agentName shouldBe "test-agent"
    }

    test("should handle agent timeout") {
        // Given
        val agentConfig = AgentConfig(
            name = "slow-agent",
            pluginClass = "com.example.SlowPlugin",
            timeout = 100
        )

        val mockPlugin = mockk<AgentPlugin>()
        // Delay long enough to exceed the agent timeout and exercise the executor's
        // timeout wrapper rather than plugin-specific error handling.
        coEvery { mockPlugin.execute(any(), any()) } coAnswers {
            delay(1000)
            PluginExecutionOutput("never returned")
        }
        every { mockPlugin.validateInput(any()) } returns com.cotor.model.ValidationResult.Success
        every { mockPluginLoader.loadPlugin(any()) } returns mockPlugin
        every { mockSecurityValidator.validate(any()) } just runs

        // When
        val result = executor.executeAgent(agentConfig, null)

        // Then
        result.isSuccess shouldBe false
        result.error?.contains("timeout") shouldBe true
    }

    test("should handle validation failure") {
        // Given
        val agentConfig = AgentConfig(
            name = "test-agent",
            pluginClass = "com.example.TestPlugin",
            timeout = 5000
        )

        val mockPlugin = mockk<AgentPlugin>()
        // Validation failure should short-circuit before the executor tries to run anything.
        every { mockPlugin.validateInput(any()) } returns com.cotor.model.ValidationResult.Failure(
            listOf("Input is invalid")
        )
        every { mockPluginLoader.loadPlugin(any()) } returns mockPlugin
        every { mockSecurityValidator.validate(any()) } just runs

        // When
        val result = executor.executeAgent(agentConfig, "invalid input")

        // Then
        result.isSuccess shouldBe false
        result.error?.contains("validation") shouldBe true
    }

    test("should surface stdout when a process failure has no stderr") {
        val agentConfig = AgentConfig(
            name = "opencode",
            pluginClass = "com.example.ProcessPlugin",
            timeout = 5000
        )

        val mockPlugin = mockk<AgentPlugin>()
        coEvery { mockPlugin.execute(any(), any()) } throws ProcessExecutionException(
            message = "OpenCode execution failed",
            exitCode = 1,
            stdout = "{\"type\":\"error\",\"error\":{\"name\":\"UnknownError\",\"data\":{\"message\":\"Error: [DecimalError] Invalid argument: [object Object]\"}}}",
            stderr = ""
        )
        every { mockPlugin.validateInput(any()) } returns com.cotor.model.ValidationResult.Success
        every { mockPluginLoader.loadPlugin(any()) } returns mockPlugin
        every { mockSecurityValidator.validate(any()) } just runs

        val result = executor.executeAgent(agentConfig, "test input")

        result.isSuccess shouldBe false
        result.output shouldBe "{\"type\":\"error\",\"error\":{\"name\":\"UnknownError\",\"data\":{\"message\":\"Error: [DecimalError] Invalid argument: [object Object]\"}}}"
        result.error shouldBe "OpenCode execution failed (exit=1): {\"type\":\"error\",\"error\":{\"name\":\"UnknownError\",\"data\":{\"message\":\"Error: [DecimalError] Invalid argument: [object Object]\"}}}"
    }
})
