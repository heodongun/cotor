package com.cotor.data.plugin

/**
 * File overview for CodexPluginTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around codex plugin test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import com.cotor.data.process.ProcessManager
import com.cotor.model.AgentExecutionException
import com.cotor.model.ExecutionContext
import com.cotor.model.ProcessResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class CodexPluginTest : FunSpec({
    test("treats codex runs with a captured final message as successful even when stderr makes the exit code non-zero") {
        val plugin = CodexPlugin()
        val processManager = object : ProcessManager {
            override suspend fun executeProcess(
                command: List<String>,
                input: String?,
                environment: Map<String, String>,
                timeout: Long,
                workingDirectory: Path?,
                onStart: ((Long) -> Unit)?
            ): ProcessResult {
                command.take(6) shouldBe listOf("codex", "exec", "--skip-git-repo-check", "--full-auto", "--color", "never")
                val outputIndex = command.indexOf("--output-last-message")
                outputIndex shouldBe 6
                val outputFile = Path.of(command[outputIndex + 1])
                Files.writeString(outputFile, "final assistant message")
                return ProcessResult(
                    exitCode = 1,
                    stdout = "",
                    stderr = "MCP auth warning",
                    isSuccess = false,
                    processId = 1234L
                )
            }
        }

        val result = plugin.execute(
            ExecutionContext(
                agentName = "codex",
                input = "hello",
                timeout = 1_000,
                parameters = emptyMap(),
                environment = emptyMap()
            ),
            processManager
        )

        result.output shouldBe "final assistant message"
        result.processId shouldBe 1234L
    }

    test("fails when codex returns non-zero and no final assistant message was captured") {
        val plugin = CodexPlugin()
        val processManager = object : ProcessManager {
            override suspend fun executeProcess(
                command: List<String>,
                input: String?,
                environment: Map<String, String>,
                timeout: Long,
                workingDirectory: Path?,
                onStart: ((Long) -> Unit)?
            ): ProcessResult {
                val outputIndex = command.indexOf("--output-last-message")
                val outputFile = Path.of(command[outputIndex + 1])
                Files.writeString(outputFile, "")
                return ProcessResult(
                    exitCode = 1,
                    stdout = "",
                    stderr = "real failure",
                    isSuccess = false
                )
            }
        }

        val error = shouldThrow<AgentExecutionException> {
            plugin.execute(
                ExecutionContext(
                    agentName = "codex",
                    input = "hello",
                    timeout = 1_000,
                    parameters = emptyMap(),
                    environment = emptyMap()
                ),
                processManager
            )
        }

        error.message shouldBe "Codex execution failed: real failure"
    }
})
