package com.cotor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk

class AppServerTest : FunSpec({
    val desktopService = mockk<DesktopAppService>(relaxed = true)
    val tuiSessionService = mockk<DesktopTuiSessionService>(relaxed = true)

    test("health and ready probes stay available without auth") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val health = client.get("/health")
            health.status shouldBe HttpStatusCode.OK
            health.bodyAsText() shouldContain "\"ok\":true"
            health.bodyAsText() shouldContain "\"service\":\"cotor-app-server\""

            val ready = client.get("/ready")
            ready.status shouldBe HttpStatusCode.OK
            ready.bodyAsText() shouldContain "\"ok\":true"
            ready.bodyAsText() shouldContain "\"service\":\"cotor-app-server\""
        }
    }

    test("authenticated routes still reject missing bearer token") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.get("/api/app/dashboard")
            response.status shouldBe HttpStatusCode.Unauthorized
            response.bodyAsText() shouldContain "Unauthorized"
        }
    }

    test("company runtime routes return lifecycle snapshots when authorized") {
        coEvery { desktopService.runtimeStatus() } returns CompanyRuntimeSnapshot(
            status = CompanyRuntimeStatus.STOPPED,
            lastAction = "idle"
        )
        coEvery { desktopService.startCompanyRuntime() } returns CompanyRuntimeSnapshot(
            status = CompanyRuntimeStatus.RUNNING,
            lastAction = "runtime-started"
        )
        coEvery { desktopService.stopCompanyRuntime() } returns CompanyRuntimeSnapshot(
            status = CompanyRuntimeStatus.STOPPED,
            lastAction = "runtime-stopped"
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val runtime = client.get("/api/app/company/runtime") {
                header("Authorization", "Bearer secret-token")
            }
            runtime.status shouldBe HttpStatusCode.OK
            runtime.bodyAsText() shouldContain "\"status\":\"STOPPED\""

            val start = client.post("/api/app/company/runtime/start") {
                header("Authorization", "Bearer secret-token")
            }
            start.status shouldBe HttpStatusCode.OK
            start.bodyAsText() shouldContain "\"status\":\"RUNNING\""

            val stop = client.post("/api/app/company/runtime/stop") {
                header("Authorization", "Bearer secret-token")
            }
            stop.status shouldBe HttpStatusCode.OK
            stop.bodyAsText() shouldContain "\"status\":\"STOPPED\""
        }
    }
})
