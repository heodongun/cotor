package com.cotor.presentation.cli

import com.cotor.app.Company
import com.cotor.app.CompanyAgentDefinition
import com.cotor.app.CompanyIssue
import com.cotor.app.DesktopAppService
import com.cotor.app.ReviewQueueItem
import com.cotor.app.ReviewQueueStatus
import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class CompanyCommandTest : FunSpec({
    val service = mockk<DesktopAppService>(relaxed = true)

    beforeSpec {
        startKoin {
            modules(
                module {
                    single { service }
                }
            )
        }
    }

    afterSpec {
        stopKoin()
    }

    test("company list prints json companies") {
        coEvery { service.listCompanies() } returns listOf(
            Company(
                id = "company-1",
                name = "Test Company",
                rootPath = "/tmp/company",
                repositoryId = "repo-1",
                defaultBaseBranch = "master",
                createdAt = 1L,
                updatedAt = 2L
            )
        )

        val result = CompanyCommand().test("list")

        result.statusCode shouldBe 0
        result.output shouldContain "\"id\": \"company-1\""
        result.output shouldContain "\"name\": \"Test Company\""
    }

    test("company agent batch-update forwards selected patch fields") {
        coEvery {
            service.batchUpdateCompanyAgentDefinitions(
                companyId = "company-1",
                agentIds = listOf("agent-1", "agent-2"),
                agentCli = "opencode",
                specialties = listOf("qa", "review"),
                enabled = false
            )
        } returns listOf(
            CompanyAgentDefinition(
                id = "agent-1",
                companyId = "company-1",
                title = "QA",
                agentCli = "opencode",
                roleSummary = "review",
                specialties = listOf("qa", "review"),
                enabled = false,
                displayOrder = 0,
                createdAt = 1L,
                updatedAt = 2L
            )
        )

        val result = CompanyCommand().test(
            "agent batch-update --company-id company-1 --agent-id agent-1 --agent-id agent-2 --agent-cli opencode --specialty qa --specialty review --enabled false"
        )

        result.statusCode shouldBe 0
        result.output shouldContain "\"agentCli\": \"opencode\""
        result.output shouldContain "\"enabled\": false"
    }

    test("company issue run waits for settled issue output") {
        coEvery { service.runIssueAndAwaitSettlement("issue-1") } returns CompanyIssue(
            id = "issue-1",
            companyId = "company-1",
            projectContextId = "project-1",
            goalId = "goal-1",
            workspaceId = "workspace-1",
            title = "Issue",
            description = "Do work",
            status = com.cotor.app.IssueStatus.DONE,
            createdAt = 1L,
            updatedAt = 2L
        )

        val result = CompanyCommand().test("issue run issue-1")

        result.statusCode shouldBe 0
        result.output shouldContain "\"id\": \"issue-1\""
        result.output shouldContain "\"status\": \"DONE\""
    }

    test("company issue run can use an explicit wait timeout") {
        coEvery { service.runIssueAndAwaitSettlement("issue-timeout", 5_000L) } returns CompanyIssue(
            id = "issue-timeout",
            companyId = "company-1",
            projectContextId = "project-1",
            goalId = "goal-1",
            workspaceId = "workspace-1",
            title = "Issue",
            description = "Do work",
            status = com.cotor.app.IssueStatus.DONE,
            createdAt = 1L,
            updatedAt = 2L
        )

        val result = CompanyCommand().test("issue run issue-timeout --wait-timeout-seconds 5")

        result.statusCode shouldBe 0
        result.output shouldContain "\"id\": \"issue-timeout\""
        result.output shouldContain "\"status\": \"DONE\""
    }

    test("company issue run supports explicit async start") {
        coEvery { service.runIssue("issue-async") } returns CompanyIssue(
            id = "issue-async",
            companyId = "company-1",
            projectContextId = "project-1",
            goalId = "goal-1",
            workspaceId = "workspace-1",
            title = "Issue",
            description = "Do work",
            status = com.cotor.app.IssueStatus.IN_PROGRESS,
            createdAt = 1L,
            updatedAt = 2L
        )

        val result = CompanyCommand().test("issue run issue-async --async")

        result.statusCode shouldBe 0
        result.output shouldContain "\"id\": \"issue-async\""
        result.output shouldContain "\"status\": \"IN_PROGRESS\""
        io.mockk.coVerify(exactly = 1) { service.runIssue("issue-async") }
    }

    test("company issue show uses the projected issue path") {
        coEvery { service.getIssueProjected("issue-2") } returns CompanyIssue(
            id = "issue-2",
            companyId = "company-1",
            projectContextId = "project-1",
            goalId = "goal-1",
            workspaceId = "workspace-1",
            title = "Projected Issue",
            description = "desc",
            status = com.cotor.app.IssueStatus.IN_PROGRESS,
            runtimeDisposition = "RUNNABLE",
            createdAt = 1L,
            updatedAt = 2L
        )

        val result = CompanyCommand().test("issue show issue-2")

        result.statusCode shouldBe 0
        result.output shouldContain "\"id\": \"issue-2\""
        result.output shouldContain "\"runtimeDisposition\": \"RUNNABLE\""
    }

    test("company dashboard uses the read-only dashboard path") {
        coEvery { service.companyDashboardReadOnly("company-1") } returns com.cotor.app.CompanyDashboardResponse()

        val result = CompanyCommand().test("dashboard --company-id company-1")

        result.statusCode shouldBe 0
        result.output shouldContain "\"companies\""
        io.mockk.coVerify(exactly = 1) { service.companyDashboardReadOnly("company-1") }
        io.mockk.coVerify(exactly = 0) { service.companyDashboard("company-1") }
    }

    test("company review qa forwards verdict and feedback") {
        coEvery { service.submitQaReviewVerdict("item-1", "PASS", "looks good") } returns ReviewQueueItem(
            id = "item-1",
            issueId = "issue-1",
            runId = "run-1",
            status = ReviewQueueStatus.READY_FOR_CEO,
            qaVerdict = "PASS",
            qaFeedback = "looks good",
            createdAt = 1L,
            updatedAt = 2L
        )

        val result = CompanyCommand().test("review qa item-1 --verdict PASS --feedback 'looks good'")

        result.statusCode shouldBe 0
        result.output shouldContain "\"id\": \"item-1\""
        result.output shouldContain "\"qaVerdict\": \"PASS\""
    }

    test("company review ceo forwards verdict and feedback") {
        coEvery { service.submitCeoReviewVerdict("item-2", "APPROVE", "ship it") } returns ReviewQueueItem(
            id = "item-2",
            issueId = "issue-2",
            runId = "run-2",
            status = ReviewQueueStatus.READY_FOR_CEO,
            ceoVerdict = "APPROVE",
            ceoFeedback = "ship it",
            createdAt = 1L,
            updatedAt = 2L
        )

        val result = CompanyCommand().test("review ceo item-2 --verdict APPROVE --feedback 'ship it'")

        result.statusCode shouldBe 0
        result.output shouldContain "\"id\": \"item-2\""
        result.output shouldContain "\"ceoVerdict\": \"APPROVE\""
    }

    test("completion output includes company and auth nested commands") {
        val result = CompletionCommand().test("bash")

        result.statusCode shouldBe 0
        result.output shouldContain "company"
        result.output shouldContain "batch-update"
        result.output shouldContain "codex-oauth"
    }
})
