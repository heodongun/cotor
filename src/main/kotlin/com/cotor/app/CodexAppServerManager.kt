package com.cotor.app

import com.cotor.data.process.resolveExecutablePath
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

data class ManagedCodexServerStatus(
    val companyId: String,
    val lifecycleState: BackendLifecycleState,
    val baseUrl: String? = null,
    val pid: Long? = null,
    val port: Int? = null,
    val lastError: String? = null,
    val managed: Boolean = true
)

class CodexAppServerManager {
    private data class ManagedProcess(
        val process: Process,
        val baseUrl: String,
        val port: Int,
        val startedAt: Long
    )

    private val processes = ConcurrentHashMap<String, ManagedProcess>()
    private val failures = ConcurrentHashMap<String, String>()

    fun executableAvailable(config: BackendConnectionConfig): Boolean {
        if (config.kind != ExecutionBackendKind.CODEX_APP_SERVER) return false
        if (config.launchMode == BackendLaunchMode.ATTACHED) return !config.baseUrl.isNullOrBlank()
        return resolveExecutablePath(config.command).let { it != null }
    }

    fun status(companyId: String, config: BackendConnectionConfig): ManagedCodexServerStatus {
        if (config.kind != ExecutionBackendKind.CODEX_APP_SERVER) {
            return ManagedCodexServerStatus(
                companyId = companyId,
                lifecycleState = BackendLifecycleState.STOPPED,
                managed = false
            )
        }
        if (config.launchMode == BackendLaunchMode.ATTACHED) {
            val baseUrl = config.baseUrl?.trim()?.takeIf { it.isNotBlank() }
            return ManagedCodexServerStatus(
                companyId = companyId,
                lifecycleState = if (baseUrl != null) BackendLifecycleState.ATTACHED else BackendLifecycleState.FAILED,
                baseUrl = baseUrl,
                port = baseUrl?.let(::portFromUrl),
                lastError = if (baseUrl == null) "Attached Codex app server URL is not configured." else null,
                managed = false
            )
        }
        val process = processes[companyId]
        return when {
            process == null -> ManagedCodexServerStatus(
                companyId = companyId,
                lifecycleState = BackendLifecycleState.STOPPED,
                lastError = failures[companyId]
            )
            !process.process.isAlive -> ManagedCodexServerStatus(
                companyId = companyId,
                lifecycleState = BackendLifecycleState.FAILED,
                baseUrl = process.baseUrl,
                pid = process.process.pid(),
                port = process.port,
                lastError = failures[companyId] ?: "Managed Codex app server exited."
            )
            else -> ManagedCodexServerStatus(
                companyId = companyId,
                lifecycleState = BackendLifecycleState.RUNNING,
                baseUrl = process.baseUrl,
                pid = process.process.pid(),
                port = process.port
            )
        }
    }

    fun ensureStarted(companyId: String, config: BackendConnectionConfig): ManagedCodexServerStatus {
        if (config.kind != ExecutionBackendKind.CODEX_APP_SERVER) {
            return ManagedCodexServerStatus(companyId = companyId, lifecycleState = BackendLifecycleState.STOPPED)
        }
        if (config.launchMode == BackendLaunchMode.ATTACHED) {
            return status(companyId, config)
        }
        synchronized(this) {
            val existing = processes[companyId]
            if (existing != null && existing.process.isAlive) {
                return ManagedCodexServerStatus(
                    companyId = companyId,
                    lifecycleState = BackendLifecycleState.RUNNING,
                    baseUrl = existing.baseUrl,
                    pid = existing.process.pid(),
                    port = existing.port
                )
            }
            stop(companyId)
            val executable = resolveExecutablePath(config.command)
                ?: return rememberFailure(companyId, "Unable to find executable: ${config.command}")
            val port = config.port ?: reservePort()
            val command = listOf(executable.toString()) + config.args.map { argument ->
                argument.replace("{port}", port.toString())
            }
            val builder = ProcessBuilder(command)
            val workingDirectory = config.workingDirectory?.takeIf { it.isNotBlank() }?.let { Path.of(it).toFile() }
            if (workingDirectory != null) {
                builder.directory(workingDirectory)
            }
            val environment = builder.environment()
            val inheritedPath = environment["PATH"]
            val executableParent = executable.parent?.toString()
            if (!executableParent.isNullOrBlank()) {
                environment["PATH"] = listOfNotNull(executableParent, inheritedPath)
                    .flatMap { it.split(java.io.File.pathSeparator) }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(java.io.File.pathSeparator)
            }
            return try {
                val process = builder.start()
                val baseUrl = "http://127.0.0.1:$port"
                val managed = ManagedProcess(
                    process = process,
                    baseUrl = baseUrl,
                    port = port,
                    startedAt = System.currentTimeMillis()
                )
                processes[companyId] = managed
                failures.remove(companyId)
                waitForHealth(companyId, managed, config)
            } catch (error: Exception) {
                rememberFailure(companyId, error.message ?: "Failed to start managed Codex app server.")
            }
        }
    }

    fun restart(companyId: String, config: BackendConnectionConfig): ManagedCodexServerStatus {
        stop(companyId)
        return ensureStarted(companyId, config)
    }

    fun stop(companyId: String) {
        synchronized(this) {
            val process = processes.remove(companyId)?.process ?: return
            process.destroy()
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    fun stopAll() {
        synchronized(this) {
            val ids = processes.keys.toList()
            ids.forEach { stop(it) }
        }
    }

    private fun waitForHealth(
        companyId: String,
        managed: ManagedProcess,
        config: BackendConnectionConfig
    ): ManagedCodexServerStatus {
        val deadline = System.currentTimeMillis() + Duration.ofSeconds(config.startupTimeoutSeconds.toLong()).toMillis()
        val healthUrl = managed.baseUrl + config.healthCheckPath
        while (System.currentTimeMillis() <= deadline) {
            if (!managed.process.isAlive) {
                return rememberFailure(companyId, "Managed Codex app server exited before becoming healthy.")
            }
            if (isHealthy(healthUrl, config.timeoutSeconds)) {
                return ManagedCodexServerStatus(
                    companyId = companyId,
                    lifecycleState = BackendLifecycleState.RUNNING,
                    baseUrl = managed.baseUrl,
                    pid = managed.process.pid(),
                    port = managed.port
                )
            }
            Thread.sleep(250)
        }
        stop(companyId)
        return rememberFailure(companyId, "Timed out waiting for Codex app server health check.")
    }

    private fun isHealthy(url: String, timeoutSeconds: Int): Boolean {
        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutSeconds * 1000
            connection.readTimeout = timeoutSeconds * 1000
            connection.connect()
            connection.responseCode in 200..299
        }.getOrDefault(false)
    }

    private fun rememberFailure(companyId: String, message: String): ManagedCodexServerStatus {
        failures[companyId] = message
        return ManagedCodexServerStatus(
            companyId = companyId,
            lifecycleState = BackendLifecycleState.FAILED,
            lastError = message
        )
    }

    private fun reservePort(): Int = ServerSocket(0).use { it.localPort }

    private fun portFromUrl(url: String): Int? = runCatching { URL(url).port.takeIf { it > 0 } ?: URL(url).defaultPort }.getOrNull()
}
