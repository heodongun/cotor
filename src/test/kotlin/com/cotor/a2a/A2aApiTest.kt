package com.cotor.a2a

import com.cotor.app.AgentRun
import com.cotor.app.AgentRunStatus
import com.cotor.app.DesktopAppService
import com.cotor.app.DesktopTuiSessionService
import com.cotor.app.cotorAppModule
import com.cotor.app.defaultDesktopAppHome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class A2aApiTest : FunSpec({
    val desktopService = mockk<DesktopAppService>(relaxed = true)
    val tuiSessionService = mockk<DesktopTuiSessionService>(relaxed = true)
    val json = Json { encodeDefaults = true }

    beforeTest {
        clearMocks(desktopService, tuiSessionService, answers = false, recordedCalls = true)
        val a2aDir = defaultDesktopAppHome().resolve("a2a")
        if (a2aDir.exists()) {
            a2aDir.deleteRecursively()
        }
    }

    fun envelope(
        dedupeKey: String = "company-1:issue-1:task-1:assign:v1",
        ttlMs: Long = 60_000,
        type: String = "task.assign",
        ts: Long = System.currentTimeMillis()
    ) = A2aEnvelope(
        v = "a2a.v1",
        id = "message-1",
        type = type,
        ts = ts,
        tenant = A2aTenant(companyId = "company-1"),
        from = A2aParty(agentId = "agent-ceo", roleName = "CEO", executionAgentName = "opencode"),
        to = listOf(A2aParty(agentId = "agent-builder", roleName = "Builder", executionAgentName = "opencode")),
        dedupeKey = dedupeKey,
        ttlMs = ttlMs,
        body = buildJsonObject {
            put("title", "Implement smallest change")
        }
    )

    test("auth missing returns unauthorized") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.post("/api/a2a/v1/sessions") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(A2aHelloRequest(agentId = "agent-1", capabilities = listOf("task.assign"), tenant = A2aTenant("company-1"))))
            }

            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("duplicate dedupe key returns already_processed") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val hello = client.post("/api/a2a/v1/sessions") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(A2aHelloRequest(agentId = "agent-builder", capabilities = listOf("task.assign"), tenant = A2aTenant("company-1"))))
            }
            hello.status shouldBe HttpStatusCode.OK

            val first = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(envelope()))
            }
            first.status shouldBe HttpStatusCode.OK
            first.bodyAsText() shouldContain "\"dedupeStatus\":\"accepted\""

            val second = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(envelope()))
            }
            second.status shouldBe HttpStatusCode.OK
            second.bodyAsText() shouldContain "\"dedupeStatus\":\"already_processed\""
        }
    }

    test("same dedupe key is accepted independently for different companies") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val firstCompanyEnvelope = envelope(dedupeKey = "shared-key").copy(
                id = "message-company-1",
                tenant = A2aTenant(companyId = "company-1")
            )
            val secondCompanyEnvelope = envelope(dedupeKey = "shared-key").copy(
                id = "message-company-2",
                tenant = A2aTenant(companyId = "company-2")
            )

            val first = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(firstCompanyEnvelope))
            }
            val second = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(secondCompanyEnvelope))
            }

            first.status shouldBe HttpStatusCode.OK
            second.status shouldBe HttpStatusCode.OK
            first.bodyAsText() shouldContain "\"dedupeStatus\":\"accepted\""
            second.bodyAsText() shouldContain "\"dedupeStatus\":\"accepted\""
        }
    }

    test("task.assign routes through canonical task assignment ingestion") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val assignment = envelope(dedupeKey = "task-assign-key", type = "task.assign").copy(
                to = listOf(A2aParty(agentId = "agent-builder", roleName = "Builder")),
                correlation = A2aCorrelation(issueId = "issue-1", goalId = "goal-1"),
                body = buildJsonObject {
                    put("title", "Implement smallest change")
                    put("message", "Take ownership of the issue and report back with a handoff if blocked.")
                }
            )

            val response = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(assignment))
            }

            response.status shouldBe HttpStatusCode.OK
            coVerify(exactly = 1) {
                desktopService.ingestA2aTaskAssignment(
                    companyId = "company-1",
                    fromAgentName = "CEO",
                    toAgentName = "Builder",
                    title = "Implement smallest change",
                    content = "Take ownership of the issue and report back with a handoff if blocked.",
                    issueId = "issue-1",
                    goalId = "goal-1"
                )
            }
        }
    }

    test("task.accept is mirrored through canonical note ingestion") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val accepted = envelope(dedupeKey = "task-accept-key", type = "task.accept").copy(
                correlation = A2aCorrelation(issueId = "issue-1", goalId = "goal-1"),
                body = buildJsonObject {
                    put("title", "Accepted implementation task")
                    put("message", "Builder accepted the task and is starting execution.")
                }
            )

            val response = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(accepted))
            }

            response.status shouldBe HttpStatusCode.OK
            coVerify(exactly = 1) {
                desktopService.ingestAgentNote(
                    companyId = "company-1",
                    agentName = "CEO",
                    title = "Accepted implementation task",
                    content = "Builder accepted the task and is starting execution.",
                    issueId = "issue-1",
                    goalId = "goal-1",
                    visibility = "goal"
                )
            }
        }
    }

    test("heartbeat updates an existing A2A session") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val hello = client.post("/api/a2a/v1/sessions") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(A2aHelloRequest(agentId = "agent-builder", capabilities = listOf("task.assign"), tenant = A2aTenant("company-1"))))
            }
            hello.status shouldBe HttpStatusCode.OK
            val sessionId = hello.bodyAsText().substringAfter("\"sessionId\":\"").substringBefore('"')

            val heartbeat = client.post("/api/a2a/v1/sessions/$sessionId/heartbeat") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
            }

            heartbeat.status shouldBe HttpStatusCode.OK
            heartbeat.bodyAsText() shouldContain "\"sessionId\":\"$sessionId\""
            heartbeat.bodyAsText() shouldContain "\"ok\":true"
        }
    }

    test("heartbeat rejects an unknown session") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val heartbeat = client.post("/api/a2a/v1/sessions/missing-session/heartbeat") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
            }

            heartbeat.status shouldBe HttpStatusCode.NotFound
            heartbeat.bodyAsText() shouldContain "Unknown session"
        }
    }

    test("expired sessions are pruned and heartbeat returns unknown session") {
        val customRouter = A2aRouter(
            desktopService = desktopService,
            sessionStore = A2aSessionStore(heartbeatIntervalMs = 10, sessionTtlMs = 20)
        )
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService,
                    a2aRouter = customRouter
                )
            }

            val hello = client.post("/api/a2a/v1/sessions") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(A2aHelloRequest(agentId = "agent-builder", capabilities = listOf("task.assign"), tenant = A2aTenant("company-1"))))
            }
            hello.status shouldBe HttpStatusCode.OK
            val sessionId = hello.bodyAsText().substringAfter("\"sessionId\":\"").substringBefore('"')

            Thread.sleep(30)

            val heartbeat = client.post("/api/a2a/v1/sessions/$sessionId/heartbeat") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
            }

            heartbeat.status shouldBe HttpStatusCode.NotFound
            heartbeat.bodyAsText() shouldContain "Unknown session"
        }
    }

    test("cotorAppModule honors the injected A2A router instance") {
        val customRouter = A2aRouter(
            desktopService = desktopService,
            sessionStore = A2aSessionStore(heartbeatIntervalMs = 42)
        )
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService,
                    a2aRouter = customRouter
                )
            }

            val hello = client.post("/api/a2a/v1/sessions") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(A2aHelloRequest(agentId = "agent-builder", capabilities = listOf("task.assign"), tenant = A2aTenant("company-1"))))
            }

            hello.status shouldBe HttpStatusCode.OK
            hello.bodyAsText() shouldContain "\"heartbeatIntervalMs\":42"
        }
    }

    test("expired ttl is rejected") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(envelope(ttlMs = 1, ts = 1)))
            }

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "\"code\":\"expired_message\""
        }
    }

    test("pull consumes messages in fifo order") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val hello = client.post("/api/a2a/v1/sessions") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(A2aHelloRequest(agentId = "agent-builder", capabilities = listOf("task.assign"), tenant = A2aTenant("company-1"))))
            }
            hello.status shouldBe HttpStatusCode.OK
            val sessionId = hello.bodyAsText().substringAfter("\"sessionId\":\"").substringBefore('"')

            client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(envelope(dedupeKey = "k1", type = "message.note")))
            }
            client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(envelope(dedupeKey = "k2", type = "message.handoff", ts = System.currentTimeMillis() + 1)))
            }

            val firstPull = client.get("/api/a2a/v1/messages/pull?session_id=$sessionId&limit=10") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
            }
            firstPull.status shouldBe HttpStatusCode.OK
            firstPull.bodyAsText() shouldContain "\"messages\":["
            firstPull.bodyAsText() shouldContain "\"type\":\"message.note\""
            firstPull.bodyAsText() shouldContain "\"type\":\"message.handoff\""

            val secondPull = client.get("/api/a2a/v1/messages/pull?session_id=$sessionId&limit=10") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
            }
            secondPull.status shouldBe HttpStatusCode.OK
            secondPull.bodyAsText() shouldContain "\"messages\":[]"
        }
    }

    test("pull with after cursor does not discard unseen later messages") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val hello = client.post("/api/a2a/v1/sessions") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(A2aHelloRequest(agentId = "agent-builder", capabilities = listOf("task.assign"), tenant = A2aTenant("company-1"))))
            }
            hello.status shouldBe HttpStatusCode.OK
            val sessionId = hello.bodyAsText().substringAfter("\"sessionId\":\"").substringBefore('"')

            client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(envelope(dedupeKey = "k3", type = "message.note", ts = System.currentTimeMillis() + 2)))
            }
            client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(envelope(dedupeKey = "k4", type = "message.handoff", ts = System.currentTimeMillis() + 3)))
            }

            val skippedPull = client.get("/api/a2a/v1/messages/pull?session_id=$sessionId&after=1&limit=10") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
            }
            skippedPull.status shouldBe HttpStatusCode.OK
            skippedPull.bodyAsText() shouldContain "\"type\":\"message.handoff\""

            val secondPull = client.get("/api/a2a/v1/messages/pull?session_id=$sessionId&after=2&limit=10") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
            }
            secondPull.status shouldBe HttpStatusCode.OK
            secondPull.bodyAsText() shouldContain "\"messages\":[]"
        }
    }

    test("unknown message type is rejected") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(envelope(type = "unknown.type", dedupeKey = "unknown-key")))
            }

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "\"code\":\"unsupported_type\""
        }
    }

    test("snapshot returns company dashboard payload") {
        coEvery { desktopService.companyDashboardReadOnly("company-1") } returns com.cotor.app.CompanyDashboardResponse()

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.post("/api/a2a/v1/sync/snapshot") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(A2aSnapshotRequest(tenant = A2aTenant(companyId = "company-1"))))
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"dashboard\""
            response.bodyAsText() shouldContain "\"companies\""
            coVerify(exactly = 1) { desktopService.companyDashboardReadOnly("company-1") }
            coVerify(exactly = 0) { desktopService.companyDashboard("company-1") }
        }
    }

    test("sync.snapshot.request enqueues a sync.snapshot.response envelope for the requester session") {
        coEvery { desktopService.companyDashboardReadOnly("company-1") } returns com.cotor.app.CompanyDashboardResponse()

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val hello = client.post("/api/a2a/v1/sessions") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(A2aHelloRequest(agentId = "agent-ceo", executionAgentName = "opencode", capabilities = listOf("sync.snapshot.request"), tenant = A2aTenant("company-1"))))
            }
            hello.status shouldBe HttpStatusCode.OK
            val sessionId = hello.bodyAsText().substringAfter("\"sessionId\":\"").substringBefore('"')

            val requestEnvelope = envelope(dedupeKey = "snapshot-request-key", type = "sync.snapshot.request")

            val postResponse = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(requestEnvelope))
            }

            val pullResponse = client.get("/api/a2a/v1/messages/pull?session_id=$sessionId&limit=10") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
            }

            postResponse.status shouldBe HttpStatusCode.OK
            pullResponse.status shouldBe HttpStatusCode.OK
            pullResponse.bodyAsText() shouldContain "\"type\":\"sync.snapshot.response\""
            pullResponse.bodyAsText() shouldContain "\"dashboard\""
            pullResponse.bodyAsText() shouldContain "\"messageId\":\"message-1\""
            coVerify(exactly = 1) { desktopService.companyDashboardReadOnly("company-1") }
        }
    }

    test("artifact registration keeps only a bounded in-memory history") {
        val router = A2aRouter(
            desktopService = desktopService,
            sessionStore = A2aSessionStore(),
            dedupeStore = A2aDedupeStore()
        )

        repeat(1_005) { index ->
            router.registerArtifact(
                A2aArtifactRegistrationRequest(
                    tenant = A2aTenant(companyId = "company-1"),
                    kind = "log",
                    label = "artifact-$index"
                ),
                now = index.toLong()
            )
        }

        router.artifactCount() shouldBe 1_000
    }

    test("artifacts can be listed back with tenant-scoped filtering") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            client.post("/api/a2a/v1/artifacts") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        A2aArtifactRegistrationRequest(
                            tenant = A2aTenant(companyId = "company-1"),
                            kind = "log",
                            label = "artifact-1",
                            issueId = "issue-1",
                            runId = "run-1"
                        )
                    )
                )
            }
            client.post("/api/a2a/v1/artifacts") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        A2aArtifactRegistrationRequest(
                            tenant = A2aTenant(companyId = "company-1"),
                            kind = "report",
                            label = "artifact-2",
                            issueId = "issue-2",
                            runId = "run-2"
                        )
                    )
                )
            }
            client.post("/api/a2a/v1/artifacts") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        A2aArtifactRegistrationRequest(
                            tenant = A2aTenant(companyId = "company-2"),
                            kind = "report",
                            label = "artifact-3",
                            issueId = "issue-3",
                            runId = "run-3"
                        )
                    )
                )
            }

            val response = client.get("/api/a2a/v1/artifacts?company_id=company-1&issue_id=issue-1") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "artifact-1"
            response.bodyAsText().contains("artifact-2") shouldBe false
            response.bodyAsText().contains("artifact-3") shouldBe false
        }
    }

    test("message.note is mirrored through canonical note ingestion") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(envelope(dedupeKey = "note-key", type = "message.note")))
            }

            response.status shouldBe HttpStatusCode.OK
            coVerify(exactly = 1) {
                desktopService.ingestAgentNote(
                    companyId = "company-1",
                    agentName = "CEO",
                    title = "Implement smallest change",
                    content = "Implement smallest change",
                    issueId = null,
                    goalId = null,
                    visibility = "goal"
                )
            }
        }
    }

    test("message.warning is mirrored through canonical warning ingestion") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val warningEnvelope = envelope(dedupeKey = "warning-key", type = "message.warning").copy(
                correlation = A2aCorrelation(issueId = "issue-1", goalId = "goal-1"),
                body = buildJsonObject {
                    put("title", "Budget risk")
                    put("message", "Execution is approaching the daily spend cap.")
                }
            )

            val response = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(warningEnvelope))
            }

            response.status shouldBe HttpStatusCode.OK
            coVerify(exactly = 1) {
                desktopService.ingestAgentWarning(
                    companyId = "company-1",
                    agentName = "CEO",
                    title = "Budget risk",
                    content = "Execution is approaching the daily spend cap.",
                    issueId = "issue-1",
                    goalId = "goal-1"
                )
            }
        }
    }

    test("message.handoff is mirrored through canonical handoff ingestion") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(envelope(dedupeKey = "handoff-key", type = "message.handoff")))
            }

            response.status shouldBe HttpStatusCode.OK
            coVerify(exactly = 1) {
                desktopService.ingestAgentHandoff(
                    companyId = "company-1",
                    fromAgentName = "CEO",
                    toAgentName = "Builder",
                    title = "Implement smallest change",
                    content = "Implement smallest change",
                    issueId = null,
                    goalId = null
                )
            }
        }
    }

    test("message.escalation is mirrored through canonical escalation ingestion") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val response = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(envelope(dedupeKey = "escalation-key", type = "message.escalation")))
            }

            response.status shouldBe HttpStatusCode.OK
            coVerify(exactly = 1) {
                desktopService.ingestAgentEscalation(
                    companyId = "company-1",
                    fromAgentName = "CEO",
                    toAgentName = "Builder",
                    title = "Implement smallest change",
                    content = "Implement smallest change",
                    issueId = null,
                    goalId = null
                )
            }
        }
    }

    test("message.feedback is mirrored through canonical feedback ingestion") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val feedbackEnvelope = envelope(dedupeKey = "feedback-key", type = "message.feedback").copy(
                to = listOf(A2aParty(agentId = "agent-ceo", roleName = "CEO")),
                correlation = A2aCorrelation(issueId = "issue-1", goalId = "goal-1"),
                body = buildJsonObject {
                    put("title", "QA follow-up")
                    put("message", "Regression passed after the last patch.")
                }
            )

            val response = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(feedbackEnvelope))
            }

            response.status shouldBe HttpStatusCode.OK
            coVerify(exactly = 1) {
                desktopService.ingestAgentFeedback(
                    companyId = "company-1",
                    fromAgentName = "CEO",
                    toAgentName = "CEO",
                    title = "QA follow-up",
                    content = "Regression passed after the last patch.",
                    issueId = "issue-1",
                    goalId = "goal-1"
                )
            }
        }
    }

    test("review.verdict routes QA verdict through canonical ingestion") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val reviewVerdict = envelope(dedupeKey = "qa-verdict-key", type = "review.verdict").copy(
                correlation = A2aCorrelation(reviewQueueItemId = "queue-1"),
                body = buildJsonObject {
                    put("queueItemId", "queue-1")
                    put("stage", "qa")
                    put("verdict", "PASS")
                    put("feedback", "Looks good")
                }
            )

            val response = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(reviewVerdict))
            }

            response.status shouldBe HttpStatusCode.OK
            coVerify(exactly = 1) {
                desktopService.ingestA2aReviewVerdict(
                    companyId = "company-1",
                    queueItemId = "queue-1",
                    stage = "qa",
                    verdict = "PASS",
                    feedback = "Looks good"
                )
            }
        }
    }

    test("review.verdict routes CEO verdict through canonical ingestion") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val reviewVerdict = envelope(dedupeKey = "ceo-verdict-key", type = "review.verdict").copy(
                correlation = A2aCorrelation(reviewQueueItemId = "queue-2"),
                body = buildJsonObject {
                    put("queueItemId", "queue-2")
                    put("stage", "ceo")
                    put("verdict", "APPROVE")
                    put("feedback", "Ship it")
                }
            )

            val response = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(reviewVerdict))
            }

            response.status shouldBe HttpStatusCode.OK
            coVerify(exactly = 1) {
                desktopService.ingestA2aReviewVerdict(
                    companyId = "company-1",
                    queueItemId = "queue-2",
                    stage = "ceo",
                    verdict = "APPROVE",
                    feedback = "Ship it"
                )
            }
        }
    }

    test("review.verdict without queue item id is rejected") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val reviewVerdict = envelope(dedupeKey = "missing-queue-key", type = "review.verdict").copy(
                body = buildJsonObject {
                    put("stage", "qa")
                    put("verdict", "PASS")
                }
            )

            val response = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(reviewVerdict))
            }

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "review.verdict requires correlation.reviewQueueItemId or body.queueItemId"
        }
    }

    test("review.request routes through canonical review request ingestion") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val reviewRequest = envelope(dedupeKey = "review-request-key", type = "review.request").copy(
                correlation = A2aCorrelation(issueId = "issue-1", taskId = "task-1", runId = "run-1"),
                body = buildJsonObject {
                    put("issueId", "issue-1")
                    put("taskId", "task-1")
                    put("runId", "run-1")
                    put("title", "Open review lane")
                }
            )

            val response = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(reviewRequest))
            }

            response.status shouldBe HttpStatusCode.OK
            coVerify(exactly = 1) {
                desktopService.ingestA2aReviewRequest(
                    companyId = "company-1",
                    issueId = "issue-1",
                    taskId = "task-1",
                    runId = "run-1"
                )
            }
        }
    }

    test("review.request without issue id is rejected") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val reviewRequest = envelope(dedupeKey = "missing-review-request-key", type = "review.request").copy(
                body = buildJsonObject {
                    put("title", "Open review lane")
                }
            )

            val response = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(reviewRequest))
            }

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "review.request requires correlation.issueId or body.issueId"
        }
    }

    test("run.update routes through canonical run update ingestion") {
        coEvery {
            desktopService.ingestA2aRunUpdate(
                companyId = "company-1",
                runId = "run-1",
                status = "RUNNING",
                output = "started",
                error = null,
                processId = 42L,
                durationMs = 100L
            )
        } returns AgentRun(
            id = "run-1",
            taskId = "task-1",
            workspaceId = "workspace-1",
            repositoryId = "repo-1",
            agentName = "Builder",
            branchName = "branch",
            worktreePath = "/tmp/worktree",
            status = AgentRunStatus.RUNNING,
            processId = 42L,
            durationMs = 100L,
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

            val runUpdate = envelope(dedupeKey = "run-update-key", type = "run.update").copy(
                correlation = A2aCorrelation(runId = "run-1"),
                body = buildJsonObject {
                    put("runId", "run-1")
                    put("status", "RUNNING")
                    put("output", "started")
                    put("processId", "42")
                    put("durationMs", "100")
                }
            )

            val response = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(runUpdate))
            }

            response.status shouldBe HttpStatusCode.OK
            coVerify(exactly = 1) {
                desktopService.ingestA2aRunUpdate(
                    companyId = "company-1",
                    runId = "run-1",
                    status = "RUNNING",
                    output = "started",
                    error = null,
                    processId = 42L,
                    durationMs = 100L
                )
            }
        }
    }

    test("run.update without run id is rejected") {
        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            val runUpdate = envelope(dedupeKey = "missing-run-key", type = "run.update").copy(
                body = buildJsonObject {
                    put("status", "RUNNING")
                }
            )

            val response = client.post("/api/a2a/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer secret-token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(runUpdate))
            }

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "run.update requires correlation.runId or body.runId"
        }
    }
})
