package com.cotor.app

import com.cotor.data.process.ProcessManager
import com.cotor.model.ProcessResult
import java.nio.file.Path

internal class FakeProcessManager(
    private val handler: suspend (command: List<String>, workingDirectory: Path?) -> ProcessResult
) : ProcessManager {
    override suspend fun executeProcess(
        command: List<String>,
        input: String?,
        environment: Map<String, String>,
        timeout: Long,
        workingDirectory: Path?
    ): ProcessResult = handler(command, workingDirectory)
}

internal fun ok(stdout: String = "", stderr: String = "") = ProcessResult(
    exitCode = 0,
    stdout = stdout,
    stderr = stderr,
    isSuccess = true
)

internal fun fail(stderr: String) = ProcessResult(
    exitCode = 1,
    stdout = "",
    stderr = stderr,
    isSuccess = false
)

internal fun <T> suspendAndReturn(block: suspend () -> T): T = kotlinx.coroutines.runBlocking {
    block()
}
