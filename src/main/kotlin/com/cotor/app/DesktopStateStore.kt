package com.cotor.app

/**
 * File overview for DesktopStateStore.
 *
 * This file belongs to the app layer for the desktop shell and localhost app-server surface.
 * It groups declarations around desktop state store so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.app.persistence.StateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Persists the lightweight desktop state into Application Support.
 *
 * The store is intentionally forgiving: if the JSON file becomes unreadable we
 * fall back to an empty state instead of blocking the whole desktop app from booting.
 */
class DesktopStateStore(
    private val appHomeProvider: () -> Path = { defaultDesktopAppHome() },
) : StateRepository<DesktopAppState> {
    companion object {
        private const val MAX_PERSISTED_RESOLVED_ISSUES = 20
        private const val MAX_PERSISTED_TASK_PROMPT_CHARS = 512
        private const val MAX_PERSISTED_RUN_OUTPUT_CHARS = 4000
        private const val MAX_STATE_LOAD_LOG_BYTES = 1L * 1024L * 1024L
        private const val STATE_LOAD_LOG_DEDUP_WINDOW_MS = 30_000L
        private const val STATE_LOCK_TIMEOUT_MS = 3_000L
        private const val STATE_LOCK_RETRY_DELAY_MS = 50L
        private const val MAX_TAIL_TRIM_RECOVERY_CHARS = 512

        @Volatile
        private var lastStateLoadLogMessage: String? = null

        @Volatile
        private var lastStateLoadLogAt: Long = 0L
    }

    private data class CachedState(
        val state: DesktopAppState,
        val lastModifiedAtMillis: Long,
        val sizeInBytes: Long
    )

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    // A single process can finish multiple background runs nearly at once, so writes
    // need to be serialized even though the backing file is small.
    private val mutex = Mutex()

    @Volatile
    private var cachedState: CachedState? = null

    fun appHome(): Path = appHomeProvider()

    fun managedReposRoot(): Path = appHome().resolve("ManagedRepos")

    override suspend fun load(): DesktopAppState = withContext(Dispatchers.IO) {
        val stateFile = stateFile()
        if (!stateFile.exists()) {
            return@withContext DesktopAppState()
        }
        val backupFile = backupStateFile()
        currentFingerprint(stateFile)?.let { fingerprint ->
            cachedState
                ?.takeIf {
                    it.lastModifiedAtMillis == fingerprint.first &&
                        it.sizeInBytes == fingerprint.second
                }
                ?.let { return@withContext it.state }
        }

        withStateFileLock {
            currentFingerprint(stateFile)?.let { fingerprint ->
                cachedState
                    ?.takeIf {
                        it.lastModifiedAtMillis == fingerprint.first &&
                            it.sizeInBytes == fingerprint.second
                    }
                    ?.let { return@withStateFileLock it.state }
            }
            val raw = runCatching { stateFile.readText() }.getOrElse { return@withStateFileLock DesktopAppState() }
            decodeState(raw)?.also { decoded ->
                val compacted = compactStateForPersistence(normalizeLegacyCompanyRuntimeState(decoded))
                if (raw != json.encodeToString(DesktopAppState.serializer(), compacted)) {
                    saveLocked(compacted)
                } else {
                    updateCache(stateFile, compacted)
                }
                return@withStateFileLock compacted
            } ?: DesktopAppState()
            if (backupFile.exists()) {
                val backupRaw = runCatching { backupFile.readText() }.getOrNull()
                decodeState(backupRaw.orEmpty())?.also { recovered ->
                    val compacted = compactStateForPersistence(normalizeLegacyCompanyRuntimeState(recovered))
                    saveLocked(compacted)
                    return@withStateFileLock compacted
                }
            }
            DesktopAppState()
        }
    }

    override suspend fun save(state: DesktopAppState) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                withStateFileLock {
                    saveLocked(state)
                }
            }
        }
    }

    private fun stateFile(): Path = appHome().resolve("state.json")

    private fun backupStateFile(): Path = appHome().resolve("state.json.bak")

    private fun lockFile(): Path = appHome().resolve("state.lock")

    private fun lockMetadataFile(): Path = appHome().resolve("state.lock.json")

    private fun saveLocked(state: DesktopAppState) {
        val file = stateFile()
        val backupFile = backupStateFile()
        // Always create the parent directories before the first write so the
        // app can start from a completely clean machine state.
        file.parent?.createDirectories()
        managedReposRoot().createDirectories()
        val compactedState = compactStateForPersistence(normalizeLegacyCompanyRuntimeState(state))
        val payload = json.encodeToString(DesktopAppState.serializer(), compactedState)
        val tempFile = Files.createTempFile(file.parent, "${file.fileName}.", ".tmp")
        tempFile.writeText(payload)
        enforceOwnerOnlyPermissions(tempFile)
        moveWithAtomicFallback(tempFile, file)
        enforceOwnerOnlyPermissions(file)
        val backupTempFile = Files.createTempFile(backupFile.parent, "${backupFile.fileName}.", ".tmp")
        backupTempFile.writeText(payload)
        enforceOwnerOnlyPermissions(backupTempFile)
        moveWithAtomicFallback(backupTempFile, backupFile)
        enforceOwnerOnlyPermissions(backupFile)
        updateCache(file, compactedState)
    }

    private fun decodeStateOrNull(raw: String): DesktopAppState? {
        val strictDecode = runCatching { json.decodeFromString<DesktopAppState>(raw) }
        strictDecode.getOrNull()?.let { return it }
        return decodeStateLenient(raw, strictDecode.exceptionOrNull())
    }

    private fun decodeState(raw: String): DesktopAppState? {
        decodeStateOrNull(raw)?.let { return it }
        var candidate = raw.trimEnd()
        var trimsRemaining = MAX_TAIL_TRIM_RECOVERY_CHARS
        while (candidate.isNotEmpty() && trimsRemaining > 0) {
            candidate = candidate.dropLast(1).trimEnd()
            trimsRemaining -= 1
            decodeStateOrNull(candidate)?.let { return it }
        }
        return null
    }

    private fun decodeStateLenient(raw: String, strictError: Throwable?): DesktopAppState? = runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        val skippedEntries = mutableListOf<String>()
        fun <T> decodeList(name: String, serializer: KSerializer<T>): List<T> =
            root[name]
                ?.jsonArray
                ?.mapIndexedNotNull { index, element ->
                    runCatching { json.decodeFromJsonElement(serializer, element) }
                        .onFailure { error ->
                            skippedEntries += "$name[$index]: ${error.message ?: error::class.simpleName.orEmpty()}"
                        }
                        .getOrNull()
                }
                ?: emptyList()
        fun <T> decodeObject(name: String, serializer: KSerializer<T>, defaultValue: T): T =
            root[name]
                ?.let { element ->
                    runCatching { json.decodeFromJsonElement(serializer, element) }
                        .onFailure { error ->
                            skippedEntries += "$name: ${error.message ?: error::class.simpleName.orEmpty()}"
                        }
                        .getOrNull()
                }
                ?: defaultValue

        val recovered = DesktopAppState(
            companies = decodeList("companies", Company.serializer()),
            companyAgentDefinitions = decodeList("companyAgentDefinitions", CompanyAgentDefinition.serializer()),
            projectContexts = decodeList("projectContexts", CompanyProjectContext.serializer()),
            repositories = decodeList("repositories", ManagedRepository.serializer()),
            workspaces = decodeList("workspaces", Workspace.serializer()),
            tasks = decodeList("tasks", AgentTask.serializer()),
            runs = decodeList("runs", AgentRun.serializer()),
            goals = decodeList("goals", CompanyGoal.serializer()),
            issues = decodeList("issues", CompanyIssue.serializer()),
            issueDependencies = decodeList("issueDependencies", IssueDependency.serializer()),
            orgProfiles = decodeList("orgProfiles", OrgAgentProfile.serializer()),
            workflowPipelines = decodeList("workflowPipelines", WorkflowPipelineDefinition.serializer()),
            workflowTopologies = decodeList("workflowTopologies", WorkflowTopologySnapshot.serializer()),
            goalDecisions = decodeList("goalDecisions", GoalOrchestrationDecision.serializer()),
            reviewQueue = decodeList("reviewQueue", ReviewQueueItem.serializer()),
            companyActivity = decodeList("companyActivity", CompanyActivityItem.serializer()),
            agentContextEntries = decodeList("agentContextEntries", AgentContextEntry.serializer()),
            agentMessages = decodeList("agentMessages", AgentMessage.serializer()),
            opsMetrics = decodeObject("opsMetrics", OpsMetricSnapshot.serializer(), OpsMetricSnapshot()),
            signals = decodeList("signals", OpsSignal.serializer()),
            backendSettings = decodeObject("backendSettings", DesktopBackendSettings.serializer(), DesktopBackendSettings()),
            linearSettings = decodeObject("linearSettings", DesktopLinearSettings.serializer(), DesktopLinearSettings()),
            runtime = decodeObject("runtime", CompanyRuntimeSnapshot.serializer(), CompanyRuntimeSnapshot()),
            companyRuntimes = decodeList("companyRuntimes", CompanyRuntimeSnapshot.serializer())
        )
        val summary = buildString {
            append("Recovered state with lenient decode")
            strictError?.message?.takeIf { it.isNotBlank() }?.let {
                append(" | strict=")
                append(it)
            }
            if (skippedEntries.isNotEmpty()) {
                append(" | skipped=")
                append(skippedEntries.take(20).joinToString("; "))
                if (skippedEntries.size > 20) {
                    append(" (+")
                    append(skippedEntries.size - 20)
                    append(" more)")
                }
            }
        }
        appendStateLoadLog(summary)
        recovered
    }.onFailure { error ->
        appendStateLoadLog(
            "Failed lenient state decode" +
                (strictError?.message?.let { " | strict=$it" } ?: "") +
                " | lenient=${error.message ?: error::class.simpleName.orEmpty()}"
        )
    }.getOrNull()

    private fun appendStateLoadLog(message: String) {
        runCatching {
            val now = System.currentTimeMillis()
            synchronized(DesktopStateStore::class.java) {
                if (message == lastStateLoadLogMessage && now - lastStateLoadLogAt < STATE_LOAD_LOG_DEDUP_WINDOW_MS) {
                    return@runCatching
                }
                lastStateLoadLogMessage = message
                lastStateLoadLogAt = now
            }
            val runtimeDir = appHome().resolve("runtime").resolve("backend")
            runtimeDir.createDirectories()
            val logFile = runtimeDir.resolve("state-load.log")
            if (logFile.exists() && Files.size(logFile) >= MAX_STATE_LOAD_LOG_BYTES) {
                Files.move(
                    logFile,
                    logFile.resolveSibling("${logFile.fileName}.1"),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            Files.writeString(
                logFile,
                "[$now] $message\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        }
    }

    private suspend fun <T> withStateFileLock(block: () -> T): T {
        val lockPath = lockFile()
        val metadataPath = lockMetadataFile()
        lockPath.parent?.createDirectories()
        metadataPath.parent?.createDirectories()
        return try {
            FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            ).use { channel ->
                val deadline = System.currentTimeMillis() + STATE_LOCK_TIMEOUT_MS
                while (true) {
                    val lock = channel.tryLock()
                    if (lock != null) {
                        return@use lock.use {
                            writeLockMetadata(metadataPath)
                            try {
                                block()
                            } finally {
                                clearLockMetadata(metadataPath)
                            }
                        }
                    }
                    if (System.currentTimeMillis() >= deadline) {
                        val holder = runCatching { metadataPath.readText() }.getOrNull()?.trim()
                        error(
                            buildString {
                                append("state.lock acquisition timed out after ")
                                append(STATE_LOCK_TIMEOUT_MS)
                                append("ms; path=")
                                append(lockPath)
                                if (!holder.isNullOrBlank()) {
                                    append("; holder=")
                                    append(holder)
                                }
                            }
                        )
                    }
                    delay(STATE_LOCK_RETRY_DELAY_MS)
                }
                error("Unreachable")
            }
        } catch (_: OverlappingFileLockException) {
            // The same JVM can legitimately re-enter state reads/writes while a
            // sibling coroutine still holds the process-wide file lock. In that
            // case the in-process mutexes already provide serialization, so we
            // only need the inter-process lock when it is available.
            block()
        }
    }

    private fun moveWithAtomicFallback(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (error: Exception) {
            appendStateLoadLog(
                "ATOMIC_MOVE failed for ${target.fileName}: ${error.message ?: error::class.simpleName.orEmpty()}; falling back to non-atomic replace"
            )
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun writeLockMetadata(metadataPath: Path) {
        val now = System.currentTimeMillis()
        val pid = runCatching { ProcessHandle.current().pid() }.getOrDefault(-1L)
        val payload = """
            {"pid":$pid,"lockedAt":$now,"appHome":"${appHome().toString().replace("\"", "\\\"")}"}
        """.trimIndent()
        metadataPath.writeText(payload)
        enforceOwnerOnlyPermissions(metadataPath)
    }

    private fun clearLockMetadata(metadataPath: Path) {
        runCatching { Files.deleteIfExists(metadataPath) }
    }

    private fun enforceOwnerOnlyPermissions(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
                )
            )
        }
    }

    private fun currentFingerprint(file: Path): Pair<Long, Long>? =
        if (!file.exists()) {
            null
        } else {
            Files.getLastModifiedTime(file).toMillis() to Files.size(file)
        }

    private fun updateCache(file: Path, state: DesktopAppState) {
        currentFingerprint(file)?.let { fingerprint ->
            cachedState = CachedState(
                state = state,
                lastModifiedAtMillis = fingerprint.first,
                sizeInBytes = fingerprint.second
            )
        }
    }

    private fun compactStateForPersistence(state: DesktopAppState): DesktopAppState =
        state.run {
            val unresolvedIssueIds = issues
                .filter { it.status != IssueStatus.DONE && it.status != IssueStatus.CANCELED }
                .mapTo(linkedSetOf()) { it.id }
            val recentResolvedIssueIds = issues
                .filter { it.status == IssueStatus.DONE || it.status == IssueStatus.CANCELED }
                .sortedByDescending { it.updatedAt }
                .take(MAX_PERSISTED_RESOLVED_ISSUES)
                .mapTo(linkedSetOf()) { it.id }
            val latestRetainedTaskIds = tasks
                .filter { task -> task.issueId != null && task.issueId in recentResolvedIssueIds }
                .groupBy { it.issueId!! }
                .values
                .mapNotNull { issueTasks -> issueTasks.maxByOrNull { it.updatedAt }?.id }
                .toSet()
            val retainedTasks = tasks.filter { task ->
                task.issueId == null ||
                    task.status == DesktopTaskStatus.RUNNING ||
                    task.status == DesktopTaskStatus.QUEUED ||
                    task.issueId in unresolvedIssueIds ||
                    task.id in latestRetainedTaskIds
            }
            val retainedTaskIds = retainedTasks.mapTo(linkedSetOf()) { it.id }
            val retainedRuns = runs
                .groupBy { it.taskId }
                .flatMap { (taskId, taskRuns) ->
                    if (taskId !in retainedTaskIds) {
                        emptyList()
                    } else {
                        val task = retainedTasks.firstOrNull { it.id == taskId }
                        if (task?.issueId in unresolvedIssueIds || task?.status == DesktopTaskStatus.RUNNING || task?.status == DesktopTaskStatus.QUEUED) {
                            taskRuns
                        } else {
                            listOfNotNull(taskRuns.maxByOrNull { it.updatedAt })
                        }
                    }
                }
            val retainedReviewQueueIssueIds = issues
                .filter { it.status != IssueStatus.DONE && it.status != IssueStatus.CANCELED }
                .mapTo(linkedSetOf()) { it.id }
            copy(
                tasks = retainedTasks.map { compactTaskForPersistence(it, unresolvedIssueIds) },
                runs = retainedRuns.map(::compactRunForPersistence),
                reviewQueue = reviewQueue.filter { it.issueId in retainedReviewQueueIssueIds },
                companyActivity = companyActivity.sortedByDescending { it.createdAt }.take(200),
                signals = signals.sortedByDescending { it.createdAt }.take(150),
                goalDecisions = goalDecisions.sortedByDescending { it.createdAt }.take(150)
            )
        }

    private fun normalizeLegacyCompanyRuntimeState(state: DesktopAppState): DesktopAppState =
        state.copy(
            runtime = state.runtime.withNormalizedStopIntent(),
            companyRuntimes = state.companyRuntimes.map { runtime ->
                runtime.withNormalizedStopIntent()
            }
        )

    private fun compactTaskForPersistence(task: AgentTask, unresolvedIssueIds: Set<String>): AgentTask {
        if (
            task.status == DesktopTaskStatus.RUNNING ||
            task.status == DesktopTaskStatus.QUEUED ||
            task.issueId in unresolvedIssueIds
        ) {
            return task
        }
        return task.copy(
            prompt = compactRequiredText(task.prompt, MAX_PERSISTED_TASK_PROMPT_CHARS),
            plan = null
        )
    }

    private fun compactRunForPersistence(run: AgentRun): AgentRun =
        if (run.status == AgentRunStatus.RUNNING || run.status == AgentRunStatus.QUEUED) {
            run
        } else {
            run.copy(output = compactText(run.output, MAX_PERSISTED_RUN_OUTPUT_CHARS))
        }

    private fun compactText(value: String?, maxChars: Int): String? {
        val text = value ?: return null
        if (text.length <= maxChars) {
            return text
        }
        val omittedChars = text.length - maxChars
        return buildString {
            append(text.take(maxChars))
            append("\n\n[compacted ")
            append(omittedChars)
            append(" chars]")
        }
    }

    private fun compactRequiredText(value: String, maxChars: Int): String =
        compactText(value, maxChars) ?: value
}

/**
 * Desktop data lives under the conventional macOS Application Support location
 * so it behaves like a native app instead of leaving metadata in arbitrary repos.
 */
fun defaultDesktopAppHome(): Path {
    val overriddenHome = sequenceOf(
        System.getenv("COTOR_DESKTOP_APP_HOME"),
        System.getenv("COTOR_APP_HOME")
    )
        .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .map { java.nio.file.Paths.get(it).toAbsolutePath().normalize() }
        .firstOrNull()
    if (overriddenHome != null) {
        return overriddenHome
    }
    val userHome = java.nio.file.Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize()
    return userHome
        .resolve("Library")
        .resolve("Application Support")
        .resolve("CotorDesktop")
}
