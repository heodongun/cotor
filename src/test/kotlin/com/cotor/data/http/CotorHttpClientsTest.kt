package com.cotor.data.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class CotorHttpClientsTest : FunSpec({
    test("reuses the default HTTP client to avoid per-call selector thread growth") {
        val first = CotorHttpClients.newClient()
        val second = CotorHttpClients.newClient()

        (first === second) shouldBe true
    }

    test("reuses timeout-specific HTTP clients") {
        val first = CotorHttpClients.client(Duration.ofSeconds(20))
        val second = CotorHttpClients.client(Duration.ofSeconds(20))
        val differentTimeout = CotorHttpClients.client(Duration.ofSeconds(5))

        (first === second) shouldBe true
        (first === differentTimeout) shouldBe false
    }

    test("uses daemon executor threads so HTTP clients do not keep the JVM alive") {
        val executor = CotorHttpClients.newClient().executor().orElseThrow() as ExecutorService

        val result = executor.submit<Pair<Boolean, String>> {
            Thread.currentThread().isDaemon to Thread.currentThread().name
        }.get(2, TimeUnit.SECONDS)

        result.first shouldBe true
        result.second shouldStartWith "cotor-http-"
    }
})
