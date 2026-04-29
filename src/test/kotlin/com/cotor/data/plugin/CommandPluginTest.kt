package com.cotor.data.plugin

import com.cotor.data.process.ProcessManager
import com.cotor.model.ExecutionContext
import com.cotor.model.ProcessResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class CommandPluginTest : FunSpec({
    test("command plugin validates argv template before execution") {
        val processManager = RecordingProcessManager()
        val plugin = CommandPlugin()

        shouldThrow<IllegalStateException> {
            plugin.execute(
                ExecutionContext(
                    agentName = "command",
                    input = null,
                    parameters = mapOf("argvJson" to "[\"sh\",\"-c\",\"id\"]"),
                    environment = emptyMap(),
                    timeout = 1000,
                    validateCommand = { throw IllegalStateException("blocked") }
                ),
                processManager
            )
        }

        processManager.commands.size shouldBe 0
    }

    test("command plugin validates template while passing substituted input as literal argv") {
        val processManager = RecordingProcessManager()
        val validatedCommands = mutableListOf<List<String>>()
        val plugin = CommandPlugin()

        plugin.execute(
            ExecutionContext(
                agentName = "command",
                input = "literal ; | < > input",
                parameters = mapOf("argvJson" to "[\"/opt/homebrew/bin/qwen\",\"{input}\"]"),
                environment = emptyMap(),
                timeout = 1000,
                validateCommand = { validatedCommands += it }
            ),
            processManager
        )

        validatedCommands.single().shouldContainExactly(listOf("/opt/homebrew/bin/qwen", "{input}"))
        processManager.commands.single().shouldContainExactly(listOf("/opt/homebrew/bin/qwen", "literal ; | < > input"))
    }
})

private class RecordingProcessManager : ProcessManager {
    val commands = mutableListOf<List<String>>()

    override suspend fun executeProcess(
        command: List<String>,
        input: String?,
        environment: Map<String, String>,
        timeout: Long,
        workingDirectory: Path?,
        onStart: ((Long) -> Unit)?
    ): ProcessResult {
        commands += command
        return ProcessResult(exitCode = 0, stdout = "ok", stderr = "", isSuccess = true)
    }
}
