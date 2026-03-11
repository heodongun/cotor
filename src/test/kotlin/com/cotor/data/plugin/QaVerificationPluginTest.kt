package com.cotor.data.plugin

import com.cotor.data.process.ProcessManager
import com.cotor.model.ExecutionContext
import com.cotor.model.ProcessExecutionException
import com.cotor.model.ProcessResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class QaVerificationPluginTest : FunSpec({
    test("auto-detects a Gradle repository and runs the wrapper test command") {
        val root = Files.createTempDirectory("qa-gradle")
        root.resolve("gradlew").writeText("#!/usr/bin/env bash\n")
        root.resolve("build.gradle.kts").writeText("plugins {}\n")

        val plugin = QaVerificationPlugin()
        val processManager = recordingProcessManager(
            expectedCommand = listOf("./gradlew", "test", "--console=plain"),
            expectedWorkingDirectory = root
        )

        val result = plugin.execute(
            ExecutionContext(
                agentName = "qa",
                input = null,
                timeout = 1_000,
                parameters = emptyMap(),
                environment = emptyMap(),
                workingDirectory = root
            ),
            processManager
        )

        result.output shouldBe "tests passed"
    }

    test("prefers package-manager verify scripts before generic test scripts") {
        val root = Files.createTempDirectory("qa-js")
        root.resolve("package.json").writeText(
            """
            {
              "scripts": {
                "verify": "vitest run",
                "test": "echo fallback"
              }
            }
            """.trimIndent()
        )
        root.resolve("pnpm-lock.yaml").writeText("lockfileVersion: '9.0'\n")

        val plugin = QaVerificationPlugin()
        val processManager = recordingProcessManager(
            expectedCommand = listOf("pnpm", "run", "verify"),
            expectedWorkingDirectory = root
        )

        val result = plugin.execute(
            ExecutionContext(
                agentName = "qa",
                input = null,
                timeout = 1_000,
                parameters = emptyMap(),
                environment = emptyMap(),
                workingDirectory = root
            ),
            processManager
        )

        result.output shouldBe "tests passed"
    }

    test("uses commandJson override when provided") {
        val root = Files.createTempDirectory("qa-override")
        val plugin = QaVerificationPlugin()
        val processManager = recordingProcessManager(
            expectedCommand = listOf("python3", "-m", "pytest", "-q"),
            expectedWorkingDirectory = root
        )

        val result = plugin.execute(
            ExecutionContext(
                agentName = "qa",
                input = null,
                timeout = 1_000,
                parameters = mapOf("commandJson" to """["python3","-m","pytest","-q"]"""),
                environment = emptyMap(),
                workingDirectory = root
            ),
            processManager
        )

        result.output shouldBe "tests passed"
    }

    test("fails with a clear message when no supported verification command is found") {
        val root = Files.createTempDirectory("qa-missing")
        val plugin = QaVerificationPlugin()

        val error = shouldThrow<IllegalArgumentException> {
            plugin.execute(
                ExecutionContext(
                    agentName = "qa",
                    input = null,
                    timeout = 1_000,
                    parameters = emptyMap(),
                    environment = emptyMap(),
                    workingDirectory = root
                ),
                object : ProcessManager {
                    override suspend fun executeProcess(
                        command: List<String>,
                        input: String?,
                        environment: Map<String, String>,
                        timeout: Long,
                        workingDirectory: Path?
                    ): ProcessResult {
                        error("Process manager should not be called when detection fails")
                    }
                }
            )
        }

        error.message shouldBe
            "No supported QA verification command found in ${root.toAbsolutePath().normalize()}. Set parameters.commandJson to override detection."
    }

    test("preserves exit code and streams when the verification command fails") {
        val root = Files.createTempDirectory("qa-failure")
        root.resolve("Cargo.toml").writeText("[package]\nname = \"demo\"\nversion = \"0.1.0\"\n")
        val plugin = QaVerificationPlugin()

        val error = shouldThrow<ProcessExecutionException> {
            plugin.execute(
                ExecutionContext(
                    agentName = "qa",
                    input = null,
                    timeout = 1_000,
                    parameters = emptyMap(),
                    environment = emptyMap(),
                    workingDirectory = root
                ),
                object : ProcessManager {
                    override suspend fun executeProcess(
                        command: List<String>,
                        input: String?,
                        environment: Map<String, String>,
                        timeout: Long,
                        workingDirectory: Path?
                    ): ProcessResult {
                        command shouldBe listOf("cargo", "test", "--quiet")
                        return ProcessResult(
                            exitCode = 101,
                            stdout = "failing stdout",
                            stderr = "failing stderr",
                            isSuccess = false
                        )
                    }
                }
            )
        }

        error.message shouldBe "QA verification failed: cargo test --quiet"
        error.exitCode shouldBe 101
        error.stdout shouldBe "failing stdout"
        error.stderr shouldBe "failing stderr"
    }
})

private fun recordingProcessManager(
    expectedCommand: List<String>,
    expectedWorkingDirectory: Path
): ProcessManager {
    return object : ProcessManager {
        override suspend fun executeProcess(
            command: List<String>,
            input: String?,
            environment: Map<String, String>,
            timeout: Long,
            workingDirectory: Path?
        ): ProcessResult {
            command shouldBe expectedCommand
            workingDirectory shouldBe expectedWorkingDirectory
            input shouldBe null
            return ProcessResult(
                exitCode = 0,
                stdout = "tests passed",
                stderr = "",
                isSuccess = true
            )
        }
    }
}
