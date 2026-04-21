package com.cotor.data.process

/**
 * File overview for ProcessManagerTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around process manager test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import kotlinx.coroutines.TimeoutCancellationException
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class ProcessManagerTest : FunSpec({

    test("executeProcess can report the child pid as soon as the process starts") {
        val processManager = CoroutineProcessManager(mockk<Logger>(relaxed = true))
        var startedPid: Long? = null

        val result = processManager.executeProcess(
            command = listOf("/bin/sh", "-c", "echo process-started"),
            input = null,
            environment = emptyMap(),
            timeout = 5_000,
            onStart = { pid -> startedPid = pid }
        )

        result.isSuccess shouldBe true
        result.stdout.trim() shouldBe "process-started"
        val pid = startedPid.shouldNotBeNull()
        pid shouldBeGreaterThan 0
        val resultPid = result.processId.shouldNotBeNull()
        resultPid shouldBeGreaterThan 0
    }

    test("executeProcess augments PATH so env-based scripts can resolve helper binaries") {
        val processManager = CoroutineProcessManager(mockk<Logger>(relaxed = true))
        val tempDir = Files.createTempDirectory("process-manager-path-test")
        val fakeNode = tempDir.resolve("node")
        Files.writeString(
            fakeNode,
            """
            #!/bin/sh
            echo "fake-node:$1"
            """.trimIndent()
        )
        Files.setPosixFilePermissions(
            fakeNode,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
        )

        val tool = tempDir.resolve("tool")
        Files.writeString(
            tool,
            """
            #!/usr/bin/env node
            console.log("hello")
            """.trimIndent()
        )
        Files.setPosixFilePermissions(
            tool,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
        )

        val result = processManager.executeProcess(
            command = listOf(tool.toString()),
            input = null,
            environment = mapOf("PATH" to ""),
            timeout = 5_000
        )

        result.isSuccess shouldBe true
        result.stdout.trim() shouldBe "fake-node:$tool"
    }

    test("executeProcess times out even when the child keeps pipes open") {
        val processManager = CoroutineProcessManager(mockk<Logger>(relaxed = true))

        shouldThrow<TimeoutCancellationException> {
            processManager.executeProcess(
                command = listOf("/bin/sh", "-c", "sleep 30"),
                input = null,
                environment = emptyMap(),
                timeout = 200
            )
        }
    }

    test("executeProcess drains large stdout output before returning") {
        val processManager = CoroutineProcessManager(mockk<Logger>(relaxed = true))

        val result = processManager.executeProcess(
            command = listOf(
                "/bin/sh",
                "-c",
                "python3 - <<'PY'\nfor i in range(20000):\n    print(f'line-{i}')\nPY"
            ),
            input = null,
            environment = emptyMap(),
            timeout = 30_000
        )

        result.isSuccess shouldBe true
        result.stdout shouldContain "line-0"
        result.stdout shouldContain "line-19999"
    }

    test("effectiveUserHome prefers HOME from the environment over user.home") {
        effectiveUserHome(
            environment = mapOf("HOME" to "/tmp/cotor-home"),
            systemHome = "/Users/real-home"
        ) shouldBe "/tmp/cotor-home"
    }

    test("resolveExecutablePath searches the HOME override user-local bin before the real user home") {
        val fakeHome = Files.createTempDirectory("process-manager-home-override")
        val fakeLocalBin = fakeHome.resolve(".local").resolve("bin")
        Files.createDirectories(fakeLocalBin)
        val commandName = "cotor-home-only-probe"
        val fakeExecutable = fakeLocalBin.resolve(commandName)
        Files.writeString(
            fakeExecutable,
            """
            #!/bin/sh
            exit 0
            """.trimIndent()
        )
        Files.setPosixFilePermissions(
            fakeExecutable,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
        )

        val resolved = resolveExecutablePath(
            command = commandName,
            environment = mapOf("PATH" to "/usr/bin:/bin", "HOME" to fakeHome.toString()),
            systemHome = "/Users/real-home"
        )

        resolved.shouldNotBeNull()
        resolved shouldBe Path.of(fakeExecutable.toString()).toAbsolutePath().normalize()
    }
})
