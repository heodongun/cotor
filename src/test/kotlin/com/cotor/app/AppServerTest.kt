package com.cotor.app

/**
 * File overview for AppServerTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around app server test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppServerTest : FunSpec({
    val desktopService = mockk<DesktopAppService>(relaxed = true)
    val tuiSessionService = mockk<DesktopTuiSessionService>(relaxed = true)

    beforeTest {
        clearMocks(desktopService, tuiSessionService, answers = false, recordedCalls = true)
    }

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

    test("help guide route returns localized app help when authorized") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.get("/api/app/help-guide?lang=en") {
                header("Authorization", "Bearer secret-token")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"title\":\"Cotor Help\""
            response.bodyAsText() shouldContain "cotor help web"
            response.bodyAsText() shouldContain "cotor help ai"
        }
    }

    test("dashboard route returns the top-level dashboard when authorized") {
        coEvery { desktopService.dashboard() } returns DashboardResponse(
            repositories = emptyList(),
            workspaces = emptyList(),
            tasks = emptyList(),
            settings = DesktopSettings(
                appHome = "/tmp/cotor-app-home",
                managedReposRoot = "/tmp/cotor-managed-repos",
                availableAgents = listOf("opencode")
            )
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.get("/api/app/dashboard") {
                header("Authorization", "Bearer secret-token")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"repositories\":[]"
        }
    }

    test("company memory snapshot route returns backend memory payload when authorized") {
        coEvery { desktopService.companyMemorySnapshot("company-1", "issue-1", null) } returns CompanyMemorySnapshotResponse(
            companyMemory = "Company memory",
            workflowMemory = "Workflow memory",
            agentMemory = "Agent memory"
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.get("/api/app/companies/company-1/memory-snapshot?issueId=issue-1") {
                header("Authorization", "Bearer secret-token")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"companyMemory\":\"Company memory\""
            response.bodyAsText() shouldContain "\"workflowMemory\":\"Workflow memory\""
            response.bodyAsText() shouldContain "\"agentMemory\":\"Agent memory\""
            coVerify(exactly = 1) { desktopService.companyMemorySnapshot("company-1", "issue-1", null) }
        }
    }

    test("evidence files route rejects blank path") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.get("/api/app/evidence/files?path=") {
                header("Authorization", "Bearer secret-token")
            }

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "path must not be blank"
        }
    }

    test("shutdown route accepts authenticated graceful shutdown requests") {
        val shutdownLatch = CountDownLatch(1)

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService,
                    shutdownHandler = { shutdownLatch.countDown() }
                )
            }

            val response = client.post("/api/app/shutdown") {
                header("Authorization", "Bearer secret-token")
            }

            response.status shouldBe HttpStatusCode.Accepted
            response.bodyAsText() shouldContain "\"ok\":true"
            shutdownLatch.await(2, TimeUnit.SECONDS) shouldBe true
        }
    }

    test("tui session list route returns active sessions when authorized") {
        coEvery { tuiSessionService.listSessions() } returns listOf(
            TuiSession(
                id = "tui-1",
                workspaceId = "workspace-1",
                repositoryId = "repo-1",
                repositoryPath = "/tmp/repo",
                agentName = "codex",
                baseBranch = "master",
                status = TuiSessionStatus.RUNNING,
                transcript = "cotor>",
                createdAt = 1L,
                updatedAt = 2L
            )
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.get("/api/app/tui/sessions") {
                header("Authorization", "Bearer secret-token")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"id\":\"tui-1\""
            response.bodyAsText() shouldContain "\"repositoryPath\":\"/tmp/repo\""
            response.bodyAsText() shouldContain "\"status\":\"RUNNING\""
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

    test("mcp company_summary uses the read-only dashboard path") {
        coEvery { desktopService.companyDashboardReadOnly("company-1") } returns CompanyDashboardResponse()

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.post("/api/app/mcp") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", "application/json")
                setBody(
                    """
                    {
                      "jsonrpc": "2.0",
                      "id": 1,
                      "method": "tools/call",
                      "params": {
                        "name": "company_summary",
                        "arguments": {
                          "companyId": "company-1"
                        }
                      }
                    }
                    """.trimIndent()
                )
            }

            response.status shouldBe HttpStatusCode.OK
            coVerify(exactly = 1) { desktopService.companyDashboardReadOnly("company-1") }
            coVerify(exactly = 0) { desktopService.companyDashboard("company-1") }
        }
    }

    test("mcp companies resource uses the read-only dashboard path") {
        coEvery { desktopService.companyDashboardReadOnly() } returns CompanyDashboardResponse(
            companies = listOf(
                Company(
                    id = "company-1",
                    name = "Cotor",
                    rootPath = "/tmp/cotor",
                    repositoryId = "repo-1",
                    defaultBaseBranch = "main",
                    createdAt = 1L,
                    updatedAt = 1L
                )
            )
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.post("/api/app/mcp") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", "application/json")
                setBody(
                    """
                    {
                      "jsonrpc": "2.0",
                      "id": 1,
                      "method": "resources/read",
                      "params": {
                        "uri": "cotor://companies"
                      }
                    }
                    """.trimIndent()
                )
            }

            response.status shouldBe HttpStatusCode.OK
            coVerify(exactly = 1) { desktopService.companyDashboardReadOnly() }
            coVerify(exactly = 0) { desktopService.companyDashboard() }
        }
    }

    test("mcp resources read rejects blank uri") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.post("/api/app/mcp") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", "application/json")
                setBody(
                    """
                    {
                      "jsonrpc": "2.0",
                      "id": 1,
                      "method": "resources/read",
                      "params": {
                        "uri": ""
                      }
                    }
                    """.trimIndent()
                )
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "uri must not be blank"
        }
    }

    test("mcp tools list marks read-only tools explicitly") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.post("/api/app/mcp") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", "application/json")
                setBody(
                    """
                    {
                      "jsonrpc": "2.0",
                      "id": 1,
                      "method": "tools/list"
                    }
                    """.trimIndent()
                )
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"name\":\"company_summary\""
            response.bodyAsText() shouldContain "\"name\":\"company_memory_snapshot\""
            response.bodyAsText() shouldContain "\"readOnlyHint\":true"
            response.bodyAsText() shouldContain "\"name\":\"company_runtime_start\""
            response.bodyAsText() shouldContain "\"name\":\"company_runtime_stop\""
            response.bodyAsText() shouldContain "\"name\":\"company_review_qa\""
            response.bodyAsText() shouldContain "\"name\":\"company_review_ceo\""
        }
    }

    test("mcp initialize advertises a read-only surface explicitly") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.post("/api/app/mcp") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", "application/json")
                setBody(
                    """
                    {
                      "jsonrpc": "2.0",
                      "id": 1,
                      "method": "initialize"
                    }
                    """.trimIndent()
                )
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"readOnlySurface\":true"
            response.bodyAsText() shouldContain "\"listChanged\":false"
            response.bodyAsText() shouldContain "\"subscribe\":false"
        }
    }

    test("mcp runtime control tools mutate company runtime explicitly") {
        coEvery { desktopService.startCompanyRuntime("company-1") } returns CompanyRuntimeSnapshot(
            companyId = "company-1",
            status = CompanyRuntimeStatus.RUNNING,
            lastAction = "started"
        )
        coEvery { desktopService.stopCompanyRuntime("company-1") } returns CompanyRuntimeSnapshot(
            companyId = "company-1",
            status = CompanyRuntimeStatus.STOPPED,
            lastAction = "stopped"
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val startResponse = client.post("/api/app/mcp") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", "application/json")
                setBody(
                    """
                    {
                      "jsonrpc": "2.0",
                      "id": 1,
                      "method": "tools/call",
                      "params": {
                        "name": "company_runtime_start",
                        "arguments": { "companyId": "company-1" }
                      }
                    }
                    """.trimIndent()
                )
            }

            val stopResponse = client.post("/api/app/mcp") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", "application/json")
                setBody(
                    """
                    {
                      "jsonrpc": "2.0",
                      "id": 2,
                      "method": "tools/call",
                      "params": {
                        "name": "company_runtime_stop",
                        "arguments": { "companyId": "company-1" }
                      }
                    }
                    """.trimIndent()
                )
            }

            startResponse.status shouldBe HttpStatusCode.OK
            stopResponse.status shouldBe HttpStatusCode.OK
            startResponse.bodyAsText() shouldContain "RUNNING"
            stopResponse.bodyAsText() shouldContain "STOPPED"
            coVerify(exactly = 1) { desktopService.startCompanyRuntime("company-1") }
            coVerify(exactly = 1) { desktopService.stopCompanyRuntime("company-1") }
        }
    }

    test("mcp review verdict tools mutate review queue state explicitly") {
        coEvery { desktopService.submitQaReviewVerdict("queue-1", "PASS", "Looks good") } returns ReviewQueueItem(
            id = "queue-1",
            issueId = "issue-1",
            runId = "run-1",
            status = ReviewQueueStatus.READY_FOR_CEO,
            qaVerdict = "PASS",
            qaFeedback = "Looks good",
            createdAt = 1L,
            updatedAt = 2L
        )
        coEvery { desktopService.submitCeoReviewVerdict("queue-2", "APPROVE", "Ship it") } returns ReviewQueueItem(
            id = "queue-2",
            issueId = "issue-2",
            runId = "run-2",
            status = ReviewQueueStatus.MERGED,
            ceoVerdict = "APPROVE",
            ceoFeedback = "Ship it",
            createdAt = 1L,
            updatedAt = 2L
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val qaResponse = client.post("/api/app/mcp") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", "application/json")
                setBody(
                    """
                    {
                      "jsonrpc": "2.0",
                      "id": 1,
                      "method": "tools/call",
                      "params": {
                        "name": "company_review_qa",
                        "arguments": { "queueItemId": "queue-1", "verdict": "PASS", "feedback": "Looks good" }
                      }
                    }
                    """.trimIndent()
                )
            }

            val ceoResponse = client.post("/api/app/mcp") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", "application/json")
                setBody(
                    """
                    {
                      "jsonrpc": "2.0",
                      "id": 2,
                      "method": "tools/call",
                      "params": {
                        "name": "company_review_ceo",
                        "arguments": { "queueItemId": "queue-2", "verdict": "APPROVE", "feedback": "Ship it" }
                      }
                    }
                    """.trimIndent()
                )
            }

            qaResponse.status shouldBe HttpStatusCode.OK
            ceoResponse.status shouldBe HttpStatusCode.OK
            qaResponse.bodyAsText() shouldContain "READY_FOR_CEO"
            ceoResponse.bodyAsText() shouldContain "MERGED"
            coVerify(exactly = 1) { desktopService.submitQaReviewVerdict("queue-1", "PASS", "Looks good") }
            coVerify(exactly = 1) { desktopService.submitCeoReviewVerdict("queue-2", "APPROVE", "Ship it") }
        }
    }

    test("review queue verdict routes submit qa and ceo verdicts when authorized") {
        coEvery { desktopService.submitQaReviewVerdict("queue-1", "PASS", "Looks good") } returns ReviewQueueItem(
            id = "queue-1",
            issueId = "issue-1",
            runId = "run-1",
            status = ReviewQueueStatus.READY_FOR_CEO,
            qaVerdict = "PASS",
            qaFeedback = "Looks good",
            createdAt = 1L,
            updatedAt = 2L
        )
        coEvery { desktopService.submitCeoReviewVerdict("queue-2", "APPROVE", "Ship it") } returns ReviewQueueItem(
            id = "queue-2",
            issueId = "issue-2",
            runId = "run-2",
            status = ReviewQueueStatus.MERGED,
            ceoVerdict = "APPROVE",
            ceoFeedback = "Ship it",
            createdAt = 1L,
            updatedAt = 2L
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val qaResponse = client.post("/api/app/review-queue/queue-1/qa") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", "application/json")
                setBody("""{"verdict":"PASS","feedback":"Looks good"}""")
            }

            val ceoResponse = client.post("/api/app/review-queue/queue-2/ceo") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", "application/json")
                setBody("""{"verdict":"APPROVE","feedback":"Ship it"}""")
            }

            qaResponse.status shouldBe HttpStatusCode.OK
            ceoResponse.status shouldBe HttpStatusCode.OK
            qaResponse.bodyAsText() shouldContain "\"qaVerdict\":\"PASS\""
            ceoResponse.bodyAsText() shouldContain "\"ceoVerdict\":\"APPROVE\""
            coVerify(exactly = 1) { desktopService.submitQaReviewVerdict("queue-1", "PASS", "Looks good") }
            coVerify(exactly = 1) { desktopService.submitCeoReviewVerdict("queue-2", "APPROVE", "Ship it") }
        }
    }

    test("review queue merge route merges approved pull requests when authorized") {
        coEvery { desktopService.mergeReviewQueueItem("queue-3") } returns ReviewQueueItem(
            id = "queue-3",
            issueId = "issue-3",
            runId = "run-3",
            status = ReviewQueueStatus.MERGED,
            ceoVerdict = "APPROVE",
            pullRequestState = "MERGED",
            mergeCommitSha = "abc123",
            createdAt = 1L,
            updatedAt = 2L
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.post("/api/app/review-queue/queue-3/merge") {
                header("Authorization", "Bearer secret-token")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"status\":\"MERGED\""
            response.bodyAsText() shouldContain "\"mergeCommitSha\":\"abc123\""
            coVerify(exactly = 1) { desktopService.mergeReviewQueueItem("queue-3") }
        }
    }

    test("mcp company_memory_snapshot returns the unified memory snapshot") {
        coEvery { desktopService.companyMemorySnapshot("company-1", "issue-1", null) } returns CompanyMemorySnapshotResponse(
            companyMemory = "company=Cotor",
            workflowMemory = "goal=Improve onboarding",
            agentMemory = "role=CEO"
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.post("/api/app/mcp") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", "application/json")
                setBody(
                    """
                    {
                      "jsonrpc": "2.0",
                      "id": 3,
                      "method": "tools/call",
                      "params": {
                        "name": "company_memory_snapshot",
                        "arguments": { "companyId": "company-1", "issueId": "issue-1" }
                      }
                    }
                    """.trimIndent()
                )
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "company=Cotor"
            response.bodyAsText() shouldContain "goal=Improve onboarding"
            response.bodyAsText() shouldContain "role=CEO"
            coVerify(exactly = 1) { desktopService.companyMemorySnapshot("company-1", "issue-1", null) }
        }
    }

    test("mcp resources list marks read-only resources explicitly") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.post("/api/app/mcp") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", "application/json")
                setBody(
                    """
                    {
                      "jsonrpc": "2.0",
                      "id": 1,
                      "method": "resources/list"
                    }
                    """.trimIndent()
                )
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"uri\":\"cotor://companies\""
            response.bodyAsText() shouldContain "\"readOnlyHint\":true"
        }
    }

    test("company delete route removes a company when authorized") {
        coEvery { desktopService.deleteCompany("company-1") } returns Company(
            id = "company-1",
            name = "Cotor",
            rootPath = "/tmp/cotor",
            repositoryId = "repo-1",
            defaultBaseBranch = "master",
            createdAt = 1L,
            updatedAt = 1L
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.delete("/api/app/companies/company-1") {
                header("Authorization", "Bearer secret-token")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"id\":\"company-1\""
            response.bodyAsText() shouldContain "\"name\":\"Cotor\""
        }
    }

    test("company create route returns company-scoped GitHub readiness when authorized") {
        val company = Company(
            id = "company-1",
            name = "Cotor",
            rootPath = "/tmp/cotor",
            repositoryId = "repo-1",
            defaultBaseBranch = "master",
            createdAt = 1L,
            updatedAt = 1L
        )
        coEvery {
            desktopService.createCompany(
                name = "Cotor",
                rootPath = "/tmp/cotor",
                defaultBaseBranch = "master",
                autonomyEnabled = true
            )
        } returns company
        coEvery { desktopService.githubPublishStatus("company-1") } returns GitHubPublishStatus(
            policy = CodePublishMode.REQUIRE_GITHUB_PR,
            ghInstalled = true,
            ghAuthenticated = false,
            originConfigured = false,
            repositoryPath = "/tmp/cotor",
            companyId = "company-1",
            companyName = "Cotor",
            message = "GitHub PR mode requires an authenticated gh CLI session."
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.post("/api/app/companies") {
                header("Authorization", "Bearer secret-token")
                setBody(
                    """
                    {
                      "name": "Cotor",
                      "rootPath": "/tmp/cotor",
                      "defaultBaseBranch": "master",
                      "autonomyEnabled": true
                    }
                    """.trimIndent()
                )
                header("Content-Type", "application/json")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"company\":{\"id\":\"company-1\""
            response.bodyAsText() shouldContain "\"githubPublishStatus\":{\"policy\":\"REQUIRE_GITHUB_PR\""
            response.bodyAsText() shouldContain "\"ghAuthenticated\":false"
            response.bodyAsText() shouldContain "authenticated gh CLI session"
        }
    }

    test("company agent batch update route applies changes when authorized") {
        coEvery {
            desktopService.batchUpdateCompanyAgentDefinitions(
                companyId = "company-1",
                agentIds = listOf("agent-1", "agent-2"),
                agentCli = "opencode",
                specialties = listOf("qa", "verification"),
                enabled = false
            )
        } returns listOf(
            CompanyAgentDefinition(
                id = "agent-1",
                companyId = "company-1",
                title = "Builder",
                agentCli = "opencode",
                roleSummary = "builds features",
                specialties = listOf("qa", "verification"),
                enabled = false,
                displayOrder = 0,
                createdAt = 1L,
                updatedAt = 2L
            ),
            CompanyAgentDefinition(
                id = "agent-2",
                companyId = "company-1",
                title = "QA",
                agentCli = "opencode",
                roleSummary = "reviews work",
                specialties = listOf("qa", "verification"),
                enabled = false,
                displayOrder = 1,
                createdAt = 1L,
                updatedAt = 2L
            )
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.patch("/api/app/companies/company-1/agents/batch") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", "application/json")
                setBody(
                    """
                    {
                      "agentIds": ["agent-1", "agent-2"],
                      "agentCli": "opencode",
                      "specialties": ["qa", "verification"],
                      "enabled": false
                    }
                    """.trimIndent()
                )
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"id\":\"agent-1\""
            response.bodyAsText() shouldContain "\"agentCli\":\"opencode\""
            response.bodyAsText() shouldContain "\"enabled\":false"
        }
    }

    test("goal and issue delete routes remove scoped records when authorized") {
        coEvery { desktopService.listGoals() } returns listOf(
            CompanyGoal(
                id = "goal-1",
                companyId = "company-1",
                projectContextId = "project-1",
                title = "Goal",
                description = "Desc",
                status = GoalStatus.ACTIVE,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        coEvery { desktopService.deleteGoal("goal-1") } returns CompanyGoal(
            id = "goal-1",
            companyId = "company-1",
            projectContextId = "project-1",
            title = "Goal",
            description = "Desc",
            status = GoalStatus.ACTIVE,
            createdAt = 1L,
            updatedAt = 1L
        )
        coEvery { desktopService.listIssues(companyId = "company-1") } returns listOf(
            CompanyIssue(
                id = "issue-1",
                companyId = "company-1",
                projectContextId = "project-1",
                goalId = "goal-1",
                workspaceId = "workspace-1",
                title = "Issue",
                description = "Issue desc",
                status = IssueStatus.PLANNED,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        coEvery { desktopService.deleteIssue("issue-1") } returns CompanyIssue(
            id = "issue-1",
            companyId = "company-1",
            projectContextId = "project-1",
            goalId = "goal-1",
            workspaceId = "workspace-1",
            title = "Issue",
            description = "Issue desc",
            status = IssueStatus.PLANNED,
            createdAt = 1L,
            updatedAt = 1L
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val goalResponse = client.delete("/api/app/companies/company-1/goals/goal-1") {
                header("Authorization", "Bearer secret-token")
            }
            goalResponse.status shouldBe HttpStatusCode.OK
            goalResponse.bodyAsText() shouldContain "\"id\":\"goal-1\""

            val issueResponse = client.delete("/api/app/companies/company-1/issues/issue-1") {
                header("Authorization", "Bearer secret-token")
            }
            issueResponse.status shouldBe HttpStatusCode.OK
            issueResponse.bodyAsText() shouldContain "\"id\":\"issue-1\""

            coEvery { desktopService.getIssueProjected("issue-1") } returns CompanyIssue(
                id = "issue-1",
                companyId = "company-1",
                projectContextId = "project-1",
                goalId = "goal-1",
                workspaceId = "workspace-1",
                title = "Issue",
                description = "Issue desc",
                status = IssueStatus.PLANNED,
                codeProducing = true,
                branchName = "codex/cotor/issue-1",
                worktreePath = "/tmp/cotor/.cotor/worktrees/issue-1/codex",
                pullRequestNumber = 99,
                pullRequestUrl = "https://github.com/heodongun/cotor/pull/99",
                pullRequestState = "OPEN",
                qaVerdict = "PASS",
                qaFeedback = "Looks good.",
                ceoVerdict = "APPROVE",
                ceoFeedback = "Ship it.",
                mergeResult = "MERGED",
                transitionReason = "CEO approved and merged the PR.",
                createdAt = 1L,
                updatedAt = 1L
            )

            val issueDetailResponse = client.get("/api/app/companies/company-1/issues/issue-1") {
                header("Authorization", "Bearer secret-token")
            }
            issueDetailResponse.status shouldBe HttpStatusCode.OK
            issueDetailResponse.bodyAsText() shouldContain "\"id\":\"issue-1\""
            issueDetailResponse.bodyAsText() shouldContain "\"pullRequestNumber\":99"
            issueDetailResponse.bodyAsText() shouldContain "\"qaVerdict\":\"PASS\""
            issueDetailResponse.bodyAsText() shouldContain "\"ceoVerdict\":\"APPROVE\""
            coVerify(exactly = 1) { desktopService.getIssueProjected("issue-1") }
            coVerify(exactly = 0) { desktopService.getIssue("issue-1") }
        }
    }

    test("issue runs route returns all runs linked to an issue when authorized") {
        coEvery { desktopService.listIssueRuns("issue-1") } returns listOf(
            AgentRun(
                id = "run-1",
                taskId = "task-1",
                workspaceId = "workspace-1",
                repositoryId = "repo-1",
                agentId = "agent-1",
                agentName = "codex",
                repoRoot = "/tmp/repo",
                baseBranch = "master",
                branchName = "codex/task-1",
                worktreePath = "/tmp/repo/.cotor/worktrees/task-1",
                status = AgentRunStatus.COMPLETED,
                output = "reviewed implementation successfully",
                createdAt = 1L,
                updatedAt = 2L
            )
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.get("/api/app/issues/issue-1/runs") {
                header("Authorization", "Bearer secret-token")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"id\":\"run-1\""
            response.bodyAsText() shouldContain "reviewed implementation successfully"
        }
    }

    test("issue execution details route returns per-agent execution logs when authorized") {
        coEvery { desktopService.issueExecutionDetails("issue-1") } returns listOf(
            IssueAgentExecutionDetail(
                roleName = "QA",
                agentName = "opencode",
                agentCli = "opencode",
                assignedPrompt = "Review the latest PR and summarize risk.",
                taskId = "task-1",
                taskStatus = "COMPLETED",
                runId = "run-1",
                runStatus = "COMPLETED",
                stdout = "QA_VERDICT: PASS",
                stderr = null,
                branchName = "opencode/qa",
                pullRequestUrl = "https://github.com/bssm-oss/cotor-test/pull/1",
                updatedAt = 2L,
                publishSummary = "review queue: READY_FOR_CEO"
            )
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.get("/api/app/issues/issue-1/execution-details") {
                header("Authorization", "Bearer secret-token")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"roleName\":\"QA\""
            response.bodyAsText() shouldContain "\"stdout\":\"QA_VERDICT: PASS\""
        }
    }

    test("company execution log route returns nested execution data when authorized") {
        coEvery { desktopService.executionLog("company-1") } returns listOf(
            mapOf(
                "issueId" to "issue-1",
                "issueTitle" to "Ship README change",
                "issueStatus" to "IN_PROGRESS",
                "tasks" to listOf(
                    mapOf(
                        "taskId" to "task-1",
                        "status" to "RUNNING",
                        "runs" to listOf(
                            mapOf(
                                "runId" to "run-1",
                                "agent" to "opencode",
                                "status" to "RUNNING",
                                "processId" to 42L
                            )
                        )
                    )
                )
            )
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.get("/api/app/companies/company-1/execution-log") {
                header("Authorization", "Bearer secret-token")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"issueId\":\"issue-1\""
            response.bodyAsText() shouldContain "\"agent\":\"opencode\""
            response.bodyAsText() shouldContain "\"processId\":42"
        }
    }

    test("backend settings and company topology routes respond when authorized") {
        coEvery { desktopService.backendStatuses() } returns listOf(
            ExecutionBackendStatus(
                kind = ExecutionBackendKind.LOCAL_COTOR,
                displayName = "Local Cotor",
                health = "healthy",
                config = BackendConnectionConfig(kind = ExecutionBackendKind.LOCAL_COTOR)
            )
        )
        coEvery {
            desktopService.testBackend(
                kind = ExecutionBackendKind.CODEX_APP_SERVER,
                baseUrl = "http://127.0.0.1:8788",
                authMode = null,
                token = null,
                timeoutSeconds = null
            )
        } returns ExecutionBackendStatus(
            kind = ExecutionBackendKind.CODEX_APP_SERVER,
            displayName = "Codex App Server",
            health = "healthy",
            message = "Connected",
            config = BackendConnectionConfig(
                kind = ExecutionBackendKind.CODEX_APP_SERVER,
                baseUrl = "http://127.0.0.1:8788"
            )
        )
        coEvery {
            desktopService.updateCompanyBackend(
                companyId = "company-1",
                backendKind = ExecutionBackendKind.CODEX_APP_SERVER,
                baseUrl = "http://127.0.0.1:8788",
                authMode = null,
                token = null,
                timeoutSeconds = null,
                useGlobalDefault = false
            )
        } returns Company(
            id = "company-1",
            name = "Cotor",
            rootPath = "/tmp/cotor",
            repositoryId = "repo-1",
            defaultBaseBranch = "master",
            backendKind = ExecutionBackendKind.CODEX_APP_SERVER,
            createdAt = 1L,
            updatedAt = 1L
        )
        coEvery { desktopService.listWorkflowTopologies("company-1") } returns listOf(
            WorkflowTopologySnapshot(
                companyId = "company-1",
                agents = listOf("CEO", "Builder"),
                edges = listOf(
                    AgentCollaborationEdge(
                        companyId = "company-1",
                        fromAgentId = "agent-ceo",
                        toAgentId = "agent-builder",
                        reason = "CEO routes implementation",
                        handoffType = "coordination"
                    )
                )
            )
        )
        coEvery { desktopService.listGoalDecisions("company-1") } returns listOf(
            GoalOrchestrationDecision(
                id = "decision-1",
                companyId = "company-1",
                title = "Created execution graph",
                summary = "Planned two issues.",
                createdAt = 1L
            )
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val backends = client.get("/api/app/settings/backends") {
                header("Authorization", "Bearer secret-token")
            }
            backends.status shouldBe HttpStatusCode.OK
            backends.bodyAsText() shouldContain "\"kind\":\"LOCAL_COTOR\""

            val tested = client.post("/api/app/settings/backends/test") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", "application/json")
                setBody("""{"kind":"CODEX_APP_SERVER","baseUrl":"http://127.0.0.1:8788"}""")
            }
            tested.status shouldBe HttpStatusCode.OK
            tested.bodyAsText() shouldContain "\"kind\":\"CODEX_APP_SERVER\""

            val updated = client.patch("/api/app/companies/company-1/backend") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", "application/json")
                setBody("""{"backendKind":"CODEX_APP_SERVER","baseUrl":"http://127.0.0.1:8788","useGlobalDefault":false}""")
            }
            updated.status shouldBe HttpStatusCode.OK
            updated.bodyAsText() shouldContain "\"backendKind\":\"CODEX_APP_SERVER\""

            val topology = client.get("/api/app/companies/company-1/topology") {
                header("Authorization", "Bearer secret-token")
            }
            topology.status shouldBe HttpStatusCode.OK
            topology.bodyAsText() shouldContain "\"handoffType\":\"coordination\""

            val decisions = client.get("/api/app/companies/company-1/decisions") {
                header("Authorization", "Bearer secret-token")
            }
            decisions.status shouldBe HttpStatusCode.OK
            decisions.bodyAsText() shouldContain "\"title\":\"Created execution graph\""
        }
    }

    test("company Linear routes update config and trigger sync when authorized") {
        coEvery {
            desktopService.updateCompanyLinear(
                companyId = "company-1",
                enabled = true,
                endpoint = "https://api.linear.app/graphql",
                apiToken = "token-1",
                teamId = "team-1",
                projectId = "project-1",
                stateMappings = any(),
                useGlobalDefault = false
            )
        } returns Company(
            id = "company-1",
            name = "Linear Co",
            rootPath = "/tmp/linear",
            repositoryId = "repo-1",
            defaultBaseBranch = "master",
            linearSyncEnabled = true,
            linearConfigOverride = LinearConnectionConfig(
                endpoint = "https://api.linear.app/graphql",
                apiToken = "token-1",
                teamId = "team-1",
                projectId = "project-1"
            ),
            createdAt = 1L,
            updatedAt = 1L
        )
        coEvery { desktopService.syncCompanyLinear("company-1") } returns LinearSyncResponse(
            ok = true,
            message = "Synced 2 issues to Linear",
            syncedIssues = 2,
            createdIssues = 2
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val update = client.patch("/api/app/companies/company-1/linear") {
                header("Authorization", "Bearer secret-token")
                setBody(
                    """
                    {
                      "enabled": true,
                      "endpoint": "https://api.linear.app/graphql",
                      "apiToken": "token-1",
                      "teamId": "team-1",
                      "projectId": "project-1",
                      "stateMappings": [
                        {"localStatus":"IN_PROGRESS","linearStateName":"In Progress"}
                      ]
                    }
                    """.trimIndent()
                )
                header("Content-Type", "application/json")
            }
            update.status shouldBe HttpStatusCode.OK
            update.bodyAsText() shouldContain "\"linearSyncEnabled\":true"

            val sync = client.post("/api/app/companies/company-1/linear/resync") {
                header("Authorization", "Bearer secret-token")
            }
            sync.status shouldBe HttpStatusCode.OK
            sync.bodyAsText() shouldContain "\"ok\":true"
            sync.bodyAsText() shouldContain "\"syncedIssues\":2"
        }
    }

    test("task detail routes return empty payloads when no agent run exists yet") {
        coEvery { desktopService.getTask("task-1") } returns AgentTask(
            id = "task-1",
            workspaceId = "workspace-1",
            title = "Implement",
            prompt = "Do work",
            agents = listOf("codex"),
            status = DesktopTaskStatus.QUEUED,
            createdAt = 1L,
            updatedAt = 1L
        )
        coEvery { desktopService.listWorkspaces(any()) } returns listOf(
            Workspace(
                id = "workspace-1",
                repositoryId = "repo-1",
                name = "repo · master",
                baseBranch = "master",
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        coEvery { desktopService.listRuns("task-1") } returns emptyList()

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val changes = client.get("/api/app/tasks/task-1/changes/codex") {
                header("Authorization", "Bearer secret-token")
            }
            changes.status shouldBe HttpStatusCode.OK
            changes.bodyAsText() shouldContain "\"baseBranch\":\"master\""
            changes.bodyAsText() shouldContain "\"changedFiles\":[]"

            val files = client.get("/api/app/tasks/task-1/files/codex") {
                header("Authorization", "Bearer secret-token")
            }
            files.status shouldBe HttpStatusCode.OK
            files.bodyAsText() shouldBe "[]"

            val ports = client.get("/api/app/tasks/task-1/ports/codex") {
                header("Authorization", "Bearer secret-token")
            }
            ports.status shouldBe HttpStatusCode.OK
            ports.bodyAsText() shouldBe "[]"
        }
    }

    test("company issue create route creates a scoped issue when authorized") {
        coEvery { desktopService.createIssue("company-1", "goal-1", "Issue", "Issue desc", 3, "manual") } returns CompanyIssue(
            id = "issue-1",
            companyId = "company-1",
            projectContextId = "project-1",
            goalId = "goal-1",
            workspaceId = "workspace-1",
            title = "Issue",
            description = "Issue desc",
            status = IssueStatus.DELEGATED,
            createdAt = 1L,
            updatedAt = 1L
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.post("/api/app/companies/company-1/issues") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", "application/json")
                setBody(
                    """
                    {
                      "goalId": "goal-1",
                      "title": "Issue",
                      "description": "Issue desc"
                    }
                    """.trimIndent()
                )
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"id\":\"issue-1\""
            response.bodyAsText() shouldContain "\"companyId\":\"company-1\""
        }
    }

    test("company dashboard route returns a company-scoped live snapshot when authorized") {
        coEvery { desktopService.companyDashboardReadOnly("company-1") } returns CompanyDashboardResponse(
            companies = listOf(
                Company(
                    id = "company-1",
                    name = "Cotor",
                    rootPath = "/tmp/cotor",
                    repositoryId = "repo-1",
                    defaultBaseBranch = "master",
                    dailyBudgetCents = 1500,
                    monthlyBudgetCents = 12000,
                    createdAt = 1L,
                    updatedAt = 1L
                )
            ),
            issues = listOf(
                CompanyIssue(
                    id = "issue-1",
                    companyId = "company-1",
                    projectContextId = "project-1",
                    goalId = "goal-1",
                    workspaceId = "workspace-1",
                    title = "Issue",
                    description = "Issue desc",
                    status = IssueStatus.IN_PROGRESS,
                    kind = "execution",
                    createdAt = 1L,
                    updatedAt = 2L
                )
            ),
            tasks = listOf(
                AgentTask(
                    id = "task-1",
                    workspaceId = "workspace-1",
                    issueId = "issue-1",
                    title = "Issue",
                    prompt = "Issue desc",
                    agents = listOf("codex"),
                    status = DesktopTaskStatus.RUNNING,
                    createdAt = 1L,
                    updatedAt = 2L
                )
            ),
            runtime = CompanyRuntimeSnapshot(
                companyId = "company-1",
                status = CompanyRuntimeStatus.RUNNING,
                lastAction = "monitoring-active-runs",
                todaySpentCents = 245,
                monthSpentCents = 1180,
                budgetPausedAt = 5L,
                budgetResetDate = "2026-03-28"
            )
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.get("/api/app/companies/company-1/dashboard") {
                header("Authorization", "Bearer secret-token")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"issues\":[{\"id\":\"issue-1\""
            response.bodyAsText() shouldContain "\"tasks\":[{\"id\":\"task-1\""
            response.bodyAsText() shouldContain "\"issueDependencies\":[]"
            response.bodyAsText() shouldContain "\"reviewQueue\":[]"
            response.bodyAsText() shouldContain "\"workflowTopologies\":[]"
            response.bodyAsText() shouldContain "\"goalDecisions\":[]"
            response.bodyAsText() shouldContain "\"runningAgentSessions\":[]"
            response.bodyAsText() shouldContain "\"signals\":[]"
            response.bodyAsText() shouldContain "\"activity\":[]"
            response.bodyAsText() shouldContain "\"lastAction\":\"monitoring-active-runs\""
            response.bodyAsText() shouldContain "\"tickIntervalSeconds\":60"
            response.bodyAsText() shouldContain "\"backendKind\":\"LOCAL_COTOR\""
            response.bodyAsText() shouldContain "\"backendHealth\":\"unknown\""
            response.bodyAsText() shouldContain "\"backendLifecycleState\":\"STOPPED\""
            response.bodyAsText() shouldContain "\"dailyBudgetCents\":1500"
            response.bodyAsText() shouldContain "\"monthlyBudgetCents\":12000"
            response.bodyAsText() shouldContain "\"todaySpentCents\":245"
            response.bodyAsText() shouldContain "\"monthSpentCents\":1180"
            response.bodyAsText() shouldContain "\"budgetPausedAt\":5"
            coVerify(exactly = 1) { desktopService.companyDashboardReadOnly("company-1") }
            coVerify(exactly = 0) { desktopService.companyDashboard("company-1") }
        }
    }

    test("company patch route accepts spend guardrails when authorized") {
        coEvery {
            desktopService.updateCompany(
                companyId = "company-1",
                name = null,
                defaultBaseBranch = null,
                autonomyEnabled = null,
                backendKind = null,
                dailyBudgetCents = 1500,
                monthlyBudgetCents = 12000
            )
        } returns Company(
            id = "company-1",
            name = "Cotor",
            rootPath = "/tmp/cotor",
            repositoryId = "repo-1",
            defaultBaseBranch = "master",
            dailyBudgetCents = 1500,
            monthlyBudgetCents = 12000,
            createdAt = 1L,
            updatedAt = 2L
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.patch("/api/app/companies/company-1") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", "application/json")
                setBody("""{"dailyBudgetCents":1500,"monthlyBudgetCents":12000}""")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"dailyBudgetCents\":1500"
            response.bodyAsText() shouldContain "\"monthlyBudgetCents\":12000"
        }
    }

    test("company event stream includes the company dashboard snapshot when authorized") {
        every { desktopService.companyEvents("company-1") } returns flowOf(
            CompanyEventEnvelope(
                event = CompanyEvent(
                    id = "event-1",
                    companyId = "company-1",
                    type = "runtime.updated",
                    title = "Runtime updated",
                    createdAt = 1L
                ),
                companyDashboard = CompanyDashboardResponse(
                    companies = listOf(
                        Company(
                            id = "company-1",
                            name = "Cotor",
                            rootPath = "/tmp/cotor",
                            repositoryId = "repo-1",
                            defaultBaseBranch = "master",
                            createdAt = 1L,
                            updatedAt = 1L
                        )
                    ),
                    activity = listOf(
                        CompanyActivityItem(
                            id = "activity-1",
                            companyId = "company-1",
                            source = "runtime",
                            title = "Started issue run",
                            createdAt = 1L
                        )
                    ),
                    runtime = CompanyRuntimeSnapshot(
                        companyId = "company-1",
                        status = CompanyRuntimeStatus.RUNNING,
                        lastAction = "monitoring-active-runs"
                    )
                )
            )
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.get("/api/app/companies/company-1/events") {
                header("Authorization", "Bearer secret-token")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"companyDashboard\""
            response.bodyAsText() shouldContain "\"tasks\":[]"
            response.bodyAsText() shouldContain "\"issueDependencies\":[]"
            response.bodyAsText() shouldContain "\"reviewQueue\":[]"
            response.bodyAsText() shouldContain "\"workflowTopologies\":[]"
            response.bodyAsText() shouldContain "\"goalDecisions\":[]"
            response.bodyAsText() shouldContain "\"runningAgentSessions\":[]"
            response.bodyAsText() shouldContain "\"signals\":[]"
            response.bodyAsText() shouldContain "\"activity\":[{\"id\":\"activity-1\""
            response.bodyAsText() shouldContain "\"lastAction\":\"monitoring-active-runs\""
            response.bodyAsText() shouldContain "\"tickIntervalSeconds\":60"
            response.bodyAsText() shouldContain "\"backendKind\":\"LOCAL_COTOR\""
            response.bodyAsText() shouldContain "\"backendHealth\":\"unknown\""
            response.bodyAsText() shouldContain "\"backendLifecycleState\":\"STOPPED\""
        }
    }
})
