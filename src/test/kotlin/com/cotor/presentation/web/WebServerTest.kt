package com.cotor.presentation.web

import com.cotor.app.CompanyDashboardResponse
import com.cotor.app.DesktopAppService
import com.cotor.data.config.ConfigRepository
import com.cotor.data.registry.AgentRegistry
import com.cotor.domain.orchestrator.PipelineOrchestrator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import java.nio.file.Files

class WebServerTest : FunSpec({
    test("company console page is served") {
        val configRepository = mockk<ConfigRepository>(relaxed = true)
        val agentRegistry = mockk<AgentRegistry>(relaxed = true)
        val orchestrator = mockk<PipelineOrchestrator>(relaxed = true)
        val desktopService = mockk<DesktopAppService>(relaxed = true)
        val editorDir = Files.createTempDirectory("cotor-web-test")

        testApplication {
            application {
                cotorWebModule(
                    configRepository = configRepository,
                    agentRegistry = agentRegistry,
                    orchestrator = orchestrator,
                    desktopService = desktopService,
                    editorDir = editorDir,
                    readOnly = false,
                    buildTemplates = { emptyList() },
                    listSavedPipelines = { emptyList() },
                    loadPipelineDetail = { null },
                    savePipeline = { editorDir.resolve("saved.yaml") },
                    buildTimelinePayload = { _, _ -> emptyList() }
                )
            }

            val response = client.get("/company")
            response.status.value shouldBe 200
            response.bodyAsText() shouldContain "Cotor Company Console"
            response.bodyAsText() shouldContain "/api/company/dashboard"
        }
    }

    test("company dashboard api is served through web module") {
        val configRepository = mockk<ConfigRepository>(relaxed = true)
        val agentRegistry = mockk<AgentRegistry>(relaxed = true)
        val orchestrator = mockk<PipelineOrchestrator>(relaxed = true)
        val desktopService = mockk<DesktopAppService>(relaxed = true)
        val editorDir = Files.createTempDirectory("cotor-web-test")
        coEvery { desktopService.companyDashboard() } returns CompanyDashboardResponse()

        testApplication {
            application {
                cotorWebModule(
                    configRepository = configRepository,
                    agentRegistry = agentRegistry,
                    orchestrator = orchestrator,
                    desktopService = desktopService,
                    editorDir = editorDir,
                    readOnly = false,
                    buildTemplates = { emptyList() },
                    listSavedPipelines = { emptyList() },
                    loadPipelineDetail = { null },
                    savePipeline = { editorDir.resolve("saved.yaml") },
                    buildTimelinePayload = { _, _ -> emptyList() }
                )
            }

            val response = client.get("/api/company/dashboard")
            response.status.value shouldBe 200
            response.bodyAsText() shouldContain "\"companies\""
            response.bodyAsText() shouldContain "\"runtime\""
        }
    }
})
