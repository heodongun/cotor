package com.cotor.data.process

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.slf4j.Logger

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
})
