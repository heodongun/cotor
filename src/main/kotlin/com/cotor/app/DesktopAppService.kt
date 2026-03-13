package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.data.process.resolveExecutablePath
import com.cotor.domain.executor.AgentExecutor
import com.cotor.domain.planning.GoalDrivenTaskPlanner
import com.cotor.integrations.linear.DefaultLinearTrackerAdapter
import com.cotor.integrations.linear.LinearTrackerAdapter
import com.cotor.model.AgentConfig
import com.cotor.model.AgentExecutionMetadata
import com.cotor.model.AgentResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.nio.file.Files
import java.util.Comparator
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
    private val commandAvailability: (String) -> Boolean = ::defaultCommandAvailability,
    private val linearTracker: LinearTrackerAdapter = DefaultLinearTrackerAdapter(),
    private val codexAppServerManager: CodexAppServerManager = CodexAppServerManager()
) {
    // Reads are cheap and frequent, but writes must be serialized so the state file
    // cannot be partially overwritten when multiple agent runs finish at once.
    private val stateMutex = Mutex()
    private val companyRuntimeTickMutexes = mutableMapOf<String, Mutex>()

    // Runs execute in background coroutines so the API can return immediately after
    // the user presses "Run Task" in the desktop client.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val companyRuntimeJobs: MutableMap<String, Job> = linkedMapOf()
    private val automationRefreshJobs: MutableMap<String, Job> = linkedMapOf()
    private val staleRunStartupGraceMs = 15_000L
    private val companyEventStream = MutableSharedFlow<CompanyEventEnvelope>(replay = 0, extraBufferCapacity = 64)
    private val backendJson = Json { ignoreUnknownKeys = true }
    private val localExecutionBackend = LocalCotorBackend(agentExecutor)
    private val codexAppServerBackend = CodexAppServerBackend(backendJson)

    suspend fun dashboard(): DashboardResponse {
        queueAutomationRefresh()
        val state = stateStore.load().withDerivedMetrics()
        val orgProfiles = ensureOrgProfiles(state.orgProfiles, state.companyAgentDefinitions, state.companies)
        return DashboardResponse(
            repositories = state.repositories.sortedByDescending { it.updatedAt },
            workspaces = state.workspaces.sortedByDescending { it.updatedAt },
            tasks = state.tasks.sortedByDescending { it.createdAt },
            settings = settings(),
            companies = state.companies.sortedByDescending { it.updatedAt },
            companyAgentDefinitions = state.companyAgentDefinitions
                .sortedWith(compareBy<CompanyAgentDefinition> { it.displayOrder }.thenBy { it.title.lowercase() }),
            projectContexts = state.projectContexts.sortedByDescending { it.lastUpdatedAt },
            goals = state.goals.sortedByDescending { it.updatedAt },
            issues = state.issues.sortedByDescending { it.updatedAt },
            reviewQueue = state.reviewQueue.sortedByDescending { it.updatedAt },
            orgProfiles = orgProfiles,
            workflowTopologies = computeWorkflowTopologies(state, orgProfiles),
            goalDecisions = state.goalDecisions.sortedByDescending { it.createdAt },
            runningAgentSessions = computeRunningAgentSessions(state, orgProfiles),
            backendStatuses = computeBackendStatuses(state),
            opsMetrics = state.opsMetrics,
            activity = state.companyActivity.sortedByDescending { it.createdAt },
            companyRuntimes = state.companyRuntimes.sortedByDescending { it.lastTickAt ?: 0L }
        )
    }

    suspend fun companyDashboard(companyId: String? = null): CompanyDashboardResponse {
        queueAutomationRefresh(companyId)
        val state = stateStore.load().withDerivedMetrics()
        val filteredGoals = state.goals
            .filter { companyId == null || it.companyId == companyId }
            .sortedByDescending { it.updatedAt }
        val filteredIssues = state.issues
            .filter { companyId == null || it.companyId == companyId }
            .sortedByDescending { it.updatedAt }
        val filteredIssueIds = filteredIssues.map { it.id }.toSet()
        val orgProfiles = ensureOrgProfiles(state.orgProfiles, state.companyAgentDefinitions, state.companies)
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
            orgProfiles = orgProfiles
                .filter { companyId == null || it.companyId == companyId },
            workflowTopologies = computeWorkflowTopologies(state, orgProfiles)
                .filter { companyId == null || it.companyId == companyId },
            goalDecisions = state.goalDecisions
                .filter { companyId == null || it.companyId == companyId }
                .sortedByDescending { it.createdAt },
            runningAgentSessions = computeRunningAgentSessions(state, orgProfiles)
                .filter { companyId == null || it.companyId == companyId },
            backendStatuses = computeBackendStatuses(state)
                .filter { companyId == null || state.companies.firstOrNull { company -> company.id == companyId }?.backendKind == it.kind || (companyId == null) },
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

    private fun queueAutomationRefresh(companyId: String? = null) {
        val key = companyId ?: "__all__"
        if (automationRefreshJobs[key]?.isActive == true) {
            return
        }
        automationRefreshJobs[key] = serviceScope.launch {
            try {
                prepareCompanyAutomationState(companyId)
            } finally {
                automationRefreshJobs.remove(key)
            }
        }
    }

    fun companyEvents(companyId: String): Flow<CompanyEventEnvelope> =
        companyEventStream.filter { it.event.companyId == companyId }

    private suspend fun prepareCompanyAutomationState(companyId: String? = null) {
        migrateLegacyCompanyRosters(companyId)
        resumeRunningCompanyRuntimes(companyId)
        ensureAutonomousCompanyRuntimes(companyId)
        reconcileStaleAgentRuns(companyId)
        val targetCompanyIds = stateStore.load().companies
            .map { it.id }
            .filter { companyId == null || it == companyId }
        targetCompanyIds.forEach { activeCompanyId ->
            normalizeCompanyAutomationState(activeCompanyId)
            reconcileTerminalIssueStates(activeCompanyId)
            archiveRecursiveFollowUpGoals(activeCompanyId)
            requeueRecoverableBlockedIssues(activeCompanyId)
            requeueBlockedRemediationIssues(activeCompanyId)
        }
        stimulateAutonomousCompanyProgress(companyId)
    }

    private suspend fun migrateLegacyCompanyRosters(companyId: String? = null) {
        stateMutex.withLock {
            val state = stateStore.load()
            val now = System.currentTimeMillis()
            val targetCompanies = state.companies.filter { companyId == null || it.id == companyId }
            val companiesNeedingMigration = targetCompanies.filter { company ->
                state.companyAgentDefinitions.none { it.companyId == company.id }
            }
            if (companiesNeedingMigration.isEmpty()) {
                return@withLock
            }

            val seededByCompany: Map<Company, List<CompanyAgentDefinition>> = companiesNeedingMigration.associateWith { company ->
                seedCompanyAgentDefinitions(company.id, now)
            }
            val definitionsByCompany: Map<String, Map<String, CompanyAgentDefinition>> =
                seededByCompany.mapValues { (_, defs) -> defs.associateBy { it.title } }
                    .mapKeys { (company, _) -> company.id }
            val migratedIssueAssignments = state.issues.map { issue ->
                val fallbackDefinitions = definitionsByCompany[issue.companyId] ?: return@map issue
                val matchingRole = state.orgProfiles
                    .firstOrNull { it.id == issue.assigneeProfileId }
                    ?.roleName
                    ?.let(fallbackDefinitions::get)
                issue.copy(
                    assigneeProfileId = matchingRole?.id ?: issue.assigneeProfileId
                )
            }
            val nextDefinitions = state.companyAgentDefinitions + seededByCompany.values.flatten()
            val nextProfiles = deriveProfiles(nextDefinitions, state.companies)
            val nextState = state.copy(
                companyAgentDefinitions = nextDefinitions,
                orgProfiles = nextProfiles,
                issues = migratedIssueAssignments
            ).withDerivedMetrics()
            stateStore.save(nextState)
        }
    }

    private suspend fun resumeRunningCompanyRuntimes(companyId: String? = null) {
        val runningCompanyIds = stateStore.load().companyRuntimes
            .filter { it.status == CompanyRuntimeStatus.RUNNING }
            .map { it.companyId }
            .filterNotNull()
            .filter { companyId == null || it == companyId }
        runningCompanyIds.forEach { runningCompanyId ->
            if (companyRuntimeJobs[runningCompanyId]?.isActive == true) {
                return@forEach
            }
            runCatching { startCompanyBackend(runningCompanyId) }
            ensureCompanyRuntimeLoop(runningCompanyId)
            runCatching { runCompanyRuntimeTick(runningCompanyId) }
        }
    }

    private suspend fun ensureAutonomousCompanyRuntimes(companyId: String? = null) {
        val state = stateStore.load()
        val companiesNeedingRuntime = state.goals
            .filter { it.autonomyEnabled && it.status == GoalStatus.ACTIVE }
            .filter { companyId == null || it.companyId == companyId }
            .mapNotNull { it.companyId }
            .distinct()
            .filter { activeCompanyId ->
                state.companyRuntimes.firstOrNull { it.companyId == activeCompanyId }?.status != CompanyRuntimeStatus.RUNNING
            }
        companiesNeedingRuntime.forEach { activeCompanyId ->
            startCompanyRuntime(activeCompanyId)
        }
    }

    private suspend fun reconcileStaleAgentRuns(companyId: String? = null) {
        val affectedTaskIds = mutableSetOf<String>()
        stateMutex.withLock {
            val state = stateStore.load()
            val now = System.currentTimeMillis()
            val relevantIssueIds = state.issues
                .filter { companyId == null || it.companyId == companyId }
                .map { it.id }
                .toSet()
            val staleRuns = state.runs.filter { run ->
                run.status == AgentRunStatus.RUNNING &&
                    isRunStale(run, now) &&
                    (companyId == null || state.tasks.firstOrNull { it.id == run.taskId }?.issueId in relevantIssueIds)
            }
            if (staleRuns.isEmpty()) {
                return@withLock
            }

            val staleRunIds = staleRuns.map { it.id }.toSet()
            val updatedRuns = state.runs.map { run ->
                if (run.id in staleRunIds) {
                    run.copy(
                        status = AgentRunStatus.FAILED,
                        error = run.error ?: "Agent process exited before Cotor recorded a final result",
                        updatedAt = now
                    )
                } else {
                    run
                }
            }
            val runsByTask = updatedRuns.groupBy { it.taskId }
            val updatedTasks = state.tasks.map { task ->
                val taskRuns = runsByTask[task.id].orEmpty()
                if (task.status != DesktopTaskStatus.RUNNING && task.status != DesktopTaskStatus.QUEUED) {
                    task
                } else if (taskRuns.any { it.status == AgentRunStatus.RUNNING || it.status == AgentRunStatus.QUEUED }) {
                    task
                } else {
                    val nextStatus = finalTaskStatus(taskRuns)
                    if (nextStatus != task.status) {
                        affectedTaskIds += task.id
                        task.copy(status = nextStatus, updatedAt = now)
                    } else {
                        task
                    }
                }
            }
            val nextState = state.copy(
                runs = updatedRuns,
                tasks = updatedTasks
            ).withDerivedMetrics()
            stateStore.save(nextState)
        }
        affectedTaskIds.forEach { taskId ->
            getTask(taskId)?.let { task ->
                syncIssueFromTask(task.id, task.status)
            }
        }
    }

    private suspend fun stimulateAutonomousCompanyProgress(companyId: String? = null) {
        val snapshot = stateStore.load()
        val now = System.currentTimeMillis()
        val targetCompanyIds = snapshot.goals
            .filter { it.autonomyEnabled && it.status == GoalStatus.ACTIVE }
            .mapNotNull { it.companyId }
            .filter { companyId == null || it == companyId }
            .distinct()

        targetCompanyIds.forEach { activeCompanyId ->
            val runtime = snapshot.companyRuntimes.firstOrNull { it.companyId == activeCompanyId }
            val hasPendingIssues = snapshot.issues.any { issue ->
                issue.companyId == activeCompanyId &&
                    issue.status != IssueStatus.DONE &&
                    issue.status != IssueStatus.CANCELED
            }
            val hasActiveTasks = snapshot.tasks.any { task ->
                (task.status == DesktopTaskStatus.RUNNING || task.status == DesktopTaskStatus.QUEUED) &&
                    snapshot.issues.firstOrNull { it.id == task.issueId }?.companyId == activeCompanyId
            }
            val tickIsStale = runtime?.lastTickAt?.let { now - it > 5_000 } ?: true
            val loopMissing = companyRuntimeJobs[activeCompanyId]?.isActive != true

            if (hasPendingIssues && !hasActiveTasks && tickIsStale) {
                if (runtime?.status != CompanyRuntimeStatus.RUNNING) {
                    startCompanyRuntime(activeCompanyId)
                } else if (loopMissing) {
                    ensureCompanyRuntimeLoop(activeCompanyId)
                }
                runCatching { runCompanyRuntimeTick(activeCompanyId) }
            }
        }
    }

    private fun finalTaskStatus(runs: List<AgentRun>): DesktopTaskStatus = when {
        runs.isEmpty() -> DesktopTaskStatus.FAILED
        runs.all { it.status == AgentRunStatus.COMPLETED } -> DesktopTaskStatus.COMPLETED
        runs.any { it.status == AgentRunStatus.COMPLETED } &&
            runs.any { it.status == AgentRunStatus.FAILED } -> DesktopTaskStatus.PARTIAL
        else -> DesktopTaskStatus.FAILED
    }

    private fun isProcessAlive(processId: Long?): Boolean {
        if (processId == null) {
            return false
        }
        return runCatching {
            ProcessHandle.of(processId).map { it.isAlive }.orElse(false)
        }.getOrDefault(false)
    }

    private fun isRunStale(run: AgentRun, now: Long): Boolean {
        return when {
            run.processId != null -> !isProcessAlive(run.processId)
            else -> now - run.updatedAt > staleRunStartupGraceMs
        }
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
        val loadedState = stateStore.load()
        val initialBackendKind = initialCompanyBackendKind(loadedState)
        val requestedRoot = Path.of(rootPath).toAbsolutePath().normalize()
        val repositoryRoot = runCatching {
            gitWorkspaceService.ensureInitializedRepositoryRoot(
                requestedRoot,
                defaultBaseBranch
            )
        }.getOrElse { error ->
            if (loadedState.repositories.any { sameRepositoryRoot(it.localPath, requestedRoot) }) {
                requestedRoot
            } else {
                throw error
            }
        }
        val repository = upsertRepositoryForPath(loadedState.repositories, repositoryRoot, now)
        val resolvedBranch = defaultBaseBranch?.takeIf { it.isNotBlank() } ?: repository.defaultBranch
        val existing = loadedState.companies.firstOrNull {
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
            val refreshedState = loadedState.copy(
                repositories = mergeRepository(loadedState.repositories, repository),
                companies = loadedState.companies.map { if (it.id == existing.id) refreshed else it }
            )
            val workspaceState = ensureCompanyWorkspace(refreshedState, refreshed, now).first
            val nextState = workspaceState.recordCompanyActivity(
                companyId = refreshed.id,
                source = "company",
                title = "Updated company",
                detail = refreshed.name
            ).withDerivedMetrics()
            stateStore.save(nextState)
            publishCompanyEvent(
                companyId = refreshed.id,
                type = "company.updated",
                title = "Updated company",
                detail = refreshed.name
            )
            return@withLock refreshed
        }

        val companyId = UUID.randomUUID().toString()
        val company = Company(
            id = companyId,
            name = name.trim().ifEmpty { repositoryRoot.fileName.toString() },
            rootPath = repositoryRoot.toString(),
            repositoryId = repository.id,
            defaultBaseBranch = resolvedBranch,
            backendKind = initialBackendKind,
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
        val baseState = loadedState.copy(
            repositories = mergeRepository(loadedState.repositories, repository),
            companies = loadedState.companies + company,
            projectContexts = loadedState.projectContexts + context,
            companyAgentDefinitions = loadedState.companyAgentDefinitions + seededDefinitions,
            companyRuntimes = loadedState.companyRuntimes + CompanyRuntimeSnapshot(companyId = companyId, backendKind = company.backendKind)
        )
        val workspaceState = ensureCompanyWorkspace(baseState, company, now).first
        val nextState = workspaceState.recordCompanyActivity(
            companyId = companyId,
            projectContextId = context.id,
            source = "company",
            title = "Created company",
            detail = company.name
        ).withDerivedMetrics()
        stateStore.save(nextState)
        writeCompanyContextSnapshot(nextState, company, context)
        publishCompanyEvent(
            companyId = company.id,
            type = "company.created",
            title = "Created company",
            detail = company.name
        )
        company
    }

    private fun initialCompanyBackendKind(state: DesktopAppState): ExecutionBackendKind {
        val preferred = state.backendSettings.defaultBackendKind
        if (preferred != ExecutionBackendKind.CODEX_APP_SERVER) {
            return preferred
        }
        val codexConfig = state.backendSettings.backends.firstOrNull { it.kind == ExecutionBackendKind.CODEX_APP_SERVER }
        return if (codexConfig == null || !codexConfig.enabled || !codexAppServerManager.executableAvailable(codexConfig)) {
            ExecutionBackendKind.LOCAL_COTOR
        } else {
            preferred
        }
    }

    suspend fun updateCompany(
        companyId: String,
        name: String? = null,
        defaultBaseBranch: String? = null,
        autonomyEnabled: Boolean? = null,
        backendKind: ExecutionBackendKind? = null
    ): Company = stateMutex.withLock {
        val state = stateStore.load()
        val company = state.companies.firstOrNull { it.id == companyId }
            ?: throw IllegalArgumentException("Company not found: $companyId")
        val updated = company.copy(
            name = name?.trim()?.takeIf { it.isNotBlank() } ?: company.name,
            defaultBaseBranch = defaultBaseBranch?.trim()?.takeIf { it.isNotBlank() } ?: company.defaultBaseBranch,
            backendKind = backendKind ?: company.backendKind,
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
        nextState.projectContexts.firstOrNull { it.companyId == companyId }?.let { context ->
            writeCompanyContextSnapshot(nextState, updated, context)
        }
        publishCompanyEvent(
            companyId = updated.id,
            type = "company.updated",
            title = "Updated company",
            detail = updated.name
        )
        updated
    }

    suspend fun updateBackendSettings(
        defaultBackendKind: ExecutionBackendKind,
        codexLaunchMode: BackendLaunchMode? = null,
        codexCommand: String? = null,
        codexArgs: List<String>? = null,
        codexWorkingDirectory: String? = null,
        codexPort: Int? = null,
        codexStartupTimeoutSeconds: Int? = null,
        codexAppServerBaseUrl: String? = null,
        codexAuthMode: String? = null,
        codexToken: String? = null,
        codexTimeoutSeconds: Int? = null
    ): DesktopSettings = stateMutex.withLock {
        val state = stateStore.load()
        val updatedConfigs = state.backendSettings.backends.map { config ->
            if (config.kind == ExecutionBackendKind.CODEX_APP_SERVER) {
                config.copy(
                    launchMode = codexLaunchMode ?: config.launchMode,
                    command = codexCommand?.trim()?.takeIf { it.isNotBlank() } ?: config.command,
                    args = codexArgs ?: config.args,
                    workingDirectory = codexWorkingDirectory?.trim()?.takeIf { it.isNotBlank() } ?: config.workingDirectory,
                    port = codexPort ?: config.port,
                    startupTimeoutSeconds = codexStartupTimeoutSeconds ?: config.startupTimeoutSeconds,
                    baseUrl = codexAppServerBaseUrl?.trim()?.takeIf { it.isNotBlank() } ?: config.baseUrl,
                    authMode = codexAuthMode?.trim()?.takeIf { it.isNotBlank() } ?: config.authMode,
                    token = codexToken ?: config.token,
                    timeoutSeconds = codexTimeoutSeconds ?: config.timeoutSeconds
                )
            } else {
                config
            }
        }
        stateStore.save(
            state.copy(
                backendSettings = state.backendSettings.copy(
                    defaultBackendKind = defaultBackendKind,
                    backends = updatedConfigs
                )
            )
        )
        settings()
    }

    suspend fun updateCompanyBackend(
        companyId: String,
        backendKind: ExecutionBackendKind,
        launchMode: BackendLaunchMode? = null,
        command: String? = null,
        args: List<String>? = null,
        workingDirectory: String? = null,
        port: Int? = null,
        startupTimeoutSeconds: Int? = null,
        baseUrl: String? = null,
        authMode: String? = null,
        token: String? = null,
        timeoutSeconds: Int? = null,
        useGlobalDefault: Boolean = false
    ): Company = stateMutex.withLock {
        val state = stateStore.load()
        val company = state.companies.firstOrNull { it.id == companyId }
            ?: throw IllegalArgumentException("Company not found: $companyId")
        val override = if (useGlobalDefault) {
            null
        } else {
            val defaultConfig = effectiveBackendConfig(company, state)
            BackendConnectionConfig(
                kind = backendKind,
                launchMode = launchMode ?: defaultConfig.launchMode,
                command = command?.trim()?.takeIf { it.isNotBlank() } ?: defaultConfig.command,
                args = args ?: defaultConfig.args,
                workingDirectory = workingDirectory?.trim()?.takeIf { it.isNotBlank() } ?: defaultConfig.workingDirectory,
                port = port ?: defaultConfig.port,
                startupTimeoutSeconds = startupTimeoutSeconds ?: defaultConfig.startupTimeoutSeconds,
                baseUrl = baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: defaultConfig.baseUrl,
                healthCheckPath = defaultConfig.healthCheckPath,
                authMode = authMode?.trim()?.takeIf { it.isNotBlank() } ?: defaultConfig.authMode,
                token = token ?: defaultConfig.token,
                timeoutSeconds = timeoutSeconds ?: defaultConfig.timeoutSeconds,
                enabled = true
            )
        }
        val updated = company.copy(
            backendKind = backendKind,
            backendConfigOverride = override,
            updatedAt = System.currentTimeMillis()
        )
        val nextState = state.copy(
            companies = state.companies.map { if (it.id == companyId) updated else it }
        ).recordCompanyActivity(
            companyId = companyId,
            source = "company",
            title = "Updated execution backend",
            detail = backendKind.name
        ).withDerivedMetrics()
        stateStore.save(nextState)
        publishCompanyEvent(
            companyId = companyId,
            type = "backend.updated",
            title = "Updated execution backend",
            detail = backendKind.name
        )
        updated
    }

    suspend fun updateCompanyLinear(
        companyId: String,
        enabled: Boolean,
        endpoint: String? = null,
        apiToken: String? = null,
        teamId: String? = null,
        projectId: String? = null,
        stateMappings: List<LinearStateMapping>? = null,
        useGlobalDefault: Boolean = false
    ): Company = stateMutex.withLock {
        val state = stateStore.load()
        val company = state.companies.firstOrNull { it.id == companyId }
            ?: throw IllegalArgumentException("Company not found: $companyId")
        val effectiveConfig = effectiveLinearConfig(company, state)
        val override = if (useGlobalDefault) {
            null
        } else {
            effectiveConfig.copy(
                endpoint = endpoint?.trim()?.takeIf { it.isNotBlank() } ?: effectiveConfig.endpoint,
                apiToken = apiToken ?: effectiveConfig.apiToken,
                teamId = teamId?.trim()?.takeIf { it.isNotBlank() } ?: effectiveConfig.teamId,
                projectId = projectId?.trim()?.takeIf { it.isNotBlank() } ?: effectiveConfig.projectId,
                stateMappings = stateMappings ?: effectiveConfig.stateMappings
            )
        }
        val updated = company.copy(
            linearSyncEnabled = enabled,
            linearConfigOverride = override,
            updatedAt = System.currentTimeMillis()
        )
        val nextState = state.copy(
            companies = state.companies.map { if (it.id == companyId) updated else it }
        ).recordCompanyActivity(
            companyId = companyId,
            source = "linear-sync",
            title = if (enabled) "Enabled Linear sync" else "Disabled Linear sync",
            detail = updated.linearConfigOverride?.projectId ?: updated.linearConfigOverride?.teamId
        ).withDerivedMetrics()
        stateStore.save(nextState)
        publishCompanyEvent(
            companyId = companyId,
            type = if (enabled) "linear.enabled" else "linear.disabled",
            title = if (enabled) "Enabled Linear sync" else "Disabled Linear sync"
        )
        updated
    }

    suspend fun syncCompanyLinear(companyId: String): LinearSyncResponse {
        val snapshot = stateStore.load()
        val company = snapshot.companies.firstOrNull { it.id == companyId }
            ?: throw IllegalArgumentException("Company not found: $companyId")
        if (!company.linearSyncEnabled) {
            return LinearSyncResponse(ok = false, message = "Linear sync is disabled for ${company.name}")
        }
        val relevantIssues = snapshot.issues.filter { it.companyId == companyId }.sortedBy { it.createdAt }
        var synced = 0
        var created = 0
        var commented = 0
        val failedIssues = mutableListOf<String>()
        relevantIssues.forEach { issue ->
            val result = mirrorIssueToLinear(issue.id)
            if (result.isSuccess) {
                val outcome = result.getOrNull()
                synced += 1
                if (outcome?.created == true) created += 1
                if (outcome?.commented == true) commented += 1
            } else {
                failedIssues += issue.title
            }
        }
        val ok = failedIssues.isEmpty()
        val response = LinearSyncResponse(
            ok = ok,
            message = if (ok) "Synced ${relevantIssues.size} issues to Linear" else "Synced with ${failedIssues.size} failures",
            syncedIssues = synced,
            createdIssues = created,
            commentedIssues = commented,
            failedIssues = failedIssues
        )
        publishCompanyEvent(
            companyId = companyId,
            type = if (ok) "linear.synced" else "linear.partial",
            title = "Linear sync completed",
            detail = response.message
        )
        return response
    }

    suspend fun backendStatuses(): List<ExecutionBackendStatus> =
        computeBackendStatuses(stateStore.load())

    suspend fun companyBackendStatus(companyId: String): ExecutionBackendStatus =
        companyBackendStatus(companyId, stateStore.load())

    suspend fun startCompanyBackend(companyId: String): ExecutionBackendStatus {
        val state = stateStore.load()
        val company = state.companies.firstOrNull { it.id == companyId }
            ?: throw IllegalArgumentException("Company not found: $companyId")
        val config = effectiveBackendConfig(company, state)
        val status = if (config.kind == ExecutionBackendKind.CODEX_APP_SERVER && config.launchMode == BackendLaunchMode.MANAGED) {
            publishCompanyEvent(companyId, "backend.starting", "Starting backend")
            codexAppServerManager.ensureStarted(companyId, config)
        } else {
            codexAppServerManager.status(companyId, config)
        }
        publishCompanyEvent(
            companyId = companyId,
            type = if (status.lifecycleState == BackendLifecycleState.RUNNING || status.lifecycleState == BackendLifecycleState.ATTACHED) "backend.started" else "backend.failed",
            title = if (status.lifecycleState == BackendLifecycleState.RUNNING || status.lifecycleState == BackendLifecycleState.ATTACHED) "Backend ready" else "Backend unavailable",
            detail = status.lastError
        )
        return companyBackendStatus(companyId)
    }

    suspend fun stopCompanyBackend(companyId: String): ExecutionBackendStatus {
        codexAppServerManager.stop(companyId)
        publishCompanyEvent(companyId, "backend.stopped", "Stopped backend")
        return companyBackendStatus(companyId)
    }

    suspend fun restartCompanyBackend(companyId: String): ExecutionBackendStatus {
        val state = stateStore.load()
        val company = state.companies.firstOrNull { it.id == companyId }
            ?: throw IllegalArgumentException("Company not found: $companyId")
        val config = effectiveBackendConfig(company, state)
        publishCompanyEvent(companyId, "backend.restarting", "Restarting backend")
        if (config.kind == ExecutionBackendKind.CODEX_APP_SERVER && config.launchMode == BackendLaunchMode.MANAGED) {
            codexAppServerManager.restart(companyId, config)
        }
        return companyBackendStatus(companyId)
    }

    suspend fun testBackend(
        kind: ExecutionBackendKind,
        launchMode: BackendLaunchMode? = null,
        command: String? = null,
        args: List<String>? = null,
        workingDirectory: String? = null,
        port: Int? = null,
        startupTimeoutSeconds: Int? = null,
        baseUrl: String? = null,
        authMode: String? = null,
        token: String? = null,
        timeoutSeconds: Int? = null
    ): ExecutionBackendStatus {
        val state = stateStore.load()
        val defaultConfig = state.backendSettings.backends.firstOrNull { it.kind == kind }
            ?: BackendConnectionConfig(kind = kind)
        val config = defaultConfig.copy(
            launchMode = launchMode ?: defaultConfig.launchMode,
            command = command?.trim()?.takeIf { it.isNotBlank() } ?: defaultConfig.command,
            args = args ?: defaultConfig.args,
            workingDirectory = workingDirectory?.trim()?.takeIf { it.isNotBlank() } ?: defaultConfig.workingDirectory,
            port = port ?: defaultConfig.port,
            startupTimeoutSeconds = startupTimeoutSeconds ?: defaultConfig.startupTimeoutSeconds,
            baseUrl = baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: defaultConfig.baseUrl,
            authMode = authMode?.trim()?.takeIf { it.isNotBlank() } ?: defaultConfig.authMode,
            token = token ?: defaultConfig.token,
            timeoutSeconds = timeoutSeconds ?: defaultConfig.timeoutSeconds
        )
        if (kind == ExecutionBackendKind.CODEX_APP_SERVER && config.launchMode == BackendLaunchMode.MANAGED) {
            val status = codexAppServerManager.ensureStarted("__backend-test__", config)
            val effectiveConfig = config.copy(baseUrl = status.baseUrl ?: config.baseUrl, port = status.port ?: config.port)
            val health = executionBackendFor(kind).health(effectiveConfig)
            codexAppServerManager.stop("__backend-test__")
            return health.copy(
                lifecycleState = status.lifecycleState,
                managed = status.managed,
                pid = status.pid,
                port = status.port,
                lastError = status.lastError ?: health.lastError,
                config = effectiveConfig
            )
        }
        return executionBackendFor(kind).health(config)
    }

    suspend fun deleteCompany(companyId: String): Company = stateMutex.withLock {
        companyRuntimeJobs.remove(companyId)?.cancel()
        companyRuntimeTickMutexes.remove(companyId)
        codexAppServerManager.stop(companyId)

        val state = stateStore.load()
        val company = state.companies.firstOrNull { it.id == companyId }
            ?: throw IllegalArgumentException("Company not found: $companyId")
        val deletedGoalIds = state.goals.filter { it.companyId == companyId }.map { it.id }.toSet()
        val deletedIssueIds = state.issues.filter { it.companyId == companyId }.map { it.id }.toSet()
        val deletedTaskIds = state.tasks.filter { it.issueId in deletedIssueIds }.map { it.id }.toSet()
        val deletedRunIds = state.runs.filter { it.taskId in deletedTaskIds }.map { it.id }.toSet()
        val deletedWorkspaceIds = state.issues
            .filter { it.companyId == companyId }
            .map { it.workspaceId }
            .toSet()

        val nextTasks = state.tasks.filterNot { it.id in deletedTaskIds }
        val nextRuns = state.runs.filterNot { it.id in deletedRunIds }
        val nextIssues = state.issues.filterNot { it.id in deletedIssueIds }
        val activeWorkspaceIds = (nextIssues.map { it.workspaceId } + nextTasks.map { it.workspaceId }).toSet()

        val nextState = state.copy(
            companies = state.companies.filterNot { it.id == companyId },
            companyAgentDefinitions = state.companyAgentDefinitions.filterNot { it.companyId == companyId },
            projectContexts = state.projectContexts.filterNot { it.companyId == companyId },
            workspaces = state.workspaces.filterNot { it.id in deletedWorkspaceIds && it.id !in activeWorkspaceIds },
            tasks = nextTasks,
            runs = nextRuns,
            goals = state.goals.filterNot { it.id in deletedGoalIds },
            issues = nextIssues,
            issueDependencies = state.issueDependencies.filterNot {
                it.issueId in deletedIssueIds || it.dependsOnIssueId in deletedIssueIds
            },
            orgProfiles = state.orgProfiles.filterNot { it.companyId == companyId },
            reviewQueue = state.reviewQueue.filterNot {
                it.companyId == companyId || it.issueId in deletedIssueIds || it.runId in deletedRunIds
            },
            companyActivity = state.companyActivity.filterNot { it.companyId == companyId },
            signals = state.signals.filterNot {
                it.companyId == companyId || it.goalId in deletedGoalIds || it.issueId in deletedIssueIds
            },
            companyRuntimes = state.companyRuntimes.filterNot { it.companyId == companyId },
            runtime = if (state.runtime.companyId == companyId) CompanyRuntimeSnapshot() else state.runtime
        ).withDerivedMetrics()
        stateStore.save(nextState)
        deleteDirectoryRecursively(companyContextRoot(company))
        company
    }

    suspend fun createCompanyAgentDefinition(
        companyId: String,
        title: String,
        agentCli: String,
        roleSummary: String,
        specialties: List<String> = emptyList(),
        collaborationInstructions: String? = null,
        preferredCollaboratorIds: List<String> = emptyList(),
        memoryNotes: String? = null,
        enabled: Boolean = true
    ): CompanyAgentDefinition = stateMutex.withLock {
        val state = stateStore.load()
        require(state.companies.any { it.id == companyId }) { "Company not found: $companyId" }
        val now = System.currentTimeMillis()
        val normalizedCollaborators = preferredCollaboratorIds
            .map { it.trim() }
            .filter { collaboratorId ->
                collaboratorId.isNotBlank() && state.companyAgentDefinitions.any { it.companyId == companyId && it.id == collaboratorId }
            }
            .distinct()
        val definition = CompanyAgentDefinition(
            id = UUID.randomUUID().toString(),
            companyId = companyId,
            title = title.trim().ifEmpty { "Agent" },
            agentCli = agentCli.trim().ifEmpty { "echo" },
            roleSummary = roleSummary.trim().ifEmpty { "general execution" },
            specialties = specialties.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            collaborationInstructions = collaborationInstructions?.trim()?.takeIf { it.isNotBlank() },
            preferredCollaboratorIds = normalizedCollaborators,
            memoryNotes = memoryNotes?.trim()?.takeIf { it.isNotBlank() },
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
        nextState.companies.firstOrNull { it.id == companyId }?.let { company ->
            nextState.projectContexts.firstOrNull { it.companyId == companyId }?.let { context ->
                writeCompanyContextSnapshot(nextState, company, context)
            }
        }
        definition
    }

    suspend fun updateCompanyAgentDefinition(
        companyId: String,
        agentId: String,
        title: String? = null,
        agentCli: String? = null,
        roleSummary: String? = null,
        specialties: List<String>? = null,
        collaborationInstructions: String? = null,
        preferredCollaboratorIds: List<String>? = null,
        memoryNotes: String? = null,
        enabled: Boolean? = null,
        displayOrder: Int? = null
    ): CompanyAgentDefinition = stateMutex.withLock {
        val state = stateStore.load()
        val current = state.companyAgentDefinitions.firstOrNull { it.companyId == companyId && it.id == agentId }
            ?: throw IllegalArgumentException("Company agent not found: $agentId")
        val normalizedCollaborators = preferredCollaboratorIds
            ?.map { it.trim() }
            ?.filter { collaboratorId ->
                collaboratorId.isNotBlank() &&
                    collaboratorId != agentId &&
                    state.companyAgentDefinitions.any { it.companyId == companyId && it.id == collaboratorId }
            }
            ?.distinct()
        val updated = current.copy(
            title = title?.trim()?.takeIf { it.isNotBlank() } ?: current.title,
            agentCli = agentCli?.trim()?.takeIf { it.isNotBlank() } ?: current.agentCli,
            roleSummary = roleSummary?.trim()?.takeIf { it.isNotBlank() } ?: current.roleSummary,
            specialties = specialties?.map { it.trim() }?.filter { it.isNotBlank() }?.distinct() ?: current.specialties,
            collaborationInstructions = when {
                collaborationInstructions == null -> current.collaborationInstructions
                collaborationInstructions.isBlank() -> null
                else -> collaborationInstructions.trim()
            },
            preferredCollaboratorIds = normalizedCollaborators ?: current.preferredCollaboratorIds,
            memoryNotes = when {
                memoryNotes == null -> current.memoryNotes
                memoryNotes.isBlank() -> null
                else -> memoryNotes.trim()
            },
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
        nextState.companies.firstOrNull { it.id == companyId }?.let { company ->
            nextState.projectContexts.firstOrNull { it.companyId == companyId }?.let { context ->
                writeCompanyContextSnapshot(nextState, company, context)
            }
        }
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
        autonomyEnabled: Boolean = true,
        priority: Int = 2,
        operatingPolicy: String? = null,
        startRuntimeIfNeeded: Boolean = true
    ): CompanyGoal {
        val goal = stateMutex.withLock {
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
                priority = priority,
                successMetrics = successMetrics.filter { it.isNotBlank() },
                operatingPolicy = operatingPolicy?.trim()?.takeIf { it.isNotBlank() },
                autonomyEnabled = autonomyEnabled,
                createdAt = now,
                updatedAt = now
            )
            val decomposition = decomposeGoal(goal, workspace, profiles, companyDefinitions, now)
            val decision = GoalOrchestrationDecision(
                id = UUID.randomUUID().toString(),
                companyId = company.id,
                goalId = goal.id,
                title = "Created execution graph",
                summary = "CEO decomposed \"${goal.title}\" into ${decomposition.first.size} issues across ${companyDefinitions.count { it.companyId == company.id && it.enabled }} agents.",
                createdIssues = decomposition.first.map { it.id },
                assignments = decomposition.first.mapNotNull { issue ->
                    val assignee = profiles.firstOrNull { it.id == issue.assigneeProfileId }?.roleName
                    assignee?.let { "${issue.title} -> $it" }
                },
                escalations = emptyList(),
                createdAt = now
            )
            val nextState = workspaceState.copy(
                companies = nextCompanies,
                companyAgentDefinitions = companyDefinitions,
                projectContexts = nextProjectContexts,
                goals = workspaceState.goals + goal,
                issues = workspaceState.issues + decomposition.first,
                issueDependencies = workspaceState.issueDependencies + decomposition.second,
                orgProfiles = profiles,
                goalDecisions = workspaceState.goalDecisions + decision,
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
            publishCompanyEvent(
                companyId = company.id,
                type = "goal.created",
                title = "Created goal",
                detail = goal.title,
                goalId = goal.id
            )
            publishCompanyEvent(
                companyId = company.id,
                type = "goal.decomposed",
                title = "Created execution graph",
                detail = "${decomposition.first.size} issues planned",
                goalId = goal.id
            )
            goal
        }
        queueGoalPostCreateWork(goal, startRuntimeIfNeeded)
        return goal
    }

    suspend fun createGoal(
        title: String,
        description: String,
        successMetrics: List<String> = emptyList(),
        autonomyEnabled: Boolean = true
    ): CompanyGoal =
        createGoal(
            companyId = null,
            title = title,
            description = description,
            successMetrics = successMetrics,
            autonomyEnabled = autonomyEnabled
        )

    suspend fun updateGoal(
        goalId: String,
        title: String? = null,
        description: String? = null,
        successMetrics: List<String>? = null,
        autonomyEnabled: Boolean? = null
    ): CompanyGoal {
        val updated = stateMutex.withLock {
            val state = stateStore.load()
            val current = state.goals.firstOrNull { it.id == goalId }
                ?: throw IllegalArgumentException("Goal not found: $goalId")
            val updated = current.copy(
                title = title?.trim()?.takeIf { it.isNotBlank() } ?: current.title,
                description = description?.trim()?.takeIf { it.isNotBlank() } ?: current.description,
                successMetrics = successMetrics ?: current.successMetrics,
                autonomyEnabled = autonomyEnabled ?: current.autonomyEnabled,
                updatedAt = System.currentTimeMillis()
            )
            val nextState = state.copy(
                goals = state.goals.map { if (it.id == goalId) updated else it }
            ).recordCompanyActivity(
                companyId = updated.companyId,
                projectContextId = updated.projectContextId,
                goalId = updated.id,
                source = "goal",
                title = "Updated goal",
                detail = updated.title
            ).withDerivedMetrics()
            stateStore.save(nextState)
            val company = nextState.companies.firstOrNull { it.id == updated.companyId }
            val context = nextState.projectContexts.firstOrNull { it.id == updated.projectContextId }
            if (company != null && context != null) {
                writeCompanyContextSnapshot(nextState, company, context)
            }
            updated
        }
        queueGoalPostUpdateWork(updated)
        return updated
    }

    private fun queueGoalPostCreateWork(goal: CompanyGoal, startRuntimeIfNeeded: Boolean) {
        serviceScope.launch {
            runCatching { mirrorGoalIssuesToLinear(goal.id) }
            if (goal.autonomyEnabled && startRuntimeIfNeeded) {
                runCatching { startCompanyRuntime(goal.companyId) }
            }
        }
    }

    private fun queueGoalPostUpdateWork(goal: CompanyGoal) {
        if (!goal.autonomyEnabled || goal.status != GoalStatus.ACTIVE) {
            return
        }
        serviceScope.launch {
            runCatching { startCompanyRuntime(goal.companyId) }
        }
    }

    suspend fun deleteGoal(goalId: String): CompanyGoal = stateMutex.withLock {
        val state = stateStore.load()
        val goal = state.goals.firstOrNull { it.id == goalId }
            ?: throw IllegalArgumentException("Goal not found: $goalId")
        val deletedIssueIds = state.issues.filter { it.goalId == goalId }.map { it.id }.toSet()
        val deletedTaskIds = state.tasks.filter { it.issueId in deletedIssueIds }.map { it.id }.toSet()
        val deletedRunIds = state.runs.filter { it.taskId in deletedTaskIds }.map { it.id }.toSet()
        val nextState = state.copy(
            goals = state.goals.filterNot { it.id == goalId },
            issues = state.issues.filterNot { it.id in deletedIssueIds },
            issueDependencies = state.issueDependencies.filterNot {
                it.issueId in deletedIssueIds || it.dependsOnIssueId in deletedIssueIds
            },
            tasks = state.tasks.filterNot { it.id in deletedTaskIds },
            runs = state.runs.filterNot { it.id in deletedRunIds },
            reviewQueue = state.reviewQueue.filterNot { it.issueId in deletedIssueIds || it.runId in deletedRunIds },
            companyActivity = state.companyActivity.filterNot { it.goalId == goalId || it.issueId in deletedIssueIds },
            signals = state.signals.filterNot { it.goalId == goalId || it.issueId in deletedIssueIds }
        ).recordCompanyActivity(
            companyId = goal.companyId,
            projectContextId = goal.projectContextId,
            source = "goal",
            title = "Deleted goal",
            detail = goal.title,
            severity = "warning"
        ).withDerivedMetrics()
        stateStore.save(nextState)
        state.companies.firstOrNull { it.id == goal.companyId }?.let { company ->
            deleteGoalContextEntries(company, setOf(goalId))
            deleteIssueContextEntries(company, deletedIssueIds)
            nextState.projectContexts.firstOrNull { it.id == goal.projectContextId }?.let { context ->
                writeCompanyContextSnapshot(nextState, company, context)
            }
        }
        goal
    }

    suspend fun deleteIssue(issueId: String): CompanyIssue = stateMutex.withLock {
        val state = stateStore.load()
        val issue = state.issues.firstOrNull { it.id == issueId }
            ?: throw IllegalArgumentException("Issue not found: $issueId")
        val deletedTaskIds = state.tasks.filter { it.issueId == issueId }.map { it.id }.toSet()
        val deletedRunIds = state.runs.filter { it.taskId in deletedTaskIds }.map { it.id }.toSet()
        val nextState = state.copy(
            issues = state.issues.filterNot { it.id == issueId },
            issueDependencies = state.issueDependencies.filterNot {
                it.issueId == issueId || it.dependsOnIssueId == issueId
            },
            tasks = state.tasks.filterNot { it.id in deletedTaskIds },
            runs = state.runs.filterNot { it.id in deletedRunIds },
            reviewQueue = state.reviewQueue.filterNot { it.issueId == issueId || it.runId in deletedRunIds },
            companyActivity = state.companyActivity.filterNot { it.issueId == issueId },
            signals = state.signals.filterNot { it.issueId == issueId }
        ).recordCompanyActivity(
            companyId = issue.companyId,
            projectContextId = issue.projectContextId,
            goalId = issue.goalId,
            source = "issue",
            title = "Deleted issue",
            detail = issue.title,
            severity = "warning"
        ).withDerivedMetrics()
        stateStore.save(nextState)
        state.companies.firstOrNull { it.id == issue.companyId }?.let { company ->
            deleteIssueContextEntries(company, setOf(issueId))
            nextState.projectContexts.firstOrNull { it.id == issue.projectContextId }?.let { context ->
                writeCompanyContextSnapshot(nextState, company, context)
            }
        }
        issue
    }

    suspend fun createIssue(
        companyId: String,
        goalId: String,
        title: String,
        description: String,
        priority: Int = 3,
        kind: String = "manual"
    ): CompanyIssue = stateMutex.withLock {
        val state = stateStore.load()
        val now = System.currentTimeMillis()
        val company = state.companies.firstOrNull { it.id == companyId }
            ?: throw IllegalArgumentException("Company not found: $companyId")
        val goal = state.goals.firstOrNull { it.id == goalId && it.companyId == companyId }
            ?: throw IllegalArgumentException("Goal not found for company: $goalId")
        val workspaceResolution = ensureCompanyWorkspace(state, company, now)
        val workspaceState = workspaceResolution.first
        val workspace = workspaceResolution.second
        val projectContext = ensureProjectContext(workspaceState, company, now)
        val profiles = ensureOrgProfiles(workspaceState.orgProfiles, workspaceState.companyAgentDefinitions, workspaceState.companies)
        val assignee = suggestProfileForCustomIssue(
            title = title,
            description = description,
            kind = kind,
            profiles = profiles.filter { it.companyId == companyId }
        )
        val issue = CompanyIssue(
            id = UUID.randomUUID().toString(),
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = title.trim().ifEmpty { "New Issue" },
            description = description.trim(),
            status = if (assignee == null) IssueStatus.PLANNED else IssueStatus.DELEGATED,
            priority = priority.coerceIn(1, 4),
            kind = kind.trim().ifEmpty { "manual" },
            assigneeProfileId = assignee?.id,
            sourceSignal = "manual",
            createdAt = now,
            updatedAt = now
        )
        val nextState = workspaceState.copy(
            issues = workspaceState.issues + issue,
            orgProfiles = profiles
        ).recordCompanyActivity(
            companyId = company.id,
            projectContextId = projectContext.id,
            goalId = goal.id,
            issueId = issue.id,
            source = "issue",
            title = "Created issue",
            detail = issue.title
        ).withDerivedMetrics()
        stateStore.save(nextState)
        writeCompanyContextSnapshot(nextState, company, projectContext)
        publishCompanyEvent(
            companyId = company.id,
            type = "issue.created",
            title = "Created issue",
            detail = issue.title,
            goalId = goal.id,
            issueId = issue.id
        )
        issue
    }

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
        val decomposition = decomposeGoal(goal, workspace, profiles, workspaceState.companyAgentDefinitions, now)
        val decision = GoalOrchestrationDecision(
            id = UUID.randomUUID().toString(),
            companyId = company.id,
            goalId = goal.id,
            title = "Decomposed goal",
            summary = "CEO expanded \"${goal.title}\" into ${decomposition.first.size} issues.",
            createdIssues = decomposition.first.map { it.id },
            assignments = decomposition.first.mapNotNull { issue ->
                val assignee = profiles.firstOrNull { it.id == issue.assigneeProfileId }?.roleName
                assignee?.let { "${issue.title} -> $it" }
            },
            escalations = emptyList(),
            createdAt = now
        )
        val nextState = workspaceState.copy(
            issues = workspaceState.issues + decomposition.first,
            issueDependencies = workspaceState.issueDependencies + decomposition.second,
            orgProfiles = profiles,
            goalDecisions = workspaceState.goalDecisions + decision
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
        publishCompanyEvent(
            companyId = company.id,
            type = "goal.decomposed",
            title = "Decomposed goal",
            detail = "${decomposition.first.size} issues planned",
            goalId = goal.id
        )
        mirrorGoalIssuesToLinear(goal.id)
        decomposition.first
    }

    suspend fun delegateIssue(issueId: String): CompanyIssue {
        val delegated = stateMutex.withLock {
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
            nextState.companies.firstOrNull { it.id == issue.companyId }?.let { company ->
                nextState.projectContexts.firstOrNull { it.id == issue.projectContextId }?.let { context ->
                    writeCompanyContextSnapshot(nextState, company, context)
                }
            }
            delegated
        }
        mirrorIssueToLinear(delegated.id)
        return delegated
    }

    suspend fun runIssue(issueId: String): CompanyIssue {
        val delegated = delegateIssue(issueId)
        return startDelegatedIssue(delegated)
    }

    private suspend fun startDelegatedIssue(issue: CompanyIssue): CompanyIssue {
        val executableIssue = ensureIssueWorkspace(issue)
        val state = stateStore.load()
        val profile = state.orgProfiles.firstOrNull { it.id == executableIssue.assigneeProfileId }
            ?: ensureOrgProfiles(state.orgProfiles, state.companyAgentDefinitions, state.companies)
                .firstOrNull { it.companyId == executableIssue.companyId }
            ?: ensureOrgProfiles(state.orgProfiles, state.companyAgentDefinitions, state.companies).firstOrNull()
            ?: OrgAgentProfile(
                id = executableIssue.assigneeProfileId ?: "fallback-${executableIssue.companyId}",
                companyId = executableIssue.companyId,
                roleName = "Fallback Executor",
                executionAgentName = preferredExecutableAgent(),
                capabilities = listOf("implementation", "integration"),
                mergeAuthority = true
            )
        val latestTask = state.tasks
            .filter { it.issueId == executableIssue.id }
            .maxByOrNull { it.updatedAt }
        val shouldCreateFreshTask = latestTask == null ||
            latestTask.status == DesktopTaskStatus.FAILED ||
            latestTask.status == DesktopTaskStatus.PARTIAL
        val task = if (shouldCreateFreshTask) {
            createTask(
                workspaceId = executableIssue.workspaceId,
                title = executableIssue.title,
                prompt = buildIssueExecutionPrompt(state, executableIssue, profile),
                agents = listOf(profile.executionAgentName),
                issueId = executableIssue.id
            )
        } else {
            latestTask
        }
        if (task.status == DesktopTaskStatus.QUEUED) {
            runTaskIfPresent(task.id)
        }
        val runningIssue = stateMutex.withLock {
            val latest = stateStore.load()
            val currentIssue = latest.issues.firstOrNull { it.id == executableIssue.id } ?: return@withLock executableIssue
            val runningIssue = currentIssue.copy(
                status = if (currentIssue.status == IssueStatus.DONE || currentIssue.status == IssueStatus.IN_REVIEW) {
                    currentIssue.status
                } else {
                    IssueStatus.IN_PROGRESS
                },
                updatedAt = System.currentTimeMillis()
            )
            val nextState = latest.copy(
                issues = latest.issues.map { if (it.id == issue.id) runningIssue else it }
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
            nextState.companies.firstOrNull { it.id == runningIssue.companyId }?.let { company ->
                nextState.projectContexts.firstOrNull { it.id == runningIssue.projectContextId }?.let { context ->
                    writeCompanyContextSnapshot(nextState, company, context)
                }
            }
            runningIssue
        }
        mirrorIssueToLinear(
            runningIssue.id,
            comment = "Cotor started work on \"${runningIssue.title}\" with agent ${profile.executionAgentName}."
        )
        return runningIssue
    }

    private suspend fun ensureIssueWorkspace(issue: CompanyIssue): CompanyIssue = stateMutex.withLock {
        val state = stateStore.load()
        if (state.workspaces.any { it.id == issue.workspaceId }) {
            return@withLock issue
        }
        val company = state.companies.firstOrNull { it.id == issue.companyId } ?: return@withLock issue
        val now = System.currentTimeMillis()
        val workspaceResolution = ensureCompanyWorkspace(state, company, now)
        val workspaceState = workspaceResolution.first
        val workspace = workspaceResolution.second
        val updatedIssue = issue.copy(workspaceId = workspace.id, updatedAt = now)
        val nextState = workspaceState.copy(
            issues = workspaceState.issues.map { existing ->
                if (existing.id == issue.id) updatedIssue else existing
            }
        )
        stateStore.save(nextState)
        updatedIssue
    }

    suspend fun mergeReviewQueueItem(itemId: String): ReviewQueueItem {
        val mergedItem = stateMutex.withLock {
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
        mergedIssue?.let { issue ->
            state.companies.firstOrNull { it.id == issue.companyId }?.let { company ->
                nextState.projectContexts.firstOrNull { it.id == issue.projectContextId }?.let { context ->
                    writeCompanyContextSnapshot(nextState, company, context)
                }
            }
        }
        mergedItem
        }
        val mergedIssue = stateStore.load().issues.firstOrNull { it.id == mergedItem.issueId }
        if (mergedIssue != null) {
            mirrorIssueToLinear(
                mergedIssue.id,
                comment = "Cotor marked \"${mergedIssue.title}\" as merged and done."
            )
        }
        return mergedItem
    }

    suspend fun startCompanyRuntime(companyId: String): CompanyRuntimeSnapshot {
        val initialState = stateStore.load()
        val initialCompany = initialState.companies.firstOrNull { it.id == companyId }
        val initialConfig = initialCompany?.let { effectiveBackendConfig(it, initialState) }
        val initialBackendStatus = if (initialCompany != null && initialConfig != null && initialConfig.kind == ExecutionBackendKind.CODEX_APP_SERVER) {
            if (initialConfig.launchMode == BackendLaunchMode.MANAGED) {
                publishCompanyEvent(companyId, "backend.starting", "Starting backend")
                codexAppServerManager.ensureStarted(companyId, initialConfig)
            } else {
                codexAppServerManager.status(companyId, initialConfig)
            }
        } else {
            null
        }
        val effectiveCompanyId = stateMutex.withLock {
            val state = stateStore.load()
            val resolvedCompanyId = if (hasCompanyScope(state, companyId)) {
                companyId
            } else {
                resolveLatestCompanyId(state) ?: companyId
            }
            val now = System.currentTimeMillis()
            val nextState = state.copy(
                companyRuntimes = upsertCompanyRuntime(
                    state.companyRuntimes,
                    CompanyRuntimeSnapshot(
                        companyId = resolvedCompanyId,
                        status = CompanyRuntimeStatus.RUNNING,
                        backendKind = state.companies.firstOrNull { it.id == resolvedCompanyId }?.backendKind
                            ?: state.backendSettings.defaultBackendKind,
                        backendHealth = when (initialBackendStatus?.lifecycleState) {
                            BackendLifecycleState.RUNNING, BackendLifecycleState.ATTACHED -> "healthy"
                            BackendLifecycleState.STARTING, BackendLifecycleState.RESTARTING -> "starting"
                            BackendLifecycleState.FAILED -> "offline"
                            BackendLifecycleState.STOPPED, null -> "unknown"
                        },
                        backendMessage = initialBackendStatus?.lastError,
                        backendLifecycleState = initialBackendStatus?.lifecycleState ?: BackendLifecycleState.STOPPED,
                        backendPid = initialBackendStatus?.pid,
                        backendPort = initialBackendStatus?.port,
                        tickIntervalSeconds = companyRuntimeTickIntervalMs / 1000,
                        lastStartedAt = now,
                        lastAction = "runtime-started",
                        lastError = null
                    )
                )
            ).recordSignal(
                source = "runtime",
                message = "Started autonomous company runtime",
                companyId = resolvedCompanyId
            ).recordCompanyActivity(
                companyId = resolvedCompanyId,
                source = "runtime",
                title = "Started runtime"
            ).withDerivedMetrics()
            stateStore.save(nextState)
            resolvedCompanyId
        }
        publishCompanyEvent(
            companyId = effectiveCompanyId,
            type = "runtime.started",
            title = "Started runtime"
        )
        ensureCompanyRuntimeLoop(effectiveCompanyId)
        runCompanyRuntimeTick(effectiveCompanyId)
        return runtimeStatus(effectiveCompanyId)
    }

    suspend fun startCompanyRuntime(): CompanyRuntimeSnapshot {
        val companyId = resolveLatestCompanyId(stateStore.load())
            ?: throw IllegalStateException("Create a company before starting runtime")
        return startCompanyRuntime(companyId)
    }

    suspend fun stopCompanyRuntime(companyId: String): CompanyRuntimeSnapshot {
        companyRuntimeJobs.remove(companyId)?.cancel()
        codexAppServerManager.stop(companyId)
        val snapshot = stateMutex.withLock {
            val state = stateStore.load()
            val now = System.currentTimeMillis()
            val nextState = state.copy(
                companyRuntimes = upsertCompanyRuntime(
                    state.companyRuntimes,
                    (state.companyRuntimes.firstOrNull { it.companyId == companyId } ?: CompanyRuntimeSnapshot(companyId = companyId)).copy(
                        companyId = companyId,
                        status = CompanyRuntimeStatus.STOPPED,
                        lastStoppedAt = now,
                        lastAction = "runtime-stopped",
                        backendLifecycleState = BackendLifecycleState.STOPPED,
                        backendPid = null
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
        publishCompanyEvent(
            companyId = companyId,
            type = "runtime.stopped",
            title = "Stopped runtime"
        )
        return snapshot
    }

    suspend fun stopCompanyRuntime(): CompanyRuntimeSnapshot {
        val companyId = resolveLatestCompanyId(stateStore.load()) ?: companyRuntimeJobs.keys.lastOrNull()
            ?: throw IllegalStateException("Create a company before stopping runtime")
        return stopCompanyRuntime(companyId)
    }

    suspend fun runCompanyRuntimeTick(companyId: String): CompanyRuntimeSnapshot {
        val tickMutex = stateMutex.withLock {
            companyRuntimeTickMutexes.getOrPut(companyId) { Mutex() }
        }
        return tickMutex.withLock {
            reconcileStaleAgentRuns(companyId)
            val reconciledIssues = reconcileTerminalIssueStates(companyId)
            val initial = stateStore.load()
            val runtime = initial.companyRuntimes.firstOrNull { it.companyId == companyId } ?: CompanyRuntimeSnapshot(companyId = companyId)
            if (runtime.status != CompanyRuntimeStatus.RUNNING) {
                return@withLock runtimeStatus(companyId)
            }
            runCatching { startCompanyBackend(companyId) }
            reconcileNonPublishingReviewRuns(companyId)

            val actions = mutableListOf<String>()
            if (reconciledIssues > 0) {
                actions += "reconciled-issues:$reconciledIssues"
            }
            val archivedRecursiveGoals = archiveRecursiveFollowUpGoals(companyId)
            if (archivedRecursiveGoals > 0) {
                actions += "archived-recursive-goals:$archivedRecursiveGoals"
            }
            val retriedBlockedIssues = requeueBlockedRemediationIssues(companyId)
            if (retriedBlockedIssues > 0) {
                actions += "retried-blocked:$retriedBlockedIssues"
            }
            synthesizeAutonomousFollowUpGoal(companyId)?.let { synthesizedGoal ->
                actions += "goal-added:${synthesizedGoal.id}"
            }

            val current = stateStore.load()
            val autonomousGoals = current.goals.filter {
                it.companyId == companyId && it.autonomyEnabled && it.status == GoalStatus.ACTIVE
            }
            if (autonomousGoals.isEmpty()) {
                return@withLock updateRuntimeAfterTick(
                    companyId,
                    lastAction = actions.joinToString(separator = ", ").ifBlank { "idle-no-autonomous-goals" }
                )
            }

            autonomousGoals.forEach { goal ->
                if (current.issues.none { it.goalId == goal.id }) {
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
            val companyProfiles = ensureOrgProfiles(
                executionSnapshot.orgProfiles,
                executionSnapshot.companyAgentDefinitions,
                executionSnapshot.companies
            ).filter { it.companyId == companyId && it.enabled }
            val occupiedProfileIds = executionSnapshot.tasks
                .filter { it.status == DesktopTaskStatus.RUNNING || it.status == DesktopTaskStatus.QUEUED }
                .mapNotNull { task ->
                    val issue = executionSnapshot.issues.firstOrNull { it.id == task.issueId } ?: return@mapNotNull null
                    if (issue.companyId != companyId) return@mapNotNull null
                    issue.assigneeProfileId
                }
                .toMutableSet()
            val runnableIssues = executionSnapshot.issues
                .filter { issue ->
                    executionSnapshot.goals.firstOrNull { it.id == issue.goalId }?.let { goal ->
                        goal.autonomyEnabled && goal.status == GoalStatus.ACTIVE && goal.companyId == companyId
                    } == true
                }
                .filter { issue ->
                    issue.status == IssueStatus.PLANNED || issue.status == IssueStatus.BACKLOG || issue.status == IssueStatus.DELEGATED
                }
                .sortedWith(compareBy<CompanyIssue> { it.priority }.thenBy { it.createdAt })
                .filter { issue ->
                    val dependenciesSatisfied = issue.dependsOn.all { dependencyId ->
                        executionSnapshot.issues.firstOrNull { it.id == dependencyId }?.status == IssueStatus.DONE
                    }
                    val alreadyStarted = executionSnapshot.tasks.any { task ->
                        task.issueId == issue.id &&
                            (task.status == DesktopTaskStatus.RUNNING || task.status == DesktopTaskStatus.QUEUED)
                    }
                    dependenciesSatisfied && !alreadyStarted
                }
            val startableIssues = mutableListOf<CompanyIssue>()
            runnableIssues.forEach { candidate ->
                val delegated = if (candidate.assigneeProfileId == null || candidate.status != IssueStatus.DELEGATED) {
                    delegateIssue(candidate.id)
                } else {
                    candidate
                }
                if (companyProfiles.isEmpty()) {
                    if (startableIssues.isEmpty()) {
                        startableIssues += delegated
                    }
                    return@forEach
                }
                val profileId = delegated.assigneeProfileId
                if (profileId == null || occupiedProfileIds.add(profileId)) {
                    startableIssues += delegated
                }
            }

            startableIssues.forEach { runnableIssue ->
                startDelegatedIssue(runnableIssue)
                actions += "started:${runnableIssue.id}"
            }

            updateRuntimeAfterTick(
                companyId,
                lastAction = actions.joinToString(separator = ", ").ifBlank { "idle-no-work" }
            )
        }
    }

    private suspend fun requeueBlockedRemediationIssues(companyId: String): Int {
        var retried = 0
        stateMutex.withLock {
            val state = stateStore.load()
            val now = System.currentTimeMillis()
            val activeTaskIssueIds = state.tasks
                .filter { it.status == DesktopTaskStatus.RUNNING || it.status == DesktopTaskStatus.QUEUED }
                .mapNotNull { it.issueId }
                .toSet()
            var changed = false
            val updatedIssues = state.issues.map { issue ->
                if (issue.companyId != companyId || issue.status != IssueStatus.BLOCKED) {
                    return@map issue
                }
                val goal = state.goals.firstOrNull { it.id == issue.goalId } ?: return@map issue
                if (!goal.operatingPolicy.orEmpty().startsWith("auto-follow-up:")) {
                    return@map issue
                }
                if (issue.id in activeTaskIssueIds) {
                    return@map issue
                }
                val priorAttempts = state.tasks.count { it.issueId == issue.id }
                if (priorAttempts >= 3) {
                    return@map issue
                }
                changed = true
                retried += 1
                issue.copy(
                    status = IssueStatus.PLANNED,
                    blockedBy = emptyList(),
                    updatedAt = now
                )
            }
            if (!changed) {
                return@withLock
            }
            stateStore.save(
                state.copy(issues = updatedIssues)
                    .recordCompanyActivity(
                        companyId = companyId,
                        source = "company-runtime",
                        title = "Retried blocked remediation work",
                        detail = "Moved blocked follow-up issues back to planned for another attempt."
                    )
                    .withDerivedMetrics()
            )
        }
        return retried
    }

    private suspend fun requeueRecoverableBlockedIssues(companyId: String): Int {
        var retried = 0
        stateMutex.withLock {
            val state = stateStore.load()
            val now = System.currentTimeMillis()
            val goalsById = state.goals.associateBy { it.id }
            val issuesById = state.issues.associateBy { it.id }
            val activeTaskIssueIds = state.tasks
                .filter { it.status == DesktopTaskStatus.RUNNING || it.status == DesktopTaskStatus.QUEUED }
                .mapNotNull { it.issueId }
                .toSet()
            var changed = false
            val updatedIssues = state.issues.map { issue ->
                if (issue.companyId != companyId || issue.status != IssueStatus.BLOCKED) {
                    return@map issue
                }
                if (issue.id in activeTaskIssueIds) {
                    return@map issue
                }
                val goal = goalsById[issue.goalId]
                val issueTasks = state.tasks
                    .filter { it.issueId == issue.id }
                    .sortedByDescending { it.updatedAt }
                if (issueTasks.isEmpty()) {
                    val dependenciesSatisfied = issue.dependsOn.all { dependencyId ->
                        issuesById[dependencyId]?.status == IssueStatus.DONE
                    }
                    val goalAllowsAutomation = goal?.status == GoalStatus.ACTIVE && goal.autonomyEnabled
                    if (!goalAllowsAutomation || !dependenciesSatisfied) {
                        return@map issue
                    }
                    changed = true
                    retried += 1
                    return@map issue.copy(
                        status = IssueStatus.PLANNED,
                        blockedBy = emptyList(),
                        updatedAt = now
                    )
                }
                val latestTask = issueTasks.first()
                val latestRun = state.runs
                    .filter { it.taskId == latestTask.id }
                    .maxByOrNull { it.updatedAt }
                if (!isRecoverableInfrastructureFailure(latestTask, latestRun)) {
                    return@map issue
                }
                if (issueTasks.size >= 3) {
                    return@map issue
                }
                changed = true
                retried += 1
                issue.copy(
                    status = IssueStatus.PLANNED,
                    blockedBy = emptyList(),
                    updatedAt = now
                )
            }
            if (!changed) {
                return@withLock
            }
            stateStore.save(
                state.copy(issues = updatedIssues)
                    .recordCompanyActivity(
                        companyId = companyId,
                        source = "company-runtime",
                        title = "Retried recoverable blocked issues",
                        detail = "Moved infrastructure-blocked issues back to planned for another attempt."
                    )
                    .withDerivedMetrics()
            )
        }
        return retried
    }

    private fun isRecoverableInfrastructureFailure(task: AgentTask, run: AgentRun?): Boolean {
        if (task.status != DesktopTaskStatus.FAILED && task.status != DesktopTaskStatus.PARTIAL) {
            return false
        }
        val failureSignals = buildList {
            add(run?.error)
            add(run?.publish?.error)
            add(run?.output)
        }.mapNotNull { it?.lowercase() }
        return failureSignals.any { message ->
            message.contains("git command failed") ||
                message.contains("could not connect to the server") ||
                message.contains("network connection was lost") ||
                message.contains("connection refused") ||
                message.contains("no run found")
        }
    }

    private suspend fun reconcileTerminalIssueStates(companyId: String): Int {
        val taskIdsToSync = mutableListOf<String>()
        stateMutex.withLock {
            val state = stateStore.load()
            val activeTaskIssueIds = state.tasks
                .filter { it.status == DesktopTaskStatus.RUNNING || it.status == DesktopTaskStatus.QUEUED }
                .mapNotNull { it.issueId }
                .toSet()
            state.issues
                .filter { issue ->
                    issue.companyId == companyId &&
                        issue.status != IssueStatus.DONE &&
                        issue.status != IssueStatus.CANCELED &&
                        issue.id !in activeTaskIssueIds
                }
                .forEach { issue ->
                    val latestTask = state.tasks
                        .filter { it.issueId == issue.id }
                        .maxByOrNull { it.updatedAt }
                        ?: return@forEach
                    if (latestTask.status == DesktopTaskStatus.RUNNING || latestTask.status == DesktopTaskStatus.QUEUED) {
                        return@forEach
                    }
                    taskIdsToSync += latestTask.id
                }
        }
        taskIdsToSync.distinct().forEach { taskId ->
            getTask(taskId)?.let { task ->
                syncIssueFromTask(task.id, task.status)
            }
        }
        return taskIdsToSync.distinct().size
    }

    private suspend fun normalizeCompanyAutomationState(companyId: String): Int {
        var changed = 0
        stateMutex.withLock {
            val state = stateStore.load()
            val now = System.currentTimeMillis()
            val issuesById = state.issues.associateBy { it.id }
            val goalsById = state.goals.associateBy { it.id }
            val reviewQueueById = state.reviewQueue.associateBy { it.id }
            val activeTaskIssueIds = state.tasks
                .filter { it.status == DesktopTaskStatus.RUNNING || it.status == DesktopTaskStatus.QUEUED }
                .mapNotNull { it.issueId }
                .toSet()
            val tasksByIssueId = state.tasks
                .filter { it.issueId != null }
                .groupBy { it.issueId!! }
            val recursiveGoalIds = state.goals
                .filter { goal ->
                    goal.companyId == companyId &&
                        goal.status == GoalStatus.ACTIVE &&
                        goal.operatingPolicy.orEmpty().startsWith("auto-follow-up:")
                }
                .mapNotNull { goal ->
                    val parentGoal = when {
                        goal.operatingPolicy?.startsWith("auto-follow-up:issue:") == true -> {
                            val issueId = goal.operatingPolicy.removePrefix("auto-follow-up:issue:")
                            issuesById[issueId]?.goalId?.let(goalsById::get)
                        }
                        goal.operatingPolicy?.startsWith("auto-follow-up:review:") == true -> {
                            val reviewId = goal.operatingPolicy.removePrefix("auto-follow-up:review:")
                            val issueId = reviewQueueById[reviewId]?.issueId
                            issueId?.let(issuesById::get)?.goalId?.let(goalsById::get)
                        }
                        else -> null
                    } ?: return@mapNotNull null
                    if (parentGoal.operatingPolicy.orEmpty().startsWith("auto-follow-up:")) goal.id else null
                }
                .toSet()
            val nextIssues = state.issues.map { issue ->
                if (issue.companyId != companyId) {
                    return@map issue
                }
                val latestTask = tasksByIssueId[issue.id].orEmpty().maxByOrNull { it.updatedAt }
                val goal = goalsById[issue.goalId]
                val nextStatus = when {
                    issue.goalId in recursiveGoalIds && issue.status != IssueStatus.DONE && issue.status != IssueStatus.CANCELED -> IssueStatus.CANCELED
                    latestTask == null || issue.id in activeTaskIssueIds || issue.status == IssueStatus.DONE || issue.status == IssueStatus.CANCELED -> issue.status
                    latestTask.status == DesktopTaskStatus.COMPLETED -> IssueStatus.DONE
                    latestTask.status == DesktopTaskStatus.FAILED || latestTask.status == DesktopTaskStatus.PARTIAL -> {
                        if (goal?.operatingPolicy.orEmpty().startsWith("auto-follow-up:")) {
                            val attempts = tasksByIssueId[issue.id].orEmpty().size
                            if (attempts < 3) IssueStatus.PLANNED else IssueStatus.BLOCKED
                        } else {
                            IssueStatus.BLOCKED
                        }
                    }
                    else -> issue.status
                }
                if (nextStatus == issue.status) {
                    issue
                } else {
                    changed += 1
                    issue.copy(status = nextStatus, blockedBy = if (nextStatus == IssueStatus.PLANNED) emptyList() else issue.blockedBy, updatedAt = now)
                }
            }
            val activeTaskIssueIdsForGoals = state.tasks
                .filter { it.status == DesktopTaskStatus.RUNNING || it.status == DesktopTaskStatus.QUEUED }
                .mapNotNull { it.issueId }
                .toSet()
            val nextGoals = state.goals.map { goal ->
                if (goal.companyId != companyId) {
                    return@map goal
                }
                val nextStatus = when {
                    goal.id in recursiveGoalIds && goal.status == GoalStatus.ACTIVE -> GoalStatus.COMPLETED
                    else -> {
                        val goalIssues = nextIssues.filter { it.goalId == goal.id }
                        val unresolvedIssues = goalIssues.filter {
                            it.status != IssueStatus.DONE && it.status != IssueStatus.CANCELED
                        }
                        val blockedIssueIds = unresolvedIssues
                            .filter { it.status == IssueStatus.BLOCKED }
                            .map { it.id }
                            .toSet()
                        val hasActiveTasks = unresolvedIssues.any { it.id in activeTaskIssueIdsForGoals }
                        val downstreamBlocked = unresolvedIssues.isNotEmpty() && unresolvedIssues.all { issue ->
                            issue.status == IssueStatus.BLOCKED || issue.dependsOn.any { dependencyId -> dependencyId in blockedIssueIds }
                        }
                        val latestTaskStatuses = unresolvedIssues.mapNotNull { issue ->
                            tasksByIssueId[issue.id].orEmpty().maxByOrNull { it.updatedAt }?.status
                        }
                        val autoFollowUpTerminal = goal.operatingPolicy.orEmpty().startsWith("auto-follow-up:") &&
                            unresolvedIssues.isNotEmpty() &&
                            !hasActiveTasks &&
                            unresolvedIssues.all { issue ->
                                issue.status == IssueStatus.BLOCKED ||
                                    issue.dependsOn.any { dependencyId -> dependencyId in blockedIssueIds }
                            } &&
                            latestTaskStatuses.isNotEmpty() &&
                            latestTaskStatuses.all { it == DesktopTaskStatus.FAILED || it == DesktopTaskStatus.PARTIAL }
                        when {
                            unresolvedIssues.isEmpty() && goal.status != GoalStatus.COMPLETED -> GoalStatus.COMPLETED
                            autoFollowUpTerminal -> GoalStatus.BLOCKED
                            goal.status == GoalStatus.ACTIVE && !hasActiveTasks && blockedIssueIds.isNotEmpty() && downstreamBlocked -> GoalStatus.BLOCKED
                            goal.status == GoalStatus.BLOCKED && unresolvedIssues.any {
                                it.status == IssueStatus.PLANNED ||
                                    it.status == IssueStatus.DELEGATED ||
                                    it.status == IssueStatus.IN_PROGRESS ||
                                    it.status == IssueStatus.IN_REVIEW
                            } -> GoalStatus.ACTIVE
                            else -> goal.status
                        }
                    }
                }
                if (nextStatus == goal.status) goal else goal.copy(status = nextStatus, updatedAt = now)
            }
            val blockedGoalIds = nextGoals
                .filter { it.companyId == companyId && it.status == GoalStatus.BLOCKED }
                .map { it.id }
                .toSet()
            val finalizedIssues = nextIssues.map { issue ->
                if (
                    issue.companyId == companyId &&
                    issue.goalId in blockedGoalIds &&
                    issue.status != IssueStatus.DONE &&
                    issue.status != IssueStatus.CANCELED &&
                    issue.status != IssueStatus.BLOCKED
                ) {
                    changed += 1
                    issue.copy(status = IssueStatus.CANCELED, updatedAt = now)
                } else {
                    issue
                }
            }
            if (changed == 0 && recursiveGoalIds.isEmpty()) {
                return@withLock
            }
            val detailParts = buildList {
                if (changed > 0) add("reconciled $changed issue states")
                if (recursiveGoalIds.isNotEmpty()) add("archived ${recursiveGoalIds.size} recursive follow-up goals")
            }
            stateStore.save(
                state.copy(
                    issues = finalizedIssues,
                    goals = nextGoals
                ).recordCompanyActivity(
                    companyId = companyId,
                    source = "company-runtime",
                    title = "Normalized autonomous company state",
                    detail = detailParts.joinToString(", ")
                ).withDerivedMetrics()
            )
        }
        return changed
    }

    private suspend fun archiveRecursiveFollowUpGoals(companyId: String): Int {
        var archivedCount = 0
        stateMutex.withLock {
            val state = stateStore.load()
            val issuesById = state.issues.associateBy { it.id }
            val goalsById = state.goals.associateBy { it.id }
            val reviewQueueById = state.reviewQueue.associateBy { it.id }
            val activeGoalIdsWithTasks = state.tasks
                .filter { it.status == DesktopTaskStatus.RUNNING || it.status == DesktopTaskStatus.QUEUED }
                .mapNotNull { task -> task.issueId?.let { issuesById[it]?.goalId } }
                .toSet()
            val now = System.currentTimeMillis()
            val recursiveGoalIds = state.goals
                .filter { goal ->
                    goal.companyId == companyId &&
                        goal.status == GoalStatus.ACTIVE &&
                        goal.operatingPolicy.orEmpty().startsWith("auto-follow-up:") &&
                        goal.id !in activeGoalIdsWithTasks
                }
                .mapNotNull { goal ->
                    val parentGoal = when {
                        goal.operatingPolicy?.startsWith("auto-follow-up:issue:") == true -> {
                            val issueId = goal.operatingPolicy.removePrefix("auto-follow-up:issue:")
                            issuesById[issueId]?.goalId?.let(goalsById::get)
                        }
                        goal.operatingPolicy?.startsWith("auto-follow-up:review:") == true -> {
                            val reviewId = goal.operatingPolicy.removePrefix("auto-follow-up:review:")
                            val issueId = reviewQueueById[reviewId]?.issueId
                            issueId?.let(issuesById::get)?.goalId?.let(goalsById::get)
                        }
                        else -> null
                    } ?: return@mapNotNull null
                    if (parentGoal.operatingPolicy.orEmpty().startsWith("auto-follow-up:")) goal.id else null
                }
                .toSet()
            if (recursiveGoalIds.isEmpty()) {
                return@withLock
            }
            archivedCount = recursiveGoalIds.size
            val nextState = state.copy(
                goals = state.goals.map { goal ->
                    if (goal.id !in recursiveGoalIds) {
                        goal
                    } else {
                        goal.copy(
                            status = GoalStatus.COMPLETED,
                            updatedAt = now
                        )
                    }
                },
                issues = state.issues.map { issue ->
                    if (issue.goalId !in recursiveGoalIds || issue.status == IssueStatus.DONE || issue.status == IssueStatus.CANCELED) {
                        issue
                    } else {
                        issue.copy(
                            status = IssueStatus.CANCELED,
                            updatedAt = now
                        )
                    }
                }
            ).recordCompanyActivity(
                companyId = companyId,
                source = "company-runtime",
                title = "Archived recursive follow-up work",
                detail = "Closed duplicate nested remediation goals and kept the parent remediation loop active."
            ).withDerivedMetrics()
            stateStore.save(nextState)
        }
        return archivedCount
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
    ): AgentTask = stateMutex.withLock {
        val now = System.currentTimeMillis()
        val loadedState = stateStore.load()
        val workspaceResolution = when {
            loadedState.workspaces.any { it.id == workspaceId } -> loadedState to loadedState.workspaces.first { it.id == workspaceId }
            issueId != null -> {
                val issue = loadedState.issues.firstOrNull { it.id == issueId }
                val company = issue?.let { loadedState.companies.firstOrNull { company -> company.id == it.companyId } }
                if (company != null) {
                    ensureCompanyWorkspace(loadedState, company, now)
                } else if (loadedState.workspaces.isNotEmpty()) {
                    loadedState to loadedState.workspaces.maxByOrNull { it.updatedAt }
                } else {
                    ensureLatestRepositoryWorkspace(loadedState, now)
                }
            }
            loadedState.workspaces.isNotEmpty() -> loadedState to loadedState.workspaces.maxByOrNull { it.updatedAt }
            else -> ensureLatestRepositoryWorkspace(loadedState, now)
        }
        val ensuredResolution = if (workspaceResolution.second == null) {
            ensureLatestRepositoryWorkspace(workspaceResolution.first, now)
        } else {
            workspaceResolution
        }
        val state = ensuredResolution.first
        val workspace = ensuredResolution.second
            ?: throw IllegalArgumentException("Workspace not found: $workspaceId")
        val repository = state.repositories.firstOrNull { it.id == workspace.repositoryId }
            ?: throw IllegalArgumentException("Repository not found for workspace: ${workspace.repositoryId}")
        val taskId = UUID.randomUUID().toString()
        val requestedAgents = agents.map { it.trim().lowercase() }.filter { it.isNotBlank() }.ifEmpty {
            listOf("claude", "codex")
        }
        val linkedIssue = issueId?.let { id -> state.issues.firstOrNull { it.id == id } }
        val executionPlan = if (linkedIssue != null) {
            val assignmentPrompt = buildString {
                appendLine(prompt.trim())
                if (linkedIssue.acceptanceCriteria.isNotEmpty()) {
                    appendLine()
                    appendLine("Issue acceptance criteria:")
                    linkedIssue.acceptanceCriteria.forEach { criterion ->
                        appendLine("- $criterion")
                    }
                }
            }.trim()
            TaskExecutionPlan(
                goalSummary = title?.takeIf { it.isNotBlank() } ?: linkedIssue.title,
                decompositionSource = "issue-assignment",
                sharedChecklist = linkedIssue.acceptanceCriteria,
                assignments = listOf(
                    AgentAssignmentPlan(
                        participantId = linkedIssue.assigneeProfileId,
                        agentName = requestedAgents.first(),
                        role = title?.takeIf { it.isNotBlank() } ?: linkedIssue.title,
                        phase = linkedIssue.kind.lowercase(),
                        focus = linkedIssue.kind.lowercase(),
                        subtasks = linkedIssue.acceptanceCriteria.mapIndexed { index, criterion ->
                            TaskSubtask(
                                id = "${linkedIssue.id}-${index + 1}",
                                title = criterion,
                                details = "Complete this acceptance criterion for the linked issue."
                            )
                        },
                        assignedPrompt = assignmentPrompt
                    )
                )
            )
        } else {
            taskPlanner.buildPlan(
                title = title,
                prompt = prompt,
                agents = requestedAgents
            )
        }

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
        task
    }

    suspend fun runTask(taskId: String): AgentTask {
        return runTaskIfPresent(taskId) ?: throw IllegalArgumentException("Task not found: $taskId")
    }

    suspend fun getChanges(runId: String): ChangeSummary {
        val state = stateStore.load()
        val run = state.runs.firstOrNull { it.id == runId }
            ?: throw IllegalArgumentException("Run not found: $runId")
        val task = state.tasks.firstOrNull { it.id == run.taskId }
            ?: throw IllegalArgumentException("Task not found for run: ${run.taskId}")
        val workspace = state.workspaces.firstOrNull { it.id == task.workspaceId }
            ?: throw IllegalArgumentException("Workspace not found for task: ${task.workspaceId}")
        val fallback = ChangeSummary(
            runId = run.id,
            branchName = run.branchName,
            baseBranch = workspace.baseBranch.ifBlank { run.baseBranch },
            patch = "",
            changedFiles = emptyList()
        )
        val worktreePath = run.worktreePath.takeIf { it.isNotBlank() }?.let(Path::of) ?: return fallback
        if (!worktreePath.exists()) {
            return fallback
        }

        return runCatching {
            gitWorkspaceService.buildChangeSummary(
                runId = run.id,
                worktreePath = worktreePath,
                branchName = run.branchName,
                baseBranch = workspace.baseBranch
            )
        }.getOrElse {
            fallback
        }
    }

    suspend fun listFiles(runId: String, relativePath: String?): List<FileTreeNode> {
        val run = stateStore.load().runs.firstOrNull { it.id == runId }
            ?: throw IllegalArgumentException("Run not found: $runId")
        val worktreePath = run.worktreePath.takeIf { it.isNotBlank() }?.let(Path::of) ?: return emptyList()
        if (!worktreePath.exists()) {
            return emptyList()
        }
        return runCatching {
            gitWorkspaceService.listFiles(worktreePath, relativePath)
        }.getOrElse {
            emptyList()
        }
    }

    suspend fun listPorts(runId: String): List<PortEntry> {
        val run = stateStore.load().runs.firstOrNull { it.id == runId }
            ?: throw IllegalArgumentException("Run not found: $runId")
        return run.processId?.let { processId ->
            runCatching {
                gitWorkspaceService.listPorts(processId)
            }.getOrElse {
                emptyList()
            }
        } ?: emptyList()
    }

    fun settings(): DesktopSettings {
        val state = runBlocking { stateStore.load() }
        return DesktopSettings(
            appHome = stateStore.appHome().toString(),
            managedReposRoot = stateStore.managedReposRoot().toString(),
            availableAgents = BuiltinAgentCatalog.names(),
            availableCliAgents = BuiltinAgentCatalog.names().filter { isExecutableAvailable(it) },
            recentCompanies = emptyList(),
            defaultLaunchMode = "company",
            backendSettings = state.backendSettings,
            linearSettings = state.linearSettings,
            backendStatuses = computeBackendStatuses(state),
            shortcuts = ShortcutConfig()
        )
    }

    private fun effectiveBackendConfig(company: Company?, state: DesktopAppState): BackendConnectionConfig {
        val kind = company?.backendKind ?: state.backendSettings.defaultBackendKind
        val base = state.backendSettings.backends.firstOrNull { it.kind == kind } ?: BackendConnectionConfig(kind = kind)
        return company?.backendConfigOverride?.let { override ->
            base.copy(
                launchMode = override.launchMode,
                command = override.command.ifBlank { base.command },
                args = if (override.args.isEmpty()) base.args else override.args,
                workingDirectory = override.workingDirectory ?: base.workingDirectory,
                port = override.port ?: base.port,
                startupTimeoutSeconds = override.startupTimeoutSeconds,
                baseUrl = override.baseUrl ?: base.baseUrl,
                healthCheckPath = override.healthCheckPath.ifBlank { base.healthCheckPath },
                authMode = override.authMode.ifBlank { base.authMode },
                token = override.token ?: base.token,
                timeoutSeconds = override.timeoutSeconds,
                enabled = override.enabled
            )
        } ?: base
    }

    private fun effectiveLinearConfig(company: Company, state: DesktopAppState): LinearConnectionConfig {
        val base = state.linearSettings.defaultConfig
        val override = company.linearConfigOverride ?: return base
        return base.copy(
            endpoint = override.endpoint.ifBlank { base.endpoint },
            apiToken = override.apiToken ?: base.apiToken,
            teamId = override.teamId ?: base.teamId,
            projectId = override.projectId ?: base.projectId,
            stateMappings = if (override.stateMappings.isEmpty()) base.stateMappings else override.stateMappings
        )
    }

    private fun resolveLinearStateName(issue: CompanyIssue, config: LinearConnectionConfig): String? =
        config.stateMappings.firstOrNull { it.localStatus.equals(issue.status.name, ignoreCase = true) }?.linearStateName

    private fun executionBackendFor(kind: ExecutionBackendKind): ExecutionBackend =
        when (kind) {
            ExecutionBackendKind.LOCAL_COTOR -> localExecutionBackend
            ExecutionBackendKind.CODEX_APP_SERVER -> codexAppServerBackend
        }

    private fun localBackendConfig(state: DesktopAppState): BackendConnectionConfig =
        state.backendSettings.backends.firstOrNull { it.kind == ExecutionBackendKind.LOCAL_COTOR }
            ?: BackendConnectionConfig(kind = ExecutionBackendKind.LOCAL_COTOR)

    private fun companyBackendStatus(companyId: String, state: DesktopAppState): ExecutionBackendStatus {
        val company = state.companies.firstOrNull { it.id == companyId }
            ?: return computeBackendStatuses(state).firstOrNull { it.kind == state.backendSettings.defaultBackendKind }
                ?: ExecutionBackendStatus(
                    kind = state.backendSettings.defaultBackendKind,
                    displayName = state.backendSettings.defaultBackendKind.name,
                    config = effectiveBackendConfig(null, state)
                )
        val config = effectiveBackendConfig(company, state)
        if (config.kind == ExecutionBackendKind.CODEX_APP_SERVER) {
            val managedStatus = codexAppServerManager.status(company.id, config)
            return ExecutionBackendStatus(
                kind = config.kind,
                displayName = codexAppServerBackend.displayName,
                health = when (managedStatus.lifecycleState) {
                    BackendLifecycleState.RUNNING, BackendLifecycleState.ATTACHED -> "healthy"
                    BackendLifecycleState.STARTING, BackendLifecycleState.RESTARTING -> "starting"
                    BackendLifecycleState.STOPPED -> "stopped"
                    BackendLifecycleState.FAILED -> "offline"
                },
                message = when (managedStatus.lifecycleState) {
                    BackendLifecycleState.RUNNING -> "Managed Codex app server is running."
                    BackendLifecycleState.ATTACHED -> "Using attached Codex app server."
                    BackendLifecycleState.STARTING -> "Starting managed Codex app server."
                    BackendLifecycleState.RESTARTING -> "Restarting managed Codex app server."
                    BackendLifecycleState.STOPPED -> "Managed Codex app server is stopped."
                    BackendLifecycleState.FAILED -> managedStatus.lastError ?: "Managed Codex app server is unavailable."
                },
                lifecycleState = managedStatus.lifecycleState,
                managed = managedStatus.managed,
                pid = managedStatus.pid,
                port = managedStatus.port,
                lastError = managedStatus.lastError,
                config = config.copy(baseUrl = managedStatus.baseUrl ?: config.baseUrl, port = managedStatus.port ?: config.port),
                capabilities = codexAppServerBackend.capabilities
            )
        }
        return ExecutionBackendStatus(
            kind = config.kind,
            displayName = localExecutionBackend.displayName,
            health = if (config.enabled) "healthy" else "disabled",
            message = if (config.enabled) "Using local Cotor app-server and CLI execution." else "Disabled in settings.",
            lifecycleState = if (config.enabled) BackendLifecycleState.RUNNING else BackendLifecycleState.STOPPED,
            managed = false,
            config = config,
            capabilities = localExecutionBackend.capabilities
        )
    }

    private fun shouldFallbackFromCodexResult(result: AgentResult): Boolean {
        if (result.isSuccess) return false
        val error = result.error?.lowercase().orEmpty()
        return error.contains("codex app server") ||
            error.contains("failed to execute against codex") ||
            error.contains("base url is not configured") ||
            error.contains("connection refused") ||
            error.contains("connectexception") ||
            error.contains("http 404") ||
            error.contains("http 500") ||
            error.contains("timed out")
    }

    private suspend fun recordBackendFallback(
        company: Company,
        issue: CompanyIssue?,
        preferredKind: ExecutionBackendKind,
        reason: String
    ) {
        stateMutex.withLock {
            val state = stateStore.load()
            val nextState = state.recordCompanyActivity(
                companyId = company.id,
                projectContextId = issue?.projectContextId,
                goalId = issue?.goalId,
                issueId = issue?.id,
                source = "execution-backend",
                title = "Fell back to local execution",
                detail = "${preferredKind.name} was unavailable: $reason"
            ).recordSignal(
                source = "execution-backend",
                message = "Fell back to Local Cotor because ${preferredKind.name} was unavailable: $reason",
                companyId = company.id,
                projectContextId = issue?.projectContextId,
                goalId = issue?.goalId,
                issueId = issue?.id,
                severity = "warning"
            ).withDerivedMetrics()
            stateStore.save(nextState)
        }
        publishCompanyEvent(
            companyId = company.id,
            type = "backend.fallback",
            title = "Fell back to local execution",
            detail = reason,
            goalId = issue?.goalId,
            issueId = issue?.id
        )
    }

    private fun computeBackendStatuses(state: DesktopAppState): List<ExecutionBackendStatus> =
        state.backendSettings.backends.map { config ->
            when (config.kind) {
                ExecutionBackendKind.LOCAL_COTOR -> ExecutionBackendStatus(
                    kind = config.kind,
                    displayName = localExecutionBackend.displayName,
                    health = if (config.enabled) "healthy" else "disabled",
                    message = if (config.enabled) "Using local Cotor app-server and CLI execution." else "Disabled in settings.",
                    lifecycleState = if (config.enabled) BackendLifecycleState.RUNNING else BackendLifecycleState.STOPPED,
                    managed = false,
                    config = config,
                    capabilities = localExecutionBackend.capabilities
                )
                ExecutionBackendKind.CODEX_APP_SERVER -> {
                    val executableAvailable = codexAppServerManager.executableAvailable(config)
                    val attachedUrl = config.baseUrl?.trim().orEmpty()
                    val lifecycleState = when {
                        !config.enabled -> BackendLifecycleState.STOPPED
                        config.launchMode == BackendLaunchMode.ATTACHED && attachedUrl.isNotBlank() -> BackendLifecycleState.ATTACHED
                        config.launchMode == BackendLaunchMode.MANAGED && executableAvailable -> BackendLifecycleState.STOPPED
                        else -> BackendLifecycleState.FAILED
                    }
                    ExecutionBackendStatus(
                        kind = config.kind,
                        displayName = codexAppServerBackend.displayName,
                        health = when {
                            !config.enabled -> "disabled"
                            lifecycleState == BackendLifecycleState.ATTACHED -> "configured"
                            lifecycleState == BackendLifecycleState.STOPPED -> "ready"
                            else -> "offline"
                        },
                        message = when {
                            !config.enabled -> "Disabled in settings."
                            lifecycleState == BackendLifecycleState.ATTACHED -> "Attached to ${attachedUrl.removeSuffix("/")}"
                            lifecycleState == BackendLifecycleState.STOPPED -> "Ready to launch managed Codex app server."
                            else -> "Codex executable is unavailable or the attached URL is not configured."
                        },
                        lifecycleState = lifecycleState,
                        managed = config.launchMode == BackendLaunchMode.MANAGED,
                        port = config.port,
                        config = config,
                        capabilities = codexAppServerBackend.capabilities,
                        lastError = if (lifecycleState == BackendLifecycleState.FAILED) "Codex backend is not runnable with the current configuration." else null
                    )
                }
            }
        }

    private data class LinearSyncOutcome(
        val issueId: String,
        val created: Boolean,
        val commented: Boolean
    )

    private suspend fun mirrorGoalIssuesToLinear(goalId: String) {
        val issueIds = stateStore.load().issues.filter { it.goalId == goalId }.map { it.id }
        issueIds.forEach { mirrorIssueToLinear(it) }
    }

    private suspend fun mirrorIssueToLinear(
        issueId: String,
        comment: String? = null
    ): Result<LinearSyncOutcome> {
        val snapshot = stateStore.load()
        val issue = snapshot.issues.firstOrNull { it.id == issueId }
            ?: return Result.failure(IllegalArgumentException("Issue not found: $issueId"))
        val company = snapshot.companies.firstOrNull { it.id == issue.companyId }
            ?: return Result.failure(IllegalArgumentException("Company not found: ${issue.companyId}"))
        if (!company.linearSyncEnabled) {
            return Result.failure(IllegalStateException("Linear sync is disabled for ${company.name}"))
        }
        val config = effectiveLinearConfig(company, snapshot)
        val assigneeId = snapshot.orgProfiles.firstOrNull { it.id == issue.assigneeProfileId }?.linearAssigneeId
        val desiredState = resolveLinearStateName(issue, config)
        val created = issue.linearIssueId.isNullOrBlank()
        val syncResult = linearTracker.syncIssue(config, issue, desiredState, assigneeId)
        if (syncResult.isFailure) {
            val error = syncResult.exceptionOrNull() ?: IllegalStateException("Unknown Linear sync failure")
            recordLinearSyncFailure(company, issue, error.message ?: error.toString())
            return Result.failure(error)
        }
        val mirror = syncResult.getOrThrow()
        stateMutex.withLock {
            val state = stateStore.load()
            val current = state.issues.firstOrNull { it.id == issueId } ?: return@withLock
            val updatedIssue = current.copy(
                linearIssueId = mirror.id,
                linearIssueIdentifier = mirror.identifier ?: current.linearIssueIdentifier,
                linearIssueUrl = mirror.url ?: current.linearIssueUrl,
                lastLinearSyncAt = System.currentTimeMillis()
            )
            stateStore.save(
                state.copy(
                    issues = state.issues.map { if (it.id == issueId) updatedIssue else it }
                ).withDerivedMetrics()
            )
        }
        var commented = false
        val normalizedComment = comment?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedComment != null) {
            val commentResult = linearTracker.createComment(config, mirror.id, normalizedComment)
            if (commentResult.isFailure) {
                val error = commentResult.exceptionOrNull() ?: IllegalStateException("Unknown Linear comment failure")
                recordLinearSyncFailure(company, issue, error.message ?: error.toString())
                return Result.failure(error)
            }
            commented = true
        }
        publishCompanyEvent(
            companyId = company.id,
            type = if (created) "linear.issue.created" else "linear.issue.updated",
            title = if (created) "Mirrored issue to Linear" else "Updated Linear issue",
            detail = mirror.identifier ?: issue.title,
            issueId = issue.id
        )
        return Result.success(LinearSyncOutcome(issue.id, created, commented))
    }

    private suspend fun recordLinearSyncFailure(company: Company, issue: CompanyIssue, message: String) {
        stateMutex.withLock {
            val state = stateStore.load()
            val nextState = state.recordCompanyActivity(
                companyId = company.id,
                projectContextId = issue.projectContextId,
                goalId = issue.goalId,
                issueId = issue.id,
                source = "linear-sync",
                title = "Linear sync failed",
                detail = "${issue.title}: $message",
                severity = "warning"
            ).recordSignal(
                source = "linear-sync",
                message = "Linear sync failed for ${issue.title}: $message",
                companyId = company.id,
                projectContextId = issue.projectContextId,
                goalId = issue.goalId,
                issueId = issue.id,
                severity = "warning"
            ).withDerivedMetrics()
            stateStore.save(nextState)
        }
        publishCompanyEvent(
            companyId = company.id,
            type = "linear.sync.failed",
            title = "Linear sync failed",
            detail = "${issue.title}: $message",
            issueId = issue.id
        )
    }

    private fun computeWorkflowTopologies(
        state: DesktopAppState,
        orgProfiles: List<OrgAgentProfile>
    ): List<WorkflowTopologySnapshot> =
        state.companies.map { company ->
            val definitions = state.companyAgentDefinitions
                .filter { it.companyId == company.id && it.enabled }
                .sortedWith(compareBy<CompanyAgentDefinition> { it.displayOrder }.thenBy { it.title.lowercase() })
            val edges = buildList {
                definitions.forEach { definition ->
                    definition.preferredCollaboratorIds.forEach { collaboratorId ->
                        if (definitions.any { it.id == collaboratorId }) {
                            add(
                                AgentCollaborationEdge(
                                    companyId = company.id,
                                    fromAgentId = definition.id,
                                    toAgentId = collaboratorId,
                                    reason = definition.collaborationInstructions ?: "Preferred handoff",
                                    handoffType = inferHandoffType(definition.roleSummary)
                                )
                            )
                        }
                    }
                }
                if (definitions.size > 1 && none { edge -> edge.fromAgentId == definitions.first().id }) {
                    val leader = definitions.first()
                    definitions.drop(1).forEach { collaborator ->
                        add(
                            AgentCollaborationEdge(
                                companyId = company.id,
                                fromAgentId = leader.id,
                                toAgentId = collaborator.id,
                                reason = "CEO-driven orchestration lane",
                                handoffType = "coordination"
                            )
                        )
                    }
                }
            }
            val activeLoops = state.issues
                .filter { it.companyId == company.id && it.status != IssueStatus.DONE && it.status != IssueStatus.CANCELED }
                .map { "${it.kind}:${it.status.name.lowercase()}" }
                .distinct()
            WorkflowTopologySnapshot(
                companyId = company.id,
                agents = orgProfiles.filter { it.companyId == company.id }.map { it.roleName },
                edges = edges,
                activeLoops = activeLoops,
                updatedAt = System.currentTimeMillis()
            )
        }

    private fun computeRunningAgentSessions(
        state: DesktopAppState,
        orgProfiles: List<OrgAgentProfile>
    ): List<RunningAgentSession> {
        val tasksById = state.tasks.associateBy { it.id }
        val issuesById = state.issues.associateBy { it.id }
        return state.runs
            .filter { it.status == AgentRunStatus.QUEUED || it.status == AgentRunStatus.RUNNING }
            .mapNotNull { run ->
                val task = tasksById[run.taskId] ?: return@mapNotNull null
                val issue = task.issueId?.let { issuesById[it] }
                val companyId = issue?.companyId
                    ?: state.workspaces.firstOrNull { it.id == task.workspaceId }?.repositoryId?.let { repositoryId ->
                        state.companies.firstOrNull { it.repositoryId == repositoryId }?.id
                    }
                    ?: return@mapNotNull null
                val profile = issue?.assigneeProfileId?.let { profileId ->
                    orgProfiles.firstOrNull { it.id == profileId }
                } ?: orgProfiles.firstOrNull {
                    it.companyId == companyId && it.executionAgentName.equals(run.agentName, ignoreCase = true)
                }
                RunningAgentSession(
                    companyId = companyId,
                    runId = run.id,
                    taskId = task.id,
                    issueId = issue?.id,
                    goalId = issue?.goalId,
                    agentId = run.agentId,
                    agentName = run.agentName,
                    roleName = profile?.roleName,
                    status = run.status,
                    branchName = run.branchName,
                    processId = run.processId,
                    outputSnippet = run.output?.lineSequence()?.take(3)?.joinToString("\n"),
                    startedAt = run.createdAt,
                    updatedAt = run.updatedAt
                )
            }
            .sortedByDescending { it.updatedAt }
    }

    private fun inferHandoffType(roleSummary: String): String {
        val normalized = roleSummary.lowercase()
        return when {
            listOf("review", "qa", "verification").any { it in normalized } -> "review"
            listOf("plan", "design", "strategy", "ceo").any { it in normalized } -> "planning"
            else -> "execution"
        }
    }

    private fun publishCompanyEvent(
        companyId: String,
        type: String,
        title: String,
        detail: String? = null,
        goalId: String? = null,
        issueId: String? = null,
        runId: String? = null
    ) {
        companyEventStream.tryEmit(
            CompanyEventEnvelope(
                event = CompanyEvent(
                    id = UUID.randomUUID().toString(),
                    companyId = companyId,
                    type = type,
                    title = title,
                    detail = detail,
                    goalId = goalId,
                    issueId = issueId,
                    runId = runId,
                    createdAt = System.currentTimeMillis()
                )
            )
        )
    }

    private suspend fun upsertRepositoryForPath(
        repositories: List<ManagedRepository>,
        repositoryRoot: Path,
        now: Long
    ): ManagedRepository {
        val existing = repositories.firstOrNull { sameRepositoryRoot(it.localPath, repositoryRoot) }
        val defaultBranch = runCatching {
            gitWorkspaceService.detectDefaultBranch(repositoryRoot)
        }.getOrElse { existing?.defaultBranch ?: "master" }
        val remoteUrl = runCatching {
            gitWorkspaceService.detectRemoteUrl(repositoryRoot)
        }.getOrNull() ?: existing?.remoteUrl
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
        val agentsDir = root.resolve("agents")
        val workflowsDir = root.resolve("workflows")
        Files.createDirectories(companyDir)
        Files.createDirectories(projectDir)
        Files.createDirectories(issuesDir)
        Files.createDirectories(agentsDir)
        Files.createDirectories(workflowsDir)
        val companyGoals = state.goals.filter { it.companyId == company.id }.sortedByDescending { it.updatedAt }
        val companyIssues = state.issues.filter { it.companyId == company.id }.sortedByDescending { it.updatedAt }
        val companyDefinitions = state.companyAgentDefinitions
            .filter { it.companyId == company.id }
            .sortedWith(compareBy<CompanyAgentDefinition> { it.displayOrder }.thenBy { it.title.lowercase() })
        val companyProfiles = state.orgProfiles.filter { it.companyId == company.id }
        val companyActivity = state.companyActivity.filter { it.companyId == company.id }.sortedByDescending { it.createdAt }.take(12)
        val companyReviewQueue = state.reviewQueue.filter { it.companyId == company.id }.sortedByDescending { it.updatedAt }
        val companyDecisions = state.goalDecisions.filter { it.companyId == company.id }.sortedByDescending { it.createdAt }.take(10)
        Files.writeString(
            root.resolve("company.md"),
            buildString {
                appendLine("# ${company.name}")
                appendLine()
                appendLine("- rootPath: ${company.rootPath}")
                appendLine("- defaultBaseBranch: ${company.defaultBaseBranch}")
                appendLine("- autonomyEnabled: ${company.autonomyEnabled}")
                appendLine("- goals: ${companyGoals.size}")
                appendLine("- issues: ${companyIssues.size}")
                appendLine("- agents: ${companyDefinitions.size}")
                appendLine()
                appendLine("## Company memory")
                appendLine("This file persists the company-wide operating memory across sessions.")
                appendLine()
                appendLine("### Active goals")
                if (companyGoals.isEmpty()) {
                    appendLine("- none")
                } else {
                    companyGoals.take(8).forEach { goal ->
                        appendLine("- ${goal.title} [${goal.status}]")
                    }
                }
                appendLine()
                appendLine("### Current issue state")
                if (companyIssues.isEmpty()) {
                    appendLine("- none")
                } else {
                    companyIssues.take(10).forEach { issue ->
                        appendLine("- ${issue.title} [${issue.status}]")
                    }
                }
                appendLine()
                appendLine("### Recent activity")
                if (companyActivity.isEmpty()) {
                    appendLine("- none")
                } else {
                    companyActivity.forEach { item ->
                        appendLine("- ${item.title}${item.detail?.let { detail -> ": $detail" } ?: ""}")
                    }
                }
                appendLine()
                appendLine("### Recent CEO decisions")
                if (companyDecisions.isEmpty()) {
                    appendLine("- none")
                } else {
                    companyDecisions.forEach { decision ->
                        appendLine("- ${decision.title}: ${decision.summary}")
                    }
                }
            }
        )
        Files.writeString(
            projectDir.resolve("default.md"),
            buildString {
                appendLine("# ${projectContext.name}")
                appendLine()
                appendLine("Company project context for ${company.name}.")
                appendLine()
                appendLine("## Workflow memory")
                appendLine("The CEO can reshape workflow using the current roster and issue graph.")
                appendLine()
                appendLine("### Agent roster")
                if (companyDefinitions.isEmpty()) {
                    appendLine("- none")
                } else {
                    companyDefinitions.forEach { definition ->
                        val profile = companyProfiles.firstOrNull { it.id == definition.id }
                        appendLine("- ${definition.title} · ${definition.agentCli}")
                        appendLine("  role: ${definition.roleSummary}")
                        appendLine("  specialties: ${definition.specialties.joinToString(", ").ifBlank { "none" }}")
                        appendLine("  collaborators: ${definition.preferredCollaboratorIds.mapNotNull { collaboratorId -> companyDefinitions.firstOrNull { it.id == collaboratorId }?.title }.joinToString(", ").ifBlank { "none" }}")
                        definition.collaborationInstructions?.takeIf { it.isNotBlank() }?.let {
                            appendLine("  collaboration: $it")
                        }
                        appendLine("  capabilities: ${profile?.capabilities?.joinToString(", ").orEmpty().ifBlank { "none" }}")
                    }
                }
                appendLine()
                appendLine("### Review queue")
                if (companyReviewQueue.isEmpty()) {
                    appendLine("- none")
                } else {
                    companyReviewQueue.take(10).forEach { item ->
                        val issue = companyIssues.firstOrNull { it.id == item.issueId }
                        appendLine("- ${issue?.title ?: item.issueId} [${item.status}]")
                    }
                }
                appendLine()
                appendLine("### Decision memory")
                if (companyDecisions.isEmpty()) {
                    appendLine("- none")
                } else {
                    companyDecisions.forEach { decision ->
                        appendLine("- ${decision.title}: ${decision.summary}")
                    }
                }
            }
        )
        Files.writeString(
            workflowsDir.resolve("active.md"),
            buildString {
                appendLine("# ${company.name} workflow memory")
                appendLine()
                appendLine("## Current dependency graph")
                if (companyIssues.isEmpty()) {
                    appendLine("- none")
                } else {
                    companyIssues.sortedBy { it.createdAt }.forEach { issue ->
                        appendLine("- ${issue.title} [${issue.kind}/${issue.status}]")
                        appendLine("  dependsOn: ${issue.dependsOn.joinToString(", ").ifBlank { "none" }}")
                    }
                }
                appendLine()
                appendLine("## Latest CEO decisions")
                if (companyDecisions.isEmpty()) {
                    appendLine("- none")
                } else {
                    companyDecisions.forEach { decision ->
                        appendLine("- ${decision.title}: ${decision.summary}")
                    }
                }
            }
        )
        companyDefinitions.forEach { definition ->
            val assignedIssues = companyIssues.filter { it.assigneeProfileId == definition.id }
            val profile = companyProfiles.firstOrNull { it.id == definition.id }
            Files.writeString(
                agentsDir.resolve("${definition.id}.md"),
                buildString {
                    appendLine("# ${definition.title}")
                    appendLine()
                    appendLine("- cli: ${definition.agentCli}")
                    appendLine("- enabled: ${definition.enabled}")
                    appendLine("- roleSummary: ${definition.roleSummary}")
                    appendLine("- specialties: ${definition.specialties.joinToString(", ").ifBlank { "none" }}")
                    appendLine("- preferredCollaborators: ${definition.preferredCollaboratorIds.mapNotNull { collaboratorId -> companyDefinitions.firstOrNull { it.id == collaboratorId }?.title }.joinToString(", ").ifBlank { "none" }}")
                    appendLine("- capabilities: ${profile?.capabilities?.joinToString(", ").orEmpty().ifBlank { "none" }}")
                    appendLine("- mergeAuthority: ${profile?.mergeAuthority == true}")
                    definition.collaborationInstructions?.takeIf { it.isNotBlank() }?.let {
                        appendLine("- collaborationInstructions: $it")
                    }
                    definition.memoryNotes?.takeIf { it.isNotBlank() }?.let {
                        appendLine("- memoryNotes: $it")
                    }
                    appendLine()
                    appendLine("## Agent memory")
                    appendLine("Remember company context, assigned issues, and recent workflow events.")
                    appendLine()
                    appendLine("### Assigned issues")
                    if (assignedIssues.isEmpty()) {
                        appendLine("- none")
                    } else {
                        assignedIssues.sortedByDescending { it.updatedAt }.forEach { issue ->
                            appendLine("- ${issue.title} [${issue.status}]")
                        }
                    }
                    appendLine()
                    appendLine("### Recent related events")
                    val relatedActivity = companyActivity.filter { item ->
                        assignedIssues.any { it.id == item.issueId } || item.source == "agent-roster"
                    }
                    if (relatedActivity.isEmpty()) {
                        appendLine("- none")
                    } else {
                        relatedActivity.forEach { item ->
                            appendLine("- ${item.title}${item.detail?.let { detail -> ": $detail" } ?: ""}")
                        }
                    }
                }
            )
        }
        companyGoals.forEach { goal ->
            Files.writeString(
                companyDir.resolve("${goal.id}.md"),
                buildString {
                    appendLine("# ${goal.title}")
                    appendLine()
                    appendLine("- status: ${goal.status}")
                    appendLine("- successMetrics: ${goal.successMetrics.joinToString(", ").ifBlank { "none" }}")
                    appendLine()
                    appendLine(goal.description)
                    appendLine()
                    appendLine("## Derived issues")
                    val derivedIssues = companyIssues.filter { it.goalId == goal.id }.sortedByDescending { it.updatedAt }
                    if (derivedIssues.isEmpty()) {
                        appendLine("- none")
                    } else {
                        derivedIssues.forEach { issue ->
                            appendLine("- ${issue.title} [${issue.status}]")
                        }
                    }
                }
            )
        }
        companyIssues.forEach { issue ->
            Files.writeString(
                issuesDir.resolve("${issue.id}.md"),
                buildString {
                    appendLine("# ${issue.title}")
                    appendLine()
                    appendLine("- status: ${issue.status}")
                    appendLine("- kind: ${issue.kind}")
                    appendLine("- assigneeProfileId: ${issue.assigneeProfileId ?: "unassigned"}")
                    appendLine("- dependsOn: ${issue.dependsOn.joinToString(", ").ifBlank { "none" }}")
                    appendLine()
                    appendLine(issue.description)
                }
            )
        }
    }

    private fun deleteDirectoryRecursively(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun deleteGoalContextEntries(company: Company, goalIds: Set<String>) {
        if (goalIds.isEmpty()) return
        val goalsDir = companyContextRoot(company).resolve("goals")
        goalIds.forEach { goalId ->
            Files.deleteIfExists(goalsDir.resolve("$goalId.md"))
        }
    }

    private fun deleteIssueContextEntries(company: Company, issueIds: Set<String>) {
        if (issueIds.isEmpty()) return
        val issuesDir = companyContextRoot(company).resolve("issues")
        issueIds.forEach { issueId ->
            Files.deleteIfExists(issuesDir.resolve("$issueId.md"))
        }
    }

    private fun sameRepositoryRoot(savedPath: String, repositoryRoot: Path): Boolean =
        Path.of(savedPath).toAbsolutePath().normalize() == repositoryRoot.toAbsolutePath().normalize()

    private suspend fun executeTask(taskId: String) {
        var snapshot = stateStore.load()
        val task = snapshot.tasks.firstOrNull { it.id == taskId } ?: return
        var workspace = snapshot.workspaces.firstOrNull { it.id == task.workspaceId }
        if (workspace == null && task.issueId != null) {
            snapshot.issues.firstOrNull { it.id == task.issueId }?.let { ensureIssueWorkspace(it) }
            snapshot = stateStore.load()
            workspace = snapshot.workspaces.firstOrNull { it.id == task.workspaceId }
        }
        if (workspace == null) {
            updateTaskStatus(task.id, DesktopTaskStatus.FAILED)
            return
        }
        val repository = snapshot.repositories.firstOrNull { it.id == workspace.repositoryId }
        if (repository == null) {
            updateTaskStatus(task.id, DesktopTaskStatus.FAILED)
            return
        }
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
            val currentState = stateStore.load()
            val issue = task.issueId?.let { issueId -> currentState.issues.firstOrNull { it.id == issueId } }
            val company = issue?.let { linkedIssue -> currentState.companies.firstOrNull { it.id == linkedIssue.companyId } }
                ?: currentState.companies.firstOrNull { it.repositoryId == repository.id }
            val preferredBackendKind = company?.backendKind ?: currentState.backendSettings.defaultBackendKind
            val backendConfig = effectiveBackendConfig(company, currentState)
            val executionBackend = executionBackendFor(preferredBackendKind)

            val startedRun = run.copy(status = AgentRunStatus.RUNNING, updatedAt = System.currentTimeMillis())
            replaceRun(startedRun)
            company?.let {
                publishCompanyEvent(
                    companyId = it.id,
                    type = "run.started",
                    title = "Started agent run",
                    detail = "${agent.name} on ${task.title}",
                    goalId = issue?.goalId,
                    issueId = issue?.id,
                    runId = startedRun.id
                )
            }

            val executionMetadata = AgentExecutionMetadata(
                repoRoot = Path.of(repository.localPath),
                workspaceId = workspace.id,
                taskId = task.id,
                agentId = agent.name,
                baseBranch = workspace.baseBranch,
                branchName = binding.branchName,
                workingDirectory = binding.worktreePath,
                onProcessStarted = { processId ->
                    runBlocking {
                        replaceRun(
                            startedRun.copy(
                                processId = processId,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            )
            val assignedPrompt = assignedPromptFor(task, agent.name)

            val result = if (preferredBackendKind == ExecutionBackendKind.CODEX_APP_SERVER) {
                val managedStatus = company?.let {
                    if (backendConfig.launchMode == BackendLaunchMode.MANAGED) {
                        publishCompanyEvent(it.id, "backend.starting", "Starting backend", issueId = issue?.id)
                        codexAppServerManager.ensureStarted(it.id, backendConfig)
                    } else {
                        codexAppServerManager.status(it.id, backendConfig)
                    }
                }
                val effectiveBackendConfig = backendConfig.copy(
                    baseUrl = managedStatus?.baseUrl ?: backendConfig.baseUrl,
                    port = managedStatus?.port ?: backendConfig.port
                )
                val health = executionBackend.health(effectiveBackendConfig)
                if (health.health != "healthy") {
                    company?.let {
                        recordBackendFallback(
                            company = it,
                            issue = issue,
                            preferredKind = preferredBackendKind,
                            reason = health.message ?: "Codex app server is unavailable"
                        )
                    }
                    localExecutionBackend.execute(
                        ExecutionBackendRequest(
                            agent = agent,
                            prompt = assignedPrompt,
                            effectiveConfig = localBackendConfig(currentState),
                            metadata = executionMetadata
                        )
                    )
                } else {
                    val remoteResult = executionBackend.execute(
                        ExecutionBackendRequest(
                            agent = agent,
                            prompt = assignedPrompt,
                            effectiveConfig = effectiveBackendConfig,
                            metadata = executionMetadata
                        )
                    )
                    if (shouldFallbackFromCodexResult(remoteResult)) {
                        company?.let {
                            recordBackendFallback(
                                company = it,
                                issue = issue,
                                preferredKind = preferredBackendKind,
                                reason = remoteResult.error ?: "Codex app server execution failed"
                            )
                        }
                        localExecutionBackend.execute(
                            ExecutionBackendRequest(
                                agent = agent,
                                prompt = assignedPrompt,
                                effectiveConfig = localBackendConfig(currentState),
                                metadata = executionMetadata
                            )
                        )
                    } else {
                        remoteResult
                    }
                }
            } else {
                executionBackend.execute(
                    ExecutionBackendRequest(
                        agent = agent,
                        prompt = assignedPrompt,
                        effectiveConfig = backendConfig,
                        metadata = executionMetadata
                    )
                )
            }
            val publishRequired = requiresCodePublish(issue)
            val publish = if (result.isSuccess && publishRequired) {
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
            val finalError = result.error ?: if (publishRequired) publish?.error else null

            replaceRun(
                startedRun.copy(
                    status = if (result.isSuccess && finalError == null) AgentRunStatus.COMPLETED else AgentRunStatus.FAILED,
                    processId = result.processId,
                    output = result.output,
                    error = finalError,
                    publish = publish,
                    durationMs = result.duration.takeIf { it > 0 },
                    updatedAt = System.currentTimeMillis()
                )
            )
            company?.let {
                publishCompanyEvent(
                    companyId = it.id,
                    type = if (result.isSuccess && finalError == null) "run.completed" else "run.failed",
                    title = if (result.isSuccess && finalError == null) "Completed agent run" else "Agent run failed",
                    detail = result.error ?: publish?.error ?: "${agent.name} finished ${task.title}",
                    goalId = issue?.goalId,
                    issueId = issue?.id,
                    runId = startedRun.id
                )
            }
        } catch (t: Throwable) {
            recordRunFailure(task, workspace, repository, agent.name, t.message ?: "Unknown error")
        }
    }

    private fun assignedPromptFor(task: AgentTask, agentName: String): String =
        task.plan?.assignmentFor(agentName)?.assignedPrompt ?: task.prompt

    private suspend fun resolveAgents(repositoryRoot: Path, requestedAgents: List<String>): Map<String, AgentConfig> {
        val configPath = repositoryRoot.resolve("cotor.yaml")
        val configured = if (configPath.exists()) {
            runCatching {
                configRepository.loadConfig(configPath).agents.associateBy { it.name.lowercase() }
            }.getOrElse {
                emptyMap()
            }
        } else {
            emptyMap()
        }

        return requestedAgents.associateWith { name ->
            configured[name.lowercase()]
                ?: BuiltinAgentCatalog.get(name)
                ?: commandBackedAgentConfig(name)
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
        return stateMutex.withLock {
            val state = stateStore.load()
            val existing = state.runs.firstOrNull { it.taskId == task.id && it.agentName == agentName }
            if (existing != null) {
                return@withLock existing
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
            run
        }
    }

    private suspend fun replaceRun(run: AgentRun) {
        stateMutex.withLock {
            val state = stateStore.load()
            stateStore.save(
                state.copy(
                    runs = if (state.runs.any { existing -> existing.id == run.id }) {
                        state.runs.map { existing -> if (existing.id == run.id) run else existing }
                    } else {
                        state.runs + run
                    }
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

    private suspend fun runTaskIfPresent(taskId: String): AgentTask? {
        val task = getTask(taskId) ?: return null
        updateTaskStatus(task.id, DesktopTaskStatus.RUNNING)
        serviceScope.launch {
            // Long-running agent execution is detached from the request lifecycle.
            executeTask(task.id)
        }
        return task.copy(status = DesktopTaskStatus.RUNNING)
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
        val state = stateStore.load()
        task.issueId?.let { issueId ->
            state.issues.firstOrNull { it.id == issueId }?.let { issue ->
                publishCompanyEvent(
                    companyId = issue.companyId,
                    type = "run.failed",
                    title = "Agent run failed",
                    detail = message,
                    goalId = issue.goalId,
                    issueId = issue.id,
                    runId = run.id
                )
            }
        }
    }

    private fun requiresCodePublish(issue: CompanyIssue?): Boolean {
        if (issue == null) return true
        return when (issue.kind.lowercase()) {
            "review", "approval" -> false
            else -> true
        }
    }

    private suspend fun reconcileNonPublishingReviewRuns(companyId: String) {
        val taskIdsToSync = mutableListOf<String>()
        stateMutex.withLock {
            val state = stateStore.load()
            val tasksById = state.tasks.associateBy { it.id }
            val issuesById = state.issues.associateBy { it.id }
            var changed = false
            val now = System.currentTimeMillis()
            val repairedRuns = state.runs.map { run ->
                val task = tasksById[run.taskId] ?: return@map run
                val issue = task.issueId?.let(issuesById::get) ?: return@map run
                if (issue.companyId != companyId || requiresCodePublish(issue)) {
                    return@map run
                }
                if (run.status != AgentRunStatus.FAILED) {
                    return@map run
                }
                val publishError = run.publish?.error ?: return@map run
                if (!publishError.startsWith("No changes to publish")) {
                    return@map run
                }
                changed = true
                taskIdsToSync += task.id
                run.copy(
                    status = AgentRunStatus.COMPLETED,
                    error = null,
                    publish = run.publish.copy(error = null),
                    updatedAt = now
                )
            }
            if (!changed) {
                return@withLock
            }
            val repairedTaskIds = taskIdsToSync.toSet()
            val repairedTasks = state.tasks.map { task ->
                if (task.id !in repairedTaskIds) {
                    return@map task
                }
                task.copy(
                    status = DesktopTaskStatus.COMPLETED,
                    updatedAt = now
                )
            }
            stateStore.save(
                state.copy(
                    tasks = repairedTasks,
                    runs = repairedRuns
                ).withDerivedMetrics()
            )
        }
        taskIdsToSync.distinct().forEach { taskId ->
            syncIssueFromTask(taskId, DesktopTaskStatus.COMPLETED)
        }
    }

    private fun decomposeGoal(
        goal: CompanyGoal,
        workspace: Workspace,
        profiles: List<OrgAgentProfile>,
        definitions: List<CompanyAgentDefinition>,
        now: Long
    ): Pair<List<CompanyIssue>, List<IssueDependency>> {
        val participants = buildPlanningParticipants(goal.companyId, profiles, definitions)
        val plan = taskPlanner.buildPlanForParticipants(
            title = goal.title,
            prompt = goal.description,
            participants = participants
        )
        val issueIds = plan.assignments.map { UUID.randomUUID().toString() }
        val dependencyIdsByPhase = linkedMapOf<String, MutableList<String>>()
        val issues = plan.assignments.mapIndexed { index, assignment ->
            val profile = profiles.firstOrNull {
                it.id == assignment.participantId ||
                    (assignment.participantId == null && it.executionAgentName.equals(assignment.agentName, ignoreCase = true))
            }
            val dependsOn = when (assignment.phase.lowercase()) {
                "review" -> dependencyIdsByPhase["execution"].orEmpty()
                "approval" -> dependencyIdsByPhase["review"].orEmpty().ifEmpty { dependencyIdsByPhase["execution"].orEmpty() }
                else -> emptyList()
            }
            val issueId = issueIds[index]
            dependencyIdsByPhase.getOrPut(assignment.phase.lowercase()) { mutableListOf() }.add(issueId)
            CompanyIssue(
                id = issueId,
                companyId = goal.companyId,
                projectContextId = goal.projectContextId,
                goalId = goal.id,
                workspaceId = workspace.id,
                title = assignment.subtasks.firstOrNull()?.title ?: "${assignment.role}: ${assignment.focus}",
                description = buildIssueDescription(goal, assignment, plan.sharedChecklist),
                status = if (dependsOn.isEmpty()) IssueStatus.PLANNED else IssueStatus.BACKLOG,
                priority = when (assignment.phase.lowercase()) {
                    "execution" -> 2
                    "review" -> 3
                    "approval" -> 4
                    else -> 3
                },
                kind = assignment.phase.lowercase(),
                assigneeProfileId = profile?.id,
                blockedBy = emptyList(),
                dependsOn = dependsOn,
                acceptanceCriteria = (plan.sharedChecklist + assignment.subtasks.map { it.title }).distinct(),
                riskLevel = when (assignment.phase.lowercase()) {
                    "review" -> "low"
                    "approval" -> "medium"
                    else -> "medium"
                },
                createdAt = now + index,
                updatedAt = now + index
            )
        }
        val dependencies = issues.flatMap { issue ->
            issue.dependsOn.map { dependencyId ->
                IssueDependency(
                    id = UUID.randomUUID().toString(),
                    issueId = issue.id,
                    dependsOnIssueId = dependencyId
                )
            }
        }
        return issues to dependencies
    }

    private suspend fun synthesizeAutonomousFollowUpGoal(companyId: String): CompanyGoal? {
        val state = stateStore.load()
        val company = state.companies.firstOrNull { it.id == companyId } ?: return null
        val openGoals = state.goals.filter {
            it.companyId == companyId && it.status != GoalStatus.COMPLETED
        }
        val activeTaskIssueIds = state.tasks
            .filter { it.status == DesktopTaskStatus.RUNNING || it.status == DesktopTaskStatus.QUEUED }
            .mapNotNull { it.issueId }
            .toSet()
        val issuesById = state.issues
            .filter { it.companyId == companyId }
            .associateBy { it.id }

        data class FollowUpCandidate(
            val issue: CompanyIssue,
            val reason: String,
            val marker: String
        )

        fun hasOpenFollowUp(marker: String): Boolean =
            openGoals.any { it.operatingPolicy == marker }

        val failedReviewCandidate = state.reviewQueue
            .filter { it.companyId == companyId }
            .sortedByDescending { it.updatedAt }
            .mapNotNull { item ->
                if (item.status != ReviewQueueStatus.FAILED_CHECKS && item.status != ReviewQueueStatus.CHANGES_REQUESTED) {
                    return@mapNotNull null
                }
                val issue = issuesById[item.issueId] ?: return@mapNotNull null
                val parentGoal = state.goals.firstOrNull { it.id == issue.goalId }
                if (parentGoal?.operatingPolicy?.startsWith("auto-follow-up:") == true) {
                    return@mapNotNull null
                }
                val marker = "auto-follow-up:review:${item.id}"
                if (hasOpenFollowUp(marker)) return@mapNotNull null
                val reason = when (item.status) {
                    ReviewQueueStatus.FAILED_CHECKS -> "Review queue checks failed and require a remediation loop."
                    ReviewQueueStatus.CHANGES_REQUESTED -> "Review requested changes and requires a remediation loop."
                    else -> return@mapNotNull null
                }
                FollowUpCandidate(issue = issue, reason = reason, marker = marker)
            }
            .firstOrNull()

        val blockedIssueCandidate = state.issues
            .filter { it.companyId == companyId && it.status == IssueStatus.BLOCKED }
            .sortedByDescending { it.updatedAt }
            .mapNotNull { issue ->
                if (issue.id in activeTaskIssueIds) return@mapNotNull null
                val parentGoal = state.goals.firstOrNull { it.id == issue.goalId }
                if (parentGoal?.operatingPolicy?.startsWith("auto-follow-up:") == true) {
                    return@mapNotNull null
                }
                val marker = "auto-follow-up:issue:${issue.id}"
                if (hasOpenFollowUp(marker)) return@mapNotNull null
                FollowUpCandidate(
                    issue = issue,
                    reason = "Execution is blocked and needs a new CEO-managed remediation goal.",
                    marker = marker
                )
            }
            .firstOrNull()

        val candidate = failedReviewCandidate ?: blockedIssueCandidate ?: return null
        val parentGoal = state.goals.firstOrNull { it.id == candidate.issue.goalId }
        val title = "Resolve follow-up for \"${candidate.issue.title}\""
        val description = buildString {
            appendLine("CEO generated this goal automatically because follow-up work is required.")
            appendLine()
            appendLine("Company: ${company.name}")
            parentGoal?.let {
                appendLine("Parent goal: ${it.title}")
            }
            appendLine("Trigger issue: ${candidate.issue.title}")
            appendLine("Reason: ${candidate.reason}")
            appendLine()
            appendLine("Required outcome:")
            appendLine("- Unblock the affected work or satisfy the failed review signal.")
            appendLine("- Re-run validation and capture any residual risk.")
            appendLine("- Hand the result back to the CEO for another decision cycle.")
        }
        return createGoal(
            companyId = companyId,
            title = title,
            description = description,
            successMetrics = listOf(
                "The triggering issue is no longer blocked or failed in review.",
                "Validation is re-run and residual risks are documented."
            ),
            autonomyEnabled = true,
            priority = 1,
            operatingPolicy = candidate.marker,
            startRuntimeIfNeeded = false
        )
    }

    private fun buildIssueDescription(
        goal: CompanyGoal,
        assignment: AgentAssignmentPlan,
        sharedChecklist: List<String>
    ): String = buildString {
        appendLine("Goal: ${goal.title}")
        appendLine()
        appendLine("Role: ${assignment.role}")
        appendLine("Phase: ${assignment.phase}")
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
    }.trim()

    private fun buildIssueExecutionPrompt(
        state: DesktopAppState,
        issue: CompanyIssue,
        profile: OrgAgentProfile
    ): String {
        val company = state.companies.firstOrNull { it.id == issue.companyId }
        val goal = state.goals.firstOrNull { it.id == issue.goalId }
        val projectContext = state.projectContexts.firstOrNull { it.id == issue.projectContextId }
        val siblingIssues = state.issues
            .filter { it.goalId == issue.goalId }
            .filterNot { it.id == issue.id }
            .sortedBy { it.createdAt }
            .take(4)
        val memoryBundle = company?.let {
            buildExecutionMemoryBundle(
                state = state,
                company = it,
                projectContext = projectContext,
                goal = goal,
                issue = issue,
                profile = profile
            )
        }
        val completedExecutionRuns = state.issues
            .filter { it.goalId == issue.goalId && it.kind.equals("execution", ignoreCase = true) && it.status == IssueStatus.DONE }
            .sortedByDescending { it.updatedAt }
            .mapNotNull { completedIssue ->
                val completedTask = state.tasks
                    .filter { it.issueId == completedIssue.id }
                    .maxByOrNull { it.updatedAt }
                    ?: return@mapNotNull null
                val completedRun = state.runs
                    .filter { it.taskId == completedTask.id && it.status == AgentRunStatus.COMPLETED }
                    .maxByOrNull { it.updatedAt }
                    ?: return@mapNotNull null
                buildString {
                    append("- ${completedIssue.title}")
                    append(" [branch: ${completedRun.branchName}]")
                    completedRun.publish?.commitSha?.let { append(" [commit: ${it.take(7)}]") }
                }
            }
            .take(3)
        val completedExecutionEvidence = state.issues
            .filter { it.goalId == issue.goalId && it.kind.equals("execution", ignoreCase = true) && it.status == IssueStatus.DONE }
            .sortedByDescending { it.updatedAt }
            .mapNotNull { completedIssue ->
                val completedTask = state.tasks
                    .filter { it.issueId == completedIssue.id }
                    .maxByOrNull { it.updatedAt }
                    ?: return@mapNotNull null
                val completedRun = state.runs
                    .filter { it.taskId == completedTask.id && it.status == AgentRunStatus.COMPLETED }
                    .maxByOrNull { it.updatedAt }
                    ?: return@mapNotNull null
                buildString {
                    appendLine("- ${completedIssue.title}")
                    appendLine("  branch: ${completedRun.branchName}")
                    completedRun.publish?.commitSha?.let { appendLine("  commit: ${it.take(7)}") }
                    completedRun.output?.takeIf { it.isNotBlank() }?.let { output ->
                        appendLine("  execution summary: ${summarizeForPrompt(output, 280)}")
                    }
                }.trimEnd()
            }
            .take(3)
        val completedReviewEvidence = state.issues
            .filter { it.goalId == issue.goalId && it.kind.equals("review", ignoreCase = true) && it.status == IssueStatus.DONE }
            .sortedByDescending { it.updatedAt }
            .mapNotNull { reviewIssue ->
                val reviewTask = state.tasks
                    .filter { it.issueId == reviewIssue.id }
                    .maxByOrNull { it.updatedAt }
                    ?: return@mapNotNull null
                val reviewRun = state.runs
                    .filter { it.taskId == reviewTask.id && it.status == AgentRunStatus.COMPLETED }
                    .maxByOrNull { it.updatedAt }
                    ?: return@mapNotNull null
                buildString {
                    appendLine("- ${reviewIssue.title}")
                    reviewRun.output?.takeIf { it.isNotBlank() }?.let { output ->
                        appendLine("  review summary: ${summarizeForPrompt(output, 280)}")
                    }
                }.trimEnd()
            }
            .take(3)
        val promptBody = when (issue.kind.lowercase()) {
            "review" -> buildString {
                appendLine("Company review context")
                company?.let {
                    appendLine("- company: ${it.name}")
                    appendLine("- rootPath: ${it.rootPath}")
                    appendLine("- baseBranch: ${it.defaultBaseBranch}")
                }
                goal?.let { appendLine("- goal: ${it.title}") }
                appendLine("- assignedRole: ${profile.roleName}")
                appendLine()
                appendLine("Review task: ${issue.title}")
                if (completedExecutionRuns.isNotEmpty()) {
                    appendLine()
                    appendLine("Completed execution work to review:")
                    completedExecutionRuns.forEach(::appendLine)
                }
                if (completedExecutionEvidence.isNotEmpty()) {
                    appendLine()
                    appendLine("Execution evidence:")
                    completedExecutionEvidence.forEach(::appendLine)
                }
                if (issue.acceptanceCriteria.isNotEmpty()) {
                    appendLine()
                    appendLine("Acceptance criteria:")
                    issue.acceptanceCriteria.forEach { appendLine("- $it") }
                }
                appendLine()
                appendLine("Review instructions:")
                appendLine("- Review only the completed execution work listed above.")
                appendLine("- Use the execution summaries above as primary evidence. Only inspect the branch or commit directly if something is unclear.")
                appendLine("- Act as a direct reviewer, not as an orchestrator.")
                appendLine("- Do not create plans, spawn sub-agents, or perform broad repo exploration.")
                appendLine("- Keep the review tight: inspect only the listed execution result and produce one concise verdict.")
                appendLine("- Do not create commits, branches, pushes, or pull requests. Cotor will handle workflow publishing.")
                appendLine("- Do not modify repository files unless a tiny fix is absolutely required to unblock the flow.")
                appendLine("- No publish is required for a pure review step.")
                appendLine("- If the provided execution evidence is sufficient and you do not find a concrete defect, mark the work ready instead of asking for speculative follow-up.")
                appendLine("- Return a concise verdict, residual risks, and whether the goal is ready for approval.")
                memoryBundle?.let { memory ->
                    appendLine()
                    appendLine("Workflow memory:")
                    appendLine(summarizeForPrompt(memory.workflowMemory, 320))
                    appendLine()
                    appendLine("Agent memory:")
                    appendLine(summarizeForPrompt(memory.agentMemory, 220))
                }
            }
            "approval" -> buildString {
                appendLine("Company approval context")
                company?.let {
                    appendLine("- company: ${it.name}")
                    appendLine("- rootPath: ${it.rootPath}")
                    appendLine("- baseBranch: ${it.defaultBaseBranch}")
                }
                goal?.let { appendLine("- goal: ${it.title}") }
                appendLine("- assignedRole: ${profile.roleName}")
                appendLine()
                appendLine("Approval task: ${issue.title}")
                if (completedExecutionEvidence.isNotEmpty()) {
                    appendLine()
                    appendLine("Completed execution evidence:")
                    completedExecutionEvidence.forEach(::appendLine)
                }
                if (completedReviewEvidence.isNotEmpty()) {
                    appendLine()
                    appendLine("Completed review evidence:")
                    completedReviewEvidence.forEach(::appendLine)
                }
                if (issue.acceptanceCriteria.isNotEmpty()) {
                    appendLine()
                    appendLine("Acceptance criteria:")
                    issue.acceptanceCriteria.forEach { appendLine("- $it") }
                }
                appendLine()
                appendLine("Approval instructions:")
                appendLine("- Review the completed execution and review outcomes listed above.")
                appendLine("- Prefer the summaries above over broad repository exploration.")
                appendLine("- Act as a direct approver, not as an orchestrator.")
                appendLine("- Do not create plans, spawn sub-agents, or perform broad repo exploration.")
                appendLine("- Keep the approval tight and decide from the provided evidence unless a concrete inconsistency forces a deeper check.")
                appendLine("- Do not create commits, branches, pushes, or pull requests. Cotor will handle workflow publishing.")
                appendLine("- Do not modify repository files unless a tiny unblocker is required.")
                appendLine("- No publish is required for a pure approval step.")
                appendLine("- Return a final decision: ready, blocked, or needs follow-up, with one short rationale.")
                memoryBundle?.let { memory ->
                    appendLine()
                    appendLine("Workflow memory:")
                    appendLine(summarizeForPrompt(memory.workflowMemory, 320))
                    appendLine()
                    appendLine("Agent memory:")
                    appendLine(summarizeForPrompt(memory.agentMemory, 220))
                }
            }
            else -> buildString {
                appendLine("Company execution context")
                company?.let {
                    appendLine("- company: ${it.name}")
                    appendLine("- rootPath: ${it.rootPath}")
                    appendLine("- baseBranch: ${it.defaultBaseBranch}")
                }
                projectContext?.let {
                    appendLine("- projectContext: ${it.name}")
                }
                goal?.let {
                    appendLine("- goal: ${it.title}")
                    appendLine("- goalDescription: ${summarizeForPrompt(it.description, 240)}")
                }
                appendLine("- assignedRole: ${profile.roleName}")
                appendLine()
                appendLine("Task")
                appendLine("- Make the smallest complete repository change that satisfies this issue: ${issue.title}")
                appendLine("- Act as a direct worker. Do not act like a planner or orchestrator.")
                appendLine("- Start editing immediately. Do not spend time on broad repo exploration.")
                appendLine("- Do not create plans, spawn sub-agents, or do broad multi-step analysis.")
                appendLine("- If the repository is tiny or has no obvious product surface, prefer editing README.md or adding one small text/script artifact.")
                appendLine("- Run one targeted validation command that proves the change works, then stop.")
                if (siblingIssues.isNotEmpty()) {
                    appendLine()
                    appendLine("Parallel issues in the same goal:")
                    siblingIssues.forEach { sibling ->
                        appendLine("- ${sibling.title} [${sibling.status}]")
                    }
                }
                appendLine()
                appendLine("Rules")
                appendLine("- Work in the assigned isolated branch and worktree.")
                appendLine("- Do not create commits, branches, pushes, or pull requests. Cotor will publish after you finish.")
                appendLine("- Keep the change minimal and focused on this issue.")
                if (issue.acceptanceCriteria.isNotEmpty()) {
                    appendLine()
                    appendLine("Done when:")
                    issue.acceptanceCriteria.forEach { appendLine("- $it") }
                }
                memoryBundle?.let { memory ->
                    appendLine()
                    appendLine("Company memory:")
                    appendLine(summarizeForPrompt(memory.companyMemory, 180))
                    appendLine()
                    appendLine("Workflow memory:")
                    appendLine(summarizeForPrompt(memory.workflowMemory, 200))
                    appendLine()
                    appendLine("Agent memory:")
                    appendLine(summarizeForPrompt(memory.agentMemory, 140))
                }
            }
        }
        return promptBody.trim()
    }

    private data class ExecutionMemoryBundle(
        val companyMemory: String,
        val workflowMemory: String,
        val agentMemory: String
    )

    private fun buildExecutionMemoryBundle(
        state: DesktopAppState,
        company: Company,
        projectContext: CompanyProjectContext?,
        goal: CompanyGoal?,
        issue: CompanyIssue,
        profile: OrgAgentProfile
    ): ExecutionMemoryBundle {
        val companyGoals = state.goals
            .filter { it.companyId == company.id && it.status != GoalStatus.COMPLETED }
            .sortedByDescending { it.updatedAt }
            .take(3)
        val goalIssues = state.issues
            .filter { it.goalId == goal?.id }
            .sortedByDescending { it.updatedAt }
            .take(5)
        val recentActivity = state.companyActivity
            .filter { it.companyId == company.id }
            .sortedByDescending { it.createdAt }
            .take(5)
        val recentDecisions = state.goalDecisions
            .filter { it.companyId == company.id }
            .sortedByDescending { it.createdAt }
            .take(3)
        val assignedIssues = state.issues
            .filter { it.companyId == company.id && it.assigneeProfileId == profile.id }
            .sortedByDescending { it.updatedAt }
            .take(3)
        val collaboratorIds = state.companyAgentDefinitions
            .firstOrNull { it.companyId == company.id && it.id == profile.id }
            ?.preferredCollaboratorIds
            .orEmpty()
        val collaboratorNames = state.companyAgentDefinitions
            .filter { it.companyId == company.id && it.id in collaboratorIds }
            .map { it.title }

        return ExecutionMemoryBundle(
            companyMemory = buildString {
                appendLine("company=${company.name}")
                appendLine("rootPath=${company.rootPath}")
                appendLine("defaultBaseBranch=${company.defaultBaseBranch}")
                appendLine("activeGoals=${companyGoals.joinToString { it.title }}")
                appendLine(
                    "recentActivity=${
                        recentActivity.joinToString(" | ") { "${it.title}${it.detail?.let { detail -> ": $detail" } ?: ""}" }
                    }"
                )
            }.trim(),
            workflowMemory = buildString {
                projectContext?.let { appendLine("projectContext=${it.name}") }
                goal?.let {
                    appendLine("goal=${it.title}")
                    appendLine("goalDescription=${it.description.lineSequence().firstOrNull().orEmpty()}")
                }
                appendLine("issue=${issue.title}")
                appendLine("issueStatus=${issue.status}")
                appendLine(
                    "goalIssues=${
                        goalIssues.joinToString(" | ") { "${it.title} [${it.status}]" }
                    }"
                )
                appendLine(
                    "recentDecisions=${
                        recentDecisions.joinToString(" | ") { it.summary }
                    }"
                )
            }.trim(),
            agentMemory = buildString {
                appendLine("role=${profile.roleName}")
                appendLine("agentCli=${profile.executionAgentName}")
                appendLine("capabilities=${profile.capabilities.joinToString()}")
                appendLine(
                    "assignedIssues=${
                        assignedIssues.joinToString(" | ") { "${it.title} [${it.status}]" }
                    }"
                )
                if (collaboratorNames.isNotEmpty()) {
                    appendLine("preferredCollaborators=${collaboratorNames.joinToString()}")
                }
            }.trim()
        )
    }

    private fun summarizeForPrompt(text: String, maxChars: Int): String {
        val compact = text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return if (compact.length <= maxChars) compact else compact.take(maxChars).trimEnd() + "…"
    }

    private fun readMemoryFile(path: Path): String =
        runCatching { Files.readString(path).trim() }
            .getOrElse { "" }
            .ifBlank { "No persistent memory recorded yet." }

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
                val descriptor = listOf(
                    role,
                    definition.roleSummary,
                    definition.specialties.joinToString(" ")
                ).joinToString(" ").lowercase()
                val descriptorTokens = descriptor
                    .split(Regex("[^a-z0-9]+"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                val capabilities = mutableSetOf<String>()
                fun addIfMatch(capability: String, vararg tokens: String) {
                    if (tokens.any { token ->
                            descriptorTokens.any { candidate ->
                                candidate == token || candidate.startsWith(token)
                            }
                        }) {
                        capabilities += capability
                    }
                }
                addIfMatch("planning", "ceo", "chief", "lead", "strategy", "planner", "planning", "manager", "director")
                addIfMatch("triage", "triage", "coordination", "orchestr", "assign", "delegate")
                addIfMatch("goal-decomposition", "plan", "roadmap", "spec", "product", "breakdown")
                addIfMatch("review", "review", "approve", "approval")
                addIfMatch("qa", "qa", "quality", "test", "verification", "validate")
                addIfMatch("implementation", "build", "builder", "implement", "ship", "delivery", "developer", "engineer")
                addIfMatch("backend", "backend", "server", "api", "kotlin", "infra", "platform")
                addIfMatch("frontend", "frontend", "ui", "ux", "design", "desktop", "swift")
                addIfMatch("integration", "integration", "merge", "release", "ops")
                if (capabilities.isEmpty()) {
                    capabilities += listOf("implementation", "integration")
                }
                val capabilityList = capabilities.toList()
                val isChief = capabilityList.any { it == "planning" || it == "triage" } || role.equals("CEO", ignoreCase = true)
                val isQa = capabilityList.any { it == "qa" || it == "review" }
                OrgAgentProfile(
                    id = definition.id,
                    companyId = definition.companyId,
                    roleName = role,
                    executionAgentName = definition.agentCli,
                    capabilities = capabilityList,
                    reviewerPolicy = if (isQa) "review-queue" else null,
                    mergeAuthority = isChief
                )
            }
    }

    private fun seedCompanyAgentDefinitions(companyId: String, now: Long): List<CompanyAgentDefinition> {
        val builtins = BuiltinAgentCatalog.names()
        val preferredAgent = listOf("codex", "claude", "gemini", "opencode", "qwen")
            .firstOrNull { candidate ->
                builtins.any { it.equals(candidate, ignoreCase = true) } && isExecutableAvailable(candidate)
            }
            ?: builtins.firstOrNull { it.equals("codex", ignoreCase = true) }
            ?: builtins.firstOrNull { isExecutableAvailable(it) }
            ?: builtins.firstOrNull()
            ?: "codex"
        return listOf("CEO", "Builder", "QA").mapIndexed { index, roleTitle ->
            val roleSummary = when (index) {
                0 -> "lead strategy, planning, triage, final merge"
                1 -> "implementation, integration, delivery"
                else -> "qa, review, verification"
            }
            CompanyAgentDefinition(
                id = UUID.randomUUID().toString(),
                companyId = companyId,
                title = roleTitle,
                agentCli = preferredAgent,
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

    private fun suggestProfileForCustomIssue(
        title: String,
        description: String,
        kind: String,
        profiles: List<OrgAgentProfile>
    ): OrgAgentProfile? {
        if (profiles.isEmpty()) {
            return null
        }
        val haystack = listOf(title, description, kind).joinToString(" ").lowercase()
        return profiles.maxByOrNull { profile ->
            val capabilityHits = profile.capabilities.count { capability ->
                capability.isNotBlank() && haystack.contains(capability.lowercase())
            }
            capabilityHits + when {
                haystack.contains("review") && profile.reviewerPolicy != null -> 2
                haystack.contains("qa") && profile.roleName.lowercase().contains("qa") -> 2
                else -> 0
            }
        }?.takeIf { it.enabled } ?: profiles.firstOrNull { it.enabled }
    }

    private fun buildPlanningParticipants(
        companyId: String,
        profiles: List<OrgAgentProfile>,
        definitions: List<CompanyAgentDefinition>
    ): List<GoalDrivenTaskPlanner.PlanningParticipant> {
        val companyProfiles = profiles.filter { it.companyId == companyId && it.enabled }
        val companyDefinitions = definitions
            .filter { it.companyId == companyId && it.enabled }
            .sortedWith(compareBy<CompanyAgentDefinition> { it.displayOrder }.thenBy { it.title.lowercase() })

        if (companyDefinitions.isNotEmpty()) {
            return companyDefinitions.map { definition ->
                val profile = companyProfiles.firstOrNull { it.id == definition.id }
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = definition.id,
                    agentName = definition.agentCli.trim().lowercase(),
                    title = definition.title,
                    roleSummary = definition.roleSummary,
                    specialties = definition.specialties,
                    collaborationInstructions = definition.collaborationInstructions,
                    preferredCollaborators = definition.preferredCollaboratorIds.mapNotNull { collaboratorId ->
                        companyDefinitions.firstOrNull { it.id == collaboratorId }?.title
                    },
                    memoryNotes = definition.memoryNotes,
                    capabilities = profile?.capabilities ?: emptyList(),
                    mergeAuthority = profile?.mergeAuthority == true
                )
            }
        }

        return companyProfiles.map { profile ->
            GoalDrivenTaskPlanner.PlanningParticipant(
                participantId = profile.id,
                agentName = profile.executionAgentName.trim().lowercase(),
                title = profile.roleName,
                roleSummary = profile.capabilities.joinToString(", "),
                specialties = profile.capabilities,
                capabilities = profile.capabilities,
                mergeAuthority = profile.mergeAuthority
            )
        }
    }

    private fun commandBackedAgentConfig(name: String): AgentConfig? {
        val resolvedExecutable = resolveExecutablePath(name)?.toString() ?: return null
        val normalizedName = name.trim().lowercase()
        return AgentConfig(
            name = normalizedName,
            pluginClass = "com.cotor.data.plugin.CommandPlugin",
            parameters = mapOf(
                "argvJson" to """["$resolvedExecutable","{input}"]"""
            ),
            timeout = 15 * 60_000L,
            tags = listOf("desktop-roster", "command")
        )
    }

    private fun isExecutableAvailable(command: String): Boolean {
        if (command.equals("echo", ignoreCase = true)) {
            return true
        }
        return commandAvailability(command)
    }

    private fun preferredExecutableAgent(): String {
        val builtins = BuiltinAgentCatalog.names()
        return builtins.firstOrNull { isExecutableAvailable(it) }
            ?: builtins.firstOrNull()
            ?: "echo"
    }

    private fun ensureCompanyWorkspace(state: DesktopAppState, company: Company, now: Long): Pair<DesktopAppState, Workspace> {
        state.workspaces.firstOrNull { it.repositoryId == company.repositoryId && it.baseBranch == company.defaultBaseBranch }
            ?.let { return state to it }
        val repositoryRoot = Path.of(company.rootPath).toAbsolutePath().normalize()
        val repository = state.repositories.firstOrNull { it.id == company.repositoryId }
            ?: state.repositories.firstOrNull { sameRepositoryRoot(it.localPath, repositoryRoot) }
            ?: ManagedRepository(
                id = UUID.randomUUID().toString(),
                name = repositoryRoot.fileName?.toString().orEmpty().ifBlank { company.name },
                localPath = repositoryRoot.toString(),
                sourceKind = RepositorySourceKind.LOCAL,
                remoteUrl = null,
                defaultBranch = company.defaultBaseBranch,
                createdAt = now,
                updatedAt = now
            )
        val nextCompanies = state.companies.map {
            if (it.id == company.id) {
                it.copy(repositoryId = repository.id, updatedAt = now)
            } else {
                it
            }
        }
        val workspace = Workspace(
            id = UUID.randomUUID().toString(),
            repositoryId = repository.id,
            name = "${company.name} · ${company.defaultBaseBranch}",
            baseBranch = company.defaultBaseBranch,
            createdAt = now,
            updatedAt = now
        )
        val nextState = state.copy(
            repositories = mergeRepository(state.repositories, repository),
            companies = nextCompanies,
            workspaces = state.workspaces + workspace
        )
        return nextState to workspace
    }

    private fun ensureLatestRepositoryWorkspace(state: DesktopAppState, now: Long): Pair<DesktopAppState, Workspace?> {
        val existingWorkspace = state.workspaces.maxByOrNull { it.updatedAt }
        if (existingWorkspace != null) {
            return state to existingWorkspace
        }
        val repository = state.repositories.maxByOrNull { it.updatedAt } ?: return state to null
        val workspace = Workspace(
            id = UUID.randomUUID().toString(),
            repositoryId = repository.id,
            name = "${repository.name} · ${repository.defaultBranch}",
            baseBranch = repository.defaultBranch,
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
        var runtimeContinuationCompanyId: String? = null
        val snapshot = stateStore.load()
        val task = snapshot.tasks.firstOrNull { it.id == taskId } ?: return
        val issueId = task.issueId ?: return
        val latestRuns = snapshot.runs.filter { it.taskId == taskId }
        val primaryRun = latestRuns.firstOrNull { it.publish?.pullRequestUrl != null } ?: latestRuns.firstOrNull()
        val now = System.currentTimeMillis()
        stateMutex.withLock {
            val state = stateStore.load()
            val issue = state.issues.firstOrNull { it.id == issueId } ?: return@withLock
            val nextIssueStatus = when {
                finalStatus == DesktopTaskStatus.COMPLETED && primaryRun?.publish?.pullRequestUrl != null -> IssueStatus.IN_REVIEW
                finalStatus == DesktopTaskStatus.COMPLETED -> IssueStatus.DONE
                else -> IssueStatus.BLOCKED
            }
            val updatedIssue = issue.copy(status = nextIssueStatus, updatedAt = now)
            val queueStatus = when {
                primaryRun?.publish?.error != null -> ReviewQueueStatus.FAILED_CHECKS
                primaryRun?.publish?.pullRequestUrl != null -> ReviewQueueStatus.READY_TO_MERGE
                else -> ReviewQueueStatus.AWAITING_QA
            }
            val nextReviewQueue = if (primaryRun?.publish?.pullRequestUrl != null || primaryRun?.publish?.pullRequestNumber != null) {
                val existing = state.reviewQueue.firstOrNull { it.issueId == issueId }
                val queueItem = ReviewQueueItem(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    companyId = issue.companyId,
                    projectContextId = issue.projectContextId,
                    issueId = issueId,
                    runId = primaryRun?.id ?: "",
                    pullRequestNumber = primaryRun?.publish?.pullRequestNumber,
                    pullRequestUrl = primaryRun?.publish?.pullRequestUrl,
                    status = queueStatus,
                    checksSummary = primaryRun?.publish?.checksSummary ?: primaryRun?.error,
                    mergeability = primaryRun?.publish?.mergeability,
                    requestedReviewers = primaryRun?.publish?.requestedReviewers ?: emptyList(),
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
            val runtime = nextState.companyRuntimes.firstOrNull { it.companyId == updatedIssue.companyId }
            if (runtime?.status == CompanyRuntimeStatus.RUNNING) {
                runtimeContinuationCompanyId = updatedIssue.companyId
            }
        }
        val syncComment = buildString {
            append("Cotor finished task \"${task.title}\" with ${finalStatus.name}.")
            primaryRun?.publish?.pullRequestUrl?.let { append("\nPR: $it") }
            primaryRun?.error?.takeIf { it.isNotBlank() }?.let { append("\nError: $it") }
        }
        mirrorIssueToLinear(issueId, syncComment)
        runtimeContinuationCompanyId?.let { companyId ->
            serviceScope.launch {
                runCatching { runCompanyRuntimeTick(companyId) }
            }
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
            val company = companies.firstOrNull { it.id == companyId }
            val backendKind = company?.backendKind ?: backendSettings.defaultBackendKind
            val backendStatus = companyBackendStatus(companyId, this)
            existing.copy(
                companyId = companyId,
                backendKind = backendKind,
                backendHealth = backendStatus?.health ?: "unknown",
                backendMessage = backendStatus?.message,
                backendLifecycleState = backendStatus.lifecycleState,
                backendPid = backendStatus.pid,
                backendPort = backendStatus.port,
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
    return resolveExecutablePath(command) != null
}
