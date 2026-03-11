package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.domain.executor.AgentExecutor
import com.cotor.domain.planning.GoalDrivenTaskPlanner
import com.cotor.model.AgentConfig
import com.cotor.model.AgentExecutionMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.exists

/**
 * High-level application service that owns the desktop-facing workflow.
 *
 * This layer deliberately hides git/worktree details from the HTTP handlers so the
 * desktop contract stays centered on repositories, workspaces, tasks, and runs.
 */
class DesktopAppService(
    private val stateStore: DesktopStateStore,
    private val gitWorkspaceService: GitWorkspaceService,
    private val configRepository: ConfigRepository,
    private val agentExecutor: AgentExecutor,
    private val taskPlanner: GoalDrivenTaskPlanner = GoalDrivenTaskPlanner(),
    private val companyRuntimeTickIntervalMs: Long = 60_000L,
    private val commandAvailability: (String) -> Boolean = ::defaultCommandAvailability
) {
    // Reads are cheap and frequent, but writes must be serialized so the state file
    // cannot be partially overwritten when multiple agent runs finish at once.
    private val stateMutex = Mutex()

    // Runs execute in background coroutines so the API can return immediately after
    // the user presses "Run Task" in the desktop client.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val companyRuntimeJobs: MutableMap<String, Job> = linkedMapOf()

    suspend fun dashboard(): DashboardResponse = DashboardResponse(
        repositories = listRepositories(),
        workspaces = listWorkspaces(),
        tasks = listTasks(),
        settings = settings(),
        companies = listCompanies(),
        companyAgentDefinitions = listCompanyAgentDefinitions(),
        projectContexts = listProjectContexts(),
        goals = listGoals(),
        issues = listIssues(),
        reviewQueue = listReviewQueue(),
        orgProfiles = listOrgProfiles(),
        opsMetrics = stateStore.load().opsMetrics,
        activity = listCompanyActivity(),
        companyRuntimes = stateStore.load().withDerivedMetrics().companyRuntimes.sortedByDescending { it.lastTickAt ?: 0L }
    )

    suspend fun companyDashboard(companyId: String? = null): CompanyDashboardResponse {
        val state = stateStore.load().withDerivedMetrics()
        val filteredGoals = state.goals
            .filter { companyId == null || it.companyId == companyId }
            .sortedByDescending { it.updatedAt }
        val filteredIssues = state.issues
            .filter { companyId == null || it.companyId == companyId }
            .sortedByDescending { it.updatedAt }
        val filteredIssueIds = filteredIssues.map { it.id }.toSet()
        return CompanyDashboardResponse(
            companies = state.companies.sortedByDescending { it.updatedAt },
            companyAgentDefinitions = state.companyAgentDefinitions
                .filter { companyId == null || it.companyId == companyId }
                .sortedWith(compareBy<CompanyAgentDefinition> { it.displayOrder }.thenBy { it.title.lowercase() }),
            projectContexts = state.projectContexts
                .filter { companyId == null || it.companyId == companyId }
                .sortedByDescending { it.lastUpdatedAt },
            goals = filteredGoals,
            issues = filteredIssues,
            issueDependencies = state.issueDependencies.filter { it.issueId in filteredIssueIds || it.dependsOnIssueId in filteredIssueIds },
            reviewQueue = state.reviewQueue
                .filter { companyId == null || it.companyId == companyId }
                .sortedByDescending { it.updatedAt },
            orgProfiles = ensureOrgProfiles(state.orgProfiles, state.companyAgentDefinitions, state.companies)
                .filter { companyId == null || it.companyId == companyId },
            opsMetrics = state.opsMetrics,
            runtime = if (companyId == null) {
                state.runtime
            } else {
                state.companyRuntimes.firstOrNull { it.companyId == companyId } ?: CompanyRuntimeSnapshot(companyId = companyId)
            },
            signals = state.signals
                .filter { companyId == null || it.companyId == companyId }
                .sortedByDescending { it.createdAt },
            activity = state.companyActivity
                .filter { companyId == null || it.companyId == companyId }
                .sortedByDescending { it.createdAt }
        )
    }

    suspend fun listRepositories(): List<ManagedRepository> =
        stateStore.load().repositories.sortedByDescending { it.updatedAt }

    suspend fun listWorkspaces(repositoryId: String? = null): List<Workspace> {
        val workspaces = stateStore.load().workspaces.sortedByDescending { it.updatedAt }
        return repositoryId?.let { id -> workspaces.filter { it.repositoryId == id } } ?: workspaces
    }

    suspend fun listBranches(repositoryId: String): List<String> {
        val repository = stateStore.load().repositories.firstOrNull { it.id == repositoryId }
            ?: throw IllegalArgumentException("Repository not found: $repositoryId")
        return gitWorkspaceService.listBranches(Path.of(repository.localPath))
    }

    suspend fun listTasks(workspaceId: String? = null): List<AgentTask> {
        val tasks = stateStore.load().tasks.sortedByDescending { it.createdAt }
        return workspaceId?.let { id -> tasks.filter { it.workspaceId == id } } ?: tasks
    }

    suspend fun listRuns(taskId: String? = null): List<AgentRun> {
        val runs = stateStore.load().runs.sortedByDescending { it.createdAt }
        return taskId?.let { id -> runs.filter { it.taskId == id } } ?: runs
    }

    suspend fun listGoals(): List<CompanyGoal> =
        stateStore.load().goals.sortedByDescending { it.updatedAt }

    suspend fun listCompanies(): List<Company> =
        stateStore.load().companies.sortedByDescending { it.updatedAt }

    suspend fun getCompany(companyId: String): Company? =
        stateStore.load().companies.firstOrNull { it.id == companyId }

    suspend fun listCompanyAgentDefinitions(companyId: String? = null): List<CompanyAgentDefinition> {
        val items = stateStore.load().companyAgentDefinitions
            .sortedWith(compareBy<CompanyAgentDefinition> { it.displayOrder }.thenBy { it.title.lowercase() })
        return companyId?.let { id -> items.filter { it.companyId == id } } ?: items
    }

    suspend fun listProjectContexts(companyId: String? = null): List<CompanyProjectContext> {
        val items = stateStore.load().projectContexts.sortedByDescending { it.lastUpdatedAt }
        return companyId?.let { id -> items.filter { it.companyId == id } } ?: items
    }

    suspend fun listCompanyActivity(companyId: String? = null): List<CompanyActivityItem> {
        val items = stateStore.load().companyActivity.sortedByDescending { it.createdAt }
        return companyId?.let { id -> items.filter { it.companyId == id } } ?: items
    }

    suspend fun getGoal(goalId: String): CompanyGoal? =
        stateStore.load().goals.firstOrNull { it.id == goalId }

    suspend fun listIssues(goalId: String? = null, companyId: String? = null): List<CompanyIssue> {
        val issues = stateStore.load().issues.sortedByDescending { it.updatedAt }
        return issues.filter { issue ->
            (goalId == null || issue.goalId == goalId) &&
                (companyId == null || issue.companyId == companyId)
        }
    }

    suspend fun listReviewQueue(companyId: String? = null): List<ReviewQueueItem> =
        stateStore.load().reviewQueue
            .filter { companyId == null || it.companyId == companyId }
            .sortedByDescending { it.updatedAt }

    suspend fun listOrgProfiles(): List<OrgAgentProfile> =
        ensureOrgProfiles(
            stateStore.load().orgProfiles,
            stateStore.load().companyAgentDefinitions,
            stateStore.load().companies
        )

    suspend fun listSignals(companyId: String? = null): List<OpsSignal> =
        stateStore.load().signals
            .filter { companyId == null || it.companyId == companyId }
            .sortedByDescending { it.createdAt }

    suspend fun runtimeStatus(companyId: String? = null): CompanyRuntimeSnapshot {
        val state = stateStore.load().withDerivedMetrics()
        return if (companyId == null) {
            state.runtime
        } else {
            state.companyRuntimes.firstOrNull { it.companyId == companyId } ?: CompanyRuntimeSnapshot(companyId = companyId)
        }
    }

    suspend fun getTask(taskId: String): AgentTask? =
        stateStore.load().tasks.firstOrNull { it.id == taskId }

    suspend fun getIssue(issueId: String): CompanyIssue? =
        stateStore.load().issues.firstOrNull { it.id == issueId }

    suspend fun updateGoalAutonomy(goalId: String, enabled: Boolean): CompanyGoal = stateMutex.withLock {
        val state = stateStore.load()
        val goal = state.goals.firstOrNull { it.id == goalId }
            ?: throw IllegalArgumentException("Goal not found: $goalId")
        val updated = goal.copy(
            autonomyEnabled = enabled,
            status = when {
                enabled && goal.status == GoalStatus.PAUSED -> GoalStatus.ACTIVE
                !enabled && goal.status == GoalStatus.ACTIVE -> GoalStatus.PAUSED
                else -> goal.status
            },
            updatedAt = System.currentTimeMillis()
        )
        val nextState = state.copy(
            goals = state.goals.map { if (it.id == goalId) updated else it }
        ).recordSignal(
            companyId = updated.companyId,
            projectContextId = updated.projectContextId,
            source = "goal-policy",
            message = if (enabled) "Enabled autonomy for ${updated.title}" else "Paused autonomy for ${updated.title}",
            goalId = goalId
        ).withDerivedMetrics()
        stateStore.save(nextState)
        updated
    }

    suspend fun createCompany(
        name: String,
        rootPath: String,
        defaultBaseBranch: String? = null,
        autonomyEnabled: Boolean = true
    ): Company = stateMutex.withLock {
        val now = System.currentTimeMillis()
        val repositoryRoot = gitWorkspaceService.resolveRepositoryRoot(Path.of(rootPath))
        val repository = upsertRepositoryForPath(stateStore.load().repositories, repositoryRoot, now)
        val resolvedBranch = defaultBaseBranch?.takeIf { it.isNotBlank() } ?: repository.defaultBranch
        val existing = stateStore.load().companies.firstOrNull {
            Path.of(it.rootPath).toAbsolutePath().normalize() == repositoryRoot.toAbsolutePath().normalize()
        }
        if (existing != null) {
            val refreshed = existing.copy(
                name = name.trim().ifEmpty { existing.name },
                repositoryId = repository.id,
                defaultBaseBranch = resolvedBranch,
                autonomyEnabled = autonomyEnabled,
                updatedAt = now
            )
            val nextState = stateStore.load().copy(
                repositories = mergeRepository(stateStore.load().repositories, repository),
                companies = stateStore.load().companies.map { if (it.id == existing.id) refreshed else it }
            ).recordCompanyActivity(
                companyId = refreshed.id,
                source = "company",
                title = "Updated company",
                detail = refreshed.name
            ).withDerivedMetrics()
            stateStore.save(nextState)
            return@withLock refreshed
        }

        val companyId = UUID.randomUUID().toString()
        val company = Company(
            id = companyId,
            name = name.trim().ifEmpty { repositoryRoot.fileName.toString() },
            rootPath = repositoryRoot.toString(),
            repositoryId = repository.id,
            defaultBaseBranch = resolvedBranch,
            autonomyEnabled = autonomyEnabled,
            createdAt = now,
            updatedAt = now
        )
        val context = CompanyProjectContext(
            id = UUID.randomUUID().toString(),
            companyId = companyId,
            name = company.name,
            slug = slugify(company.name),
            contextDocPath = companyContextRoot(company).resolve("projects/default.md").toString(),
            lastUpdatedAt = now
        )
        val seededDefinitions = seedCompanyAgentDefinitions(companyId, now)
        val nextState = stateStore.load().copy(
            repositories = mergeRepository(stateStore.load().repositories, repository),
            companies = stateStore.load().companies + company,
            companyAgentDefinitions = stateStore.load().companyAgentDefinitions + seededDefinitions,
            projectContexts = stateStore.load().projectContexts + context,
            companyRuntimes = stateStore.load().companyRuntimes + CompanyRuntimeSnapshot(companyId = companyId)
        ).recordCompanyActivity(
            companyId = companyId,
            projectContextId = context.id,
            source = "company",
            title = "Created company",
            detail = company.name
        ).withDerivedMetrics()
        stateStore.save(nextState)
        writeCompanyContextSnapshot(nextState, company, context)
        company
    }

    suspend fun updateCompany(
        companyId: String,
        name: String? = null,
        defaultBaseBranch: String? = null,
        autonomyEnabled: Boolean? = null
    ): Company = stateMutex.withLock {
        val state = stateStore.load()
        val company = state.companies.firstOrNull { it.id == companyId }
            ?: throw IllegalArgumentException("Company not found: $companyId")
        val updated = company.copy(
            name = name?.trim()?.takeIf { it.isNotBlank() } ?: company.name,
            defaultBaseBranch = defaultBaseBranch?.trim()?.takeIf { it.isNotBlank() } ?: company.defaultBaseBranch,
            autonomyEnabled = autonomyEnabled ?: company.autonomyEnabled,
            updatedAt = System.currentTimeMillis()
        )
        val nextState = state.copy(
            companies = state.companies.map { if (it.id == companyId) updated else it }
        ).recordCompanyActivity(
            companyId = updated.id,
            source = "company",
            title = "Updated company",
            detail = updated.name
        ).withDerivedMetrics()
        stateStore.save(nextState)
        updated
    }

    suspend fun createCompanyAgentDefinition(
        companyId: String,
        title: String,
        agentCli: String,
        roleSummary: String,
        enabled: Boolean = true
    ): CompanyAgentDefinition = stateMutex.withLock {
        val state = stateStore.load()
        require(state.companies.any { it.id == companyId }) { "Company not found: $companyId" }
        val now = System.currentTimeMillis()
        val definition = CompanyAgentDefinition(
            id = UUID.randomUUID().toString(),
            companyId = companyId,
            title = title.trim().ifEmpty { "Agent" },
            agentCli = agentCli.trim().ifEmpty { "echo" },
            roleSummary = roleSummary.trim().ifEmpty { "general execution" },
            enabled = enabled,
            displayOrder = state.companyAgentDefinitions.count { it.companyId == companyId },
            createdAt = now,
            updatedAt = now
        )
        val nextProfiles = deriveProfiles(state.companyAgentDefinitions + definition, state.companies)
        val nextState = state.copy(
            companyAgentDefinitions = state.companyAgentDefinitions + definition,
            orgProfiles = nextProfiles
        ).recordCompanyActivity(
            companyId = companyId,
            source = "agent-roster",
            title = "Added agent",
            detail = "${definition.title} · ${definition.agentCli}"
        ).withDerivedMetrics()
        stateStore.save(nextState)
        definition
    }

    suspend fun updateCompanyAgentDefinition(
        companyId: String,
        agentId: String,
        title: String? = null,
        agentCli: String? = null,
        roleSummary: String? = null,
        enabled: Boolean? = null,
        displayOrder: Int? = null
    ): CompanyAgentDefinition = stateMutex.withLock {
        val state = stateStore.load()
        val current = state.companyAgentDefinitions.firstOrNull { it.companyId == companyId && it.id == agentId }
            ?: throw IllegalArgumentException("Company agent not found: $agentId")
        val updated = current.copy(
            title = title?.trim()?.takeIf { it.isNotBlank() } ?: current.title,
            agentCli = agentCli?.trim()?.takeIf { it.isNotBlank() } ?: current.agentCli,
            roleSummary = roleSummary?.trim()?.takeIf { it.isNotBlank() } ?: current.roleSummary,
            enabled = enabled ?: current.enabled,
            displayOrder = displayOrder ?: current.displayOrder,
            updatedAt = System.currentTimeMillis()
        )
        val nextDefinitions = state.companyAgentDefinitions.map { if (it.id == agentId) updated else it }
        val nextState = state.copy(
            companyAgentDefinitions = nextDefinitions,
            orgProfiles = deriveProfiles(nextDefinitions, state.companies)
        ).recordCompanyActivity(
            companyId = companyId,
            source = "agent-roster",
            title = "Updated agent",
            detail = "${updated.title} · ${updated.agentCli}"
        ).withDerivedMetrics()
        stateStore.save(nextState)
        updated
    }

    suspend fun updateWorkspaceBaseBranch(workspaceId: String, baseBranch: String): Workspace = stateMutex.withLock {
        val state = stateStore.load()
        val workspace = state.workspaces.firstOrNull { it.id == workspaceId }
            ?: throw IllegalArgumentException("Workspace not found: $workspaceId")
        val updated = workspace.copy(
            baseBranch = baseBranch.trim().ifEmpty { workspace.baseBranch },
            updatedAt = System.currentTimeMillis()
        )
        val nextState = state.copy(
            workspaces = state.workspaces.map { if (it.id == workspaceId) updated else it },
            companies = state.companies.map { company ->
                if (company.repositoryId == updated.repositoryId) {
                    company.copy(defaultBaseBranch = updated.baseBranch, updatedAt = updated.updatedAt)
                } else {
                    company
                }
            }
        ).withDerivedMetrics()
        stateStore.save(nextState)
        updated
    }

    suspend fun createGoal(
        companyId: String?,
        title: String,
        description: String,
        successMetrics: List<String> = emptyList(),
        autonomyEnabled: Boolean = true
    ): CompanyGoal = stateMutex.withLock {
        val state = stateStore.load()
        val now = System.currentTimeMillis()
        val company = resolveCompanyForGoal(state, companyId, now)
        val workspaceResolution = ensureCompanyWorkspace(state, company, now)
        val workspaceState = workspaceResolution.first
        val workspace = workspaceResolution.second
        val projectContext = ensureProjectContext(workspaceState, company, now)
        val companyDefinitions = if (workspaceState.companyAgentDefinitions.any { it.companyId == company.id }) {
            workspaceState.companyAgentDefinitions
        } else {
            workspaceState.companyAgentDefinitions + seedCompanyAgentDefinitions(company.id, now)
        }
        val nextCompanies = if (workspaceState.companies.any { it.id == company.id }) {
            workspaceState.companies
        } else {
            workspaceState.companies + company
        }
        val nextProjectContexts = if (workspaceState.projectContexts.any { it.id == projectContext.id }) {
            workspaceState.projectContexts
        } else {
            workspaceState.projectContexts + projectContext
        }
        val profiles = ensureOrgProfiles(workspaceState.orgProfiles, companyDefinitions, nextCompanies)
        val goal = CompanyGoal(
            id = UUID.randomUUID().toString(),
            companyId = company.id,
            projectContextId = projectContext.id,
            title = title.trim().ifEmpty { "New Goal" },
            description = description.trim(),
            status = GoalStatus.ACTIVE,
            successMetrics = successMetrics.filter { it.isNotBlank() },
            autonomyEnabled = autonomyEnabled,
            createdAt = now,
            updatedAt = now
        )
        val decomposition = decomposeGoal(goal, workspace, profiles, now)
        val nextState = workspaceState.copy(
            companies = nextCompanies,
            companyAgentDefinitions = companyDefinitions,
            projectContexts = nextProjectContexts,
            goals = workspaceState.goals + goal,
            issues = workspaceState.issues + decomposition.first,
            issueDependencies = workspaceState.issueDependencies + decomposition.second,
            orgProfiles = profiles,
            companyRuntimes = if (workspaceState.companyRuntimes.any { it.companyId == company.id }) {
                workspaceState.companyRuntimes
            } else {
                workspaceState.companyRuntimes + CompanyRuntimeSnapshot(companyId = company.id)
            }
        ).recordCompanyActivity(
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            source = "goal",
            title = "Created goal",
            detail = goal.title
        ).withDerivedMetrics()
        stateStore.save(nextState)
        writeCompanyContextSnapshot(nextState, company, projectContext)
        goal
    }

    suspend fun createGoal(
        title: String,
        description: String,
        successMetrics: List<String> = emptyList(),
        autonomyEnabled: Boolean = true
    ): CompanyGoal =
        createGoal(null, title, description, successMetrics, autonomyEnabled)

    suspend fun decomposeGoal(goalId: String): List<CompanyIssue> = stateMutex.withLock {
        val state = stateStore.load()
        val existing = state.issues.filter { it.goalId == goalId }
        if (existing.isNotEmpty()) {
            return@withLock existing.sortedByDescending { it.updatedAt }
        }
        val goal = state.goals.firstOrNull { it.id == goalId }
            ?: throw IllegalArgumentException("Goal not found: $goalId")
        val now = System.currentTimeMillis()
        val company = state.companies.firstOrNull { it.id == goal.companyId }
            ?: throw IllegalArgumentException("Company not found for goal: ${goal.companyId}")
        val workspaceResolution = ensureCompanyWorkspace(state, company, now)
        val workspaceState = workspaceResolution.first
        val workspace = workspaceResolution.second
        val projectContext = ensureProjectContext(workspaceState, company, now)
        val profiles = ensureOrgProfiles(workspaceState.orgProfiles, workspaceState.companyAgentDefinitions, workspaceState.companies)
        val decomposition = decomposeGoal(goal, workspace, profiles, now)
        val nextState = workspaceState.copy(
            issues = workspaceState.issues + decomposition.first,
            issueDependencies = workspaceState.issueDependencies + decomposition.second,
            orgProfiles = profiles
        ).recordCompanyActivity(
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            source = "goal-decomposition",
            title = "Decomposed goal",
            detail = goal.title
        ).withDerivedMetrics()
        stateStore.save(nextState)
        writeCompanyContextSnapshot(nextState, company, projectContext)
        decomposition.first
    }

    suspend fun delegateIssue(issueId: String): CompanyIssue = stateMutex.withLock {
        val state = stateStore.load()
        val profiles = ensureOrgProfiles(state.orgProfiles, state.companyAgentDefinitions, state.companies)
        val issue = state.issues.firstOrNull { it.id == issueId }
            ?: throw IllegalArgumentException("Issue not found: $issueId")
        val assignee = suggestProfileForIssue(issue, profiles)
        val delegated = issue.copy(
            assigneeProfileId = assignee?.id ?: issue.assigneeProfileId,
            status = IssueStatus.DELEGATED,
            updatedAt = System.currentTimeMillis()
        )
        val nextState = state.copy(
            issues = state.issues.map { if (it.id == issueId) delegated else it },
            orgProfiles = profiles
        ).recordCompanyActivity(
            companyId = issue.companyId,
            projectContextId = issue.projectContextId,
            goalId = issue.goalId,
            issueId = issue.id,
            source = "issue-routing",
            title = "Delegated issue",
            detail = "${issue.title} -> ${assignee?.roleName ?: "unassigned"}"
        ).withDerivedMetrics()
        stateStore.save(nextState)
        delegated
    }

    suspend fun runIssue(issueId: String): CompanyIssue {
        val delegated = delegateIssue(issueId)
        val state = stateStore.load()
        val profile = state.orgProfiles.firstOrNull { it.id == delegated.assigneeProfileId }
            ?: ensureOrgProfiles(state.orgProfiles, state.companyAgentDefinitions, state.companies)
                .firstOrNull { it.companyId == delegated.companyId }
            ?: ensureOrgProfiles(state.orgProfiles, state.companyAgentDefinitions, state.companies).firstOrNull()
            ?: throw IllegalStateException("No execution profiles are configured")
        val task = createTask(
            workspaceId = delegated.workspaceId,
            title = delegated.title,
            prompt = delegated.description,
            agents = listOf(profile.executionAgentName),
            issueId = delegated.id
        )
        runTask(task.id)
        return stateMutex.withLock {
            val latest = stateStore.load()
            val runningIssue = latest.issues.firstOrNull { it.id == issueId }
                ?.copy(status = IssueStatus.IN_PROGRESS, updatedAt = System.currentTimeMillis())
                ?: throw IllegalArgumentException("Issue not found: $issueId")
            val nextState = latest.copy(
                issues = latest.issues.map { if (it.id == issueId) runningIssue else it }
            ).recordCompanyActivity(
                companyId = runningIssue.companyId,
                projectContextId = runningIssue.projectContextId,
                goalId = runningIssue.goalId,
                issueId = runningIssue.id,
                source = "issue-run",
                title = "Started issue run",
                detail = runningIssue.title
            ).withDerivedMetrics()
            stateStore.save(nextState)
            runningIssue
        }
    }

    suspend fun mergeReviewQueueItem(itemId: String): ReviewQueueItem = stateMutex.withLock {
        val state = stateStore.load()
        val item = state.reviewQueue.firstOrNull { it.id == itemId }
            ?: throw IllegalArgumentException("Review queue item not found: $itemId")
        val mergedAt = System.currentTimeMillis()
        val mergedItem = item.copy(status = ReviewQueueStatus.MERGED, updatedAt = mergedAt)
        val nextIssues = state.issues.map { issue ->
            if (issue.id == item.issueId) {
                issue.copy(status = IssueStatus.DONE, updatedAt = mergedAt)
            } else {
                issue
            }
        }
        val nextGoals = state.goals.map { goal ->
            val unresolved = nextIssues.any { it.goalId == goal.id && it.status != IssueStatus.DONE && it.status != IssueStatus.CANCELED }
            if (goal.id == nextIssues.firstOrNull { it.id == item.issueId }?.goalId && !unresolved) {
                goal.copy(status = GoalStatus.COMPLETED, updatedAt = mergedAt)
            } else {
                goal
            }
        }
        val mergedIssue = nextIssues.firstOrNull { it.id == item.issueId }
        val nextState = state.copy(
            reviewQueue = state.reviewQueue.map { if (it.id == itemId) mergedItem else it },
            issues = nextIssues,
            goals = nextGoals
        ).recordCompanyActivity(
            companyId = mergedIssue?.companyId ?: item.companyId,
            projectContextId = mergedIssue?.projectContextId ?: item.projectContextId,
            goalId = mergedIssue?.goalId,
            issueId = item.issueId,
            source = "review-queue",
            title = "Merged issue",
            detail = mergedIssue?.title ?: item.id
        ).recordSignal(
            source = "review-queue",
            message = "Merged review queue item ${item.id}",
            companyId = mergedIssue?.companyId ?: item.companyId,
            projectContextId = mergedIssue?.projectContextId ?: item.projectContextId,
            goalId = nextIssues.firstOrNull { it.id == item.issueId }?.goalId,
            issueId = item.issueId
        ).withDerivedMetrics()
        stateStore.save(nextState)
        mergedItem
    }

    suspend fun startCompanyRuntime(companyId: String): CompanyRuntimeSnapshot {
        val snapshot = stateMutex.withLock {
            val state = stateStore.load()
            require(hasCompanyScope(state, companyId)) { "Company not found: $companyId" }
            val now = System.currentTimeMillis()
            val nextState = state.copy(
                companyRuntimes = upsertCompanyRuntime(
                    state.companyRuntimes,
                    CompanyRuntimeSnapshot(
                        companyId = companyId,
                        status = CompanyRuntimeStatus.RUNNING,
                        tickIntervalSeconds = companyRuntimeTickIntervalMs / 1000,
                        lastStartedAt = now,
                        lastAction = "runtime-started",
                        lastError = null
                    )
                )
            ).recordSignal(
                source = "runtime",
                message = "Started autonomous company runtime",
                companyId = companyId
            ).recordCompanyActivity(
                companyId = companyId,
                source = "runtime",
                title = "Started runtime"
            ).withDerivedMetrics()
            stateStore.save(nextState)
            nextState.companyRuntimes.firstOrNull { it.companyId == companyId } ?: CompanyRuntimeSnapshot(companyId = companyId)
        }
        ensureCompanyRuntimeLoop(companyId)
        runCompanyRuntimeTick(companyId)
        return runtimeStatus(companyId)
    }

    suspend fun startCompanyRuntime(): CompanyRuntimeSnapshot {
        val companyId = resolveLatestCompanyId(stateStore.load())
            ?: throw IllegalStateException("Create a company before starting runtime")
        return startCompanyRuntime(companyId)
    }

    suspend fun stopCompanyRuntime(companyId: String): CompanyRuntimeSnapshot {
        companyRuntimeJobs.remove(companyId)?.cancel()
        return stateMutex.withLock {
            val state = stateStore.load()
            val now = System.currentTimeMillis()
            val nextState = state.copy(
                companyRuntimes = upsertCompanyRuntime(
                    state.companyRuntimes,
                    (state.companyRuntimes.firstOrNull { it.companyId == companyId } ?: CompanyRuntimeSnapshot(companyId = companyId)).copy(
                        companyId = companyId,
                        status = CompanyRuntimeStatus.STOPPED,
                        lastStoppedAt = now,
                        lastAction = "runtime-stopped"
                    )
                )
            ).recordSignal(
                source = "runtime",
                message = "Stopped autonomous company runtime",
                companyId = companyId
            ).recordCompanyActivity(
                companyId = companyId,
                source = "runtime",
                title = "Stopped runtime"
            ).withDerivedMetrics()
            stateStore.save(nextState)
            nextState.companyRuntimes.firstOrNull { it.companyId == companyId } ?: CompanyRuntimeSnapshot(companyId = companyId)
        }
    }

    suspend fun stopCompanyRuntime(): CompanyRuntimeSnapshot {
        val companyId = resolveLatestCompanyId(stateStore.load()) ?: companyRuntimeJobs.keys.lastOrNull()
            ?: throw IllegalStateException("Create a company before stopping runtime")
        return stopCompanyRuntime(companyId)
    }

    suspend fun runCompanyRuntimeTick(companyId: String): CompanyRuntimeSnapshot {
        val initial = stateStore.load()
        val runtime = initial.companyRuntimes.firstOrNull { it.companyId == companyId } ?: CompanyRuntimeSnapshot(companyId = companyId)
        if (runtime.status != CompanyRuntimeStatus.RUNNING) {
            return runtimeStatus(companyId)
        }

        val autonomousGoals = initial.goals.filter {
            it.companyId == companyId && it.autonomyEnabled && it.status == GoalStatus.ACTIVE
        }
        if (autonomousGoals.isEmpty()) {
            return updateRuntimeAfterTick(companyId, lastAction = "idle-no-autonomous-goals")
        }

        val actions = mutableListOf<String>()
        autonomousGoals.forEach { goal ->
            if (initial.issues.none { it.goalId == goal.id }) {
                decomposeGoal(goal.id)
                actions += "decomposed:${goal.id}"
            }
        }

        val reviewSnapshot = stateStore.load()
        reviewSnapshot.reviewQueue
            .filter { it.status == ReviewQueueStatus.READY_TO_MERGE }
            .filter { item ->
                val issue = reviewSnapshot.issues.firstOrNull { it.id == item.issueId } ?: return@filter false
                reviewSnapshot.goals.firstOrNull { it.id == issue.goalId }?.autonomyEnabled == true &&
                    issue.companyId == companyId
            }
            .forEach { item ->
                mergeReviewQueueItem(item.id)
                actions += "merged:${item.id}"
            }

        val executionSnapshot = stateStore.load()
        val runnableIssue = executionSnapshot.issues
            .filter { issue ->
                executionSnapshot.goals.firstOrNull { it.id == issue.goalId }?.let { goal ->
                    goal.autonomyEnabled && goal.status == GoalStatus.ACTIVE && goal.companyId == companyId
                } == true
            }
            .filter { issue ->
                issue.status == IssueStatus.PLANNED || issue.status == IssueStatus.BACKLOG || issue.status == IssueStatus.DELEGATED
            }
            .sortedWith(compareBy<CompanyIssue> { it.priority }.thenBy { it.createdAt })
            .firstOrNull { issue ->
                val dependenciesSatisfied = issue.dependsOn.all { dependencyId ->
                    executionSnapshot.issues.firstOrNull { it.id == dependencyId }?.status == IssueStatus.DONE
                }
                val alreadyStarted = executionSnapshot.tasks.any { it.issueId == issue.id }
                dependenciesSatisfied && !alreadyStarted
            }

        if (runnableIssue != null) {
            runIssue(runnableIssue.id)
            actions += "started:${runnableIssue.id}"
        }

        return updateRuntimeAfterTick(
            companyId,
            lastAction = actions.joinToString(separator = ", ").ifBlank { "idle-no-work" }
        )
    }

    suspend fun openLocalRepository(path: String): ManagedRepository {
        val repositoryRoot = gitWorkspaceService.resolveRepositoryRoot(Path.of(path))
        val now = System.currentTimeMillis()
        val defaultBranch = gitWorkspaceService.detectDefaultBranch(repositoryRoot)
        val remoteUrl = gitWorkspaceService.detectRemoteUrl(repositoryRoot)

        return stateMutex.withLock {
            val state = stateStore.load()
            val existing = state.repositories.firstOrNull {
                sameRepositoryRoot(it.localPath, repositoryRoot)
            }
            if (existing != null) {
                val refreshed = existing.copy(
                    name = repositoryRoot.fileName.toString(),
                    localPath = repositoryRoot.toString(),
                    remoteUrl = remoteUrl ?: existing.remoteUrl,
                    defaultBranch = defaultBranch,
                    updatedAt = now
                )
                stateStore.save(
                    state.copy(
                        repositories = state.repositories.map {
                            if (it.id == existing.id) refreshed else it
                        }
                    )
                )
                return@withLock refreshed
            }

            val repository = ManagedRepository(
                id = UUID.randomUUID().toString(),
                name = repositoryRoot.fileName.toString(),
                localPath = repositoryRoot.toString(),
                sourceKind = RepositorySourceKind.LOCAL,
                remoteUrl = remoteUrl,
                defaultBranch = defaultBranch,
                createdAt = now,
                updatedAt = now
            )
            stateStore.save(state.copy(repositories = state.repositories + repository))
            repository
        }
    }

    suspend fun cloneRepository(url: String): ManagedRepository {
        stateMutex.withLock {
            val existing = stateStore.load().repositories.firstOrNull { it.remoteUrl == url }
            if (existing != null) {
                return existing
            }
        }

        val repositoryRoot = gitWorkspaceService.cloneRepository(url)
        val now = System.currentTimeMillis()
        val defaultBranch = gitWorkspaceService.detectDefaultBranch(repositoryRoot)

        return stateMutex.withLock {
            val state = stateStore.load()
            val existingByUrl = state.repositories.firstOrNull { it.remoteUrl == url }
            if (existingByUrl != null) {
                return@withLock existingByUrl
            }

            val existingByPath = state.repositories.firstOrNull {
                sameRepositoryRoot(it.localPath, repositoryRoot)
            }
            if (existingByPath != null) {
                val refreshed = existingByPath.copy(
                    name = repositoryRoot.fileName.toString(),
                    localPath = repositoryRoot.toString(),
                    sourceKind = RepositorySourceKind.CLONED,
                    remoteUrl = url,
                    defaultBranch = defaultBranch,
                    updatedAt = now
                )
                stateStore.save(
                    state.copy(
                        repositories = state.repositories.map {
                            if (it.id == existingByPath.id) refreshed else it
                        }
                    )
                )
                return@withLock refreshed
            }

            val repository = ManagedRepository(
                id = UUID.randomUUID().toString(),
                name = repositoryRoot.fileName.toString(),
                localPath = repositoryRoot.toString(),
                sourceKind = RepositorySourceKind.CLONED,
                remoteUrl = url,
                defaultBranch = defaultBranch,
                createdAt = now,
                updatedAt = now
            )
            stateStore.save(state.copy(repositories = state.repositories + repository))
            repository
        }
    }

    suspend fun createWorkspace(repositoryId: String, name: String?, baseBranch: String?): Workspace {
        val state = stateStore.load()
        val repository = state.repositories.firstOrNull { it.id == repositoryId }
            ?: throw IllegalArgumentException("Repository not found: $repositoryId")
        val now = System.currentTimeMillis()
        val resolvedBranch = baseBranch?.ifBlank { null } ?: repository.defaultBranch
        val workspace = Workspace(
            id = UUID.randomUUID().toString(),
            repositoryId = repository.id,
            name = name?.takeIf { it.isNotBlank() } ?: "${repository.name} · $resolvedBranch",
            baseBranch = resolvedBranch,
            createdAt = now,
            updatedAt = now
        )

        stateStore.save(state.copy(workspaces = state.workspaces + workspace))
        return workspace
    }

    suspend fun createTask(
        workspaceId: String,
        title: String?,
        prompt: String,
        agents: List<String>,
        issueId: String? = null
    ): AgentTask {
        val state = stateStore.load()
        val workspace = state.workspaces.firstOrNull { it.id == workspaceId }
            ?: throw IllegalArgumentException("Workspace not found: $workspaceId")
        val repository = state.repositories.firstOrNull { it.id == workspace.repositoryId }
            ?: throw IllegalArgumentException("Repository not found for workspace: ${workspace.repositoryId}")
        val now = System.currentTimeMillis()
        val taskId = UUID.randomUUID().toString()
        val requestedAgents = agents.map { it.trim().lowercase() }.filter { it.isNotBlank() }.ifEmpty {
            listOf("claude", "codex")
        }
        val executionPlan = taskPlanner.buildPlan(
            title = title,
            prompt = prompt,
            agents = requestedAgents
        )

        // Freeze the requested agent list into the task record up front so rerenders,
        // retries, and later refreshes all refer to the same execution plan.
        val task = AgentTask(
            id = taskId,
            workspaceId = workspace.id,
            issueId = issueId,
            title = title?.takeIf { it.isNotBlank() } ?: prompt.lineSequence().firstOrNull()?.take(48).orEmpty().ifBlank { "New Task" },
            prompt = prompt,
            agents = requestedAgents,
            plan = executionPlan,
            status = DesktopTaskStatus.QUEUED,
            createdAt = now,
            updatedAt = now
        )

        val nextState = state.copy(
            tasks = state.tasks + task,
            repositories = state.repositories.map {
                if (it.id == repository.id) it.copy(updatedAt = now) else it
            },
            workspaces = state.workspaces.map {
                if (it.id == workspace.id) it.copy(updatedAt = now) else it
            }
        )
        stateStore.save(nextState)
        return task
    }

    suspend fun runTask(taskId: String): AgentTask {
        val task = getTask(taskId) ?: throw IllegalArgumentException("Task not found: $taskId")
        updateTaskStatus(task.id, DesktopTaskStatus.RUNNING)
        serviceScope.launch {
            // Long-running agent execution is detached from the request lifecycle.
            executeTask(task.id)
        }
        return task.copy(status = DesktopTaskStatus.RUNNING)
    }

    suspend fun getChanges(runId: String): ChangeSummary {
        val state = stateStore.load()
        val run = state.runs.firstOrNull { it.id == runId }
            ?: throw IllegalArgumentException("Run not found: $runId")
        val task = state.tasks.firstOrNull { it.id == run.taskId }
            ?: throw IllegalArgumentException("Task not found for run: ${run.taskId}")
        val workspace = state.workspaces.firstOrNull { it.id == task.workspaceId }
            ?: throw IllegalArgumentException("Workspace not found for task: ${task.workspaceId}")

        return gitWorkspaceService.buildChangeSummary(
            runId = run.id,
            worktreePath = Path.of(run.worktreePath),
            branchName = run.branchName,
            baseBranch = workspace.baseBranch
        )
    }

    suspend fun listFiles(runId: String, relativePath: String?): List<FileTreeNode> {
        val run = stateStore.load().runs.firstOrNull { it.id == runId }
            ?: throw IllegalArgumentException("Run not found: $runId")
        return gitWorkspaceService.listFiles(Path.of(run.worktreePath), relativePath)
    }

    suspend fun listPorts(runId: String): List<PortEntry> {
        val run = stateStore.load().runs.firstOrNull { it.id == runId }
            ?: throw IllegalArgumentException("Run not found: $runId")
        return gitWorkspaceService.listPorts(run.processId)
    }

    fun settings(): DesktopSettings = DesktopSettings(
        appHome = stateStore.appHome().toString(),
        managedReposRoot = stateStore.managedReposRoot().toString(),
        availableAgents = BuiltinAgentCatalog.names(),
        availableCliAgents = BuiltinAgentCatalog.names().filter { isExecutableAvailable(it) },
        recentCompanies = emptyList(),
        defaultLaunchMode = "company",
        shortcuts = ShortcutConfig()
    )

    private suspend fun upsertRepositoryForPath(
        repositories: List<ManagedRepository>,
        repositoryRoot: Path,
        now: Long
    ): ManagedRepository {
        val defaultBranch = gitWorkspaceService.detectDefaultBranch(repositoryRoot)
        val remoteUrl = gitWorkspaceService.detectRemoteUrl(repositoryRoot)
        val existing = repositories.firstOrNull { sameRepositoryRoot(it.localPath, repositoryRoot) }
        return if (existing != null) {
            existing.copy(
                name = repositoryRoot.fileName.toString(),
                localPath = repositoryRoot.toString(),
                remoteUrl = remoteUrl ?: existing.remoteUrl,
                defaultBranch = defaultBranch,
                updatedAt = now
            )
        } else {
            ManagedRepository(
                id = UUID.randomUUID().toString(),
                name = repositoryRoot.fileName.toString(),
                localPath = repositoryRoot.toString(),
                sourceKind = RepositorySourceKind.LOCAL,
                remoteUrl = remoteUrl,
                defaultBranch = defaultBranch,
                createdAt = now,
                updatedAt = now
            )
        }
    }

    private fun mergeRepository(
        repositories: List<ManagedRepository>,
        repository: ManagedRepository
    ): List<ManagedRepository> =
        repositories.filterNot { it.id == repository.id || sameRepositoryRoot(it.localPath, Path.of(repository.localPath)) } + repository

    private fun companyContextRoot(company: Company): Path =
        stateStore.appHome().resolve(".cotor").resolve("companies").resolve(slugify(company.name))

    private fun slugify(value: String): String =
        value.lowercase().replace("[^a-z0-9]+".toRegex(), "-").trim('-').ifBlank { "company" }

    private fun writeCompanyContextSnapshot(
        state: DesktopAppState,
        company: Company,
        projectContext: CompanyProjectContext
    ) {
        val root = companyContextRoot(company)
        val companyDir = root.resolve("goals")
        val projectDir = root.resolve("projects")
        val issuesDir = root.resolve("issues")
        Files.createDirectories(companyDir)
        Files.createDirectories(projectDir)
        Files.createDirectories(issuesDir)
        Files.writeString(
            root.resolve("company.md"),
            buildString {
                appendLine("# ${company.name}")
                appendLine()
                appendLine("- rootPath: ${company.rootPath}")
                appendLine("- defaultBaseBranch: ${company.defaultBaseBranch}")
                appendLine("- autonomyEnabled: ${company.autonomyEnabled}")
            }
        )
        Files.writeString(
            projectDir.resolve("default.md"),
            buildString {
                appendLine("# ${projectContext.name}")
                appendLine()
                appendLine("Company project context for ${company.name}.")
            }
        )
        state.goals.filter { it.companyId == company.id }.forEach { goal ->
            Files.writeString(
                companyDir.resolve("${goal.id}.md"),
                buildString {
                    appendLine("# ${goal.title}")
                    appendLine()
                    appendLine(goal.description)
                }
            )
        }
        state.issues.filter { it.companyId == company.id }.forEach { issue ->
            Files.writeString(
                issuesDir.resolve("${issue.id}.md"),
                buildString {
                    appendLine("# ${issue.title}")
                    appendLine()
                    appendLine("- status: ${issue.status}")
                    appendLine("- kind: ${issue.kind}")
                    appendLine()
                    appendLine(issue.description)
                }
            )
        }
    }

    private fun sameRepositoryRoot(savedPath: String, repositoryRoot: Path): Boolean =
        Path.of(savedPath).toAbsolutePath().normalize() == repositoryRoot.toAbsolutePath().normalize()

    private suspend fun executeTask(taskId: String) {
        val snapshot = stateStore.load()
        val task = snapshot.tasks.firstOrNull { it.id == taskId } ?: return
        val workspace = snapshot.workspaces.firstOrNull { it.id == task.workspaceId } ?: return
        val repository = snapshot.repositories.firstOrNull { it.id == workspace.repositoryId } ?: return
        val repositoryRoot = Path.of(repository.localPath)
        val agents = resolveAgents(repositoryRoot, task.agents)

        updateTaskStatus(task.id, DesktopTaskStatus.RUNNING)

        coroutineScope {
            // Each requested agent gets its own branch/worktree pair, so parallel execution
            // does not create cross-agent file conflicts inside the same repository root.
            task.agents.map { agentName ->
                async {
                    executeAgentRun(
                        task = task,
                        workspace = workspace,
                        repository = repository,
                        agentName = agentName,
                        agent = agents[agentName] ?: BuiltinAgentCatalog.get(agentName)
                    )
                }
            }.awaitAll()
        }

        val latestRuns = listRuns(task.id)
        val successCount = latestRuns.count { it.status == AgentRunStatus.COMPLETED }
        val failedCount = latestRuns.count { it.status == AgentRunStatus.FAILED }
        val finalStatus = when {
            successCount == latestRuns.size && latestRuns.isNotEmpty() -> DesktopTaskStatus.COMPLETED
            successCount > 0 && failedCount > 0 -> DesktopTaskStatus.PARTIAL
            else -> DesktopTaskStatus.FAILED
        }
        updateTaskStatus(task.id, finalStatus)
        syncIssueFromTask(task.id, finalStatus)
    }

    private suspend fun executeAgentRun(
        task: AgentTask,
        workspace: Workspace,
        repository: ManagedRepository,
        agentName: String,
        agent: AgentConfig?
    ) {
        if (agent == null) {
            recordRunFailure(task, workspace, repository, agentName, "Unknown agent configuration")
            return
        }

        try {
            val binding = gitWorkspaceService.ensureWorktree(
                repositoryRoot = Path.of(repository.localPath),
                taskId = task.id,
                taskTitle = task.title,
                agentName = agent.name,
                baseBranch = workspace.baseBranch
            )
            val run = upsertQueuedRun(task, workspace, repository, agent.name, binding)

            val startedRun = run.copy(status = AgentRunStatus.RUNNING, updatedAt = System.currentTimeMillis())
            replaceRun(startedRun)

            val result = agentExecutor.executeAgent(
                agent = agent,
                input = assignedPromptFor(task, agent.name),
                // The only context the CLI agents need for worktree isolation is cwd.
                // They can continue using their existing command-line contracts.
                metadata = AgentExecutionMetadata(
                    repoRoot = Path.of(repository.localPath),
                    workspaceId = workspace.id,
                    taskId = task.id,
                    agentId = agent.name,
                    baseBranch = workspace.baseBranch,
                    branchName = binding.branchName,
                    workingDirectory = binding.worktreePath
                )
            )
            val publish = if (result.isSuccess) {
                gitWorkspaceService.publishRun(
                    task = task,
                    agentName = agent.name,
                    worktreePath = binding.worktreePath,
                    branchName = binding.branchName,
                    baseBranch = workspace.baseBranch
                )
            } else {
                null
            }
            val finalError = result.error ?: publish?.error

            replaceRun(
                startedRun.copy(
                    status = if (result.isSuccess && publish?.error == null) AgentRunStatus.COMPLETED else AgentRunStatus.FAILED,
                    processId = result.processId,
                    output = result.output,
                    error = finalError,
                    publish = publish,
                    durationMs = result.duration.takeIf { it > 0 },
                    updatedAt = System.currentTimeMillis()
                )
            )
        } catch (t: Throwable) {
            recordRunFailure(task, workspace, repository, agent.name, t.message ?: "Unknown error")
        }
    }

    private fun assignedPromptFor(task: AgentTask, agentName: String): String =
        task.plan?.assignmentFor(agentName)?.assignedPrompt ?: task.prompt

    private suspend fun resolveAgents(repositoryRoot: Path, requestedAgents: List<String>): Map<String, AgentConfig> {
        val configPath = repositoryRoot.resolve("cotor.yaml")
        val configured = if (configPath.exists()) {
            configRepository.loadConfig(configPath).agents.associateBy { it.name.lowercase() }
        } else {
            emptyMap()
        }

        return requestedAgents.associateWith { name ->
            configured[name.lowercase()] ?: BuiltinAgentCatalog.get(name)
        }.filterValues { it != null }
            .mapValues { it.value!! }
    }

    private suspend fun upsertQueuedRun(
        task: AgentTask,
        workspace: Workspace,
        repository: ManagedRepository,
        agentName: String,
        binding: WorktreeBinding
    ): AgentRun {
        val state = stateStore.load()
        val existing = state.runs.firstOrNull { it.taskId == task.id && it.agentName == agentName }
        if (existing != null) {
            return existing
        }

        val now = System.currentTimeMillis()
        val run = AgentRun(
            id = UUID.randomUUID().toString(),
            taskId = task.id,
            workspaceId = workspace.id,
            repositoryId = repository.id,
            agentId = agentName,
            agentName = agentName,
            repoRoot = repository.localPath,
            baseBranch = workspace.baseBranch,
            branchName = binding.branchName,
            worktreePath = binding.worktreePath.toString(),
            status = AgentRunStatus.QUEUED,
            createdAt = now,
            updatedAt = now
        )
        stateStore.save(state.copy(runs = state.runs + run))
        return run
    }

    private suspend fun replaceRun(run: AgentRun) {
        stateMutex.withLock {
            val state = stateStore.load()
            stateStore.save(
                state.copy(
                    runs = state.runs.map { existing -> if (existing.id == run.id) run else existing }
                )
            )
        }
    }

    private suspend fun updateTaskStatus(taskId: String, status: DesktopTaskStatus) {
        stateMutex.withLock {
            val state = stateStore.load()
            stateStore.save(
                state.copy(
                    tasks = state.tasks.map {
                        if (it.id == taskId) {
                            it.copy(status = status, updatedAt = System.currentTimeMillis())
                        } else {
                            it
                        }
                    }
                )
            )
        }
    }

    private suspend fun recordRunFailure(
        task: AgentTask,
        workspace: Workspace,
        repository: ManagedRepository,
        agentName: String,
        message: String
    ) {
        // Even when worktree creation fails early, keep a synthetic branch name in the run
        // record so the UI can still explain which isolated slot was intended for this agent.
        val binding = WorktreeBinding(
            branchName = "codex/cotor/${task.id.take(8)}/${agentName.trim().lowercase().replace("[^a-z0-9]+".toRegex(), "-").trim('-')}",
            worktreePath = Path.of(repository.localPath)
        )
        val run = upsertQueuedRun(task, workspace, repository, agentName, binding)
        replaceRun(
            run.copy(
                status = AgentRunStatus.FAILED,
                error = message,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun decomposeGoal(
        goal: CompanyGoal,
        workspace: Workspace,
        profiles: List<OrgAgentProfile>,
        now: Long
    ): Pair<List<CompanyIssue>, List<IssueDependency>> {
        val agents = profiles.filter { it.enabled }.map { it.executionAgentName }.distinct().ifEmpty {
            listOf("claude", "codex")
        }
        val plan = taskPlanner.buildPlan(
            title = goal.title,
            prompt = goal.description,
            agents = agents
        )
        val issueIds = plan.assignments.map { UUID.randomUUID().toString() }
        val issues = plan.assignments.mapIndexed { index, assignment ->
            val profile = profiles.firstOrNull { it.executionAgentName.equals(assignment.agentName, ignoreCase = true) }
            val dependencyId = issueIds.getOrNull(index - 1)
            CompanyIssue(
                id = issueIds[index],
                companyId = goal.companyId,
                projectContextId = goal.projectContextId,
                goalId = goal.id,
                workspaceId = workspace.id,
                title = assignment.subtasks.firstOrNull()?.title ?: "${assignment.role}: ${assignment.focus}",
                description = buildIssueDescription(goal, assignment, plan.sharedChecklist),
                status = if (index == 0) IssueStatus.PLANNED else IssueStatus.BACKLOG,
                priority = if (index == 0) 2 else 3,
                kind = assignment.role.lowercase().replace(" ", "-"),
                assigneeProfileId = profile?.id,
                blockedBy = dependencyId?.let(::listOf) ?: emptyList(),
                dependsOn = dependencyId?.let(::listOf) ?: emptyList(),
                acceptanceCriteria = (plan.sharedChecklist + assignment.subtasks.map { it.title }).distinct(),
                riskLevel = if (assignment.role.contains("review", ignoreCase = true)) "low" else "medium",
                createdAt = now + index,
                updatedAt = now + index
            )
        }
        val dependencies = issues.zipWithNext().map { (before, after) ->
            IssueDependency(
                id = UUID.randomUUID().toString(),
                issueId = after.id,
                dependsOnIssueId = before.id
            )
        }
        return issues to dependencies
    }

    private fun buildIssueDescription(
        goal: CompanyGoal,
        assignment: AgentAssignmentPlan,
        sharedChecklist: List<String>
    ): String = buildString {
        appendLine("Goal: ${goal.title}")
        appendLine()
        appendLine("Role: ${assignment.role}")
        appendLine("Focus: ${assignment.focus}")
        if (assignment.subtasks.isNotEmpty()) {
            appendLine()
            appendLine("Owned subtasks:")
            assignment.subtasks.forEach { subtask ->
                appendLine("- ${subtask.title}: ${subtask.details}")
            }
        }
        if (sharedChecklist.isNotEmpty()) {
            appendLine()
            appendLine("Shared checklist:")
            sharedChecklist.forEach { item ->
                appendLine("- $item")
            }
        }
        appendLine()
        append(assignment.assignedPrompt)
    }.trim()

    private fun ensureOrgProfiles(
        existing: List<OrgAgentProfile>,
        definitions: List<CompanyAgentDefinition>,
        companies: List<Company>
    ): List<OrgAgentProfile> {
        if (definitions.isNotEmpty()) {
            return deriveProfiles(definitions, companies)
        }
        if (existing.isNotEmpty()) {
            return existing
        }
        return companies.flatMap { company ->
            seedCompanyAgentDefinitions(company.id, System.currentTimeMillis())
        }.let { derived ->
            if (derived.isNotEmpty()) deriveProfiles(derived, companies) else emptyList()
        }
    }

    private fun deriveProfiles(
        definitions: List<CompanyAgentDefinition>,
        companies: List<Company>
    ): List<OrgAgentProfile> {
        if (definitions.isEmpty()) {
            return emptyList()
        }
        val knownCompanies = companies.associateBy { it.id }
        return definitions
            .filter { it.enabled && knownCompanies.containsKey(it.companyId) }
            .sortedWith(
                compareBy<CompanyAgentDefinition> { it.companyId }
                    .thenBy { it.displayOrder }
                    .thenBy { it.title.lowercase() }
            )
            .map { definition ->
                val role = definition.title.trim().ifEmpty { "Agent" }
                val summary = definition.roleSummary.lowercase()
                val capabilities = when {
                    summary.contains("qa") || summary.contains("review") || summary.contains("test") || summary.contains("verification") ->
                        listOf("qa", "review", "verification")
                    summary.contains("ceo") || summary.contains("lead") || summary.contains("strategy") || summary.contains("planning") ->
                        listOf("planning", "triage", "goal-decomposition")
                    else ->
                        listOf("implementation", "integration", "backend", "frontend")
                }
                val isChief = summary.contains("ceo") || summary.contains("lead") || role.equals("CEO", ignoreCase = true)
                val isQa = capabilities.contains("qa")
                OrgAgentProfile(
                    id = definition.id,
                    companyId = definition.companyId,
                    roleName = role,
                    executionAgentName = definition.agentCli,
                    capabilities = capabilities,
                    reviewerPolicy = if (isQa) "review-queue" else null,
                    mergeAuthority = isChief
                )
            }
    }

    private fun seedCompanyAgentDefinitions(companyId: String, now: Long): List<CompanyAgentDefinition> {
        val builtins = BuiltinAgentCatalog.names()
        val preferredAgents = listOf("codex", "gemini", "claude", "opencode", "qwen")
            .filter { candidate ->
                builtins.any { it.equals(candidate, ignoreCase = true) } && isExecutableAvailable(candidate)
            }
            .ifEmpty { builtins.filter { isExecutableAvailable(it) }.take(3) }
            .ifEmpty { listOfNotNull(builtins.firstOrNull { it.equals("echo", ignoreCase = true) }) }
            .ifEmpty { builtins.take(3) }
        return preferredAgents.mapIndexed { index, agentName ->
            val title = when (index) {
                0 -> "CEO"
                1 -> "Builder"
                else -> "QA"
            }
            val roleSummary = when (index) {
                0 -> "lead strategy, planning, triage, final merge"
                1 -> "implementation, integration, delivery"
                else -> "qa, review, verification"
            }
            CompanyAgentDefinition(
                id = UUID.randomUUID().toString(),
                companyId = companyId,
                title = title,
                agentCli = agentName,
                roleSummary = roleSummary,
                displayOrder = index,
                createdAt = now,
                updatedAt = now
            )
        }
    }

    private fun suggestProfileForIssue(
        issue: CompanyIssue,
        profiles: List<OrgAgentProfile>
    ): OrgAgentProfile? {
        profiles.firstOrNull { it.id == issue.assigneeProfileId && it.enabled }?.let { return it }
        val haystack = listOf(issue.title, issue.description, issue.kind).joinToString(" ").lowercase()
        return profiles.firstOrNull { profile ->
            profile.enabled && profile.capabilities.any { haystack.contains(it.lowercase()) }
        } ?: profiles.firstOrNull { it.enabled }
    }

    private fun isExecutableAvailable(command: String): Boolean {
        if (command.equals("echo", ignoreCase = true)) {
            return true
        }
        return commandAvailability(command)
    }

    private fun ensureCompanyWorkspace(state: DesktopAppState, company: Company, now: Long): Pair<DesktopAppState, Workspace> {
        state.workspaces.firstOrNull { it.repositoryId == company.repositoryId && it.baseBranch == company.defaultBaseBranch }
            ?.let { return state to it }
        val repository = state.repositories.firstOrNull { it.id == company.repositoryId }
            ?: throw IllegalStateException("Open or clone a repository before creating a goal")
        val workspace = Workspace(
            id = UUID.randomUUID().toString(),
            repositoryId = repository.id,
            name = "${company.name} · ${company.defaultBaseBranch}",
            baseBranch = company.defaultBaseBranch,
            createdAt = now,
            updatedAt = now
        )
        val nextState = state.copy(
            repositories = state.repositories.map {
                if (it.id == repository.id) it.copy(updatedAt = now) else it
            },
            workspaces = state.workspaces + workspace
        )
        return nextState to workspace
    }

    private fun resolveCompanyForGoal(state: DesktopAppState, companyId: String?, now: Long): Company {
        if (companyId != null) {
            return state.companies.firstOrNull { it.id == companyId }
                ?: throw IllegalArgumentException("Company not found: $companyId")
        }
        state.companies.maxByOrNull { it.updatedAt }?.let { return it }
        val repository = state.repositories.maxByOrNull { it.updatedAt }
            ?: throw IllegalStateException("Open or clone a repository before creating a goal")
        return Company(
            id = UUID.randomUUID().toString(),
            name = repository.name,
            rootPath = repository.localPath,
            repositoryId = repository.id,
            defaultBaseBranch = repository.defaultBranch,
            autonomyEnabled = true,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun ensureProjectContext(state: DesktopAppState, company: Company, now: Long): CompanyProjectContext {
        state.projectContexts.firstOrNull { it.companyId == company.id }?.let { return it }
        return CompanyProjectContext(
            id = UUID.randomUUID().toString(),
            companyId = company.id,
            name = company.name,
            slug = slugify(company.name),
            contextDocPath = companyContextRoot(company).resolve("projects/default.md").toString(),
            lastUpdatedAt = now
        )
    }

    private suspend fun syncIssueFromTask(taskId: String, finalStatus: DesktopTaskStatus) {
        val snapshot = stateStore.load()
        val task = snapshot.tasks.firstOrNull { it.id == taskId } ?: return
        val issueId = task.issueId ?: return
        val latestRuns = snapshot.runs.filter { it.taskId == taskId }
        if (latestRuns.isEmpty()) {
            return
        }
        val primaryRun = latestRuns.firstOrNull { it.publish?.pullRequestUrl != null } ?: latestRuns.first()
        val now = System.currentTimeMillis()
        stateMutex.withLock {
            val state = stateStore.load()
            val issue = state.issues.firstOrNull { it.id == issueId } ?: return@withLock
            val nextIssueStatus = when {
                finalStatus == DesktopTaskStatus.COMPLETED && primaryRun.publish?.pullRequestUrl != null -> IssueStatus.IN_REVIEW
                finalStatus == DesktopTaskStatus.COMPLETED -> IssueStatus.DONE
                else -> IssueStatus.BLOCKED
            }
            val updatedIssue = issue.copy(status = nextIssueStatus, updatedAt = now)
            val queueStatus = when {
                primaryRun.publish?.error != null -> ReviewQueueStatus.FAILED_CHECKS
                primaryRun.publish?.pullRequestUrl != null -> ReviewQueueStatus.READY_TO_MERGE
                else -> ReviewQueueStatus.AWAITING_QA
            }
            val nextReviewQueue = if (primaryRun.publish?.pullRequestUrl != null || primaryRun.publish?.pullRequestNumber != null) {
                val existing = state.reviewQueue.firstOrNull { it.issueId == issueId }
                val queueItem = ReviewQueueItem(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    companyId = issue.companyId,
                    projectContextId = issue.projectContextId,
                    issueId = issueId,
                    runId = primaryRun.id,
                    pullRequestNumber = primaryRun.publish?.pullRequestNumber,
                    pullRequestUrl = primaryRun.publish?.pullRequestUrl,
                    status = queueStatus,
                    checksSummary = primaryRun.publish?.checksSummary ?: primaryRun.error,
                    mergeability = primaryRun.publish?.mergeability,
                    requestedReviewers = primaryRun.publish?.requestedReviewers ?: emptyList(),
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now
                )
                state.reviewQueue.filterNot { it.id == queueItem.id || it.issueId == issueId } + queueItem
            } else {
                state.reviewQueue
            }
            val nextGoals = state.goals.map { goal ->
                if (goal.id != issue.goalId) {
                    goal
                } else {
                    val unresolved = state.issues
                        .map { if (it.id == issueId) updatedIssue else it }
                        .any { it.goalId == goal.id && it.status != IssueStatus.DONE && it.status != IssueStatus.CANCELED }
                    if (!unresolved) {
                        goal.copy(status = GoalStatus.COMPLETED, updatedAt = now)
                    } else {
                        goal
                    }
                }
            }
            val nextState = state.copy(
                issues = state.issues.map { if (it.id == issueId) updatedIssue else it },
                reviewQueue = nextReviewQueue,
                goals = nextGoals
            ).recordCompanyActivity(
                companyId = updatedIssue.companyId,
                projectContextId = updatedIssue.projectContextId,
                goalId = updatedIssue.goalId,
                issueId = updatedIssue.id,
                source = "task-run",
                title = "Updated issue state",
                detail = "${updatedIssue.title} -> ${updatedIssue.status}"
            ).recordSignal(
                source = "task-run",
                message = "Issue ${updatedIssue.title} moved to ${updatedIssue.status}",
                companyId = updatedIssue.companyId,
                projectContextId = updatedIssue.projectContextId,
                goalId = updatedIssue.goalId,
                issueId = updatedIssue.id,
                severity = if (updatedIssue.status == IssueStatus.BLOCKED) "warning" else "info"
            ).withDerivedMetrics()
            stateStore.save(nextState)
        }
    }

    private fun ensureCompanyRuntimeLoop(companyId: String) {
        if (companyRuntimeJobs[companyId]?.isActive == true) {
            return
        }
        companyRuntimeJobs[companyId] = serviceScope.launch {
            while (isActive) {
                delay(companyRuntimeTickIntervalMs)
                try {
                    if (runtimeStatus(companyId).status != CompanyRuntimeStatus.RUNNING) {
                        break
                    }
                    runCompanyRuntimeTick(companyId)
                } catch (cause: Throwable) {
                    markCompanyRuntimeError(companyId, cause)
                    break
                }
            }
        }
    }

    private suspend fun markCompanyRuntimeError(companyId: String, cause: Throwable) {
        stateMutex.withLock {
            val state = stateStore.load()
            val now = System.currentTimeMillis()
            val currentRuntime = state.companyRuntimes.firstOrNull { it.companyId == companyId }
                ?: CompanyRuntimeSnapshot(companyId = companyId)
            val nextState = state.copy(
                companyRuntimes = upsertCompanyRuntime(
                    state.companyRuntimes,
                    currentRuntime.copy(
                        companyId = companyId,
                        status = CompanyRuntimeStatus.ERROR,
                        lastStoppedAt = now,
                        lastAction = "runtime-error",
                        lastError = cause.message ?: cause::class.simpleName
                    )
                )
            ).recordSignal(
                source = "runtime",
                message = "Autonomous runtime failed: ${cause.message ?: cause::class.simpleName}",
                severity = "error",
                companyId = companyId
            ).recordCompanyActivity(
                companyId = companyId,
                source = "runtime",
                title = "Runtime error",
                detail = cause.message ?: cause::class.simpleName,
                severity = "error"
            ).withDerivedMetrics()
            stateStore.save(nextState)
        }
    }

    private suspend fun updateRuntimeAfterTick(companyId: String, lastAction: String): CompanyRuntimeSnapshot =
        stateMutex.withLock {
            val state = stateStore.load()
            val current = state.companyRuntimes.firstOrNull { it.companyId == companyId }
                ?: CompanyRuntimeSnapshot(companyId = companyId)
            if (current.status != CompanyRuntimeStatus.RUNNING) {
                val settledState = state.withDerivedMetrics()
                stateStore.save(settledState)
                return@withLock settledState.companyRuntimes.firstOrNull { it.companyId == companyId }
                    ?: CompanyRuntimeSnapshot(companyId = companyId)
            }
            val nextState = state.copy(
                companyRuntimes = upsertCompanyRuntime(
                    state.companyRuntimes,
                    current.copy(
                        companyId = companyId,
                        status = CompanyRuntimeStatus.RUNNING,
                        tickIntervalSeconds = companyRuntimeTickIntervalMs / 1000,
                        lastTickAt = System.currentTimeMillis(),
                        lastAction = lastAction,
                        lastError = null
                    )
                )
            ).recordSignal(
                source = "runtime-tick",
                message = lastAction,
                companyId = companyId
            ).withDerivedMetrics()
            stateStore.save(nextState)
            nextState.companyRuntimes.firstOrNull { it.companyId == companyId } ?: CompanyRuntimeSnapshot(companyId = companyId)
        }

    private fun DesktopAppState.withDerivedMetrics(): DesktopAppState {
        val derivedCompanyRuntimes = computeCompanyRuntimes()
        return copy(
            opsMetrics = computeOpsMetrics(),
            runtime = computeRuntimeSnapshot(derivedCompanyRuntimes),
            companyRuntimes = derivedCompanyRuntimes
        )
    }

    private fun DesktopAppState.computeOpsMetrics(): OpsMetricSnapshot =
        OpsMetricSnapshot(
            openGoals = goals.count { it.status != GoalStatus.COMPLETED },
            activeIssues = issues.count { it.status == IssueStatus.PLANNED || it.status == IssueStatus.DELEGATED || it.status == IssueStatus.IN_PROGRESS || it.status == IssueStatus.IN_REVIEW },
            blockedIssues = issues.count { it.status == IssueStatus.BLOCKED },
            readyToMergeCount = reviewQueue.count { it.status == ReviewQueueStatus.READY_TO_MERGE },
            mergedCount = reviewQueue.count { it.status == ReviewQueueStatus.MERGED },
            lastUpdatedAt = System.currentTimeMillis()
        )

    private fun DesktopAppState.computeRuntimeSnapshot(companySnapshots: List<CompanyRuntimeSnapshot>): CompanyRuntimeSnapshot {
        val latestCompanyRuntime = companySnapshots.maxByOrNull {
            maxOf(
                it.lastTickAt ?: 0L,
                it.lastStartedAt ?: 0L,
                it.lastStoppedAt ?: 0L
            )
        }
        val seed = latestCompanyRuntime ?: runtime
        return seed.copy(
            tickIntervalSeconds = companyRuntimeTickIntervalMs / 1000,
            activeGoalCount = goals.count { it.status == GoalStatus.ACTIVE },
            activeIssueCount = issues.count {
                it.status == IssueStatus.PLANNED ||
                    it.status == IssueStatus.DELEGATED ||
                    it.status == IssueStatus.IN_PROGRESS ||
                    it.status == IssueStatus.IN_REVIEW
            },
            autonomyEnabledGoalCount = goals.count { it.autonomyEnabled }
        )
    }

    private fun DesktopAppState.computeCompanyRuntimes(): List<CompanyRuntimeSnapshot> =
        allKnownCompanyIds().map { companyId ->
            val existing = companyRuntimes.firstOrNull { it.companyId == companyId } ?: CompanyRuntimeSnapshot(companyId = companyId)
            existing.copy(
                companyId = companyId,
                tickIntervalSeconds = companyRuntimeTickIntervalMs / 1000,
                activeGoalCount = goals.count { it.companyId == companyId && it.status == GoalStatus.ACTIVE },
                activeIssueCount = issues.count {
                    it.companyId == companyId &&
                        (
                            it.status == IssueStatus.PLANNED ||
                                it.status == IssueStatus.DELEGATED ||
                                it.status == IssueStatus.IN_PROGRESS ||
                                it.status == IssueStatus.IN_REVIEW
                            )
                },
                autonomyEnabledGoalCount = goals.count { it.companyId == companyId && it.autonomyEnabled }
            )
        }

    private fun DesktopAppState.allKnownCompanyIds(): List<String> =
        buildSet {
            companies.mapTo(this) { it.id }
            goals.mapTo(this) { it.companyId }
            issues.mapTo(this) { it.companyId }
            reviewQueue.mapNotNullTo(this) { it.companyId }
            signals.mapNotNullTo(this) { it.companyId }
            companyRuntimes.mapNotNullTo(this) { it.companyId }
        }.toList()

    private fun hasCompanyScope(state: DesktopAppState, companyId: String): Boolean =
        companyId in state.allKnownCompanyIds()

    private fun resolveLatestCompanyId(state: DesktopAppState): String? =
        state.companies.maxByOrNull { it.updatedAt }?.id
            ?: state.goals.maxByOrNull { it.updatedAt }?.companyId
            ?: state.issues.maxByOrNull { it.updatedAt }?.companyId
            ?: state.companyRuntimes.maxByOrNull {
                maxOf(
                    it.lastTickAt ?: 0L,
                    it.lastStartedAt ?: 0L,
                    it.lastStoppedAt ?: 0L
                )
            }?.companyId

    private fun DesktopAppState.recordSignal(
        source: String,
        message: String,
        severity: String = "info",
        companyId: String? = null,
        projectContextId: String? = null,
        goalId: String? = null,
        issueId: String? = null
    ): DesktopAppState {
        val signal = OpsSignal(
            id = UUID.randomUUID().toString(),
            companyId = companyId,
            projectContextId = projectContextId,
            source = source,
            message = message,
            severity = severity,
            goalId = goalId,
            issueId = issueId,
            createdAt = System.currentTimeMillis()
        )
        return copy(signals = (listOf(signal) + signals).take(100))
    }

    private fun DesktopAppState.recordCompanyActivity(
        companyId: String,
        source: String,
        title: String,
        detail: String? = null,
        severity: String = "info",
        projectContextId: String? = null,
        goalId: String? = null,
        issueId: String? = null
    ): DesktopAppState {
        val item = CompanyActivityItem(
            id = UUID.randomUUID().toString(),
            companyId = companyId,
            projectContextId = projectContextId,
            goalId = goalId,
            issueId = issueId,
            source = source,
            title = title,
            detail = detail,
            severity = severity,
            createdAt = System.currentTimeMillis()
        )
        return copy(companyActivity = (listOf(item) + companyActivity).take(200))
    }

    private fun upsertCompanyRuntime(
        runtimes: List<CompanyRuntimeSnapshot>,
        updated: CompanyRuntimeSnapshot
    ): List<CompanyRuntimeSnapshot> =
        runtimes.filterNot { it.companyId == updated.companyId } + updated
}

private fun defaultCommandAvailability(command: String): Boolean {
    val path = System.getenv("PATH") ?: return false
    return path.split(java.io.File.pathSeparator).any { dir ->
        val file = java.io.File(dir, command)
        file.exists() && file.canExecute()
    }
}
