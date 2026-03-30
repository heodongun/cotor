package com.cotor.app

/**
 * File overview for DesktopAppService.
 *
 * This file belongs to the app layer for the desktop shell and localhost app-server surface.
 * It groups the service entrypoints, helper payloads, and runtime coordination logic that back the
 * desktop product's company, task, review, and automation workflows.
 * Read here first when tracing how desktop-facing state moves from repository/workspace setup into
 * goals, issues, tasks, runs, and long-lived company runtime loops.
 */


import com.cotor.data.config.ConfigRepository
import com.cotor.data.process.resolveExecutablePath
import com.cotor.domain.executor.AgentExecutor
import com.cotor.domain.planning.GoalDrivenTaskPlanner
import com.cotor.integrations.linear.DefaultLinearTrackerAdapter
import com.cotor.integrations.linear.LinearTrackerAdapter
import com.cotor.model.AgentConfig
import com.cotor.model.AgentExecutionMetadata
import com.cotor.model.AgentResult
import com.cotor.model.CodexDefaults
import com.cotor.model.ProcessExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists

@kotlinx.serialization.Serializable
private data class CompanyAutomationTraceEvent(
    val timestamp: Long,
    val companyId: String,
    val projectContextId: String? = null,
    val goalId: String? = null,
    val issueId: String,
    val issueTitle: String,
    val issueKind: String,
    val oldStatus: IssueStatus,
    val newStatus: IssueStatus,
    val source: String,
    val reason: String,
    val operatingPolicy: String? = null,
    val latestTaskId: String? = null,
    val latestTaskStatus: String? = null,
    val latestTaskUpdatedAt: Long? = null,
    val latestRunId: String? = null,
    val latestRunStatus: String? = null,
    val latestRunUpdatedAt: Long? = null,
    val retryEligible: Boolean? = null,
    val runErrorSnippet: String? = null
)

@kotlinx.serialization.Serializable
private data class CeoPlanningPayload(
    val goalSummary: String,
    val issues: List<CeoPlannedIssue> = emptyList()
)

@kotlinx.serialization.Serializable
private data class CeoPlannedIssue(
    val refId: String,
    val title: String,
    val description: String,
    val kind: String = "execution",
    val assigneeRole: String,
    val priority: Int = 3,
    val codeProducing: Boolean? = null,
    val dependsOn: List<String> = emptyList(),
    val acceptanceCriteria: List<String> = emptyList(),
    val reviewRequired: Boolean = true,
    val approvalRequired: Boolean = true
)

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
    private val codexAppServerManager: CodexAppServerManager = CodexAppServerManager(),
    private val staleRunStartupGraceMs: Long = 15_000L,
    private val runHeartbeatIntervalMs: Long = 5_000L
) {
    companion object {
        private const val COMPANY_TRACE_DEDUP_WINDOW_MS = 30_000L
        private const val COMPANY_TRACE_ROTATE_BYTES = 8L * 1024L * 1024L
        private const val COMPANY_RUNTIME_ERROR_ROTATE_BYTES = 2L * 1024L * 1024L
        private const val RECOVERABLE_RETRY_BASE_DELAY_MS = 30_000L
        private const val RECOVERABLE_RETRY_MAX_DELAY_MS = 5L * 60_000L
        private const val RECOVERABLE_RETRY_MAX_ATTEMPTS = 3
        private const val SUPERSEDED_PR_RECONCILIATION_INTERVAL_MS = 5L * 60_000L
        private const val CEO_PLANNING_SOURCE = "ceo-planning"
        private const val QA_REVIEW_SOURCE_PREFIX = "qa-review:"
        private const val CEO_APPROVAL_SOURCE_PREFIX = "ceo-approval:"
        private const val INTERRUPTED_RUN_ERROR =
            "Execution was interrupted because the app-server stopped before the run finished."
        private const val INTERRUPTED_ISSUE_REASON =
            "Runtime stopped while execution was in progress; the issue was returned to the queue."
    }

    // Reads are cheap and frequent, but writes must be serialized so the state file
    // cannot be partially overwritten when multiple agent runs finish at once.
    private val stateMutex = Mutex()
    private val companyRuntimeTickMutexes = mutableMapOf<String, Mutex>()

    // Runs execute in background coroutines so the API can return immediately after
    // the user presses "Run Task" in the desktop client.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val companyRuntimeJobs: MutableMap<String, Job> = linkedMapOf()
    private val automationRefreshJobs: MutableMap<String, Job> = linkedMapOf()
    private val activeTaskJobs: MutableMap<String, Job> = ConcurrentHashMap()
    private val intentionallyInterruptedTaskIds = ConcurrentHashMap.newKeySet<String>()
    private val recentCompanyAutomationTraceKeys = ConcurrentHashMap<String, Long>()
    private val recentSupersededPullRequestCleanupAt = ConcurrentHashMap<String, Long>()
    private val companyEventStream = MutableSharedFlow<CompanyEventEnvelope>(replay = 0, extraBufferCapacity = 64)
    private val backendJson = Json { ignoreUnknownKeys = true }
    private val localExecutionBackend = LocalCotorBackend(agentExecutor)
    private val codexAppServerBackend = CodexAppServerBackend(backendJson)

    private enum class RecoverableRetryMode {
        NONE,
        WAITING,
        READY,
        EXHAUSTED
    }

    private data class RecoverableRetryDecision(
        val mode: RecoverableRetryMode,
        val consecutiveFailures: Int = 0,
        val retryAt: Long? = null
    ) {
        val canAutoRetry: Boolean
            get() = mode == RecoverableRetryMode.WAITING || mode == RecoverableRetryMode.READY
    }

    init {
        // Runtime state is persisted across app-server restarts. Reattach loops eagerly
        // on service startup so companies keep progressing even before the UI polls.
        queueAutomationRefresh()
    }

    fun shutdown() {
        runBlocking {
            interruptActiveTasksForShutdown()
        }
        activeTaskJobs.values.forEach { it.cancel() }
        activeTaskJobs.clear()
        companyRuntimeJobs.values.forEach { it.cancel() }
        companyRuntimeJobs.clear()
        automationRefreshJobs.values.forEach { it.cancel() }
        automationRefreshJobs.clear()
        codexAppServerManager.stopAll()
        serviceScope.cancel()
    }

    suspend fun dashboard(): DashboardResponse {
        // Every dashboard read is also a chance to heal background loops after an app-server
        // restart. The read path stays cheap, but it nudges the automation layer back into the
        // expected steady state before serializing the full desktop snapshot.
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
        if (companyId == null) {
            migrateLegacyCompanyRosters(companyId)
            queueAutomationRefresh(companyId)
        } else {
            prepareCompanyAutomationState(companyId)
        }
        val state = stateStore.load().withDerivedMetrics()
        return companyDashboardSnapshot(state, companyId)
    }

    private fun companyDashboardSnapshot(
        state: DesktopAppState,
        companyId: String? = null
    ): CompanyDashboardResponse {
        val filteredGoals = state.goals
            .filter { companyId == null || it.companyId == companyId }
            .sortedByDescending { it.updatedAt }
        val filteredIssues = state.issues
            .filter { companyId == null || it.companyId == companyId }
            .sortedByDescending { it.updatedAt }
        val filteredIssueIds = filteredIssues.map { it.id }.toSet()
        val filteredTasks = state.tasks
            .filter { task ->
                val issueId = task.issueId
                issueId != null && issueId in filteredIssueIds
            }
            .sortedByDescending { it.updatedAt }
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
            tasks = filteredTasks,
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

    suspend fun listWorkflowTopologies(companyId: String? = null): List<WorkflowTopologySnapshot> {
        queueAutomationRefresh(companyId)
        val state = stateStore.load().withDerivedMetrics()
        val orgProfiles = ensureOrgProfiles(state.orgProfiles, state.companyAgentDefinitions, state.companies)
        return computeWorkflowTopologies(state, orgProfiles)
            .filter { companyId == null || it.companyId == companyId }
    }

    suspend fun listGoalDecisions(companyId: String? = null): List<GoalOrchestrationDecision> {
        queueAutomationRefresh(companyId)
        return stateStore.load().goalDecisions
            .filter { companyId == null || it.companyId == companyId }
            .sortedByDescending { it.createdAt }
    }

    private fun queueAutomationRefresh(companyId: String? = null) {
        if (companyId != null && automationRefreshJobs["__all__"]?.isActive == true) {
            return
        }
        val key = companyId ?: "__all__"
        if (automationRefreshJobs[key]?.isActive == true) {
            return
        }
        automationRefreshJobs[key] = serviceScope.launch {
            try {
                prepareCompanyAutomationState(companyId)
            } catch (cause: Throwable) {
                companyId?.let { markCompanyRuntimeError(it, cause) }
                throw cause
            } finally {
                automationRefreshJobs.remove(key)
            }
        }
    }

    fun companyEvents(companyId: String): Flow<CompanyEventEnvelope> =
        companyEventStream.filter { it.event.companyId == companyId }

    private suspend fun prepareCompanyAutomationState(companyId: String? = null) {
        migrateLegacyCompanyRosters(companyId)
        migrateLegacyFollowUpGoals(companyId)
        resumeRunningCompanyRuntimes(companyId)
        ensureAutonomousCompanyRuntimes(companyId)
        reconcileStaleAgentRuns(companyId)
        val targetCompanyIds = stateStore.load().companies
            .map { it.id }
            .filter { companyId == null || it == companyId }
        targetCompanyIds.forEach { activeCompanyId ->
            normalizeCompanyAutomationState(activeCompanyId)
            repairWorkflowLineages(activeCompanyId)
            reconcileNonPublishingReviewRuns(activeCompanyId)
            reconcileTerminalIssueStates(activeCompanyId)
            archiveRecursiveFollowUpGoals(activeCompanyId)
            reopenResolvedMergeConflictIssues(activeCompanyId)
            reopenNoOpPullRequestExecutionIssues(activeCompanyId)
            requeueRecoverableBlockedIssues(activeCompanyId)
            requeueLegacyMergeConflictExecutionIssues(activeCompanyId)
            reconcileSupersededManagedPullRequests(activeCompanyId)
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

    private suspend fun migrateLegacyFollowUpGoals(companyId: String? = null) {
        val traceEvents = mutableListOf<CompanyAutomationTraceEvent>()
        stateMutex.withLock {
            val state = stateStore.load()
            val issuesById = state.issues.associateBy { it.id }
            val goalsById = state.goals.associateBy { it.id }
            val reviewQueueById = state.reviewQueue.associateBy { it.id }
            val now = System.currentTimeMillis()
            var changed = false

            val canonicalGoals = state.goals.map { goal ->
                if ((companyId != null && goal.companyId != companyId) || !goal.operatingPolicy.orEmpty().startsWith("auto-follow-up:")) {
                    return@map goal
                }
                val rootGoalId = resolveFollowUpRootGoalId(goal.id, issuesById, goalsById, reviewQueueById)
                    ?: return@map goal
                val canonicalPolicy = "auto-follow-up:goal:$rootGoalId"
                val canonicalTitle = generatedFollowUpSubject(goal.title)
                    ?.let(::canonicalFollowUpSubject)
                    ?.let { subject -> "Resolve follow-up for \"$subject\"" }
                    ?: goal.title
                val inferredFollowUpContext = goal.followUpContext ?: inferLegacyFollowUpContext(
                    state = state,
                    goal = goal,
                    rootGoalId = rootGoalId,
                    issuesById = issuesById,
                    goalsById = goalsById,
                    reviewQueueById = reviewQueueById
                )
                if (
                    goal.operatingPolicy == canonicalPolicy &&
                    goal.title == canonicalTitle &&
                    goal.followUpContext == inferredFollowUpContext
                ) {
                    return@map goal
                }
                changed = true
                goal.copy(
                    operatingPolicy = canonicalPolicy,
                    title = canonicalTitle,
                    followUpContext = inferredFollowUpContext,
                    updatedAt = now
                )
            }

            val canonicalGoalsById = canonicalGoals.associateBy { it.id }
            val activeGoalIdsWithTasks = state.tasks
                .filter { it.status == DesktopTaskStatus.RUNNING || it.status == DesktopTaskStatus.QUEUED }
                .mapNotNull { task -> task.issueId?.let { issuesById[it]?.goalId } }
                .toSet()
            val duplicateGoalIds = canonicalGoals
                .filter { goal ->
                    (companyId == null || goal.companyId == companyId) &&
                        goal.status == GoalStatus.ACTIVE &&
                        goal.operatingPolicy.orEmpty().startsWith("auto-follow-up:")
                }
                .groupBy { resolveFollowUpRootGoalId(it.id, issuesById, canonicalGoalsById, reviewQueueById) ?: canonicalFollowUpSubject(it.title) }
                .values
                .flatMap { goalsForLineage ->
                    if (goalsForLineage.size <= 1) {
                        emptyList()
                    } else {
                        val keepGoal = goalsForLineage.maxWithOrNull(
                            compareBy<CompanyGoal>(
                                { if (it.id in activeGoalIdsWithTasks) 1 else 0 },
                                { it.updatedAt }
                            )
                        )
                        goalsForLineage
                            .filterNot { it.id == keepGoal?.id || it.id in activeGoalIdsWithTasks }
                            .map { it.id }
                    }
                }
                .toSet()
            if (duplicateGoalIds.isNotEmpty()) {
                changed = true
            }

            val nextIssues = state.issues.map { issue ->
                if (companyId != null && issue.companyId != companyId) {
                    return@map issue
                }
                val goal = canonicalGoalsById[issue.goalId]
                val inferredExecutionIntent = if (issue.executionIntent == null) {
                    inferExecutionIntent(
                        kind = issue.kind,
                        title = issue.title,
                        description = issue.description,
                        plannedCodeProducing = issue.codeProducing
                    )
                } else {
                    issue.executionIntent
                }
                val normalizedCodeProducing = when (inferredExecutionIntent) {
                    ExecutionIntent.VALIDATION_ONLY, ExecutionIntent.PR_REUSE_HANDOFF -> false
                    ExecutionIntent.CODE_CHANGE, ExecutionIntent.MERGE_CONFLICT_REMEDIATION -> true
                    null -> issue.codeProducing
                }
                val normalizedIssue = issue.copy(
                    executionIntent = inferredExecutionIntent,
                    codeProducing = normalizedCodeProducing
                )
                if (normalizedIssue == issue) {
                    issue
                } else {
                    changed = true
                    normalizedIssue.copy(updatedAt = now)
                }
            }

            if (!changed) {
                return@withLock
            }

            val migratedIssues = nextIssues.map { issue ->
                if (issue.goalId !in duplicateGoalIds || issue.status == IssueStatus.DONE || issue.status == IssueStatus.CANCELED) {
                    issue
                } else {
                    traceEvents += CompanyAutomationTraceEvent(
                        timestamp = now,
                        companyId = issue.companyId,
                        projectContextId = issue.projectContextId,
                        goalId = issue.goalId,
                        issueId = issue.id,
                        issueTitle = issue.title,
                        issueKind = issue.kind,
                        oldStatus = issue.status,
                        newStatus = IssueStatus.CANCELED,
                        source = "migrateLegacyFollowUpGoals",
                        reason = "Canceled a duplicate historical follow-up goal during root-goal lineage migration.",
                        operatingPolicy = canonicalGoalsById[issue.goalId]?.operatingPolicy
                    )
                    issue.copy(
                        status = IssueStatus.CANCELED,
                        updatedAt = now
                    )
                }
            }
            val nextGoals = canonicalGoals.map { goal ->
                if (goal.id !in duplicateGoalIds) {
                    goal
                } else {
                    goal.copy(
                        status = GoalStatus.COMPLETED,
                        updatedAt = now
                    )
                }
            }
            val changedCompanies = nextGoals
                .filter { goal ->
                    val previousGoal = goalsById[goal.id]
                    previousGoal != null &&
                        (
                            previousGoal.operatingPolicy != goal.operatingPolicy ||
                                previousGoal.title != goal.title ||
                                previousGoal.status != goal.status ||
                                previousGoal.followUpContext != goal.followUpContext
                            )
                }
                .map { it.companyId }
                .distinct()
            var nextState = state.copy(
                goals = nextGoals,
                issues = migratedIssues
            )
            val detailParts = buildList {
                if (canonicalGoals != state.goals) add("canonicalized follow-up markers")
                if (migratedIssues != state.issues) add("backfilled legacy follow-up execution metadata")
                if (duplicateGoalIds.isNotEmpty()) add("collapsed ${duplicateGoalIds.size} duplicate follow-up goals")
            }
            changedCompanies.forEach { changedCompanyId ->
                nextState = nextState.recordCompanyActivity(
                    companyId = changedCompanyId,
                    source = "company-runtime",
                    title = "Migrated legacy follow-up state",
                    detail = detailParts.joinToString(", ")
                )
            }
            nextState = nextState.withDerivedMetrics()
            stateStore.save(nextState)
        }
        traceEvents.forEach(::appendCompanyAutomationTrace)
    }

    private suspend fun resumeRunningCompanyRuntimes(companyId: String? = null) {
        val runningCompanyIds = stateStore.load().companyRuntimes
            .filter { it.status == CompanyRuntimeStatus.RUNNING }
            .map { it.companyId }
            .filterNotNull()
            .filter { companyId == null || it == companyId }
        runningCompanyIds.forEach { runningCompanyId ->
            val preResumeState = stateStore.load()
            val shouldForceResumeTick = hasQueuedCompanyIssues(preResumeState, runningCompanyId) &&
                !hasActiveCompanyTasks(preResumeState, runningCompanyId)
            val queuedIssueCount = queuedCompanyIssueCount(preResumeState, runningCompanyId)
            val runtimeLoopActive = companyRuntimeJobs[runningCompanyId]?.isActive == true
            val shouldRecordResume = queuedIssueCount > 0 && (!runtimeLoopActive || shouldForceResumeTick)
            if (!runtimeLoopActive) {
                runCatching { startCompanyBackend(runningCompanyId) }
                    .onFailure { cause -> markCompanyRuntimeError(runningCompanyId, cause) }
                ensureCompanyRuntimeLoop(runningCompanyId)
            }
            if (shouldForceResumeTick) {
                markRuntimeTickHeartbeat(runningCompanyId, "recovering-after-app-server-shutdown")
            }
            if (!runtimeLoopActive || shouldForceResumeTick) {
                runCatching { runCompanyRuntimeTick(runningCompanyId) }
                    .onFailure { cause -> markCompanyRuntimeError(runningCompanyId, cause) }
                if (shouldRecordResume) {
                    recordCompanyRuntimeResume(runningCompanyId, queuedIssueCount)
                }
            }
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
                val runtime = state.companyRuntimes.firstOrNull { it.companyId == activeCompanyId }
                runtime?.status != CompanyRuntimeStatus.RUNNING && runtime?.manuallyStoppedAt == null
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

    private suspend fun interruptActiveTasksForShutdown() {
        val traceEvents = mutableListOf<CompanyAutomationTraceEvent>()
        stateMutex.withLock {
            val state = stateStore.load()
            val now = System.currentTimeMillis()
            val issuesById = state.issues.associateBy { it.id }
            val goalsById = state.goals.associateBy { it.id }
            val activeTasks = state.tasks.filter {
                it.status == DesktopTaskStatus.RUNNING || it.status == DesktopTaskStatus.QUEUED
            }
            if (activeTasks.isEmpty()) {
                return@withLock
            }

            val interruptedTaskIds = activeTasks.mapTo(linkedSetOf()) { it.id }
            intentionallyInterruptedTaskIds.addAll(interruptedTaskIds)

            val updatedRuns = state.runs.map { run ->
                if (run.taskId in interruptedTaskIds &&
                    (run.status == AgentRunStatus.RUNNING || run.status == AgentRunStatus.QUEUED)
                ) {
                    run.copy(
                        status = AgentRunStatus.FAILED,
                        error = INTERRUPTED_RUN_ERROR,
                        updatedAt = now
                    )
                } else {
                    run
                }
            }
            val latestRunsByTaskId = updatedRuns
                .groupBy { it.taskId }
                .mapValues { (_, runs) -> runs.maxByOrNull { it.updatedAt }!! }
            val updatedTasks = state.tasks.map { task ->
                if (task.id in interruptedTaskIds) {
                    task.copy(
                        status = DesktopTaskStatus.FAILED,
                        updatedAt = now
                    )
                } else {
                    task
                }
            }
            var nextState = state.copy(
                tasks = updatedTasks,
                runs = updatedRuns
            )
            activeTasks.forEach { task ->
                val issue = task.issueId?.let(issuesById::get) ?: return@forEach
                if (issue.status == IssueStatus.DONE || issue.status == IssueStatus.CANCELED) {
                    return@forEach
                }
                val reopenedStatus = if (issue.kind.equals("planning", ignoreCase = true)) {
                    IssueStatus.PLANNED
                } else {
                    IssueStatus.DELEGATED
                }
                val latestRun = latestRunsByTaskId[task.id]
                traceEvents += buildCompanyAutomationTraceEvent(
                    issue = issue,
                    goal = issue.goalId?.let(goalsById::get),
                    oldStatus = issue.status,
                    newStatus = reopenedStatus,
                    source = "interruptActiveTasksForShutdown",
                    reason = "App-server shutdown interrupted an active task; the issue was returned to the queue.",
                    latestTask = task.copy(status = DesktopTaskStatus.FAILED, updatedAt = now),
                    latestRun = latestRun
                )
                nextState = nextState.copy(
                    issues = nextState.issues.map { existing ->
                        if (existing.id == issue.id) {
                            existing.copy(
                                status = reopenedStatus,
                                transitionReason = INTERRUPTED_ISSUE_REASON,
                                updatedAt = now
                            )
                        } else {
                            existing
                        }
                    }
                ).recordCompanyActivity(
                    companyId = issue.companyId,
                    source = "runtime",
                    title = "Interrupted by app-server shutdown",
                    detail = issue.title,
                    severity = "warning",
                    goalId = issue.goalId,
                    issueId = issue.id
                )
            }
            nextState = nextState.recordSignal(
                source = "runtime",
                message = "Interrupted ${activeTasks.size} active task(s) during app-server shutdown.",
                severity = "warning"
            ).withDerivedMetrics()
            stateStore.save(nextState)
        }
        traceEvents.forEach(::appendCompanyAutomationTrace)
    }

    private suspend fun reopenInterruptedBlockedIssues(companyId: String): Int {
        val traceEvents = mutableListOf<CompanyAutomationTraceEvent>()
        var reopenedCount = 0
        stateMutex.withLock {
            val state = stateStore.load()
            val now = System.currentTimeMillis()
            val runtime = state.companyRuntimes.firstOrNull { it.companyId == companyId } ?: return@withLock
            val interruptionBoundary = runtime.lastStartedAt ?: return@withLock
            val goalsById = state.goals.associateBy { it.id }
            val latestTasksByIssueId = state.tasks
                .filter { it.issueId != null }
                .groupBy { it.issueId!! }
                .mapValues { (_, tasks) -> tasks.maxByOrNull { it.updatedAt }!! }
            val latestRunsByTaskId = state.runs
                .groupBy { it.taskId }
                .mapValues { (_, runs) -> runs.maxByOrNull { it.updatedAt }!! }
            val issuesToReopen = state.issues.mapNotNull { issue ->
                if (issue.companyId != companyId || issue.status != IssueStatus.BLOCKED) {
                    return@mapNotNull null
                }
                if (issue.pullRequestNumber != null || issue.pullRequestUrl != null) {
                    return@mapNotNull null
                }
                if (issue.qaVerdict != null || issue.ceoVerdict != null) {
                    return@mapNotNull null
                }
                val latestTask = latestTasksByIssueId[issue.id] ?: return@mapNotNull null
                val latestRun = latestRunsByTaskId[latestTask.id] ?: return@mapNotNull null
                val interruptedByRestart =
                    latestTask.status in setOf(DesktopTaskStatus.FAILED, DesktopTaskStatus.PARTIAL) &&
                        latestTask.updatedAt < interruptionBoundary &&
                        latestRun.status == AgentRunStatus.FAILED &&
                        latestRun.publish == null &&
                        latestRun.error?.contains(
                            "Agent process exited before Cotor recorded a final result",
                            ignoreCase = true
                        ) == true
                if (!interruptedByRestart) {
                    return@mapNotNull null
                }
                issue to Pair(latestTask, latestRun)
            }
            if (issuesToReopen.isEmpty()) {
                return@withLock
            }
            val taskIds = issuesToReopen.mapTo(linkedSetOf()) { it.second.first.id }
            val updatedRuns = state.runs.map { run ->
                if (run.taskId in taskIds && run.status == AgentRunStatus.FAILED &&
                    run.error?.contains("Agent process exited before Cotor recorded a final result", ignoreCase = true) == true
                ) {
                    run.copy(
                        error = INTERRUPTED_RUN_ERROR,
                        updatedAt = now
                    )
                } else {
                    run
                }
            }
            var nextState = state.copy(runs = updatedRuns)
            issuesToReopen.forEach { (issue, pair) ->
                val latestTask = pair.first
                val latestRun = latestRunsByTaskId[latestTask.id]?.copy(error = INTERRUPTED_RUN_ERROR)
                    ?: pair.second.copy(error = INTERRUPTED_RUN_ERROR)
                val reopenedStatus = if (issue.kind.equals("planning", ignoreCase = true)) {
                    IssueStatus.PLANNED
                } else {
                    IssueStatus.DELEGATED
                }
                reopenedCount += 1
                traceEvents += buildCompanyAutomationTraceEvent(
                    issue = issue,
                    goal = issue.goalId?.let(goalsById::get),
                    oldStatus = issue.status,
                    newStatus = reopenedStatus,
                    source = "reopenInterruptedBlockedIssues",
                    reason = "Recovered an issue that was blocked by an app-server shutdown while its task was still running.",
                    latestTask = latestTask,
                    latestRun = latestRun
                )
                nextState = nextState.copy(
                    issues = nextState.issues.map { existing ->
                        if (existing.id == issue.id) {
                            existing.copy(
                                status = reopenedStatus,
                                transitionReason = INTERRUPTED_ISSUE_REASON,
                                updatedAt = now
                            )
                        } else {
                            existing
                        }
                    }
                ).recordCompanyActivity(
                    companyId = issue.companyId,
                    source = "runtime",
                    title = "Recovered interrupted issue",
                    detail = issue.title,
                    severity = "warning",
                    goalId = issue.goalId,
                    issueId = issue.id
                )
            }
            nextState = nextState.recordSignal(
                source = "runtime",
                message = "Recovered $reopenedCount blocked issue(s) that were interrupted by a previous app-server shutdown.",
                severity = "warning",
                companyId = companyId
            ).withDerivedMetrics()
            stateStore.save(nextState)
        }
        traceEvents.forEach(::appendCompanyAutomationTrace)
        return reopenedCount
    }

    private fun isTaskIntentionallyInterrupted(taskId: String): Boolean =
        taskId in intentionallyInterruptedTaskIds

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

            if (hasPendingIssues && !hasActiveTasks && tickIsStale && runtime?.manuallyStoppedAt == null) {
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

    suspend fun listIssueRuns(issueId: String): List<AgentRun> {
        val state = stateStore.load()
        val taskIds = state.tasks
            .filter { it.issueId == issueId }
            .mapTo(linkedSetOf()) { it.id }
        return state.runs
            .filter { it.taskId in taskIds }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun listGoals(): List<CompanyGoal> =
        stateStore.load().goals.sortedByDescending { it.updatedAt }

    suspend fun listCompanies(): List<Company> =
        stateStore.load().companies.sortedByDescending { it.updatedAt }

    suspend fun getCompany(companyId: String): Company? =
        stateStore.load().companies.firstOrNull { it.id == companyId }

    suspend fun listCompanyAgentDefinitions(companyId: String? = null): List<CompanyAgentDefinition> {
        migrateLegacyCompanyRosters(companyId)
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
        autonomyEnabled: Boolean = true,
        dailyBudgetCents: Int? = null,
        monthlyBudgetCents: Int? = null
    ): Company = stateMutex.withLock {
        // Company creation is the bridge from an arbitrary checkout on disk to the product's
        // higher-level operating model. The method normalizes the repo root, ensures a managed
        // repository/workspace exists, seeds default org roles, and records the first persisted
        // company snapshot in one serialized transaction.
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
                dailyBudgetCents = normalizeBudgetOverride(dailyBudgetCents, existing.dailyBudgetCents),
                monthlyBudgetCents = normalizeBudgetOverride(monthlyBudgetCents, existing.monthlyBudgetCents),
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
            dailyBudgetCents = dailyBudgetCents?.takeIf { it > 0 },
            monthlyBudgetCents = monthlyBudgetCents?.takeIf { it > 0 },
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
        backendKind: ExecutionBackendKind? = null,
        dailyBudgetCents: Int? = null,
        monthlyBudgetCents: Int? = null
    ): Company = stateMutex.withLock {
        val state = stateStore.load()
        val company = state.companies.firstOrNull { it.id == companyId }
            ?: throw IllegalArgumentException("Company not found: $companyId")
        val updated = company.copy(
            name = name?.trim()?.takeIf { it.isNotBlank() } ?: company.name,
            defaultBaseBranch = defaultBaseBranch?.trim()?.takeIf { it.isNotBlank() } ?: company.defaultBaseBranch,
            backendKind = backendKind ?: company.backendKind,
            autonomyEnabled = autonomyEnabled ?: company.autonomyEnabled,
            dailyBudgetCents = normalizeBudgetOverride(dailyBudgetCents, company.dailyBudgetCents),
            monthlyBudgetCents = normalizeBudgetOverride(monthlyBudgetCents, company.monthlyBudgetCents),
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
        codePublishMode: CodePublishMode? = null,
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
                    codePublishMode = codePublishMode ?: state.backendSettings.codePublishMode,
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
            runtime = if (state.runtime.companyId == companyId) CompanyRuntimeSnapshot() else state.runtime,
            workflowPipelines = state.workflowPipelines.filterNot { it.companyId == companyId },
            agentContextEntries = state.agentContextEntries.filterNot { it.companyId == companyId },
            agentMessages = state.agentMessages.filterNot { it.companyId == companyId }
        ).withDerivedMetrics()
        stateStore.save(nextState)
        deleteDirectoryRecursively(companyContextRoot(company))
        company
    }

    // ── Workflow Pipeline CRUD ──────────────────────────────────────────

    fun seedDefaultPipeline(companyId: String, now: Long = System.currentTimeMillis()): WorkflowPipelineDefinition {
        val pipelineId = UUID.randomUUID().toString()
        return WorkflowPipelineDefinition(
            id = pipelineId,
            companyId = companyId,
            name = "Default",
            stages = listOf(
                WorkflowStageDefinition(id = "execution", kind = "execution", title = "Execution", order = 0),
                WorkflowStageDefinition(
                    id = "qa-review", kind = "review", title = "QA Review",
                    assigneeRoleName = "QA", verdictKey = "QA_VERDICT",
                    skipWhen = "!codeProducing", order = 1
                ),
                WorkflowStageDefinition(
                    id = "ceo-approval", kind = "approval", title = "CEO Approval",
                    assigneeRoleName = "CEO", verdictKey = "CEO_VERDICT",
                    skipWhen = "!codeProducing", order = 2
                )
            ),
            isDefault = true,
            createdAt = now,
            updatedAt = now
        )
    }

    suspend fun listPipelines(companyId: String): List<WorkflowPipelineDefinition> =
        stateStore.load().workflowPipelines.filter { it.companyId == companyId }

    suspend fun createPipeline(
        companyId: String,
        name: String,
        stages: List<WorkflowStageDefinition>
    ): WorkflowPipelineDefinition = stateMutex.withLock {
        val state = stateStore.load()
        require(state.companies.any { it.id == companyId }) { "Company not found: $companyId" }
        val now = System.currentTimeMillis()
        val pipeline = WorkflowPipelineDefinition(
            id = UUID.randomUUID().toString(),
            companyId = companyId,
            name = name.trim().ifEmpty { "Custom Pipeline" },
            stages = stages.mapIndexed { idx, s -> s.copy(order = idx) },
            isDefault = false,
            createdAt = now,
            updatedAt = now
        )
        stateStore.save(
            state.copy(workflowPipelines = state.workflowPipelines + pipeline).withDerivedMetrics()
        )
        pipeline
    }

    suspend fun updatePipeline(
        pipelineId: String,
        name: String? = null,
        stages: List<WorkflowStageDefinition>? = null
    ): WorkflowPipelineDefinition = stateMutex.withLock {
        val state = stateStore.load()
        val current = state.workflowPipelines.firstOrNull { it.id == pipelineId }
            ?: throw IllegalArgumentException("Pipeline not found: $pipelineId")
        val now = System.currentTimeMillis()
        val updated = current.copy(
            name = name?.trim()?.takeIf { it.isNotBlank() } ?: current.name,
            stages = stages?.mapIndexed { idx, s -> s.copy(order = idx) } ?: current.stages,
            updatedAt = now
        )
        stateStore.save(
            state.copy(workflowPipelines = state.workflowPipelines.map { if (it.id == pipelineId) updated else it }).withDerivedMetrics()
        )
        updated
    }

    suspend fun deletePipeline(pipelineId: String): WorkflowPipelineDefinition = stateMutex.withLock {
        val state = stateStore.load()
        val pipeline = state.workflowPipelines.firstOrNull { it.id == pipelineId }
            ?: throw IllegalArgumentException("Pipeline not found: $pipelineId")
        require(!pipeline.isDefault) { "Cannot delete the default pipeline" }
        stateStore.save(
            state.copy(workflowPipelines = state.workflowPipelines.filterNot { it.id == pipelineId }).withDerivedMetrics()
        )
        pipeline
    }

    suspend fun setDefaultPipeline(companyId: String, pipelineId: String): WorkflowPipelineDefinition = stateMutex.withLock {
        val state = stateStore.load()
        val target = state.workflowPipelines.firstOrNull { it.id == pipelineId && it.companyId == companyId }
            ?: throw IllegalArgumentException("Pipeline not found: $pipelineId")
        val now = System.currentTimeMillis()
        val nextPipelines = state.workflowPipelines.map {
            if (it.companyId != companyId) it
            else it.copy(isDefault = it.id == pipelineId, updatedAt = now)
        }
        val nextCompanies = state.companies.map {
            if (it.id == companyId) it.copy(defaultPipelineId = pipelineId, updatedAt = now) else it
        }
        stateStore.save(
            state.copy(workflowPipelines = nextPipelines, companies = nextCompanies).withDerivedMetrics()
        )
        target.copy(isDefault = true, updatedAt = now)
    }

    fun resolveCompanyPipeline(state: DesktopAppState, companyId: String): WorkflowPipelineDefinition? =
        state.workflowPipelines.firstOrNull { it.companyId == companyId && it.isDefault }
            ?: state.workflowPipelines.firstOrNull { it.companyId == companyId }

    // ── Agent Context Entries ───────────────────────────────────────────

    suspend fun listContextEntries(companyId: String, goalId: String? = null, issueId: String? = null): List<AgentContextEntry> {
        val entries = stateStore.load().agentContextEntries.filter { it.companyId == companyId }
        return when {
            issueId != null -> entries.filter { it.issueId == issueId || it.visibility == "company" }
            goalId != null -> entries.filter { it.goalId == goalId || it.visibility == "company" }
            else -> entries
        }
    }

    suspend fun addContextEntry(
        companyId: String,
        agentName: String,
        kind: String,
        title: String,
        content: String,
        issueId: String? = null,
        goalId: String? = null,
        visibility: String = "company"
    ): AgentContextEntry = stateMutex.withLock {
        val state = stateStore.load()
        val entry = AgentContextEntry(
            id = UUID.randomUUID().toString(),
            companyId = companyId,
            issueId = issueId,
            goalId = goalId,
            agentName = agentName.trim(),
            kind = kind.trim(),
            title = title.trim(),
            content = content.trim(),
            visibility = visibility,
            createdAt = System.currentTimeMillis()
        )
        stateStore.save(state.copy(agentContextEntries = state.agentContextEntries + entry).withDerivedMetrics())
        entry
    }

    suspend fun deleteContextEntry(entryId: String) = stateMutex.withLock {
        val state = stateStore.load()
        stateStore.save(state.copy(agentContextEntries = state.agentContextEntries.filterNot { it.id == entryId }).withDerivedMetrics())
    }

    // ── Agent Messages ──────────────────────────────────────────────────

    // ── Execution Log & Issue Graph ────────────────────────────────────

    suspend fun executionLog(companyId: String): List<Map<String, Any?>> {
        val state = stateStore.load()
        val issues = state.issues.filter { it.companyId == companyId }
        val issueIds = issues.map { it.id }.toSet()
        val tasks = state.tasks.filter { it.issueId in issueIds }
        val taskIds = tasks.map { it.id }.toSet()
        val runs = state.runs.filter { it.taskId in taskIds }
        val profiles = state.orgProfiles.associateBy { it.id }
        return issues.sortedByDescending { it.updatedAt }.map { issue ->
            val issueTasks = tasks.filter { it.issueId == issue.id }.sortedByDescending { it.updatedAt }
            val issueRuns = runs.filter { it.taskId in issueTasks.map { t -> t.id }.toSet() }.sortedByDescending { it.updatedAt }
            val assignee = issue.assigneeProfileId?.let { profiles[it] }
            mapOf(
                "issueId" to issue.id,
                "issueTitle" to issue.title,
                "issueStatus" to issue.status.name,
                "issueKind" to issue.kind,
                "assignee" to (assignee?.roleName ?: "unassigned"),
                "dependsOn" to issue.dependsOn,
                "pullRequestUrl" to issue.pullRequestUrl,
                "qaVerdict" to issue.qaVerdict,
                "ceoVerdict" to issue.ceoVerdict,
                "tasks" to issueTasks.map { task ->
                    val taskRuns = issueRuns.filter { it.taskId == task.id }
                    mapOf(
                        "taskId" to task.id,
                        "status" to task.status.name,
                        "createdAt" to task.createdAt,
                        "updatedAt" to task.updatedAt,
                        "runs" to taskRuns.map { run ->
                            mapOf(
                                "runId" to run.id,
                                "agent" to run.agentName,
                                "status" to run.status.name,
                                "branch" to run.branchName,
                                "error" to run.error?.take(300),
                                "outputSummary" to run.output?.takeLast(200),
                                "durationMs" to run.durationMs,
                                "pullRequestUrl" to run.publish?.pullRequestUrl,
                                "publishError" to run.publish?.error?.take(200),
                                "createdAt" to run.createdAt,
                                "updatedAt" to run.updatedAt
                            )
                        }
                    )
                }
            )
        }
    }

    suspend fun issueGraph(companyId: String): Map<String, Any> {
        val state = stateStore.load()
        val issues = state.issues.filter { it.companyId == companyId }
        val profiles = state.orgProfiles.associateBy { it.id }
        val deps = state.issueDependencies.filter { dep ->
            issues.any { it.id == dep.issueId } || issues.any { it.id == dep.dependsOnIssueId }
        }
        val nodes = issues.map { issue ->
            val assignee = issue.assigneeProfileId?.let { profiles[it] }
            mapOf(
                "id" to issue.id,
                "title" to issue.title,
                "status" to issue.status.name,
                "kind" to issue.kind,
                "assignee" to (assignee?.roleName ?: "unassigned"),
                "goalId" to issue.goalId,
                "dependsOn" to issue.dependsOn,
                "pullRequestUrl" to issue.pullRequestUrl,
                "qaVerdict" to issue.qaVerdict,
                "ceoVerdict" to issue.ceoVerdict
            )
        }
        val edges = deps.map { dep ->
            mapOf("from" to dep.dependsOnIssueId, "to" to dep.issueId, "relation" to dep.relation)
        } + issues.flatMap { issue ->
            issue.dependsOn.map { depId -> mapOf("from" to depId, "to" to issue.id, "relation" to "depends") }
        }
        return mapOf("nodes" to nodes, "edges" to edges.distinctBy { "${it["from"]}-${it["to"]}" })
    }

    suspend fun listMessages(companyId: String, goalId: String? = null, issueId: String? = null): List<AgentMessage> {
        val msgs = stateStore.load().agentMessages.filter { it.companyId == companyId }
        return when {
            issueId != null -> msgs.filter { it.issueId == issueId }
            goalId != null -> msgs.filter { it.goalId == goalId }
            else -> msgs
        }.sortedByDescending { it.createdAt }
    }

    suspend fun sendMessage(
        companyId: String,
        fromAgentName: String,
        toAgentName: String?,
        kind: String,
        subject: String,
        body: String,
        issueId: String? = null,
        goalId: String? = null
    ): AgentMessage = stateMutex.withLock {
        val state = stateStore.load()
        val now = System.currentTimeMillis()
        val message = AgentMessage(
            id = UUID.randomUUID().toString(),
            companyId = companyId,
            fromAgentName = fromAgentName.trim(),
            toAgentName = toAgentName?.trim(),
            issueId = issueId,
            goalId = goalId,
            kind = kind.trim(),
            subject = subject.trim(),
            body = body.trim(),
            createdAt = now
        )
        stateStore.save(state.copy(agentMessages = state.agentMessages + message).withDerivedMetrics())
        publishCompanyEvent(
            companyId = companyId,
            type = "agent.message",
            title = "${message.fromAgentName} -> ${message.toAgentName ?: "all"}: ${message.subject}",
            detail = message.body.take(200),
            goalId = goalId,
            issueId = issueId
        )
        message
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
        followUpContext: FollowUpContextSnapshot? = null,
        startRuntimeIfNeeded: Boolean = true
    ): CompanyGoal {
        // Goals are the top-level planning primitive for the company layer. Creation intentionally
        // does more than append a row: it ensures a workspace/project context exists, materializes
        // any missing agent roster state, persists the goal, then queues decomposition/runtime work
        // so the organization can immediately turn the goal into executable issues.
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
                followUpContext = followUpContext,
                autonomyEnabled = autonomyEnabled,
                createdAt = now,
                updatedAt = now
            )
            val nextState = workspaceState.copy(
                companies = nextCompanies,
                companyAgentDefinitions = companyDefinitions,
                projectContexts = nextProjectContexts,
                goals = workspaceState.goals + goal,
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
            publishCompanyEvent(
                companyId = company.id,
                type = "goal.created",
                title = "Created goal",
                detail = goal.title,
                goalId = goal.id
            )
            goal
        }
        runCatching { decomposeGoal(goal.id) }
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
            if (goal.autonomyEnabled && startRuntimeIfNeeded && !isCompanyManuallyStopped(goal.companyId)) {
                runCatching { startCompanyRuntime(goal.companyId) }
            }
        }
    }

    private fun queueGoalPostUpdateWork(goal: CompanyGoal) {
        if (!goal.autonomyEnabled || goal.status != GoalStatus.ACTIVE) {
            return
        }
        serviceScope.launch {
            if (!isCompanyManuallyStopped(goal.companyId)) {
                runCatching { startCompanyRuntime(goal.companyId) }
            }
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

    suspend fun decomposeGoal(goalId: String): List<CompanyIssue> {
        val planningIssueId = stateMutex.withLock {
            val state = stateStore.load()
            val existing = state.issues.filter { it.goalId == goalId }
            val existingNonPlanning = existing.filterNot { it.kind.equals("planning", ignoreCase = true) }
            val unresolvedNonPlanning = existingNonPlanning.filterNot { issue ->
                issue.status == IssueStatus.DONE || issue.status == IssueStatus.CANCELED
            }
            if (unresolvedNonPlanning.isNotEmpty()) {
                return existing.sortedByDescending { it.updatedAt }
            }
            val existingPlanningIssue = existing.firstOrNull { it.kind.equals("planning", ignoreCase = true) }
            val existingPlanningTaskRunning = existingPlanningIssue?.let { planningIssue ->
                state.tasks.any { task ->
                    task.issueId == planningIssue.id &&
                        (task.status == DesktopTaskStatus.RUNNING || task.status == DesktopTaskStatus.QUEUED)
                }
            } == true
            if (existingPlanningIssue != null && existingPlanningTaskRunning) {
                return existing.sortedByDescending { it.updatedAt }
            }
            if (existingPlanningIssue != null &&
                existingPlanningIssue.status == IssueStatus.DONE &&
                unresolvedNonPlanning.isNotEmpty()
            ) {
                return existing.sortedByDescending { it.updatedAt }
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
            val chiefProfile = profiles.firstOrNull {
                it.companyId == company.id && (it.mergeAuthority || it.roleName.equals("CEO", ignoreCase = true))
            } ?: suggestProfileForCustomIssue(
                title = "CEO planning",
                description = goal.description,
                kind = "planning",
                profiles = profiles.filter { it.companyId == company.id }
            )
            val planningIssue = existingPlanningIssue?.copy(
                assigneeProfileId = chiefProfile?.id ?: existingPlanningIssue.assigneeProfileId,
                status = IssueStatus.PLANNED,
                transitionReason = "CEO planning lane is preparing explicit issue assignments for this goal.",
                updatedAt = now
            ) ?: CompanyIssue(
                id = UUID.randomUUID().toString(),
                companyId = goal.companyId,
                projectContextId = goal.projectContextId,
                goalId = goal.id,
                workspaceId = workspace.id,
                title = "CEO plan and delegate \"${goal.title}\"",
                description = buildPlanningIssueDescription(goal, company, workspaceState.companyAgentDefinitions, profiles),
                status = IssueStatus.PLANNED,
                priority = 1,
                kind = "planning",
                assigneeProfileId = chiefProfile?.id,
                blockedBy = emptyList(),
                dependsOn = emptyList(),
                acceptanceCriteria = listOf(
                    "Produce a structured plan with explicit issues, assignees, dependencies, and PR expectations.",
                    "Use the current company roster and goal context when assigning work."
                ),
                riskLevel = "medium",
                codeProducing = false,
                transitionReason = "CEO planning lane is preparing explicit issue assignments for this goal.",
                sourceSignal = CEO_PLANNING_SOURCE,
                createdAt = now,
                updatedAt = now
            )
            val nextState = workspaceState.copy(
                issues = workspaceState.issues.filterNot { it.id == planningIssue.id } + planningIssue,
                orgProfiles = profiles,
            ).recordCompanyActivity(
                companyId = company.id,
                projectContextId = projectContext.id,
                goalId = goal.id,
                issueId = planningIssue.id,
                source = "goal-decomposition",
                title = if (existingPlanningIssue == null) "Created CEO planning issue" else "Reopened CEO planning issue",
                detail = planningIssue.title
            ).withDerivedMetrics()
            stateStore.save(nextState)
            writeCompanyContextSnapshot(nextState, company, projectContext)
            publishCompanyEvent(
                companyId = company.id,
                type = "goal.planning.started",
                title = "Started CEO planning",
                detail = planningIssue.title,
                goalId = goal.id,
                issueId = planningIssue.id
            )
            planningIssue.id
        }
        // Materialize a deterministic fallback plan immediately so the company
        // loop can keep moving even before an explicit CEO planning run returns.
        runCatching {
            stateMutex.withLock {
                val state = stateStore.load()
                val planningIssue = state.issues.firstOrNull { it.id == planningIssueId } ?: return@withLock
                val existingNonPlanning = state.issues.filter {
                    it.goalId == planningIssue.goalId && !it.kind.equals("planning", ignoreCase = true)
                }
                val unresolvedNonPlanning = existingNonPlanning.filterNot { issue ->
                    issue.status == IssueStatus.DONE || issue.status == IssueStatus.CANCELED
                }
                if (unresolvedNonPlanning.isNotEmpty()) {
                    stateStore.save(
                        state.copy(
                            issues = state.issues.map {
                                if (it.id == planningIssueId) {
                                    it.copy(status = IssueStatus.DONE, updatedAt = System.currentTimeMillis())
                                } else {
                                    it
                                }
                            }
                        ).withDerivedMetrics()
                    )
                    return@withLock
                }
                val goal = state.goals.firstOrNull { it.id == planningIssue.goalId } ?: return@withLock
                val workspace = state.workspaces.firstOrNull { it.id == planningIssue.workspaceId } ?: return@withLock
                val profiles = ensureOrgProfiles(state.orgProfiles, state.companyAgentDefinitions, state.companies)
                val now = System.currentTimeMillis()
                val fallback = buildFallbackGoalIssues(
                    state = state,
                    goal = goal,
                    workspace = workspace,
                    profiles = profiles,
                    definitions = state.companyAgentDefinitions,
                    now = now
                )
                val updatedPlanningIssue = planningIssue.copy(
                    status = IssueStatus.DONE,
                    transitionReason = "CEO planning lane used the deterministic fallback planner.",
                    updatedAt = now
                )
                val company = state.companies.firstOrNull { it.id == goal.companyId } ?: return@withLock
                val projectContext = state.projectContexts.firstOrNull { it.companyId == company.id } ?: return@withLock
                val nextState = state.copy(
                    issues = state.issues.filterNot { it.id == planningIssueId } + updatedPlanningIssue + fallback.first,
                    issueDependencies = state.issueDependencies.filterNot { dep ->
                        dep.issueId == planningIssueId
                    } + fallback.second
                ).withDerivedMetrics()
                stateStore.save(nextState)
                writeCompanyContextSnapshot(nextState, company, projectContext)
            }
        }
        runCatching { mirrorGoalIssuesToLinear(goalId) }
        return stateStore.load().issues
            .filter { it.goalId == goalId }
            .sortedByDescending { it.updatedAt }
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
        val workspace = state.workspaces.firstOrNull { it.id == executableIssue.workspaceId }
        val repository = workspace?.let { ws -> state.repositories.firstOrNull { it.id == ws.repositoryId } }
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
        if (requiresGitHubPullRequest(executableIssue, state) && workspace != null && repository != null) {
            val readiness = runCatching {
                gitWorkspaceService.ensureGitHubPublishReady(
                    worktreePath = Path.of(repository.localPath),
                    baseBranch = workspace.baseBranch
                )
            }.getOrElse { cause ->
                return handleDelegatedIssueStartFailure(executableIssue, cause)
            }
            if (!readiness.ready) {
                return blockIssueForGitHubReadiness(executableIssue, readiness.error ?: "GitHub publishing is unavailable")
            }
        }
        val latestTask = state.tasks
            .filter { it.issueId == executableIssue.id }
            .maxByOrNull { it.updatedAt }
        val latestRun = latestTask?.let { latestRunForTask(state, it.id) }
        val expectedWorkflowLineage = if (isWorkflowIssue(executableIssue)) {
            expectedWorkflowLineageForIssue(state, executableIssue)
        } else {
            null
        }
        val needsReworkRun =
            latestTask?.status == DesktopTaskStatus.COMPLETED &&
                (
                    executableIssue.qaVerdict == "CHANGES_REQUESTED" ||
                        executableIssue.ceoVerdict == "CHANGES_REQUESTED"
                    )
        val workflowNeedsFreshTask =
            isWorkflowIssue(executableIssue) &&
                latestTask != null &&
                latestTask.status != DesktopTaskStatus.RUNNING &&
                latestTask.status != DesktopTaskStatus.QUEUED &&
                workflowIssueNeedsFreshTask(executableIssue, latestTask, latestRun, expectedWorkflowLineage)
        val shouldCreateFreshTask = latestTask == null ||
            latestTask.status == DesktopTaskStatus.FAILED ||
            latestTask.status == DesktopTaskStatus.PARTIAL ||
            needsReworkRun ||
            workflowNeedsFreshTask
        val task = if (shouldCreateFreshTask) {
            createTask(
                workspaceId = executableIssue.workspaceId,
                title = executableIssue.title,
                prompt = buildIssueExecutionPrompt(state, executableIssue, profile),
                agents = listOf(profile.executionAgentName),
                issueId = executableIssue.id,
                workflowLineage = expectedWorkflowLineage
            )
        } else {
            latestTask
        }
        if (!shouldCreateFreshTask && expectedWorkflowLineage != null) {
            synchronizeWorkflowTaskLineage(executableIssue.id, task.id, expectedWorkflowLineage)
        }
        if (task.status == DesktopTaskStatus.QUEUED) {
            serviceScope.launch {
                runCatching { runTaskIfPresent(task.id) }
                    .onFailure { cause ->
                        markCompanyRuntimeError(executableIssue.companyId, cause)
                    }
            }
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

    private suspend fun handleDelegatedIssueStartFailure(issue: CompanyIssue, cause: Throwable): CompanyIssue {
        val reason = cause.message ?: cause::class.simpleName ?: "Unknown issue start failure"
        return if (requiresGitHubPullRequest(issue, stateStore.load())) {
            blockIssueForGitHubReadiness(issue, "GitHub readiness check failed: $reason")
        } else {
            blockIssueForRuntimeStartFailure(issue, reason)
        }
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

    private suspend fun blockIssueForGitHubReadiness(issue: CompanyIssue, reason: String): CompanyIssue {
        return stateMutex.withLock {
            val state = stateStore.load()
            val currentIssue = state.issues.firstOrNull { it.id == issue.id } ?: return@withLock issue
            val now = System.currentTimeMillis()
            val existingInfraIssue = state.issues.firstOrNull {
                it.companyId == currentIssue.companyId &&
                    it.goalId == currentIssue.goalId &&
                    it.kind.equals("infra", ignoreCase = true) &&
                    it.title == "Restore GitHub publishing for ${currentIssue.title}"
            }
            val infraIssue = existingInfraIssue?.copy(
                status = IssueStatus.PLANNED,
                description = "GitHub publishing is required before this code issue can continue.\n\n$reason",
                updatedAt = now
            ) ?: CompanyIssue(
                id = UUID.randomUUID().toString(),
                companyId = currentIssue.companyId,
                projectContextId = currentIssue.projectContextId,
                goalId = currentIssue.goalId,
                workspaceId = currentIssue.workspaceId,
                title = "Restore GitHub publishing for ${currentIssue.title}",
                description = "GitHub publishing is required before this code issue can continue.\n\n$reason",
                status = IssueStatus.PLANNED,
                priority = 1,
                kind = "infra",
                sourceSignal = "github-readiness",
                createdAt = now,
                updatedAt = now
            )
            val blockedIssue = currentIssue.copy(
                status = IssueStatus.BLOCKED,
                blockedBy = (currentIssue.blockedBy + infraIssue.id).distinct(),
                transitionReason = reason,
                updatedAt = now
            )
            val nextIssues = state.issues
                .filterNot { it.id == blockedIssue.id || it.id == infraIssue.id } + blockedIssue + infraIssue
            val nextState = state.copy(
                issues = nextIssues
            ).recordCompanyActivity(
                companyId = currentIssue.companyId,
                projectContextId = currentIssue.projectContextId,
                goalId = currentIssue.goalId,
                issueId = currentIssue.id,
                source = "github-readiness",
                title = "Blocked code issue",
                detail = reason,
                severity = "warning"
            ).recordCompanyActivity(
                companyId = currentIssue.companyId,
                projectContextId = currentIssue.projectContextId,
                goalId = currentIssue.goalId,
                issueId = infraIssue.id,
                source = "github-readiness",
                title = if (existingInfraIssue == null) "Created infra issue" else "Reopened infra issue",
                detail = infraIssue.title,
                severity = "warning"
            ).withDerivedMetrics()
            stateStore.save(nextState)
            blockedIssue
        }
    }

    private suspend fun blockIssueForRuntimeStartFailure(issue: CompanyIssue, reason: String): CompanyIssue =
        stateMutex.withLock {
            val state = stateStore.load()
            val currentIssue = state.issues.firstOrNull { it.id == issue.id } ?: return@withLock issue
            val now = System.currentTimeMillis()
            val blockedIssue = currentIssue.copy(
                status = IssueStatus.BLOCKED,
                updatedAt = now
            )
            val nextState = state.copy(
                issues = state.issues.map { existing ->
                    if (existing.id == currentIssue.id) blockedIssue else existing
                }
            ).recordCompanyActivity(
                companyId = currentIssue.companyId,
                projectContextId = currentIssue.projectContextId,
                goalId = currentIssue.goalId,
                issueId = currentIssue.id,
                source = "issue-run",
                title = "Failed to start issue run",
                detail = reason,
                severity = "error"
            ).recordSignal(
                source = "issue-run",
                message = "Failed to start ${currentIssue.title}: $reason",
                companyId = currentIssue.companyId,
                projectContextId = currentIssue.projectContextId,
                goalId = currentIssue.goalId,
                issueId = currentIssue.id,
                severity = "error"
            ).withDerivedMetrics()
            stateStore.save(nextState)
            blockedIssue
        }

    private fun isDependencySatisfied(
        issue: CompanyIssue,
        dependency: CompanyIssue,
        state: DesktopAppState
    ): Boolean {
        if (isSupersededCanceledDependency(dependency, state)) {
            return true
        }
        return when (issue.kind.lowercase()) {
            "review" ->
                dependency.status == IssueStatus.IN_REVIEW ||
                    dependency.status == IssueStatus.READY_FOR_CEO ||
                    dependency.status == IssueStatus.DONE
            else -> dependency.status == IssueStatus.DONE
        }
    }

    private fun runnableIssueStatusRank(status: IssueStatus): Int = when (status) {
        IssueStatus.DELEGATED -> 0
        IssueStatus.PLANNED -> 1
        IssueStatus.BACKLOG -> 2
        else -> 3
    }

    private fun isSupersededCanceledDependency(
        dependency: CompanyIssue,
        state: DesktopAppState
    ): Boolean {
        if (dependency.status != IssueStatus.CANCELED) {
            return false
        }
        val latestSuccessfulReplacement = state.issues
            .asSequence()
            .filter { candidate ->
                candidate.companyId == dependency.companyId &&
                    candidate.id != dependency.id &&
                    candidate.status == IssueStatus.DONE &&
                    candidate.kind.equals(dependency.kind, ignoreCase = true) &&
                    candidate.title.trim().equals(dependency.title.trim(), ignoreCase = true)
            }
            .maxOfOrNull { it.updatedAt }
        return latestSuccessfulReplacement != null && latestSuccessfulReplacement > dependency.updatedAt
    }

    private data class StructuredVerdict(
        val value: String,
        val feedback: String
    )

    private fun parseStructuredVerdict(
        output: String?,
        marker: String,
        successVerdict: String,
        failureVerdict: String
    ): StructuredVerdict? {
        val text = output?.trim().orEmpty()
        if (text.isBlank()) {
            return null
        }
        val regex = Regex("(?im)^\\s*${Regex.escape(marker)}\\s*:\\s*([A-Z_]+)\\s*$")
        val explicit = regex.find(text)?.groupValues?.getOrNull(1)?.trim()?.uppercase()
        val normalized = when (explicit) {
            successVerdict -> successVerdict
            failureVerdict -> failureVerdict
            else -> null
        } ?: run {
            val lower = text.lowercase()
            when {
                lower.contains("changes requested") || lower.contains("request changes") || lower.contains("needs follow-up") || lower.contains("blocked") -> failureVerdict
                lower.contains("ready for approval") || lower.contains("approve") || lower.contains("pass") || lower.contains("ready") -> successVerdict
                else -> successVerdict
            }
        }
        val feedback = text
            .lineSequence()
            .filterNot { it.trim().startsWith("$marker:", ignoreCase = true) }
            .joinToString("\n")
            .trim()
        return StructuredVerdict(
            value = normalized,
            feedback = feedback.ifBlank { summarizeForPrompt(text, 240) }
        )
    }

    private fun normalizedReviewFeedback(text: String?): String =
        text.orEmpty()
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun reviewQueueMatchesPublishedRun(queueItem: ReviewQueueItem, run: AgentRun?): Boolean {
        if (run == null) {
            return false
        }
        val publish = run.publish
        if (queueItem.pullRequestNumber != null && publish?.pullRequestNumber != null) {
            return queueItem.pullRequestNumber == publish.pullRequestNumber
        }
        if (!queueItem.pullRequestUrl.isNullOrBlank() && !publish?.pullRequestUrl.isNullOrBlank()) {
            return queueItem.pullRequestUrl == publish.pullRequestUrl
        }
        if (!queueItem.branchName.isNullOrBlank() && !run.branchName.isNullOrBlank()) {
            return queueItem.branchName == run.branchName
        }
        if (!queueItem.worktreePath.isNullOrBlank() && !run.worktreePath.isNullOrBlank()) {
            return queueItem.worktreePath == run.worktreePath
        }
        return false
    }

    private fun reviewTaskAlreadyApplied(
        reviewIssue: CompanyIssue,
        executionIssue: CompanyIssue,
        approvalIssue: CompanyIssue?,
        queueItem: ReviewQueueItem,
        verdict: StructuredVerdict,
        reviewPassed: Boolean
    ): Boolean {
        val expectedReviewStatus = if (reviewPassed) IssueStatus.DONE else IssueStatus.BLOCKED
        val expectedExecutionStatus = if (reviewPassed) IssueStatus.READY_FOR_CEO else IssueStatus.PLANNED
        val expectedQueueStatus = if (reviewPassed) ReviewQueueStatus.READY_FOR_CEO else ReviewQueueStatus.CHANGES_REQUESTED
        val normalizedFeedback = normalizedReviewFeedback(verdict.feedback)
        val reviewMatches =
            reviewIssue.status == expectedReviewStatus &&
                reviewIssue.qaVerdict == verdict.value &&
                normalizedReviewFeedback(reviewIssue.qaFeedback) == normalizedFeedback
        val executionMatches =
            executionIssue.status == expectedExecutionStatus &&
                executionIssue.qaVerdict == verdict.value &&
                normalizedReviewFeedback(executionIssue.qaFeedback) == normalizedFeedback
        val queueMatches =
            queueItem.status == expectedQueueStatus &&
                queueItem.qaVerdict == verdict.value &&
                normalizedReviewFeedback(queueItem.qaFeedback) == normalizedFeedback
        val approvalMatches = when {
            !reviewPassed -> true
            approvalIssue == null -> queueItem.approvalIssueId == null
            else -> approvalIssue.status in setOf(IssueStatus.PLANNED, IssueStatus.IN_PROGRESS, IssueStatus.DONE) &&
                approvalIssue.qaVerdict == verdict.value &&
                normalizedReviewFeedback(approvalIssue.qaFeedback) == normalizedFeedback
        }
        return reviewMatches && executionMatches && queueMatches && approvalMatches
    }

    private fun approvalTaskAlreadyApplied(
        approvalIssue: CompanyIssue,
        executionIssue: CompanyIssue,
        queueItem: ReviewQueueItem,
        verdict: StructuredVerdict,
        approvalGranted: Boolean
    ): Boolean {
        val expectedApprovalStatus = if (approvalGranted) IssueStatus.IN_PROGRESS else IssueStatus.BLOCKED
        val expectedExecutionStatus = if (approvalGranted) IssueStatus.READY_FOR_CEO else IssueStatus.PLANNED
        val expectedQueueStatus = if (approvalGranted) ReviewQueueStatus.READY_FOR_CEO else ReviewQueueStatus.CHANGES_REQUESTED
        val normalizedFeedback = normalizedReviewFeedback(verdict.feedback)
        val approvalMatches =
            approvalIssue.status == expectedApprovalStatus &&
                approvalIssue.ceoVerdict == verdict.value &&
                normalizedReviewFeedback(approvalIssue.ceoFeedback) == normalizedFeedback
        val executionMatches =
            executionIssue.status == expectedExecutionStatus &&
                executionIssue.ceoVerdict == verdict.value &&
                normalizedReviewFeedback(executionIssue.ceoFeedback) == normalizedFeedback
        val queueMatches =
            queueItem.status == expectedQueueStatus &&
                queueItem.ceoVerdict == verdict.value &&
                normalizedReviewFeedback(queueItem.ceoFeedback) == normalizedFeedback
        return approvalMatches && executionMatches && queueMatches
    }

    private suspend fun completeApprovalIssueAfterMerge(issueId: String) {
        stateMutex.withLock {
            val state = stateStore.load()
            val issue = state.issues.firstOrNull { it.id == issueId } ?: return@withLock
            val now = System.currentTimeMillis()
            val nextIssues = state.issues.map { existing ->
                if (existing.id == issueId) existing.copy(status = IssueStatus.DONE, updatedAt = now) else existing
            }
            val nextGoals = state.goals.map { goal ->
                if (goal.id != issue.goalId) {
                    goal
                } else {
                    val unresolved = nextIssues.any { it.goalId == goal.id && it.status != IssueStatus.DONE && it.status != IssueStatus.CANCELED }
                    if (!unresolved) goal.copy(status = GoalStatus.COMPLETED, updatedAt = now) else goal
                }
            }
            stateStore.save(
                state.copy(
                    issues = nextIssues,
                    goals = nextGoals
                ).recordCompanyActivity(
                    companyId = issue.companyId,
                    projectContextId = issue.projectContextId,
                    goalId = issue.goalId,
                    issueId = issue.id,
                    source = "ceo-approval",
                    title = "Completed approval issue",
                    detail = issue.title
                ).withDerivedMetrics()
            )
        }
    }

    suspend fun mergeReviewQueueItem(itemId: String): ReviewQueueItem {
        val before = stateStore.load()
        val item = before.reviewQueue.firstOrNull { it.id == itemId }
            ?: throw IllegalArgumentException("Review queue item not found: $itemId")
        val run = before.runs.firstOrNull { it.id == item.runId }
        val executionIssueBeforeMerge = before.issues.firstOrNull { it.id == item.issueId }
        val worktreePath = item.worktreePath?.takeIf { it.isNotBlank() } ?: run?.worktreePath
        val pullRequestNumber = item.pullRequestNumber ?: run?.publish?.pullRequestNumber
        if (worktreePath.isNullOrBlank() || pullRequestNumber == null) {
            throw IllegalStateException("Review queue item $itemId is missing branch or pull request metadata")
        }
        val baseBranch = run?.baseBranch?.takeIf { it.isNotBlank() }
            ?: executionIssueBeforeMerge?.workspaceId
                ?.let { workspaceId -> before.workspaces.firstOrNull { it.id == workspaceId }?.baseBranch }
            ?: before.companies.firstOrNull { it.id == item.companyId }?.defaultBaseBranch
            ?: "master"

        val reviewBody = item.ceoFeedback?.takeIf { it.isNotBlank() }
            ?: "CEO approved this pull request after QA review."
        val commentBody = buildPullRequestFeedbackBody(
            actorLabel = "CEO",
            verdictKey = "CEO_VERDICT",
            verdict = item.ceoVerdict ?: "APPROVE",
            feedback = reviewBody
        )
        runCatching {
            gitWorkspaceService.commentOnPullRequest(
                worktreePath = Path.of(worktreePath),
                pullRequestNumber = pullRequestNumber,
                body = commentBody
            )
        }
        val approvedMetadata = gitWorkspaceService.submitPullRequestReview(
            worktreePath = Path.of(worktreePath),
            pullRequestNumber = pullRequestNumber,
            verdict = PullRequestReviewVerdict.APPROVE,
            body = reviewBody
        )
        if (approvedMetadata.mergeability.equals("DIRTY", ignoreCase = true)) {
            return markReviewQueueMergeConflict(
                itemId = itemId,
                approvedMetadata = approvedMetadata,
                mergeError = ProcessExecutionException(
                    message = "GH command failed",
                    exitCode = 1,
                    stdout = "Pull request #$pullRequestNumber is not mergeable because the merge commit cannot be cleanly created.",
                    stderr = ""
                )
            )
        }
        val mergeResult = try {
            gitWorkspaceService.mergePullRequest(
                worktreePath = Path.of(worktreePath),
                pullRequestNumber = pullRequestNumber
            )
        } catch (error: ProcessExecutionException) {
            if (isMergeConflictFailure(error)) {
                return markReviewQueueMergeConflict(
                    itemId = itemId,
                    approvedMetadata = approvedMetadata,
                    mergeError = error
                )
            }
            throw error
        }
        val baseBranchSync = runCatching {
            gitWorkspaceService.syncBaseBranchAfterMerge(
                worktreePath = Path.of(worktreePath),
                baseBranch = baseBranch
            )
        }.getOrElse { error ->
            BaseBranchSyncResult(
                skippedReason = "Merged PR successfully, but local $baseBranch sync failed: ${error.message ?: error::class.simpleName.orEmpty()}"
            )
        }

        val mergedItem = stateMutex.withLock {
            val state = stateStore.load()
            val currentItem = state.reviewQueue.firstOrNull { it.id == itemId }
                ?: throw IllegalArgumentException("Review queue item not found: $itemId")
            val mergedAt = System.currentTimeMillis()
            val nextMergedItem = currentItem.copy(
                status = ReviewQueueStatus.MERGED,
                pullRequestNumber = approvedMetadata.pullRequestNumber ?: currentItem.pullRequestNumber,
                pullRequestUrl = approvedMetadata.pullRequestUrl ?: currentItem.pullRequestUrl,
                pullRequestState = mergeResult.state ?: approvedMetadata.pullRequestState ?: currentItem.pullRequestState,
                mergeability = approvedMetadata.mergeability ?: currentItem.mergeability,
                ceoVerdict = currentItem.ceoVerdict ?: "APPROVE",
                ceoFeedback = currentItem.ceoFeedback ?: reviewBody,
                ceoReviewedAt = currentItem.ceoReviewedAt ?: mergedAt,
                mergeCommitSha = mergeResult.mergeCommitSha,
                mergedAt = mergedAt,
                updatedAt = mergedAt
            )
            val nextIssues = state.issues.map { issue ->
                if (issue.id == currentItem.issueId) {
                    issue.copy(
                        status = IssueStatus.DONE,
                        pullRequestNumber = nextMergedItem.pullRequestNumber ?: issue.pullRequestNumber,
                        pullRequestUrl = nextMergedItem.pullRequestUrl ?: issue.pullRequestUrl,
                        pullRequestState = nextMergedItem.pullRequestState ?: issue.pullRequestState,
                        ceoVerdict = nextMergedItem.ceoVerdict ?: issue.ceoVerdict,
                        ceoFeedback = nextMergedItem.ceoFeedback ?: issue.ceoFeedback,
                        mergeResult = mergeResult.state ?: "MERGED",
                        transitionReason = "CEO approved and merged PR ${nextMergedItem.pullRequestUrl ?: nextMergedItem.pullRequestNumber}.",
                        updatedAt = mergedAt
                    )
                } else {
                    issue
                }
            }
            val nextGoals = state.goals.map { goal ->
                val unresolved = nextIssues.any { it.goalId == goal.id && it.status != IssueStatus.DONE && it.status != IssueStatus.CANCELED }
                if (goal.id == nextIssues.firstOrNull { it.id == currentItem.issueId }?.goalId && !unresolved) {
                    goal.copy(status = GoalStatus.COMPLETED, updatedAt = mergedAt)
                } else {
                    goal
                }
            }
            val mergedIssue = nextIssues.firstOrNull { it.id == currentItem.issueId }
            val nextState = state.copy(
                reviewQueue = state.reviewQueue.map { if (it.id == itemId) nextMergedItem else it },
                issues = nextIssues,
                goals = nextGoals
            ).recordCompanyActivity(
                companyId = mergedIssue?.companyId ?: currentItem.companyId,
                projectContextId = mergedIssue?.projectContextId ?: currentItem.projectContextId,
                goalId = mergedIssue?.goalId,
                issueId = currentItem.issueId,
                source = "review-queue",
                title = "Merged issue",
                detail = mergedIssue?.title ?: currentItem.id
            ).recordSignal(
                source = "review-queue",
                message = buildString {
                    append("Merged review queue item ${currentItem.id}")
                    if (baseBranchSync.synced) {
                        append(" and synced local ")
                        append(baseBranch)
                        append(" from origin/")
                        append(baseBranch)
                    } else {
                        baseBranchSync.skippedReason?.takeIf { it.isNotBlank() }?.let {
                            append(" (")
                            append(it)
                            append(')')
                        }
                    }
                },
                companyId = mergedIssue?.companyId ?: currentItem.companyId,
                projectContextId = mergedIssue?.projectContextId ?: currentItem.projectContextId,
                goalId = nextIssues.firstOrNull { it.id == currentItem.issueId }?.goalId,
                issueId = currentItem.issueId
            ).withDerivedMetrics()
            stateStore.save(nextState)
            mergedIssue?.let { issue ->
                state.companies.firstOrNull { it.id == issue.companyId }?.let { company ->
                    nextState.projectContexts.firstOrNull { it.id == issue.projectContextId }?.let { context ->
                        writeCompanyContextSnapshot(nextState, company, context)
                    }
                }
            }
            nextMergedItem
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

    private suspend fun markReviewQueueMergeConflict(
        itemId: String,
        approvedMetadata: PublishMetadata,
        mergeError: ProcessExecutionException
    ): ReviewQueueItem {
        val conflictMessage = mergeConflictFeedback(mergeError)
        val updatedItem = stateMutex.withLock {
            val state = stateStore.load()
            val currentItem = state.reviewQueue.firstOrNull { it.id == itemId }
                ?: throw IllegalArgumentException("Review queue item not found: $itemId")
            val executionIssue = state.issues.firstOrNull { it.id == currentItem.issueId }
            val approvalIssue = currentItem.approvalIssueId?.let { approvalIssueId ->
                state.issues.firstOrNull { it.id == approvalIssueId }
            }
            val baseBranchLabel = executionIssue?.companyId?.let { companyId ->
                state.companies.firstOrNull { it.id == companyId }?.defaultBaseBranch
            } ?: "the base branch"
            val now = System.currentTimeMillis()
            val nextItem = currentItem.copy(
                status = ReviewQueueStatus.CHANGES_REQUESTED,
                pullRequestNumber = approvedMetadata.pullRequestNumber ?: currentItem.pullRequestNumber,
                pullRequestUrl = approvedMetadata.pullRequestUrl ?: currentItem.pullRequestUrl,
                pullRequestState = approvedMetadata.pullRequestState ?: currentItem.pullRequestState,
                mergeability = approvedMetadata.mergeability ?: currentItem.mergeability ?: "DIRTY",
                ceoVerdict = "CHANGES_REQUESTED",
                ceoFeedback = conflictMessage,
                ceoReviewedAt = now,
                updatedAt = now
            )
            val nextIssues = state.issues.map { issue ->
                when (issue.id) {
                    executionIssue?.id -> issue.copy(
                        status = IssueStatus.PLANNED,
                        executionIntent = ExecutionIntent.MERGE_CONFLICT_REMEDIATION,
                        pullRequestNumber = nextItem.pullRequestNumber ?: issue.pullRequestNumber,
                        pullRequestUrl = nextItem.pullRequestUrl ?: issue.pullRequestUrl,
                        pullRequestState = nextItem.pullRequestState ?: issue.pullRequestState,
                        ceoVerdict = "CHANGES_REQUESTED",
                        ceoFeedback = conflictMessage,
                        transitionReason = "CEO merge could not complete because PR ${nextItem.pullRequestUrl ?: nextItem.pullRequestNumber} no longer merges cleanly with $baseBranchLabel.",
                        updatedAt = now
                    )
                    approvalIssue?.id -> issue.copy(
                        status = IssueStatus.BLOCKED,
                        ceoVerdict = "CHANGES_REQUESTED",
                        ceoFeedback = conflictMessage,
                        transitionReason = "Merge is blocked until the execution branch is rebased and conflicts are resolved.",
                        updatedAt = now
                    )
                    else -> issue
                }
            }
            val nextState = state.copy(
                reviewQueue = state.reviewQueue.map { if (it.id == itemId) nextItem else it },
                issues = nextIssues
            ).recordCompanyActivity(
                companyId = executionIssue?.companyId ?: currentItem.companyId,
                projectContextId = executionIssue?.projectContextId ?: currentItem.projectContextId,
                goalId = executionIssue?.goalId,
                issueId = executionIssue?.id ?: currentItem.issueId,
                source = "review-queue",
                title = "Merge conflict requires follow-up work",
                detail = conflictMessage,
                severity = "warning"
            ).recordSignal(
                source = "review-queue",
                message = "PR ${nextItem.pullRequestUrl ?: nextItem.pullRequestNumber} could not be merged cleanly and needs remediation.",
                companyId = executionIssue?.companyId ?: currentItem.companyId,
                projectContextId = executionIssue?.projectContextId ?: currentItem.projectContextId,
                goalId = executionIssue?.goalId,
                issueId = executionIssue?.id ?: currentItem.issueId,
                severity = "warning"
            ).withDerivedMetrics()
            stateStore.save(nextState)
            nextItem
        }
        val executionIssue = stateStore.load().issues.firstOrNull { it.id == updatedItem.issueId }
        if (executionIssue != null) {
            mirrorIssueToLinear(
                executionIssue.id,
                comment = "Cotor could not merge \"${executionIssue.title}\" because the pull request no longer merges cleanly: $conflictMessage"
            )
        }
        return updatedItem
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
            val existingRuntime = state.companyRuntimes
                .firstOrNull { it.companyId == resolvedCompanyId }
                ?.let(::runtimeWithinCurrentBudgetWindow)
                ?: CompanyRuntimeSnapshot(companyId = resolvedCompanyId)
            val now = System.currentTimeMillis()
            val nextState = state.copy(
                companyRuntimes = upsertCompanyRuntime(
                    state.companyRuntimes,
                    existingRuntime.copy(
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
                        manuallyStoppedAt = null,
                        budgetPausedAt = null,
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
        serviceScope.launch {
            runCatching { runCompanyRuntimeTick(effectiveCompanyId) }
                .onFailure { cause -> markCompanyRuntimeError(effectiveCompanyId, cause) }
        }
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
                        manuallyStoppedAt = now,
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
            // A runtime tick is the autonomous "housekeeping plus scheduling" pass for one
            // company. The order matters: first reconcile stale terminal states from prior runs,
            // then revive recoverable work, then synthesize any follow-up goals, and only after the
            // state is coherent decide which new issues are allowed to start.
            markRuntimeTickHeartbeat(companyId, "tick-started")
            migrateLegacyFollowUpGoals(companyId)
            reconcileStaleAgentRuns(companyId)
            reconcileNonPublishingReviewRuns(companyId)
            val repairedWorkflowLineages = repairWorkflowLineages(companyId)
            val reopenedInterruptedIssues = reopenInterruptedBlockedIssues(companyId)
            val reconciledIssues = reconcileTerminalIssueStates(companyId)
            var normalizedStates = normalizeCompanyAutomationState(companyId)
            val initial = stateStore.load()
            val runtime = initial.companyRuntimes.firstOrNull { it.companyId == companyId } ?: CompanyRuntimeSnapshot(companyId = companyId)
            if (runtime.status != CompanyRuntimeStatus.RUNNING) {
                return@withLock runtimeStatus(companyId)
            }
            if (isBudgetExhausted(initial, companyId, runtime)) {
                return@withLock updateRuntimeAfterTick(companyId, lastAction = "budget-paused")
            }
            runCatching { startCompanyBackend(companyId) }

            val actions = mutableListOf<String>()
            if (reopenedInterruptedIssues > 0) {
                actions += "reopened-interrupted:$reopenedInterruptedIssues"
            }
            if (repairedWorkflowLineages > 0) {
                actions += "repaired-workflow-lineages:$repairedWorkflowLineages"
            }
            if (reconciledIssues > 0) {
                actions += "reconciled-issues:$reconciledIssues"
            }
            if (normalizedStates > 0) {
                actions += "normalized-states:$normalizedStates"
            }
            val resolvedGitHubReadinessIssues = resolveGitHubReadinessBlockedIssues(companyId)
            if (resolvedGitHubReadinessIssues > 0) {
                actions += "resolved-github-readiness:$resolvedGitHubReadinessIssues"
                normalizedStates = normalizeCompanyAutomationState(companyId)
                if (normalizedStates > 0) {
                    actions += "renormalized-after-github-readiness:$normalizedStates"
                }
            }
            val reopenedResolvedMergeConflicts = reopenResolvedMergeConflictIssues(companyId)
            if (reopenedResolvedMergeConflicts > 0) {
                actions += "resolved-merge-conflicts:$reopenedResolvedMergeConflicts"
                normalizedStates = normalizeCompanyAutomationState(companyId)
                if (normalizedStates > 0) {
                    actions += "renormalized-after-merge-conflicts:$normalizedStates"
                }
            }
            val requeuedLegacyMergeConflictIssues = requeueLegacyMergeConflictExecutionIssues(companyId)
            if (requeuedLegacyMergeConflictIssues > 0) {
                actions += "requeued-merge-conflicts:$requeuedLegacyMergeConflictIssues"
            }
            val reopenedNoOpPrIssues = reopenNoOpPullRequestExecutionIssues(companyId)
            if (reopenedNoOpPrIssues > 0) {
                actions += "recovered-existing-pr:$reopenedNoOpPrIssues"
                normalizedStates = normalizeCompanyAutomationState(companyId)
                if (normalizedStates > 0) {
                    actions += "renormalized-after-existing-pr:$normalizedStates"
                }
            }
            val recoveredBlockedIssues = requeueRecoverableBlockedIssues(companyId)
            if (recoveredBlockedIssues > 0) {
                actions += "retried-recoverable:$recoveredBlockedIssues"
                normalizedStates = normalizeCompanyAutomationState(companyId)
                if (normalizedStates > 0) {
                    actions += "reactivated-goals:$normalizedStates"
                }
            }
            val archivedRecursiveGoals = archiveRecursiveFollowUpGoals(companyId)
            if (archivedRecursiveGoals > 0) {
                actions += "archived-recursive-goals:$archivedRecursiveGoals"
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
                val hasOpenNonPlanningIssues = current.issues.any { issue ->
                    issue.goalId == goal.id &&
                        !issue.kind.equals("planning", ignoreCase = true) &&
                        issue.status != IssueStatus.DONE &&
                        issue.status != IssueStatus.CANCELED
                }
                if (!hasOpenNonPlanningIssues) {
                    decomposeGoal(goal.id)
                    actions += "decomposed:${goal.id}"
                }
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
            val tasksByIssueId = executionSnapshot.tasks
                .filter { it.issueId != null }
                .groupBy { it.issueId!! }
            val latestRunsByTaskId = executionSnapshot.runs
                .groupBy { it.taskId }
                .mapValues { (_, runs) -> runs.maxByOrNull { it.updatedAt }!! }
            val now = System.currentTimeMillis()
            val runnableIssues = executionSnapshot.issues
                .filter { issue ->
                    executionSnapshot.goals.firstOrNull { it.id == issue.goalId }?.let { goal ->
                        goal.autonomyEnabled && goal.status == GoalStatus.ACTIVE && goal.companyId == companyId
                    } == true
                }
                .filter { issue ->
                    issue.status == IssueStatus.PLANNED || issue.status == IssueStatus.BACKLOG || issue.status == IssueStatus.DELEGATED
                }
                .filterNot { issue ->
                    issue.sourceSignal == "github-readiness"
                }
                .sortedWith(
                    compareBy<CompanyIssue> { runnableIssueStatusRank(it.status) }
                        .thenBy { it.priority }
                        .thenBy { it.createdAt }
                )
                .filter { issue ->
                    val dependenciesSatisfied = issue.dependsOn.all { dependencyId ->
                        val dependency = executionSnapshot.issues.firstOrNull { it.id == dependencyId } ?: return@all false
                        isDependencySatisfied(issue, dependency, executionSnapshot)
                    }
                    val alreadyStarted = executionSnapshot.tasks.any { task ->
                        task.issueId == issue.id &&
                            (task.status == DesktopTaskStatus.RUNNING || task.status == DesktopTaskStatus.QUEUED)
                    }
                    val retryDecision = resolveRecoverableRetryDecision(
                        tasksByIssueId[issue.id].orEmpty(),
                        latestRunsByTaskId,
                        now
                    )
                    val waitingForRetryCooldown =
                        issue.status in setOf(IssueStatus.PLANNED, IssueStatus.BACKLOG, IssueStatus.DELEGATED) &&
                            retryDecision.mode == RecoverableRetryMode.WAITING
                    dependenciesSatisfied && !alreadyStarted && !waitingForRetryCooldown
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

            for (runnableIssue in startableIssues) {
                val startedIssue = runCatching { startDelegatedIssue(runnableIssue) }
                    .getOrElse { cause -> handleDelegatedIssueStartFailure(runnableIssue, cause) }
                actions += if (startedIssue.status == IssueStatus.BLOCKED) {
                    "start-blocked:${startedIssue.id}"
                } else {
                    "started:${startedIssue.id}"
                }
            }

            val settledState = stateStore.load()
            val effectiveLastAction = when {
                actions.isNotEmpty() -> actions.joinToString(separator = ", ")
                hasActiveCompanyTasks(settledState, companyId) -> "monitoring-active-runs"
                hasPendingCompanyIssues(settledState, companyId) -> "idle-pending-issues"
                else -> "idle-no-work"
            }

            updateRuntimeAfterTick(
                companyId,
                lastAction = effectiveLastAction
            )
        }
    }

    private fun hasActiveCompanyTasks(state: DesktopAppState, companyId: String): Boolean {
        val issueCompanyById = state.issues.associate { it.id to it.companyId }
        return state.tasks.any { task ->
            (task.status == DesktopTaskStatus.RUNNING || task.status == DesktopTaskStatus.QUEUED) &&
                task.issueId?.let(issueCompanyById::get) == companyId
        }
    }

    private fun hasQueuedCompanyIssues(state: DesktopAppState, companyId: String): Boolean =
        state.issues.any { issue ->
            issue.companyId == companyId &&
                (issue.status == IssueStatus.PLANNED ||
                    issue.status == IssueStatus.BACKLOG ||
                    issue.status == IssueStatus.DELEGATED)
        }

    private fun queuedCompanyIssueCount(state: DesktopAppState, companyId: String): Int =
        state.issues.count { issue ->
            issue.companyId == companyId &&
                (issue.status == IssueStatus.PLANNED ||
                    issue.status == IssueStatus.BACKLOG ||
                    issue.status == IssueStatus.DELEGATED)
        }

    private fun hasPendingCompanyIssues(state: DesktopAppState, companyId: String): Boolean =
        state.issues.any { issue ->
            issue.companyId == companyId &&
                issue.status != IssueStatus.DONE &&
                issue.status != IssueStatus.CANCELED
        }

    private fun isCompanyManuallyStopped(state: DesktopAppState, companyId: String): Boolean =
        state.companyRuntimes.firstOrNull { it.companyId == companyId }?.manuallyStoppedAt != null

    private suspend fun isCompanyManuallyStopped(companyId: String): Boolean =
        isCompanyManuallyStopped(stateStore.load(), companyId)

    private suspend fun markRuntimeTickHeartbeat(companyId: String, lastAction: String) {
        stateMutex.withLock {
            val state = stateStore.load()
            val current = state.companyRuntimes.firstOrNull { it.companyId == companyId } ?: return@withLock
            if (current.status != CompanyRuntimeStatus.RUNNING) {
                return@withLock
            }
            stateStore.save(
                state.copy(
                    companyRuntimes = upsertCompanyRuntime(
                        state.companyRuntimes,
                        current.copy(
                            lastTickAt = System.currentTimeMillis(),
                            lastAction = lastAction,
                            lastError = null
                        )
                    )
                ).withDerivedMetrics()
            )
        }
    }

    private suspend fun recordCompanyRuntimeResume(companyId: String, queuedIssueCount: Int) {
        stateMutex.withLock {
            val state = stateStore.load()
            if (queuedIssueCount <= 0) {
                return@withLock
            }
            val detail = when (queuedIssueCount) {
                1 -> "1 queued issue resumed after the app-server restarted."
                else -> "$queuedIssueCount queued issues resumed after the app-server restarted."
            }
            if (state.hasRecentCompanyActivity(companyId, "Resumed delegated issues", detail)) {
                return@withLock
            }
            val current = state.companyRuntimes.firstOrNull { it.companyId == companyId }
            val nextState = state
                .copy(
                    companyRuntimes = current?.let {
                        upsertCompanyRuntime(
                            state.companyRuntimes,
                            it.copy(
                                lastTickAt = System.currentTimeMillis(),
                                lastAction = "resuming-interrupted-issues",
                                lastError = null
                            )
                        )
                    } ?: state.companyRuntimes
                )
                .recordCompanyActivity(
                    companyId = companyId,
                    source = "runtime",
                    title = "Resumed delegated issues",
                    detail = detail,
                    severity = "info"
                )
                .withDerivedMetrics()
            stateStore.save(nextState)
        }
    }

    private data class GitHubReadinessRecoveryCandidate(
        val issueId: String,
        val infraIssueIds: Set<String>,
        val repositoryRoot: Path,
        val baseBranch: String
    )

    private data class MergeConflictRecoveryCandidate(
        val reviewQueueItemId: String,
        val executionIssueId: String,
        val approvalIssueId: String,
        val pullRequestNumber: Int,
        val worktreePath: Path
    )

    private data class NoOpPullRequestRecoveryCandidate(
        val executionIssueId: String,
        val pullRequestNumber: Int,
        val worktreePath: Path,
        val latestTask: AgentTask,
        val latestRun: AgentRun
    )

    private suspend fun resolveGitHubReadinessBlockedIssues(companyId: String): Int {
        val candidates = stateMutex.withLock {
            val state = stateStore.load()
            val issuesById = state.issues.associateBy { it.id }
            state.issues
                .filter { issue ->
                    issue.companyId == companyId &&
                        issue.status == IssueStatus.BLOCKED &&
                        issue.blockedBy.isNotEmpty() &&
                        requiresGitHubPullRequest(issue, state)
                }
                .mapNotNull { issue ->
                    val readinessInfraIssueIds = issue.blockedBy
                        .mapNotNull(issuesById::get)
                        .filter { dependency ->
                            dependency.sourceSignal == "github-readiness" &&
                                dependency.kind.equals("infra", ignoreCase = true) &&
                                dependency.status != IssueStatus.DONE &&
                                dependency.status != IssueStatus.CANCELED
                        }
                        .map { it.id }
                        .toSet()
                    if (readinessInfraIssueIds.isEmpty()) {
                        return@mapNotNull null
                    }
                    val workspace = state.workspaces.firstOrNull { it.id == issue.workspaceId } ?: return@mapNotNull null
                    val repository = state.repositories.firstOrNull { it.id == workspace.repositoryId } ?: return@mapNotNull null
                    GitHubReadinessRecoveryCandidate(
                        issueId = issue.id,
                        infraIssueIds = readinessInfraIssueIds,
                        repositoryRoot = Path.of(repository.localPath),
                        baseBranch = workspace.baseBranch
                    )
                }
        }
        if (candidates.isEmpty()) {
            return 0
        }

        val recoveredIssueIds = candidates
            .filter { candidate ->
                runCatching {
                    gitWorkspaceService.ensureGitHubPublishReady(
                        worktreePath = candidate.repositoryRoot,
                        baseBranch = candidate.baseBranch
                    )
                }.getOrNull()?.ready == true
            }
            .map { it.issueId }
            .toSet()
        if (recoveredIssueIds.isEmpty()) {
            return 0
        }

        return stateMutex.withLock {
            val state = stateStore.load()
            val issuesById = state.issues.associateBy { it.id }
            val candidatesByIssueId = candidates.associateBy { it.issueId }
            val now = System.currentTimeMillis()
            var changed = 0
            val recoveredInfraIssueIds = recoveredIssueIds
                .mapNotNull(candidatesByIssueId::get)
                .flatMap { it.infraIssueIds }
                .toSet()
            val updatedIssues = state.issues.map { issue ->
                if (issue.id !in recoveredIssueIds) {
                    return@map issue
                }
                val candidate = candidatesByIssueId[issue.id] ?: return@map issue
                val readinessDependencies = issue.blockedBy.filter { it in candidate.infraIssueIds }
                if (readinessDependencies.isEmpty()) {
                    return@map issue
                }
                val remainingBlockedBy = issue.blockedBy.filterNot { it in candidate.infraIssueIds }
                changed += 1
                issue.copy(
                    status = if (remainingBlockedBy.isEmpty()) IssueStatus.PLANNED else IssueStatus.BLOCKED,
                    blockedBy = remainingBlockedBy,
                    transitionReason = if (remainingBlockedBy.isEmpty()) null else issue.transitionReason,
                    updatedAt = now
                )
            }.toMutableList()

            val stillBlockingInfraIds = updatedIssues
                .flatMap { it.blockedBy }
                .toSet()
            for (index in updatedIssues.indices) {
                val issue = updatedIssues[index]
                if (
                    issue.id in recoveredInfraIssueIds &&
                    issue.sourceSignal == "github-readiness" &&
                    issue.kind.equals("infra", ignoreCase = true) &&
                    issue.id !in stillBlockingInfraIds &&
                    issue.status != IssueStatus.DONE
                ) {
                    updatedIssues[index] = issue.copy(
                        status = IssueStatus.DONE,
                        transitionReason = "Resolved automatically after GitHub publishing became ready.",
                        updatedAt = now
                    )
                }
            }

            if (changed == 0) {
                return@withLock 0
            }

            var nextState = state.copy(issues = updatedIssues)
            recoveredIssueIds.forEach { issueId ->
                val issue = updatedIssues.firstOrNull { it.id == issueId } ?: return@forEach
                nextState = nextState.recordCompanyActivity(
                    companyId = issue.companyId,
                    projectContextId = issue.projectContextId,
                    goalId = issue.goalId,
                    issueId = issue.id,
                    source = "github-readiness",
                    title = "Unblocked code issue",
                    detail = issue.title
                )
            }
            updatedIssues
                .filter { it.id in recoveredInfraIssueIds && it.sourceSignal == "github-readiness" && it.kind.equals("infra", ignoreCase = true) && it.status == IssueStatus.DONE }
                .forEach { infraIssue ->
                    nextState = nextState.recordCompanyActivity(
                        companyId = infraIssue.companyId,
                        projectContextId = infraIssue.projectContextId,
                        goalId = infraIssue.goalId,
                        issueId = infraIssue.id,
                        source = "github-readiness",
                        title = "Resolved infra issue",
                        detail = infraIssue.title
                    )
                }
            stateStore.save(nextState.withDerivedMetrics())
            changed
        }
    }

    private suspend fun reconcileSupersededManagedPullRequests(
        companyId: String,
        force: Boolean = false
    ): Int {
        val now = System.currentTimeMillis()
        if (!force) {
            val lastCleanupAt = recentSupersededPullRequestCleanupAt[companyId]
            if (lastCleanupAt != null && now - lastCleanupAt < SUPERSEDED_PR_RECONCILIATION_INTERVAL_MS) {
                return 0
            }
        }

        val snapshot = stateStore.load()
        val company = snapshot.companies.firstOrNull { it.id == companyId } ?: return 0
        val rootPath = company.rootPath.takeIf { it.isNotBlank() } ?: return 0
        val companyIdsForRepository = snapshot.companies
            .filter { it.rootPath == rootPath }
            .map { it.id }
            .toSet()
        val preservePullRequestNumbers = buildSet {
            snapshot.reviewQueue
                .filter {
                    it.companyId in companyIdsForRepository &&
                        it.pullRequestNumber != null &&
                        !it.pullRequestState.equals("MERGED", ignoreCase = true) &&
                        !it.pullRequestState.equals("CLOSED", ignoreCase = true)
                }
                .mapNotNullTo(this) { it.pullRequestNumber }
            snapshot.issues
                .filter {
                    it.companyId in companyIdsForRepository &&
                        it.pullRequestNumber != null &&
                        !it.pullRequestState.equals("MERGED", ignoreCase = true) &&
                        !it.pullRequestState.equals("CLOSED", ignoreCase = true)
                }
                .mapNotNullTo(this) { it.pullRequestNumber }
        }

        val cleanupResult = runCatching {
            gitWorkspaceService.closeSupersededManagedPullRequests(
                worktreePath = Path.of(rootPath),
                preservePullRequestNumbers = preservePullRequestNumbers
            )
        }.getOrElse {
            if (it is CancellationException) {
                throw it
            }
            appendCompanyRuntimeErrorLog(
                companyId = companyId,
                cause = IllegalStateException(
                    "Failed to reconcile superseded PRs for ${company.name}: ${it.message ?: it::class.simpleName}",
                    it
                )
            )
            recentSupersededPullRequestCleanupAt[companyId] = now
            return 0
        }
        val closedPullRequestNumbers = cleanupResult.closedPullRequestNumbers
        recentSupersededPullRequestCleanupAt[companyId] = now
        if (closedPullRequestNumbers.isEmpty()) {
            return 0
        }

        stateMutex.withLock {
            val state = stateStore.load()
            var nextState = state.copy(
                reviewQueue = state.reviewQueue.map { item ->
                    if (item.companyId in companyIdsForRepository && item.pullRequestNumber in closedPullRequestNumbers) {
                        item.copy(
                            pullRequestState = "CLOSED",
                            updatedAt = now
                        )
                    } else {
                        item
                    }
                },
                issues = state.issues.map { issue ->
                    if (issue.companyId in companyIdsForRepository && issue.pullRequestNumber in closedPullRequestNumbers) {
                        issue.copy(
                            pullRequestState = "CLOSED",
                            updatedAt = now
                        )
                    } else {
                        issue
                    }
                }
            )
            nextState = nextState.recordCompanyActivity(
                companyId = companyId,
                source = "review-queue",
                title = "Closed superseded pull requests",
                detail = buildString {
                    append("Closed ")
                    append(closedPullRequestNumbers.size)
                    append(" outdated Cotor-managed PR")
                    if (closedPullRequestNumbers.size != 1) append('s')
                    append(": ")
                    append(closedPullRequestNumbers.joinToString(", ") { "#$it" })
                }
            ).recordSignal(
                source = "review-queue",
                message = "Closed ${closedPullRequestNumbers.size} superseded Cotor-managed PRs for ${company.name}.",
                companyId = companyId
            ).withDerivedMetrics()
            stateStore.save(nextState)
        }
        return closedPullRequestNumbers.size
    }

    private suspend fun requeueLegacyMergeConflictExecutionIssues(companyId: String): Int {
        return stateMutex.withLock {
            val state = stateStore.load()
            val issuesById = state.issues.associateBy { it.id }
            val activeIssueIds = state.tasks
                .filter { it.status == DesktopTaskStatus.RUNNING || it.status == DesktopTaskStatus.QUEUED }
                .mapNotNull { it.issueId }
                .toSet()
            val now = System.currentTimeMillis()
            val candidateExecutionIds = state.reviewQueue.mapNotNull { item ->
                if (item.companyId != companyId || item.status != ReviewQueueStatus.CHANGES_REQUESTED) {
                    return@mapNotNull null
                }
                if (!item.mergeability.equals("DIRTY", ignoreCase = true)) {
                    return@mapNotNull null
                }
                val executionIssue = issuesById[item.issueId] ?: return@mapNotNull null
                if (executionIssue.status != IssueStatus.BLOCKED || executionIssue.id in activeIssueIds) {
                    return@mapNotNull null
                }
                val approvalIssue = item.approvalIssueId?.let(issuesById::get)
                val blockedByMergeConflict =
                    approvalIssue?.transitionReason?.contains(
                        "Merge is blocked until the execution branch is rebased and conflicts are resolved.",
                        ignoreCase = true
                    ) == true ||
                        item.ceoFeedback.orEmpty().contains("could not merge this pull request cleanly", ignoreCase = true) ||
                        item.ceoFeedback.orEmpty().contains("merge conflict", ignoreCase = true) ||
                        executionIssue.ceoFeedback.orEmpty().contains("could not merge this pull request cleanly", ignoreCase = true) ||
                        executionIssue.ceoFeedback.orEmpty().contains("merge conflict", ignoreCase = true)
                if (!blockedByMergeConflict) {
                    return@mapNotNull null
                }
                executionIssue.id
            }.toSet()
            if (candidateExecutionIds.isEmpty()) {
                return@withLock 0
            }

            var nextState = state.copy(
                issues = state.issues.map { issue ->
                    if (issue.id in candidateExecutionIds) {
                        issue.copy(
                            status = IssueStatus.PLANNED,
                            transitionReason = "Detected a legacy CEO merge-conflict blocker and re-queued execution to rebase and republish the PR.",
                            updatedAt = now
                        )
                    } else {
                        issue
                    }
                }
            )
            candidateExecutionIds.forEach { issueId ->
                val executionIssue = nextState.issues.firstOrNull { it.id == issueId } ?: return@forEach
                nextState = nextState.recordCompanyActivity(
                    companyId = executionIssue.companyId,
                    projectContextId = executionIssue.projectContextId,
                    goalId = executionIssue.goalId,
                    issueId = executionIssue.id,
                    source = "review-queue",
                    title = "Re-queued merge-conflict remediation",
                    detail = executionIssue.pullRequestUrl ?: executionIssue.title,
                    severity = "warning"
                ).recordSignal(
                    source = "review-queue",
                    message = "Re-queued ${executionIssue.title} to remediate a stale merge-conflict block.",
                    companyId = executionIssue.companyId,
                    projectContextId = executionIssue.projectContextId,
                    goalId = executionIssue.goalId,
                    issueId = executionIssue.id,
                    severity = "warning"
                )
            }
            stateStore.save(nextState.withDerivedMetrics())
            candidateExecutionIds.size
        }
    }

    private suspend fun reopenResolvedMergeConflictIssues(companyId: String): Int {
        val candidates = stateMutex.withLock {
            val state = stateStore.load()
            val issuesById = state.issues.associateBy { it.id }
            state.reviewQueue.mapNotNull { item ->
                if (item.companyId != companyId || item.status != ReviewQueueStatus.CHANGES_REQUESTED) {
                    return@mapNotNull null
                }
                if (item.pullRequestNumber == null || item.worktreePath.isNullOrBlank()) {
                    return@mapNotNull null
                }
                if (!item.mergeability.equals("DIRTY", ignoreCase = true)) {
                    return@mapNotNull null
                }
                val approvalIssue = item.approvalIssueId?.let(issuesById::get) ?: return@mapNotNull null
                val executionIssue = issuesById[item.issueId] ?: return@mapNotNull null
                if (approvalIssue.status != IssueStatus.BLOCKED) {
                    return@mapNotNull null
                }
                val blockedByMergeConflict =
                    approvalIssue.transitionReason?.contains(
                        "Merge is blocked until the execution branch is rebased and conflicts are resolved.",
                        ignoreCase = true
                    ) == true ||
                        item.ceoFeedback.orEmpty().contains("could not merge this pull request cleanly", ignoreCase = true) ||
                        item.ceoFeedback.orEmpty().contains("merge conflict", ignoreCase = true)
                if (!blockedByMergeConflict || executionIssue.status in setOf(IssueStatus.DONE, IssueStatus.CANCELED)) {
                    return@mapNotNull null
                }
                MergeConflictRecoveryCandidate(
                    reviewQueueItemId = item.id,
                    executionIssueId = executionIssue.id,
                    approvalIssueId = approvalIssue.id,
                    pullRequestNumber = item.pullRequestNumber,
                    worktreePath = Path.of(item.worktreePath)
                )
            }
        }
        if (candidates.isEmpty()) {
            return 0
        }

        val refreshedMetadataByQueueId = candidates.mapNotNull { candidate ->
            val refreshed = runCatching {
                gitWorkspaceService.refreshPullRequestMetadata(candidate.worktreePath, candidate.pullRequestNumber)
            }.getOrNull() ?: return@mapNotNull null
            if (!refreshed.pullRequestState.equals("OPEN", ignoreCase = true)) {
                return@mapNotNull null
            }
            if (!refreshed.mergeability.equals("CLEAN", ignoreCase = true)) {
                return@mapNotNull null
            }
            candidate.reviewQueueItemId to refreshed
        }.toMap()
        if (refreshedMetadataByQueueId.isEmpty()) {
            return 0
        }

        return stateMutex.withLock {
            val state = stateStore.load()
            val candidatesByQueueId = candidates.associateBy { it.reviewQueueItemId }
            val now = System.currentTimeMillis()
            var reopened = 0
            val reopenedQueueIds = linkedSetOf<String>()
            val updatedReviewQueue = state.reviewQueue.map { item ->
                val refreshed = refreshedMetadataByQueueId[item.id] ?: return@map item
                val candidate = candidatesByQueueId[item.id] ?: return@map item
                val approvalIssue = state.issues.firstOrNull { it.id == candidate.approvalIssueId } ?: return@map item
                if (approvalIssue.status != IssueStatus.BLOCKED) {
                    return@map item
                }
                reopened += 1
                reopenedQueueIds += item.id
                item.copy(
                    status = ReviewQueueStatus.READY_FOR_CEO,
                    pullRequestState = refreshed.pullRequestState ?: item.pullRequestState,
                    mergeability = refreshed.mergeability ?: item.mergeability,
                    ceoVerdict = null,
                    ceoFeedback = null,
                    ceoReviewedAt = null,
                    updatedAt = now
                )
            }

            if (reopened == 0) {
                return@withLock 0
            }

            val recoveredExecutionIssueIds = candidates
                .filter { it.reviewQueueItemId in reopenedQueueIds }
                .map { it.executionIssueId }
                .toSet()
            val recoveredApprovalIssueIds = candidates
                .filter { it.reviewQueueItemId in reopenedQueueIds }
                .map { it.approvalIssueId }
                .toSet()
            val updatedIssues = state.issues.map { issue ->
                when (issue.id) {
                    in recoveredExecutionIssueIds -> issue.copy(
                        status = IssueStatus.READY_FOR_CEO,
                        ceoVerdict = null,
                        ceoFeedback = null,
                        transitionReason = "Merge conflict was resolved on ${issue.pullRequestUrl ?: "the pull request"}; ready for CEO approval again.",
                        updatedAt = now
                    )
                    in recoveredApprovalIssueIds -> issue.copy(
                        status = IssueStatus.PLANNED,
                        ceoVerdict = null,
                        ceoFeedback = null,
                        transitionReason = "Merge conflict was resolved; CEO approval can run again.",
                        updatedAt = now
                    )
                    else -> issue
                }
            }

            var nextState = state.copy(
                reviewQueue = updatedReviewQueue,
                issues = updatedIssues
            )
            reopenedQueueIds.forEach { queueId ->
                val queueItem = updatedReviewQueue.firstOrNull { it.id == queueId } ?: return@forEach
                val executionIssue = updatedIssues.firstOrNull { it.id == queueItem.issueId } ?: return@forEach
                nextState = nextState.recordCompanyActivity(
                    companyId = executionIssue.companyId,
                    projectContextId = executionIssue.projectContextId,
                    goalId = executionIssue.goalId,
                    issueId = executionIssue.id,
                    source = "review-queue",
                    title = "Resolved merge conflict block",
                    detail = queueItem.pullRequestUrl ?: "PR #${queueItem.pullRequestNumber}"
                ).recordSignal(
                    source = "review-queue",
                    message = "PR ${queueItem.pullRequestUrl ?: queueItem.pullRequestNumber} merges cleanly again and is back in the CEO lane.",
                    companyId = executionIssue.companyId,
                    projectContextId = executionIssue.projectContextId,
                    goalId = executionIssue.goalId,
                    issueId = executionIssue.id
                )
            }
            stateStore.save(nextState.withDerivedMetrics())
            reopened
        }
    }

    private suspend fun reopenNoOpPullRequestExecutionIssues(companyId: String): Int {
        val candidates = stateMutex.withLock {
            val state = stateStore.load()
            val latestTasksByIssueId = state.tasks
                .filter { it.issueId != null }
                .groupBy { it.issueId!! }
                .mapValues { (_, tasks) -> tasks.maxByOrNull { it.updatedAt }!! }
            val latestRunsByTaskId = state.runs
                .groupBy { it.taskId }
                .mapValues { (_, runs) -> runs.maxByOrNull { it.updatedAt }!! }
            state.issues.mapNotNull { issue ->
                if (issue.companyId != companyId || issue.kind != "execution" || issue.status != IssueStatus.BLOCKED) {
                    return@mapNotNull null
                }
                if (issue.pullRequestNumber == null || issue.worktreePath.isNullOrBlank()) {
                    return@mapNotNull null
                }
                val latestTask = latestTasksByIssueId[issue.id] ?: return@mapNotNull null
                val latestRun = latestRunsByTaskId[latestTask.id] ?: return@mapNotNull null
                if (
                    latestTask.status !in setOf(DesktopTaskStatus.COMPLETED, DesktopTaskStatus.FAILED, DesktopTaskStatus.PARTIAL) ||
                        latestRun.status !in setOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED)
                ) {
                    return@mapNotNull null
                }
                if (!isExistingPrNoDiffReuseCandidate(issue, latestRun)) {
                    return@mapNotNull null
                }
                NoOpPullRequestRecoveryCandidate(
                    executionIssueId = issue.id,
                    pullRequestNumber = issue.pullRequestNumber,
                    worktreePath = Path.of(issue.worktreePath),
                    latestTask = latestTask,
                    latestRun = latestRun
                )
            }
        }
        if (candidates.isEmpty()) {
            return 0
        }

        val recoveredByIssueId = candidates.mapNotNull { candidate ->
            val refreshed = runCatching {
                gitWorkspaceService.refreshPullRequestMetadata(candidate.worktreePath, candidate.pullRequestNumber)
            }.getOrNull() ?: return@mapNotNull null
            val pullRequestState = refreshed.pullRequestState?.uppercase()
            val mergeability = refreshed.mergeability?.uppercase()
            if (
                pullRequestState != "MERGED" &&
                    !(pullRequestState == "OPEN" && mergeability in setOf("CLEAN", "DIRTY"))
            ) {
                return@mapNotNull null
            }
            candidate.executionIssueId to refreshed
        }.toMap()
        if (recoveredByIssueId.isEmpty()) {
            return 0
        }

        return stateMutex.withLock {
            val state = stateStore.load()
            val candidatesByIssueId = candidates.associateBy { it.executionIssueId }
            val companyProfiles = ensureOrgProfiles(state.orgProfiles, state.companyAgentDefinitions, state.companies)
                .filter { it.companyId == companyId }
            val chiefProfile = findChiefProfile(companyId, companyProfiles)
            val now = System.currentTimeMillis()
            var reopened = 0
            val updatedItems = mutableMapOf<String, ReviewQueueItem>()
            val approvalIssueUpdates = mutableMapOf<String, CompanyIssue>()
            val updatedIssues = state.issues.map { issue ->
                val refreshed = recoveredByIssueId[issue.id] ?: return@map issue
                val candidate = candidatesByIssueId[issue.id] ?: return@map issue
                reopened += 1
                val existingQueueItem = state.reviewQueue.firstOrNull { it.issueId == issue.id }
                val existingApprovalIssue = state.issues.firstOrNull {
                    relatedExecutionIssueId(it) == issue.id && it.kind.equals("approval", ignoreCase = true)
                }
                val existingQaVerdict = existingQueueItem?.qaVerdict ?: issue.qaVerdict
                val existingQaFeedback = existingQueueItem?.qaFeedback ?: issue.qaFeedback
                val baseQueueItem = ReviewQueueItem(
                    id = existingQueueItem?.id ?: UUID.randomUUID().toString(),
                    companyId = issue.companyId,
                    projectContextId = issue.projectContextId,
                    issueId = issue.id,
                    runId = candidate.latestRun.id,
                    branchName = candidate.latestRun.branchName ?: issue.branchName,
                    worktreePath = candidate.latestRun.worktreePath ?: issue.worktreePath,
                    pullRequestNumber = refreshed.pullRequestNumber ?: issue.pullRequestNumber,
                    pullRequestUrl = refreshed.pullRequestUrl ?: issue.pullRequestUrl,
                    pullRequestState = refreshed.pullRequestState ?: issue.pullRequestState,
                    status = existingQueueItem?.status ?: ReviewQueueStatus.AWAITING_QA,
                    checksSummary = candidate.latestRun.error,
                    mergeability = refreshed.mergeability,
                    requestedReviewers = emptyList(),
                    qaVerdict = existingQaVerdict,
                    qaFeedback = existingQaFeedback,
                    qaReviewedAt = existingQueueItem?.qaReviewedAt,
                    qaIssueId = existingQueueItem?.qaIssueId,
                    ceoVerdict = existingQueueItem?.ceoVerdict,
                    ceoFeedback = existingQueueItem?.ceoFeedback,
                    ceoReviewedAt = existingQueueItem?.ceoReviewedAt,
                    approvalIssueId = existingApprovalIssue?.id ?: existingQueueItem?.approvalIssueId,
                    createdAt = existingQueueItem?.createdAt ?: now,
                    updatedAt = now
                )
                val refreshedState = refreshed.pullRequestState?.uppercase()
                val refreshedMergeability = refreshed.mergeability?.uppercase()
                when {
                    refreshedState == "MERGED" -> {
                        updatedItems[issue.id] = baseQueueItem.copy(
                            status = ReviewQueueStatus.MERGED,
                            mergeability = refreshed.mergeability ?: baseQueueItem.mergeability,
                            ceoVerdict = baseQueueItem.ceoVerdict ?: issue.ceoVerdict ?: "APPROVE",
                            ceoFeedback = baseQueueItem.ceoFeedback ?: issue.ceoFeedback,
                            ceoReviewedAt = baseQueueItem.ceoReviewedAt ?: now,
                            mergedAt = existingQueueItem?.mergedAt ?: now,
                            approvalIssueId = existingApprovalIssue?.id ?: baseQueueItem.approvalIssueId,
                            updatedAt = now
                        )
                        existingApprovalIssue?.let { approvalIssue ->
                            approvalIssueUpdates[approvalIssue.id] = approvalIssue.copy(
                                status = IssueStatus.DONE,
                                pullRequestState = "MERGED",
                                transitionReason = "Existing PR was already merged while resolving a no-diff remediation.",
                                updatedAt = now
                            )
                        }
                        issue.copy(
                            status = IssueStatus.DONE,
                            executionIntent = issue.executionIntent ?: inferExecutionIntent(
                                kind = issue.kind,
                                title = issue.title,
                                description = issue.description,
                                plannedCodeProducing = issue.codeProducing
                            ),
                            pullRequestNumber = baseQueueItem.pullRequestNumber ?: issue.pullRequestNumber,
                            pullRequestUrl = baseQueueItem.pullRequestUrl ?: issue.pullRequestUrl,
                            pullRequestState = "MERGED",
                            mergeResult = "MERGED",
                            ceoVerdict = baseQueueItem.ceoVerdict ?: issue.ceoVerdict,
                            ceoFeedback = baseQueueItem.ceoFeedback ?: issue.ceoFeedback,
                            transitionReason = "Existing PR ${baseQueueItem.pullRequestUrl ?: baseQueueItem.pullRequestNumber} already merged; closing the no-diff remediation path.",
                            updatedAt = now
                        )
                    }
                    refreshedState == "OPEN" && refreshedMergeability == "DIRTY" -> {
                        val queueItem = baseQueueItem.copy(
                            status = ReviewQueueStatus.CHANGES_REQUESTED,
                            mergeability = refreshed.mergeability ?: baseQueueItem.mergeability ?: "DIRTY",
                            ceoVerdict = baseQueueItem.ceoVerdict ?: issue.ceoVerdict ?: "CHANGES_REQUESTED",
                            ceoFeedback = baseQueueItem.ceoFeedback
                                ?: issue.ceoFeedback
                                ?: "GitHub still reports a merge conflict on the existing PR lineage.",
                            ceoReviewedAt = baseQueueItem.ceoReviewedAt ?: now,
                            approvalIssueId = existingApprovalIssue?.id ?: baseQueueItem.approvalIssueId,
                            updatedAt = now
                        )
                        updatedItems[issue.id] = queueItem
                        existingApprovalIssue?.let { approvalIssue ->
                            approvalIssueUpdates[approvalIssue.id] = approvalIssue.copy(
                                status = IssueStatus.BLOCKED,
                                pullRequestNumber = queueItem.pullRequestNumber ?: approvalIssue.pullRequestNumber,
                                pullRequestUrl = queueItem.pullRequestUrl ?: approvalIssue.pullRequestUrl,
                                pullRequestState = queueItem.pullRequestState ?: approvalIssue.pullRequestState,
                                ceoVerdict = queueItem.ceoVerdict,
                                ceoFeedback = queueItem.ceoFeedback,
                                transitionReason = "Existing PR still has merge conflicts; keep remediation queued on the same PR lineage.",
                                updatedAt = now
                            )
                        }
                        issue.copy(
                            status = IssueStatus.PLANNED,
                            executionIntent = ExecutionIntent.MERGE_CONFLICT_REMEDIATION,
                            pullRequestNumber = queueItem.pullRequestNumber ?: issue.pullRequestNumber,
                            pullRequestUrl = queueItem.pullRequestUrl ?: issue.pullRequestUrl,
                            pullRequestState = queueItem.pullRequestState ?: issue.pullRequestState,
                            ceoVerdict = queueItem.ceoVerdict,
                            ceoFeedback = queueItem.ceoFeedback,
                            transitionReason = "Existing PR ${queueItem.pullRequestUrl ?: queueItem.pullRequestNumber} still has merge conflicts; keep remediation queued on the same PR lineage.",
                            updatedAt = now
                        )
                    }
                    else -> {
                        val qaAlreadyPassed = existingQaVerdict.equals("PASS", ignoreCase = true)
                        var queueItem = baseQueueItem.copy(
                            status = if (qaAlreadyPassed) ReviewQueueStatus.READY_FOR_CEO else ReviewQueueStatus.AWAITING_QA,
                            mergeability = refreshed.mergeability ?: baseQueueItem.mergeability ?: "CLEAN",
                            ceoVerdict = null,
                            ceoFeedback = null,
                            ceoReviewedAt = null,
                            updatedAt = now
                        )
                        if (qaAlreadyPassed && chiefProfile != null) {
                            val approvalIssue = existingApprovalIssue?.copy(
                                title = "CEO approve ${issue.title}",
                                description = buildCeoApprovalIssueDescription(
                                    issue.copy(
                                        status = IssueStatus.READY_FOR_CEO,
                                        pullRequestNumber = queueItem.pullRequestNumber,
                                        pullRequestUrl = queueItem.pullRequestUrl,
                                        pullRequestState = queueItem.pullRequestState,
                                        ceoVerdict = null,
                                        ceoFeedback = null
                                    ),
                                    queueItem
                                ),
                                status = IssueStatus.PLANNED,
                                assigneeProfileId = chiefProfile.id,
                                dependsOn = listOfNotNull(queueItem.qaIssueId).ifEmpty { listOf(issue.id) },
                                branchName = queueItem.branchName,
                                worktreePath = queueItem.worktreePath,
                                pullRequestNumber = queueItem.pullRequestNumber,
                                pullRequestUrl = queueItem.pullRequestUrl,
                                pullRequestState = queueItem.pullRequestState,
                                qaVerdict = existingQaVerdict,
                                qaFeedback = existingQaFeedback,
                                ceoVerdict = null,
                                ceoFeedback = null,
                                transitionReason = "Existing PR is still valid and ready for CEO approval.",
                                updatedAt = now
                            ) ?: CompanyIssue(
                                id = UUID.randomUUID().toString(),
                                companyId = issue.companyId,
                                projectContextId = issue.projectContextId,
                                goalId = issue.goalId,
                                workspaceId = issue.workspaceId,
                                title = "CEO approve ${issue.title}",
                                description = buildCeoApprovalIssueDescription(
                                    issue.copy(
                                        status = IssueStatus.READY_FOR_CEO,
                                        pullRequestNumber = queueItem.pullRequestNumber,
                                        pullRequestUrl = queueItem.pullRequestUrl,
                                        pullRequestState = queueItem.pullRequestState,
                                        ceoVerdict = null,
                                        ceoFeedback = null
                                    ),
                                    queueItem
                                ),
                                status = IssueStatus.PLANNED,
                                priority = 3,
                                kind = "approval",
                                assigneeProfileId = chiefProfile.id,
                                blockedBy = emptyList(),
                                dependsOn = listOf(issue.id),
                                acceptanceCriteria = listOf(
                                    "Review the current PR and QA verdict.",
                                    "Emit CEO_VERDICT and approve merge only when the branch is ready."
                                ),
                                riskLevel = "medium",
                                codeProducing = false,
                                branchName = queueItem.branchName,
                                worktreePath = queueItem.worktreePath,
                                pullRequestNumber = queueItem.pullRequestNumber,
                                pullRequestUrl = queueItem.pullRequestUrl,
                                pullRequestState = queueItem.pullRequestState,
                                qaVerdict = existingQaVerdict,
                                qaFeedback = existingQaFeedback,
                                transitionReason = "Existing PR is still valid and ready for CEO approval.",
                                sourceSignal = ceoApprovalSource(issue.id),
                                createdAt = now,
                                updatedAt = now
                            )
                            approvalIssueUpdates[approvalIssue.id] = approvalIssue
                            queueItem = queueItem.copy(approvalIssueId = approvalIssue.id)
                        }
                        updatedItems[issue.id] = queueItem
                        issue.copy(
                            status = if (qaAlreadyPassed) IssueStatus.READY_FOR_CEO else IssueStatus.IN_REVIEW,
                            executionIntent = issue.executionIntent ?: inferExecutionIntent(
                                kind = issue.kind,
                                title = issue.title,
                                description = issue.description,
                                plannedCodeProducing = issue.codeProducing
                            ),
                            pullRequestNumber = queueItem.pullRequestNumber ?: issue.pullRequestNumber,
                            pullRequestUrl = queueItem.pullRequestUrl ?: issue.pullRequestUrl,
                            pullRequestState = queueItem.pullRequestState ?: issue.pullRequestState,
                            ceoVerdict = null,
                            ceoFeedback = null,
                            transitionReason = if (qaAlreadyPassed) {
                                "Existing PR ${queueItem.pullRequestUrl ?: queueItem.pullRequestNumber} already contains the remediation; ready for CEO approval again."
                            } else {
                                "Existing PR ${queueItem.pullRequestUrl ?: queueItem.pullRequestNumber} still needs QA on the reused lineage."
                            },
                            updatedAt = now
                        )
                    }
                }
            }

            if (reopened == 0) {
                return@withLock 0
            }

            val updatedReviewQueue = state.reviewQueue
                .filterNot { it.issueId in recoveredByIssueId.keys }
                .plus(updatedItems.values)
            val updatedIssueList = updatedIssues
                .filterNot { issue -> issue.id in approvalIssueUpdates.keys }
                .plus(approvalIssueUpdates.values)
            var nextState = state.copy(
                issues = updatedIssueList,
                reviewQueue = updatedReviewQueue
            )
            recoveredByIssueId.keys.forEach { issueId ->
                val issue = updatedIssueList.firstOrNull { it.id == issueId } ?: return@forEach
                val queueItem = updatedItems[issueId]
                val title = when {
                    issue.status == IssueStatus.DONE -> "Closed no-diff remediation after merge"
                    issue.status == IssueStatus.PLANNED -> "Kept remediation queued on existing PR"
                    issue.status == IssueStatus.READY_FOR_CEO -> "Recovered existing PR for CEO approval"
                    else -> "Recovered existing PR lineage"
                }
                val signalMessage = when {
                    issue.status == IssueStatus.DONE ->
                        "PR ${issue.pullRequestUrl ?: issue.pullRequestNumber} already merged, so Cotor closed the no-diff remediation path."
                    issue.status == IssueStatus.PLANNED ->
                        "PR ${issue.pullRequestUrl ?: issue.pullRequestNumber} still has merge conflicts; remediation stays on the same PR lineage."
                    issue.status == IssueStatus.READY_FOR_CEO ->
                        "PR ${issue.pullRequestUrl ?: issue.pullRequestNumber} already contains the remediation and is back in the CEO lane."
                    else ->
                        "PR ${issue.pullRequestUrl ?: issue.pullRequestNumber} was refreshed on the existing lineage after a no-diff remediation run."
                }
                nextState = nextState.recordCompanyActivity(
                    companyId = issue.companyId,
                    projectContextId = issue.projectContextId,
                    goalId = issue.goalId,
                    issueId = issue.id,
                    source = "review-queue",
                    title = title,
                    detail = queueItem?.pullRequestUrl ?: issue.pullRequestUrl ?: "PR #${issue.pullRequestNumber}"
                ).recordSignal(
                    source = "review-queue",
                    message = signalMessage,
                    companyId = issue.companyId,
                    projectContextId = issue.projectContextId,
                    goalId = issue.goalId,
                    issueId = issue.id,
                    severity = if (issue.status == IssueStatus.PLANNED) "warning" else "info"
                )
            }
            stateStore.save(nextState.withDerivedMetrics())
            reopened
        }
    }

    private suspend fun requeueRecoverableBlockedIssues(companyId: String): Int {
        var retried = 0
        val traceEvents = mutableListOf<CompanyAutomationTraceEvent>()
        val informationalTraceEvents = mutableListOf<CompanyAutomationTraceEvent>()
        stateMutex.withLock {
            val state = stateStore.load()
            val now = System.currentTimeMillis()
            val goalsById = state.goals.associateBy { it.id }
            val issuesById = state.issues.associateBy { it.id }
            val latestRunsByTaskId = state.runs
                .groupBy { it.taskId }
                .mapValues { (_, runs) -> runs.maxByOrNull { it.updatedAt }!! }
            val activeTaskIssueIds = state.tasks
                .filter { it.status == DesktopTaskStatus.RUNNING || it.status == DesktopTaskStatus.QUEUED }
                .mapNotNull { it.issueId }
                .toSet()
            var changed = false
            var nextState = state
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
                    val goalAllowsAutomation = goal?.autonomyEnabled == true &&
                        goal.status != GoalStatus.COMPLETED
                    if (!goalAllowsAutomation || !dependenciesSatisfied) {
                        return@map issue
                    }
                    changed = true
                    retried += 1
                    val updatedIssue = issue.copy(
                        status = IssueStatus.PLANNED,
                        blockedBy = emptyList(),
                        updatedAt = now
                    )
                    traceEvents += buildCompanyAutomationTraceEvent(
                        issue = issue,
                        goal = goal,
                        oldStatus = issue.status,
                        newStatus = updatedIssue.status,
                        source = "requeueRecoverableBlockedIssues",
                        reason = "No tasks exist yet and all dependencies are done, so the issue is recoverable."
                    )
                    return@map updatedIssue
                }
                val latestTask = issueTasks.first()
                val latestRun = latestRunsByTaskId[latestTask.id]
                val retryDecision = resolveRecoverableRetryDecision(issueTasks, latestRunsByTaskId, now)
                if (retryDecision.mode == RecoverableRetryMode.NONE || retryDecision.mode == RecoverableRetryMode.EXHAUSTED) {
                    if (retryDecision.mode == RecoverableRetryMode.EXHAUSTED) {
                        informationalTraceEvents += buildCompanyAutomationTraceEvent(
                            issue = issue,
                            goal = goal,
                            oldStatus = issue.status,
                            newStatus = issue.status,
                            source = "requeueRecoverableBlockedIssues",
                            reason = "Stopped automatic retry after ${retryDecision.consecutiveFailures} consecutive recoverable failures.",
                            latestTask = latestTask,
                            latestRun = latestRun,
                            retryEligible = false
                        )
                    }
                    return@map issue
                }
                if (retryDecision.mode == RecoverableRetryMode.WAITING) {
                    informationalTraceEvents += buildCompanyAutomationTraceEvent(
                        issue = issue,
                        goal = goal,
                        oldStatus = issue.status,
                        newStatus = issue.status,
                        source = "requeueRecoverableBlockedIssues",
                        reason = "Waiting for retry cooldown before rerunning the issue.",
                        latestTask = latestTask,
                        latestRun = latestRun,
                        retryEligible = true
                    )
                    return@map issue
                }
                changed = true
                retried += 1
                val updatedIssue = issue.copy(
                    status = IssueStatus.PLANNED,
                    blockedBy = emptyList(),
                    updatedAt = now
                )
                traceEvents += buildCompanyAutomationTraceEvent(
                    issue = issue,
                    goal = goal,
                    oldStatus = issue.status,
                    newStatus = updatedIssue.status,
                    source = "requeueRecoverableBlockedIssues",
                    reason = "Retrying recoverable infrastructure failure.",
                    latestTask = latestTask,
                    latestRun = latestRun
                )
                updatedIssue
            }
            if (!changed) {
                return@withLock
            }
            nextState = nextState.copy(issues = updatedIssues)
            traceEvents.forEach { trace ->
                nextState = nextState.recordCompanyActivity(
                    companyId = trace.companyId,
                    projectContextId = trace.projectContextId,
                    goalId = trace.goalId,
                    issueId = trace.issueId,
                    source = trace.source,
                    title = "Retried blocked issue",
                    detail = "${trace.issueTitle}: ${trace.reason}",
                    severity = "warning"
                )
            }
            stateStore.save(nextState.withDerivedMetrics())
        }
        traceEvents.forEach(::appendCompanyAutomationTrace)
        informationalTraceEvents.forEach(::appendCompanyAutomationTrace)
        return retried
    }

    private fun failureSignals(task: AgentTask, run: AgentRun?): List<String> {
        if (task.status != DesktopTaskStatus.FAILED && task.status != DesktopTaskStatus.PARTIAL) {
            return emptyList()
        }
        return buildList {
            add(run?.error)
            add(run?.publish?.error)
            add(run?.output)
        }.mapNotNull { signal ->
            signal?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    private fun isTransientGitHubPublishFailure(message: String): Boolean =
        message.contains("submitted too quickly") ||
            message.contains("could not connect to the server") ||
            message.contains("network connection was lost") ||
            message.contains("connection refused") ||
            message.contains("timed out")

    private fun isRecoverableCodexExecutionConfigFailure(message: String): Boolean =
        (
            message.contains("model_reasoning_effort") &&
                (
                    message.contains("unknown variant") ||
                        message.contains("expected one of") ||
                        message.contains("invalid value")
                    )
            ) ||
            CodexDefaults.isRecoverableModelSelectionFailure(message)

    private fun isRecoverableCodexMcpBootstrapFailure(message: String): Boolean =
        (
            message.contains("rmcp::transport::worker") &&
                (
                    message.contains("invalid_token") ||
                        message.contains("no access token was provided in this request") ||
                        message.contains("authrequired(authrequirederror")
                    )
            ) ||
            message.contains("githubcopilot.com/.well-known/oauth-protected-resource/mcp/")

    private fun extractGitHubPublishReadinessFailureReason(
        task: AgentTask,
        issue: CompanyIssue,
        run: AgentRun?,
        state: DesktopAppState
    ): String? {
        if (!requiresGitHubPullRequest(issue, state)) {
            return null
        }
        val baseBranch = state.workspaces.firstOrNull { it.id == issue.workspaceId }?.baseBranch ?: "base branch"
        val matchingSignal = failureSignals(task, run).firstOrNull { signal ->
            val message = signal.lowercase()
            when {
                message.contains("pull request create failed") -> !isTransientGitHubPublishFailure(message)
                message.contains("no history in common") -> true
                message.contains("github publishing requires") -> true
                message.contains("no github remote configured") -> true
                message.contains("origin remote") && message.contains("github publishing") -> true
                message.contains("could not resolve to a repository") -> true
                message.contains("base repository") -> true
                message.contains("head repository") -> true
                else -> false
            }
        } ?: return null
        return if (matchingSignal.contains("no history in common", ignoreCase = true)) {
            "GitHub publishing cannot open PRs for this repository because local $baseBranch was initialized independently and has no history in common with origin/$baseBranch.\n\nOriginal error: $matchingSignal"
        } else {
            "GitHub publishing is blocked by repository or pull request configuration.\n\nOriginal error: $matchingSignal"
        }
    }

    private fun isRecoverableInfrastructureFailure(task: AgentTask, run: AgentRun?): Boolean {
        if (task.status != DesktopTaskStatus.FAILED && task.status != DesktopTaskStatus.PARTIAL) {
            return false
        }
        return failureSignals(task, run).map { it.lowercase() }.any { message ->
            message.contains("git command failed") ||
                message.contains("could not connect to the server") ||
                message.contains("network connection was lost") ||
                message.contains("connection refused") ||
                message.contains("no run found") ||
                message.contains("exited before cotor recorded a final result") ||
                message.contains(INTERRUPTED_RUN_ERROR.lowercase()) ||
                (message.contains("pull request create failed") && isTransientGitHubPublishFailure(message)) ||
                message.contains("submitted too quickly") ||
                isRecoverableCodexExecutionConfigFailure(message) ||
                isRecoverableCodexMcpBootstrapFailure(message)
        }
    }

    private fun shouldResetRecoverableRetryBudget(task: AgentTask, run: AgentRun?): Boolean {
        if (task.status != DesktopTaskStatus.FAILED && task.status != DesktopTaskStatus.PARTIAL) {
            return false
        }
        return failureSignals(task, run).any { message ->
            CodexDefaults.isRetiredModelAliasFailure(message)
        }
    }

    private fun computeRecoverableRetryDelayMs(consecutiveFailures: Int): Long {
        val exponent = (consecutiveFailures - 1).coerceAtLeast(0).coerceAtMost(4)
        val multiplier = 1L shl exponent
        return (RECOVERABLE_RETRY_BASE_DELAY_MS * multiplier).coerceAtMost(RECOVERABLE_RETRY_MAX_DELAY_MS)
    }

    private fun resolveRecoverableRetryDecision(
        issueTasks: List<AgentTask>,
        latestRunsByTaskId: Map<String, AgentRun>,
        now: Long
    ): RecoverableRetryDecision {
        val orderedTasks = issueTasks.sortedByDescending { it.updatedAt }
        val latestTask = orderedTasks.firstOrNull() ?: return RecoverableRetryDecision(RecoverableRetryMode.NONE)
        val latestRun = latestRunsByTaskId[latestTask.id]
        if (!isRecoverableInfrastructureFailure(latestTask, latestRun)) {
            return RecoverableRetryDecision(RecoverableRetryMode.NONE)
        }

        var consecutiveFailures = 0
        for (candidate in orderedTasks) {
            val candidateRun = latestRunsByTaskId[candidate.id]
            if (!isRecoverableInfrastructureFailure(candidate, candidateRun)) {
                break
            }
            consecutiveFailures += 1
        }

        if (consecutiveFailures >= RECOVERABLE_RETRY_MAX_ATTEMPTS) {
            if (shouldResetRecoverableRetryBudget(latestTask, latestRun)) {
                return RecoverableRetryDecision(
                    mode = RecoverableRetryMode.READY,
                    consecutiveFailures = 0,
                    retryAt = now
                )
            }
            return RecoverableRetryDecision(
                mode = RecoverableRetryMode.EXHAUSTED,
                consecutiveFailures = consecutiveFailures,
                retryAt = latestTask.updatedAt + computeRecoverableRetryDelayMs(consecutiveFailures)
            )
        }

        val retryAt = latestTask.updatedAt + computeRecoverableRetryDelayMs(consecutiveFailures)
        return RecoverableRetryDecision(
            mode = if (now >= retryAt) RecoverableRetryMode.READY else RecoverableRetryMode.WAITING,
            consecutiveFailures = consecutiveFailures,
            retryAt = retryAt
        )
    }

    private suspend fun reconcileTerminalIssueStates(companyId: String): Int {
        val taskIdsToSync = mutableListOf<String>()
        val informationalTraceEvents = mutableListOf<CompanyAutomationTraceEvent>()
        stateMutex.withLock {
            val state = stateStore.load()
            val now = System.currentTimeMillis()
            val latestRunsByTaskId = state.runs
                .groupBy { it.taskId }
                .mapValues { (_, runs) -> runs.maxByOrNull { it.updatedAt }!! }
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
                    val issueTasks = state.tasks
                        .filter { it.issueId == issue.id }
                        .sortedByDescending { it.updatedAt }
                    val latestTask = issueTasks.firstOrNull() ?: return@forEach
                    val staleTerminalFailureAlreadySuperseded =
                        latestTask.status in setOf(DesktopTaskStatus.FAILED, DesktopTaskStatus.PARTIAL) &&
                            issue.status in setOf(IssueStatus.PLANNED, IssueStatus.BACKLOG, IssueStatus.DELEGATED) &&
                            issue.updatedAt > latestTask.updatedAt
                    if (staleTerminalFailureAlreadySuperseded) {
                        informationalTraceEvents += buildCompanyAutomationTraceEvent(
                            issue = issue,
                            goal = state.goals.firstOrNull { it.id == issue.goalId },
                            oldStatus = issue.status,
                            newStatus = issue.status,
                            source = "reconcileTerminalIssueStates",
                            reason = "Skipped stale failed-task reconciliation because the issue was reopened after the failed task completed.",
                            latestTask = latestTask,
                            latestRun = latestRunsByTaskId[latestTask.id]
                        )
                        return@forEach
                    }
                    val retryDecision = resolveRecoverableRetryDecision(issueTasks, latestRunsByTaskId, now)
                    val latestRun = latestRunsByTaskId[latestTask.id]
                    if (latestTask.status == DesktopTaskStatus.RUNNING || latestTask.status == DesktopTaskStatus.QUEUED) {
                        return@forEach
                    }
                    val existingPullRequestReusePending =
                        issue.kind.equals("execution", ignoreCase = true) &&
                            issue.status == IssueStatus.BLOCKED &&
                            latestRun != null &&
                            shouldReuseExecutionLineageBinding(issue) &&
                            isExistingPrNoDiffReuseCandidate(issue, latestRun)
                    if (existingPullRequestReusePending) {
                        informationalTraceEvents += buildCompanyAutomationTraceEvent(
                            issue = issue,
                            goal = state.goals.firstOrNull { it.id == issue.goalId },
                            oldStatus = issue.status,
                            newStatus = issue.status,
                            source = "reconcileTerminalIssueStates",
                            reason = "Skipped task-to-issue reconciliation because the latest run produced no new diff on an existing PR lineage and existing-PR recovery should run first.",
                            latestTask = latestTask,
                            latestRun = latestRun
                        )
                        return@forEach
                    }
                    val recoverableRetryPending =
                        issue.status in setOf(IssueStatus.PLANNED, IssueStatus.BACKLOG, IssueStatus.DELEGATED) &&
                            retryDecision.canAutoRetry
                    if (recoverableRetryPending) {
                        informationalTraceEvents += buildCompanyAutomationTraceEvent(
                            issue = issue,
                            goal = state.goals.firstOrNull { it.id == issue.goalId },
                            oldStatus = issue.status,
                            newStatus = issue.status,
                            source = "reconcileTerminalIssueStates",
                            reason = "Skipped task-to-issue reconciliation because a recoverable retry is pending a new task run.",
                            latestTask = latestTask,
                            latestRun = latestRun,
                            retryEligible = true
                        )
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
        informationalTraceEvents.forEach(::appendCompanyAutomationTrace)
        return taskIdsToSync.distinct().size
    }

    private suspend fun normalizeCompanyAutomationState(companyId: String): Int {
        var changed = 0
        val traceEvents = mutableListOf<CompanyAutomationTraceEvent>()
        val informationalTraceEvents = mutableListOf<CompanyAutomationTraceEvent>()
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
            val latestRunsByTaskId = state.runs
                .groupBy { it.taskId }
                .mapValues { (_, runs) -> runs.maxByOrNull { it.updatedAt }!! }
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
                        goal.operatingPolicy?.startsWith("auto-follow-up:goal:") == true -> {
                            val parentGoalId = goal.operatingPolicy.removePrefix("auto-follow-up:goal:")
                            goalsById[parentGoalId]
                        }
                        else -> null
                    } ?: return@mapNotNull null
                    if (parentGoal.operatingPolicy.orEmpty().startsWith("auto-follow-up:")) goal.id else null
                }
                .toSet()
            fun resolvedRootGoalIdForFollowUp(goal: CompanyGoal): String? =
                goal.followUpContext?.rootGoalId ?: when {
                    goal.operatingPolicy?.startsWith("auto-follow-up:issue:") == true -> {
                        val issueId = goal.operatingPolicy.removePrefix("auto-follow-up:issue:")
                        issuesById[issueId]?.goalId
                    }
                    goal.operatingPolicy?.startsWith("auto-follow-up:review:") == true -> {
                        val reviewId = goal.operatingPolicy.removePrefix("auto-follow-up:review:")
                        reviewQueueById[reviewId]?.issueId?.let(issuesById::get)?.goalId
                    }
                    goal.operatingPolicy?.startsWith("auto-follow-up:goal:") == true -> {
                        goal.operatingPolicy.removePrefix("auto-follow-up:goal:").ifBlank { null }
                    }
                    else -> null
                }
            fun isResolvedFollowUpRoot(goal: CompanyGoal): Boolean {
                val rootGoalResolved = resolvedRootGoalIdForFollowUp(goal)
                    ?.let(goalsById::get)
                    ?.status == GoalStatus.COMPLETED
                val triggerIssueResolved = goal.followUpContext?.triggerIssueId
                    ?.let(issuesById::get)
                    ?.let { triggerIssue ->
                        triggerIssue.status == IssueStatus.DONE ||
                            triggerIssue.status == IssueStatus.CANCELED ||
                            triggerIssue.mergeResult.equals("MERGED", ignoreCase = true) ||
                            triggerIssue.pullRequestState.equals("MERGED", ignoreCase = true)
                    } == true
                return rootGoalResolved || triggerIssueResolved
            }
            val duplicateFollowUpGoalIds = state.goals
                .filter { goal ->
                    goal.companyId == companyId &&
                        goal.status != GoalStatus.COMPLETED &&
                        goal.operatingPolicy.orEmpty().startsWith("auto-follow-up:") &&
                        goal.followUpContext != null
                }
                .groupBy { goal ->
                    val context = requireNotNull(goal.followUpContext)
                    listOf(
                        context.rootGoalId,
                        context.reviewQueueItemId ?: "",
                        context.triggerIssueId ?: "",
                        context.failureClass.name
                    ).joinToString("|")
                }
                .values
                .flatMap { groupedGoals ->
                    groupedGoals
                        .sortedByDescending { it.updatedAt }
                        .drop(1)
                }
                .map { it.id }
                .toSet()
            val obsoleteFollowUpGoalIds = state.goals
                .filter { goal ->
                    goal.companyId == companyId &&
                        goal.status != GoalStatus.COMPLETED &&
                        goal.operatingPolicy.orEmpty().startsWith("auto-follow-up:") &&
                        (goal.id in duplicateFollowUpGoalIds || isResolvedFollowUpRoot(goal))
                }
                .map { it.id }
                .toSet()
            val doneIssueSignatureLatestUpdatedAt = state.issues
                .filter { it.companyId == companyId && it.status == IssueStatus.DONE }
                .groupBy { issue -> issue.kind.lowercase() to issue.title.trim().lowercase() }
                .mapValues { (_, issues) -> issues.maxOf { it.updatedAt } }
            val nextIssues = state.issues.map { issue ->
                if (issue.companyId != companyId) {
                    return@map issue
                }
                val latestTask = tasksByIssueId[issue.id].orEmpty().maxByOrNull { it.updatedAt }
                val goal = goalsById[issue.goalId]
                val workflowDrivenIssue = issue.kind.lowercase() in setOf("execution", "review", "approval")
                val linkedApprovalIssue = if (issue.kind.equals("execution", ignoreCase = true)) {
                    state.issues.firstOrNull {
                        relatedExecutionIssueId(it) == issue.id && it.kind.equals("approval", ignoreCase = true)
                    }
                } else {
                    null
                }
                val linkedExecutionIssue = if (issue.kind.equals("approval", ignoreCase = true)) {
                    relatedExecutionIssueId(issue)?.let(issuesById::get)
                } else {
                    null
                }
                val mergedReviewQueueItem = if (issue.kind.equals("execution", ignoreCase = true)) {
                    state.reviewQueue.firstOrNull { it.issueId == issue.id && it.status == ReviewQueueStatus.MERGED }
                } else {
                    null
                }
                val issueTasks = tasksByIssueId[issue.id].orEmpty().sortedByDescending { it.updatedAt }
                val latestRun = latestTask?.let { latestRunsByTaskId[it.id] }
                val retryDecision = resolveRecoverableRetryDecision(issueTasks, latestRunsByTaskId, now)
                val recoverableRetryPending =
                    latestTask != null &&
                        latestTask.status != DesktopTaskStatus.RUNNING &&
                        latestTask.status != DesktopTaskStatus.QUEUED &&
                        issue.id !in activeTaskIssueIds &&
                        issue.status in setOf(IssueStatus.PLANNED, IssueStatus.BACKLOG, IssueStatus.DELEGATED) &&
                        retryDecision.canAutoRetry
                val recoverableBlockedIssueReadyForRetry =
                    latestTask != null &&
                        latestTask.status != DesktopTaskStatus.RUNNING &&
                        latestTask.status != DesktopTaskStatus.QUEUED &&
                        issue.id !in activeTaskIssueIds &&
                        issue.status == IssueStatus.BLOCKED &&
                        goal?.autonomyEnabled == true &&
                        goal.status != GoalStatus.COMPLETED &&
                        retryDecision.mode == RecoverableRetryMode.READY
                val supersededBySuccessfulRetry =
                    goal?.operatingPolicy.orEmpty().startsWith("auto-follow-up:") &&
                        issue.status != IssueStatus.DONE &&
                        issue.status != IssueStatus.CANCELED &&
                        (
                            doneIssueSignatureLatestUpdatedAt[issue.kind.lowercase() to issue.title.trim().lowercase()]
                                ?.let { latestDoneUpdatedAt -> latestDoneUpdatedAt > issue.updatedAt }
                            ) == true
                val approvalSatisfiedByMergedExecution =
                    issue.kind.equals("approval", ignoreCase = true) &&
                        issue.status != IssueStatus.DONE &&
                        issue.status != IssueStatus.CANCELED &&
                        linkedExecutionIssue?.status == IssueStatus.DONE &&
                        linkedExecutionIssue.mergeResult.equals("MERGED", ignoreCase = true)
                val executionSatisfiedByMergedPullRequest =
                    issue.kind.equals("execution", ignoreCase = true) &&
                        issue.status != IssueStatus.DONE &&
                        issue.status != IssueStatus.CANCELED &&
                        (
                            issue.mergeResult.equals("MERGED", ignoreCase = true) ||
                                issue.pullRequestState.equals("MERGED", ignoreCase = true) ||
                                mergedReviewQueueItem != null ||
                                (
                                    linkedApprovalIssue?.status == IssueStatus.DONE &&
                                        issue.ceoVerdict.equals("APPROVE", ignoreCase = true)
                                    )
                            )
                val existingPullRequestReusePending =
                    issue.kind.equals("execution", ignoreCase = true) &&
                        issue.status == IssueStatus.BLOCKED &&
                        latestRun != null &&
                        shouldReuseExecutionLineageBinding(issue) &&
                        isExistingPrNoDiffReuseCandidate(issue, latestRun)
                val nextStatus = when {
                    executionSatisfiedByMergedPullRequest -> IssueStatus.DONE
                    approvalSatisfiedByMergedExecution -> IssueStatus.DONE
                    supersededBySuccessfulRetry -> IssueStatus.CANCELED
                    issue.goalId in obsoleteFollowUpGoalIds && issue.status != IssueStatus.DONE && issue.status != IssueStatus.CANCELED ->
                        IssueStatus.CANCELED
                    issue.goalId in recursiveGoalIds && issue.status != IssueStatus.DONE && issue.status != IssueStatus.CANCELED -> IssueStatus.CANCELED
                    existingPullRequestReusePending -> issue.status
                    recoverableBlockedIssueReadyForRetry -> IssueStatus.PLANNED
                    recoverableRetryPending -> issue.status
                    latestTask == null || issue.id in activeTaskIssueIds || issue.status == IssueStatus.DONE || issue.status == IssueStatus.CANCELED -> issue.status
                    workflowDrivenIssue -> issue.status
                    latestTask.status == DesktopTaskStatus.COMPLETED -> IssueStatus.DONE
                    latestTask.status == DesktopTaskStatus.FAILED || latestTask.status == DesktopTaskStatus.PARTIAL -> IssueStatus.BLOCKED
                    else -> issue.status
                }
                if (nextStatus == issue.status) {
                    if (recoverableRetryPending) {
                        informationalTraceEvents += buildCompanyAutomationTraceEvent(
                            issue = issue,
                            goal = goal,
                            oldStatus = issue.status,
                            newStatus = issue.status,
                            source = "normalizeCompanyAutomationState",
                            reason = "Skipped blocking because a recoverable retry is pending a new task run.",
                            latestTask = latestTask,
                            latestRun = latestRun,
                            retryEligible = true
                        )
                    } else if (existingPullRequestReusePending) {
                        informationalTraceEvents += buildCompanyAutomationTraceEvent(
                            issue = issue,
                            goal = goal,
                            oldStatus = issue.status,
                            newStatus = issue.status,
                            source = "normalizeCompanyAutomationState",
                            reason = "Preserved the blocked issue because its latest run produced no new diff on an existing PR lineage; existing-PR recovery will refresh the PR state first.",
                            latestTask = latestTask,
                            latestRun = latestRun,
                            retryEligible = false
                        )
                    } else if (
                        issue.status == IssueStatus.BLOCKED &&
                        latestTask != null &&
                        latestTask.status != DesktopTaskStatus.RUNNING &&
                        latestTask.status != DesktopTaskStatus.QUEUED &&
                        issue.id !in activeTaskIssueIds &&
                        workflowDrivenIssue
                    ) {
                        informationalTraceEvents += buildCompanyAutomationTraceEvent(
                            issue = issue,
                            goal = goal,
                            oldStatus = issue.status,
                            newStatus = issue.status,
                            source = "normalizeCompanyAutomationState",
                            reason = if (isRecoverableInfrastructureFailure(latestTask, latestRun)) {
                                if (retryDecision.mode == RecoverableRetryMode.EXHAUSTED) {
                                    "Blocked workflow issue hit the automatic retry limit after repeated recoverable failures."
                                } else {
                                    "Blocked workflow issue is recoverable but could not be reopened because its goal is not autonomous."
                                }
                            } else {
                                "Blocked workflow issue remains blocked because its latest failure is not recoverable."
                            },
                            latestTask = latestTask,
                            latestRun = latestRun,
                            retryEligible = retryDecision.canAutoRetry
                        )
                    } else if (workflowDrivenIssue && latestTask != null && latestTask.status != DesktopTaskStatus.RUNNING && latestTask.status != DesktopTaskStatus.QUEUED) {
                        informationalTraceEvents += buildCompanyAutomationTraceEvent(
                            issue = issue,
                            goal = goal,
                            oldStatus = issue.status,
                            newStatus = issue.status,
                            source = "normalizeCompanyAutomationState",
                            reason = "Skipped overriding a workflow-driven issue because task reconciliation owns its final status.",
                            latestTask = latestTask,
                            latestRun = latestRun,
                            retryEligible = retryDecision.canAutoRetry
                        )
                    }
                    issue
                } else {
                    changed += 1
                    val updatedIssue = issue.copy(
                        status = nextStatus,
                        blockedBy = if (nextStatus == IssueStatus.PLANNED) emptyList() else issue.blockedBy,
                        pullRequestState = if (executionSatisfiedByMergedPullRequest) {
                            issue.pullRequestState ?: "MERGED"
                        } else {
                            issue.pullRequestState
                        },
                        mergeResult = if (executionSatisfiedByMergedPullRequest) {
                            issue.mergeResult ?: "MERGED"
                        } else {
                            issue.mergeResult
                        },
                        transitionReason = when {
                            executionSatisfiedByMergedPullRequest ->
                                "Pull request ${issue.pullRequestUrl ?: issue.pullRequestNumber} is already merged; closing the stale execution issue."
                            approvalSatisfiedByMergedExecution ->
                                "Linked execution issue already merged; closing the approval gate."
                            else -> issue.transitionReason
                        },
                        updatedAt = now
                    )
                    val reason = when {
                        executionSatisfiedByMergedPullRequest -> "Closed a stale execution issue because its linked pull request already merged successfully."
                        approvalSatisfiedByMergedExecution -> "Closed a stale approval issue because the linked execution issue already merged successfully."
                        supersededBySuccessfulRetry -> "A newer retry with the same title and kind already finished successfully."
                        issue.goalId in obsoleteFollowUpGoalIds -> "Canceled stale follow-up work because the root issue or goal is already resolved."
                        issue.goalId in recursiveGoalIds -> "Canceled recursive nested follow-up work."
                        recoverableBlockedIssueReadyForRetry -> "Reopened a blocked issue because the latest task failed with a recoverable infrastructure error."
                        latestTask?.status == DesktopTaskStatus.COMPLETED -> "Latest task completed successfully."
                        latestTask?.status == DesktopTaskStatus.FAILED || latestTask?.status == DesktopTaskStatus.PARTIAL -> "Latest task failed; keeping the issue blocked until recoverable retry logic handles it."
                        else -> "Normalized issue state from task and goal status."
                    }
                    traceEvents += buildCompanyAutomationTraceEvent(
                        issue = issue,
                        goal = goal,
                        oldStatus = issue.status,
                        newStatus = updatedIssue.status,
                        source = "normalizeCompanyAutomationState",
                        reason = reason,
                        latestTask = latestTask,
                        latestRun = latestRun,
                        retryEligible = retryDecision.canAutoRetry
                    )
                    updatedIssue
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
                    goal.id in obsoleteFollowUpGoalIds && goal.status != GoalStatus.COMPLETED -> GoalStatus.COMPLETED
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
                                    it.status == IssueStatus.IN_REVIEW ||
                                    it.status == IssueStatus.READY_FOR_CEO
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
            val obsoleteFollowUpIssueIds = finalizedIssues
                .filter { it.companyId == companyId && it.goalId in obsoleteFollowUpGoalIds }
                .map { it.id }
                .toSet()
            val updatedReviewQueue = state.reviewQueue.filterNot { item ->
                item.companyId == companyId &&
                    (
                        item.issueId in obsoleteFollowUpIssueIds ||
                            item.qaIssueId in obsoleteFollowUpIssueIds ||
                            item.approvalIssueId in obsoleteFollowUpIssueIds
                        )
            }
            if (changed == 0 && recursiveGoalIds.isEmpty() && obsoleteFollowUpGoalIds.isEmpty() && updatedReviewQueue.size == state.reviewQueue.size) {
                return@withLock
            }
            val detailParts = buildList {
                if (changed > 0) add("reconciled $changed issue states")
                if (obsoleteFollowUpGoalIds.isNotEmpty()) add("archived ${obsoleteFollowUpGoalIds.size} obsolete follow-up goals")
                if (recursiveGoalIds.isNotEmpty()) add("archived ${recursiveGoalIds.size} recursive follow-up goals")
            }
            var nextState = state.copy(
                issues = finalizedIssues,
                goals = nextGoals,
                reviewQueue = updatedReviewQueue
            )
            nextState = nextState.recordCompanyActivity(
                companyId = companyId,
                source = "company-runtime",
                title = "Normalized autonomous company state",
                detail = detailParts.joinToString(", ")
            )
            traceEvents
                .filter { it.newStatus == IssueStatus.BLOCKED || it.newStatus == IssueStatus.CANCELED }
                .forEach { trace ->
                    nextState = nextState.recordCompanyActivity(
                        companyId = trace.companyId,
                        projectContextId = trace.projectContextId,
                        goalId = trace.goalId,
                        issueId = trace.issueId,
                        source = trace.source,
                        title = when (trace.newStatus) {
                            IssueStatus.BLOCKED -> "Blocked issue"
                            IssueStatus.CANCELED -> "Canceled issue"
                            else -> "Updated issue"
                        },
                        detail = "${trace.issueTitle}: ${trace.reason}",
                        severity = if (trace.newStatus == IssueStatus.BLOCKED) "warning" else "info"
                    )
                }
            stateStore.save(nextState.withDerivedMetrics())
        }
        traceEvents.forEach(::appendCompanyAutomationTrace)
        informationalTraceEvents.forEach(::appendCompanyAutomationTrace)
        return changed
    }

    private suspend fun repairWorkflowLineages(companyId: String): Int {
        var changed = 0
        val traceEvents = mutableListOf<CompanyAutomationTraceEvent>()
        stateMutex.withLock {
            val state = stateStore.load()
            val now = System.currentTimeMillis()
            val companyProfiles = ensureOrgProfiles(state.orgProfiles, state.companyAgentDefinitions, state.companies)
                .filter { it.companyId == companyId }
            val issuesById = state.issues.associateBy { it.id }.toMutableMap()
            val reviewQueueById = state.reviewQueue.associateBy { it.id }.toMutableMap()
            val tasksById = state.tasks.associateBy { it.id }.toMutableMap()
            val runsById = state.runs.associateBy { it.id }.toMutableMap()

            fun latestTask(issueId: String): AgentTask? =
                tasksById.values.filter { it.issueId == issueId }.maxByOrNull { it.updatedAt }

            fun latestRun(taskId: String): AgentRun? =
                runsById.values.filter { it.taskId == taskId }.maxByOrNull { it.updatedAt }

            fun removeWorkflowIssue(issueId: String?) {
                if (issueId == null) return
                val removedIssue = issuesById.remove(issueId) ?: return
                val removedTaskIds = tasksById.values
                    .filter { it.issueId == removedIssue.id }
                    .map { it.id }
                    .toSet()
                removedTaskIds.forEach(tasksById::remove)
                runsById.entries.removeIf { it.value.taskId in removedTaskIds }
            }

            state.reviewQueue
                .filter { it.companyId == companyId && it.status != ReviewQueueStatus.MERGED }
                .sortedByDescending { it.updatedAt }
                .forEach { originalQueueItem ->
                    val queueItem = reviewQueueById[originalQueueItem.id] ?: return@forEach
                    val executionIssue = issuesById[queueItem.issueId] ?: return@forEach
                    val latestExecutionTask = latestTask(executionIssue.id)
                    val latestExecutionRun = latestExecutionTask?.let { latestRun(it.id) }
                    val pendingExecutionLineageUpdate =
                        latestExecutionTask != null &&
                            latestExecutionTask.status == DesktopTaskStatus.COMPLETED &&
                            latestExecutionRun?.publish != null &&
                            !reviewQueueReflectsExecutionRun(queueItem, latestExecutionTask, latestExecutionRun)
                    if (pendingExecutionLineageUpdate) {
                        return@forEach
                    }
                    val lineageState = state.copy(
                        issues = issuesById.values.toList(),
                        tasks = tasksById.values.toList(),
                        runs = runsById.values.toList(),
                        reviewQueue = reviewQueueById.values.toList()
                    )
                    val expectedLineage = resolvedWorkflowLineageForQueueItem(lineageState, queueItem) ?: return@forEach
                    val qaProfile = findQaProfile(companyId, companyProfiles)
                    val reviewIssue = queueItem.qaIssueId?.let(issuesById::get) ?: issuesById.values.firstOrNull {
                        relatedExecutionIssueId(it) == executionIssue.id && it.kind.equals("review", ignoreCase = true)
                    }
                    val latestReviewTask = reviewIssue?.let { latestTask(it.id) }
                    val latestReviewRun = latestReviewTask?.let { latestRun(it.id) }
                    val reviewIssueReopenedAfterTask =
                        reviewIssue != null &&
                            latestReviewTask != null &&
                            reviewIssue.updatedAt > latestReviewTask.updatedAt
                    val reviewLineageExplicitMismatch =
                        reviewIssue != null &&
                            (
                                (reviewIssue.workflowLineage != null && !workflowLineagesMatch(expectedLineage, reviewIssue.workflowLineage)) ||
                                    (latestReviewTask?.workflowLineage != null && !workflowLineagesMatch(expectedLineage, latestReviewTask.workflowLineage)) ||
                                    (latestReviewRun?.workflowLineage != null && !workflowLineagesMatch(expectedLineage, latestReviewRun.workflowLineage))
                                )
                    val reviewLineageCanBeAdopted =
                        reviewIssue != null &&
                            latestReviewTask != null &&
                            canAdoptExpectedWorkflowLineage(
                                issue = reviewIssue,
                                task = latestReviewTask,
                                run = latestReviewRun,
                                expectedLineage = expectedLineage
                            )
                    val reviewIssueCanAdoptQueueStateWithoutTask =
                        reviewIssue != null &&
                            latestReviewTask == null &&
                            when (queueItem.status) {
                                ReviewQueueStatus.AWAITING_QA ->
                                    queueItem.qaVerdict == null &&
                                        reviewIssue.status in setOf(IssueStatus.PLANNED, IssueStatus.DELEGATED, IssueStatus.IN_PROGRESS)
                                ReviewQueueStatus.READY_FOR_CEO ->
                                    (
                                        queueItem.qaVerdict == null &&
                                            reviewIssue.status in setOf(IssueStatus.PLANNED, IssueStatus.DELEGATED, IssueStatus.IN_PROGRESS)
                                        ) ||
                                        (
                                            queueItem.qaVerdict != null &&
                                                reviewIssue.qaVerdict != null &&
                                                queueItem.qaVerdict == reviewIssue.qaVerdict &&
                                                reviewIssue.status == IssueStatus.DONE
                                            )
                                ReviewQueueStatus.CHANGES_REQUESTED ->
                                    queueItem.qaVerdict != null &&
                                        reviewIssue.qaVerdict != null &&
                                        queueItem.qaVerdict == reviewIssue.qaVerdict &&
                                        reviewIssue.status == IssueStatus.BLOCKED
                                ReviewQueueStatus.MERGED ->
                                    reviewIssue.status == IssueStatus.DONE
                                else -> false
                            }
                    val reviewAssigneeMismatch =
                        reviewIssue != null &&
                            qaProfile != null &&
                            reviewIssue.assigneeProfileId != qaProfile.id
                    val staleReviewLineage =
                        reviewIssue != null &&
                            (
                                reviewAssigneeMismatch ||
                                (!reviewIssueCanAdoptQueueStateWithoutTask && latestReviewTask == null) ||
                                    (!reviewLineageCanBeAdopted && (reviewIssueReopenedAfterTask || reviewLineageExplicitMismatch))
                                )

                    if (reviewIssue == null || staleReviewLineage) {
                        if (qaProfile != null) {
                            removeWorkflowIssue(reviewIssue?.id)
                            removeWorkflowIssue(queueItem.approvalIssueId)
                            val refreshedReviewIssue = CompanyIssue(
                                id = UUID.randomUUID().toString(),
                                companyId = executionIssue.companyId,
                                projectContextId = executionIssue.projectContextId,
                                goalId = executionIssue.goalId,
                                workspaceId = executionIssue.workspaceId,
                                title = "QA review ${executionIssue.title}",
                                description = buildQaReviewIssueDescription(executionIssue, queueItem.copy(workflowLineage = expectedLineage)),
                                status = IssueStatus.PLANNED,
                                priority = 2,
                                kind = "review",
                                assigneeProfileId = qaProfile.id,
                                blockedBy = emptyList(),
                                dependsOn = listOf(executionIssue.id),
                                acceptanceCriteria = listOf(
                                    "Review the current PR and its changed files.",
                                    "Return QA_VERDICT with concrete acceptance or requested changes."
                                ),
                                riskLevel = "low",
                                codeProducing = false,
                                branchName = executionIssue.branchName,
                                worktreePath = executionIssue.worktreePath,
                                pullRequestNumber = executionIssue.pullRequestNumber,
                                pullRequestUrl = executionIssue.pullRequestUrl,
                                pullRequestState = executionIssue.pullRequestState,
                                transitionReason = "Rebuilt the QA lineage for the current pull request.",
                                sourceSignal = qaReviewSource(executionIssue.id),
                                createdAt = now,
                                updatedAt = now,
                                workflowLineage = expectedLineage
                            )
                            issuesById[executionIssue.id] = executionIssue.copy(
                                status = IssueStatus.IN_REVIEW,
                                qaVerdict = null,
                                qaFeedback = null,
                                ceoVerdict = null,
                                ceoFeedback = null,
                                updatedAt = now
                            )
                            issuesById[refreshedReviewIssue.id] = refreshedReviewIssue
                            reviewQueueById[queueItem.id] = queueItem.copy(
                                status = ReviewQueueStatus.AWAITING_QA,
                                qaVerdict = null,
                                qaFeedback = null,
                                qaReviewedAt = null,
                                qaIssueId = refreshedReviewIssue.id,
                                ceoVerdict = null,
                                ceoFeedback = null,
                                ceoReviewedAt = null,
                                approvalIssueId = null,
                                workflowLineage = expectedLineage,
                                updatedAt = now
                            )
                            changed += 1
                            traceEvents += buildCompanyAutomationTraceEvent(
                                issue = executionIssue,
                                goal = state.goals.firstOrNull { it.id == executionIssue.goalId },
                                oldStatus = executionIssue.status,
                                newStatus = IssueStatus.IN_REVIEW,
                                source = "repairWorkflowLineages",
                                reason = "Rebuilt stale QA lineage so only the latest execution publish can be reviewed."
                            )
                            return@forEach
                        }
                    } else if (
                        !workflowLineagesMatch(expectedLineage, queueItem.workflowLineage) ||
                            !workflowLineagesMatch(expectedLineage, reviewIssue.workflowLineage) ||
                            (latestReviewTask != null && !workflowLineagesMatch(expectedLineage, latestReviewTask.workflowLineage)) ||
                            (latestReviewRun != null && !workflowLineagesMatch(expectedLineage, latestReviewRun.workflowLineage))
                    ) {
                        issuesById[reviewIssue.id] = reviewIssue.copy(workflowLineage = expectedLineage)
                        latestReviewTask?.let { tasksById[it.id] = it.copy(workflowLineage = expectedLineage) }
                        latestReviewRun?.let { runsById[it.id] = it.copy(workflowLineage = expectedLineage) }
                        reviewQueueById[queueItem.id] = queueItem.copy(workflowLineage = expectedLineage)
                        changed += 1
                    }

                    val refreshedQueue = reviewQueueById[queueItem.id] ?: return@forEach
                    if (refreshedQueue.status != ReviewQueueStatus.READY_FOR_CEO && refreshedQueue.approvalIssueId == null) {
                        return@forEach
                    }
                    val currentReviewIssue = refreshedQueue.qaIssueId?.let(issuesById::get)
                    val chiefProfile = findChiefProfile(companyId, companyProfiles)
                    val approvalIssue = refreshedQueue.approvalIssueId?.let(issuesById::get) ?: issuesById.values.firstOrNull {
                        relatedExecutionIssueId(it) == executionIssue.id && it.kind.equals("approval", ignoreCase = true)
                    }
                    val latestApprovalTask = approvalIssue?.let { latestTask(it.id) }
                    val latestApprovalRun = latestApprovalTask?.let { latestRun(it.id) }
                    val approvalIssueReopenedAfterTask =
                        approvalIssue != null &&
                            latestApprovalTask != null &&
                            approvalIssue.updatedAt > latestApprovalTask.updatedAt
                    val approvalLineageExplicitMismatch =
                        approvalIssue != null &&
                            (
                                (approvalIssue.workflowLineage != null && !workflowLineagesMatch(expectedLineage, approvalIssue.workflowLineage)) ||
                                    (latestApprovalTask?.workflowLineage != null && !workflowLineagesMatch(expectedLineage, latestApprovalTask.workflowLineage)) ||
                                    (latestApprovalRun?.workflowLineage != null && !workflowLineagesMatch(expectedLineage, latestApprovalRun.workflowLineage))
                                )
                    val approvalLineageCanBeAdopted =
                        approvalIssue != null &&
                            latestApprovalTask != null &&
                            canAdoptExpectedWorkflowLineage(
                                issue = approvalIssue,
                                task = latestApprovalTask,
                                run = latestApprovalRun,
                                expectedLineage = expectedLineage
                            )
                    val approvalIssueCanAdoptQueueStateWithoutTask =
                        approvalIssue != null &&
                            latestApprovalTask == null &&
                            (
                                (
                                    refreshedQueue.ceoVerdict != null &&
                                        approvalIssue.ceoVerdict != null &&
                                        refreshedQueue.ceoVerdict == approvalIssue.ceoVerdict &&
                                        approvalIssue.status ==
                                        when (refreshedQueue.status) {
                                            ReviewQueueStatus.CHANGES_REQUESTED -> IssueStatus.BLOCKED
                                            ReviewQueueStatus.MERGED -> IssueStatus.DONE
                                            else -> approvalIssue.status
                                        }
                                    ) ||
                                    (
                                        refreshedQueue.status == ReviewQueueStatus.READY_FOR_CEO &&
                                            refreshedQueue.ceoVerdict == null &&
                                            approvalIssue.status in setOf(IssueStatus.PLANNED, IssueStatus.DELEGATED, IssueStatus.IN_PROGRESS)
                                        )
                                )
                    val approvalAssigneeMismatch =
                        approvalIssue != null &&
                            chiefProfile != null &&
                            approvalIssue.assigneeProfileId != chiefProfile.id
                    val staleApprovalLineage =
                        approvalIssue != null &&
                            (
                                approvalAssigneeMismatch ||
                                (!approvalIssueCanAdoptQueueStateWithoutTask && latestApprovalTask == null) ||
                                    (!approvalLineageCanBeAdopted && (approvalIssueReopenedAfterTask || approvalLineageExplicitMismatch))
                                )
                    if (refreshedQueue.status == ReviewQueueStatus.READY_FOR_CEO && (approvalIssue == null || staleApprovalLineage)) {
                        if (chiefProfile != null) {
                            removeWorkflowIssue(approvalIssue?.id)
                            val refreshedApprovalIssue = CompanyIssue(
                                id = UUID.randomUUID().toString(),
                                companyId = executionIssue.companyId,
                                projectContextId = executionIssue.projectContextId,
                                goalId = executionIssue.goalId,
                                workspaceId = executionIssue.workspaceId,
                                title = "CEO approve ${executionIssue.title}",
                                description = buildCeoApprovalIssueDescription(executionIssue, refreshedQueue.copy(workflowLineage = expectedLineage)),
                                status = IssueStatus.PLANNED,
                                priority = 3,
                                kind = "approval",
                                assigneeProfileId = chiefProfile.id,
                                blockedBy = emptyList(),
                                dependsOn = listOfNotNull(currentReviewIssue?.id).ifEmpty { listOf(executionIssue.id) },
                                acceptanceCriteria = listOf(
                                    "Review the current PR and QA verdict.",
                                    "Emit CEO_VERDICT and approve merge only when the branch is ready."
                                ),
                                riskLevel = "medium",
                                codeProducing = false,
                                branchName = executionIssue.branchName,
                                worktreePath = executionIssue.worktreePath,
                                pullRequestNumber = executionIssue.pullRequestNumber,
                                pullRequestUrl = executionIssue.pullRequestUrl,
                                pullRequestState = executionIssue.pullRequestState,
                                qaVerdict = refreshedQueue.qaVerdict,
                                qaFeedback = refreshedQueue.qaFeedback,
                                transitionReason = "Rebuilt the CEO approval lineage for the current pull request.",
                                sourceSignal = ceoApprovalSource(executionIssue.id),
                                createdAt = now,
                                updatedAt = now,
                                workflowLineage = expectedLineage
                            )
                            issuesById[executionIssue.id] = executionIssue.copy(
                                status = IssueStatus.READY_FOR_CEO,
                                ceoVerdict = null,
                                ceoFeedback = null,
                                updatedAt = now
                            )
                            issuesById[refreshedApprovalIssue.id] = refreshedApprovalIssue
                            reviewQueueById[queueItem.id] = refreshedQueue.copy(
                                ceoVerdict = null,
                                ceoFeedback = null,
                                ceoReviewedAt = null,
                                approvalIssueId = refreshedApprovalIssue.id,
                                workflowLineage = expectedLineage,
                                updatedAt = now
                            )
                            changed += 1
                            traceEvents += buildCompanyAutomationTraceEvent(
                                issue = executionIssue,
                                goal = state.goals.firstOrNull { it.id == executionIssue.goalId },
                                oldStatus = executionIssue.status,
                                newStatus = IssueStatus.READY_FOR_CEO,
                                source = "repairWorkflowLineages",
                                reason = "Rebuilt stale CEO approval lineage so only the latest QA result can advance the merge gate."
                            )
                        }
                    } else if (
                        approvalIssue != null &&
                            (
                                !workflowLineagesMatch(expectedLineage, approvalIssue.workflowLineage) ||
                                    (latestApprovalTask != null && !workflowLineagesMatch(expectedLineage, latestApprovalTask.workflowLineage)) ||
                                    (latestApprovalRun != null && !workflowLineagesMatch(expectedLineage, latestApprovalRun.workflowLineage))
                                )
                    ) {
                        if (approvalIssueCanAdoptQueueStateWithoutTask || approvalLineageCanBeAdopted) {
                            issuesById[approvalIssue.id] = approvalIssue.copy(workflowLineage = expectedLineage)
                            latestApprovalTask?.let { tasksById[it.id] = it.copy(workflowLineage = expectedLineage) }
                            latestApprovalRun?.let { runsById[it.id] = it.copy(workflowLineage = expectedLineage) }
                            reviewQueueById[queueItem.id] = refreshedQueue.copy(
                                approvalIssueId = approvalIssue.id,
                                workflowLineage = expectedLineage,
                                updatedAt = now
                            )
                        } else {
                            removeWorkflowIssue(approvalIssue.id)
                            reviewQueueById[queueItem.id] = refreshedQueue.copy(
                                ceoVerdict = null,
                                ceoFeedback = null,
                                ceoReviewedAt = null,
                                approvalIssueId = null,
                                workflowLineage = expectedLineage,
                                updatedAt = now
                            )
                        }
                        changed += 1
                    }
                }

            if (changed > 0) {
                var nextState = state.copy(
                    issues = issuesById.values.sortedBy { it.createdAt },
                    tasks = tasksById.values.sortedBy { it.createdAt },
                    runs = runsById.values.sortedBy { it.createdAt },
                    reviewQueue = reviewQueueById.values.sortedBy { it.createdAt }
                )
                nextState = nextState.recordCompanyActivity(
                    companyId = companyId,
                    source = "workflow-lineage",
                    title = "Healed workflow lineage",
                    detail = "Repaired $changed stale QA/CEO review lineage entries."
                ).withDerivedMetrics()
                stateStore.save(nextState)
            }
        }
        traceEvents.forEach(::appendCompanyAutomationTrace)
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
                        goal.operatingPolicy?.startsWith("auto-follow-up:goal:") == true -> {
                            val parentGoalId = goal.operatingPolicy.removePrefix("auto-follow-up:goal:")
                            goalsById[parentGoalId]
                        }
                        else -> null
                    } ?: return@mapNotNull null
                    if (parentGoal.operatingPolicy.orEmpty().startsWith("auto-follow-up:")) goal.id else null
                }
                .toSet()
            val duplicateFollowUpGoalIds = state.goals
                .filter { goal ->
                    goal.companyId == companyId &&
                        goal.status == GoalStatus.ACTIVE &&
                        goal.operatingPolicy.orEmpty().startsWith("auto-follow-up:")
                }
                .groupBy { resolveFollowUpRootGoalId(it.id, issuesById, goalsById, reviewQueueById) ?: canonicalFollowUpSubject(it.title) }
                .values
                .flatMap { goalsForSubject ->
                    if (goalsForSubject.size <= 1) {
                        emptyList()
                    } else {
                        val keepGoal = goalsForSubject.maxWithOrNull(
                            compareBy<CompanyGoal>(
                                { if (it.id in activeGoalIdsWithTasks) 1 else 0 },
                                { it.updatedAt }
                            )
                        )
                        goalsForSubject.filterNot { it.id == keepGoal?.id }.map { it.id }
                    }
                }
                .toSet()
            val archivedGoalIds = recursiveGoalIds + duplicateFollowUpGoalIds
            if (archivedGoalIds.isEmpty()) {
                return@withLock
            }
            archivedCount = archivedGoalIds.size
            val nextState = state.copy(
                goals = state.goals.map { goal ->
                    if (goal.id !in archivedGoalIds) {
                        goal
                    } else {
                        goal.copy(
                            status = GoalStatus.COMPLETED,
                            updatedAt = now
                        )
                    }
                },
                issues = state.issues.map { issue ->
                    if (issue.goalId !in archivedGoalIds || issue.status == IssueStatus.DONE || issue.status == IssueStatus.CANCELED) {
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

    private fun isGeneratedFollowUpTitle(title: String): Boolean =
        title.trim().startsWith("Resolve follow-up for \"")

    private fun canonicalFollowUpSubject(title: String): String {
        var current = title.trim()
        while (true) {
            val nested = generatedFollowUpSubject(current) ?: break
            current = nested.trim()
        }
        return current
    }

    private fun generatedFollowUpSubject(title: String): String? {
        val trimmed = title.trim()
        val prefix = "Resolve follow-up for \""
        return if (trimmed.startsWith(prefix) && trimmed.endsWith("\"") && trimmed.length > prefix.length + 1) {
            trimmed.removePrefix(prefix).removeSuffix("\"")
        } else {
            null
        }
    }

    private fun followUpDescriptionValue(description: String, label: String): String? =
        description.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$label:") }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun inferLegacyFollowUpContext(
        state: DesktopAppState,
        goal: CompanyGoal,
        rootGoalId: String,
        issuesById: Map<String, CompanyIssue>,
        goalsById: Map<String, CompanyGoal>,
        reviewQueueById: Map<String, ReviewQueueItem>
    ): FollowUpContextSnapshot? {
        if (!goal.operatingPolicy.orEmpty().startsWith("auto-follow-up:")) {
            return null
        }
        val goalIssueIds = state.issues
            .asSequence()
            .filter { it.goalId == goal.id }
            .map { it.id }
            .toSet()
        val reviewQueueItem = state.reviewQueue
            .filter { queueItem ->
                queueItem.issueId in goalIssueIds ||
                    queueItem.qaIssueId in goalIssueIds ||
                    queueItem.approvalIssueId in goalIssueIds
            }
            .maxByOrNull { it.updatedAt }
        val triggerIssueTitle = followUpDescriptionValue(goal.description, "Trigger issue")
        val triggerIssue = sequenceOf(
            reviewQueueItem?.issueId?.let(issuesById::get),
            state.issues
                .filter { it.companyId == goal.companyId && it.goalId == rootGoalId }
                .maxByOrNull { issue ->
                    if (issue.title == triggerIssueTitle) {
                        issue.updatedAt
                    } else {
                        Long.MIN_VALUE
                    }
                },
            state.issues
                .filter { it.companyId == goal.companyId && it.title == triggerIssueTitle }
                .maxByOrNull { it.updatedAt },
            followUpDescriptionValue(goal.description, "Pull request")
                ?.let { pullRequestUrl ->
                    state.issues
                        .filter { it.companyId == goal.companyId && it.pullRequestUrl == pullRequestUrl }
                        .maxByOrNull { it.updatedAt }
                }
        ).filterNotNull().firstOrNull()
        val failureClass = when {
            reviewQueueItem?.mergeability.equals("DIRTY", ignoreCase = true) -> FollowUpFailureClass.MERGE_CONFLICT
            goal.description.contains("merge conflict", ignoreCase = true) -> FollowUpFailureClass.MERGE_CONFLICT
            reviewQueueItem?.status == ReviewQueueStatus.FAILED_CHECKS -> FollowUpFailureClass.REVIEW_FAILED_CHECKS
            reviewQueueItem?.status == ReviewQueueStatus.CHANGES_REQUESTED -> FollowUpFailureClass.REVIEW_CHANGES_REQUESTED
            goal.description.contains("requested changes", ignoreCase = true) -> FollowUpFailureClass.REVIEW_CHANGES_REQUESTED
            else -> FollowUpFailureClass.BLOCKED_EXECUTION
        }
        if (triggerIssue == null && reviewQueueItem == null && goalsById[rootGoalId] == null) {
            return null
        }
        return FollowUpContextSnapshot(
            rootGoalId = rootGoalId,
            triggerIssueId = triggerIssue?.id,
            reviewQueueItemId = reviewQueueItem?.id,
            pullRequestNumber = reviewQueueItem?.pullRequestNumber ?: triggerIssue?.pullRequestNumber,
            failureClass = failureClass
        )
    }

    private fun resolveFollowUpRootGoalId(
        goalId: String,
        issuesById: Map<String, CompanyIssue>,
        goalsById: Map<String, CompanyGoal>,
        reviewQueueById: Map<String, ReviewQueueItem>
    ): String? {
        var currentGoalId = goalId
        val visitedGoalIds = mutableSetOf<String>()
        while (visitedGoalIds.add(currentGoalId)) {
            val goal = goalsById[currentGoalId] ?: return currentGoalId
            val policy = goal.operatingPolicy.orEmpty()
            currentGoalId = when {
                policy.startsWith("auto-follow-up:issue:") -> {
                    val issueId = policy.removePrefix("auto-follow-up:issue:")
                    issuesById[issueId]?.goalId ?: return currentGoalId
                }
                policy.startsWith("auto-follow-up:review:") -> {
                    val reviewId = policy.removePrefix("auto-follow-up:review:")
                    val issueId = reviewQueueById[reviewId]?.issueId
                    issueId?.let(issuesById::get)?.goalId ?: return currentGoalId
                }
                policy.startsWith("auto-follow-up:goal:") -> {
                    policy.removePrefix("auto-follow-up:goal:").ifBlank { return currentGoalId }
                }
                else -> return currentGoalId
            }
        }
        return currentGoalId
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
        issueId: String? = null,
        workflowLineage: WorkflowLineageSnapshot? = null
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
            updatedAt = now,
            workflowLineage = workflowLineage
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
        // Public callers use this thin wrapper so the implementation can return null internally
        // when reconciliation races with deletion. API consumers, however, should see a clear
        // not-found error instead of a silent no-op.
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
        val githubPublishStatus = runBlocking { computeGitHubPublishStatus(state = state) }
        return DesktopSettings(
            appHome = stateStore.appHome().toString(),
            managedReposRoot = stateStore.managedReposRoot().toString(),
            availableAgents = BuiltinAgentCatalog.names(),
            availableCliAgents = BuiltinAgentCatalog.names().filter { isExecutableAvailable(it) },
            recentCompanies = emptyList(),
            defaultLaunchMode = "company",
            backendSettings = state.backendSettings,
            githubPublishStatus = githubPublishStatus,
            linearSettings = state.linearSettings,
            backendStatuses = computeBackendStatuses(state),
            shortcuts = ShortcutConfig()
        )
    }

    suspend fun githubPublishStatus(companyId: String? = null): GitHubPublishStatus {
        val state = stateStore.load()
        val company = companyId?.let { requestedId ->
            state.companies.firstOrNull { it.id == requestedId }
        }
        return computeGitHubPublishStatus(state = state, company = company)
    }

    private suspend fun computeGitHubPublishStatus(
        state: DesktopAppState,
        company: Company? = null
    ): GitHubPublishStatus {
        val targetCompany = company ?: state.companies.maxByOrNull { it.updatedAt }
        val repository = targetCompany?.let { linked ->
            state.repositories.firstOrNull { it.id == linked.repositoryId }
        } ?: state.repositories.maxByOrNull { it.updatedAt }
        val environment = runCatching {
            gitWorkspaceService.inspectGitHubPublishEnvironment(
                repositoryRoot = repository?.localPath?.let(Path::of),
                baseBranch = targetCompany?.defaultBaseBranch ?: repository?.defaultBranch
            )
        }.getOrElse {
            GitHubPublishEnvironment(
                ghInstalled = false,
                ghAuthenticated = false,
                originConfigured = false,
                bootstrapAvailable = false,
                message = "Unable to inspect GitHub publish environment."
            )
        }
        return GitHubPublishStatus(
            policy = state.backendSettings.codePublishMode,
            ghInstalled = environment.ghInstalled,
            ghAuthenticated = environment.ghAuthenticated,
            originConfigured = environment.originConfigured,
            originUrl = environment.originUrl,
            bootstrapAvailable = environment.bootstrapAvailable,
            repositoryPath = environment.repositoryPath,
            companyId = targetCompany?.id,
            companyName = targetCompany?.name,
            message = environment.message
        )
    }

    private fun requiresGitHubPullRequest(issue: CompanyIssue?, state: DesktopAppState): Boolean =
        requiresCodePublish(issue) && state.backendSettings.codePublishMode == CodePublishMode.REQUIRE_GITHUB_PR

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
        val snapshot = runCatching {
            companyDashboardSnapshot(runBlocking { stateStore.load() }.withDerivedMetrics(), companyId)
        }.getOrNull()
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
                ),
                companyDashboard = snapshot
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

        try {
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
        } catch (cancelled: CancellationException) {
            if (!isTaskIntentionallyInterrupted(task.id)) {
                throw cancelled
            }
            return
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
        var heartbeatJob: Job? = null
        if (agent == null) {
            recordRunFailure(task, workspace, repository, agentName, "Unknown agent configuration")
            return
        }

        try {
            val currentState = stateStore.load()
            val issue = task.issueId?.let { issueId -> currentState.issues.firstOrNull { it.id == issueId } }
            val binding = if (shouldReuseExecutionLineageBinding(issue)) {
                gitWorkspaceService.ensureExistingWorktreeLineage(
                    repositoryRoot = Path.of(repository.localPath),
                    branchName = requireNotNull(issue?.branchName),
                    worktreePath = Path.of(requireNotNull(issue?.worktreePath)),
                    baseBranch = workspace.baseBranch
                )
            } else {
                gitWorkspaceService.ensureWorktree(
                    repositoryRoot = Path.of(repository.localPath),
                    taskId = task.id,
                    taskTitle = task.title,
                    agentName = agent.name,
                    baseBranch = workspace.baseBranch
                )
            }
            val run = upsertQueuedRun(task, workspace, repository, agent.name, binding)
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
            heartbeatJob = serviceScope.launch {
                while (isActive) {
                    delay(runHeartbeatIntervalMs)
                    touchActiveRunWithoutProcess(startedRun.id)
                }
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
            val pullRequestRequired = requiresGitHubPullRequest(issue, currentState) && issue != null
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
            val finalError = result.error ?: when {
                !publishRequired -> null
                !pullRequestRequired -> publish?.error?.takeUnless {
                    isNonFatalPublishError(it, currentState, task = task, issue = issue)
                }
                publish == null -> "GitHub publishing did not run for required code work"
                publish.error != null -> publish.error?.takeUnless {
                    isNonFatalPublishError(it, currentState, task = task, issue = issue)
                }
                publish.pullRequestUrl.isNullOrBlank() || publish.pullRequestNumber == null ->
                    "GitHub pull request was not created for required code work"
                else -> null
            }
            val estimatedCostCents = estimateRunCostCents(agent, assignedPrompt, result)

            replaceRun(
                startedRun.copy(
                    status = if (result.isSuccess && finalError == null) AgentRunStatus.COMPLETED else AgentRunStatus.FAILED,
                    processId = result.processId,
                    output = result.output,
                    error = finalError,
                    publish = publish,
                    durationMs = result.duration.takeIf { it > 0 },
                    estimatedCostCents = estimatedCostCents.takeIf { it > 0 },
                    updatedAt = System.currentTimeMillis()
                )
            )
            company?.let {
                if (estimatedCostCents > 0) {
                    recordRunCost(it.id, estimatedCostCents)
                }
            }
            company?.let {
                val successDetail = result.output
                    ?.lineSequence()
                    ?.filter { it.isNotBlank() }
                    ?.take(6)
                    ?.joinToString("\n")
                    ?.takeIf { detail -> detail.isNotBlank() }
                publishCompanyEvent(
                    companyId = it.id,
                    type = if (result.isSuccess && finalError == null) "run.completed" else "run.failed",
                    title = if (result.isSuccess && finalError == null) "Completed agent run" else "Agent run failed",
                    detail = if (result.isSuccess && finalError == null) {
                        successDetail ?: "${agent.name} finished ${task.title}"
                    } else {
                        result.error ?: publish?.error ?: "${agent.name} finished ${task.title}"
                    },
                    goalId = issue?.goalId,
                    issueId = issue?.id,
                    runId = startedRun.id
                )
            }
            heartbeatJob?.cancel()
        } catch (cancelled: CancellationException) {
            if (!isTaskIntentionallyInterrupted(task.id)) {
                throw cancelled
            }
        } catch (t: Throwable) {
            recordRunFailure(task, workspace, repository, agent.name, t.message ?: "Unknown error")
        } finally {
            heartbeatJob?.cancel()
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
                updatedAt = now,
                workflowLineage = task.workflowLineage
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

    private suspend fun synchronizeWorkflowTaskLineage(
        issueId: String,
        taskId: String,
        lineage: WorkflowLineageSnapshot
    ) {
        stateMutex.withLock {
            val state = stateStore.load()
            var changed = false
            val nextIssues = state.issues.map { issue ->
                if (issue.id == issueId && !workflowLineagesMatch(lineage, issue.workflowLineage)) {
                    changed = true
                    issue.copy(workflowLineage = lineage, updatedAt = issue.updatedAt)
                } else {
                    issue
                }
            }
            val nextTasks = state.tasks.map { task ->
                if (task.id == taskId && !workflowLineagesMatch(lineage, task.workflowLineage)) {
                    changed = true
                    task.copy(workflowLineage = lineage, updatedAt = task.updatedAt)
                } else {
                    task
                }
            }
            val nextRuns = state.runs.map { run ->
                if (run.taskId == taskId && !workflowLineagesMatch(lineage, run.workflowLineage)) {
                    changed = true
                    run.copy(workflowLineage = lineage, updatedAt = run.updatedAt)
                } else {
                    run
                }
            }
            val nextReviewQueue = state.reviewQueue.map { item ->
                if (item.id == lineage.reviewQueueItemId && !workflowLineagesMatch(lineage, item.workflowLineage)) {
                    changed = true
                    item.copy(workflowLineage = lineage, updatedAt = item.updatedAt)
                } else {
                    item
                }
            }
            if (changed) {
                stateStore.save(
                    state.copy(
                        issues = nextIssues,
                        tasks = nextTasks,
                        runs = nextRuns,
                        reviewQueue = nextReviewQueue
                    )
                )
            }
        }
    }

    private suspend fun touchActiveRunWithoutProcess(runId: String) {
        stateMutex.withLock {
            val state = stateStore.load()
            val now = System.currentTimeMillis()
            var changed = false
            val nextRuns = state.runs.map { run ->
                if (run.id == runId && run.status == AgentRunStatus.RUNNING && run.processId == null) {
                    changed = true
                    run.copy(updatedAt = now)
                } else {
                    run
                }
            }
            if (changed) {
                stateStore.save(state.copy(runs = nextRuns))
            }
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
        activeTaskJobs[task.id] = serviceScope.launch {
            // Long-running agent execution is detached from the request lifecycle.
            try {
                executeTask(task.id)
            } finally {
                activeTaskJobs.remove(task.id)
                intentionallyInterruptedTaskIds.remove(task.id)
            }
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

    private fun isValidationOnlyExecutionFollowUpTitle(title: String): Boolean {
        return isValidationOnlyExecutionFollowUpText(title)
    }

    private fun isValidationOnlyExecutionFollowUpText(
        title: String,
        description: String = ""
    ): Boolean {
        val normalized = listOf(title, description)
            .joinToString("\n")
            .trim()
            .lowercase()
        if (normalized.isBlank()) return false
        val validationSignals = listOf(
            "re-run validation",
            "rerun validation",
            "revalidate",
            "validate",
            "validation",
            "residual risk",
            "summarize any residual risk",
            "summarize any residual risks",
            "capture any residual risk",
            "capture residual risk"
        )
        val implementationSignals = listOf(
            "implement",
            "implementation",
            "fix ",
            "patch",
            "build ",
            "create ",
            "add ",
            "deliver ",
            "ship ",
            "feature",
            "page",
            "screen",
            "endpoint",
            "component"
        )
        return validationSignals.any(normalized::contains) && implementationSignals.none(normalized::contains)
    }

    private fun isPrReuseHandoffExecutionText(
        title: String,
        description: String = ""
    ): Boolean {
        val normalized = listOf(title, description)
            .joinToString("\n")
            .trim()
            .lowercase()
        if (normalized.isBlank()) return false
        val handoffSignals = listOf(
            "hand the result back",
            "hand back",
            "report back",
            "summarize what the ceo should decide next",
            "summarize what ceo should decide next",
            "decide next",
            "decision cycle",
            "next decision cycle",
            "another decision cycle",
            "summarize the current pr",
            "report the current pr"
        )
        val implementationSignals = listOf(
            "implement",
            "implementation",
            "fix ",
            "patch",
            "build ",
            "create ",
            "add ",
            "deliver ",
            "ship ",
            "feature",
            "page",
            "screen",
            "endpoint",
            "component"
        )
        return handoffSignals.any(normalized::contains) && implementationSignals.none(normalized::contains)
    }

    private fun isMergeConflictRemediationText(
        title: String,
        description: String = ""
    ): Boolean {
        val normalized = listOf(title, description)
            .joinToString("\n")
            .trim()
            .lowercase()
        if (normalized.isBlank()) return false
        return normalized.contains("merge conflict") ||
            normalized.contains("merges cleanly") ||
            normalized.contains("resolve the conflict") ||
            normalized.contains("resolve conflicts") ||
            normalized.contains("rebase")
    }

    private fun inferExecutionIntent(
        kind: String,
        title: String,
        description: String = "",
        plannedCodeProducing: Boolean? = null
    ): ExecutionIntent? {
        if (!kind.equals("execution", ignoreCase = true)) {
            return null
        }
        return when {
            isMergeConflictRemediationText(title, description) -> ExecutionIntent.MERGE_CONFLICT_REMEDIATION
            isValidationOnlyExecutionFollowUpText(title, description) -> ExecutionIntent.VALIDATION_ONLY
            isPrReuseHandoffExecutionText(title, description) -> ExecutionIntent.PR_REUSE_HANDOFF
            plannedCodeProducing == false -> ExecutionIntent.PR_REUSE_HANDOFF
            else -> ExecutionIntent.CODE_CHANGE
        }
    }

    private fun requiresCodePublish(issue: CompanyIssue?): Boolean {
        if (issue == null) return true
        issue.executionIntent?.let { intent ->
            return when (intent) {
                ExecutionIntent.CODE_CHANGE, ExecutionIntent.MERGE_CONFLICT_REMEDIATION -> true
                ExecutionIntent.VALIDATION_ONLY, ExecutionIntent.PR_REUSE_HANDOFF -> false
            }
        }
        if (issue.kind.equals("execution", ignoreCase = true) && isValidationOnlyExecutionFollowUpTitle(issue.title)) {
            return false
        }
        issue.codeProducing?.let { return it }
        return when (issue.kind.lowercase()) {
            "review", "approval", "planning", "infra" -> false
            else -> true
        }
    }

    private fun isExistingPrNoDiffReuseCandidate(
        issue: CompanyIssue,
        run: AgentRun?
    ): Boolean {
        if (issue.pullRequestNumber == null || issue.worktreePath.isNullOrBlank()) {
            return false
        }
        val signal = listOf(
            run?.publish?.error,
            run?.error,
            run?.output
        ).filterNotNull().joinToString("\n").lowercase()
        if (signal.isBlank()) {
            return false
        }
        return signal.contains("no changes to publish") ||
            signal.contains("no legitimate local change to apply") ||
            signal.contains("already matches `master`") ||
            signal.contains("already matches master") ||
            signal.contains("nothing to rebase or merge")
    }

    private fun shouldReuseExecutionLineageBinding(issue: CompanyIssue?): Boolean {
        if (issue == null) return false
        if (issue.branchName.isNullOrBlank() || issue.worktreePath.isNullOrBlank()) {
            return false
        }
        if (issue.pullRequestNumber == null && issue.pullRequestUrl.isNullOrBlank()) {
            return false
        }
        return when (
            issue.executionIntent ?: inferExecutionIntent(
                kind = issue.kind,
                title = issue.title,
                description = issue.description,
                plannedCodeProducing = issue.codeProducing
            )
        ) {
            ExecutionIntent.MERGE_CONFLICT_REMEDIATION,
            ExecutionIntent.VALIDATION_ONLY,
            ExecutionIntent.PR_REUSE_HANDOFF -> true
            else -> false
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
                if (issue.companyId != companyId) {
                    return@map run
                }
                if (run.status != AgentRunStatus.FAILED) {
                    return@map run
                }
                val publishError = run.publish?.error ?: return@map run
                val noChangesPublishOnly = !requiresCodePublish(issue) && publishError.startsWith("No changes to publish")
                val localOnlyPublish = isNonFatalPublishError(publishError, state)
                if (!noChangesPublishOnly && !localOnlyPublish) {
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

    private fun buildFallbackGoalIssues(
        state: DesktopAppState,
        goal: CompanyGoal,
        workspace: Workspace,
        profiles: List<OrgAgentProfile>,
        definitions: List<CompanyAgentDefinition>,
        now: Long
    ): Pair<List<CompanyIssue>, List<IssueDependency>> {
        val deterministicFollowUp = buildDeterministicMergeConflictFollowUpIssues(
            state = state,
            goal = goal,
            workspace = workspace,
            profiles = profiles,
            now = now
        )
        if (deterministicFollowUp != null) {
            return deterministicFollowUp
        }
        val participants = buildPlanningParticipants(goal.companyId, profiles, definitions)
        val plan = taskPlanner.buildPlanForParticipants(
            title = goal.title,
            prompt = goal.description,
            participants = participants
        )
        val fallbackPayload = CeoPlanningPayload(
            goalSummary = plan.goalSummary,
            issues = plan.assignments
                .filter { it.phase.equals("execution", ignoreCase = true) }
                .mapIndexed { index, assignment ->
                    CeoPlannedIssue(
                        refId = "exec-${index + 1}",
                        title = assignment.subtasks.firstOrNull()?.title ?: "${assignment.role}: ${assignment.focus}",
                        description = buildIssueDescription(goal, assignment, plan.sharedChecklist),
                        kind = "execution",
                        assigneeRole = assignment.role,
                        priority = 2,
                        codeProducing = inferExecutionIntent(
                            kind = "execution",
                            title = assignment.subtasks.firstOrNull()?.title ?: "${assignment.role}: ${assignment.focus}",
                            description = assignment.subtasks.firstOrNull()?.details.orEmpty()
                        ) in setOf(
                            ExecutionIntent.CODE_CHANGE,
                            ExecutionIntent.MERGE_CONFLICT_REMEDIATION
                        ),
                        dependsOn = emptyList(),
                        acceptanceCriteria = (plan.sharedChecklist + assignment.subtasks.map { it.title }).distinct(),
                        reviewRequired = true,
                        approvalRequired = true
                    )
                }
        )
        val issues = materializePlannedIssues(
            goal = goal,
            workspace = workspace,
            profiles = profiles,
            plan = fallbackPayload,
            now = now,
            planningSource = "fallback"
        )
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

    private fun buildDeterministicMergeConflictFollowUpIssues(
        state: DesktopAppState,
        goal: CompanyGoal,
        workspace: Workspace,
        profiles: List<OrgAgentProfile>,
        now: Long
    ): Pair<List<CompanyIssue>, List<IssueDependency>>? {
        val context = goal.followUpContext ?: return null
        if (context.failureClass != FollowUpFailureClass.MERGE_CONFLICT) {
            return null
        }
        val triggerIssue = context.triggerIssueId?.let { triggerIssueId ->
            state.issues.firstOrNull { it.id == triggerIssueId }
        }
        val reviewQueueItem = context.reviewQueueItemId?.let { reviewQueueItemId ->
            state.reviewQueue.firstOrNull { it.id == reviewQueueItemId }
        }
        val branchName = reviewQueueItem?.branchName ?: triggerIssue?.branchName
        val worktreePath = reviewQueueItem?.worktreePath ?: triggerIssue?.worktreePath
        val pullRequestNumber = reviewQueueItem?.pullRequestNumber ?: triggerIssue?.pullRequestNumber ?: context.pullRequestNumber
        val pullRequestUrl = reviewQueueItem?.pullRequestUrl ?: triggerIssue?.pullRequestUrl
        val pullRequestState = reviewQueueItem?.pullRequestState ?: triggerIssue?.pullRequestState
        val baseBranch = state.companies.firstOrNull { it.id == goal.companyId }?.defaultBaseBranch ?: workspace.baseBranch
        val companyProfiles = profiles.filter { it.companyId == goal.companyId && it.enabled }
        val remediationTitle = buildString {
            append("Resolve merge conflict")
            pullRequestNumber?.let { append(" for PR #$it") }
            append(" against ")
            append(baseBranch)
        }
        val remediationDescription = buildString {
            appendLine("This remediation issue is synthesized automatically for an existing PR merge conflict.")
            appendLine()
            triggerIssue?.let { appendLine("Trigger issue: ${it.title}") }
            pullRequestUrl?.let { appendLine("Pull request: $it") }
            branchName?.let { appendLine("Branch: $it") }
            worktreePath?.let { appendLine("Worktree: $it") }
            appendLine("Base branch: $baseBranch")
            appendLine()
            appendLine("Responsibilities:")
            appendLine("- Rebase or resolve conflicts on the existing PR lineage.")
            appendLine("- Reuse the current branch and worktree instead of creating a handoff PR.")
            appendLine("- Leave the PR ready for the existing QA/CEO lineage once it merges cleanly again.")
        }.trim()
        val validationTitle = "Re-run validation and summarize residual risk"
        val validationDescription = buildString {
            appendLine("This validation issue runs after merge-conflict remediation on the existing PR lineage.")
            appendLine()
            triggerIssue?.let { appendLine("Trigger issue: ${it.title}") }
            pullRequestUrl?.let { appendLine("Pull request: $it") }
            branchName?.let { appendLine("Branch: $it") }
            appendLine()
            appendLine("Responsibilities:")
            appendLine("- Re-run validation against the remediated PR branch.")
            appendLine("- Summarize any remaining residual risk without manufacturing a new diff.")
            appendLine("- Hand the result back by reopening the existing CEO approval lane, not by creating a new PR.")
        }.trim()
        val remediationAssignee = suggestProfileForCustomIssue(
            title = remediationTitle,
            description = remediationDescription,
            kind = "execution",
            profiles = companyProfiles
        )
        val validationAssignee = findQaProfile(goal.companyId, companyProfiles) ?: suggestProfileForCustomIssue(
            title = validationTitle,
            description = validationDescription,
            kind = "execution",
            profiles = companyProfiles
        )
        val remediationIssue = CompanyIssue(
            id = UUID.randomUUID().toString(),
            companyId = goal.companyId,
            projectContextId = goal.projectContextId,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = remediationTitle,
            description = remediationDescription,
            status = IssueStatus.PLANNED,
            priority = 1,
            kind = "execution",
            assigneeProfileId = remediationAssignee?.id,
            acceptanceCriteria = listOf(
                "The existing PR branch merges cleanly with the latest base branch.",
                "No replacement PR is created for the same remediation."
            ),
            riskLevel = "high",
            codeProducing = true,
            executionIntent = ExecutionIntent.MERGE_CONFLICT_REMEDIATION,
            branchName = branchName,
            worktreePath = worktreePath,
            pullRequestNumber = pullRequestNumber,
            pullRequestUrl = pullRequestUrl,
            pullRequestState = pullRequestState,
            transitionReason = "Merge-conflict follow-up synthesized a deterministic remediation issue on the existing PR lineage.",
            sourceSignal = "merge-conflict-follow-up:${context.reviewQueueItemId ?: context.triggerIssueId ?: goal.id}",
            createdAt = now,
            updatedAt = now
        )
        val validationIssue = CompanyIssue(
            id = UUID.randomUUID().toString(),
            companyId = goal.companyId,
            projectContextId = goal.projectContextId,
            goalId = goal.id,
            workspaceId = workspace.id,
            title = validationTitle,
            description = validationDescription,
            status = IssueStatus.BACKLOG,
            priority = 2,
            kind = "execution",
            assigneeProfileId = validationAssignee?.id,
            dependsOn = listOf(remediationIssue.id),
            acceptanceCriteria = listOf(
                "Validation is re-run against the remediated PR.",
                "Residual risk is summarized without creating a new PR."
            ),
            riskLevel = "medium",
            codeProducing = false,
            executionIntent = ExecutionIntent.VALIDATION_ONLY,
            branchName = branchName,
            worktreePath = worktreePath,
            pullRequestNumber = pullRequestNumber,
            pullRequestUrl = pullRequestUrl,
            pullRequestState = pullRequestState,
            transitionReason = "Merge-conflict follow-up synthesized a deterministic validation pass on the existing PR lineage.",
            sourceSignal = "merge-conflict-follow-up:${context.reviewQueueItemId ?: context.triggerIssueId ?: goal.id}",
            createdAt = now + 1,
            updatedAt = now + 1
        )
        return listOf(remediationIssue, validationIssue) to listOf(
            IssueDependency(
                id = UUID.randomUUID().toString(),
                issueId = validationIssue.id,
                dependsOnIssueId = remediationIssue.id
            )
        )
    }

    private fun buildPlanningIssueDescription(
        goal: CompanyGoal,
        company: Company,
        definitions: List<CompanyAgentDefinition>,
        profiles: List<OrgAgentProfile>
    ): String = buildString {
        appendLine("CEO planning issue for the company goal.")
        appendLine()
        appendLine("Company: ${company.name}")
        appendLine("Goal: ${goal.title}")
        appendLine("Description:")
        appendLine(goal.description.ifBlank { goal.title })
        appendLine()
        appendLine("Roster:")
        definitions
            .filter { it.companyId == company.id && it.enabled }
            .sortedWith(compareBy<CompanyAgentDefinition> { it.displayOrder }.thenBy { it.title.lowercase() })
            .forEach { definition ->
                val capabilities = profiles.firstOrNull { it.id == definition.id }?.capabilities.orEmpty()
                appendLine("- ${definition.title}: ${definition.roleSummary}")
                if (definition.specialties.isNotEmpty()) {
                    appendLine("  specialties: ${definition.specialties.joinToString(", ")}")
                }
                if (capabilities.isNotEmpty()) {
                    appendLine("  capabilities: ${capabilities.joinToString(", ")}")
                }
            }
        appendLine()
        appendLine("Responsibilities:")
        appendLine("- Break the goal into explicit downstream issues.")
        appendLine("- Assign each issue to the best-fit role in the roster.")
        appendLine("- Decide dependency order and whether each issue should produce code and a PR.")
        appendLine("- Keep review and final CEO approval as separate downstream gates.")
    }.trim()

    private fun buildCeoPlanningPrompt(
        state: DesktopAppState,
        issue: CompanyIssue,
        profile: OrgAgentProfile
    ): String {
        val goal = state.goals.firstOrNull { it.id == issue.goalId }
        val company = state.companies.firstOrNull { it.id == issue.companyId }
        val definitions = state.companyAgentDefinitions
            .filter { it.companyId == issue.companyId && it.enabled }
            .sortedWith(compareBy<CompanyAgentDefinition> { it.displayOrder }.thenBy { it.title.lowercase() })
        val recentDecisions = state.goalDecisions
            .filter { it.companyId == issue.companyId }
            .sortedByDescending { it.createdAt }
            .take(5)
        return buildString {
            appendLine("You are the CEO planning the next work graph for the company.")
            appendLine()
            company?.let {
                appendLine("Company: ${it.name}")
                appendLine("Repository root: ${it.rootPath}")
                appendLine("Base branch: ${it.defaultBaseBranch}")
            }
            goal?.let {
                appendLine("Goal: ${it.title}")
                appendLine("Goal description:")
                appendLine(it.description.ifBlank { it.title })
                it.followUpContext?.let { context ->
                    appendLine()
                    appendLine("Follow-up context:")
                    appendLine("- Root goal id: ${context.rootGoalId}")
                    context.triggerIssueId?.let { triggerIssueId -> appendLine("- Trigger issue id: $triggerIssueId") }
                    context.reviewQueueItemId?.let { reviewQueueItemId -> appendLine("- Review queue item id: $reviewQueueItemId") }
                    context.pullRequestNumber?.let { pullRequestNumber -> appendLine("- Pull request number: #$pullRequestNumber") }
                    appendLine("- Failure class: ${context.failureClass.name}")
                }
            }
            appendLine()
            appendLine("Available roster:")
            definitions.forEach { definition ->
                appendLine("- ${definition.title}: ${definition.roleSummary}")
                if (definition.specialties.isNotEmpty()) {
                    appendLine("  specialties: ${definition.specialties.joinToString(", ")}")
                }
            }
            if (recentDecisions.isNotEmpty()) {
                appendLine()
                appendLine("Recent CEO decisions:")
                recentDecisions.forEach { decision ->
                    appendLine("- ${decision.summary}")
                }
            }
            appendLine()
            appendLine("Planning rules:")
            appendLine("- Create only the concrete execution or research issues the company should do next.")
            appendLine("- Do not create QA review or CEO approval issues; Cotor creates PR gates separately.")
            appendLine("- Use one issue per branchable slice of work.")
            appendLine("- Prefer a multi-issue execution graph over one giant issue.")
            appendLine("- When the roster supports it, create 3 to 6 downstream issues and keep at least 2 implementation slices runnable in parallel.")
            appendLine("- Generic work should route to Builder-first unless the goal clearly needs a specialist.")
            appendLine("- Set codeProducing=true when the issue should end with a branch and GitHub PR.")
            appendLine("- For validation-only or residual-risk follow-up work, set codeProducing=false and do not manufacture placeholder repository artifacts just to force a diff.")
            appendLine("- For handoff, reporting, CEO decision, validation, or residual-risk tasks, default to codeProducing=false.")
            if (goal?.followUpContext?.failureClass == FollowUpFailureClass.MERGE_CONFLICT) {
                appendLine("- This goal exists to remediate an existing merge conflict on the current PR lineage.")
                appendLine("- Reuse the existing PR branch/worktree instead of inventing a new handoff PR or a new review lineage.")
            }
            appendLine("- Use dependsOn with refIds from earlier issues when ordering matters.")
            appendLine()
            appendLine("Return JSON only inside one ```json fenced block using this schema:")
            appendLine("```json")
            appendLine("""{"goalSummary":"...","issues":[{"refId":"exec-1","title":"...","description":"...","kind":"execution","assigneeRole":"Builder","priority":2,"codeProducing":true,"dependsOn":[],"acceptanceCriteria":["..."],"reviewRequired":true,"approvalRequired":true}]}""")
            appendLine("```")
            appendLine()
            appendLine("Do not include prose outside the JSON block.")
            appendLine("Current role: ${profile.roleName}")
        }.trim()
    }

    private fun parseCeoPlanningPayload(output: String?): CeoPlanningPayload? {
        val text = output?.trim().orEmpty()
        if (text.isBlank()) {
            return null
        }
        val fencedMatch = Regex("```json\\s*(\\{.*?})\\s*```", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
        val candidate = fencedMatch ?: extractJsonObject(text) ?: return null
        return runCatching {
            backendJson.decodeFromString(CeoPlanningPayload.serializer(), candidate)
        }.getOrNull()?.takeIf { payload ->
            payload.issues.isNotEmpty() && payload.issues.all { issue ->
                issue.refId.isNotBlank() && issue.title.isNotBlank() && issue.assigneeRole.isNotBlank()
            }
        }
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) {
            return null
        }
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val ch = text[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return text.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun materializePlannedIssues(
        goal: CompanyGoal,
        workspace: Workspace,
        profiles: List<OrgAgentProfile>,
        plan: CeoPlanningPayload,
        now: Long,
        planningSource: String
    ): List<CompanyIssue> {
        val companyProfiles = profiles.filter { it.companyId == goal.companyId && it.enabled }
        val issueIdsByRef = linkedMapOf<String, String>()
        val createdIssuePairs = plan.issues.mapIndexed { index, plannedIssue ->
            val issueId = UUID.randomUUID().toString()
            issueIdsByRef[plannedIssue.refId.trim()] = issueId
            val assignee = companyProfiles.firstOrNull { it.roleName.equals(plannedIssue.assigneeRole.trim(), ignoreCase = true) }
                ?: suggestProfileForCustomIssue(
                    title = plannedIssue.title,
                    description = plannedIssue.description,
                    kind = plannedIssue.kind,
                    profiles = companyProfiles
                )
            val normalizedKind = plannedIssue.kind.trim().ifBlank { "execution" }.lowercase()
            val executionIntent = inferExecutionIntent(
                kind = normalizedKind,
                title = plannedIssue.title.trim(),
                description = plannedIssue.description.trim(),
                plannedCodeProducing = plannedIssue.codeProducing
            )
            val codeProducing = when (executionIntent) {
                ExecutionIntent.VALIDATION_ONLY, ExecutionIntent.PR_REUSE_HANDOFF -> false
                ExecutionIntent.MERGE_CONFLICT_REMEDIATION, ExecutionIntent.CODE_CHANGE -> true
                null -> plannedIssue.codeProducing ?: when (normalizedKind) {
                    "planning", "review", "approval", "qa", "infra", "research" -> false
                    else -> true
                }
            }
            plannedIssue to CompanyIssue(
                id = issueId,
                companyId = goal.companyId,
                projectContextId = goal.projectContextId,
                goalId = goal.id,
                workspaceId = workspace.id,
                title = plannedIssue.title.trim(),
                description = plannedIssue.description.trim().ifBlank { plannedIssue.title.trim() },
                status = IssueStatus.PLANNED,
                priority = plannedIssue.priority.coerceIn(1, 4),
                kind = normalizedKind,
                assigneeProfileId = assignee?.id,
                blockedBy = emptyList(),
                dependsOn = emptyList(),
                acceptanceCriteria = plannedIssue.acceptanceCriteria.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
                riskLevel = if (codeProducing) "medium" else "low",
                codeProducing = codeProducing,
                executionIntent = executionIntent,
                transitionReason = if (planningSource == "ceo") {
                    "CEO planning run assigned this issue to ${assignee?.roleName ?: plannedIssue.assigneeRole}."
                } else {
                    "Fallback planner assigned this issue to ${assignee?.roleName ?: plannedIssue.assigneeRole}."
                },
                sourceSignal = "$CEO_PLANNING_SOURCE:${goal.id}",
                createdAt = now + index,
                updatedAt = now + index
            )
        }
        return createdIssuePairs.map { (plannedIssue, createdIssue) ->
            val dependencyIds = plannedIssue.dependsOn.mapNotNull(issueIdsByRef::get).distinct()
            createdIssue.copy(
                status = if (dependencyIds.isEmpty()) IssueStatus.PLANNED else IssueStatus.BACKLOG,
                dependsOn = dependencyIds
            )
        }
    }

    private fun findChiefProfile(companyId: String, profiles: List<OrgAgentProfile>): OrgAgentProfile? =
        profiles.firstOrNull {
            it.companyId == companyId && it.enabled && (it.mergeAuthority || it.roleName.equals("CEO", ignoreCase = true))
        } ?: profiles.firstOrNull { it.companyId == companyId && it.enabled }

    private fun isQaCapableProfile(profile: OrgAgentProfile): Boolean =
        profile.reviewerPolicy != null ||
            profile.roleName.equals("QA", ignoreCase = true) ||
            profile.capabilities.any { capability ->
                capability.equals("qa", ignoreCase = true) || capability.equals("review", ignoreCase = true)
            }

    private fun findQaProfile(companyId: String, profiles: List<OrgAgentProfile>): OrgAgentProfile? =
        profiles.firstOrNull {
            it.companyId == companyId &&
                it.enabled &&
                !it.mergeAuthority &&
                (
                    it.roleName.equals("QA", ignoreCase = true) ||
                        it.capabilities.any { capability -> capability.equals("qa", ignoreCase = true) }
                    )
        } ?: profiles.firstOrNull {
            it.companyId == companyId &&
                it.enabled &&
                !it.mergeAuthority &&
                isQaCapableProfile(it)
        } ?: profiles.firstOrNull {
            it.companyId == companyId &&
                it.enabled &&
                isQaCapableProfile(it)
        } ?: findChiefProfile(companyId, profiles)

    private fun qaReviewSource(issueId: String): String = "$QA_REVIEW_SOURCE_PREFIX$issueId"

    private fun ceoApprovalSource(issueId: String): String = "$CEO_APPROVAL_SOURCE_PREFIX$issueId"

    private fun relatedExecutionIssueId(issue: CompanyIssue): String? = when {
        issue.sourceSignal.startsWith(QA_REVIEW_SOURCE_PREFIX) -> issue.sourceSignal.removePrefix(QA_REVIEW_SOURCE_PREFIX)
        issue.sourceSignal.startsWith(CEO_APPROVAL_SOURCE_PREFIX) -> issue.sourceSignal.removePrefix(CEO_APPROVAL_SOURCE_PREFIX)
        else -> null
    }?.takeIf { it.isNotBlank() }

    private fun isWorkflowIssue(issue: CompanyIssue): Boolean =
        issue.kind.equals("review", ignoreCase = true) || issue.kind.equals("approval", ignoreCase = true)

    private fun workflowLineagesMatch(
        expected: WorkflowLineageSnapshot?,
        actual: WorkflowLineageSnapshot?
    ): Boolean {
        if (expected == null || actual == null) {
            return false
        }
        return expected.lineageId == actual.lineageId &&
            expected.reviewQueueItemId == actual.reviewQueueItemId &&
            expected.executionIssueId == actual.executionIssueId &&
            expected.executionRunId == actual.executionRunId &&
            expected.generation == actual.generation
    }

    private fun latestTaskForIssue(state: DesktopAppState, issueId: String): AgentTask? =
        state.tasks
            .filter { it.issueId == issueId }
            .maxByOrNull { it.updatedAt }

    private fun latestRunForTask(state: DesktopAppState, taskId: String): AgentRun? =
        state.runs
            .filter { it.taskId == taskId }
            .maxByOrNull { it.updatedAt }

    private fun nextWorkflowLineageGeneration(
        state: DesktopAppState,
        executionIssueId: String
    ): Int {
        val observedGenerations = buildList {
            addAll(state.reviewQueue.filter { it.issueId == executionIssueId }.mapNotNull { it.workflowLineage?.generation })
            addAll(
                state.issues
                    .filter {
                        it.id == executionIssueId || relatedExecutionIssueId(it) == executionIssueId
                    }
                    .mapNotNull { it.workflowLineage?.generation }
            )
            addAll(state.tasks.mapNotNull { task ->
                task.workflowLineage?.takeIf { it.executionIssueId == executionIssueId }?.generation
            })
            addAll(state.runs.mapNotNull { run ->
                run.workflowLineage?.takeIf { it.executionIssueId == executionIssueId }?.generation
            })
        }
        return (observedGenerations.maxOrNull() ?: 0) + 1
    }

    private fun buildWorkflowLineageSnapshot(
        reviewQueueItemId: String,
        executionIssueId: String,
        executionTaskId: String,
        executionRun: AgentRun,
        generation: Int,
        lineageId: String = UUID.randomUUID().toString()
    ): WorkflowLineageSnapshot =
        WorkflowLineageSnapshot(
            lineageId = lineageId,
            reviewQueueItemId = reviewQueueItemId,
            executionIssueId = executionIssueId,
            executionTaskId = executionTaskId,
            executionRunId = executionRun.id,
            pullRequestNumber = executionRun.publish?.pullRequestNumber,
            pullRequestUrl = executionRun.publish?.pullRequestUrl,
            branchName = executionRun.branchName,
            worktreePath = executionRun.worktreePath,
            generation = generation
        )

    private fun resolvedWorkflowLineageForQueueItem(
        state: DesktopAppState,
        queueItem: ReviewQueueItem
    ): WorkflowLineageSnapshot? {
        queueItem.workflowLineage?.let { return it }
        val queuedRun = state.runs.firstOrNull { it.id == queueItem.runId }
        val queuedTask = queuedRun?.let { run -> state.tasks.firstOrNull { it.id == run.taskId } }
        val executionTaskAndRun =
            if (queuedTask?.issueId == queueItem.issueId && queuedRun != null) {
                queuedTask to queuedRun
            } else {
                val executionTasks = state.tasks
                    .filter { it.issueId == queueItem.issueId }
                    .sortedByDescending { it.updatedAt }
                val executionRuns = executionTasks
                    .flatMap { task ->
                        state.runs
                            .filter { it.taskId == task.id }
                            .sortedByDescending { it.updatedAt }
                            .map { run -> task to run }
                    }
                val matchedExecution = executionRuns.firstOrNull { (_, run) -> reviewQueueMatchesPublishedRun(queueItem, run) }
                if (matchedExecution != null) {
                    matchedExecution
                } else {
                    val queueHasExplicitExecutionIdentity =
                        queueItem.pullRequestNumber != null ||
                            !queueItem.pullRequestUrl.isNullOrBlank() ||
                            !queueItem.branchName.isNullOrBlank() ||
                            !queueItem.worktreePath.isNullOrBlank()
                    if (!queueHasExplicitExecutionIdentity) {
                        executionRuns.firstOrNull()
                    } else {
                        null
                    }
                }
            }
        val (executionTask, executionRun) = executionTaskAndRun ?: return null
        return buildWorkflowLineageSnapshot(
            reviewQueueItemId = queueItem.id,
            executionIssueId = queueItem.issueId,
            executionTaskId = executionTask.id,
            executionRun = executionRun,
            generation = 1,
            lineageId = "legacy-${queueItem.id}"
        )
    }

    private fun findQueueItemForWorkflowIssue(
        state: DesktopAppState,
        issue: CompanyIssue
    ): ReviewQueueItem? {
        val executionIssueId = relatedExecutionIssueId(issue) ?: return null
        val candidates = state.reviewQueue
            .filter { it.issueId == executionIssueId && it.status != ReviewQueueStatus.MERGED }
            .sortedByDescending { it.updatedAt }
        return when {
            issue.kind.equals("review", ignoreCase = true) ->
                candidates.firstOrNull { it.qaIssueId == issue.id } ?: candidates.firstOrNull()
            issue.kind.equals("approval", ignoreCase = true) ->
                candidates.firstOrNull { it.approvalIssueId == issue.id }
                    ?: candidates.firstOrNull { it.status == ReviewQueueStatus.READY_FOR_CEO }
                    ?: candidates.firstOrNull()
            else -> null
        }
    }

    private fun expectedWorkflowLineageForIssue(
        state: DesktopAppState,
        issue: CompanyIssue
    ): WorkflowLineageSnapshot? =
        issue.workflowLineage ?: findQueueItemForWorkflowIssue(state, issue)?.let { queueItem ->
            resolvedWorkflowLineageForQueueItem(state, queueItem)
        }

    private fun workflowIssueNeedsFreshTask(
        issue: CompanyIssue,
        latestTask: AgentTask?,
        latestRun: AgentRun?,
        expectedLineage: WorkflowLineageSnapshot?
    ): Boolean {
        if (latestTask == null) {
            return true
        }
        if (latestTask.status == DesktopTaskStatus.FAILED || latestTask.status == DesktopTaskStatus.PARTIAL) {
            return true
        }
        if (issue.updatedAt > latestTask.updatedAt) {
            return true
        }
        if (expectedLineage == null) {
            return latestTask.workflowLineage != null || latestRun?.workflowLineage != null
        }
        if (
            workflowLineagesMatch(expectedLineage, issue.workflowLineage) &&
            workflowLineagesMatch(expectedLineage, latestTask.workflowLineage) &&
            (latestRun == null || workflowLineagesMatch(expectedLineage, latestRun.workflowLineage))
        ) {
            return false
        }
        if (canAdoptExpectedWorkflowLineage(issue, latestTask, latestRun, expectedLineage)) {
            return false
        }
        return true
    }

    private fun canAdoptExpectedWorkflowLineage(
        issue: CompanyIssue,
        task: AgentTask,
        run: AgentRun?,
        expectedLineage: WorkflowLineageSnapshot?
    ): Boolean {
        if (expectedLineage == null) {
            return false
        }
        if (issue.updatedAt > task.updatedAt) {
            return false
        }
        val explicitMismatch =
            (issue.workflowLineage != null && !workflowLineagesMatch(expectedLineage, issue.workflowLineage)) ||
                (task.workflowLineage != null && !workflowLineagesMatch(expectedLineage, task.workflowLineage)) ||
                (run?.workflowLineage != null && !workflowLineagesMatch(expectedLineage, run.workflowLineage))
        return !explicitMismatch
    }

    private fun synthesizeLegacyWorkflowLineageForCurrentTask(
        queueItem: ReviewQueueItem,
        task: AgentTask,
        run: AgentRun?
    ): WorkflowLineageSnapshot =
        WorkflowLineageSnapshot(
            lineageId = "legacy-workflow-${queueItem.id}",
            reviewQueueItemId = queueItem.id,
            executionIssueId = queueItem.issueId,
            executionTaskId = task.id,
            executionRunId = run?.id ?: queueItem.runId.ifBlank { "legacy-queue:${queueItem.id}" },
            pullRequestNumber = queueItem.pullRequestNumber,
            pullRequestUrl = queueItem.pullRequestUrl,
            branchName = queueItem.branchName,
            worktreePath = queueItem.worktreePath,
            generation = 1
        )

    private fun reviewQueueReflectsExecutionRun(
        queueItem: ReviewQueueItem,
        task: AgentTask,
        run: AgentRun?
    ): Boolean {
        if (run == null) {
            return false
        }
        val queueLineage = queueItem.workflowLineage
        if (queueLineage != null) {
            return queueLineage.executionTaskId == task.id && queueLineage.executionRunId == run.id
        }
        return queueItem.runId == run.id
    }

    private fun buildQaReviewIssueDescription(executionIssue: CompanyIssue, queueItem: ReviewQueueItem): String = buildString {
        appendLine("QA review issue for a concrete pull request.")
        appendLine()
        appendLine("Execution issue: ${executionIssue.title}")
        queueItem.branchName?.let { appendLine("Branch: $it") }
        queueItem.pullRequestUrl?.let { appendLine("Pull request: $it") }
        queueItem.checksSummary?.takeIf { it.isNotBlank() }?.let {
            appendLine("Checks summary:")
            appendLine(it)
        }
        appendLine()
        appendLine("Responsibilities:")
        appendLine("- Review the current PR and branch evidence only.")
        appendLine("- Validate the implementation against the stated acceptance criteria.")
        appendLine("- Emit QA_VERDICT: PASS or QA_VERDICT: CHANGES_REQUESTED with concise feedback.")
    }.trim()

    private fun buildCeoApprovalIssueDescription(executionIssue: CompanyIssue, queueItem: ReviewQueueItem): String = buildString {
        appendLine("CEO approval issue for a QA-reviewed pull request.")
        appendLine()
        appendLine("Execution issue: ${executionIssue.title}")
        queueItem.branchName?.let { appendLine("Branch: $it") }
        queueItem.pullRequestUrl?.let { appendLine("Pull request: $it") }
        queueItem.qaVerdict?.let { appendLine("QA verdict: $it") }
        queueItem.qaFeedback?.takeIf { it.isNotBlank() }?.let {
            appendLine("QA feedback:")
            appendLine(it)
        }
        appendLine()
        appendLine("Responsibilities:")
        appendLine("- Review the current PR and the latest QA verdict.")
        appendLine("- Emit CEO_VERDICT: APPROVE or CEO_VERDICT: CHANGES_REQUESTED with concise feedback.")
        appendLine("- Only approve if the PR is ready to merge into the base branch.")
    }.trim()

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
        val goalsById = state.goals.associateBy { it.id }
        val reviewQueueById = state.reviewQueue.associateBy { it.id }

        data class FollowUpCandidate(
            val issue: CompanyIssue,
            val reviewQueueItem: ReviewQueueItem? = null,
            val failureClass: FollowUpFailureClass,
            val rootGoalId: String,
            val followUpContext: FollowUpContextSnapshot,
            val reason: String,
            val marker: String,
            val subject: String
        )

        fun hasOpenFollowUp(marker: String): Boolean =
            openGoals.any { it.operatingPolicy == marker }

        fun hasOpenFollowUpForContext(context: FollowUpContextSnapshot): Boolean =
            openGoals.any { openGoal ->
                val existing = openGoal.followUpContext ?: return@any false
                existing.rootGoalId == context.rootGoalId &&
                    existing.failureClass == context.failureClass &&
                    (
                        existing.reviewQueueItemId != null && existing.reviewQueueItemId == context.reviewQueueItemId ||
                            (
                                existing.reviewQueueItemId == null &&
                                    context.reviewQueueItemId == null &&
                                    existing.triggerIssueId == context.triggerIssueId
                                )
                        )
            }

        val openFollowUpSubjects = openGoals
            .filter { it.operatingPolicy.orEmpty().startsWith("auto-follow-up:") }
            .map { canonicalFollowUpSubject(it.title) }
            .toSet()

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
                val rootGoalId = resolveFollowUpRootGoalId(issue.goalId, issuesById, goalsById, reviewQueueById)
                    ?: issue.goalId
                val marker = "auto-follow-up:goal:$rootGoalId"
                val failureClass = when {
                    item.status == ReviewQueueStatus.CHANGES_REQUESTED && item.mergeability.equals("DIRTY", ignoreCase = true) ->
                        FollowUpFailureClass.MERGE_CONFLICT
                    item.status == ReviewQueueStatus.FAILED_CHECKS -> FollowUpFailureClass.REVIEW_FAILED_CHECKS
                    else -> FollowUpFailureClass.REVIEW_CHANGES_REQUESTED
                }
                val followUpContext = FollowUpContextSnapshot(
                    rootGoalId = rootGoalId,
                    triggerIssueId = issue.id,
                    reviewQueueItemId = item.id,
                    pullRequestNumber = item.pullRequestNumber ?: issue.pullRequestNumber,
                    failureClass = failureClass
                )
                if (hasOpenFollowUpForContext(followUpContext)) return@mapNotNull null
                if (hasOpenFollowUp(marker)) return@mapNotNull null
                val subject = canonicalFollowUpSubject(issue.title)
                if (subject in openFollowUpSubjects) return@mapNotNull null
                val reason = when (item.status) {
                    ReviewQueueStatus.FAILED_CHECKS -> "Review queue checks failed and require a remediation loop."
                    ReviewQueueStatus.CHANGES_REQUESTED -> when (failureClass) {
                        FollowUpFailureClass.MERGE_CONFLICT ->
                            "The current PR no longer merges cleanly with the latest base branch and needs deterministic remediation on the existing PR lineage."
                        else -> "Review requested changes and requires a remediation loop."
                    }
                    else -> return@mapNotNull null
                }
                FollowUpCandidate(
                    issue = issue,
                    reviewQueueItem = item,
                    failureClass = failureClass,
                    rootGoalId = rootGoalId,
                    followUpContext = followUpContext,
                    reason = reason,
                    marker = marker,
                    subject = subject
                )
            }
            .firstOrNull()

        val blockedIssueCandidate = state.issues
            .filter {
                it.companyId == companyId &&
                    it.status == IssueStatus.BLOCKED &&
                    !it.kind.equals("planning", ignoreCase = true)
            }
            .sortedByDescending { it.updatedAt }
            .mapNotNull { issue ->
                if (issue.id in activeTaskIssueIds) return@mapNotNull null
                val parentGoal = state.goals.firstOrNull { it.id == issue.goalId }
                if (parentGoal?.operatingPolicy?.startsWith("auto-follow-up:") == true) {
                    return@mapNotNull null
                }
                val rootGoalId = resolveFollowUpRootGoalId(issue.goalId, issuesById, goalsById, reviewQueueById)
                    ?: issue.goalId
                val marker = "auto-follow-up:goal:$rootGoalId"
                val followUpContext = FollowUpContextSnapshot(
                    rootGoalId = rootGoalId,
                    triggerIssueId = issue.id,
                    failureClass = FollowUpFailureClass.BLOCKED_EXECUTION
                )
                if (hasOpenFollowUpForContext(followUpContext)) return@mapNotNull null
                if (hasOpenFollowUp(marker)) return@mapNotNull null
                val subject = canonicalFollowUpSubject(issue.title)
                if (subject in openFollowUpSubjects) return@mapNotNull null
                FollowUpCandidate(
                    issue = issue,
                    failureClass = FollowUpFailureClass.BLOCKED_EXECUTION,
                    rootGoalId = rootGoalId,
                    followUpContext = followUpContext,
                    reason = "Execution is blocked and needs a new CEO-managed remediation goal.",
                    marker = marker,
                    subject = subject
                )
            }
            .firstOrNull()

        val candidate = failedReviewCandidate ?: blockedIssueCandidate
        if (candidate != null) {
            val parentGoal = state.goals.firstOrNull { it.id == candidate.issue.goalId }
            val title = "Resolve follow-up for \"${candidate.subject}\""
            val description = buildString {
                appendLine("CEO generated this goal automatically because follow-up work is required.")
                appendLine()
                appendLine("Company: ${company.name}")
                parentGoal?.let {
                    appendLine("Parent goal: ${it.title}")
                }
                appendLine("Trigger issue: ${candidate.issue.title}")
                candidate.reviewQueueItem?.pullRequestUrl?.let { appendLine("Pull request: $it") }
                appendLine("Reason: ${candidate.reason}")
                appendLine()
                appendLine("Required outcome:")
                when (candidate.failureClass) {
                    FollowUpFailureClass.MERGE_CONFLICT -> {
                        appendLine("- Reuse the current PR branch and worktree to resolve the merge conflict.")
                        appendLine("- Re-run validation and capture residual risk on the existing PR lineage.")
                        appendLine("- Reopen the existing CEO approval lane instead of creating a handoff PR.")
                    }
                    else -> {
                        appendLine("- Unblock the affected work or satisfy the failed review signal.")
                        appendLine("- Re-run validation and capture any residual risk.")
                        appendLine("- Summarize what the CEO should decide next once the remediation is complete.")
                        appendLine("- Create the next wave so the company can keep moving with multiple branchable issues when more work remains.")
                    }
                }
            }
            return createGoal(
                companyId = companyId,
                title = title,
                description = description,
                successMetrics = listOf(
                    "The triggering issue is no longer blocked or failed in review.",
                    "Validation is re-run and residual risks are documented.",
                    "The CEO receives a concrete next-wave recommendation instead of a dead-end remediation note."
                ),
                autonomyEnabled = true,
                priority = 1,
                operatingPolicy = candidate.marker,
                followUpContext = candidate.followUpContext,
                startRuntimeIfNeeded = false
            )
        }

        val hasActiveTasks = state.tasks.any { task ->
            (task.status == DesktopTaskStatus.RUNNING || task.status == DesktopTaskStatus.QUEUED) &&
                task.issueId?.let(issuesById::containsKey) == true
        }
        val unresolvedIssues = state.issues.filter {
            it.companyId == companyId && it.status != IssueStatus.DONE && it.status != IssueStatus.CANCELED
        }
        val hasActiveAutonomousGoals = openGoals.any { it.autonomyEnabled && it.status == GoalStatus.ACTIVE }
        val companyHasHistory = state.goals.any { it.companyId == companyId } || state.companyActivity.any { it.companyId == companyId }
        if (!companyHasHistory || hasActiveTasks || unresolvedIssues.isNotEmpty() || hasActiveAutonomousGoals) {
            return null
        }

        val lastContinuousCycle = state.goals
            .filter { it.companyId == companyId }
            .mapNotNull { goal ->
                goal.operatingPolicy
                    ?.takeIf { it.startsWith("auto-loop:continuous:") }
                    ?.removePrefix("auto-loop:continuous:")
                    ?.toIntOrNull()
            }
            .maxOrNull() ?: 0
        val nextCycle = lastContinuousCycle + 1
        val recentCompletedGoals = state.goals
            .filter {
                it.companyId == companyId &&
                    it.status == GoalStatus.COMPLETED &&
                    !it.operatingPolicy.orEmpty().startsWith("auto-follow-up:") &&
                    !isGeneratedFollowUpTitle(it.title)
            }
            .sortedByDescending { it.updatedAt }
            .take(3)
        val recentCompletedIssues = state.issues
            .filter {
                it.companyId == companyId &&
                    it.status == IssueStatus.DONE &&
                    !isGeneratedFollowUpTitle(it.title)
            }
            .sortedByDescending { it.updatedAt }
            .take(5)
        val title = "CEO continuous improvement cycle #$nextCycle for ${company.name}"
        val description = buildString {
            appendLine("CEO generated this goal automatically to keep the company operating without a manual pause.")
            appendLine()
            appendLine("Company: ${company.name}")
            appendLine("Cycle: #$nextCycle")
            if (recentCompletedGoals.isNotEmpty()) {
                appendLine()
                appendLine("Recently completed goals:")
                recentCompletedGoals.forEach { goal ->
                    appendLine("- ${goal.title}")
                }
            }
            if (recentCompletedIssues.isNotEmpty()) {
                appendLine()
                appendLine("Recently completed issues:")
                recentCompletedIssues.forEach { issue ->
                    appendLine("- ${issue.title}")
                }
            }
            appendLine()
            appendLine("CEO directive:")
            appendLine("- Review the current company state, recent wins, and unresolved product gaps.")
            appendLine("- Create a portfolio of 3 to 5 branchable issues for the next cycle instead of one narrow slice.")
            appendLine("- Use the current roster to run multiple compatible implementation and validation tracks in parallel when possible.")
            appendLine("- End this cycle with reviewed work and an explicit CEO decision about the next wave.")
        }
        return createGoal(
            companyId = companyId,
            title = title,
            description = description,
            successMetrics = listOf(
                "The CEO identifies the next improvement cycle and delegates it across the current roster.",
                "At least two branchable issues are opened when the roster can support concurrent work.",
                "Reviewed execution slices complete and residual risk is recorded before the next CEO decision."
            ),
            autonomyEnabled = true,
            priority = 2,
            operatingPolicy = "auto-loop:continuous:$nextCycle",
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
        val relatedExecutionIssue = relatedExecutionIssueId(issue)?.let { executionIssueId ->
            state.issues.firstOrNull { it.id == executionIssueId }
        }
        val goalReviewQueueItems = state.reviewQueue
            .filter { reviewItem ->
                state.issues.firstOrNull { it.id == reviewItem.issueId }?.goalId == issue.goalId
            }
            .sortedByDescending { it.updatedAt }
        val scopedQueueItems = relatedExecutionIssue?.let { executionIssue ->
            goalReviewQueueItems.filter { it.issueId == executionIssue.id }
        } ?: goalReviewQueueItems
        val qaPendingQueueItems = scopedQueueItems
            .filter { it.status == ReviewQueueStatus.AWAITING_QA || it.status == ReviewQueueStatus.FAILED_CHECKS }
        val ceoReadyQueueItems = scopedQueueItems
            .filter { it.status == ReviewQueueStatus.READY_FOR_CEO }
        val scopedExecutionIssueIds = when (issue.kind.lowercase()) {
            "review", "approval" -> relatedExecutionIssue?.id?.let(::setOf) ?: emptySet()
            else -> emptySet()
        }
        val scopedExecutionIssues = state.issues
            .filter {
                it.goalId == issue.goalId &&
                    it.kind.equals("execution", ignoreCase = true) &&
                    (it.status == IssueStatus.IN_REVIEW || it.status == IssueStatus.READY_FOR_CEO || it.status == IssueStatus.DONE) &&
                    (scopedExecutionIssueIds.isEmpty() || it.id in scopedExecutionIssueIds)
            }
            .sortedByDescending { it.updatedAt }
        val queueScopedExecutionEvidence = if (issue.kind.equals("review", ignoreCase = true) || issue.kind.equals("approval", ignoreCase = true)) {
            scopedQueueItems.mapNotNull { reviewItem ->
                val run = state.runs.firstOrNull { it.id == reviewItem.runId && it.status == AgentRunStatus.COMPLETED }
                    ?: return@mapNotNull null
                val executionIssue = state.issues.firstOrNull { it.id == reviewItem.issueId } ?: return@mapNotNull null
                Triple(reviewItem, executionIssue, run)
            }
                .distinctBy { (_, _, run) -> run.id }
                .take(3)
        } else {
            emptyList()
        }
        val completedExecutionRuns = if (queueScopedExecutionEvidence.isNotEmpty()) {
            queueScopedExecutionEvidence.map { (_, executionIssue, completedRun) ->
                buildString {
                    append("- ${executionIssue.title}")
                    append(" [branch: ${completedRun.branchName}]")
                    completedRun.publish?.commitSha?.let { append(" [commit: ${it.take(7)}]") }
                }
            }
        } else {
            scopedExecutionIssues
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
        }
        val completedExecutionEvidence = if (queueScopedExecutionEvidence.isNotEmpty()) {
            queueScopedExecutionEvidence.map { (_, executionIssue, completedRun) ->
                buildString {
                    appendLine("- ${executionIssue.title}")
                    appendLine("  branch: ${completedRun.branchName}")
                    completedRun.publish?.commitSha?.let { appendLine("  commit: ${it.take(7)}") }
                    completedRun.output?.takeIf { it.isNotBlank() }?.let { output ->
                        appendLine("  execution summary: ${summarizeForPrompt(output, 280)}")
                    }
                }.trimEnd()
            }
        } else {
            scopedExecutionIssues
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
        }
        val completedReviewEvidence = when (issue.kind.lowercase()) {
            "approval" -> ceoReadyQueueItems.map { reviewItem ->
                buildString {
                    appendLine("- issueId: ${reviewItem.issueId}")
                    reviewItem.pullRequestUrl?.let { appendLine("  pr: $it") }
                    reviewItem.qaVerdict?.let { appendLine("  qaVerdict: $it") }
                    reviewItem.qaFeedback?.takeIf { it.isNotBlank() }?.let { feedback ->
                        appendLine("  qaFeedback: ${summarizeForPrompt(feedback, 280)}")
                    }
                }.trimEnd()
            }
                .take(3)
            else ->
                state.issues
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
        }
        val promptBody = when (issue.kind.lowercase()) {
            "planning" -> buildCeoPlanningPrompt(state, issue, profile)
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
                val qaPromptQueueItems = qaPendingQueueItems.ifEmpty { goalReviewQueueItems.filter { it.status != ReviewQueueStatus.MERGED } }
                if (qaPromptQueueItems.isNotEmpty()) {
                    appendLine()
                    appendLine("Open pull request handoffs:")
                    qaPromptQueueItems.forEach { reviewItem ->
                        appendLine("- issueId: ${reviewItem.issueId}")
                        reviewItem.branchName?.let { appendLine("  branch: $it") }
                        reviewItem.pullRequestUrl?.let { appendLine("  pr: $it") }
                        reviewItem.checksSummary?.let { appendLine("  checks: ${summarizeForPrompt(it, 220)}") }
                    }
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
                appendLine("- The first line of your response must be exactly `QA_VERDICT: PASS` or `QA_VERDICT: CHANGES_REQUESTED`.")
                appendLine("- After the verdict line, return concise feedback, residual risks, and whether the work is ready for CEO approval.")
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
                if (ceoReadyQueueItems.isNotEmpty()) {
                    appendLine()
                    appendLine("Pull requests awaiting CEO approval:")
                    ceoReadyQueueItems.forEach { reviewItem ->
                        appendLine("- issueId: ${reviewItem.issueId}")
                        reviewItem.branchName?.let { appendLine("  branch: $it") }
                        reviewItem.pullRequestUrl?.let { appendLine("  pr: $it") }
                        reviewItem.qaVerdict?.let { appendLine("  qaVerdict: $it") }
                        reviewItem.qaFeedback?.let { appendLine("  qaFeedback: ${summarizeForPrompt(it, 220)}") }
                    }
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
                appendLine("- The first line of your response must be exactly `CEO_VERDICT: APPROVE` or `CEO_VERDICT: CHANGES_REQUESTED`.")
                appendLine("- After the verdict line, return one concise rationale for merge or rework.")
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
                val validationOnlyFollowUp = issue.kind.equals("execution", ignoreCase = true) && !requiresCodePublish(issue)
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
                if (validationOnlyFollowUp) {
                    appendLine("- Re-run the smallest validation needed for this issue and capture exact evidence: ${issue.title}")
                } else {
                    appendLine("- Make the smallest complete repository change that satisfies this issue: ${issue.title}")
                }
                appendLine("- Act as a direct worker. Do not act like a planner or orchestrator.")
                appendLine("- Start editing immediately. Do not spend time on broad repo exploration.")
                appendLine("- Do not create plans, spawn sub-agents, or do broad multi-step analysis.")
                if (validationOnlyFollowUp) {
                    appendLine("- Do not create README-only, VALIDATION.md-only, or other placeholder repository changes just to produce a diff.")
                    appendLine("- If the branch already contains the right evidence, leave files untouched and report the findings precisely.")
                    appendLine("- No publish is required for a pure validation or residual-risk follow-up.")
                } else {
                    appendLine("- If there is no legitimate product change to make, do not manufacture README-only or placeholder artifacts just to force a diff; report the blocker instead.")
                }
                appendLine("- Run one targeted validation command that proves the change works, then stop.")
                issue.qaFeedback?.takeIf { it.isNotBlank() }?.let { feedback ->
                    appendLine()
                    appendLine("Latest QA feedback:")
                    appendLine(summarizeForPrompt(feedback, 240))
                }
                issue.ceoFeedback?.takeIf { it.isNotBlank() }?.let { feedback ->
                    appendLine()
                    appendLine("Latest CEO feedback:")
                    appendLine(summarizeForPrompt(feedback, 240))
                }
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
                        }
                    ) {
                        capabilities += capability
                    }
                }
                addIfMatch("planning", "ceo", "chief", "lead", "strategy", "planner", "planning", "manager", "director")
                addIfMatch("triage", "triage", "coordination", "orchestr", "assign", "delegate")
                addIfMatch("goal-decomposition", "plan", "roadmap", "spec", "product", "breakdown")
                addIfMatch("review", "review", "approve", "approval")
                addIfMatch("qa", "qa", "test", "verification", "validate")
                if (role.equals("QA", ignoreCase = true) || descriptor.contains("quality assurance")) {
                    capabilities += "qa"
                }
                addIfMatch("implementation", "build", "builder", "implement", "ship", "delivery", "developer", "engineer")
                addIfMatch("backend", "backend", "server", "api", "kotlin", "infra", "platform")
                addIfMatch("frontend", "frontend", "ui", "ux", "design", "desktop", "swift")
                addIfMatch("integration", "integration", "merge", "release", "ops")
                if (capabilities.isEmpty()) {
                    capabilities += listOf("implementation", "integration")
                }
                val capabilityList = capabilities.toList()
                val isChief = role.equals("CEO", ignoreCase = true)
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
        data class SeededRole(
            val title: String,
            val roleSummary: String,
            val specialties: List<String> = emptyList(),
            val collaborationInstructions: String? = null,
            val preferredCollaborators: List<String> = emptyList(),
            val memoryNotes: String? = null
        )

        val templates = listOf(
            SeededRole(
                title = "CEO",
                roleSummary = "lead strategy, planning, triage, staffing decisions, and final merge approval",
                specialties = listOf("strategy", "triage", "merge", "escalation"),
                collaborationInstructions = "Review company state first, then assign execution and review work to the rest of the organization.",
                preferredCollaborators = listOf("Product Strategist", "Engineering Lead", "QA"),
                memoryNotes = "You are the top-level owner. Keep the company moving and decide the next cycle."
            ),
            SeededRole(
                title = "Product Strategist",
                roleSummary = "translate company goals into scoped work, requirements, and prioritization guidance",
                specialties = listOf("product", "requirements", "roadmap", "discovery"),
                collaborationInstructions = "Clarify the next slice before builders start, and hand planning context back to the CEO.",
                preferredCollaborators = listOf("CEO", "UX Builder", "Engineering Lead")
            ),
            SeededRole(
                title = "Engineering Lead",
                roleSummary = "coordinate architecture, implementation direction, integration planning, and technical delegation",
                specialties = listOf("architecture", "integration", "backend", "frontend"),
                collaborationInstructions = "Split technical work across builders and surface integration risks early.",
                preferredCollaborators = listOf("CEO", "Builder", "Backend Builder", "Release Manager")
            ),
            SeededRole(
                title = "UX Builder",
                roleSummary = "shape product flows, usability, interaction clarity, and user-facing experience",
                specialties = listOf("ux", "research", "flows", "usability"),
                collaborationInstructions = "Turn product requirements into clearer flows before UI polish begins.",
                preferredCollaborators = listOf("Product Strategist", "UI Builder", "Builder")
            ),
            SeededRole(
                title = "UI Builder",
                roleSummary = "craft visual interface details, component polish, layout quality, and design fidelity",
                specialties = listOf("ui", "design", "components", "visual polish"),
                collaborationInstructions = "Convert UX intent into concrete screens and hand implementation-ready guidance to frontend.",
                preferredCollaborators = listOf("UX Builder", "Builder")
            ),
            SeededRole(
                title = "Builder",
                roleSummary = "implement assigned product slices, integrate changes, and deliver reviewable work",
                specialties = listOf("implementation", "integration", "delivery"),
                collaborationInstructions = "Own user-facing implementation and coordinate with UI/UX roles on fidelity gaps.",
                preferredCollaborators = listOf("UI Builder", "UX Builder", "Backend Builder")
            ),
            SeededRole(
                title = "Backend Builder",
                roleSummary = "implement backend behavior, orchestration logic, APIs, and integration reliability",
                specialties = listOf("backend", "api", "kotlin", "orchestration"),
                collaborationInstructions = "Own service and runtime changes, then hand integration notes to QA and release.",
                preferredCollaborators = listOf("Engineering Lead", "Builder", "QA")
            ),
            SeededRole(
                title = "QA",
                roleSummary = "review completed work, verify behavior, run qa checks, and summarize residual risk",
                specialties = listOf("qa", "review", "verification", "testing"),
                collaborationInstructions = "Review every completed slice and decide whether more remediation is needed before release.",
                preferredCollaborators = listOf("Backend Builder", "Builder", "Release Manager")
            ),
            SeededRole(
                title = "Release Manager",
                roleSummary = "coordinate release readiness, final integration checks, operational notes, and delivery handoff",
                specialties = listOf("release", "ops", "integration", "delivery"),
                collaborationInstructions = "Prepare the final slice for CEO approval and call out any operational or release blockers.",
                preferredCollaborators = listOf("QA", "Engineering Lead", "CEO")
            )
        )

        val idByTitle = templates.associate { it.title to UUID.randomUUID().toString() }
        return templates.mapIndexed { index, template ->
            CompanyAgentDefinition(
                id = idByTitle.getValue(template.title),
                companyId = companyId,
                title = template.title,
                agentCli = preferredAgent,
                roleSummary = template.roleSummary,
                specialties = template.specialties,
                collaborationInstructions = template.collaborationInstructions,
                preferredCollaboratorIds = template.preferredCollaborators.mapNotNull(idByTitle::get),
                memoryNotes = template.memoryNotes,
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
        val snapshot = stateStore.load()
        val task = snapshot.tasks.firstOrNull { it.id == taskId } ?: return
        val issueId = task.issueId ?: return
        val latestRuns = snapshot.runs.filter { it.taskId == taskId }
        val primaryRun = latestRuns.firstOrNull { it.publish?.pullRequestUrl != null } ?: latestRuns.firstOrNull()
        val issue = snapshot.issues.firstOrNull { it.id == issueId } ?: return
        when (issue.kind.lowercase()) {
            "planning" -> syncPlanningIssueFromTask(task, issue, primaryRun, finalStatus)
            "review" -> syncReviewIssueFromTask(task, issue, primaryRun, finalStatus)
            "approval" -> syncApprovalIssueFromTask(task, issue, primaryRun, finalStatus)
            else -> syncExecutionIssueFromTask(task, issue, primaryRun, finalStatus)
        }
        runCatching { extractAgentContextAndMessages(issue, primaryRun) }
    }

    private suspend fun extractAgentContextAndMessages(issue: CompanyIssue, run: AgentRun?) {
        val output = run?.output ?: return
        val agentName = run.agentName
        val now = System.currentTimeMillis()
        val contextEntries = mutableListOf<AgentContextEntry>()
        val messages = mutableListOf<AgentMessage>()
        for (line in output.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("CONTEXT_NOTE:") -> contextEntries += AgentContextEntry(
                    id = UUID.randomUUID().toString(), companyId = issue.companyId,
                    issueId = issue.id, goalId = issue.goalId, agentName = agentName,
                    kind = "note", title = trimmed.substringAfter("CONTEXT_NOTE:").trim().take(100),
                    content = trimmed.substringAfter("CONTEXT_NOTE:").trim(), visibility = "goal", createdAt = now
                )
                trimmed.startsWith("CONTEXT_HANDOFF:") -> contextEntries += AgentContextEntry(
                    id = UUID.randomUUID().toString(), companyId = issue.companyId,
                    issueId = issue.id, goalId = issue.goalId, agentName = agentName,
                    kind = "handoff", title = trimmed.substringAfter("CONTEXT_HANDOFF:").trim().take(100),
                    content = trimmed.substringAfter("CONTEXT_HANDOFF:").trim(), visibility = "goal", createdAt = now
                )
                trimmed.startsWith("CONTEXT_WARNING:") -> contextEntries += AgentContextEntry(
                    id = UUID.randomUUID().toString(), companyId = issue.companyId,
                    issueId = issue.id, goalId = issue.goalId, agentName = agentName,
                    kind = "warning", title = trimmed.substringAfter("CONTEXT_WARNING:").trim().take(100),
                    content = trimmed.substringAfter("CONTEXT_WARNING:").trim(), visibility = "company", createdAt = now
                )
                trimmed.startsWith("MESSAGE_TO ") -> {
                    val rest = trimmed.removePrefix("MESSAGE_TO ").trim()
                    val colonIdx = rest.indexOf(':')
                    if (colonIdx > 0) {
                        val toAgent = rest.substring(0, colonIdx).trim()
                        val body = rest.substring(colonIdx + 1).trim()
                        messages += AgentMessage(
                            id = UUID.randomUUID().toString(), companyId = issue.companyId,
                            fromAgentName = agentName, toAgentName = toAgent,
                            issueId = issue.id, goalId = issue.goalId,
                            kind = "feedback", subject = body.take(80), body = body, createdAt = now
                        )
                    }
                }
                trimmed.startsWith("ESCALATION ") -> {
                    val rest = trimmed.removePrefix("ESCALATION ").trim()
                    val colonIdx = rest.indexOf(':')
                    if (colonIdx > 0) {
                        val toAgent = rest.substring(0, colonIdx).trim()
                        val body = rest.substring(colonIdx + 1).trim()
                        messages += AgentMessage(
                            id = UUID.randomUUID().toString(), companyId = issue.companyId,
                            fromAgentName = agentName, toAgentName = toAgent,
                            issueId = issue.id, goalId = issue.goalId,
                            kind = "escalation", subject = body.take(80), body = body, createdAt = now
                        )
                    }
                }
            }
        }
        if (contextEntries.isNotEmpty() || messages.isNotEmpty()) {
            stateMutex.withLock {
                val state = stateStore.load()
                stateStore.save(
                    state.copy(
                        agentContextEntries = state.agentContextEntries + contextEntries,
                        agentMessages = state.agentMessages + messages
                    )
                )
            }
            messages.forEach { msg ->
                publishCompanyEvent(
                    companyId = msg.companyId, type = "agent.message",
                    title = "${msg.fromAgentName} -> ${msg.toAgentName ?: "all"}: ${msg.subject}",
                    detail = msg.body.take(200), goalId = msg.goalId, issueId = msg.issueId
                )
            }
        }
    }

    private suspend fun syncPlanningIssueFromTask(
        task: AgentTask,
        issue: CompanyIssue,
        primaryRun: AgentRun?,
        finalStatus: DesktopTaskStatus
    ) {
        var runtimeContinuationCompanyId: String? = null
        val traceEvents = mutableListOf<CompanyAutomationTraceEvent>()
        val now = System.currentTimeMillis()
        stateMutex.withLock {
            val state = stateStore.load()
            val currentIssue = state.issues.firstOrNull { it.id == issue.id } ?: return@withLock
            val goal = state.goals.firstOrNull { it.id == currentIssue.goalId } ?: return@withLock
            val company = state.companies.firstOrNull { it.id == currentIssue.companyId } ?: return@withLock
            val workspace = state.workspaces.firstOrNull { it.id == currentIssue.workspaceId } ?: return@withLock
            val profiles = ensureOrgProfiles(state.orgProfiles, state.companyAgentDefinitions, state.companies)
            val existingNonPlanning = state.issues.filter {
                it.goalId == goal.id && !it.kind.equals("planning", ignoreCase = true)
            }
            val unresolvedNonPlanning = existingNonPlanning.filterNot {
                it.status == IssueStatus.DONE || it.status == IssueStatus.CANCELED
            }
            if (unresolvedNonPlanning.isNotEmpty()) {
                val updatedPlanningIssue = currentIssue.copy(
                    status = IssueStatus.DONE,
                    transitionReason = "CEO planning lane already produced downstream issues for this goal.",
                    updatedAt = now
                )
                stateStore.save(
                    state.copy(
                        issues = state.issues.map { existing -> if (existing.id == currentIssue.id) updatedPlanningIssue else existing }
                    ).withDerivedMetrics()
                )
                return@withLock
            }

            val parsedPlan = if (finalStatus == DesktopTaskStatus.COMPLETED) parseCeoPlanningPayload(primaryRun?.output) else null
            val planningSource = if (parsedPlan != null) "ceo" else "fallback"
            val fallback = buildFallbackGoalIssues(
                state = state,
                goal = goal,
                workspace = workspace,
                profiles = profiles,
                definitions = state.companyAgentDefinitions,
                now = now
            )
            val decomposition = if (parsedPlan != null) {
                val plannedIssues = materializePlannedIssues(
                    goal = goal,
                    workspace = workspace,
                    profiles = profiles,
                    plan = parsedPlan,
                    now = now,
                    planningSource = "ceo"
                )
                plannedIssues to plannedIssues.flatMap { createdIssue ->
                    createdIssue.dependsOn.map { dependencyId ->
                        IssueDependency(
                            id = UUID.randomUUID().toString(),
                            issueId = createdIssue.id,
                            dependsOnIssueId = dependencyId
                        )
                    }
                }
            } else {
                fallback
            }
            val planningReason = when {
                parsedPlan != null -> "CEO planning run created ${decomposition.first.size} downstream issues."
                finalStatus == DesktopTaskStatus.COMPLETED ->
                    "CEO planning output was invalid, so Cotor used the deterministic fallback planner."
                else ->
                    "CEO planning run ended with ${finalStatus.name}; Cotor used the deterministic fallback planner."
            }
            val updatedPlanningIssue = currentIssue.copy(
                status = IssueStatus.DONE,
                transitionReason = planningReason,
                updatedAt = now
            )
            traceEvents += buildCompanyAutomationTraceEvent(
                issue = currentIssue,
                goal = goal,
                oldStatus = currentIssue.status,
                newStatus = updatedPlanningIssue.status,
                source = "syncPlanningIssueFromTask",
                reason = planningReason,
                latestTask = task,
                latestRun = primaryRun
            )
            val decision = GoalOrchestrationDecision(
                id = UUID.randomUUID().toString(),
                companyId = company.id,
                goalId = goal.id,
                issueId = currentIssue.id,
                title = if (planningSource == "ceo") "CEO planned execution graph" else "Fallback planned execution graph",
                summary = if (planningSource == "ceo") {
                    "CEO decomposed \"${goal.title}\" into ${decomposition.first.size} branchable issues."
                } else {
                    "Fallback planner decomposed \"${goal.title}\" into ${decomposition.first.size} branchable issues after CEO planning failed."
                },
                createdIssues = decomposition.first.map { it.id },
                assignments = decomposition.first.mapNotNull { createdIssue ->
                    val assignee = profiles.firstOrNull { it.id == createdIssue.assigneeProfileId }?.roleName
                    assignee?.let { "${createdIssue.title} -> $it" }
                },
                escalations = if (planningSource == "fallback") listOf(planningReason) else emptyList(),
                createdAt = now
            )
            val nextState = state.copy(
                issues = state.issues.filterNot { it.id == currentIssue.id } + updatedPlanningIssue + decomposition.first,
                issueDependencies = state.issueDependencies.filterNot { dependency ->
                    dependency.issueId == currentIssue.id
                } + decomposition.second,
                goalDecisions = state.goalDecisions + decision
            ).recordCompanyActivity(
                companyId = company.id,
                projectContextId = currentIssue.projectContextId,
                goalId = goal.id,
                issueId = currentIssue.id,
                source = "ceo-planning",
                title = if (planningSource == "ceo") "CEO planned goal" else "Fallback planned goal",
                detail = planningReason,
                severity = if (planningSource == "ceo") "info" else "warning"
            ).withDerivedMetrics()
            stateStore.save(nextState)
            val projectContext = nextState.projectContexts.firstOrNull { it.id == currentIssue.projectContextId }
            if (projectContext != null) {
                writeCompanyContextSnapshot(nextState, company, projectContext)
            }
            val runtime = nextState.companyRuntimes.firstOrNull { it.companyId == currentIssue.companyId }
            if (runtime?.status == CompanyRuntimeStatus.RUNNING) {
                runtimeContinuationCompanyId = currentIssue.companyId
            }
        }
        traceEvents.forEach(::appendCompanyAutomationTrace)
        publishCompanyEvent(
            companyId = issue.companyId,
            type = "goal.decomposed",
            title = "Created execution graph",
            detail = issue.title,
            goalId = issue.goalId,
            issueId = issue.id
        )
        runtimeContinuationCompanyId?.let { companyId ->
            serviceScope.launch {
                runCatching { runCompanyRuntimeTick(companyId) }
            }
        }
    }

    private suspend fun syncExecutionIssueFromTask(
        task: AgentTask,
        issue: CompanyIssue,
        primaryRun: AgentRun?,
        finalStatus: DesktopTaskStatus
    ) {
        val currentState = stateStore.load()
        val currentIssue = currentState.issues.firstOrNull { it.id == issue.id } ?: return
        extractGitHubPublishReadinessFailureReason(task, currentIssue, primaryRun, currentState)?.let { reason ->
            blockIssueForGitHubReadiness(currentIssue, reason)
            return
        }
        var runtimeContinuationCompanyId: String? = null
        val supersededPullRequestClosures = mutableListOf<Triple<String, Int, String>>()
        val now = System.currentTimeMillis()
        val traceEvents = mutableListOf<CompanyAutomationTraceEvent>()
        val informationalTraceEvents = mutableListOf<CompanyAutomationTraceEvent>()
        stateMutex.withLock {
            val state = stateStore.load()
            val currentIssue = state.issues.firstOrNull { it.id == issue.id } ?: return@withLock
            val retryDecision = resolveRecoverableRetryDecision(
                state.tasks.filter { it.issueId == currentIssue.id },
                state.runs.groupBy { it.taskId }.mapValues { (_, runs) -> runs.maxByOrNull { it.updatedAt }!! },
                now
            )
            val recoverableRetryPending =
                currentIssue.status in setOf(IssueStatus.PLANNED, IssueStatus.BACKLOG, IssueStatus.DELEGATED) &&
                    retryDecision.canAutoRetry
            if (recoverableRetryPending) {
                informationalTraceEvents += buildCompanyAutomationTraceEvent(
                    issue = currentIssue,
                    goal = state.goals.firstOrNull { it.id == currentIssue.goalId },
                    oldStatus = currentIssue.status,
                    newStatus = currentIssue.status,
                    source = "syncExecutionIssueFromTask",
                    reason = "Skipped execution issue synchronization because a recoverable retry is pending a new task run.",
                    latestTask = task,
                    latestRun = primaryRun,
                    retryEligible = true
                )
                return@withLock
            }
            val hasPublishMetadata = primaryRun?.publish?.pullRequestUrl != null || primaryRun?.publish?.pullRequestNumber != null
            val mergedReviewQueueItem = state.reviewQueue.firstOrNull {
                it.issueId == currentIssue.id && it.status == ReviewQueueStatus.MERGED
            }
            val mergedApprovalIssue = state.issues.firstOrNull {
                relatedExecutionIssueId(it) == currentIssue.id &&
                    it.kind.equals("approval", ignoreCase = true) &&
                    it.status == IssueStatus.DONE
            }
            val mergedPullRequestAlreadySettled =
                finalStatus == DesktopTaskStatus.COMPLETED &&
                    !hasPublishMetadata &&
                    (
                        currentIssue.mergeResult.equals("MERGED", ignoreCase = true) ||
                            currentIssue.pullRequestState.equals("MERGED", ignoreCase = true) ||
                            mergedReviewQueueItem != null ||
                            mergedApprovalIssue != null
                        )
            if (mergedPullRequestAlreadySettled) {
                informationalTraceEvents += buildCompanyAutomationTraceEvent(
                    issue = currentIssue,
                    goal = state.goals.firstOrNull { it.id == currentIssue.goalId },
                    oldStatus = currentIssue.status,
                    newStatus = currentIssue.status,
                    source = "syncExecutionIssueFromTask",
                    reason = "Skipped stale execution replay because the linked pull request is already merged.",
                    latestTask = task,
                    latestRun = primaryRun
                )
                return@withLock
            }
            val completedWithoutPublish = finalStatus == DesktopTaskStatus.COMPLETED && !requiresCodePublish(currentIssue)
            val publishAlreadyAdvanced =
                hasPublishMetadata &&
                    finalStatus == DesktopTaskStatus.COMPLETED &&
                    currentIssue.status in setOf(IssueStatus.IN_REVIEW, IssueStatus.READY_FOR_CEO, IssueStatus.DONE) &&
                    currentIssue.branchName == primaryRun?.branchName &&
                    currentIssue.pullRequestNumber == primaryRun?.publish?.pullRequestNumber &&
                    currentIssue.pullRequestUrl == primaryRun?.publish?.pullRequestUrl
            if (publishAlreadyAdvanced) {
                return@withLock
            }
            val noDiffExistingPullRequestReuse =
                finalStatus != DesktopTaskStatus.COMPLETED &&
                    isExistingPrNoDiffReuseCandidate(currentIssue, primaryRun)
            val pullRequestRequired = requiresGitHubPullRequest(currentIssue, state)
            val publishMergeConflict =
                finalStatus == DesktopTaskStatus.COMPLETED &&
                    pullRequestRequired &&
                    hasPublishMetadata &&
                    primaryRun?.publish?.mergeability.equals("DIRTY", ignoreCase = true)
            val nextIssueStatus = when {
                publishMergeConflict -> IssueStatus.PLANNED
                finalStatus == DesktopTaskStatus.COMPLETED && pullRequestRequired && hasPublishMetadata -> IssueStatus.IN_REVIEW
                finalStatus == DesktopTaskStatus.COMPLETED && !requiresCodePublish(currentIssue) -> IssueStatus.DONE
                finalStatus == DesktopTaskStatus.COMPLETED && !pullRequestRequired -> IssueStatus.DONE
                finalStatus == DesktopTaskStatus.COMPLETED -> IssueStatus.BLOCKED
                else -> IssueStatus.BLOCKED
            }
            if (nextIssueStatus == currentIssue.status && !hasPublishMetadata) {
                return@withLock
            }
            val updatedIssue = currentIssue.copy(
                status = nextIssueStatus,
                executionIntent = when {
                    publishMergeConflict -> ExecutionIntent.MERGE_CONFLICT_REMEDIATION
                    else -> currentIssue.executionIntent
                        ?: inferExecutionIntent(
                            kind = currentIssue.kind,
                            title = currentIssue.title,
                            description = currentIssue.description,
                            plannedCodeProducing = currentIssue.codeProducing
                        )
                },
                branchName = primaryRun?.branchName ?: currentIssue.branchName,
                worktreePath = primaryRun?.worktreePath ?: currentIssue.worktreePath,
                pullRequestNumber = if (completedWithoutPublish) null else primaryRun?.publish?.pullRequestNumber ?: currentIssue.pullRequestNumber,
                pullRequestUrl = if (completedWithoutPublish) null else primaryRun?.publish?.pullRequestUrl ?: currentIssue.pullRequestUrl,
                pullRequestState = if (completedWithoutPublish) null else primaryRun?.publish?.pullRequestState ?: currentIssue.pullRequestState,
                qaVerdict = if (hasPublishMetadata || completedWithoutPublish) null else currentIssue.qaVerdict,
                qaFeedback = if (hasPublishMetadata || completedWithoutPublish) null else currentIssue.qaFeedback,
                ceoVerdict = if (hasPublishMetadata || completedWithoutPublish) null else currentIssue.ceoVerdict,
                ceoFeedback = if (hasPublishMetadata || completedWithoutPublish) null else currentIssue.ceoFeedback,
                transitionReason = when {
                    publishMergeConflict ->
                        "Execution completed on branch ${primaryRun?.branchName}, but PR ${primaryRun?.publish?.pullRequestUrl ?: primaryRun?.publish?.pullRequestNumber} does not merge cleanly with the latest base branch. Re-running on a refreshed base."
                    finalStatus == DesktopTaskStatus.COMPLETED && pullRequestRequired && hasPublishMetadata ->
                        "Execution completed on branch ${primaryRun?.branchName} and opened PR ${primaryRun?.publish?.pullRequestUrl ?: primaryRun?.publish?.pullRequestNumber}."
                    finalStatus == DesktopTaskStatus.COMPLETED && !requiresCodePublish(currentIssue) ->
                        "Execution completed for non-code work."
                    finalStatus == DesktopTaskStatus.COMPLETED && !pullRequestRequired ->
                        "Execution completed with local git allowed by settings."
                    noDiffExistingPullRequestReuse ->
                        "Execution produced no new diff against ${currentIssue.pullRequestUrl ?: "the existing PR lineage"}; Cotor will refresh that PR state instead of opening another review cycle."
                    finalStatus == DesktopTaskStatus.COMPLETED ->
                        "Execution completed but required PR publication did not succeed."
                    else -> "Execution failed and requires a recoverable retry or remediation."
                },
                updatedAt = now
            )
            if (updatedIssue.status != currentIssue.status) {
                val reason = when {
                    publishMergeConflict -> "Task completed, but GitHub reported that the published PR no longer merges cleanly with the base branch."
                    finalStatus == DesktopTaskStatus.COMPLETED && pullRequestRequired && hasPublishMetadata -> "Task completed and published a pull request."
                    finalStatus == DesktopTaskStatus.COMPLETED && !requiresCodePublish(currentIssue) -> "Task completed without a required publish step."
                    finalStatus == DesktopTaskStatus.COMPLETED && !pullRequestRequired -> "Task completed and local git publishing is allowed by settings."
                    noDiffExistingPullRequestReuse -> "Task produced no new diff and should reuse the existing PR lineage instead of creating another review cycle."
                    finalStatus == DesktopTaskStatus.COMPLETED -> "Task completed but no pull request was created for required code work."
                    else -> "Task finished with ${finalStatus.name}; issue is blocked until a recoverable retry is detected."
                }
                traceEvents += buildCompanyAutomationTraceEvent(
                    issue = currentIssue,
                    goal = state.goals.firstOrNull { it.id == currentIssue.goalId },
                    oldStatus = currentIssue.status,
                    newStatus = updatedIssue.status,
                    source = "syncExecutionIssueFromTask",
                    reason = reason,
                    latestTask = task,
                    latestRun = primaryRun
                )
            }
            val queueStatus = when {
                publishMergeConflict -> ReviewQueueStatus.CHANGES_REQUESTED
                primaryRun?.publish?.error != null -> ReviewQueueStatus.FAILED_CHECKS
                hasPublishMetadata -> ReviewQueueStatus.AWAITING_QA
                else -> ReviewQueueStatus.AWAITING_QA
            }
            val existingReviewQueueItem = state.reviewQueue
                .lastOrNull { it.issueId == currentIssue.id && it.status != ReviewQueueStatus.MERGED }
            val existingQaIssue = existingReviewQueueItem?.qaIssueId?.let { qaIssueId ->
                state.issues.firstOrNull { it.id == qaIssueId }
            } ?: state.issues.firstOrNull {
                relatedExecutionIssueId(it) == currentIssue.id && it.kind.equals("review", ignoreCase = true)
            }
            val existingQaTask = existingQaIssue?.let { latestTaskForIssue(state, it.id) }
            val existingQaRun = existingQaTask?.let { latestRunForTask(state, it.id) }
            val existingQueueLineage = existingReviewQueueItem?.let { resolvedWorkflowLineageForQueueItem(state, it) }
            val executionRunAdvanced =
                pullRequestRequired &&
                    hasPublishMetadata &&
                    existingReviewQueueItem != null &&
                    !reviewQueueReflectsExecutionRun(existingReviewQueueItem, task, primaryRun)
            val downstreamQaLineageMismatch =
                pullRequestRequired &&
                    hasPublishMetadata &&
                    existingQaIssue != null &&
                    existingQueueLineage != null &&
                    (
                        (existingQaIssue.workflowLineage != null && !workflowLineagesMatch(existingQueueLineage, existingQaIssue.workflowLineage)) ||
                            (existingQaTask?.workflowLineage != null && !workflowLineagesMatch(existingQueueLineage, existingQaTask.workflowLineage)) ||
                            (existingQaRun?.workflowLineage != null && !workflowLineagesMatch(existingQueueLineage, existingQaRun.workflowLineage))
                        )
            val publishedReviewIdentityChanged =
                pullRequestRequired &&
                    hasPublishMetadata &&
                    (
                        existingReviewQueueItem == null ||
                            executionRunAdvanced ||
                            downstreamQaLineageMismatch
                        )
            val clearWorkflowBindings = publishedReviewIdentityChanged || publishMergeConflict
            val reviewQueueItemId = if (publishedReviewIdentityChanged || existingReviewQueueItem == null) {
                UUID.randomUUID().toString()
            } else {
                existingReviewQueueItem.id
            }
            val workflowLineage = if (pullRequestRequired && hasPublishMetadata && primaryRun != null) {
                if (publishedReviewIdentityChanged || existingQueueLineage == null) {
                    buildWorkflowLineageSnapshot(
                        reviewQueueItemId = reviewQueueItemId,
                        executionIssueId = currentIssue.id,
                        executionTaskId = task.id,
                        executionRun = primaryRun,
                        generation = nextWorkflowLineageGeneration(state, currentIssue.id)
                    )
                } else {
                    buildWorkflowLineageSnapshot(
                        reviewQueueItemId = reviewQueueItemId,
                        executionIssueId = currentIssue.id,
                        executionTaskId = task.id,
                        executionRun = primaryRun,
                        generation = existingQueueLineage.generation,
                        lineageId = existingQueueLineage.lineageId
                    )
                }
            } else {
                null
            }
            val nextReviewQueue = if (pullRequestRequired && hasPublishMetadata) {
                val existing = existingReviewQueueItem
                val queueItem = ReviewQueueItem(
                    id = reviewQueueItemId,
                    companyId = currentIssue.companyId,
                    projectContextId = currentIssue.projectContextId,
                    issueId = currentIssue.id,
                    runId = primaryRun?.id ?: "",
                    branchName = primaryRun?.branchName,
                    worktreePath = primaryRun?.worktreePath,
                    pullRequestNumber = primaryRun?.publish?.pullRequestNumber,
                    pullRequestUrl = primaryRun?.publish?.pullRequestUrl,
                    pullRequestState = primaryRun?.publish?.pullRequestState,
                    status = queueStatus,
                    checksSummary = primaryRun?.publish?.checksSummary ?: primaryRun?.error,
                    mergeability = primaryRun?.publish?.mergeability,
                    requestedReviewers = primaryRun?.publish?.requestedReviewers ?: emptyList(),
                    qaVerdict = null,
                    qaFeedback = null,
                    qaReviewedAt = null,
                    qaIssueId = if (clearWorkflowBindings) null else existing?.qaIssueId,
                    ceoVerdict = null,
                    ceoFeedback = null,
                    ceoReviewedAt = null,
                    approvalIssueId = if (clearWorkflowBindings) null else existing?.approvalIssueId,
                    createdAt = if (publishedReviewIdentityChanged) now else existing?.createdAt ?: now,
                    updatedAt = now,
                    workflowLineage = workflowLineage
                )
                state.reviewQueue.filterNot { it.id == queueItem.id || it.issueId == currentIssue.id } + queueItem
            } else {
                state.reviewQueue
            }
            if (executionRunAdvanced) {
                val stalePullRequestNumber = existingReviewQueueItem?.pullRequestNumber
                val worktreeForClose = primaryRun?.worktreePath ?: currentIssue.worktreePath
                if (
                    stalePullRequestNumber != null &&
                    worktreeForClose != null &&
                    stalePullRequestNumber != primaryRun?.publish?.pullRequestNumber &&
                    !existingReviewQueueItem?.pullRequestState.equals("MERGED", ignoreCase = true) &&
                    !existingReviewQueueItem?.pullRequestState.equals("CLOSED", ignoreCase = true)
                ) {
                    val replacementRef =
                        primaryRun?.publish?.pullRequestUrl
                            ?: primaryRun?.publish?.pullRequestNumber?.let { "#$it" }
                            ?: primaryRun?.branchName
                            ?: "the latest retry branch"
                    supersededPullRequestClosures += Triple(
                        worktreeForClose,
                        stalePullRequestNumber,
                        "Superseded by newer retry $replacementRef. Closing this outdated PR to keep the review queue aligned with the latest execution branch."
                    )
                }
            }
            val companyProfiles = ensureOrgProfiles(state.orgProfiles, state.companyAgentDefinitions, state.companies)
                .filter { it.companyId == currentIssue.companyId }
            val qaProfile = findQaProfile(currentIssue.companyId, companyProfiles)
            val reusableQaIssue = if (clearWorkflowBindings) {
                null
            } else {
                existingQaIssue
            }
            val staleWorkflowIssueIds = if (clearWorkflowBindings) {
                state.issues
                    .filter {
                        relatedExecutionIssueId(it) == currentIssue.id &&
                            (
                                it.kind.equals("review", ignoreCase = true) ||
                                    it.kind.equals("approval", ignoreCase = true)
                                )
                    }
                    .map { it.id }
                    .toSet()
            } else {
                buildSet {
                    reusableQaIssue?.id?.let(::add)
                }
            }
            val qaIssue = if (
                pullRequestRequired &&
                hasPublishMetadata &&
                !publishMergeConflict &&
                queueStatus == ReviewQueueStatus.AWAITING_QA &&
                qaProfile != null
            ) {
                reusableQaIssue?.copy(
                    title = "QA review ${currentIssue.title}",
                    description = buildQaReviewIssueDescription(updatedIssue, nextReviewQueue.first { it.issueId == currentIssue.id }),
                    status = IssueStatus.PLANNED,
                    assigneeProfileId = qaProfile.id,
                    dependsOn = listOf(currentIssue.id),
                    branchName = updatedIssue.branchName,
                    worktreePath = updatedIssue.worktreePath,
                    pullRequestNumber = updatedIssue.pullRequestNumber,
                    pullRequestUrl = updatedIssue.pullRequestUrl,
                    pullRequestState = updatedIssue.pullRequestState,
                    qaVerdict = null,
                    qaFeedback = null,
                    ceoVerdict = null,
                    ceoFeedback = null,
                    transitionReason = "Execution PR is ready for QA review.",
                    updatedAt = now,
                    workflowLineage = workflowLineage
                ) ?: CompanyIssue(
                    id = UUID.randomUUID().toString(),
                    companyId = currentIssue.companyId,
                    projectContextId = currentIssue.projectContextId,
                    goalId = currentIssue.goalId,
                    workspaceId = currentIssue.workspaceId,
                    title = "QA review ${currentIssue.title}",
                    description = buildQaReviewIssueDescription(updatedIssue, nextReviewQueue.first { it.issueId == currentIssue.id }),
                    status = IssueStatus.PLANNED,
                    priority = 2,
                    kind = "review",
                    assigneeProfileId = qaProfile.id,
                    blockedBy = emptyList(),
                    dependsOn = listOf(currentIssue.id),
                    acceptanceCriteria = listOf(
                        "Review the current PR and its changed files.",
                        "Return QA_VERDICT with concrete acceptance or requested changes."
                    ),
                    riskLevel = "low",
                    codeProducing = false,
                    branchName = updatedIssue.branchName,
                    worktreePath = updatedIssue.worktreePath,
                    pullRequestNumber = updatedIssue.pullRequestNumber,
                    pullRequestUrl = updatedIssue.pullRequestUrl,
                    pullRequestState = updatedIssue.pullRequestState,
                    transitionReason = "Execution PR is ready for QA review.",
                    sourceSignal = qaReviewSource(currentIssue.id),
                    createdAt = now,
                    updatedAt = now,
                    workflowLineage = workflowLineage
                )
            } else {
                reusableQaIssue
            }
            val queueForCurrentIssue = nextReviewQueue.firstOrNull { it.issueId == currentIssue.id }
            val queueWithQaBinding = if (qaIssue != null && queueForCurrentIssue != null) {
                nextReviewQueue.map { item ->
                    if (item.id == queueForCurrentIssue.id) {
                        item.copy(
                            qaIssueId = qaIssue.id,
                            workflowLineage = workflowLineage ?: item.workflowLineage,
                            updatedAt = now
                        )
                    } else {
                        item
                    }
                }
            } else {
                nextReviewQueue
            }
            val nextGoals = state.goals.map { goal ->
                if (goal.id != currentIssue.goalId) {
                    goal
                } else {
                    val unresolved = (
                        state.issues
                            .filterNot { existing -> existing.id == currentIssue.id || existing.id == existingQaIssue?.id } +
                            updatedIssue +
                            listOfNotNull(qaIssue)
                        )
                        .any { it.goalId == goal.id && it.status != IssueStatus.DONE && it.status != IssueStatus.CANCELED }
                    if (!unresolved) goal.copy(status = GoalStatus.COMPLETED, updatedAt = now) else goal
                }
            }
            val nextState = state.copy(
                issues = state.issues
                    .filterNot { existing -> existing.id == currentIssue.id || existing.id in staleWorkflowIssueIds }
                    .plus(updatedIssue)
                    .plus(listOfNotNull(qaIssue)),
                reviewQueue = queueWithQaBinding,
                goals = nextGoals
            ).recordCompanyActivity(
                companyId = updatedIssue.companyId,
                projectContextId = updatedIssue.projectContextId,
                goalId = updatedIssue.goalId,
                issueId = updatedIssue.id,
                source = "task-run",
                title = "Updated issue state",
                detail = buildString {
                    append("${updatedIssue.title} -> ${updatedIssue.status}")
                    traceEvents.firstOrNull()?.reason?.let { append(" ($it)") }
                }
            ).recordCompanyActivity(
                companyId = updatedIssue.companyId,
                projectContextId = updatedIssue.projectContextId,
                goalId = updatedIssue.goalId,
                issueId = qaIssue?.id ?: existingQaIssue?.id,
                source = "qa-review",
                title = if (publishMergeConflict) {
                    "Skipped QA for dirty pull request"
                } else if (qaIssue == null) {
                    "Waiting for QA"
                } else if (existingQaIssue == null) {
                    "Created QA review issue"
                } else {
                    "Reopened QA review issue"
                },
                detail = updatedIssue.pullRequestUrl ?: updatedIssue.title,
                severity = if (publishMergeConflict) "warning" else "info"
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
        traceEvents.forEach(::appendCompanyAutomationTrace)
        informationalTraceEvents.forEach(::appendCompanyAutomationTrace)
        supersededPullRequestClosures.forEach { (worktreePath, pullRequestNumber, comment) ->
            runCatching {
                gitWorkspaceService.closePullRequest(
                    worktreePath = Path.of(worktreePath),
                    pullRequestNumber = pullRequestNumber,
                    comment = comment
                )
            }.onFailure { error ->
                if (error is CancellationException) {
                    throw error
                }
            }
        }
        val syncComment = buildString {
            append("Cotor finished task \"${task.title}\" with ${finalStatus.name}.")
            primaryRun?.publish?.pullRequestUrl?.let { append("\nPR: $it") }
            primaryRun?.error?.takeIf { it.isNotBlank() }?.let { append("\nError: $it") }
        }
        mirrorIssueToLinear(issue.id, syncComment)
        runtimeContinuationCompanyId?.let { companyId ->
            serviceScope.launch {
                runCatching { runCompanyRuntimeTick(companyId) }
            }
        }
    }

    private suspend fun syncReviewIssueFromTask(
        task: AgentTask,
        issue: CompanyIssue,
        primaryRun: AgentRun?,
        finalStatus: DesktopTaskStatus
    ) {
        var runtimeContinuationCompanyId: String? = null
        val queueReviews = mutableListOf<Pair<ReviewQueueItem, PullRequestReviewVerdict>>()
        val now = System.currentTimeMillis()
        val verdict = parseStructuredVerdict(primaryRun?.output, "QA_VERDICT", "PASS", "CHANGES_REQUESTED")
            ?: StructuredVerdict("PASS", summarizeForPrompt(primaryRun?.output.orEmpty(), 240))
        val traceEvents = mutableListOf<CompanyAutomationTraceEvent>()
        val informationalTraceEvents = mutableListOf<CompanyAutomationTraceEvent>()
        stateMutex.withLock {
            val state = stateStore.load()
            val currentIssue = state.issues.firstOrNull { it.id == issue.id } ?: return@withLock
            val executionIssueId = relatedExecutionIssueId(currentIssue) ?: return@withLock
            val executionIssue = state.issues.firstOrNull { it.id == executionIssueId } ?: return@withLock
            val targetQueueItem = state.reviewQueue
                .firstOrNull {
                    it.issueId == executionIssueId &&
                        it.qaIssueId == currentIssue.id &&
                        it.status != ReviewQueueStatus.MERGED
                }
                ?: return@withLock
            var expectedLineage = resolvedWorkflowLineageForQueueItem(state, targetQueueItem)
            if (
                expectedLineage == null &&
                    currentIssue.workflowLineage == null &&
                    task.workflowLineage == null &&
                    primaryRun?.workflowLineage == null &&
                    currentIssue.updatedAt <= task.updatedAt
            ) {
                expectedLineage = synthesizeLegacyWorkflowLineageForCurrentTask(targetQueueItem, task, primaryRun)
            }
            val legacyLineageAdopted =
                expectedLineage != null && canAdoptExpectedWorkflowLineage(currentIssue, task, primaryRun, expectedLineage)
            if (
                expectedLineage == null ||
                    (
                        !legacyLineageAdopted &&
                            (
                                !workflowLineagesMatch(expectedLineage, currentIssue.workflowLineage) ||
                                    !workflowLineagesMatch(expectedLineage, task.workflowLineage) ||
                                    (primaryRun != null && !workflowLineagesMatch(expectedLineage, primaryRun.workflowLineage))
                                )
                        )
            ) {
                informationalTraceEvents += buildCompanyAutomationTraceEvent(
                    issue = currentIssue,
                    goal = state.goals.firstOrNull { it.id == currentIssue.goalId },
                    oldStatus = currentIssue.status,
                    newStatus = currentIssue.status,
                    source = "syncReviewIssueFromTask",
                    reason = "Ignored a stale QA task result because its workflow lineage no longer matches the current review queue item.",
                    latestTask = task,
                    latestRun = primaryRun
                )
                return@withLock
            }
            val effectiveIssue = if (!workflowLineagesMatch(expectedLineage, currentIssue.workflowLineage)) {
                currentIssue.copy(workflowLineage = expectedLineage, updatedAt = currentIssue.updatedAt)
            } else {
                currentIssue
            }
            val effectiveTask = if (!workflowLineagesMatch(expectedLineage, task.workflowLineage)) {
                task.copy(workflowLineage = expectedLineage, updatedAt = task.updatedAt)
            } else {
                task
            }
            val effectiveRun = primaryRun?.let { run ->
                if (!workflowLineagesMatch(expectedLineage, run.workflowLineage)) {
                    run.copy(workflowLineage = expectedLineage, updatedAt = run.updatedAt)
                } else {
                    run
                }
            }
            val reviewPassed = finalStatus == DesktopTaskStatus.COMPLETED && verdict.value == "PASS"
            val companyProfiles = ensureOrgProfiles(state.orgProfiles, state.companyAgentDefinitions, state.companies)
                .filter { it.companyId == currentIssue.companyId }
            val chiefProfile = findChiefProfile(currentIssue.companyId, companyProfiles)
            val existingApprovalIssue = targetQueueItem.approvalIssueId?.let { approvalIssueId ->
                state.issues.firstOrNull { it.id == approvalIssueId }
            }
            if (
                reviewTaskAlreadyApplied(
                    reviewIssue = effectiveIssue,
                    executionIssue = executionIssue,
                    approvalIssue = existingApprovalIssue,
                    queueItem = targetQueueItem,
                    verdict = verdict,
                    reviewPassed = reviewPassed
                )
            ) {
                informationalTraceEvents += buildCompanyAutomationTraceEvent(
                    issue = currentIssue,
                    goal = state.goals.firstOrNull { it.id == currentIssue.goalId },
                    oldStatus = currentIssue.status,
                    newStatus = currentIssue.status,
                    source = "syncReviewIssueFromTask",
                    reason = "Skipped replaying QA sync because the current verdict is already reflected in the review queue and downstream issues.",
                    latestTask = task,
                    latestRun = primaryRun
                )
                return@withLock
            }
            val updatedReviewIssue = currentIssue.copy(
                status = if (reviewPassed) IssueStatus.DONE else IssueStatus.BLOCKED,
                qaVerdict = verdict.value,
                qaFeedback = verdict.feedback,
                transitionReason = if (reviewPassed) {
                    "QA approved ${targetQueueItem.pullRequestUrl ?: executionIssue.title} and handed it to the CEO."
                } else {
                    "QA requested changes on ${targetQueueItem.pullRequestUrl ?: executionIssue.title}."
                },
                updatedAt = now,
                workflowLineage = expectedLineage
            )
            val updatedExecutionIssue = executionIssue.copy(
                status = if (reviewPassed) IssueStatus.READY_FOR_CEO else IssueStatus.PLANNED,
                qaVerdict = verdict.value,
                qaFeedback = verdict.feedback,
                ceoVerdict = null,
                ceoFeedback = null,
                transitionReason = if (reviewPassed) {
                    "QA approved PR ${targetQueueItem.pullRequestUrl ?: targetQueueItem.pullRequestNumber} and escalated it to the CEO."
                } else {
                    "QA requested changes on PR ${targetQueueItem.pullRequestUrl ?: targetQueueItem.pullRequestNumber}."
                },
                updatedAt = now
            )
            val approvalIssue = if (reviewPassed && chiefProfile != null) {
                existingApprovalIssue?.copy(
                    title = "CEO approve ${executionIssue.title}",
                    description = buildCeoApprovalIssueDescription(updatedExecutionIssue, targetQueueItem),
                    status = IssueStatus.PLANNED,
                    assigneeProfileId = chiefProfile.id,
                    dependsOn = listOf(currentIssue.id),
                    branchName = updatedExecutionIssue.branchName,
                    worktreePath = updatedExecutionIssue.worktreePath,
                    pullRequestNumber = updatedExecutionIssue.pullRequestNumber,
                    pullRequestUrl = updatedExecutionIssue.pullRequestUrl,
                    pullRequestState = updatedExecutionIssue.pullRequestState,
                    qaVerdict = verdict.value,
                    qaFeedback = verdict.feedback,
                    ceoVerdict = null,
                    ceoFeedback = null,
                    transitionReason = "QA approved the current PR and reopened CEO approval.",
                    updatedAt = now,
                    workflowLineage = expectedLineage
                ) ?: CompanyIssue(
                    id = UUID.randomUUID().toString(),
                    companyId = currentIssue.companyId,
                    projectContextId = currentIssue.projectContextId,
                    goalId = currentIssue.goalId,
                    workspaceId = currentIssue.workspaceId,
                    title = "CEO approve ${executionIssue.title}",
                    description = buildCeoApprovalIssueDescription(updatedExecutionIssue, targetQueueItem),
                    status = IssueStatus.PLANNED,
                    priority = 3,
                    kind = "approval",
                    assigneeProfileId = chiefProfile.id,
                    blockedBy = emptyList(),
                    dependsOn = listOf(currentIssue.id),
                    acceptanceCriteria = listOf(
                        "Review the current PR and QA verdict.",
                        "Emit CEO_VERDICT and approve merge only when the branch is ready."
                    ),
                    riskLevel = "medium",
                    codeProducing = false,
                    branchName = updatedExecutionIssue.branchName,
                    worktreePath = updatedExecutionIssue.worktreePath,
                    pullRequestNumber = updatedExecutionIssue.pullRequestNumber,
                    pullRequestUrl = updatedExecutionIssue.pullRequestUrl,
                    pullRequestState = updatedExecutionIssue.pullRequestState,
                    qaVerdict = verdict.value,
                    qaFeedback = verdict.feedback,
                    transitionReason = "QA approved the current PR and opened CEO approval.",
                    sourceSignal = ceoApprovalSource(executionIssueId),
                    createdAt = now,
                    updatedAt = now,
                    workflowLineage = expectedLineage
                )
            } else {
                existingApprovalIssue?.copy(
                    status = if (existingApprovalIssue.status == IssueStatus.DONE) IssueStatus.DONE else IssueStatus.BLOCKED,
                    updatedAt = now,
                    workflowLineage = expectedLineage
                )
            }
            val updatedReviewQueue = state.reviewQueue.map { item ->
                if (item.id != targetQueueItem.id) {
                    item
                } else {
                    val nextItem = item.copy(
                        status = if (reviewPassed) ReviewQueueStatus.READY_FOR_CEO else ReviewQueueStatus.CHANGES_REQUESTED,
                        qaVerdict = verdict.value,
                        qaFeedback = verdict.feedback,
                        qaReviewedAt = now,
                        qaIssueId = currentIssue.id,
                        ceoVerdict = null,
                        ceoFeedback = null,
                        ceoReviewedAt = null,
                        approvalIssueId = approvalIssue?.id,
                        updatedAt = now,
                        workflowLineage = expectedLineage
                    )
                    queueReviews += nextItem to if (reviewPassed) PullRequestReviewVerdict.APPROVE else PullRequestReviewVerdict.REQUEST_CHANGES
                    nextItem
                }
            }
            traceEvents += buildCompanyAutomationTraceEvent(
                issue = currentIssue,
                goal = state.goals.firstOrNull { it.id == currentIssue.goalId },
                oldStatus = currentIssue.status,
                newStatus = updatedReviewIssue.status,
                source = "syncReviewIssueFromTask",
                reason = if (reviewPassed) {
                    "QA approved the published pull request and handed it to the CEO."
                } else {
                    "QA requested changes and sent remediation back to the execution branch."
                },
                latestTask = task,
                latestRun = primaryRun
            )
            if (
                reviewPassed &&
                existingApprovalIssue != null &&
                existingApprovalIssue.status != IssueStatus.DONE &&
                existingApprovalIssue.status != IssueStatus.CANCELED &&
                existingApprovalIssue.status != IssueStatus.PLANNED
            ) {
                traceEvents += buildCompanyAutomationTraceEvent(
                    issue = existingApprovalIssue,
                    goal = state.goals.firstOrNull { it.id == existingApprovalIssue.goalId },
                    oldStatus = existingApprovalIssue.status,
                    newStatus = IssueStatus.PLANNED,
                    source = "syncReviewIssueFromTask",
                    reason = "QA approval reopened the CEO approval issue after earlier changes were addressed.",
                    latestTask = task,
                    latestRun = primaryRun
                )
            }
            val nextState = state.copy(
                issues = state.issues
                    .filterNot { existing ->
                        existing.id == currentIssue.id ||
                            existing.id == executionIssue.id ||
                            existing.id == existingApprovalIssue?.id
                    }
                    .plus(updatedReviewIssue)
                    .plus(updatedExecutionIssue)
                    .plus(listOfNotNull(approvalIssue)),
                tasks = state.tasks.map { existing ->
                    if (existing.id == effectiveTask.id) effectiveTask else existing
                },
                runs = effectiveRun?.let { run ->
                    state.runs.map { existing -> if (existing.id == run.id) run else existing }
                } ?: state.runs,
                reviewQueue = updatedReviewQueue
            ).recordCompanyActivity(
                companyId = currentIssue.companyId,
                projectContextId = currentIssue.projectContextId,
                goalId = currentIssue.goalId,
                issueId = currentIssue.id,
                source = "qa-review",
                title = if (reviewPassed) "QA approved pull requests" else "QA requested changes",
                detail = verdict.feedback.ifBlank { currentIssue.title },
                severity = if (reviewPassed) "info" else "warning"
            ).withDerivedMetrics()
            stateStore.save(nextState)
            val runtime = nextState.companyRuntimes.firstOrNull { it.companyId == currentIssue.companyId }
            if (runtime?.status == CompanyRuntimeStatus.RUNNING) {
                runtimeContinuationCompanyId = currentIssue.companyId
            }
        }
        traceEvents.forEach(::appendCompanyAutomationTrace)
        informationalTraceEvents.forEach(::appendCompanyAutomationTrace)
        queueReviews.forEach { (item, reviewVerdict) ->
            val prNumber = item.pullRequestNumber ?: return@forEach
            val worktreePath = item.worktreePath ?: return@forEach
            val reviewBody = buildPullRequestFeedbackBody(
                actorLabel = "QA",
                verdictKey = "QA_VERDICT",
                verdict = item.qaVerdict ?: verdict.value,
                feedback = item.qaFeedback
            )
            runCatching {
                gitWorkspaceService.commentOnPullRequest(
                    worktreePath = Path.of(worktreePath),
                    pullRequestNumber = prNumber,
                    body = reviewBody
                )
                gitWorkspaceService.submitPullRequestReview(
                    worktreePath = Path.of(worktreePath),
                    pullRequestNumber = prNumber,
                    verdict = reviewVerdict,
                    body = reviewBody
                )
            }
        }
        mirrorIssueToLinear(
            issue.id,
            "QA completed review for \"${issue.title}\" with verdict ${verdict.value}."
        )
        runtimeContinuationCompanyId?.let { companyId ->
            serviceScope.launch {
                runCatching { runCompanyRuntimeTick(companyId) }
            }
        }
    }

    private suspend fun syncApprovalIssueFromTask(
        task: AgentTask,
        issue: CompanyIssue,
        primaryRun: AgentRun?,
        finalStatus: DesktopTaskStatus
    ) {
        var runtimeContinuationCompanyId: String? = null
        val mergeQueueIds = mutableListOf<String>()
        val ceoReviews = mutableListOf<Pair<ReviewQueueItem, PullRequestReviewVerdict>>()
        val now = System.currentTimeMillis()
        val verdict = parseStructuredVerdict(primaryRun?.output, "CEO_VERDICT", "APPROVE", "CHANGES_REQUESTED")
            ?: StructuredVerdict("APPROVE", summarizeForPrompt(primaryRun?.output.orEmpty(), 240))
        val approvalGranted = finalStatus == DesktopTaskStatus.COMPLETED && verdict.value == "APPROVE"
        val traceEvents = mutableListOf<CompanyAutomationTraceEvent>()
        val informationalTraceEvents = mutableListOf<CompanyAutomationTraceEvent>()
        stateMutex.withLock {
            val state = stateStore.load()
            val currentIssue = state.issues.firstOrNull { it.id == issue.id } ?: return@withLock
            val executionIssueId = relatedExecutionIssueId(currentIssue) ?: return@withLock
            val executionIssue = state.issues.firstOrNull { it.id == executionIssueId } ?: return@withLock
            val targetQueueItem = state.reviewQueue
                .firstOrNull {
                    it.issueId == executionIssueId &&
                        it.approvalIssueId == currentIssue.id &&
                        it.status == ReviewQueueStatus.READY_FOR_CEO
                }
                ?: return@withLock
            var expectedLineage = resolvedWorkflowLineageForQueueItem(state, targetQueueItem)
            if (
                expectedLineage == null &&
                    currentIssue.workflowLineage == null &&
                    task.workflowLineage == null &&
                    primaryRun?.workflowLineage == null &&
                    currentIssue.updatedAt <= task.updatedAt
            ) {
                expectedLineage = synthesizeLegacyWorkflowLineageForCurrentTask(targetQueueItem, task, primaryRun)
            }
            val legacyLineageAdopted =
                expectedLineage != null && canAdoptExpectedWorkflowLineage(currentIssue, task, primaryRun, expectedLineage)
            if (
                expectedLineage == null ||
                    (
                        !legacyLineageAdopted &&
                            (
                                !workflowLineagesMatch(expectedLineage, currentIssue.workflowLineage) ||
                                    !workflowLineagesMatch(expectedLineage, task.workflowLineage) ||
                                    (primaryRun != null && !workflowLineagesMatch(expectedLineage, primaryRun.workflowLineage))
                                )
                        )
            ) {
                informationalTraceEvents += buildCompanyAutomationTraceEvent(
                    issue = currentIssue,
                    goal = state.goals.firstOrNull { it.id == currentIssue.goalId },
                    oldStatus = currentIssue.status,
                    newStatus = currentIssue.status,
                    source = "syncApprovalIssueFromTask",
                    reason = "Ignored a stale CEO task result because its workflow lineage no longer matches the current approval queue item.",
                    latestTask = task,
                    latestRun = primaryRun
                )
                return@withLock
            }
            val effectiveIssue = if (!workflowLineagesMatch(expectedLineage, currentIssue.workflowLineage)) {
                currentIssue.copy(workflowLineage = expectedLineage, updatedAt = currentIssue.updatedAt)
            } else {
                currentIssue
            }
            val effectiveTask = if (!workflowLineagesMatch(expectedLineage, task.workflowLineage)) {
                task.copy(workflowLineage = expectedLineage, updatedAt = task.updatedAt)
            } else {
                task
            }
            val effectiveRun = primaryRun?.let { run ->
                if (!workflowLineagesMatch(expectedLineage, run.workflowLineage)) {
                    run.copy(workflowLineage = expectedLineage, updatedAt = run.updatedAt)
                } else {
                    run
                }
            }
            if (
                approvalTaskAlreadyApplied(
                    approvalIssue = effectiveIssue,
                    executionIssue = executionIssue,
                    queueItem = targetQueueItem,
                    verdict = verdict,
                    approvalGranted = approvalGranted
                )
            ) {
                informationalTraceEvents += buildCompanyAutomationTraceEvent(
                    issue = currentIssue,
                    goal = state.goals.firstOrNull { it.id == currentIssue.goalId },
                    oldStatus = currentIssue.status,
                    newStatus = currentIssue.status,
                    source = "syncApprovalIssueFromTask",
                    reason = "Skipped replaying CEO sync because the current verdict is already reflected in the review queue and approval lane.",
                    latestTask = task,
                    latestRun = primaryRun
                )
                return@withLock
            }
            val updatedApprovalIssue = currentIssue.copy(
                status = if (approvalGranted) IssueStatus.IN_PROGRESS else IssueStatus.BLOCKED,
                ceoVerdict = verdict.value,
                ceoFeedback = verdict.feedback,
                transitionReason = if (approvalGranted) {
                    "CEO approved ${targetQueueItem.pullRequestUrl ?: executionIssue.title} and queued it for merge."
                } else {
                    "CEO requested changes on ${targetQueueItem.pullRequestUrl ?: executionIssue.title}."
                },
                updatedAt = now,
                workflowLineage = expectedLineage
            )
            val updatedExecutionIssue = executionIssue.copy(
                status = if (approvalGranted) IssueStatus.READY_FOR_CEO else IssueStatus.PLANNED,
                ceoVerdict = verdict.value,
                ceoFeedback = verdict.feedback,
                transitionReason = if (approvalGranted) {
                    "CEO approved PR ${targetQueueItem.pullRequestUrl ?: targetQueueItem.pullRequestNumber} and queued merge."
                } else {
                    "CEO requested changes on PR ${targetQueueItem.pullRequestUrl ?: targetQueueItem.pullRequestNumber}."
                },
                updatedAt = now
            )
            val updatedReviewQueue = state.reviewQueue.map { item ->
                if (item.id != targetQueueItem.id) {
                    item
                } else {
                    val nextStatus = if (approvalGranted) ReviewQueueStatus.READY_FOR_CEO else ReviewQueueStatus.CHANGES_REQUESTED
                    val nextItem = item.copy(
                        status = nextStatus,
                        ceoVerdict = verdict.value,
                        ceoFeedback = verdict.feedback,
                        ceoReviewedAt = now,
                        approvalIssueId = currentIssue.id,
                        updatedAt = now,
                        workflowLineage = expectedLineage
                    )
                    if (approvalGranted) {
                        mergeQueueIds += item.id
                    } else {
                        ceoReviews += nextItem to PullRequestReviewVerdict.REQUEST_CHANGES
                    }
                    nextItem
                }
            }
            traceEvents += buildCompanyAutomationTraceEvent(
                issue = currentIssue,
                goal = state.goals.firstOrNull { it.id == currentIssue.goalId },
                oldStatus = currentIssue.status,
                newStatus = updatedApprovalIssue.status,
                source = "syncApprovalIssueFromTask",
                reason = if (approvalGranted) {
                    "CEO approved the reviewed pull request and queued it for merge."
                } else {
                    "CEO requested changes and returned the branch to execution."
                },
                latestTask = task,
                latestRun = primaryRun
            )
            val nextState = state.copy(
                issues = state.issues.map { existing ->
                    when (existing.id) {
                        currentIssue.id -> updatedApprovalIssue
                        executionIssue.id -> updatedExecutionIssue
                        else -> existing
                    }
                },
                tasks = state.tasks.map { existing ->
                    if (existing.id == effectiveTask.id) effectiveTask else existing
                },
                runs = effectiveRun?.let { run ->
                    state.runs.map { existing -> if (existing.id == run.id) run else existing }
                } ?: state.runs,
                reviewQueue = updatedReviewQueue
            ).recordCompanyActivity(
                companyId = currentIssue.companyId,
                projectContextId = currentIssue.projectContextId,
                goalId = currentIssue.goalId,
                issueId = currentIssue.id,
                source = "ceo-approval",
                title = if (approvalGranted) "CEO approved pull requests" else "CEO requested changes",
                detail = verdict.feedback.ifBlank { currentIssue.title },
                severity = if (approvalGranted) "info" else "warning"
            ).withDerivedMetrics()
            stateStore.save(nextState)
            val runtime = nextState.companyRuntimes.firstOrNull { it.companyId == currentIssue.companyId }
            if (runtime?.status == CompanyRuntimeStatus.RUNNING) {
                runtimeContinuationCompanyId = currentIssue.companyId
            }
        }
        traceEvents.forEach(::appendCompanyAutomationTrace)
        informationalTraceEvents.forEach(::appendCompanyAutomationTrace)
        ceoReviews.forEach { (item, reviewVerdict) ->
            val prNumber = item.pullRequestNumber ?: return@forEach
            val worktreePath = item.worktreePath ?: return@forEach
            val reviewBody = buildPullRequestFeedbackBody(
                actorLabel = "CEO",
                verdictKey = "CEO_VERDICT",
                verdict = item.ceoVerdict ?: verdict.value,
                feedback = item.ceoFeedback
            )
            runCatching {
                gitWorkspaceService.commentOnPullRequest(
                    worktreePath = Path.of(worktreePath),
                    pullRequestNumber = prNumber,
                    body = reviewBody
                )
                gitWorkspaceService.submitPullRequestReview(
                    worktreePath = Path.of(worktreePath),
                    pullRequestNumber = prNumber,
                    verdict = reviewVerdict,
                    body = reviewBody
                )
            }
        }
        if (approvalGranted) {
            mergeQueueIds.distinct().forEach { mergeQueueId ->
                val mergedItem = mergeReviewQueueItem(mergeQueueId)
                if (mergedItem.status == ReviewQueueStatus.MERGED) {
                    completeApprovalIssueAfterMerge(issue.id)
                }
            }
        }
        mirrorIssueToLinear(
            issue.id,
            "CEO completed approval for \"${issue.title}\" with verdict ${verdict.value}."
        )
        runtimeContinuationCompanyId?.let { companyId ->
            serviceScope.launch {
                runCatching { runCompanyRuntimeTick(companyId) }
            }
        }
    }

    private fun buildPullRequestFeedbackBody(
        actorLabel: String,
        verdictKey: String,
        verdict: String,
        feedback: String?
    ): String = buildString {
        appendLine("$verdictKey: $verdict")
        appendLine()
        appendLine("$actorLabel feedback from Cotor:")
        if (!feedback.isNullOrBlank()) {
            appendLine(feedback)
        } else {
            appendLine("$actorLabel recorded this verdict without additional notes.")
        }
    }.trim()

    private fun isMergeConflictFailure(error: ProcessExecutionException): Boolean {
        val combined = listOf(error.message, error.stdout, error.stderr)
            .joinToString("\n")
            .lowercase()
        return combined.contains("not mergeable") ||
            combined.contains("cannot be cleanly created") ||
            combined.contains("merge conflicts locally") ||
            combined.contains("merge conflict")
    }

    private fun mergeConflictFeedback(error: ProcessExecutionException): String {
        val details = listOf(error.stderr, error.stdout, error.message)
            .firstOrNull { !it.isNullOrBlank() }
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.firstOrNull()
            ?: "GitHub reported that the pull request no longer merges cleanly."
        return "GitHub could not merge this pull request cleanly. Rebase or merge the latest base branch into the execution worktree, resolve conflicts, and republish before asking CEO approval again.\n\nOriginal error: $details"
    }

    private fun buildCompanyAutomationTraceEvent(
        issue: CompanyIssue,
        goal: CompanyGoal?,
        oldStatus: IssueStatus,
        newStatus: IssueStatus,
        source: String,
        reason: String,
        latestTask: AgentTask? = null,
        latestRun: AgentRun? = null,
        retryEligible: Boolean? = null
    ): CompanyAutomationTraceEvent =
        CompanyAutomationTraceEvent(
            timestamp = System.currentTimeMillis(),
            companyId = issue.companyId,
            projectContextId = issue.projectContextId,
            goalId = issue.goalId,
            issueId = issue.id,
            issueTitle = issue.title,
            issueKind = issue.kind,
            oldStatus = oldStatus,
            newStatus = newStatus,
            source = source,
            reason = reason,
            operatingPolicy = goal?.operatingPolicy,
            latestTaskId = latestTask?.id,
            latestTaskStatus = latestTask?.status?.name,
            latestTaskUpdatedAt = latestTask?.updatedAt,
            latestRunId = latestRun?.id,
            latestRunStatus = latestRun?.status?.name,
            latestRunUpdatedAt = latestRun?.updatedAt,
            retryEligible = retryEligible,
            runErrorSnippet = latestRun?.error?.take(300) ?: latestRun?.publish?.error?.take(300)
        )

    private fun appendCompanyAutomationTrace(event: CompanyAutomationTraceEvent) {
        runCatching {
            // This file is for state transitions, not steady-state re-affirmations. Repeated
            // no-op entries made it difficult to see real workflow movement in live debugging.
            if (event.oldStatus == event.newStatus) {
                return@runCatching
            }
            val runtimeDir = stateStore.appHome().resolve("runtime").resolve("backend")
            Files.createDirectories(runtimeDir)
            val logFile = runtimeDir.resolve("company-automation-trace.log")
            val dedupKey = buildString {
                append(event.companyId)
                append('|')
                append(event.issueId)
                append('|')
                append(event.oldStatus)
                append('|')
                append(event.newStatus)
                append('|')
                append(event.source)
                append('|')
                append(event.reason)
                append('|')
                append(event.latestTaskId ?: "")
                append('|')
                append(event.latestTaskStatus ?: "")
                append('|')
                append(event.latestRunId ?: "")
                append('|')
                append(event.latestRunStatus ?: "")
                append('|')
                append(event.retryEligible?.toString().orEmpty())
            }
            val now = System.currentTimeMillis()
            recentCompanyAutomationTraceKeys.entries.removeIf { now - it.value > COMPANY_TRACE_DEDUP_WINDOW_MS }
            val previousTimestamp = recentCompanyAutomationTraceKeys.put(dedupKey, now)
            if (previousTimestamp != null && now - previousTimestamp < COMPANY_TRACE_DEDUP_WINDOW_MS) {
                return@runCatching
            }
            if (Files.exists(logFile) && Files.size(logFile) >= COMPANY_TRACE_ROTATE_BYTES) {
                Files.move(
                    logFile,
                    logFile.resolveSibling("${logFile.fileName}.1"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
            val line = backendJson.encodeToString(CompanyAutomationTraceEvent.serializer(), event) + "\n"
            Files.writeString(
                logFile,
                line,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        }
    }

    private fun ensureCompanyRuntimeLoop(companyId: String) {
        if (companyRuntimeJobs[companyId]?.isActive == true) {
            return
        }
        companyRuntimeJobs[companyId] = serviceScope.launch {
            var consecutiveFailures = 0
            val maxConsecutiveFailures = 5
            while (isActive) {
                val runtime = runtimeStatus(companyId)
                val preTickState = stateStore.load()
                val hasActiveWork = hasActiveCompanyTasks(preTickState, companyId)
                val tickDelay = if (hasActiveWork) {
                    15_000L
                } else {
                    runtime.adaptiveTickMs.coerceIn(15_000L, 120_000L)
                }
                delay(tickDelay)
                try {
                    if (runtimeStatus(companyId).status != CompanyRuntimeStatus.RUNNING) {
                        break
                    }
                    val snapshot = runCompanyRuntimeTick(companyId)
                    consecutiveFailures = 0
                    val postTickState = stateStore.load()
                    val hasActiveWorkAfterTick = hasActiveCompanyTasks(postTickState, companyId)
                    // Adaptive tick: speed up when there's work, slow down when idle
                    val wasProductive = snapshot.lastAction?.let {
                        it != "idle" && !it.startsWith("idle-")
                    } == true
                    val nextTickMs = when {
                        hasActiveWorkAfterTick -> 15_000L
                        wasProductive -> 15_000L
                        else -> (tickDelay + 10_000L).coerceAtMost(120_000L)
                    }
                    updateAdaptiveTickMs(companyId, nextTickMs)
                } catch (cancelled: CancellationException) {
                    break
                } catch (cause: Throwable) {
                    consecutiveFailures++
                    markCompanyRuntimeError(companyId, cause)
                    if (consecutiveFailures >= maxConsecutiveFailures) {
                        break
                    }
                    val backoffMs = (30_000L * (1L shl (consecutiveFailures - 1).coerceAtMost(3))).coerceAtMost(300_000L)
                    updateAdaptiveTickMs(companyId, backoffMs)
                    delay(backoffMs)
                }
            }
        }
    }

    private suspend fun updateAdaptiveTickMs(companyId: String, tickMs: Long) {
        runCatching {
            stateMutex.withLock {
                val state = stateStore.load()
                val nextRuntimes = state.companyRuntimes.map {
                    if (it.companyId == companyId) it.copy(adaptiveTickMs = tickMs) else it
                }
                stateStore.save(state.copy(companyRuntimes = nextRuntimes))
            }
        }
    }

    private fun isBudgetExhausted(state: DesktopAppState, companyId: String, runtime: CompanyRuntimeSnapshot): Boolean {
        val company = state.companies.firstOrNull { it.id == companyId } ?: return false
        val window = currentBudgetWindow(runtime)
        return isBudgetExhausted(company, window.todaySpentCents, window.monthSpentCents)
    }

    suspend fun recordRunCost(companyId: String, costCents: Int) {
        if (costCents <= 0) return
        stateMutex.withLock {
            val state = stateStore.load()
            val now = System.currentTimeMillis()
            val company = state.companies.firstOrNull { it.id == companyId } ?: return@withLock
            val today = java.time.LocalDate.now().toString()
            val nextRuntimes = state.companyRuntimes.map {
                if (it.companyId != companyId) return@map it
                val window = currentBudgetWindow(it)
                val nextTodaySpend = window.todaySpentCents + costCents
                val nextMonthSpend = window.monthSpentCents + costCents
                it.copy(
                    todaySpentCents = nextTodaySpend,
                    monthSpentCents = nextMonthSpend,
                    budgetPausedAt = if (isBudgetExhausted(company, nextTodaySpend, nextMonthSpend)) now else null,
                    budgetResetDate = today
                )
            }
            stateStore.save(state.copy(companyRuntimes = nextRuntimes))
        }
    }

    private fun normalizeBudgetOverride(requestedBudgetCents: Int?, existingBudgetCents: Int?): Int? =
        when (requestedBudgetCents) {
            null -> existingBudgetCents
            else -> requestedBudgetCents.takeIf { it > 0 }
        }

    private data class BudgetWindow(
        val dayKey: String,
        val monthKey: String,
        val todaySpentCents: Int,
        val monthSpentCents: Int
    )

    private fun currentBudgetWindow(
        runtime: CompanyRuntimeSnapshot,
        now: java.time.LocalDate = java.time.LocalDate.now()
    ): BudgetWindow {
        val todayKey = now.toString()
        val monthKey = todayKey.take(7)
        val savedDayKey = runtime.budgetResetDate
        val savedMonthKey = savedDayKey?.take(7)
        return BudgetWindow(
            dayKey = todayKey,
            monthKey = monthKey,
            todaySpentCents = if (savedDayKey == todayKey) runtime.todaySpentCents else 0,
            monthSpentCents = if (savedMonthKey == monthKey) runtime.monthSpentCents else 0
        )
    }

    private fun runtimeWithinCurrentBudgetWindow(
        runtime: CompanyRuntimeSnapshot,
        now: java.time.LocalDate = java.time.LocalDate.now()
    ): CompanyRuntimeSnapshot {
        val window = currentBudgetWindow(runtime, now)
        val resetOccurred = runtime.todaySpentCents != window.todaySpentCents || runtime.monthSpentCents != window.monthSpentCents
        return if (!resetOccurred) {
            runtime
        } else {
            runtime.copy(
                todaySpentCents = window.todaySpentCents,
                monthSpentCents = window.monthSpentCents,
                budgetPausedAt = null
            )
        }
    }

    private fun isBudgetExhausted(company: Company, todaySpentCents: Int, monthSpentCents: Int): Boolean {
        val dailyExceeded = company.dailyBudgetCents?.let { todaySpentCents >= it } ?: false
        val monthlyExceeded = company.monthlyBudgetCents?.let { monthSpentCents >= it } ?: false
        return dailyExceeded || monthlyExceeded
    }

    private fun estimateRunCostCents(
        agent: AgentConfig,
        prompt: String,
        result: AgentResult
    ): Int {
        val explicitKeys = listOf("estimatedCostCents", "costCents")
        for (key in explicitKeys) {
            result.metadata[key]?.toIntOrNull()?.let { return it.coerceAtLeast(0) }
        }
        result.metadata["usdCost"]?.toDoubleOrNull()?.let { return kotlin.math.max(0, kotlin.math.ceil(it * 100.0).toInt()) }

        val pricing = pricingForAgent(agent) ?: return 0
        val explicitPromptTokens = result.metadata["promptTokens"]?.toIntOrNull()?.coerceAtLeast(0)
        val explicitCompletionTokens = result.metadata["completionTokens"]?.toIntOrNull()?.coerceAtLeast(0)
        val explicitTotalTokens = result.metadata["totalTokens"]?.toIntOrNull()?.coerceAtLeast(0)

        if (!result.isSuccess && result.output.isNullOrBlank() && explicitPromptTokens == null && explicitCompletionTokens == null && explicitTotalTokens == null) {
            return 0
        }

        val promptTokens = explicitPromptTokens ?: when {
            explicitTotalTokens != null && explicitCompletionTokens != null ->
                (explicitTotalTokens - explicitCompletionTokens).coerceAtLeast(0)
            else -> estimateTokenCount(prompt)
        }
        val completionTokens = explicitCompletionTokens ?: when {
            explicitTotalTokens != null && explicitPromptTokens != null ->
                (explicitTotalTokens - explicitPromptTokens).coerceAtLeast(0)
            else -> estimateTokenCount(result.output)
        }
        if (promptTokens + completionTokens <= 0) {
            return 0
        }
        val estimatedCents =
            (promptTokens / 1000.0) * pricing.promptCentsPer1k +
                (completionTokens / 1000.0) * pricing.completionCentsPer1k
        return kotlin.math.max(1, kotlin.math.ceil(estimatedCents).toInt())
    }

    private data class AgentPricing(
        val promptCentsPer1k: Double,
        val completionCentsPer1k: Double
    )

    private fun pricingForAgent(agent: AgentConfig): AgentPricing? {
        val plugin = agent.pluginClass.lowercase()
        val name = agent.name.lowercase()
        return when {
            "openai" in plugin || "codex" in plugin || name == "codex" || name == "openai" ->
                AgentPricing(promptCentsPer1k = 0.30, completionCentsPer1k = 1.20)
            "anthropic" in plugin || "claude" in plugin || name == "claude" ->
                AgentPricing(promptCentsPer1k = 0.30, completionCentsPer1k = 1.50)
            "gemini" in plugin || name == "gemini" ->
                AgentPricing(promptCentsPer1k = 0.08, completionCentsPer1k = 0.24)
            else -> null
        }
    }

    private fun estimateTokenCount(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        return kotlin.math.ceil(text.length / 4.0).toInt().coerceAtLeast(1)
    }

    // ── Generic Pipeline Stage Engine ───────────────────────────────────

    private fun findProfileForPipelineStage(
        stage: WorkflowStageDefinition,
        companyId: String,
        profiles: List<OrgAgentProfile>
    ): OrgAgentProfile? {
        val roleName = stage.assigneeRoleName ?: return when (stage.kind) {
            "review" -> findQaProfile(companyId, profiles)
            "approval" -> findChiefProfile(companyId, profiles)
            else -> null
        }
        return profiles.firstOrNull {
            it.companyId == companyId && it.enabled && it.roleName.equals(roleName, ignoreCase = true)
        } ?: when (stage.kind) {
            "review" -> findQaProfile(companyId, profiles)
            "approval" -> findChiefProfile(companyId, profiles)
            else -> profiles.firstOrNull { it.companyId == companyId && it.enabled }
        }
    }

    private fun resolveNextPipelineStage(
        state: DesktopAppState,
        issue: CompanyIssue
    ): Pair<WorkflowPipelineDefinition, WorkflowStageDefinition?>? {
        val pipeline = issue.pipelineId?.let { pid -> state.workflowPipelines.firstOrNull { it.id == pid } }
            ?: resolveCompanyPipeline(state, issue.companyId)
            ?: return null
        val currentStageId = issue.currentStageId ?: issue.kind
        val sortedStages = pipeline.stages.sortedBy { it.order }
        val currentIdx = sortedStages.indexOfFirst { it.id == currentStageId }
        if (currentIdx < 0) return pipeline to null
        val nextStage = sortedStages.getOrNull(currentIdx + 1)
        return pipeline to nextStage
    }

    private suspend fun createNextPipelineStageIssue(
        executionIssue: CompanyIssue,
        currentStageIssue: CompanyIssue,
        nextStage: WorkflowStageDefinition,
        pipeline: WorkflowPipelineDefinition,
        profiles: List<OrgAgentProfile>,
        queueItem: ReviewQueueItem?,
        verdict: StructuredVerdict?,
        now: Long
    ): CompanyIssue? {
        val shouldSkip = nextStage.skipWhen?.let { condition ->
            when (condition) {
                "!codeProducing" -> executionIssue.codeProducing != true
                else -> false
            }
        } == true
        if (shouldSkip) return null

        val assignee = findProfileForPipelineStage(nextStage, executionIssue.companyId, profiles)
        val description = buildString {
            appendLine("${nextStage.title} for \"${executionIssue.title}\".")
            appendLine()
            queueItem?.branchName?.let { appendLine("Branch: $it") }
            queueItem?.pullRequestUrl?.let { appendLine("Pull request: $it") }
            verdict?.let {
                appendLine("Previous verdict: ${it.value}")
                if (it.feedback.isNotBlank()) appendLine("Feedback: ${it.feedback}")
            }
            appendLine()
            appendLine("Responsibilities:")
            nextStage.verdictKey?.let { key ->
                appendLine("- Emit $key: ${nextStage.verdictPassValue} or $key: ${nextStage.verdictFailValue} with concise feedback.")
            }
        }.trim()

        return CompanyIssue(
            id = UUID.randomUUID().toString(),
            companyId = executionIssue.companyId,
            projectContextId = executionIssue.projectContextId,
            goalId = executionIssue.goalId,
            workspaceId = executionIssue.workspaceId,
            title = "${nextStage.title}: ${executionIssue.title}",
            description = description,
            status = IssueStatus.PLANNED,
            priority = if (nextStage.kind == "approval") 3 else 2,
            kind = nextStage.kind,
            assigneeProfileId = assignee?.id,
            dependsOn = listOf(currentStageIssue.id),
            acceptanceCriteria = listOfNotNull(
                nextStage.verdictKey?.let { "Emit $it with concrete acceptance or requested changes." }
            ),
            riskLevel = if (nextStage.kind == "approval") "medium" else "low",
            codeProducing = false,
            branchName = executionIssue.branchName,
            worktreePath = executionIssue.worktreePath,
            pullRequestNumber = executionIssue.pullRequestNumber,
            pullRequestUrl = executionIssue.pullRequestUrl,
            pullRequestState = executionIssue.pullRequestState,
            qaVerdict = verdict?.value,
            qaFeedback = verdict?.feedback,
            transitionReason = "${nextStage.title} stage opened by pipeline ${pipeline.name}.",
            sourceSignal = "pipeline-stage:${nextStage.id}:${executionIssue.id}",
            pipelineId = pipeline.id,
            currentStageId = nextStage.id,
            createdAt = now,
            updatedAt = now
        )
    }

    private suspend fun markCompanyRuntimeError(companyId: String, cause: Throwable) {
        if (cause is CancellationException) {
            return
        }
        appendCompanyRuntimeErrorLog(companyId, cause)
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

    private fun appendCompanyRuntimeErrorLog(companyId: String, cause: Throwable) {
        runCatching {
            val runtimeDir = stateStore.appHome().resolve("runtime").resolve("backend")
            Files.createDirectories(runtimeDir)
            val logFile = runtimeDir.resolve("company-runtime-errors.log")
            if (Files.exists(logFile) && Files.size(logFile) >= COMPANY_RUNTIME_ERROR_ROTATE_BYTES) {
                Files.move(
                    logFile,
                    logFile.resolveSibling("${logFile.fileName}.1"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
            val stacktrace = StringWriter().also { writer ->
                cause.printStackTrace(PrintWriter(writer))
            }.toString()
            val line = buildString {
                append('[')
                append(System.currentTimeMillis())
                append("] companyId=")
                append(companyId)
                append(" error=")
                append(cause.message ?: cause::class.simpleName ?: "unknown")
                append('\n')
                append(stacktrace)
                if (!stacktrace.endsWith("\n")) append('\n')
            }
            Files.writeString(
                logFile,
                line,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
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
                        lastError = null,
                        budgetPausedAt = if (lastAction.startsWith("budget-paused")) {
                            current.budgetPausedAt ?: System.currentTimeMillis()
                        } else {
                            null
                        }
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
            activeIssues = issues.count {
                it.status == IssueStatus.PLANNED ||
                    it.status == IssueStatus.DELEGATED ||
                    it.status == IssueStatus.IN_PROGRESS ||
                    it.status == IssueStatus.IN_REVIEW ||
                    it.status == IssueStatus.READY_FOR_CEO
            },
            blockedIssues = issues.count { it.status == IssueStatus.BLOCKED },
            readyToMergeCount = reviewQueue.count { it.status == ReviewQueueStatus.READY_FOR_CEO || it.status == ReviewQueueStatus.READY_TO_MERGE },
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
                    it.status == IssueStatus.IN_REVIEW ||
                    it.status == IssueStatus.READY_FOR_CEO
            },
            autonomyEnabledGoalCount = goals.count { it.autonomyEnabled }
        )
    }

    private fun DesktopAppState.computeCompanyRuntimes(): List<CompanyRuntimeSnapshot> =
        allKnownCompanyIds().map { companyId ->
            val existing = runtimeWithinCurrentBudgetWindow(
                (companyRuntimes.firstOrNull { it.companyId == companyId }
                    ?: CompanyRuntimeSnapshot(companyId = companyId)).withNormalizedStopIntent()
            )
            val company = companies.firstOrNull { it.id == companyId }
            val backendKind = company?.backendKind ?: backendSettings.defaultBackendKind
            val backendStatus = companyBackendStatus(companyId, this)
            val runtimeStopped = existing.status == CompanyRuntimeStatus.STOPPED
            val budgetWindow = currentBudgetWindow(existing)
            val budgetPausedAt = if (company != null && isBudgetExhausted(company, budgetWindow.todaySpentCents, budgetWindow.monthSpentCents)) {
                existing.budgetPausedAt
            } else {
                null
            }
            existing.copy(
                companyId = companyId,
                backendKind = backendKind,
                backendHealth = if (runtimeStopped) "stopped" else backendStatus?.health ?: "unknown",
                backendMessage = if (runtimeStopped && existing.manuallyStoppedAt != null) {
                    "Runtime stopped manually until the user presses Start."
                } else {
                    backendStatus?.message
                },
                backendLifecycleState = if (runtimeStopped) {
                    BackendLifecycleState.STOPPED
                } else {
                    backendStatus.lifecycleState
                },
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
                                it.status == IssueStatus.IN_REVIEW ||
                                it.status == IssueStatus.READY_FOR_CEO
                        )
                },
                autonomyEnabledGoalCount = goals.count { it.companyId == companyId && it.autonomyEnabled },
                todaySpentCents = budgetWindow.todaySpentCents,
                monthSpentCents = budgetWindow.monthSpentCents,
                budgetPausedAt = budgetPausedAt
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

    private fun DesktopAppState.hasRecentCompanyActivity(
        companyId: String,
        title: String,
        detail: String? = null,
        windowMs: Long = 30_000
    ): Boolean {
        val cutoff = System.currentTimeMillis() - windowMs
        return companyActivity.any { activity ->
            activity.companyId == companyId &&
                activity.title == title &&
                activity.detail == detail &&
                activity.createdAt >= cutoff
        }
    }

    private fun upsertCompanyRuntime(
        runtimes: List<CompanyRuntimeSnapshot>,
        updated: CompanyRuntimeSnapshot
    ): List<CompanyRuntimeSnapshot> =
        runtimes.filterNot { it.companyId == updated.companyId } + updated

    private fun isNonFatalPublishError(
        error: String,
        state: DesktopAppState,
        task: AgentTask? = null,
        issue: CompanyIssue? = null
    ): Boolean {
        if (error.startsWith("No changes to publish") && task?.issueId == null && issue == null) {
            return true
        }
        if (state.backendSettings.codePublishMode != CodePublishMode.ALLOW_LOCAL_GIT) {
            return false
        }
        return error == "No GitHub remote configured; kept local commit only" ||
            error.contains("gh auth", ignoreCase = true) ||
            error.contains("GitHub publishing requires an authenticated gh CLI session.", ignoreCase = true)
    }
}

private fun defaultCommandAvailability(command: String): Boolean {
    return resolveExecutablePath(command) != null
}
