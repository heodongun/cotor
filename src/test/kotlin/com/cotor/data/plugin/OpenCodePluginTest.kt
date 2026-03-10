package com.cotor.data.plugin

import com.cotor.data.process.ProcessManager
import com.cotor.model.ExecutionContext
import com.cotor.model.ProcessExecutionException
import com.cotor.model.ProcessResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/**
 * Regression test for the OpenCode wrapper.
 *
 * The important behavior here is that a failing child process keeps its exit code
 * and captured streams when translated into a ProcessExecutionException.
 */
class OpenCodePluginTest : FunSpec({
    test("throws ProcessExecutionException with exit code and streams on failure") {
        val plugin = OpenCodePlugin()
        val processManager = object : ProcessManager {
            override suspend fun executeProcess(
                command: List<String>,
                input: String?,
                environment: Map<String, String>,
                timeout: Long,
                workingDirectory: Path?
            ): ProcessResult {
                // Assert the wrapper builds the expected argv and then simulate
                // a non-zero child process result without launching the real CLI.
                command shouldBe listOf("opencode", "generate", "hello")
                return ProcessResult(
                    exitCode = 2,
                    stdout = "partial output",
                    stderr = "cli error",
                    isSuccess = false
                )
            }
        }

        val error = shouldThrow<ProcessExecutionException> {
            plugin.execute(
                ExecutionContext(
                    // The rest of the execution context is intentionally minimal because
                    // this test only cares about error propagation from the CLI wrapper.
                    agentName = "opencode",
                    input = "hello",
                    timeout = 1_000,
                    parameters = emptyMap(),
                    environment = emptyMap()
                ),
                processManager
            )
        }

        error.message shouldBe "OpenCode execution failed"
        error.exitCode shouldBe 2
        error.stdout shouldBe "partial output"
        error.stderr shouldBe "cli error"
    }
})
