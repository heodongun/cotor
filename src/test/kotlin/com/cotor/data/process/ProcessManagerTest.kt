package com.cotor.data.process

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.slf4j.Logger
import java.nio.file.Files
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
})
