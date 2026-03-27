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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

            coEvery { desktopService.getIssue("issue-1") } returns CompanyIssue(
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
        coEvery { desktopService.companyDashboard("company-1") } returns CompanyDashboardResponse(
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
                lastAction = "monitoring-active-runs"
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
