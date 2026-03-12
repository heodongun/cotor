package com.cotor.data.process

import com.cotor.model.ProcessResult
import kotlinx.coroutines.*
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
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

        logger.debug("Starting process: ${resolvedCommand.joinToString(" ")}")
        val process = processBuilder.start()
        onStart?.invoke(process.pid())

        try {
            withTimeout(timeout) {
                coroutineScope {
                    // stdin/stdout/stderr are handled concurrently so we do not deadlock
                    // when a child process produces a lot of output while also waiting for input.
                    val stdinJob = launch {
                        if (input != null) {
                            process.outputStream.bufferedWriter().use { writer ->
                                writer.write(input)
                                writer.flush()
                            }
                        }
                        process.outputStream.close()
                    }

                    // Read from stdout
                    val stdoutDeferred = async {
                        process.inputStream.bufferedReader().use { reader ->
                            reader.readText()
                        }
                    }

                    // Read from stderr
                    val stderrDeferred = async {
                        process.errorStream.bufferedReader().use { reader ->
                            reader.readText()
                        }
                    }

                    // Wait for process to complete
                    val exitCodeDeferred = async {
                        process.waitFor()
                    }

                    // Ensure stdin is fully written before awaiting process completion so
                    // CLIs that block on EOF can actually start processing.
                    stdinJob.join()
                    val stdout = stdoutDeferred.await()
                    val stderr = stderrDeferred.await()
                    val exitCode = exitCodeDeferred.await()

                    logger.debug("Process completed with exit code: $exitCode")

                    ProcessResult(
                        exitCode = exitCode,
                        stdout = stdout,
                        stderr = stderr,
                        isSuccess = exitCode == 0,
                        processId = process.pid()
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn("Process timeout, destroying process")
            // Force-kill on timeout because some developer tools spawn interactive shells
            // that ignore polite termination and would otherwise leak in the background.
            process.destroyForcibly()
            throw e
        } catch (e: Exception) {
            logger.error("Process execution failed", e)
            process.destroyForcibly()
            throw e
        }
    }
}

fun resolveExecutablePath(command: String): Path? {
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
        val rawPath = System.getenv("PATH").orEmpty()
        rawPath.split(java.io.File.pathSeparator)
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() }
            .forEach { add(it) }

        val home = System.getProperty("user.home").orEmpty()
        add("/opt/homebrew/bin")
        add("/usr/local/bin")
        add("/usr/bin")
        add("/bin")
        add("/usr/sbin")
        add("/sbin")
        if (home.isNotBlank()) {
            add("$home/.local/bin")
            add("$home/bin")
        }
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
