package com.cotor.data.plugin

import com.cotor.data.process.ProcessManager
import com.cotor.model.ExecutionContext
import com.cotor.model.PluginExecutionOutput
import com.cotor.model.ProcessResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class QaVerificationPluginTest : FunSpec({
    test("uses explicit argvJson override when provided") {
        val root = Path.of("build/tmp/qa-plugin-override-${System.currentTimeMillis()}")
        root.createDirectories()

        val processManager = CapturingProcessManager()
        val plugin = QaVerificationPlugin()

        val output = runBlocking {
            plugin.execute(
                ExecutionContext(
                    agentName = "qa",
                    input = "",
                    parameters = mapOf("argvJson" to "[\"npm\",\"test\"]"),
                    environment = emptyMap(),
                    timeout = 60_000,
                    repoRoot = root,
                    workingDirectory = root
                ),
                processManager
            )
        }

        output shouldBe PluginExecutionOutput(output = "ok", processId = 42)
        processManager.lastCommand shouldContainExactly listOf("npm", "test")
    }

    test("autodetects gradle wrapper command from repository files") {
        val root = Path.of("build/tmp/qa-plugin-detect-${System.currentTimeMillis()}")
        root.createDirectories()
        root.resolve("gradlew").writeText("#!/usr/bin/env bash")

        val processManager = CapturingProcessManager()
        val plugin = QaVerificationPlugin()

        runBlocking {
            plugin.execute(
                ExecutionContext(
                    agentName = "qa",
                    input = "",
                    parameters = emptyMap(),
                    environment = emptyMap(),
                    timeout = 60_000,
                    repoRoot = root,
                    workingDirectory = root
                ),
                processManager
            )
        }

        processManager.lastCommand shouldContainExactly listOf("./gradlew", "test", "--console=plain")
    }
})

private class CapturingProcessManager : ProcessManager {
    var lastCommand: List<String> = emptyList()

    override suspend fun executeProcess(
        command: List<String>,
        input: String?,
        environment: Map<String, String>,
        timeout: Long,
        workingDirectory: Path?,
        onStart: ((Long) -> Unit)?
    ): ProcessResult {
        lastCommand = command
        return ProcessResult(exitCode = 0, stdout = "ok", stderr = "", isSuccess = true, processId = 42)
    }
}
