package com.cotor.data.process

/**
 * File overview for ProcessManager.
 *
 * This file belongs to the process execution layer that launches and supervises external commands.
 * It groups declarations around process manager so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.model.ProcessResult
import kotlinx.coroutines.*
import org.slf4j.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlin.io.path.isExecutable

/**
 * Interface for managing external process execution
 */
interface ProcessManager {
    /**
     * Execute external process with coroutines
     * @param command Command and arguments to execute
     * @param input Optional input data to send to stdin
     * @param environment Environment variables
     * @param timeout Timeout in milliseconds
     * @param workingDirectory Optional working directory for the child process
     * @return ProcessResult containing exit code and output
     */
    suspend fun executeProcess(
        command: List<String>,
        input: String?,
        environment: Map<String, String>,
        timeout: Long,
        workingDirectory: Path? = null,
        onStart: ((Long) -> Unit)? = null
    ): ProcessResult
}

/**
 * Coroutine-based process manager implementation
 */
class CoroutineProcessManager(
    private val logger: Logger
) : ProcessManager {

    override suspend fun executeProcess(
        command: List<String>,
        input: String?,
        environment: Map<String, String>,
        timeout: Long,
        workingDirectory: Path?,
        onStart: ((Long) -> Unit)?
    ): ProcessResult = withContext(Dispatchers.IO) {
        val resolvedCommand = resolveProcessCommand(command)
        val processBuilder = ProcessBuilder(resolvedCommand)
            .redirectErrorStream(false)

        workingDirectory?.let {
            // Every desktop agent run points this at its isolated worktree so file writes
            // from different agents never collide in the same checkout.
            processBuilder.directory(it.toFile())
        }

        // Set environment variables
        processBuilder.environment().putAll(environment)
        val resolvedExecutable = resolvedCommand.firstOrNull()?.let { runCatching { Path.of(it) }.getOrNull() }
        val effectivePath = buildEffectivePath(
            inheritedPath = processBuilder.environment()["PATH"],
            overridePath = environment["PATH"],
            resolvedExecutable = resolvedExecutable
        )
        if (effectivePath.isNotBlank()) {
            processBuilder.environment()["PATH"] = effectivePath
        }

        logger.debug("Starting process: ${redactedCommandForLogs(resolvedCommand)}")
        val process = processBuilder.start()
        onStart?.invoke(process.pid())
        logger.debug("Started process pid=${process.pid()} cwd=${workingDirectory ?: Path.of("").toAbsolutePath().normalize()} command=${redactedCommandForLogs(resolvedCommand)}")

        val stdoutBuffer = StringBuffer()
        val stderrBuffer = StringBuffer()

        val stdoutThread = thread(
            start = true,
            isDaemon = true,
            name = "cotor-process-stdout-${process.pid()}"
        ) {
            process.inputStream.bufferedReader().use { reader ->
                val chunk = CharArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    synchronized(stdoutBuffer) {
                        stdoutBuffer.append(chunk, 0, read)
                    }
                }
            }
        }
        val stderrThread = thread(
            start = true,
            isDaemon = true,
            name = "cotor-process-stderr-${process.pid()}"
        ) {
            process.errorStream.bufferedReader().use { reader ->
                val chunk = CharArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    synchronized(stderrBuffer) {
                        stderrBuffer.append(chunk, 0, read)
                    }
                }
            }
        }

        try {
            if (input != null) {
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(input)
                    writer.flush()
                }
            } else {
                process.outputStream.close()
            }

            withTimeout(timeout) {
                while (true) {
                    if (process.waitFor(50, TimeUnit.MILLISECONDS)) {
                        return@withTimeout
                    }
                    yield()
                }
            }
            joinReader(stdoutThread)
            joinReader(stderrThread)
            val exitCode = process.exitValue()
            val stdout = synchronized(stdoutBuffer) { stdoutBuffer.toString() }
            val stderr = synchronized(stderrBuffer) { stderrBuffer.toString() }

            logger.debug("Process completed with exit code: $exitCode")

            ProcessResult(
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                isSuccess = exitCode == 0,
                processId = process.pid()
            )
        } catch (e: TimeoutCancellationException) {
            logger.warn("Process timeout, destroying process")
            // Force-kill on timeout because some developer tools spawn interactive shells
            // that ignore polite termination and would otherwise leak in the background.
            process.destroyForcibly()
            joinReader(stdoutThread)
            joinReader(stderrThread)
            throw e
        } catch (e: Exception) {
            logger.error("Process execution failed", e)
            process.destroyForcibly()
            joinReader(stdoutThread)
            joinReader(stderrThread)
            throw e
        }
    }
}

