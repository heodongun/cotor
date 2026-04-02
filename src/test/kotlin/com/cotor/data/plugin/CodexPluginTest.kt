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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
                command.take(8) shouldBe listOf(
                    "codex",
                    "exec",
                    "--skip-git-repo-check",
                    "--full-auto",
                    "--color",
                    "never",
                    "-c",
                    """model_reasoning_effort="high""""
                )
                val outputIndex = command.indexOf("--output-last-message")
                outputIndex shouldBe 8
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

    test("normalizes unsupported codex reasoning effort values before invoking the CLI") {
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
                command shouldContain "-c"
                command shouldContain """model_reasoning_effort="high""""
                val outputIndex = command.indexOf("--output-last-message")
                Files.writeString(Path.of(command[outputIndex + 1]), "normalized")
                return ProcessResult(
                    exitCode = 0,
                    stdout = "",
                    stderr = "",
                    isSuccess = true
                )
            }
        }

        val result = plugin.execute(
            ExecutionContext(
                agentName = "codex",
                input = "hello",
                timeout = 1_000,
                parameters = mapOf("model_reasoning_effort" to "xhigh"),
                environment = emptyMap()
            ),
            processManager
        )

        result.output shouldBe "normalized"
    }

    test("normalizes retired codex model aliases before invoking the CLI") {
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
                command shouldContain "--model"
                command shouldContain "gpt-5.4"
                command.contains("gpt-5.3-codex-spark") shouldBe false
                val outputIndex = command.indexOf("--output-last-message")
                Files.writeString(Path.of(command[outputIndex + 1]), "normalized-model")
                return ProcessResult(
                    exitCode = 0,
                    stdout = "",
                    stderr = "",
                    isSuccess = true
                )
            }
        }

        val result = plugin.execute(
            ExecutionContext(
                agentName = "codex",
                input = "hello",
                timeout = 1_000,
                parameters = mapOf("model" to "gpt-5.3-codex-spark"),
                environment = emptyMap()
            ),
            processManager
        )

        result.output shouldBe "normalized-model"
    }

    test("disables inherited codex MCP servers for task-scoped execution") {
        val plugin = CodexPlugin()
        val fakeHome = Files.createTempDirectory("codex-plugin-home")
        val fakeCodexHome = Files.createDirectories(fakeHome.resolve(".codex"))
        Files.writeString(fakeCodexHome.resolve("auth.json"), """{"access_token":"test"}""")
        Files.writeString(
            fakeCodexHome.resolve("config.toml"),
            """
                model = "gpt-5.4"

                [mcp_servers.github]
                url = "https://api.githubcopilot.com/mcp/"
            """.trimIndent() + "\n"
        )
        var isolatedCodexHome: Path? = null
        val processManager = object : ProcessManager {
            override suspend fun executeProcess(
                command: List<String>,
                input: String?,
                environment: Map<String, String>,
                timeout: Long,
                workingDirectory: Path?,
                onStart: ((Long) -> Unit)?
            ): ProcessResult {
                command shouldContain "-c"
                command shouldContain "mcp_servers={}"
                command shouldContain "--model"
                command shouldContain "gpt-5.4"
                isolatedCodexHome = environment["CODEX_HOME"]?.let(Path::of)
                isolatedCodexHome.shouldNotBe(null)
                Files.exists(isolatedCodexHome!!.resolve("auth.json")) shouldBe true
                Files.readString(isolatedCodexHome!!.resolve("config.toml")).contains("rmcp_client = false") shouldBe true
                val outputIndex = command.indexOf("--output-last-message")
                Files.writeString(Path.of(command[outputIndex + 1]), "isolated")
                return ProcessResult(
                    exitCode = 0,
                    stdout = "",
                    stderr = "",
                    isSuccess = true
                )
            }
        }

        val result = plugin.execute(
            ExecutionContext(
                agentName = "codex",
                input = "hello",
                timeout = 1_000,
                parameters = emptyMap(),
                environment = mapOf("HOME" to fakeHome.toString()),
                taskId = "task-123"
            ),
            processManager
        )

        result.output shouldBe "isolated"
        Files.exists(isolatedCodexHome!!) shouldBe false
    }
})
