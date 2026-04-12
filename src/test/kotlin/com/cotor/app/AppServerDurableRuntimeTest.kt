package com.cotor.app

import com.cotor.runtime.durable.CheckpointNode
import com.cotor.runtime.durable.CheckpointNodeState
import com.cotor.runtime.durable.DurableExecutionPlan
import com.cotor.runtime.durable.DurableResumeCoordinator
import com.cotor.runtime.durable.DurableRunSnapshot
import com.cotor.runtime.durable.DurableRunStatus
import com.cotor.runtime.durable.DurableRuntimeService
import com.cotor.runtime.durable.ReplayMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AppServerDurableRuntimeTest : FunSpec({
    val desktopService = mockk<DesktopAppService>(relaxed = true)
    val tuiSessionService = mockk<DesktopTuiSessionService>(relaxed = true)
    val durableRuntimeService = mockk<DurableRuntimeService>()
    val durableResumeCoordinator = mockk<DurableResumeCoordinator>()

    val sampleRun = DurableRunSnapshot(
        runId = "run-1",
        pipelineName = "sample",
        replayMode = ReplayMode.LIVE,
        status = DurableRunStatus.RUNNING,
        createdAt = 1L,
        updatedAt = 1L,
        checkpoints = listOf(
            CheckpointNode(
                ordinal = 1,
                stageId = "stage-1",
                state = CheckpointNodeState.COMPLETED,
                createdAt = 1L
            )
        )
    )

    test("durable runtime routes list and inspect runs") {
        every { durableRuntimeService.listRuns() } returns listOf(sampleRun)
        every { durableRuntimeService.inspectRun("run-1") } returns sampleRun

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService,
                    durableRuntimeService = durableRuntimeService,
                    durableResumeCoordinator = durableResumeCoordinator
                )
            }

            val listResponse = client.get("/api/app/durable-runtime/runs") {
                header("Authorization", "Bearer secret-token")
            }
            listResponse.status shouldBe HttpStatusCode.OK
            listResponse.bodyAsText().contains("run-1") shouldBe true

            val inspectResponse = client.get("/api/app/durable-runtime/runs/run-1") {
                header("Authorization", "Bearer secret-token")
            }
            inspectResponse.status shouldBe HttpStatusCode.OK
            inspectResponse.bodyAsText().contains("\"pipelineName\":\"sample\"") shouldBe true
        }
    }

    test("durable runtime routes continue and approve through the coordinator") {
        coEvery { durableResumeCoordinator.continueRun("run-1", "cotor.yaml") } returns DurableExecutionPlan(
            runId = "run-1",
            replayMode = ReplayMode.CONTINUE,
            configPath = "cotor.yaml",
            pipelineName = "sample",
            restoreCheckpointId = "checkpoint-1",
            nextStageId = "stage-2",
            stageResults = emptyList(),
            pendingApproval = null
        )
        coEvery { durableResumeCoordinator.approve("run-1", "checkpoint-1") } returns sampleRun.copy(status = DurableRunStatus.RUNNING)

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService,
                    durableRuntimeService = durableRuntimeService,
                    durableResumeCoordinator = durableResumeCoordinator
                )
            }

            val continueResponse = client.post("/api/app/durable-runtime/runs/run-1/continue") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", ContentType.Application.Json.toString())
                setBody(Json.encodeToString(DurableContinueRequest(configPath = "cotor.yaml")))
            }
            continueResponse.status shouldBe HttpStatusCode.OK
            continueResponse.bodyAsText().contains("\"nextStageId\":\"stage-2\"") shouldBe true

            val approveResponse = client.post("/api/app/durable-runtime/runs/run-1/approve") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", ContentType.Application.Json.toString())
                setBody(Json.encodeToString(DurableApproveRequest(checkpointId = "checkpoint-1")))
            }
            approveResponse.status shouldBe HttpStatusCode.OK
            approveResponse.bodyAsText().contains("\"status\":\"RUNNING\"") shouldBe true
        }
    }
})
