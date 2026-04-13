package com.cotor.app

import com.cotor.knowledge.KnowledgeRecord
import com.cotor.policy.PolicyDecision
import com.cotor.policy.PolicyEffect
import com.cotor.policy.PolicyExplanation
import com.cotor.providers.github.PullRequestSnapshot
import com.cotor.provenance.EvidenceBundle
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AppServerPlatformRoutesTest : FunSpec({
    val desktopService = mockk<DesktopAppService>(relaxed = true)
    val tuiSessionService = mockk<DesktopTuiSessionService>(relaxed = true)

    test("platform routes expose policy, evidence, github, knowledge and readonly mcp") {
        coEvery { desktopService.policyDecisions(runId = "run-1", issueId = null) } returns listOf(
            PolicyDecision(
                request = com.cotor.runtime.actions.ActionRequest(
                    kind = com.cotor.runtime.actions.ActionKind.GIT_PUBLISH,
                    label = "git.publish:test"
                ),
                effect = PolicyEffect.DENY,
                explanation = PolicyExplanation(summary = "denied")
            )
        )
        coEvery { desktopService.evidenceForRun("run-1") } returns EvidenceBundle(query = "run:run-1")
        coEvery { desktopService.listGitHubPullRequests("company-1") } returns listOf(
            PullRequestSnapshot(number = 12, state = "OPEN", companyId = "company-1")
        )
        coEvery { desktopService.issueKnowledge("issue-1") } returns listOf(
            KnowledgeRecord(subjectType = "issue", subjectId = "issue-1", kind = "mergeability", title = "test", content = "CLEAN")
        )
        coEvery { desktopService.companyDashboard(any()) } returns CompanyDashboardResponse(
            companies = listOf(Company(id = "company-1", name = "Test", rootPath = ".", repositoryId = "repo", defaultBaseBranch = "main", createdAt = 1L, updatedAt = 1L))
        )

        testApplication {
            application {
                cotorAppModule(
                    token = "secret-token",
                    desktopService = desktopService,
                    tuiSessionService = tuiSessionService
                )
            }

            client.get("/api/app/policy/decisions?runId=run-1") {
                header("Authorization", "Bearer secret-token")
            }.status shouldBe HttpStatusCode.OK

            client.get("/api/app/evidence/runs/run-1") {
                header("Authorization", "Bearer secret-token")
            }.bodyAsText().contains("run:run-1") shouldBe true

            client.get("/api/app/github/pull-requests?companyId=company-1") {
                header("Authorization", "Bearer secret-token")
            }.bodyAsText().contains("\"number\":12") shouldBe true

            client.get("/api/app/knowledge/issues/issue-1") {
                header("Authorization", "Bearer secret-token")
            }.bodyAsText().contains("mergeability") shouldBe true

            val mcpResponse = client.post("/api/app/mcp") {
                header("Authorization", "Bearer secret-token")
                header("Content-Type", ContentType.Application.Json.toString())
                setBody(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 1)
                        put("method", "tools/list")
                    }.toString()
                )
            }
            mcpResponse.status shouldBe HttpStatusCode.OK
            mcpResponse.bodyAsText().contains("company_summary") shouldBe true
        }
    }
})
