package com.cotor.app

import com.cotor.data.config.ConfigRepository
import com.cotor.domain.executor.AgentExecutor
import com.cotor.model.AgentConfig
import com.cotor.model.AgentExecutionMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
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
) {
    // Reads are cheap and frequent, but writes must be serialized so the state file
    // cannot be partially overwritten when multiple agent runs finish at once.
    private val stateMutex = Mutex()
    // Runs execute in background coroutines so the API can return immediately after
    // the user presses "Run Task" in the desktop client.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun dashboard(): DashboardResponse = DashboardResponse(
        repositories = listRepositories(),
        workspaces = listWorkspaces(),
        tasks = listTasks(),
        settings = settings()
    )

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

    suspend fun getTask(taskId: String): AgentTask? =
        stateStore.load().tasks.firstOrNull { it.id == taskId }

    suspend fun openLocalRepository(path: String): ManagedRepository {
        val repositoryRoot = gitWorkspaceService.resolveRepositoryRoot(Path.of(path))
        val now = System.currentTimeMillis()

        return stateMutex.withLock {
            val state = stateStore.load()
            val existing = state.repositories.firstOrNull {
                Path.of(it.localPath).toAbsolutePath().normalize() == repositoryRoot
            }
            if (existing != null) {
                return@withLock existing
            }

            val repository = ManagedRepository(
                id = UUID.randomUUID().toString(),
                name = repositoryRoot.fileName.toString(),
                localPath = repositoryRoot.toString(),
                sourceKind = RepositorySourceKind.LOCAL,
                defaultBranch = gitWorkspaceService.detectDefaultBranch(repositoryRoot),
                createdAt = now,
                updatedAt = now
            )
            stateStore.save(state.copy(repositories = state.repositories + repository))
            repository
        }
    }

    suspend fun cloneRepository(url: String): ManagedRepository {
        val repositoryRoot = gitWorkspaceService.cloneRepository(url)
        val now = System.currentTimeMillis()

        return stateMutex.withLock {
            val state = stateStore.load()
            val existing = state.repositories.firstOrNull { it.remoteUrl == url }
            if (existing != null) {
                return@withLock existing
            }

            val repository = ManagedRepository(
                id = UUID.randomUUID().toString(),
                name = repositoryRoot.fileName.toString(),
                localPath = repositoryRoot.toString(),
                sourceKind = RepositorySourceKind.CLONED,
                remoteUrl = url,
                defaultBranch = gitWorkspaceService.detectDefaultBranch(repositoryRoot),
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
        agents: List<String>
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

        // Freeze the requested agent list into the task record up front so rerenders,
        // retries, and later refreshes all refer to the same execution plan.
        val task = AgentTask(
            id = taskId,
            workspaceId = workspace.id,
            title = title?.takeIf { it.isNotBlank() } ?: prompt.lineSequence().firstOrNull()?.take(48).orEmpty().ifBlank { "New Task" },
            prompt = prompt,
            agents = requestedAgents,
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
        shortcuts = ShortcutConfig()
    )

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
                    executeAgentRun(task, workspace, repository, agents[agentName] ?: BuiltinAgentCatalog.get(agentName))
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
    }

    private suspend fun executeAgentRun(
        task: AgentTask,
        workspace: Workspace,
        repository: ManagedRepository,
        agent: AgentConfig?
    ) {
        if (agent == null) {
            recordRunFailure(task, workspace, repository, "unknown", "Unknown agent configuration")
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
                input = task.prompt,
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

            replaceRun(
                startedRun.copy(
                    status = if (result.isSuccess) AgentRunStatus.COMPLETED else AgentRunStatus.FAILED,
                    processId = result.processId,
                    output = result.output,
                    error = result.error,
                    durationMs = result.duration.takeIf { it > 0 },
                    updatedAt = System.currentTimeMillis()
                )
            )
        } catch (t: Throwable) {
            recordRunFailure(task, workspace, repository, agent.name, t.message ?: "Unknown error")
        }
    }

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
}
