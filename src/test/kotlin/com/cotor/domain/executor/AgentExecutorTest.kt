package com.cotor.domain.executor

import com.cotor.data.plugin.AgentPlugin
import com.cotor.data.plugin.PluginLoader
import com.cotor.data.process.ProcessManager
import com.cotor.model.AgentConfig
import com.cotor.model.DataFormat
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
        coEvery { mockPlugin.execute(any(), any()) } returns "success output"
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
        coEvery { mockPlugin.execute(any(), any()) } coAnswers {
            delay(1000)
            "never returned"
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
})