private fun redactedCommandForLogs(command: List<String>): String {
    if (command.isEmpty()) return "<empty>"
    return buildString {
        append(command.first())
        if (command.size > 1) {
            append(" [")
            append(command.size - 1)
            append(" args redacted]")
        }
    }
}

private fun joinReader(thread: Thread) {
    thread.join(READER_JOIN_TIMEOUT_MS)
}

private const val READER_JOIN_TIMEOUT_MS = 250L

private fun buildEffectivePath(
    inheritedPath: String?,
    overridePath: String?,
    resolvedExecutable: Path?
): String {
    val entries = linkedSetOf<String>()

    fun addPathEntries(raw: String?) {
        raw.orEmpty()
            .split(File.pathSeparator)
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() }
            .forEach(entries::add)
    }

    val home = effectiveUserHome().orEmpty()
    resolvedExecutable?.parent?.toString()?.let(entries::add)
    addPathEntries(overridePath)
    if (home.isNotBlank()) {
        listOf(
            "$home/.local/bin",
            "$home/.opencode/bin",
            "$home/bin",
            "$home/.npm-global/bin",
            "$home/.yarn/bin",
            "$home/.foundry/bin",
            "/Applications/Codex.app/Contents/Resources"
        ).forEach(entries::add)
    }
    addPathEntries(inheritedPath)
    addPathEntries(System.getenv("PATH"))
    listOf(
        "/opt/homebrew/bin",
        "/opt/homebrew/sbin",
        "/usr/local/bin",
        "/usr/local/sbin",
        "/usr/bin",
        "/bin",
        "/usr/sbin",
        "/sbin"
    ).forEach(entries::add)

    return entries.joinToString(File.pathSeparator)
}

internal fun effectiveUserHome(
    environment: Map<String, String> = System.getenv(),
    systemHome: String? = System.getProperty("user.home")
): String? {
    return environment["HOME"]
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: systemHome?.trim()?.takeIf { it.isNotBlank() }
}

fun resolveExecutablePath(
    command: String,
    environment: Map<String, String> = System.getenv(),
    systemHome: String? = System.getProperty("user.home")
): Path? {
    val normalized = command.trim().trim('"')
    if (normalized.isBlank()) {
        return null
    }

    val directPath = runCatching { Path.of(normalized) }.getOrNull()
    if (directPath != null && (normalized.contains("/") || normalized.startsWith("."))) {
        val absolute = directPath.toAbsolutePath().normalize()
        if (absolute.exists() && absolute.isExecutable()) {
            return absolute
        }
    }

    val searchDirectories = buildList {
        val home = effectiveUserHome(environment, systemHome).orEmpty()
        if (normalized == "opencode" && home.isNotBlank()) {
            add("$home/.opencode/bin")
        }
        val rawPath = environment["PATH"].orEmpty()
        rawPath.split(java.io.File.pathSeparator)
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() }
            .forEach { add(it) }

        if (home.isNotBlank()) {
            add("$home/.local/bin")
            add("$home/.opencode/bin")
            add("$home/bin")
        }
        add("/opt/homebrew/bin")
        add("/usr/local/bin")
        add("/usr/bin")
        add("/bin")
        add("/usr/sbin")
        add("/sbin")
    }.distinct()

    return searchDirectories.firstNotNullOfOrNull { directory ->
        val candidate = runCatching { Path.of(directory, normalized).toAbsolutePath().normalize() }.getOrNull()
        if (candidate != null && candidate.exists() && candidate.isExecutable() && !Files.isDirectory(candidate)) {
            candidate
        } else {
            null
        }
    }
}

private fun resolveProcessCommand(command: List<String>): List<String> {
    if (command.isEmpty()) {
        return command
    }
    val executable = resolveExecutablePath(command.first())?.toString() ?: command.first()
    return listOf(executable) + command.drop(1)
}
