package com.cotor.data.plugin

/**
 * File overview for FileReaderPluginTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects
 * against regressions in the read-only file fanout example plugin.
 */

import com.cotor.data.process.ProcessManager
import com.cotor.model.ExecutionContext
import com.cotor.model.ProcessResult
import com.cotor.model.ValidationResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.writeText

class FileReaderPluginTest : FunSpec({
    test("reads a UTF-8 text file relative to the execution root") {
        val root = Path.of("build/tmp/file-reader-${System.currentTimeMillis()}")
        root.createDirectories()
        root.resolve("docs").createDirectories()
        root.resolve("docs/example.md").writeText("hello fanout")

        val output = runBlocking {
            FileReaderPlugin().execute(
                ExecutionContext(
                    agentName = "file-reader",
                    input = "docs/example.md",
                    parameters = emptyMap(),
                    environment = emptyMap(),
                    timeout = 30_000,
                    workingDirectory = root
                ),
                NoopProcessManager
            )
        }

        output.output shouldContain "FILE: docs/example.md"
        output.output shouldContain "hello fanout"
    }

    test("rejects paths that escape the execution root") {
        val root = Path.of("build/tmp/file-reader-escape-${System.currentTimeMillis()}")
        root.createDirectories()

        val error = shouldThrow<IllegalArgumentException> {
            runBlocking {
                FileReaderPlugin().execute(
                    ExecutionContext(
                        agentName = "file-reader",
                        input = "../secret.txt",
                        parameters = emptyMap(),
                        environment = emptyMap(),
                        timeout = 30_000,
                        workingDirectory = root
                    ),
                    NoopProcessManager
                )
            }
        }

        error.message shouldContain "escapes the execution root"
    }

    test("rejects symlinks that resolve outside the execution root") {
        val root = Path.of("build/tmp/file-reader-symlink-${System.currentTimeMillis()}")
        root.createDirectories()
        val outside = root.parent.resolve("outside-secret-${System.currentTimeMillis()}.txt")
        outside.writeText("secret")
        val link = root.resolve("linked-secret.txt")
        if (!link.exists()) {
            link.createSymbolicLinkPointingTo(outside)
        }

        val error = shouldThrow<IllegalArgumentException> {
            runBlocking {
                FileReaderPlugin().execute(
                    ExecutionContext(
                        agentName = "file-reader",
                        input = "linked-secret.txt",
                        parameters = emptyMap(),
                        environment = emptyMap(),
                        timeout = 30_000,
                        workingDirectory = root
                    ),
                    NoopProcessManager
                )
            }
        }

        error.message shouldContain "escapes the execution root"
    }

    test("rejects blank and NUL path inputs during validation") {
        val plugin = FileReaderPlugin()

        (plugin.validateInput("") as ValidationResult.Failure).errors.first() shouldContain "required"
        (plugin.validateInput("bad\u0000path") as ValidationResult.Failure).errors.first() shouldContain "NUL"
    }
})

private object NoopProcessManager : ProcessManager {
    override suspend fun executeProcess(
        command: List<String>,
        input: String?,
        environment: Map<String, String>,
        timeout: Long,
        workingDirectory: Path?,
        onStart: ((Long) -> Unit)?
    ): ProcessResult = error("FileReaderPlugin must not spawn child processes")
}
