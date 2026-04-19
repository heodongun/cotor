package com.cotor.presentation.web

import com.cotor.app.CompanyDashboardResponse
import com.cotor.app.CompanyIssue
import com.cotor.app.DesktopAppService
import com.cotor.app.IssueAgentExecutionDetail
import com.cotor.app.IssueStatus
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
import io.mockk.coVerify
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

    test("help page is served through web module") {
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

            val response = client.get("/help?lang=en")
            response.status.value shouldBe 200
            response.bodyAsText() shouldContain "Cotor Help"
            response.bodyAsText() shouldContain "cotor help web"
            response.bodyAsText() shouldContain "cotor help ai"
        }
    }

    test("company dashboard api is served through web module") {
        val configRepository = mockk<ConfigRepository>(relaxed = true)
        val agentRegistry = mockk<AgentRegistry>(relaxed = true)
        val orchestrator = mockk<PipelineOrchestrator>(relaxed = true)
        val desktopService = mockk<DesktopAppService>(relaxed = true)
        val editorDir = Files.createTempDirectory("cotor-web-test")
        coEvery { desktopService.companyDashboardReadOnly() } returns CompanyDashboardResponse()

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
            coVerify(exactly = 1) { desktopService.companyDashboardReadOnly() }
            coVerify(exactly = 0) { desktopService.companyDashboard() }
        }
    }

    test("company issue detail api is served through the projected issue path") {
        val configRepository = mockk<ConfigRepository>(relaxed = true)
        val agentRegistry = mockk<AgentRegistry>(relaxed = true)
        val orchestrator = mockk<PipelineOrchestrator>(relaxed = true)
        val desktopService = mockk<DesktopAppService>(relaxed = true)
        val editorDir = Files.createTempDirectory("cotor-web-issue-test")
        coEvery { desktopService.getIssueProjected("issue-1") } returns CompanyIssue(
            id = "issue-1",
            companyId = "company-1",
            projectContextId = "project-1",
            goalId = "goal-1",
            workspaceId = "workspace-1",
            title = "Issue",
            description = "desc",
            status = IssueStatus.IN_PROGRESS,
            runtimeDisposition = "RUNNABLE",
            createdAt = 1L,
            updatedAt = 2L
        )

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

            val response = client.get("/api/company/companies/company-1/issues/issue-1")
            response.status.value shouldBe 200
            response.bodyAsText() shouldContain "\"id\":\"issue-1\""
            response.bodyAsText() shouldContain "\"runtimeDisposition\":\"RUNNABLE\""
            io.mockk.coVerify(exactly = 1) { desktopService.getIssueProjected("issue-1") }
            io.mockk.coVerify(exactly = 0) { desktopService.getIssue("issue-1") }
        }
    }

    test("company issue execution details api is served through web module") {
        val configRepository = mockk<ConfigRepository>(relaxed = true)
        val agentRegistry = mockk<AgentRegistry>(relaxed = true)
        val orchestrator = mockk<PipelineOrchestrator>(relaxed = true)
        val desktopService = mockk<DesktopAppService>(relaxed = true)
        val editorDir = Files.createTempDirectory("cotor-web-test")
        coEvery { desktopService.issueExecutionDetails("issue-1") } returns listOf(
            IssueAgentExecutionDetail(
                roleName = "Engineering Lead",
                agentName = "opencode",
                agentCli = "opencode",
                assignedPrompt = "Implement the issue and open a PR.",
                taskId = "task-1",
                taskStatus = "COMPLETED",
                runId = "run-1",
                runStatus = "COMPLETED",
                stdout = "Finished implementation.",
                stderr = null,
                branchName = "opencode/impl",
                pullRequestUrl = "https://github.com/bssm-oss/cotor-test/pull/1",
                updatedAt = 2L,
                publishSummary = "review queue: AWAITING_QA"
            )
        )

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

            val response = client.get("/api/company/issues/issue-1/execution-details")
            response.status.value shouldBe 200
            response.bodyAsText() shouldContain "\"roleName\":\"Engineering Lead\""
            response.bodyAsText() shouldContain "\"stdout\":\"Finished implementation.\""
        }
    }
})
