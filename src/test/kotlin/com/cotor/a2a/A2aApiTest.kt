package com.cotor.a2a

import com.cotor.app.DesktopAppService
import com.cotor.app.DesktopTuiSessionService
import com.cotor.app.cotorAppModule
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
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class A2aApiTest : FunSpec({
    val desktopService = mockk<DesktopAppService>(relaxed = true)
    val tuiSessionService = mockk<DesktopTuiSessionService>(relaxed = true)
    val json = Json { encodeDefaults = true }

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
        coEvery { desktopService.companyDashboard("company-1") } returns com.cotor.app.CompanyDashboardResponse()

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
        }
    }
})
