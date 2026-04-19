package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.domain.executor.AgentExecutor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class DesktopAppServiceAgentModelNormalizationTest : FunSpec({
    test("updating an agent to /bin/echo clears a stale model string") {
        val service = normalizationTestService()
        val company = service.createCompany(
            name = "Echo Model Co",
            rootPath = Files.createTempDirectory("agent-model-echo").toString(),
            defaultBaseBranch = "main"
        )
        val builder = service.listCompanyAgentDefinitions(company.id).first { it.title == "Builder" }

        val updated = service.updateCompanyAgentDefinition(
            companyId = company.id,
            agentId = builder.id,
            agentCli = "/bin/echo"
        )

        updated.agentCli shouldBe "/bin/echo"
        updated.model.shouldBeNull()
    }

    test("updating an agent to qwen clears a stale model string because qwen has no model parameter") {
        val service = normalizationTestService()
        val company = service.createCompany(
            name = "Qwen Model Co",
            rootPath = Files.createTempDirectory("agent-model-qwen").toString(),
            defaultBaseBranch = "main"
        )
        val builder = service.listCompanyAgentDefinitions(company.id).first { it.title == "Builder" }

        val updated = service.updateCompanyAgentDefinition(
            companyId = company.id,
            agentId = builder.id,
            agentCli = "qwen"
        )

        updated.agentCli shouldBe "qwen"
        updated.model.shouldBeNull()
    }

    test("updating an agent to gemini keeps an explicit model string") {
        val service = normalizationTestService()
        val company = service.createCompany(
            name = "Gemini Model Co",
            rootPath = Files.createTempDirectory("agent-model-gemini").toString(),
            defaultBaseBranch = "main"
        )
        val builder = service.listCompanyAgentDefinitions(company.id).first { it.title == "Builder" }

        val updated = service.updateCompanyAgentDefinition(
            companyId = company.id,
            agentId = builder.id,
            agentCli = "gemini",
            model = "gemini-3.0-flash"
        )

        updated.agentCli shouldBe "gemini"
        updated.model shouldBe "gemini-3.0-flash"
    }
})

private fun normalizationTestService(): DesktopAppService {
    val appHome = Files.createTempDirectory("agent-model-normalization-home")
    val stateStore = DesktopStateStore { appHome }
    val repoRoot = Files.createDirectories(Files.createTempDirectory("agent-model-normalization-repo").resolve("repo"))
    seedAgentModelWorkspace(stateStore, repoRoot)
    val gitWorkspaceService = mockk<GitWorkspaceService>()
    coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
    coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
    coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "main"
    coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
    return DesktopAppService(
        stateStore = stateStore,
        gitWorkspaceService = gitWorkspaceService,
        configRepository = mockk<ConfigRepository>(relaxed = true),
        agentExecutor = mockk<AgentExecutor>(relaxed = true)
    )
}

private fun seedAgentModelWorkspace(stateStore: DesktopStateStore, repoRoot: java.nio.file.Path) {
    runBlocking {
        stateStore.save(
            DesktopAppState(
                repositories = listOf(
                    ManagedRepository(
                        id = "repo-1",
                        name = "repo",
                        localPath = repoRoot.toString(),
                        sourceKind = RepositorySourceKind.LOCAL,
                        defaultBranch = "main",
                        createdAt = 1,
                        updatedAt = 1
                    )
                ),
                workspaces = listOf(
                    Workspace(
                        id = "workspace-1",
                        repositoryId = "repo-1",
                        name = "repo · main",
                        baseBranch = "main",
                        createdAt = 1,
                        updatedAt = 1
                    )
                )
            )
        )
    }
}
