package com.cotor.app

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

            val issueDetailResponse = client.get("/api/app/companies/company-1/issues/issue-1") {
                header("Authorization", "Bearer secret-token")
            }
            issueDetailResponse.status shouldBe HttpStatusCode.OK
            issueDetailResponse.bodyAsText() shouldContain "\"id\":\"issue-1\""
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
        coEvery { desktopService.companyDashboard("company-1") } returns CompanyDashboardResponse(
            workflowTopologies = listOf(
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
            ),
            goalDecisions = listOf(
                GoalOrchestrationDecision(
                    id = "decision-1",
                    companyId = "company-1",
                    title = "Created execution graph",
                    summary = "Planned two issues.",
                    createdAt = 1L
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
})
