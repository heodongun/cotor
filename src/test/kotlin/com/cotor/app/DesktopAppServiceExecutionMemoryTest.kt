package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.domain.executor.AgentExecutor
import com.cotor.knowledge.KnowledgeRecord
import com.cotor.knowledge.KnowledgeService
import com.cotor.knowledge.KnowledgeStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class DesktopAppServiceExecutionMemoryTest : FunSpec({
    test("buildIssueExecutionPrompt includes company agent memoryNotes in agent memory") {
        val appHome = Files.createTempDirectory("desktop-execution-memory-home")
        val stateStore = DesktopStateStore { appHome }
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-execution-memory-repo").resolve("repo"))
        seedExecutionMemoryWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "main"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk<AgentExecutor>(relaxed = true)
        )

        val company = service.createCompany(
            name = "Execution Memory Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "main"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Use memory notes in execution",
            description = "Ensure agent memory includes persisted notes.",
            autonomyEnabled = false
        )
        val ceo = service.listCompanyAgentDefinitions(company.id).first { it.title == "CEO" }
        service.updateCompanyAgentDefinition(
            companyId = company.id,
            agentId = ceo.id,
            memoryNotes = "Always validate only the smallest necessary files before making a change."
        )
        val issue = service.listIssues(goal.id).first { it.kind == "execution" }

        val currentState = stateStore.load()
        val ceoProfile = currentState.orgProfiles.first { it.companyId == company.id && it.roleName == "CEO" }
        val memoryBundleBuilder = DesktopAppService::class.java.getDeclaredMethod(
            "buildExecutionMemoryBundle",
            DesktopAppState::class.java,
            Company::class.java,
            CompanyProjectContext::class.java,
            CompanyGoal::class.java,
            CompanyIssue::class.java,
            OrgAgentProfile::class.java
        )
        memoryBundleBuilder.isAccessible = true
        val memoryBundle = memoryBundleBuilder.invoke(
            service,
            currentState,
            company,
            currentState.projectContexts.first { it.companyId == company.id },
            goal,
            issue,
            ceoProfile
        )
        val agentMemoryField = memoryBundle.javaClass.getDeclaredField("agentMemory")
        agentMemoryField.isAccessible = true
        val agentMemory = agentMemoryField.get(memoryBundle) as String

        agentMemory shouldContain "memoryNotes=Always validate only the smallest necessary files before making a change."
    }

    test("buildExecutionMemoryBundle includes retrieved knowledge in workflow memory") {
        val appHome = Files.createTempDirectory("desktop-execution-knowledge-home")
        val stateStore = DesktopStateStore { appHome }
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-execution-knowledge-repo").resolve("repo"))
        seedExecutionMemoryWorkspace(stateStore, repoRoot)
        val knowledgeService = KnowledgeService(KnowledgeStore { appHome })
        knowledgeService.remember(
            KnowledgeRecord(
                subjectType = "issue",
                subjectId = "issue-1",
                kind = "qa-feedback",
                title = "QA feedback",
                content = "Preserve this execution-time knowledge.",
                createdAt = 1L
            )
        )
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "main"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk<AgentExecutor>(relaxed = true),
            knowledgeService = knowledgeService
        )

        val company = service.createCompany(
            name = "Execution Knowledge Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "main"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Use knowledge in execution",
            description = "Ensure workflow memory includes retrieved knowledge.",
            autonomyEnabled = false
        )
        val ceoProfile = stateStore.load().orgProfiles.first { it.companyId == company.id && it.roleName == "CEO" }
        val issue = service.listIssues(goal.id).first { it.kind == "execution" }
        val currentState = stateStore.load()
        val memoryBundleBuilder = DesktopAppService::class.java.getDeclaredMethod(
            "buildExecutionMemoryBundle",
            DesktopAppState::class.java,
            Company::class.java,
            CompanyProjectContext::class.java,
            CompanyGoal::class.java,
            CompanyIssue::class.java,
            OrgAgentProfile::class.java
        )
        memoryBundleBuilder.isAccessible = true
        val memoryBundle = memoryBundleBuilder.invoke(
            service,
            currentState,
            company,
            currentState.projectContexts.first { it.companyId == company.id },
            goal,
            issue,
            ceoProfile
        )
        val workflowMemoryField = memoryBundle.javaClass.getDeclaredField("workflowMemory")
        workflowMemoryField.isAccessible = true
        val workflowMemory = workflowMemoryField.get(memoryBundle) as String

        workflowMemory shouldContain "knowledge=qa-feedback:Preserve this execution-time knowledge."
    }

    test("review and approval prompts include company memory section") {
        val appHome = Files.createTempDirectory("desktop-execution-review-memory-home")
        val stateStore = DesktopStateStore { appHome }
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-execution-review-memory-repo").resolve("repo"))
        seedExecutionMemoryWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "main"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk<AgentExecutor>(relaxed = true)
        )

        val company = service.createCompany(
            name = "Review Memory Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "main"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Use company memory in review/approval",
            description = "Ensure review-facing prompts include the company memory section.",
            autonomyEnabled = false
        )
        val state = stateStore.load()
        val workspace = state.workspaces.first { it.repositoryId == company.repositoryId }
        val projectContext = state.projectContexts.first { it.companyId == company.id }
        val now = System.currentTimeMillis()
        val reviewIssue = CompanyIssue(
            id = "review-issue-memory",
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = "QA review Something",
            description = "Review it",
            status = IssueStatus.PLANNED,
            kind = "review",
            createdAt = now,
            updatedAt = now
        )
        val approvalIssue = reviewIssue.copy(
            id = "approval-issue-memory",
            title = "CEO approve Something",
            kind = "approval"
        )
        stateStore.save(state.copy(issues = state.issues + listOf(reviewIssue, approvalIssue)))

        val currentState = stateStore.load()
        val ceoProfile = currentState.orgProfiles.first { it.companyId == company.id && it.roleName == "CEO" }
        val promptBuilder = DesktopAppService::class.java.getDeclaredMethod(
            "buildIssueExecutionPrompt",
            DesktopAppState::class.java,
            CompanyIssue::class.java,
            OrgAgentProfile::class.java
        )
        promptBuilder.isAccessible = true
        val reviewPrompt = promptBuilder.invoke(service, currentState, reviewIssue, ceoProfile) as String
        val approvalPrompt = promptBuilder.invoke(service, currentState, approvalIssue, ceoProfile) as String

        reviewPrompt shouldContain "Company memory:"
        approvalPrompt shouldContain "Company memory:"
    }

    test("CEO planning prompt includes company memory section") {
        val appHome = Files.createTempDirectory("desktop-execution-planning-memory-home")
        val stateStore = DesktopStateStore { appHome }
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-execution-planning-memory-repo").resolve("repo"))
        seedExecutionMemoryWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "main"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk<AgentExecutor>(relaxed = true)
        )

        val company = service.createCompany(
            name = "Planning Memory Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "main"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Use company memory in planning",
            description = "Ensure planning prompts include the company memory section.",
            autonomyEnabled = false
        )
        val planningIssue = service.listIssues(goal.id).first { it.kind == "planning" }
        val state = stateStore.load()
        val ceoProfile = state.orgProfiles.first { it.companyId == company.id && it.roleName == "CEO" }
        val planningBuilder = DesktopAppService::class.java.getDeclaredMethod(
            "buildCeoPlanningPrompt",
            DesktopAppState::class.java,
            CompanyIssue::class.java,
            OrgAgentProfile::class.java
        )
        planningBuilder.isAccessible = true
        val planningPrompt = planningBuilder.invoke(service, state, planningIssue, ceoProfile) as String

        planningPrompt shouldContain "Company memory:"
        planningPrompt shouldContain "Workflow memory:"
        planningPrompt shouldContain "Agent memory:"
    }

    test("buildCompanyMemorySnapshot exposes company, workflow, and agent memory consistently") {
        val appHome = Files.createTempDirectory("desktop-company-memory-snapshot-home")
        val stateStore = DesktopStateStore { appHome }
        val repoRoot = Files.createDirectories(Files.createTempDirectory("desktop-company-memory-snapshot-repo").resolve("repo"))
        seedExecutionMemoryWorkspace(stateStore, repoRoot)
        val gitWorkspaceService = mockk<GitWorkspaceService>()
        coEvery { gitWorkspaceService.ensureInitializedRepositoryRoot(any(), any()) } returns repoRoot
        coEvery { gitWorkspaceService.resolveRepositoryRoot(any()) } returns repoRoot
        coEvery { gitWorkspaceService.detectDefaultBranch(any()) } returns "main"
        coEvery { gitWorkspaceService.detectRemoteUrl(any()) } returns null
        val service = DesktopAppService(
            stateStore = stateStore,
            gitWorkspaceService = gitWorkspaceService,
            configRepository = mockk<ConfigRepository>(relaxed = true),
            agentExecutor = mockk<AgentExecutor>(relaxed = true)
        )

        val company = service.createCompany(
            name = "Snapshot Co",
            rootPath = repoRoot.toString(),
            defaultBaseBranch = "main"
        )
        val goal = service.createGoal(
            companyId = company.id,
            title = "Snapshot Goal",
            description = "Ensure canonical memory snapshot is exposed.",
            autonomyEnabled = false
        )
        val issue = service.listIssues(goal.id).first { it.kind == "execution" }
        val state = stateStore.load()
        val ceoProfile = state.orgProfiles.first { it.companyId == company.id && it.roleName == "CEO" }
        val snapshotBuilder = DesktopAppService::class.java.getDeclaredMethod(
            "buildCompanyMemorySnapshot",
            DesktopAppState::class.java,
            Company::class.java,
            CompanyProjectContext::class.java,
            CompanyGoal::class.java,
            CompanyIssue::class.java,
            OrgAgentProfile::class.java
        )
        snapshotBuilder.isAccessible = true
        val snapshot = snapshotBuilder.invoke(
            service,
            state,
            company,
            state.projectContexts.first { it.companyId == company.id },
            goal,
            issue,
            ceoProfile
        )
        val companyMemoryField = snapshot.javaClass.getDeclaredField("companyMemory")
        val workflowMemoryField = snapshot.javaClass.getDeclaredField("workflowMemory")
        val agentMemoryField = snapshot.javaClass.getDeclaredField("agentMemory")
        companyMemoryField.isAccessible = true
        workflowMemoryField.isAccessible = true
        agentMemoryField.isAccessible = true

        (companyMemoryField.get(snapshot) as String) shouldContain "company=Snapshot Co"
        (workflowMemoryField.get(snapshot) as String) shouldContain "goal=Snapshot Goal"
        (agentMemoryField.get(snapshot) as String) shouldContain "role=CEO"
    }
})

private fun seedExecutionMemoryWorkspace(stateStore: DesktopStateStore, repoRoot: java.nio.file.Path) {
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
