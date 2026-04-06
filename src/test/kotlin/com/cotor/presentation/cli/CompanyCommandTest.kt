package com.cotor.presentation.cli

import com.cotor.app.Company
import com.cotor.app.CompanyAgentDefinition
import com.cotor.app.DesktopAppService
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

    test("completion output includes company and auth nested commands") {
        val result = CompletionCommand().test("bash")

        result.statusCode shouldBe 0
        result.output shouldContain "company"
        result.output shouldContain "batch-update"
        result.output shouldContain "codex-oauth"
    }
})
