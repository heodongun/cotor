package com.cotor.runtime

import com.cotor.runtime.actions.ActionExecutionRecord
import com.cotor.runtime.actions.ActionKind
import com.cotor.runtime.actions.ActionRequest
import com.cotor.runtime.actions.ActionScope
import com.cotor.runtime.actions.ActionStatus
import com.cotor.runtime.actions.ActionStore
import com.cotor.runtime.actions.ActionSubject
import com.cotor.verification.VerificationObservation
import com.cotor.verification.VerificationSignal
import com.cotor.verification.VerificationSignalStatus
import com.cotor.verification.VerificationStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StoreConcurrencyTest : FunSpec({
    test("ActionStore append is atomic under concurrent writers") {
        val appHome = Files.createTempDirectory("action-store-concurrency")
        val store = ActionStore { appHome }
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(20)

        try {
            repeat(20) { index ->
                executor.submit {
                    start.await(5, TimeUnit.SECONDS)
                    store.append(
                        runId = "run-1",
                        record = ActionExecutionRecord(
                            id = "record-$index",
                            runId = "run-1",
                            request = ActionRequest(
                                id = "request-$index",
                                kind = ActionKind.AGENT_EXEC,
                                label = "record-$index",
                                scope = ActionScope.RUN,
                                subject = ActionSubject(runId = "run-1")
                            ),
                            status = ActionStatus.STARTED,
                            createdAt = index.toLong(),
                            updatedAt = index.toLong()
                        )
                    )
                    done.countDown()
                }
            }

            start.countDown()
            done.await(10, TimeUnit.SECONDS) shouldBe true
            store.load("run-1")!!.records.size shouldBe 20
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    test("VerificationStore appendObservation is atomic under concurrent writers") {
        val appHome = Files.createTempDirectory("verification-store-concurrency")
        val store = VerificationStore { appHome }
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(20)

        try {
            repeat(20) { index ->
                executor.submit {
                    start.await(5, TimeUnit.SECONDS)
                    store.appendObservation(
                        issueId = "issue-1",
                        observation = VerificationObservation(
                            source = "test-$index",
                            signal = VerificationSignal(
                                key = "signal-$index",
                                status = VerificationSignalStatus.PASS,
                                detail = "detail-$index"
                            ),
                            observedAt = index.toLong()
                        )
                    )
                    done.countDown()
                }
            }

            start.countDown()
            done.await(10, TimeUnit.SECONDS) shouldBe true
            store.loadObservations("issue-1").size shouldBe 20
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
})
